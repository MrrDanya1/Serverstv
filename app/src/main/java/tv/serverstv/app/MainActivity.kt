package tv.serverstv.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()
    private var pendingConfig: String? = null
    private var receiverRegistered = false

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(XrayVpnService.EXTRA_STATUS) ?: return
            val detail = intent.getStringExtra(XrayVpnService.EXTRA_DETAIL) ?: ""
            if (::web.isInitialized) {
                web.evaluateJavascript(
                    "window.onVpnStatus && window.onVpnStatus(${JSONObject.quote(state)}, ${JSONObject.quote(detail)})",
                    null
                )
            }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.addJavascriptInterface(VpnBridge(), "AndroidVpn")
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        setContentView(web)
        val filter = IntentFilter(XrayVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnReceiver, filter)
        }
        receiverRegistered = true
        web.loadUrl("file:///android_asset/index.html")
    }

    inner class VpnBridge {
        @JavascriptInterface
        fun registerDevice() {
            io.execute { sendRegistrationRequest() }
        }

        @JavascriptInterface
        fun checkRegistration() {
            io.execute { checkRegistrationStatus() }
        }

        @JavascriptInterface
        fun loadServers() {
            fetchSubscriptionServers()
        }

        @JavascriptInterface
        fun connect(configJson: String) {
            runOnUiThread {
                pendingConfig = configJson
                val prepare = VpnService.prepare(this@MainActivity)
                if (prepare != null) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(prepare, VPN_PERMISSION_REQUEST)
                } else {
                    startVpn(configJson)
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                val intent = Intent(this@MainActivity, XrayVpnService::class.java).apply {
                    action = XrayVpnService.ACTION_STOP
                }
                startService(intent)
            }
        }
    }

    private fun getDeviceKey(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getString(KEY_DEVICE_KEY, null)
        if (!saved.isNullOrBlank()) return saved
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val key = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_DEVICE_KEY, key).apply()
        return key
    }

    private fun notifyRegistration(status: String, detail: String = "") {
        runOnUiThread {
            web.evaluateJavascript(
                "window.onRegistrationStatus && window.onRegistrationStatus(${JSONObject.quote(status)}, ${JSONObject.quote(detail)})",
                null
            )
        }
    }

    private fun sendRegistrationRequest() {
        var connection: HttpURLConnection? = null
        try {
            val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }.distinct().joinToString(" ").take(100)
                .ifBlank { "Android TV" }
            connection = URL("$API_BASE?action=register").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ServersTV/0.3 Android")
            val body = JSONObject().put("deviceKey", getDeviceKey()).put("deviceName", deviceName).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(JSONObject(response).optString("error", "HTTP $code"))
            notifyRegistration(JSONObject(response).optString("status", "pending"))
        } catch (e: Exception) {
            notifyRegistration("error", e.message ?: "Не удалось отправить запрос")
        } finally {
            connection?.disconnect()
        }
    }

    private fun checkRegistrationStatus() {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$API_BASE?action=status").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("X-Device-Key", getDeviceKey())
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code == 404) {
                notifyRegistration("not_found")
                return
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            notifyRegistration(JSONObject(response).optString("status", "pending"))
        } catch (e: Exception) {
            notifyRegistration("error", e.message ?: "Не удалось проверить статус")
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchSubscriptionServers() {
        io.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$API_BASE?action=servers").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-Device-Key", getDeviceKey())
                connection.setRequestProperty("User-Agent", "ServersTV/0.3 Android")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val parsed = JSONArray(body)
                if (parsed.length() == 0) throw IllegalStateException("В подписке пока нет серверов")
                for (i in 0 until parsed.length()) {
                    val item = parsed.optJSONObject(i)
                        ?: throw IllegalStateException("Некорректная конфигурация №${i + 1}")
                    if (item.optJSONArray("outbounds").isNullOrEmpty()) {
                        throw IllegalStateException("В конфигурации №${i + 1} нет outbounds")
                    }
                }
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.onServersLoaded && window.onServersLoaded(${JSONObject.quote(body)})",
                        null
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.onServersError && window.onServersError(${JSONObject.quote(e.message ?: "Ошибка загрузки")})",
                        null
                    )
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            pendingConfig?.let(::startVpn)
        } else if (requestCode == VPN_PERMISSION_REQUEST) {
            web.evaluateJavascript("window.onVpnStatus && window.onVpnStatus('error','Разрешение VPN не выдано')", null)
        }
    }

    private fun startVpn(config: String) {
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG, config)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(vpnReceiver)
        if (::web.isInitialized) web.destroy()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 4107
        private const val PREFS = "serverstv_settings"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val API_BASE = "https://fgwvyrhjcofebyluxtbg.supabase.co/functions/v1/servers-bot"
    }
}

private fun org.json.JSONArray?.isNullOrEmpty(): Boolean = this == null || length() == 0
