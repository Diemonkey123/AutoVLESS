package com.autovless.app.core

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.autovless.app.util.DiagnosticsLogger
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale

class LibboxRuntime(private val context: Context) : Closeable {
    private var service: Any? = null
    private var tunFd: ParcelFileDescriptor? = null

    fun isAvailable(): Boolean = isLibboxAvailable()

    fun startStandalone(configContent: String) {
        DiagnosticsLogger.log(context, "LibboxRuntime", "startStandalone configChars=${configContent.length}")
        close()
        setupIfPresent()
        val libbox = libboxClass()
        DiagnosticsLogger.log(context, "LibboxRuntime", "Using libbox class=${libbox.name}")
        val platformInterface = createStandalonePlatformInterface()
        service = createBoxService(configContent, platformInterface)
        DiagnosticsLogger.log(context, "LibboxRuntime", "NewService OK: ${service!!.javaClass.name}")
        startBoxService(service!!)
        DiagnosticsLogger.log(context, "LibboxRuntime", "BoxService.start OK")
    }

    fun startVpn(vpnService: VpnService, configContent: String) {
        DiagnosticsLogger.log(context, "LibboxRuntime", "startVpn configChars=${configContent.length}")
        close()
        setupIfPresent()
        val libbox = libboxClass()
        DiagnosticsLogger.log(context, "LibboxRuntime", "Using libbox class=${libbox.name}")
        val platformInterface = createPlatformInterface(vpnService)
        service = createBoxService(configContent, platformInterface)
        DiagnosticsLogger.log(context, "LibboxRuntime", "NewService OK: ${service!!.javaClass.name}")
        startBoxService(service!!)
        DiagnosticsLogger.log(context, "LibboxRuntime", "BoxService.start OK")
    }

    private fun startBoxService(boxService: Any) {
        val start = findMethod(boxService.javaClass, "start", 0)
            ?: throw IllegalStateException("BoxService.start не найден. Методы: ${methodNames(boxService.javaClass)}")
        invokeUnwrapped(start, boxService)
    }

    private fun setupIfPresent() {
        val libbox = libboxClass()
        val base = context.filesDir.absolutePath
        val working = context.filesDir.absolutePath
        val temp = context.cacheDir.absolutePath

        val setup4 = findMethod(libbox, "setup", 4)
        if (setup4 != null) {
            setup4.invoke(null, base, temp, android.os.Process.myUid(), android.os.Process.myUid())
            DiagnosticsLogger.log(context, "LibboxRuntime", "Setup OK: legacy setup(base,temp,uid,gid)")
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
            setFieldIfPresent(options, "logMaxLines", 300)
            setFieldIfPresent(options, "debug", false)
            invokeUnwrapped(setup1, null, options)
            DiagnosticsLogger.log(context, "LibboxRuntime", "Setup OK: setup(${optionsClass.name})")
            return
        }

        DiagnosticsLogger.log(context, "LibboxRuntime", "Setup skipped: setup method not found in ${libbox.name}")
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
                errors += "${clazz.name}: no newService/ NewService; methods=${methodNames(clazz)}"
                continue
            }
            val target = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else null
            val result = runCatching { invokeUnwrapped(method, target, configContent, platformInterface) }
            if (result.isSuccess) {
                return result.getOrNull() ?: throw IllegalStateException("${clazz.name}.${method.name} вернул null")
            }
            errors += "${clazz.name}.${method.name}: ${result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.name}"
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
            when (method.name) {
                // For test mixed proxy there is no Android VPN tunnel, so file descriptors do not need VpnService.protect().
                "usePlatformAutoDetectInterfaceControl" -> false
                "autoDetectInterfaceControl" -> null
                "openTun" -> throw IllegalStateException("openTun вызван в standalone speed-test. Это значит, что test config ошибочно содержит tun inbound")
                "writeLog" -> {
                    val text = args?.joinToString(" ") ?: ""
                    if (text.isNotBlank()) DiagnosticsLogger.log(context, "libbox", text)
                    null
                }
                "useProcFS" -> false
                "findConnectionOwner" -> -1
                "packageNameByUid" -> ""
                "uidByPackageName" -> 0
                "startDefaultInterfaceMonitor" -> null
                "closeDefaultInterfaceMonitor" -> null
                "getInterfaces" -> null
                "underNetworkExtension" -> false
                "includeAllNetworks" -> false
                "readWIFIState" -> null
                "clearDNSCache" -> null
                "sendNotification" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun invokeUnwrapped(method: Method, target: Any?, vararg args: Any?): Any? {
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e.cause ?: e
            throw IllegalStateException(cause.message ?: cause.javaClass.name, cause)
        }
    }

    private fun createPlatformInterface(vpnService: VpnService): Any {
        val interfaceClass = platformInterfaceClass()
        return Proxy.newProxyInstance(
            interfaceClass.classLoader,
            arrayOf(interfaceClass)
        ) { _, method, args ->
            when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = (args?.getOrNull(0) as? Number)?.toInt() ?: return@newProxyInstance null
                    vpnService.protect(fd)
                    null
                }
                "openTun" -> {
                    val fd = openTun(vpnService).detachFd()
                    if (method.returnType == java.lang.Long.TYPE || method.returnType == java.lang.Long::class.java) fd.toLong() else fd
                }
                "writeLog" -> {
                    val text = args?.joinToString(" ") ?: ""
                    if (text.isNotBlank()) DiagnosticsLogger.log(context, "libbox", text)
                    null
                }
                "useProcFS" -> false
                "findConnectionOwner" -> -1
                "packageNameByUid" -> ""
                "uidByPackageName" -> 0
                "usePlatformDefaultInterfaceMonitor" -> false
                "startDefaultInterfaceMonitor" -> null
                "closeDefaultInterfaceMonitor" -> null
                "usePlatformInterfaceGetter" -> false
                "getInterfaces" -> null
                "underNetworkExtension" -> false
                "includeAllNetworks" -> false
                "clearDNSCache" -> null
                "readWIFIState" -> null
                "sendNotification" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun openTun(vpnService: VpnService): ParcelFileDescriptor {
        tunFd?.close()
        val builder = vpnService.Builder()
            .setSession("AutoVLESS")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        try {
            builder.addRoute("::", 0)
        } catch (_: Throwable) {
            // IPv6 route may fail on some devices. IPv4 route is enough for MVP.
        }

        tunFd = builder.establish()
            ?: throw IllegalStateException("Не удалось создать Android TUN-интерфейс")
        return tunFd!!
    }

    override fun close() {
        DiagnosticsLogger.log(context, "LibboxRuntime", "close()")
        val current = service
        service = null
        if (current != null) {
            runCatching {
                findMethod(current.javaClass, "close", 0)?.let { invokeUnwrapped(it, current) }
            }
        }
        runCatching { tunFd?.close() }
        tunFd = null
    }

    companion object {
        private data class Binding(
            val libboxClass: Class<*>,
            val platformInterfaceClass: Class<*>
        )

        private val candidates = listOf(
            "libbox" to "libbox",
            "io.nekohasekai.libbox" to "io.nekohasekai.libbox",
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
            if (name.isNullOrBlank() || name == "null.BoxService") return null
            return runCatching { Class.forName(name) }.getOrNull()
        }

        private fun methodNames(clazz: Class<*>): String {
            return clazz.methods
                .map { "${it.name}/${it.parameterTypes.size}" }
                .distinct()
                .sorted()
                .joinToString(",")
                .take(700)
        }

        private fun setFieldIfPresent(target: Any, name: String, value: Any?) {
            val field = target.javaClass.fields.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
            val converted = when (field.type) {
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
            field.set(target, converted)
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
