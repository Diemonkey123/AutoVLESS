package com.autovless.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsLogger {
    private const val FILE_NAME = "autovless_debug.log"
    private const val MAX_BYTES = 512 * 1024
    private const val TRIM_TO_BYTES = 384 * 1024

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        val appContext = context.applicationContext
        val file = File(appContext.filesDir, FILE_NAME)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$time [$tag] ${message.replace('\n', ' ').take(2000)}\n"
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES.toLong()) {
                val text = file.readText(Charsets.UTF_8)
                file.writeText(text.takeLast(TRIM_TO_BYTES), Charsets.UTF_8)
            }
            file.appendText(line, Charsets.UTF_8)
        }
    }

    @Synchronized
    fun read(context: Context, maxChars: Int = 80_000): String {
        val dir = context.applicationContext.filesDir
        val file = File(dir, FILE_NAME)
        val main = if (file.exists()) {
            runCatching { file.readText(Charsets.UTF_8).takeLast(maxChars) }.getOrElse { "Ошибка чтения лога: ${it.message}" }
        } else {
            "Лог пуст"
        }

        val stderr = File(dir, "libbox-stderr.log")
        val stderrText = if (stderr.exists() && stderr.length() > 0) {
            runCatching {
                "\n\n--- libbox-stderr.log ---\n" + stderr.readText(Charsets.UTF_8).takeLast(20_000)
            }.getOrDefault("")
        } else {
            ""
        }

        return main + stderrText
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).writeText("", Charsets.UTF_8)
    }

    fun nodeSummary(node: com.autovless.app.vless.VlessNode): String {
        return "remark=${node.remark ?: "-"}, server=${node.server}:${node.port}, net=${node.normalizedNetwork()}, sec=${node.security ?: "-"}, sni=${node.sni ?: "-"}, host=${node.host ?: "-"}, fp=${node.fingerprint ?: "-"}, flow=${node.flow ?: "-"}, pbk=${mask(node.publicKey)}, sid=${mask(node.shortId)}"
    }

    private fun mask(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        return when {
            value.length <= 8 -> "***"
            else -> value.take(4) + "..." + value.takeLast(4)
        }
    }
}
