package com.autovless.app.core

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.autovless.app.util.DiagnosticsLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Locale

class LibboxRuntime(private val context: Context) : Closeable {
    private var service: Any? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val seenPlatformMethods = Collections.synchronizedSet(mutableSetOf<String>())

    fun isAvailable(): Boolean = isLibboxAvailable()

    fun startStandalone(configContent: String) {
        val preparedConfig = sanitizeSingBoxConfigForCurrentLibbox(configContent)
        DiagnosticsLogger.log(context, "LibboxRuntime", "startStandalone configChars=${preparedConfig.length}")
        close()
        setupIfPresent()
        val libbox = libboxClass()
        DiagnosticsLogger.log(context, "LibboxRuntime", "Using libbox class=${libbox.name}")
        val platformInterface = createStandalonePlatformInterface()
        service = createRuntimeService(preparedConfig, platformInterface)
        DiagnosticsLogger.log(context, "LibboxRuntime", "runtime service started: ${service!!.javaClass.name}")
    }

    fun startVpn(vpnService: VpnService, configContent: String) {
        val preparedConfig = sanitizeSingBoxConfigForCurrentLibbox(configContent)
        DiagnosticsLogger.log(context, "LibboxRuntime", "startVpn configChars=${preparedConfig.length}")
        DiagnosticsLogger.log(context, "LibboxRuntime", "startVpn redactedConfig=${redactConfigForLog(preparedConfig).take(7000)}")
        close()
        setupIfPresent()
        val libbox = libboxClass()
        DiagnosticsLogger.log(context, "LibboxRuntime", "Using libbox class=${libbox.name}")
        val platformInterface = createPlatformInterface(vpnService)
        service = createRuntimeService(preparedConfig, platformInterface)
        DiagnosticsLogger.log(context, "LibboxRuntime", "runtime VPN service started: ${service!!.javaClass.name}")
    }

    /**
     * sing-box 1.13 removed outbound { "type": "dns" }. Older generated configs
     * or stale APK builds can still pass it here, so sanitize right before libbox
     * starts. This is the last safety net before CommandServer decodes the config.
     */
    private fun sanitizeSingBoxConfigForCurrentLibbox(configContent: String): String {
        return try {
            val root = JSONObject(configContent)
            var removedDnsOutbounds = 0
            var convertedDnsRules = 0
            var convertedDnsServers = 0
            var fixedVpnDns = false
            var addedPort53HijackRule = false
            var migratedLegacyInboundFields = 0
            var addedSniffRule = false
            var fixedTunInbound = false

            val inbounds = root.optJSONArray("inbounds")
            var isVpnConfig = false
            var tunInboundTag = "tun-in"

            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inbound = inbounds.optJSONObject(i) ?: continue
                    if (inbound.optString("type").equals("tun", ignoreCase = true)) {
                        isVpnConfig = true
                        val tag = inbound.optString("tag")
                        if (tag.isNotBlank()) tunInboundTag = tag

                        inbound.put("address", JSONArray().put(SingBoxConfigGenerator.TUN_ADDRESS))
                        inbound.put("stack", "mixed")
                        inbound.put("auto_route", false)
                        inbound.put("strict_route", false)
                        fixedTunInbound = true
                    }
                }
            }

            root.optJSONArray("outbounds")?.let { outbounds ->
                var index = outbounds.length() - 1
                while (index >= 0) {
                    val outbound = outbounds.optJSONObject(index)
                    if (outbound != null && outbound.optString("type").equals("dns", ignoreCase = true)) {
                        outbounds.remove(index)
                        removedDnsOutbounds++
                    }
                    index--
                }
            }

            if (isVpnConfig) {
                val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
                val servers = JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "tcp")
                            .put("tag", "google-tcp")
                            .put("server", SingBoxConfigGenerator.PRIMARY_DNS)
                            .put("server_port", 53)
                            .put("detour", "selected")
                    )
                    .put(
                        JSONObject()
                            .put("type", "tcp")
                            .put("tag", "cloudflare-tcp")
                            .put("server", SingBoxConfigGenerator.SECONDARY_DNS)
                            .put("server_port", 53)
                            .put("detour", "selected")
                    )
                dns.put("servers", servers)
                dns.put("final", "google-tcp")
                dns.put("strategy", "ipv4_only")
                fixedVpnDns = true
            }

            root.optJSONObject("dns")?.optJSONArray("servers")?.let { servers ->
                for (i in 0 until servers.length()) {
                    val server = servers.optJSONObject(i) ?: continue
                    val address = server.optString("address")
                    if (address.startsWith("tcp://", ignoreCase = true)) {
                        val raw = address.substringAfter("tcp://")
                        val host = raw.substringBefore(":")
                        val port = raw.substringAfter(":", "53").toIntOrNull() ?: 53
                        server.remove("address")
                        server.put("type", "tcp")
                        server.put("server", host)
                        server.put("server_port", port)
                        convertedDnsServers++
                    }
                }
            }

            val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
            var rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
            val preRules = JSONArray()

            var hasDnsHijackRule = false
            var hasPort53HijackRule = false
            var hasSniffRule = false
            var hasUdp443RejectRule = false

            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                val protocol = rule.optString("protocol")
                val outbound = rule.optString("outbound")
                val action = rule.optString("action")

                if (action.equals("sniff", ignoreCase = true)) {
                    hasSniffRule = true
                }
                if (rule.optString("network").equals("udp", ignoreCase = true) && rule.optInt("port", -1) == 443 && action.equals("reject", ignoreCase = true)) {
                    hasUdp443RejectRule = true
                }
                if (protocol.equals("dns", ignoreCase = true) && action.equals("hijack-dns", ignoreCase = true)) {
                    hasDnsHijackRule = true
                }
                if (rule.optInt("port", -1) == 53 && action.equals("hijack-dns", ignoreCase = true)) {
                    hasPort53HijackRule = true
                }

                if (protocol.equals("dns", ignoreCase = true) && outbound.equals("dns-out", ignoreCase = true)) {
                    rule.remove("outbound")
                    rule.put("action", "hijack-dns")
                    hasDnsHijackRule = true
                    convertedDnsRules++
                }
            }

            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inbound = inbounds.optJSONObject(i) ?: continue
                    val hadLegacyInboundFields = inbound.has("sniff") ||
                        inbound.has("sniff_override_destination") ||
                        inbound.has("sniff_timeout") ||
                        inbound.has("domain_strategy")
                    if (!hadLegacyInboundFields) continue

                    var inboundTag = inbound.optString("tag")
                    if (inboundTag.isBlank()) {
                        inboundTag = "in-$i"
                        inbound.put("tag", inboundTag)
                    }

                    val sniffEnabled = !inbound.has("sniff") || inbound.optBoolean("sniff", false)
                    val sniffTimeout = inbound.optString("sniff_timeout")
                    val domainStrategy = inbound.optString("domain_strategy")

                    inbound.remove("sniff")
                    inbound.remove("sniff_override_destination")
                    inbound.remove("sniff_timeout")
                    inbound.remove("domain_strategy")
                    migratedLegacyInboundFields++

                    if (domainStrategy.isNotBlank()) {
                        preRules.put(
                            JSONObject()
                                .put("inbound", inboundTag)
                                .put("action", "resolve")
                                .put("strategy", domainStrategy)
                        )
                    }
                    if (sniffEnabled && !hasSniffRule) {
                        val sniffRule = JSONObject()
                            .put("inbound", inboundTag)
                            .put("action", "sniff")
                        if (sniffTimeout.isNotBlank()) sniffRule.put("timeout", sniffTimeout)
                        preRules.put(sniffRule)
                        hasSniffRule = true
                        addedSniffRule = true
                    }
                }
            }

            if (isVpnConfig && !hasSniffRule) {
                preRules.put(
                    JSONObject()
                        .put("inbound", tunInboundTag)
                        .put("action", "sniff")
                        .put("timeout", "300ms")
                )
                hasSniffRule = true
                addedSniffRule = true
            }

            if (preRules.length() > 0) {
                val mergedRules = JSONArray()
                for (i in 0 until preRules.length()) mergedRules.put(preRules.get(i))
                for (i in 0 until rules.length()) mergedRules.put(rules.get(i))
                route.put("rules", mergedRules)
                rules = mergedRules
            }

            if (isVpnConfig && !hasUdp443RejectRule) {
                rules.put(
                    JSONObject()
                        .put("inbound", tunInboundTag)
                        .put("network", "udp")
                        .put("port", 443)
                        .put("action", "reject")
                )
            }

            if (isVpnConfig && !hasDnsHijackRule) {
                rules.put(
                    JSONObject()
                        .put("inbound", tunInboundTag)
                        .put("protocol", "dns")
                        .put("action", "hijack-dns")
                )
                convertedDnsRules++
            }
            if (isVpnConfig) {
                route.put("override_android_vpn", false)
            }

            if (isVpnConfig && !hasPort53HijackRule) {
                rules.put(
                    JSONObject()
                        .put("inbound", tunInboundTag)
                        .put("port", 53)
                        .put("action", "hijack-dns")
                )
                addedPort53HijackRule = true
            }
            if (removedDnsOutbounds > 0 && !hasDnsHijackRule && !isVpnConfig) {
                rules.put(
                    JSONObject()
                        .put("protocol", "dns")
                        .put("action", "hijack-dns")
                )
                convertedDnsRules++
            }

            if (
                removedDnsOutbounds > 0 ||
                convertedDnsRules > 0 ||
                convertedDnsServers > 0 ||
                fixedVpnDns ||
                addedPort53HijackRule ||
                migratedLegacyInboundFields > 0 ||
                addedSniffRule ||
                fixedTunInbound
            ) {
                DiagnosticsLogger.log(
                    context,
                    "LibboxRuntime",
                    "sanitize sing-box 1.13 config: removedDnsOutbounds=$removedDnsOutbounds convertedDnsRules=$convertedDnsRules convertedDnsServers=$convertedDnsServers fixedVpnDns=$fixedVpnDns addedPort53HijackRule=$addedPort53HijackRule migratedLegacyInboundFields=$migratedLegacyInboundFields addedSniffRule=$addedSniffRule fixedTunInbound=$fixedTunInbound"
                )
            } else {
                DiagnosticsLogger.log(context, "LibboxRuntime", "sanitize sing-box 1.13 config: no deprecated fields")
            }

            root.toString(2)
        } catch (e: Throwable) {
            DiagnosticsLogger.log(context, "LibboxRuntime", "sanitize skipped: ${rootMessage(e)}")
            configContent
        }
    }

    private fun redactConfigForLog(configContent: String): String {
        return try {
            val root = JSONObject(configContent)
            root.optJSONArray("outbounds")?.let { outbounds ->
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i) ?: continue
                    if (outbound.has("uuid")) outbound.put("uuid", "***")
                    if (outbound.has("password")) outbound.put("password", "***")
                    outbound.optJSONObject("tls")?.let { tls ->
                        tls.optJSONObject("reality")?.let { reality ->
                            if (reality.has("public_key")) reality.put("public_key", "***")
                            if (reality.has("short_id")) reality.put("short_id", "***")
                        }
                    }
                }
            }
            root.toString(2)
        } catch (_: Throwable) {
            "<config redaction failed>"
        }
    }

    private fun createRuntimeService(configContent: String, platformInterface: Any): Any {
        // Prefer the old direct BoxService API when it exists. On this MVP it is safer for
        // one-shot local mixed-proxy checks: a native crash in CommandServer takes down
        // the Activity process before Kotlin can catch anything.
        val newServiceResult = runCatching {
            val oldService = createBoxService(configContent, platformInterface)
            startBoxService(oldService)
            oldService
        }
        if (newServiceResult.isSuccess) {
            DiagnosticsLogger.log(context, "LibboxRuntime", "Using direct NewService runtime")
            return newServiceResult.getOrThrow()
        }
        DiagnosticsLogger.log(context, "LibboxRuntime", "Direct NewService unavailable: ${rootMessage(newServiceResult.exceptionOrNull() ?: IllegalStateException("unknown"))}")

        val commandResult = runCatching { createCommandServerService(configContent, platformInterface) }
        if (commandResult.isSuccess) {
            DiagnosticsLogger.log(context, "LibboxRuntime", "Using CommandServer runtime")
            return commandResult.getOrThrow()
        }

        val newServiceMsg = rootMessage(newServiceResult.exceptionOrNull() ?: IllegalStateException("unknown"))
        val commandMsg = rootMessage(commandResult.exceptionOrNull() ?: IllegalStateException("unknown"))
        throw IllegalStateException(
            "libbox service не стартует. NewService: $newServiceMsg | CommandServer fallback: $commandMsg"
        )
    }

    /**
     * sing-box 1.13.x Android AAR no longer exposes Libbox.newService() in the Java API.
     * Official SFA starts the core through CommandServer.startOrReloadService().
     */
    private fun createCommandServerService(configContent: String, platformInterface: Any): Any {
        val binding = binding()
        val handler = createCommandServerHandler()
        val server = newCommandServer(handler, platformInterface)
        DiagnosticsLogger.log(context, "LibboxRuntime", "CommandServer created: ${server.javaClass.name}")

        val start = findMethod(server.javaClass, "start", 0)
            ?: throw IllegalStateException("CommandServer.start не найден. Методы: ${methodNames(server.javaClass)}")
        invokeUnwrapped(start, server)
        DiagnosticsLogger.log(context, "LibboxRuntime", "CommandServer.start OK")

        val overrideOptions = createOverrideOptions(binding.libboxClass.`package`?.name)
        val startOrReload = findMethod(server.javaClass, "startOrReloadService", 2)
            ?: throw IllegalStateException("CommandServer.startOrReloadService не найден. Методы: ${methodNames(server.javaClass)}")
        invokeUnwrapped(startOrReload, server, configContent, overrideOptions)
        DiagnosticsLogger.log(context, "LibboxRuntime", "CommandServer.startOrReloadService OK")
        return server
    }

    private fun newCommandServer(handler: Any, platformInterface: Any): Any {
        val binding = binding()
        val candidates = linkedSetOf<Class<*>>()
        classOrNull(binding.libboxClass.`package`?.name + ".CommandServer")?.let { candidates += it }
        classOrNull("io.nekohasekai.libbox.CommandServer")?.let { candidates += it }
        classOrNull("libbox.CommandServer")?.let { candidates += it }

        val errors = mutableListOf<String>()
        for (clazz in candidates) {
            val ctor = clazz.constructors.firstOrNull { it.parameterTypes.size == 2 }
            if (ctor != null) {
                val result = runCatching { ctor.newInstance(handler, platformInterface) }
                if (result.isSuccess) return result.getOrThrow()
                errors += "${clazz.name}.<init>: ${rootMessage(result.exceptionOrNull()!!)}"
            }
        }

        val method = findMethod(binding.libboxClass, "newCommandServer", 2)
            ?: findMethod(binding.libboxClass, "NewCommandServer", 2)
            ?: throw IllegalStateException(
                "newCommandServer не найден. Libbox=${binding.libboxClass.name}. Методы: ${methodNames(binding.libboxClass)}. " +
                    errors.joinToString(" | ").take(600)
            )
        val result = runCatching { invokeUnwrapped(method, null, handler, platformInterface) }
        if (result.isSuccess) {
            return result.getOrThrow()
                ?: throw IllegalStateException("${binding.libboxClass.name}.${method.name} вернул null")
        }
        throw IllegalStateException("newCommandServer failed: ${rootMessage(result.exceptionOrNull()!!)}")
    }

    private fun createOverrideOptions(packageName: String?): Any? {
        val clazz = classOrNull(packageName + ".OverrideOptions")
            ?: classOrNull("io.nekohasekai.libbox.OverrideOptions")
            ?: classOrNull("libbox.OverrideOptions")
            ?: return null
        val options = clazz.getDeclaredConstructor().newInstance()
        setFieldIfPresent(options, "autoRedirect", false)
        setFieldIfPresent(options, "includePackage", null)
        setFieldIfPresent(options, "excludePackage", null)
        return options
    }

    private fun createCommandServerHandler(): Any {
        val binding = binding()
        val handlerClass = classOrNull(binding.libboxClass.`package`?.name + ".CommandServerHandler")
            ?: classOrNull("io.nekohasekai.libbox.CommandServerHandler")
            ?: classOrNull("libbox.CommandServerHandler")
            ?: throw IllegalStateException("CommandServerHandler class не найден")

        return Proxy.newProxyInstance(
            handlerClass.classLoader,
            arrayOf(handlerClass)
        ) { _, method, args ->
            when (method.name) {
                "serviceStop", "ServiceStop" -> {
                    DiagnosticsLogger.log(context, "LibboxRuntime", "CommandServerHandler.serviceStop")
                    null
                }
                "serviceReload", "ServiceReload" -> {
                    DiagnosticsLogger.log(context, "LibboxRuntime", "CommandServerHandler.serviceReload ignored")
                    null
                }
                "getSystemProxyStatus", "GetSystemProxyStatus" -> createSystemProxyStatus(method.returnType)
                "setSystemProxyEnabled", "SetSystemProxyEnabled" -> null
                "writeDebugMessage", "WriteDebugMessage" -> {
                    val text = args?.firstOrNull()?.toString().orEmpty()
                    if (text.isNotBlank()) DiagnosticsLogger.log(context, "libbox-debug", text)
                    null
                }
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun createSystemProxyStatus(returnType: Class<*>): Any? {
        if (returnType == java.lang.Void.TYPE || returnType == Void::class.java) return null
        if (returnType == Any::class.java) return null
        return runCatching {
            val status = returnType.getDeclaredConstructor().newInstance()
            setFieldIfPresent(status, "available", false)
            setFieldIfPresent(status, "enabled", false)
            status
        }.getOrNull()
    }

    private fun startBoxService(boxService: Any) {
        val start = findMethod(boxService.javaClass, "start", 0)
            ?: throw IllegalStateException("BoxService.start не найден. Методы: ${methodNames(boxService.javaClass)}")
        invokeUnwrapped(start, boxService)
        DiagnosticsLogger.log(context, "LibboxRuntime", "BoxService.start OK")
    }

    private fun redirectStderrIfPresent(libbox: Class<*>) {
        val redirect = findMethod(libbox, "redirectStderr", 1) ?: return
        val file = java.io.File(context.filesDir, "libbox-stderr.log")
        runCatching {
            invokeUnwrapped(redirect, null, file.absolutePath)
            DiagnosticsLogger.log(context, "LibboxRuntime", "redirectStderr OK: ${file.absolutePath}")
        }.onFailure {
            DiagnosticsLogger.log(context, "LibboxRuntime", "redirectStderr ignored: ${rootMessage(it)}")
        }
    }

    private fun setupIfPresent() {
        val libbox = libboxClass()
        val base = context.filesDir.absolutePath
        val working = context.filesDir.absolutePath
        val temp = context.cacheDir.absolutePath

        val setup4 = findMethod(libbox, "setup", 4)
        if (setup4 != null) {
            invokeUnwrapped(setup4, null, base, temp, android.os.Process.myUid(), android.os.Process.myUid())
            DiagnosticsLogger.log(context, "LibboxRuntime", "Setup OK: legacy setup(base,temp,uid,gid)")
            redirectStderrIfPresent(libbox)
            return
        }

        val setup1 = findMethod(libbox, "setup", 1)
        if (setup1 != null) {
            val optionsClass = setup1.parameterTypes.first()
            val options = optionsClass.getDeclaredConstructor().newInstance()
            setFieldIfPresent(options, "basePath", base)
            setFieldIfPresent(options, "workingPath", working)
            setFieldIfPresent(options, "tempPath", temp)
            setFieldIfPresent(options, "fixAndroidStack", true)
            setFieldIfPresent(options, "commandServerListenPort", 0)
            setFieldIfPresent(options, "commandServerSecret", "")
            setFieldIfPresent(options, "logMaxLines", 500)
            setFieldIfPresent(options, "debug", false)
            invokeUnwrapped(setup1, null, options)
            DiagnosticsLogger.log(context, "LibboxRuntime", "Setup OK: setup(${optionsClass.name})")
            redirectStderrIfPresent(libbox)
            return
        }

        DiagnosticsLogger.log(context, "LibboxRuntime", "Setup skipped: setup method not found in ${libbox.name}")
        redirectStderrIfPresent(libbox)
    }

    private fun createBoxService(configContent: String, platformInterface: Any): Any {
        val binding = binding()
        val classesToTry = linkedSetOf<Class<*>>()
        classesToTry += binding.libboxClass
        classOrNull(binding.libboxClass.`package`?.name + ".BoxService")?.let { classesToTry += it }
        classOrNull("libbox.BoxService")?.let { classesToTry += it }
        classOrNull("io.nekohasekai.libbox.BoxService")?.let { classesToTry += it }

        val errors = mutableListOf<String>()
        for (clazz in classesToTry) {
            val method = findMethod(clazz, "newService", 2) ?: findMethod(clazz, "NewService", 2)
            if (method == null) {
                errors += "${clazz.name}: no newService/NewService; methods=${methodNames(clazz)}"
                continue
            }
            val target = if (Modifier.isStatic(method.modifiers)) null else null
            val result = runCatching { invokeUnwrapped(method, target, configContent, platformInterface) }
            if (result.isSuccess) {
                return result.getOrNull() ?: throw IllegalStateException("${clazz.name}.${method.name} вернул null")
            }
            errors += "${clazz.name}.${method.name}: ${rootMessage(result.exceptionOrNull()!!)}"
        }

        throw IllegalStateException(
            "NewService не найден/не запустился. Libbox=${binding.libboxClass.name}. " +
                "Platform=${binding.platformInterfaceClass.name}. " +
                errors.joinToString(" | ").take(1200)
        )
    }

    private fun createStandalonePlatformInterface(): Any {
        val interfaceClass = platformInterfaceClass()
        return Proxy.newProxyInstance(
            interfaceClass.classLoader,
            arrayOf(interfaceClass)
        ) { _, method, args ->
            logPlatformMethodOnce("standalone", method, args)
            when (method.name) {
                "localDNSTransport", "LocalDNSTransport" -> null
                "usePlatformAutoDetectInterfaceControl", "UsePlatformAutoDetectInterfaceControl" -> false
                "autoDetectInterfaceControl", "AutoDetectInterfaceControl" -> null
                "openTun", "OpenTun" -> throw IllegalStateException("openTun вызван в standalone speed-test. Test config не должен содержать tun inbound")
                "writeLog", "WriteLog", "writeDebugMessage", "WriteDebugMessage" -> {
                    val text = args?.joinToString(" ") ?: ""
                    if (text.isNotBlank()) DiagnosticsLogger.log(context, "libbox", text)
                    null
                }
                "useProcFS", "UseProcFS" -> {
                    DiagnosticsLogger.log(context, "LibboxRuntime", "UseProcFS=true standalone")
                    true
                }
                "findConnectionOwner", "FindConnectionOwner" -> {
                    DiagnosticsLogger.log(context, "LibboxRuntime", "FindConnectionOwner called unexpectedly in standalone")
                    safeUnknownUid(method.returnType)
                }
                "packageNameByUid", "PackageNameByUid" -> packageNameByUid(args)
                "uidByPackageName", "UidByPackageName" -> safeUnknownUid(method.returnType)
                "startDefaultInterfaceMonitor", "StartDefaultInterfaceMonitor" -> null
                "closeDefaultInterfaceMonitor", "CloseDefaultInterfaceMonitor" -> null
                "getInterfaces", "GetInterfaces" -> emptyIteratorFor(method.returnType)
                "underNetworkExtension", "UnderNetworkExtension" -> false
                "includeAllNetworks", "IncludeAllNetworks" -> false
                "readWIFIState", "ReadWIFIState" -> null
                "systemCertificates", "SystemCertificates" -> emptyIteratorFor(method.returnType)
                "clearDNSCache", "ClearDNSCache" -> null
                "sendNotification", "SendNotification" -> null
                "startNeighborMonitor", "StartNeighborMonitor" -> null
                "registerMyInterface", "RegisterMyInterface" -> null
                "closeNeighborMonitor", "CloseNeighborMonitor" -> null
                else -> iteratorOrDefault(method.returnType)
            }
        }
    }

    private fun createPlatformInterface(vpnService: VpnService): Any {
        val interfaceClass = platformInterfaceClass()
        return Proxy.newProxyInstance(
            interfaceClass.classLoader,
            arrayOf(interfaceClass)
        ) { _, method, args ->
            logPlatformMethodOnce("vpn", method, args)
            when (method.name) {
                "localDNSTransport", "LocalDNSTransport" -> null
                "usePlatformAutoDetectInterfaceControl", "UsePlatformAutoDetectInterfaceControl" -> {
                    DiagnosticsLogger.log(context, "LibboxRuntime", "UsePlatformAutoDetectInterfaceControl=false vpn; own package is excluded from Android VPN")
                    false
                }
                "autoDetectInterfaceControl", "AutoDetectInterfaceControl", "protect", "Protect" -> {
                    val fd = args?.asSequence()?.mapNotNull { (it as? Number)?.toInt() }?.firstOrNull()
                    if (fd == null) {
                        DiagnosticsLogger.log(context, "LibboxRuntime", "protect called without fd method=${method.name} return=${method.returnType.name} args=${args?.joinToString { it?.javaClass?.name ?: "null" } ?: "none"}")
                        if (method.returnType.isInterface) {
                            DiagnosticsLogger.log(context, "LibboxRuntime", "returning nested protect interface for ${method.returnType.name}")
                            return@newProxyInstance createProtectInterface(vpnService, method.returnType)
                        }
                        return@newProxyInstance defaultValue(method.returnType)
                    }
                    val ok = protectFd(vpnService, fd, method.name)
                    if (method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Boolean::class.java) ok else null
                }
                "openTun", "OpenTun" -> {
                    val tunOptions = args?.firstOrNull()
                    // Do not reflectively call any TunOptions getters here. In the current
                    // libbox AAR some optional fields are nil on the Go side; calling getters
                    // such as GetHTTPProxyBypassDomain() from Kotlin can SIGSEGV the process.
                    DiagnosticsLogger.log(context, "LibboxRuntime", "openTun optionsClass=${tunOptions?.javaClass?.name ?: "null"}")
                    val fd = openTun(vpnService, tunOptions).detachFd()
                    if (method.returnType == java.lang.Long.TYPE || method.returnType == java.lang.Long::class.java) fd.toLong() else fd
                }
                "writeLog", "WriteLog", "writeDebugMessage", "WriteDebugMessage" -> {
                    val text = args?.joinToString(" ") ?: ""
                    if (text.isNotBlank()) DiagnosticsLogger.log(context, "libbox", text)
                    null
                }
                "useProcFS", "UseProcFS" -> {
                    DiagnosticsLogger.log(context, "LibboxRuntime", "UseProcFS=true vpn")
                    true
                }
                "findConnectionOwner", "FindConnectionOwner" -> {
                    DiagnosticsLogger.log(context, "LibboxRuntime", "FindConnectionOwner called unexpectedly in vpn")
                    safeUnknownUid(method.returnType)
                }
                "packageNameByUid", "PackageNameByUid" -> packageNameByUid(args)
                "uidByPackageName", "UidByPackageName" -> safeUnknownUid(method.returnType)
                "usePlatformDefaultInterfaceMonitor", "UsePlatformDefaultInterfaceMonitor" -> false
                "startDefaultInterfaceMonitor", "StartDefaultInterfaceMonitor" -> null
                "closeDefaultInterfaceMonitor", "CloseDefaultInterfaceMonitor" -> null
                "usePlatformInterfaceGetter", "UsePlatformInterfaceGetter" -> false
                "getInterfaces", "GetInterfaces" -> emptyIteratorFor(method.returnType)
                "underNetworkExtension", "UnderNetworkExtension" -> false
                "includeAllNetworks", "IncludeAllNetworks" -> false
                "clearDNSCache", "ClearDNSCache" -> null
                "readWIFIState", "ReadWIFIState" -> null
                "systemCertificates", "SystemCertificates" -> emptyIteratorFor(method.returnType)
                "sendNotification", "SendNotification" -> null
                "startNeighborMonitor", "StartNeighborMonitor" -> null
                "registerMyInterface", "RegisterMyInterface" -> null
                "closeNeighborMonitor", "CloseNeighborMonitor" -> null
                else -> iteratorOrDefault(method.returnType)
            }
        }
    }

    private fun protectFd(vpnService: VpnService, fd: Int, source: String): Boolean {
        val ok = runCatching { vpnService.protect(fd) }.getOrDefault(false)
        DiagnosticsLogger.log(context, "LibboxRuntime", "protect outbound fd=$fd ok=$ok source=$source")
        return ok
    }

    private fun createProtectInterface(vpnService: VpnService, interfaceClass: Class<*>): Any {
        return Proxy.newProxyInstance(
            interfaceClass.classLoader,
            arrayOf(interfaceClass)
        ) { _, method, args ->
            logPlatformMethodOnce("protect-interface", method, args)
            val fd = args?.asSequence()?.mapNotNull { (it as? Number)?.toInt() }?.firstOrNull()
            if (fd != null) {
                val ok = protectFd(vpnService, fd, method.name)
                if (method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Boolean::class.java) ok else null
            } else {
                defaultValue(method.returnType)
            }
        }
    }

    private fun logPlatformMethodOnce(prefix: String, method: Method, args: Array<Any?>?) {
        val key = "$prefix:${method.name}/${method.parameterTypes.size}->${method.returnType.name}"
        if (seenPlatformMethods.add(key)) {
            val argTypes = args?.joinToString(",") { it?.javaClass?.name ?: "null" } ?: "none"
            DiagnosticsLogger.log(context, "LibboxRuntime", "PlatformMethod $key args=$argTypes")
        }
    }

    private fun openTun(vpnService: VpnService, tunOptions: Any?): ParcelFileDescriptor {
        tunFd?.close()
        val builder = vpnService.Builder()
            .setSession("AutoVLESS")
            // 1400 avoids MTU blackholes on Reality/WebSocket/gRPC chains.
            .setMtu(1400)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            // Critical DNS fix: give Android the VPN-side DNS peer, not public DNS.
            // Android sends resolver packets into TUN -> sing-box hijacks port 53 ->
            // sing-box resolves with TCP DNS over the selected VLESS outbound.
            // Using 8.8.8.8/1.1.1.1 here made Android try public DNS directly and
            // the log showed: IP OK, DNS/hostnames FAIL.
            .addDnsServer(SingBoxConfigGenerator.TUN_DNS_ADDRESS)

        runCatching {
            builder.addDisallowedApplication(context.packageName)
            DiagnosticsLogger.log(context, "LibboxRuntime", "Excluded own package from Android VPN: ${context.packageName}")
        }.onFailure {
            DiagnosticsLogger.log(context, "LibboxRuntime", "Could not exclude own package from Android VPN: ${rootMessage(it)}")
        }

        DiagnosticsLogger.log(context, "LibboxRuntime", "Android VPN IPv4-only; own package excluded so core outbound sockets do not loop into TUN.")

        tunFd = builder.establish()
            ?: throw IllegalStateException("Не удалось создать Android TUN-интерфейс")
        DiagnosticsLogger.log(context, "LibboxRuntime", "Android TUN established address=${SingBoxConfigGenerator.TUN_ADDRESS} dns=${SingBoxConfigGenerator.TUN_DNS_ADDRESS} stack=mixed optionsClass=${tunOptions?.javaClass?.name ?: "null"}")
        return tunFd!!
    }


    private fun describeObject(value: Any?): String {
        if (value == null) return "null"
        val clazz = value.javaClass
        val fields = runCatching {
            clazz.fields
                .take(16)
                .joinToString(",") { field ->
                    val fieldValue = runCatching { field.get(value) }.getOrNull()
                    "${field.name}=$fieldValue"
                }
        }.getOrDefault("")
        // Intentionally do not invoke zero-argument methods/getters on libbox objects.
        // Some TunOptions getters crash inside the native Go bridge when optional fields are nil.
        return "${clazz.name}{fields=[$fields]}".take(1200)
    }

    override fun close() {
        DiagnosticsLogger.log(context, "LibboxRuntime", "close()")
        val current = service
        service = null
        if (current != null) {
            runCatching {
                findMethod(current.javaClass, "closeService", 0)?.let { invokeUnwrapped(it, current) }
            }.onFailure {
                DiagnosticsLogger.log(context, "LibboxRuntime", "closeService ignored: ${rootMessage(it)}")
            }
            runCatching {
                findMethod(current.javaClass, "close", 0)?.let { invokeUnwrapped(it, current) }
            }.onFailure {
                DiagnosticsLogger.log(context, "LibboxRuntime", "close ignored: ${rootMessage(it)}")
            }
        }
        runCatching { tunFd?.close() }
        tunFd = null
    }

    private fun invokeUnwrapped(method: Method, target: Any?, vararg args: Any?): Any? {
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e.cause ?: e
            throw IllegalStateException(cause.message ?: cause.javaClass.name, cause)
        }
    }

    private fun rootMessage(error: Throwable): String {
        var current: Throwable? = error
        var last: Throwable = error
        while (current != null) {
            last = current
            current = current.cause
        }
        return last.message ?: last.javaClass.simpleName
    }

    private fun emptyIteratorFor(type: Class<*>): Any? {
        if (!type.isInterface) return null
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "len", "Len" -> 0
                "hasNext", "HasNext" -> false
                "next", "Next" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun iteratorOrDefault(type: Class<*>): Any? {
        return if (type.isInterface && type.name.lowercase(Locale.US).endsWith("iterator")) {
            emptyIteratorFor(type)
        } else {
            defaultValue(type)
        }
    }

    private fun packageNameByUid(args: Array<Any?>?): String {
        val uid = (args?.firstOrNull() as? Number)?.toInt() ?: return ""
        return if (uid == android.os.Process.myUid()) context.packageName else ""
    }

    private fun safeUnknownUid(type: Class<*>): Any? {
        // gomobile maps Go methods returning (int32, error) to Java methods
        // returning a primitive int/long and throwing exceptions for errors.
        // Returning null for a primitive return type can crash the Go runtime
        // through platformInterfaceWrapper.FindConnectionOwner().
        return when (type) {
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> (-1).toByte()
            java.lang.Short.TYPE, java.lang.Short::class.java -> (-1).toShort()
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> -1
            java.lang.Long.TYPE, java.lang.Long::class.java -> -1L
            java.lang.Float.TYPE, java.lang.Float::class.java -> -1f
            java.lang.Double.TYPE, java.lang.Double::class.java -> -1.0
            java.lang.String::class.java -> ""
            java.lang.Void.TYPE, Void::class.java -> null
            else -> defaultValue(type)
        }
    }

    companion object {
        private data class Binding(
            val libboxClass: Class<*>,
            val platformInterfaceClass: Class<*>
        )

        private val candidates = listOf(
            "io.nekohasekai.libbox" to "io.nekohasekai.libbox",
            "libbox" to "libbox",
            // Fallback: some AAR builds expose Libbox and PlatformInterface in different packages.
            "io.nekohasekai.libbox" to "libbox",
            "libbox" to "io.nekohasekai.libbox"
        )

        fun isLibboxAvailable(): Boolean = bindingOrNull() != null

        fun libboxStatusText(): String {
            val binding = bindingOrNull()
            return if (binding != null) {
                "libbox: найден (${binding.libboxClass.name})"
            } else {
                "libbox: не найден. APK собран без классов libbox или package name отличается."
            }
        }

        private fun binding(): Binding {
            return bindingOrNull()
                ?: throw IllegalStateException(
                    "libbox API не найден. Проверялись классы: " +
                        candidates.joinToString { (libboxPkg, platformPkg) ->
                            "$libboxPkg.Libbox + $platformPkg.PlatformInterface"
                        }
                )
        }

        private fun bindingOrNull(): Binding? {
            for ((libboxPkg, platformPkg) in candidates) {
                val libbox = runCatching { Class.forName("$libboxPkg.Libbox") }.getOrNull()
                val platform = runCatching { Class.forName("$platformPkg.PlatformInterface") }.getOrNull()
                if (libbox != null && platform != null) {
                    return Binding(libbox, platform)
                }
            }
            return null
        }

        private fun libboxClass(): Class<*> = binding().libboxClass

        private fun platformInterfaceClass(): Class<*> = binding().platformInterfaceClass

        private fun classOrNull(name: String?): Class<*>? {
            if (name.isNullOrBlank() || name.startsWith("null.")) return null
            return runCatching { Class.forName(name) }.getOrNull()
        }

        private fun methodNames(clazz: Class<*>): String {
            return clazz.methods
                .map { "${it.name}/${it.parameterTypes.size}" }
                .distinct()
                .sorted()
                .joinToString(",")
                .take(900)
        }

        private fun setFieldIfPresent(target: Any, name: String, value: Any?) {
            val field = target.javaClass.fields.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (field != null) {
                field.set(target, convertValue(value, field.type))
                return
            }

            val setterName = "set" + name.replaceFirstChar { it.uppercase(Locale.US) }
            val setter = target.javaClass.methods.firstOrNull {
                it.name.equals(setterName, ignoreCase = true) && it.parameterTypes.size == 1
            }
            if (setter != null) {
                setter.invoke(target, convertValue(value, setter.parameterTypes[0]))
            }
        }

        private fun convertValue(value: Any?, type: Class<*>): Any? {
            return when (type) {
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> (value as? Boolean) ?: false
                java.lang.Byte.TYPE, java.lang.Byte::class.java -> (value as? Number)?.toByte() ?: 0.toByte()
                java.lang.Short.TYPE, java.lang.Short::class.java -> (value as? Number)?.toShort() ?: 0.toShort()
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> (value as? Number)?.toInt() ?: 0
                java.lang.Long.TYPE, java.lang.Long::class.java -> (value as? Number)?.toLong() ?: 0L
                java.lang.Float.TYPE, java.lang.Float::class.java -> (value as? Number)?.toFloat() ?: 0f
                java.lang.Double.TYPE, java.lang.Double::class.java -> (value as? Number)?.toDouble() ?: 0.0
                java.lang.String::class.java -> value?.toString() ?: ""
                else -> value
            }
        }

        private fun findMethod(clazz: Class<*>, name: String, parameterCount: Int): Method? {
            return clazz.methods.firstOrNull {
                it.name.lowercase(Locale.US) == name.lowercase(Locale.US) &&
                    it.parameterTypes.size == parameterCount
            }
        }

        private fun defaultValue(type: Class<*>): Any? {
            return when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                java.lang.Void.TYPE -> null
                else -> null
            }
        }
    }
}
