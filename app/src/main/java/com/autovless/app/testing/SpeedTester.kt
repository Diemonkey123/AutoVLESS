package com.autovless.app.testing

import android.content.Context
import com.autovless.app.BuildConfig
import com.autovless.app.core.LibboxRuntime
import com.autovless.app.core.SingBoxConfigGenerator
import com.autovless.app.vless.VlessNode
import com.autovless.app.util.DiagnosticsLogger
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.math.max

class SpeedTester(private val context: Context) {
    private val latencyUrls = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://ya.ru/",
        "https://vk.com/"
    )

    private val speedUrls = listOf(
        "https://speed.cloudflare.com/__down?bytes=1048576",
        "https://speed.cloudflare.com/__down?bytes=2097152",
        "https://cachefly.cachefly.net/1mb.test",
        "https://proof.ovh.net/files/1Mb.dat"
    )

    fun test(node: VlessNode, minSpeedKbps: Double = 500.0): SpeedTestResult {
        DiagnosticsLogger.log(context, "SpeedTester", "START ${DiagnosticsLogger.nodeSummary(node)}")
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
            // Do not probe the mixed inbound with a raw empty TCP connection. On some
            // Android/libbox builds that can kill the process before Kotlin sees an exception.
            // The real HTTP proxy request below is the actual availability check.
            Thread.sleep(900)
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
            val latencyOk = checkLatency(port)
            DiagnosticsLogger.log(context, "SpeedTester", "Latency result ok=${latencyOk.first} msg=${latencyOk.second}")
            if (!latencyOk.first) {
                return SpeedTestResult(
                    SpeedTestStatus.CONNECTION_FAILED,
                    message = "url-test failed: ${latencyOk.second ?: "no response"}"
                )
            }

            val measured = measureThroughProxy(port, minSpeedKbps)
            DiagnosticsLogger.log(context, "SpeedTester", "Speed result kbps=${measured.first} msg=${measured.second}")
            val speed = measured.first
            val message = listOfNotNull(latencyOk.second, measured.second).joinToString("; ").ifBlank { null }
            when {
                speed == null -> SpeedTestResult(SpeedTestStatus.CONNECTION_FAILED, message = message ?: "download-test failed")
                speed >= minSpeedKbps -> SpeedTestResult(SpeedTestStatus.OK, speedKbps = speed, message = message)
                else -> SpeedTestResult(SpeedTestStatus.BELOW_THRESHOLD, speedKbps = speed, message = message)
            }
        } finally {
            runtime.close()
            Thread.sleep(250)
        }
    }

    private fun checkLatency(port: Int): Pair<Boolean, String?> {
        val errors = mutableListOf<String>()
        for (url in latencyUrls) {
            DiagnosticsLogger.log(context, "SpeedTester", "URL_TEST_TRY ${shortUrl(url)}")
            val started = System.nanoTime()
            val result = runCatching { openAndReadSmall(url, port, 16 * 1024) }
            if (result.isSuccess) {
                val ms = ((System.nanoTime() - started) / 1_000_000.0).toInt()
                DiagnosticsLogger.log(context, "SpeedTester", "URL_TEST_OK ${shortUrl(url)} ${ms}ms")
                return true to "url-test ${shortUrl(url)}=${ms}ms"
            }
            val err = result.exceptionOrNull()?.message ?: "failed"
            DiagnosticsLogger.log(context, "SpeedTester", "URL_TEST_FAIL ${shortUrl(url)} $err")
            errors += "${shortUrl(url)}: $err"
        }
        return false to errors.joinToString("; ").take(240)
    }

    private fun measureThroughProxy(port: Int, minSpeedKbps: Double): Pair<Double?, String?> {
        var best: Double? = null
        val errors = mutableListOf<String>()

        for (url in speedUrls) {
            DiagnosticsLogger.log(context, "SpeedTester", "DOWNLOAD_TRY ${shortUrl(url)}")
            val result = runCatching { measureOne(url, port) }
            val speed = result.getOrNull()
            if (speed != null && speed > 0.0) {
                DiagnosticsLogger.log(context, "SpeedTester", "DOWNLOAD_OK ${shortUrl(url)} ${speed.toInt()} KB/s")
                best = max(best ?: 0.0, speed)
                if (speed >= minSpeedKbps) {
                    return best to "best=${speed.toInt()} KB/s"
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "failed"
                DiagnosticsLogger.log(context, "SpeedTester", "DOWNLOAD_FAIL ${shortUrl(url)} $err")
                errors += "${shortUrl(url)}: $err"
            }
        }

        return best to when {
            best != null -> "best=${best.toInt()} KB/s"
            errors.isNotEmpty() -> errors.joinToString("; ").take(240)
            else -> null
        }
    }

    private fun measureOne(urlText: String, port: Int): Double {
        val started = System.nanoTime()
        val bytes = openAndReadSmall(urlText, port, 1024 * 1024)
        if (bytes <= 0) throw IllegalStateException("0 bytes")
        val seconds = max((System.nanoTime() - started) / 1_000_000_000.0, 0.001)
        return bytes / 1024.0 / seconds
    }

    private fun openAndReadSmall(urlText: String, port: Int, limit: Int): Long {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        val connection = URL(urlText).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "AutoVLESS-SpeedTest/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Cache-Control", "no-cache")

        val code = connection.responseCode
        if (code !in 200..299 && code != 204) {
            throw IllegalStateException("HTTP $code")
        }

        if (code == 204) return 1L

        var bytes = 0L
        connection.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (bytes < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - bytes.toInt()))
                if (read == -1) break
                bytes += read
            }
        }
        return bytes
    }

    private fun waitForLocalPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                    return true
                }
            } catch (_: Throwable) {
                Thread.sleep(150)
            }
        }
        return false
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
}
