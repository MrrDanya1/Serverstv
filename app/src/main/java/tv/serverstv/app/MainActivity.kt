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
        fun loadServers() {
            fetchSubscriptionServers()
        }

        @JavascriptInterface
        fun saveSubscriptionUrl(value: String) {
            val raw = value.trim()
            try {
                val uri = Uri.parse(raw)
                require(uri.scheme == "https")
                require(uri.host == "fgwvyrhjcofebyluxtbg.supabase.co")
                require(uri.path == "/functions/v1/servers-bot")
                require(!uri.getQueryParameter("subscription").isNullOrBlank())
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_SUBSCRIPTION_URL, raw).apply()
                runOnUiThread { fetchSubscriptionServers() }
            } catch (_: Exception) {
                runOnUiThread {
                    web.evaluateJavascript("window.onServersError && window.onServersError('Неверная ссылка подписки')", null)
                }
            }
        }

        @JavascriptInterface
        fun pasteSubscriptionFromClipboard() {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val value = if (clipboard.hasPrimaryClip()) {
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
            } else ""
            runOnUiThread {
                web.evaluateJavascript(
                    "window.onSubscriptionPasted && window.onSubscriptionPasted(${JSONObject.quote(value)})",
                    null
                )
            }
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

    private fun fetchSubscriptionServers() {
        val subscriptionUrl = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_SUBSCRIPTION_URL, null)
        if (subscriptionUrl.isNullOrBlank()) {
            runOnUiThread {
                web.evaluateJavascript("window.onServersError && window.onServersError('NO_SUBSCRIPTION')", null)
            }
            return
        }
        io.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(subscriptionUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "ServersTV/0.2 Android")
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
        private const val KEY_SUBSCRIPTION_URL = "subscription_url"
    }
}

private fun org.json.JSONArray?.isNullOrEmpty(): Boolean = this == null || length() == 0
