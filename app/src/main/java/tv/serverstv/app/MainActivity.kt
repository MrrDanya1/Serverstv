package tv.serverstv.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pendingConfig: String? = null

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
        web.loadUrl("file:///android_asset/index.html")
    }

    inner class VpnBridge {
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
                startService(Intent(this@MainActivity, XrayVpnService::class.java).apply {
                    action = XrayVpnService.ACTION_STOP
                })
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            pendingConfig?.let(::startVpn)
        } else if (requestCode == VPN_PERMISSION_REQUEST) {
            web.evaluateJavascript("window.onVpnError && window.onVpnError('Разрешение VPN не выдано')", null)
        }
    }

    private fun startVpn(config: String) {
        startService(Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG, config)
        })
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 4107
    }
}
