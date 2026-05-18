package com.autovless.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import com.autovless.app.vless.VlessParser
import com.autovless.app.util.DiagnosticsLogger
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

    private var pendingVpnConfig: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NodeStore(this)
        DiagnosticsLogger.log(this, "MainActivity", "App started version=1.4.0")
        setContentView(buildUi())
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

        root.addButton("Скопировать лог") {
            copyDiagnosticLog()
        }

        root.addButton("Очистить лог") {
            DiagnosticsLogger.clear(this)
            setStatus("лог очищен")
        }

        val note = TextView(this).apply {
            text = "v1.4: добавлен диагностический лог. После проверки нажми \"Скопировать лог\" и пришли текст. В логе есть шаги libbox, proxy, URL-test и download-test."
            textSize = 13f
            setPadding(0, 28, 0, 0)
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
        if (!LibboxRuntime.isLibboxAvailable()) {
            refreshCounters()
            setStatus(LibboxRuntime.libboxStatusText())
            return
        }

        setStatus("Проверяю active-список через реальный VLESS speed-check...")
        executor.execute {
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
                setStatus("Конфигов >= ${MIN_SPEED_KBPS.toInt()} КБ/с не найдено. Лучший результат: ${bestSpeed.format1()} КБ/с ${if (bestNodeLabel.isNotBlank()) "($bestNodeLabel)" else ""}. UNSUPPORTED: $unsupportedCount, SLOW: $slowCount, FAIL: $failedCount. Нажми \"Скопировать лог\" и пришли его.$errorsText")
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

    private fun copyDiagnosticLog() {
        val logText = DiagnosticsLogger.read(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AutoVLESS log", logText))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
        setStatus("лог скопирован в буфер. Пришли его сюда текстом")
    }

    private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
        private const val MIN_SPEED_KBPS = 500.0
    }
}
