package com.autovless.app.testing

import android.content.Context
import com.autovless.app.BuildConfig
import com.autovless.app.core.LibboxRuntime
import com.autovless.app.core.SingBoxConfigGenerator
import com.autovless.app.util.DiagnosticsLogger
import com.autovless.app.vless.VlessNode
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import kotlin.math.max

class SpeedTester(private val context: Context) {
    fun test(node: VlessNode, minSpeedKbps: Double = DEFAULT_MIN_SPEED_KBPS): SpeedTestResult {
        DiagnosticsLogger.log(context, "SpeedTester", "START_FAST ${DiagnosticsLogger.nodeSummary(node)}")
        val unsupported = node.unsupportedReason()
        if (unsupported != null) {
            DiagnosticsLogger.log(context, "SpeedTester", "UNSUPPORTED $unsupported")
            return SpeedTestResult(SpeedTestStatus.CONFIG_UNSUPPORTED, message = unsupported)
        }

        if (!LibboxRuntime.isLibboxAvailable()) {
            DiagnosticsLogger.log(context, "SpeedTester", "LIBBOX_MISSING ${LibboxRuntime.libboxStatusText()}")
            return SpeedTestResult(
                status = SpeedTestStatus.LIBBOX_MISSING,
                message = LibboxRuntime.libboxStatusText()
            )
        }

        val port = pickFreePort()
        DiagnosticsLogger.log(context, "SpeedTester", "Picked local proxy port=$port")
        val config = try {
            SingBoxConfigGenerator(context).generateTestProxyConfig(node, port)
        } catch (e: Throwable) {
            val msg = rootMessage(e)
            DiagnosticsLogger.log(context, "SpeedTester", "CONFIG_GENERATION_FAILED $msg")
            return SpeedTestResult(
                status = SpeedTestStatus.CONFIG_UNSUPPORTED,
                message = msg
            )
        }

        val runtime = LibboxRuntime(context)

        try {
            DiagnosticsLogger.log(context, "SpeedTester", "Starting standalone libbox")
            runtime.startStandalone(config)
            DiagnosticsLogger.log(context, "SpeedTester", "libbox startStandalone OK, soft-wait port=$port")
            Thread.sleep(STANDALONE_SOFT_WAIT_MS)
        } catch (e: Throwable) {
            runtime.close()
            val msg = rootMessage(e)
            DiagnosticsLogger.log(context, "SpeedTester", "LIBBOX_START_FAILED $msg")
            return SpeedTestResult(
                status = SpeedTestStatus.LIBBOX_START_FAILED,
                message = msg
            )
        }

        return try {
            val latency = checkGoogleLatency(port)
            DiagnosticsLogger.log(context, "SpeedTester", "Latency result ok=${latency != null} msg=${latency?.let { "google=${it}ms" } ?: "google failed"}")
            if (latency == null) {
                return SpeedTestResult(
                    SpeedTestStatus.CONNECTION_FAILED,
                    message = "google url-test failed"
                )
            }

            val speed = measureGoogleSpeedFast(port, minSpeedKbps)
            DiagnosticsLogger.log(context, "SpeedTester", "Speed result kbps=$speed msg=google=${speed.toInt()} KB/s")
            val message = "google=${latency}ms; speed=${speed.toInt()} KB/s"
            when {
                speed >= minSpeedKbps -> SpeedTestResult(SpeedTestStatus.OK, speedKbps = speed, message = message)
                else -> SpeedTestResult(SpeedTestStatus.BELOW_THRESHOLD, speedKbps = speed, message = "$message < ${minSpeedKbps.toInt()} KB/s")
            }
        } finally {
            runtime.close()
            Thread.sleep(RUNTIME_CLOSE_WAIT_MS)
        }
    }

    private fun checkGoogleLatency(port: Int): Int? {
        DiagnosticsLogger.log(context, "SpeedTester", "URL_TEST_TRY ${shortUrl(GOOGLE_PING_URL)}")
        val started = System.nanoTime()
        val result = runCatching {
            openAndReadLimited(
                urlText = GOOGLE_PING_URL,
                port = port,
                limit = 1,
                connectTimeoutMs = PING_TIMEOUT_MS,
                readTimeoutMs = PING_TIMEOUT_MS,
                rangeBytes = null
            )
        }

        if (result.isSuccess) {
            val ms = ((System.nanoTime() - started) / 1_000_000.0).toInt()
            DiagnosticsLogger.log(context, "SpeedTester", "URL_TEST_OK ${shortUrl(GOOGLE_PING_URL)} ${ms}ms")
            return ms
        }

        val err = result.exceptionOrNull()?.message ?: "failed"
        DiagnosticsLogger.log(context, "SpeedTester", "URL_TEST_FAIL ${shortUrl(GOOGLE_PING_URL)} $err")
        return null
    }

    private fun measureGoogleSpeedFast(port: Int, minSpeedKbps: Double): Double {
        DiagnosticsLogger.log(context, "SpeedTester", "DOWNLOAD_TRY ${shortUrl(GOOGLE_SPEED_URL)}")
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        var bytes = 0L

        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            connection = URL(GOOGLE_SPEED_URL).openConnection(proxy) as HttpURLConnection
            connection!!.connectTimeout = SPEED_CONNECT_TIMEOUT_MS
            connection!!.readTimeout = SPEED_READ_TIMEOUT_MS
            connection!!.instanceFollowRedirects = true
            connection!!.setRequestProperty("User-Agent", "AutoVLESS-SpeedTest/${BuildConfig.VERSION_NAME}")
            connection!!.setRequestProperty("Cache-Control", "no-cache")
            connection!!.setRequestProperty("Range", "bytes=0-${SPEED_MAX_BYTES - 1}")

            val code = connection!!.responseCode
            if (code !in 200..299 && code != 206) {
                throw IllegalStateException("HTTP $code")
            }

            connection!!.inputStream.use { input ->
                val buffer = ByteArray(SPEED_BUFFER_BYTES)
                while (bytes < SPEED_MAX_BYTES) {
                    val elapsedMs = ((System.nanoTime() - started) / 1_000_000.0).toLong()
                    if (elapsedMs >= SPEED_SAMPLE_MS) break

                    val read = input.read(buffer, 0, minOf(buffer.size, SPEED_MAX_BYTES - bytes.toInt()))
                    if (read == -1) break
                    bytes += read

                    val afterReadElapsedMs = max(((System.nanoTime() - started) / 1_000_000.0), 1.0)
                    if (afterReadElapsedMs >= EARLY_SUCCESS_AFTER_MS) {
                        val currentSpeed = bytes / 1024.0 / (afterReadElapsedMs / 1000.0)
                        if (currentSpeed >= minSpeedKbps) {
                            DiagnosticsLogger.log(context, "SpeedTester", "DOWNLOAD_OK ${shortUrl(GOOGLE_SPEED_URL)} ${currentSpeed.toInt()} KB/s early")
                            return currentSpeed
                        }
                    }
                }
            }

            val seconds = max((System.nanoTime() - started) / 1_000_000_000.0, 0.001)
            val speed = bytes / 1024.0 / seconds
            if (speed > 0.0) {
                DiagnosticsLogger.log(context, "SpeedTester", "DOWNLOAD_OK ${shortUrl(GOOGLE_SPEED_URL)} ${speed.toInt()} KB/s")
            } else {
                DiagnosticsLogger.log(context, "SpeedTester", "DOWNLOAD_FAIL ${shortUrl(GOOGLE_SPEED_URL)} 0 bytes")
            }
            speed
        } catch (e: Throwable) {
            DiagnosticsLogger.log(context, "SpeedTester", "DOWNLOAD_FAIL ${shortUrl(GOOGLE_SPEED_URL)} ${e.message ?: e.javaClass.simpleName}")
            0.0
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun openAndReadLimited(
        urlText: String,
        port: Int,
        limit: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        rangeBytes: Int?
    ): Long {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        val connection = URL(urlText).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "AutoVLESS-SpeedTest/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Cache-Control", "no-cache")
        if (rangeBytes != null) {
            connection.setRequestProperty("Range", "bytes=0-${rangeBytes - 1}")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 204 && code != 206) {
                throw IllegalStateException("HTTP $code")
            }

            if (code == 204) return 1L

            var bytes = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (bytes < limit) {
                    val read = input.read(buffer, 0, minOf(buffer.size, limit - bytes.toInt()))
                    if (read == -1) break
                    bytes += read
                }
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun pickFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
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

    private fun shortUrl(url: String): String {
        return runCatching { URL(url).host }.getOrDefault(url)
    }

    companion object {
        private const val DEFAULT_MIN_SPEED_KBPS = 500.0
        private const val GOOGLE_PING_URL = "https://www.gstatic.com/generate_204"
        private const val GOOGLE_SPEED_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

        private const val STANDALONE_SOFT_WAIT_MS = 700L
        private const val RUNTIME_CLOSE_WAIT_MS = 150L

        private const val PING_TIMEOUT_MS = 3_500
        private const val SPEED_CONNECT_TIMEOUT_MS = 2_500
        private const val SPEED_READ_TIMEOUT_MS = 1_500
        private const val SPEED_SAMPLE_MS = 2_500L
        private const val EARLY_SUCCESS_AFTER_MS = 700.0
        private const val SPEED_MAX_BYTES = 768 * 1024
        private const val SPEED_BUFFER_BYTES = 16 * 1024
    }
}
