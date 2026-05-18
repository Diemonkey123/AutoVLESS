package com.autovless.app.vless

import java.net.URI
import java.net.URLDecoder

object VlessParser {
    fun parse(text: String): List<VlessNode> {
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .mapNotNull { parseOne(it) }
            .distinctBy { it.nodeKey }
            .toList()
    }

    fun parseOne(raw: String): VlessNode? {
        return try {
            val uri = URI(raw)
            if (!uri.scheme.equals("vless", ignoreCase = true)) return null

            val uuid = uri.userInfo?.trim().orEmpty()
            val server = uri.host?.trim().orEmpty()
            val port = if (uri.port > 0) uri.port else 443
            if (uuid.isBlank() || server.isBlank()) return null

            val query = parseQuery(uri.rawQuery.orEmpty())
            val remark = decode(uri.rawFragment.orEmpty()).ifBlank { null }

            val sni = firstNotBlank(query["sni"], query["peer"], query["serverName"], query["server_name"])
            val host = firstNotBlank(query["host"])
            val authority = firstNotBlank(query["authority"], query["host"])
            val path = firstNotBlank(query["path"])
            val serviceName = firstNotBlank(query["serviceName"], query["service_name"])
            val mode = firstNotBlank(query["mode"])
            val extra = firstNotBlank(query["extra"])
            val flow = query["flow"]?.ifBlank { null }
            val network = firstNotBlank(query["type"], query["network"])?.lowercase()
            val security = query["security"]?.ifBlank { null }?.lowercase()
            val fingerprint = firstNotBlank(query["fp"], query["fingerprint"])
            val publicKey = firstNotBlank(query["pbk"], query["publicKey"], query["public_key"])
            val shortId = firstNotBlank(query["sid"], query["shortId"], query["short_id"])
            val spiderX = firstNotBlank(query["spx"], query["spiderX"], query["spider_x"])
            val alpn = firstNotBlank(query["alpn"])?.split(',', ';')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()

            val keySource = listOf(
                server.lowercase(),
                port.toString(),
                uuid.lowercase(),
                sni.orEmpty().lowercase(),
                host.orEmpty().lowercase(),
                authority.orEmpty().lowercase(),
                path.orEmpty(),
                serviceName.orEmpty(),
                mode.orEmpty(),
                flow.orEmpty(),
                network.orEmpty(),
                security.orEmpty(),
                fingerprint.orEmpty().lowercase(),
                publicKey.orEmpty(),
                shortId.orEmpty().lowercase(),
                spiderX.orEmpty()
            ).joinToString("|")

            VlessNode(
                raw = raw,
                uuid = uuid,
                server = server,
                port = port,
                sni = sni,
                host = host,
                path = path,
                flow = flow,
                network = network,
                security = security,
                fingerprint = fingerprint,
                publicKey = publicKey,
                shortId = shortId,
                spiderX = spiderX,
                alpn = alpn,
                mode = mode,
                serviceName = serviceName,
                authority = authority,
                extra = extra,
                remark = remark,
                nodeKey = Hash.sha256(keySource)
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val key = decode(part.substring(0, index))
                val value = decode(part.substring(index + 1))
                key to value
            }
            .toMap()
    }

    private fun firstNotBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            value
        }
    }
}
