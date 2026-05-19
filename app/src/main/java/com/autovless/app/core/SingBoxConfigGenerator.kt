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

        root.put("dns", vpnDnsConfig())
        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("address", JSONArray().put(TUN_ADDRESS))
                    // Use the same practical stack style as Android clients: system TCP
                    // keeps HTTPS/app traffic reliable, while gVisor still handles UDP.
                    .put("stack", "mixed")
                    .put("auto_route", false)
                    .put("strict_route", false)
            )
        )

        root.put("outbounds", outbounds(node, "selected"))
        root.put("route", routeFinal("selected", vpnMode = true))
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
        root.put("route", routeFinal("selected", vpnMode = false))
        return root.toString(2)
    }

    private fun vpnDnsConfig(): JSONObject {
        // Do not use DoH hostnames here. In VPN/TUN mode a DoH hostname needs
        // bootstrap DNS before DNS itself works. Use plain TCP DNS by IP and send it
        // through the selected VLESS outbound. This avoids UDP/53 dependency and
        // removes the dns.google/cloudflare-dns.com bootstrap loop.
        val servers = JSONArray()
            .put(
                JSONObject()
                    .put("type", "tcp")
                    .put("tag", "google-tcp")
                    .put("server", PRIMARY_DNS)
                    .put("server_port", 53)
                    .put("detour", "selected")
            )
            .put(
                JSONObject()
                    .put("type", "tcp")
                    .put("tag", "cloudflare-tcp")
                    .put("server", SECONDARY_DNS)
                    .put("server_port", 53)
                    .put("detour", "selected")
            )

        return JSONObject()
            .put("servers", servers)
            .put("final", "google-tcp")
            .put("strategy", "ipv4_only")
    }

    private fun routeFinal(outboundTag: String, vpnMode: Boolean): JSONObject {
        // find_process must stay disabled: Android libbox crashed earlier inside
        // PlatformInterface.FindConnectionOwner(). For VPN mode auto-detect must be
        // enabled so libbox can protect its own outbound sockets from routing back
        // into the VPN tunnel. Without that, the VPN shows connected but traffic loops.
        val route = JSONObject()
            .put("final", outboundTag)
            .put("find_process", false)
            .put("auto_detect_interface", vpnMode)
            // Keep false: this app is the Android VPN provider. The selected outbound
            // must use the physical network, not chain into another active Android VPN.
            .put("override_android_vpn", false)

        if (vpnMode) {
            // sing-box 1.13 removed legacy inbound sniff fields. Sniffing is now
            // expressed as a non-final route action before DNS hijack/final routing.
            // DNS packets from Android TUN are handled by hijack-dns instead of the
            // removed outbound { type: "dns" }.
            val rules = JSONArray()
                // DNS hijack must be the first VPN rule. Android resolver packets must
                // be intercepted locally before sniff/final routing can send raw UDP/53
                // through VLESS, where many free nodes drop it.
                .put(
                    JSONObject()
                        .put("inbound", "tun-in")
                        .put("protocol", "dns")
                        .put("action", "hijack-dns")
                )
                .put(
                    JSONObject()
                        .put("inbound", "tun-in")
                        .put("port", 53)
                        .put("action", "hijack-dns")
                )
                .put(
                    JSONObject()
                        .put("inbound", "tun-in")
                        .put("action", "sniff")
                        .put("timeout", "300ms")
                )
                // Many mobile apps try QUIC first. Most public/free VLESS nodes do not
                // pass UDP reliably, so reject UDP/443 quickly and force normal TCP HTTPS.
                .put(
                    JSONObject()
                        .put("inbound", "tun-in")
                        .put("network", "udp")
                        .put("port", 443)
                        .put("action", "reject")
                )

            route.put("rules", rules)
        }
        return route
    }

    private fun baseConfig(): JSONObject {
        return JSONObject().put("log", JSONObject().put("level", "info"))
    }

    companion object {
        const val TUN_ADDRESS = "172.19.0.1/30"
        const val TUN_DNS_ADDRESS = "172.19.0.2"
        const val PRIMARY_DNS = "8.8.8.8"
        const val SECONDARY_DNS = "1.1.1.1"
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

        // Enable VLESS UDP packet support where the server accepts it. DNS is now
        // intercepted separately, but this also helps apps that use QUIC/UDP.
        outbound.put("packet_encoding", "xudp")

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

        DiagnosticsLogger.log(context, "ConfigGenerator", "outbound built network=$network tls=$tlsEnabled reality=$realityEnabled transport=${outbound.optJSONObject("transport")?.optString("type") ?: "none"} packet=xudp")
        return outbound
    }
}
