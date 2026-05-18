package com.autovless.app.core

import android.content.Context
import com.autovless.app.vless.VlessNode
import com.autovless.app.util.DiagnosticsLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SingBoxConfigGenerator(private val context: Context) {
    fun writeVpnConfig(node: VlessNode): File {
        val file = File(context.filesDir, "singbox_vpn_config.json")
        file.writeText(generateVpnConfig(node), Charsets.UTF_8)
        return file
    }

    fun writeTestProxyConfig(node: VlessNode, port: Int): File {
        val file = File(context.cacheDir, "singbox_test_proxy_${port}.json")
        file.writeText(generateTestProxyConfig(node, port), Charsets.UTF_8)
        return file
    }

    fun generateVpnConfig(node: VlessNode): String {
        DiagnosticsLogger.log(context, "ConfigGenerator", "generateVpnConfig ${DiagnosticsLogger.nodeSummary(node)}")
        val root = baseConfig()

        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("address", JSONArray().put("172.19.0.1/30"))
                    .put("auto_route", true)
                    .put("strict_route", true)
            )
        )

        root.put("outbounds", outbounds(node, "selected"))
        root.put("route", routeFinal("selected"))
        return root.toString(2)
    }

    fun generateTestProxyConfig(node: VlessNode, port: Int): String {
        DiagnosticsLogger.log(context, "ConfigGenerator", "generateTestProxyConfig port=$port ${DiagnosticsLogger.nodeSummary(node)}")
        val root = baseConfig()

        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", "test-in")
                    .put("listen", "127.0.0.1")
                    .put("listen_port", port)
            )
        )

        root.put("outbounds", outbounds(node, "selected"))
        root.put("route", routeFinal("selected"))
        return root.toString(2)
    }

    private fun routeFinal(outboundTag: String): JSONObject {
        // Keep routing deliberately minimal for Android local speed checks.
        // Explicitly disable process lookup: on libbox Android builds it can call
        // PlatformInterface.FindConnectionOwner() from Go/native code and crash the
        // whole app process if the Java bridge returns an unexpected value.
        return JSONObject()
            .put("final", outboundTag)
            .put("find_process", false)
            .put("auto_detect_interface", false)
    }

    private fun baseConfig(): JSONObject {
        // Keep config minimal. External DNS servers can be unreliable from Russia;
        // for proxy testing, sing-box can pass the destination domain through the outbound.
        return JSONObject().put("log", JSONObject().put("level", "info"))
    }

    private fun outbounds(node: VlessNode, tag: String): JSONArray {
        return JSONArray()
            .put(vlessOutbound(node, tag))
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
    }

    private fun vlessOutbound(node: VlessNode, tag: String): JSONObject {
        node.unsupportedReason()?.let { throw IllegalArgumentException(it) }

        val security = node.security?.lowercase().orEmpty()
        val tlsEnabled = security == "tls" || security == "reality" || !node.sni.isNullOrBlank()
        val realityEnabled = security == "reality" || !node.publicKey.isNullOrBlank()
        val sni = node.sni ?: node.host ?: node.server
        val fp = node.fingerprint?.lowercase() ?: "chrome"
        val network = node.normalizedNetwork()

        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", tag)
            .put("server", node.server)
            .put("server_port", node.port)
            .put("uuid", node.uuid)

        if (!node.flow.isNullOrBlank()) {
            outbound.put("flow", node.flow)
        }

        when (network) {
            "tcp" -> {
                // Plain TCP is the default for sing-box VLESS. Do not add a fake tcp transport.
            }

            "ws", "websocket" -> {
                outbound.put(
                    "transport",
                    JSONObject()
                        .put("type", "ws")
                        .put("path", node.path ?: "/")
                        .put("headers", JSONObject().put("Host", node.host ?: sni))
                )
            }

            "grpc" -> {
                outbound.put(
                    "transport",
                    JSONObject()
                        .put("type", "grpc")
                        .put("service_name", node.serviceName ?: node.path ?: "")
                )
            }

            "http" -> {
                val transport = JSONObject()
                    .put("type", "http")
                    .put("path", node.path ?: "/")
                if (!node.host.isNullOrBlank()) {
                    transport.put("host", JSONArray().put(node.host))
                }
                outbound.put("transport", transport)
            }

            "httpupgrade" -> {
                val transport = JSONObject()
                    .put("type", "httpupgrade")
                    .put("host", node.host ?: sni)
                    .put("path", node.path ?: "/")
                outbound.put("transport", transport)
            }
        }

        if (tlsEnabled) {
            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", sni)
                .put("insecure", true)
                .put(
                    "utls",
                    JSONObject()
                        .put("enabled", true)
                        .put("fingerprint", fp)
                )

            if (node.alpn.isNotEmpty()) {
                val alpn = JSONArray()
                node.alpn.forEach { alpn.put(it) }
                tls.put("alpn", alpn)
            }

            if (realityEnabled) {
                val reality = JSONObject().put("enabled", true)
                if (!node.publicKey.isNullOrBlank()) {
                    reality.put("public_key", node.publicKey)
                }
                if (!node.shortId.isNullOrBlank()) {
                    reality.put("short_id", node.shortId)
                }
                tls.put("reality", reality)
            }

            outbound.put("tls", tls)
        }

        DiagnosticsLogger.log(context, "ConfigGenerator", "outbound built network=$network tls=$tlsEnabled reality=$realityEnabled transport=${outbound.optJSONObject("transport")?.optString("type") ?: "none"}")
        return outbound
    }
}
