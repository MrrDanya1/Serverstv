package tv.serverstv.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private data class ServerItem(
    val name: String,
    val protocol: String,
    val config: JSONObject
)

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionTimeout: Runnable? = null
    private val servers = mutableListOf<ServerItem>()
    private lateinit var root: LinearLayout
    private lateinit var serverList: LinearLayout
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var connectButton: TextView
    private lateinit var refreshButton: TextView
    private var selectedIndex = 0
    private var connected = false
    private var loading = false
    private var receiverRegistered = false

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(XrayVpnService.EXTRA_STATUS) ?: return
            val detail = intent.getStringExtra(XrayVpnService.EXTRA_DETAIL).orEmpty()
            runOnUiThread { showVpnState(state, detail) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(9, 11, 15)
        window.navigationBarColor = Color.rgb(9, 11, 15)
        buildInterface()

        val filter = IntentFilter(XrayVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnReceiver, filter)
        }
        receiverRegistered = true
        loadServers()
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(34), dp(24), dp(34), dp(24))
            setBackgroundColor(Color.rgb(9, 11, 15))
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        connectButton = TextView(this).apply {
            text = "⏻"
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(150, 160, 170))
            background = circleBackground(Color.rgb(19, 24, 30), Color.rgb(55, 65, 75), 5)
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = "Подключить VPN"
            setOnClickListener { toggleVpn() }
            setOnFocusChangeListener { view, hasFocus ->
                view.scaleX = if (hasFocus) 1.04f else 1f
                view.scaleY = if (hasFocus) 1.04f else 1f
                if (hasFocus && !connected) connectButton.setTextColor(Color.WHITE)
            }
        }
        left.addView(connectButton, LinearLayout.LayoutParams(dp(220), dp(220)))
        statusText = textView("Не подключено", 25f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        detailText = textView("Загружаю список серверов…", 15f, MUTED, false).apply {
            gravity = Gravity.CENTER
            maxLines = 3
        }
        left.addView(statusText)
        left.addView(detailText)
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(textView("Серверы", 34f, Color.WHITE, true))
        countText = textView("Загрузка…", 14f, MUTED, false).apply {
            setPadding(0, dp(5), 0, dp(18))
        }
        heading.addView(countText)
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        refreshButton = textView("↻", 32f, GREEN, true).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(PANEL, LINE, 100)
            isFocusable = true
            isClickable = true
            contentDescription = "Обновить список серверов"
            setOnClickListener { loadServers(force = true) }
        }
        header.addView(refreshButton, LinearLayout.LayoutParams(dp(58), dp(58)))
        right.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(2))
        }
        serverList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(PANEL, LINE, 18)
            clipToOutline = true
        }
        scroll.addView(serverList)
        right.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        right.addView(textView("ServersTV", 12f, MUTED, false).apply {
            setPadding(0, dp(14), 0, 0)
        })
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        setContentView(root)
        connectButton.requestFocus()
    }

    private fun loadServers(force: Boolean = false) {
        if (loading) return
        loading = true
        refreshButton.alpha = 0.5f
        if (servers.isEmpty()) {
            countText.text = "Загружаю серверы…"
            detailText.text = "Подключаюсь к хранилищу"
        }
        io.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$API_BASE?action=servers").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "ServersTV/1.0 AndroidTV")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw IllegalStateException("Сервер вернул HTTP $code")
                }
                val parsed = parseServers(body)
                runOnUiThread {
                    loading = false
                    refreshButton.alpha = 1f
                    servers.clear()
                    servers.addAll(parsed)
                    selectedIndex = selectedIndex.coerceIn(0, (servers.size - 1).coerceAtLeast(0))
                    renderServers()
                    if (servers.isEmpty()) {
                        detailText.text = "В подписке пока нет серверов"
                    } else {
                        detailText.text = "Выбери сервер и нажми кнопку подключения"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    refreshButton.alpha = 1f
                    countText.text = "Не удалось загрузить"
                    detailText.text = e.message ?: "Проверь интернет и попробуй ещё раз"
                    renderError(e.message ?: "Ошибка загрузки")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun parseServers(body: String): List<ServerItem> {
        val rootValue = JSONTokener(body).nextValue()
        val array = findServerArray(rootValue)
            ?: throw IllegalStateException("Supabase вернул неожиданный формат данных")
        val result = mutableListOf<ServerItem>()
        for (index in 0 until array.length()) {
            val raw = array.opt(index)
            val item = raw as? JSONObject
                ?: throw IllegalStateException("Сервер №${index + 1}: ожидался JSON-объект")
            val config = findConfig(item)
                ?: throw IllegalStateException("Сервер №${index + 1}: не найдена конфигурация Xray")
            val outbounds = config.optJSONArray("outbounds")
            if (outbounds == null || outbounds.length() == 0) {
                throw IllegalStateException("Сервер №${index + 1}: отсутствуют outbounds")
            }
            val outbound = outbounds.optJSONObject(0)
            val protocol = outbound?.optString("protocol").orEmpty()
                .ifBlank { "Xray" }.uppercase()
            val name = firstNonBlank(
                item.optString("remarks"), item.optString("name"),
                item.optString("server_name"), item.optString("title"),
                outbound?.optString("tag").orEmpty(), "Сервер ${index + 1}"
            )
            result.add(ServerItem(name, protocol, config))
        }
        return result
    }

    private fun findServerArray(value: Any?): JSONArray? {
        if (value is JSONArray) return value
        if (value is String) {
            return try { findServerArray(JSONTokener(value).nextValue()) } catch (_: Exception) { null }
        }
        if (value is JSONObject) {
            for (key in arrayOf("servers", "configs", "items", "data", "result")) {
                val nested = value.opt(key)
                val found = findServerArray(nested)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findConfig(item: JSONObject): JSONObject? {
        if (item.optJSONArray("outbounds") != null) return item
        for (key in arrayOf("config", "configuration", "xray_config", "config_json", "json", "content")) {
            when (val value = item.opt(key)) {
                is JSONObject -> return value
                is String -> try {
                    val parsed = JSONTokener(value).nextValue()
                    if (parsed is JSONObject) {
                        val nested = findConfig(parsed)
                        if (nested != null) return nested
                    }
                } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun renderServers() {
        serverList.removeAllViews()
        countText.text = if (servers.isEmpty()) "Нет доступных серверов" else "Доступно серверов: ${servers.size}"
        if (servers.isEmpty()) {
            val empty = textView("Список пуст", 18f, MUTED, false).apply {
                setPadding(dp(22), dp(24), dp(22), dp(24))
            }
            serverList.addView(empty)
            return
        }
        servers.forEachIndexed { index, server ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                isFocusable = true
                isClickable = true
                background = roundedBackground(
                    if (index == selectedIndex) ACTIVE else PANEL,
                    if (index == selectedIndex) GREEN else PANEL,
                    12
                )
                setOnClickListener {
                    selectedIndex = index
                    renderServers()
                    detailText.text = "Выбран: ${server.name}"
                }
                setOnFocusChangeListener { view, focused ->
                    if (focused) view.background = roundedBackground(ACTIVE, GREEN, 12)
                    else view.background = roundedBackground(
                        if (index == selectedIndex) ACTIVE else PANEL,
                        if (index == selectedIndex) GREEN else PANEL, 12
                    )
                }
            }
            val flag = textView(countryFlag(server.name), 24f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(Color.rgb(31, 39, 48), Color.TRANSPARENT, 12)
            }
            row.addView(flag, LinearLayout.LayoutParams(dp(48), dp(48)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            labels.addView(textView(cleanName(server.name), 18f, Color.WHITE, true))
            labels.addView(textView(server.protocol, 13f, MUTED, false).apply {
                setPadding(0, dp(4), 0, 0)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val mark = textView(if (index == selectedIndex) "✓" else "", 22f, GREEN, true).apply {
                gravity = Gravity.CENTER
            }
            row.addView(mark, LinearLayout.LayoutParams(dp(28), dp(48)))
            serverList.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3) })
        }
    }

    private fun renderError(message: String) {
        serverList.removeAllViews()
        val error = textView(message, 16f, Color.WHITE, false).apply {
            setPadding(dp(22), dp(22), dp(22), dp(12))
        }
        serverList.addView(error)
        val retry = actionText("Повторить загрузку") { loadServers(force = true) }
        serverList.addView(retry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(62)
        ).apply { setMargins(dp(12), dp(4), dp(12), dp(12)) })
    }

    private fun toggleVpn() {
        if (connected) {
            startService(Intent(this, XrayVpnService::class.java).apply {
                action = XrayVpnService.ACTION_STOP
            })
            return
        }
        val server = servers.getOrNull(selectedIndex)
        if (server == null) {
            detailText.text = "Сначала загрузи список серверов"
            loadServers()
            return
        }
        statusText.text = "Подключение…"
        detailText.text = server.name
        val permission = VpnService.prepare(this)
        if (permission != null) {
            pendingConfig = server.config.toString()
            @Suppress("DEPRECATION")
            startActivityForResult(permission, VPN_PERMISSION_REQUEST)
        } else {
            startVpn(server.config.toString())
        }
    }

    private var pendingConfig: String? = null

    private fun startVpn(config: String) {
        connectionTimeout?.let(mainHandler::removeCallbacks)
        val timeout = Runnable {
            if (statusText.text.toString() == "Подключение…") {
                showVpnState("error", "VPN-служба не ответила за 60 секунд. Попробуй подключиться ещё раз.")
                try {
                    startService(Intent(this, XrayVpnService::class.java).apply {
                        action = XrayVpnService.ACTION_STOP
                    })
                } catch (_: Exception) { }
            }
        }
        connectionTimeout = timeout
        mainHandler.postDelayed(timeout, 60_000L)

        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG, config)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            showVpnState("error", e.message ?: "Не удалось запустить VPN-службу")
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK) {
                pendingConfig?.let(::startVpn)
            } else {
                showVpnState("error", "Разрешение VPN не выдано")
            }
            pendingConfig = null
        }
    }

    private fun showVpnState(state: String, detail: String) {
        when (state) {
            "starting" -> {
                connected = false
                statusText.text = "Подключение…"
                detailText.text = detail
            }
            "connected" -> {
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                connected = true
                connectButton.text = "✓"
                connectButton.setTextColor(GREEN)
                connectButton.background = circleBackground(Color.rgb(19, 35, 27), GREEN, 5)
                statusText.text = "Подключено"
                detailText.text = servers.getOrNull(selectedIndex)?.name ?: detail
            }
            "disconnected" -> {
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                connected = false
                connectButton.text = "⏻"
                connectButton.setTextColor(Color.WHITE)
                connectButton.background = circleBackground(Color.rgb(19, 24, 30), LINE, 5)
                statusText.text = "Не подключено"
                detailText.text = detail.ifBlank { "Выбери сервер для подключения" }
            }
            "error" -> {
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                connected = false
                connectButton.text = "⏻"
                connectButton.setTextColor(Color.WHITE)
                connectButton.background = circleBackground(Color.rgb(36, 22, 22), Color.rgb(180, 75, 75), 5)
                statusText.text = "Ошибка подключения"
                detailText.text = detail
            }
        }
    }

    private fun actionText(label: String, action: () -> Unit): TextView =
        textView(label, 17f, GREEN, true).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(ACTIVE, GREEN, 12)
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }

    private fun textView(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun roundedBackground(color: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun circleBackground(color: Int, stroke: Int, width: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(width), stroke)
        }

    private fun countryFlag(name: String): String {
        val flags = Regex("🇦🇱|🇦🇩|🇦🇹|🇧🇪|🇧🇬|🇭🇷|🇨🇾|🇨🇿|🇩🇰|🇪🇪|🇫🇮|🇫🇷|🇩🇪|🇬🇷|🇭🇺|🇮🇸|🇮🇪|🇮🇹|🇱🇻|🇱🇹|🇱🇺|🇲🇹|🇳🇱|🇳🇴|🇵🇱|🇵🇹|🇷🇴|🇷🇸|🇸🇰|🇸🇮|🇪🇸|🇸🇪|🇨🇭|🇹🇷|🇬🇧|🇺🇸|🇨🇦|🇯🇵|🇸🇬|🇷🇺")
        return flags.find(name)?.value ?: "🌐"
    }

    private fun cleanName(name: String): String =
        name.replace(Regex("🇦🇱|🇦🇩|🇦🇹|🇧🇪|🇧🇬|🇭🇷|🇨🇾|🇨🇿|🇩🇰|🇪🇪|🇫🇮|🇫🇷|🇩🇪|🇬🇷|🇭🇺|🇮🇸|🇮🇪|🇮🇹|🇱🇻|🇱🇹|🇱🇺|🇲🇹|🇳🇱|🇳🇴|🇵🇱|🇵🇹|🇷🇴|🇷🇸|🇸🇰|🇸🇮|🇪🇸|🇸🇪|🇨🇭|🇹🇷|🇬🇧|🇺🇸|🇨🇦|🇯🇵|🇸🇬|🇷🇺"), "")
            .trim().ifBlank { name }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(vpnReceiver)
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 4107
        private const val API_BASE = "https://fgwvyrhjcofebyluxtbg.supabase.co/functions/v1/servers-bot"
        private const val GREEN = 0xFF8CF0B2.toInt()
        private const val MUTED = 0xFF89929E.toInt()
        private const val PANEL = 0xFF11151C.toInt()
        private const val ACTIVE = 0xFF1B2522.toInt()
        private const val LINE = 0xFF252B34.toInt()
    }
}
