package com.autovless.app.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class SubscriptionFetcher {
    private val urls = listOf(
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt"
    )

    fun fetchAll(): String {
        val result = StringBuilder()
        val errors = mutableListOf<String>()

        for (url in urls) {
            try {
                val text = fetch(url)
                if (text.isNotBlank()) {
                    result.appendLine(text)
                }
            } catch (e: Throwable) {
                errors += "${url}: ${e.message ?: "unknown error"}"
            }
        }

        if (result.isBlank()) {
            throw IllegalStateException("Не удалось скачать подписки: ${errors.joinToString("; ")}")
        }

        return result.toString()
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "AutoVLESS-Android/0.1")
        connection.instanceFollowRedirects = true

        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code")
        }

        return BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }
}
