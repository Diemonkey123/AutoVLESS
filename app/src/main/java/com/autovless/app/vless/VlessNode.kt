package com.autovless.app.vless

data class VlessNode(
    val raw: String,
    val uuid: String,
    val server: String,
    val port: Int,
    val sni: String?,
    val host: String?,
    val path: String?,
    val flow: String?,
    val network: String?,
    val security: String?,
    val fingerprint: String?,
    val publicKey: String?,
    val shortId: String?,
    val spiderX: String?,
    val alpn: List<String>,
    val mode: String?,
    val serviceName: String?,
    val authority: String?,
    val extra: String?,
    val remark: String?,
    val nodeKey: String
) {
    fun normalizedNetwork(): String {
        return network?.lowercase()?.trim().orEmpty().ifBlank { "tcp" }
    }

    fun unsupportedReason(): String? {
        return when (normalizedNetwork()) {
            "tcp", "ws", "websocket", "grpc", "http", "httpupgrade" -> null
            "xhttp" -> "xhttp transport пока не поддерживается sing-box/libbox в этой сборке"
            else -> "transport ${normalizedNetwork()} не поддерживается этой сборкой"
        }
    }

    fun isSupportedByThisApp(): Boolean = unsupportedReason() == null
}
