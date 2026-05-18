package com.autovless.app.testing

data class SpeedTestResult(
    val status: SpeedTestStatus,
    val speedKbps: Double? = null,
    val message: String? = null
)

enum class SpeedTestStatus {
    OK,
    BELOW_THRESHOLD,
    CONNECTION_FAILED,
    CONFIG_UNSUPPORTED,
    PROXY_PORT_FAILED,
    LIBBOX_MISSING,
    LIBBOX_START_FAILED
}
