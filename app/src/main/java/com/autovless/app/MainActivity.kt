package com.autovless.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autovless.app.core.AutoVlessVpnService
import com.autovless.app.core.LibboxRuntime
import com.autovless.app.core.SingBoxConfigGenerator
import com.autovless.app.net.SubscriptionFetcher
import com.autovless.app.storage.NodeStore
import com.autovless.app.testing.SpeedTestStatus
import com.autovless.app.testing.SpeedTester
import com.autovless.app.util.DiagnosticsLogger
import com.autovless.app.vless.VlessParser
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var store: NodeStore
    private lateinit var statusView: TextView
    private lateinit var countersView: TextView
    private lateinit var selectedView: TextView
    private lateinit var libboxView: TextView
    private lateinit var consoleView: TextView
    private lateinit var consoleScroll: ScrollView

    private val consoleBuffer = StringBuilder()
    @Volatile private var checkCancelRequested = false
    @Volatile private var checkRunning = false
    private var pendingVpnConfig: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NodeStore(this)
        setContentView(buildUi())
        DiagnosticsLogger.setLiveSink { line ->
            mainHandler.post { appendConsoleLine(line) }
        }
        loadConsoleFromDisk()
        DiagnosticsLogger.log(this, "MainActivity", "App started version=${BuildConfig.VERSION_NAME}")
        maybeRequestNotifications()
        refreshCounters()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = "AutoVLESS"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title)

        statusView = TextView(this).apply {
            text = "Статус: готово"
            textSize = 16f
            setPadding(0, 32, 0, 16)
        }
        root.addView(statusView)

        libboxView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }
        root.addView(libboxView)

        countersView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        root.addView(countersView)

        selectedView = TextView(this).apply {
            text = "Текущий конфиг: не выбран"
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        root.addView(selectedView)

        root.addButton("Обновить список") {
            updateList()
        }

        root.addButton("Проверить лучший конфиг") {
            checkBestConfig()
        }

        root.addButton("Остановить проверку") {
            DiagnosticsLogger.log(this, "UI", "Button stop check")
            checkCancelRequested = true
            setStatus("остановка проверки запрошена. Текущая проверка завершится и цикл остановится")
        }

        root.addButton("Отключить") {
            AutoVlessVpnService.stop(this)
            setStatus("VPN остановлен")
        }

        root.addButton("Очистить invalid") {
            DiagnosticsLogger.log(this, "UI", "Button clear invalid")
            store.clearInvalid()
            refreshCounters()
            setStatus("invalid-список очищен")
        }

        root.addButton("Скопировать все из консоли") {
            copyDiagnosticLog()
        }

        root.addButton("Очистить консоль") {
            DiagnosticsLogger.clear(this)
            consoleBuffer.clear()
            consoleView.text = ""
            setStatus("лог очищен")
        }

        val consoleTitle = TextView(this).apply {
            text = "Консоль действий"
            textSize = 16f
            setPadding(0, 28, 0, 8)
        }
        root.addView(consoleTitle)

        consoleView = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 220, 220))
            setBackgroundColor(Color.rgb(24, 24, 24))
            setPadding(12, 12, 12, 12)
            setTextIsSelectable(true)
        }
        consoleScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(24, 24, 24))
            addView(consoleView, ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(consoleScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(320)
        ).apply {
            topMargin = 4
        })

        val note = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}: живая консоль + копирование полного лога. Если кажется, что зависло, смотри последнюю строку консоли."
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        root.addView(note)

        return ScrollView(this).apply { addView(root) }
    }

    private fun LinearLayout.addButton(label: String, action: () -> Unit) {
        val button = Button(this@MainActivity).apply {
            text = label
            textSize = 15f
            setOnClickListener { action() }
        }
        addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 12
        })
    }

    private fun updateList() {
        DiagnosticsLogger.log(this, "UI", "Button update list")
        setStatus("Скачиваю vless_universal.txt и vless_lite.txt...")
        executor.execute {
            try {
                val text = SubscriptionFetcher().fetchAll()
                DiagnosticsLogger.log(this, "UpdateList", "Downloaded subscription chars=${text.length}")
                val parsed = VlessParser.parse(text)
                DiagnosticsLogger.log(this, "UpdateList", "Parsed nodes=${parsed.size}")
                val invalidKeys = store.getInvalidKeys()
                val supported = parsed.filter { it.isSupportedByThisApp() }
                val unsupportedCount = parsed.size - supported.size
                val filtered = supported.filterNot { it.nodeKey in invalidKeys }

                if (parsed.isEmpty()) {
                    postStatus("Ошибка: VLESS-конфиги не найдены. Старый active-список оставлен.")
                    return@execute
                }

                DiagnosticsLogger.log(this, "UpdateList", "supported=${supported.size} unsupported=$unsupportedCount invalidFiltered=${supported.size - filtered.size} activeNew=${filtered.size}")
                store.replaceActive(filtered)
                postUi {
                    refreshCounters()
                    selectedView.text = "Текущий конфиг: не выбран"
                    setStatus("Список обновлен. Скачано: ${parsed.size}. Не поддерживается: $unsupportedCount. Отфильтровано invalid: ${supported.size - filtered.size}. Активных: ${filtered.size}")
                }
            } catch (e: Throwable) {
                DiagnosticsLogger.log(this, "UpdateList", "ERROR ${e.message ?: e.javaClass.simpleName}")
                postStatus("Ошибка обновления: ${e.message ?: "unknown"}. Старый active-список оставлен.")
            }
        }
    }

    private fun checkBestConfig() {
        DiagnosticsLogger.log(this, "UI", "Button check best config")
        if (checkRunning) {
            setStatus("проверка уже идет. Смотри консоль или нажми 'Остановить проверку'")
            return
        }
        if (!LibboxRuntime.isLibboxAvailable()) {
            refreshCounters()
            setStatus(LibboxRuntime.libboxStatusText())
            return
        }

        checkCancelRequested = false
        checkRunning = true
        setStatus("Проверяю active-список через реальный VLESS speed-check...")
        executor.execute {
            try {
                val speedTester = SpeedTester(this)
                val nodes = store.getActiveNodes().shuffled()
                DiagnosticsLogger.log(this, "CheckBest", "Active nodes to check=${nodes.size}")

                if (nodes.isEmpty()) {
                    postStatus("Нет active-конфигов. Нажми 'Обновить список'.")
                    return@execute
                }

                var checked = 0
                var bestSpeed = 0.0
                var bestNodeLabel = ""
                var unsupportedCount = 0
                var slowCount = 0
                var failedCount = 0
                val sampleErrors = linkedSetOf<String>()
                var sameCriticalStartFailures = 0
                for (node in nodes) {
                    if (checkCancelRequested) {
                        DiagnosticsLogger.log(this, "CheckBest", "Stopped by user before item ${checked + 1}/${nodes.size}")
                        postUi {
                            refreshCounters()
                            setStatus("проверка остановлена. Проверено: $checked/${nodes.size}. FAIL: $failedCount, SLOW: $slowCount, UNSUPPORTED: $unsupportedCount")
                        }
                        return@execute
                    }

                    checked++
                    postStatus("Проверка $checked/${nodes.size}: ${node.remark ?: node.server}")

                    DiagnosticsLogger.log(this, "CheckBest", "Checking $checked/${nodes.size}: ${DiagnosticsLogger.nodeSummary(node)}")
                    val result = speedTester.test(node, MIN_SPEED_KBPS)
                    DiagnosticsLogger.log(this, "CheckBest", "Result $checked/${nodes.size}: status=${result.status} speed=${result.speedKbps} msg=${result.message}")
                    when (result.status) {
                        SpeedTestStatus.OK -> {
                            val speed = result.speedKbps ?: 0.0
                            val vpnConfig = SingBoxConfigGenerator(this).generateVpnConfig(node)
                            postUi {
                                refreshCounters()
                                selectedView.text = "Текущий конфиг: ${node.remark ?: node.server}:${node.port}\nСкорость: ${speed.format1()} КБ/с"
                                setStatus("Найден конфиг >= ${MIN_SPEED_KBPS.toInt()} КБ/с. Запускаю VPN...")
                                startVpnWithPermission(vpnConfig)
                            }
                            return@execute
                        }

                        SpeedTestStatus.BELOW_THRESHOLD -> {
                            val speed = result.speedKbps ?: 0.0
                            if (speed > bestSpeed) {
                                bestSpeed = speed
                                bestNodeLabel = node.remark ?: node.server
                            }
                            slowCount++
                            store.deleteActiveByKey(node.nodeKey)
                            store.addInvalid(
                                node,
                                "SPEED_BELOW_${MIN_SPEED_KBPS.toInt()}_KBPS",
                                result.speedKbps,
                                result.message
                            )
                            postUi { refreshCounters() }
                        }

                        SpeedTestStatus.CONFIG_UNSUPPORTED -> {
                            unsupportedCount++
                            result.message?.take(180)?.let { sampleErrors += "${result.status}: $it" }
                            store.deleteActiveByKey(node.nodeKey)
                            store.addInvalid(node, result.status.name, result.speedKbps, result.message)
                            postUi { refreshCounters() }
                        }

                        SpeedTestStatus.CONNECTION_FAILED,
                        SpeedTestStatus.PROXY_PORT_FAILED,
                        SpeedTestStatus.LIBBOX_START_FAILED -> {
                            failedCount++
                            result.message?.take(180)?.let { sampleErrors += "${result.status}: $it" }
                            // FAIL is usually a test/runtime problem or temporary routing problem.
                            // Do not burn the node into invalid. Leave it active for the next fixed checker.
                            if (result.status == SpeedTestStatus.LIBBOX_START_FAILED) {
                                sameCriticalStartFailures++
                                if (sameCriticalStartFailures >= 5) {
                                    postUi {
                                        refreshCounters()
                                        setStatus("Проверка остановлена: libbox не стартует одинаково на первых 5 конфигах. Последняя ошибка: ${result.message ?: result.status.name}")
                                    }
                                    return@execute
                                }
                            }
                            postUi { refreshCounters() }
                        }

                        SpeedTestStatus.LIBBOX_MISSING -> {
                            postStatus(LibboxRuntime.libboxStatusText() + " Проверка остановлена.")
                            return@execute
                        }
                    }
                }

                postUi {
                    refreshCounters()
                    selectedView.text = "Текущий конфиг: не выбран"
                    val errorsText = if (sampleErrors.isNotEmpty()) " Последние ошибки: " + sampleErrors.take(3).joinToString(" | ") else ""
                    setStatus("Конфигов >= ${MIN_SPEED_KBPS.toInt()} КБ/с не найдено. Лучший результат: ${bestSpeed.format1()} КБ/с ${if (bestNodeLabel.isNotBlank()) "($bestNodeLabel)" else ""}. UNSUPPORTED: $unsupportedCount, SLOW: $slowCount, FAIL: $failedCount. Нажми \"Скопировать все из консоли\" и пришли лог.$errorsText")
                }
            } finally {
                checkRunning = false
            }
        }
    }

    private fun startVpnWithPermission(configContent: String) {
        pendingVpnConfig = configContent
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
        } else {
            startPendingVpn()
        }
    }

    private fun startPendingVpn() {
        val config = pendingVpnConfig
        if (config.isNullOrBlank()) {
            setStatus("Нет выбранного VPN-конфига")
            return
        }
        DiagnosticsLogger.log(this, "VPN", "Starting VPN with selected config chars=${config.length}")
        AutoVlessVpnService.start(this, config)
        setStatus("VPN запускается")
    }

    @Deprecated("Deprecated in Android API, but enough for this minimal project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startPendingVpn()
            } else {
                setStatus("VPN-разрешение не выдано")
            }
        }
    }

    private fun refreshCounters() {
        countersView.text = "Активных конфигов: ${store.getActiveRaw().size}\nНевалидных конфигов: ${store.invalidCount()}"
        libboxView.text = LibboxRuntime.libboxStatusText()
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun postStatus(text: String) {
        postUi { setStatus(text) }
    }

    private fun postUi(action: () -> Unit) {
        mainHandler.post { action() }
    }

    private fun setStatus(text: String) {
        DiagnosticsLogger.log(this, "Status", text)
        statusView.text = "Статус: $text"
    }

    private fun loadConsoleFromDisk() {
        val text = DiagnosticsLogger.read(this, CONSOLE_MAX_CHARS).takeLast(CONSOLE_MAX_CHARS)
        consoleBuffer.clear()
        consoleBuffer.append(text)
        consoleView.text = text
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendConsoleLine(line: String) {
        if (!::consoleView.isInitialized) return
        consoleBuffer.append(line)
        if (consoleBuffer.length > CONSOLE_MAX_CHARS) {
            consoleBuffer.delete(0, consoleBuffer.length - CONSOLE_TRIM_TO_CHARS)
        }
        consoleView.text = consoleBuffer.toString()
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyDiagnosticLog() {
        val logText = DiagnosticsLogger.read(this, 160_000)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AutoVLESS console", logText))
        Toast.makeText(this, "Консоль скопирована", Toast.LENGTH_SHORT).show()
        setStatus("вся консоль скопирована в буфер. Пришли ее сюда текстом")
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

    override fun onDestroy() {
        DiagnosticsLogger.setLiveSink(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
        private const val MIN_SPEED_KBPS = 500.0
        private const val CONSOLE_MAX_CHARS = 90_000
        private const val CONSOLE_TRIM_TO_CHARS = 70_000
    }
}
