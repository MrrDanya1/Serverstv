package tv.serverstv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

class XrayVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> worker.execute { stopTunnel() }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    sendStatus("error", "Пустая конфигурация сервера")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Подготовка VPN"))
                worker.execute { startTunnel(config) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(source: String) {
        try {
            stopTunnel(stopService = false, notify = false)
            val root = JSONObject(source)
            if (root.optJSONArray("outbounds").isNullOrEmpty()) {
                throw IllegalArgumentException("В конфигурации нет outbounds")
            }

            val descriptor = Builder()
                .setSession("ServersTV")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .establish() ?: throw IllegalStateException("Android не создал VPN-интерфейс")

            tun = descriptor
            registerProtectController()

            val env = root.optJSONObject("env") ?: JSONObject().also { root.put("env", it) }
            env.put("xray.tun.fd", descriptor.fd.toString())

            val inbounds = JSONArray()
            inbounds.put(JSONObject()
                .put("tag", "tun-in")
                .put("protocol", "tun")
                .put("settings", JSONObject()
                    .put("name", "ServersTV")
                    .put("MTU", 1500)))
            root.put("inbounds", inbounds)

            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "runXray")
                .put("payload", JSONObject().put("xrayJson", root.toString()))
                .toString()

            val response = invokeLibXray("invoke", request)
            val result = JSONObject(response)
            if (!result.optBoolean("success", false)) {
                throw IllegalStateException(result.optString("error", "Xray не запустился"))
            }
            updateNotification("VPN запущен")
            sendStatus("connected", "Подключено")
        } catch (e: Exception) {
            stopTunnel(stopService = false, notify = false)
            val message = e.cause?.message ?: e.message ?: "Неизвестная ошибка Xray"
            updateNotification("Ошибка VPN: $message")
            sendStatus("error", message)
            stopSelf()
        }
    }

    private fun registerProtectController() {
        val api = Class.forName("libXray.LibXray")
        val contract = Class.forName("libXray.DialerController")
        val controller = Proxy.newProxyInstance(
            contract.classLoader,
            arrayOf(contract)
        ) { _, method, args ->
            when (method.name) {
                "protectFd" -> {
                    val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                    protect(fd)
                }
                else -> null
            }
        }
        api.getMethod("registerDialerController", contract).invoke(null, controller)
        api.getMethod("setDNS", contract, String::class.java)
            .invoke(null, controller, "1.1.1.1:53")
    }

    private fun invokeLibXray(method: String, request: String? = null): String {
        val api = Class.forName("libXray.LibXray")
        return if (request == null) {
            api.getMethod(method).invoke(null) as? String ?: "{}"
        } else {
            api.getMethod(method, String::class.java).invoke(null, request) as? String ?: "{}"
        }
    }

    private fun stopTunnel(stopService: Boolean = true, notify: Boolean = true) {
        try {
            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "stopXray")
                .put("payload", JSONObject())
                .toString()
            invokeLibXray("invoke", request)
        } catch (_: Exception) {
        }
        try {
            Class.forName("libXray.LibXray").getMethod("resetDNS").invoke(null)
        } catch (_: Exception) {
        }
        tun?.close()
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (notify) sendStatus("disconnected", "Отключено")
        if (stopService) stopSelf()
    }

    private fun sendStatus(status: String, detail: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            .putExtra(EXTRA_DETAIL, detail))
    }

    private fun notification(message: String): Notification {
        val channelId = "serverstv_vpn"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                channelId, "ServersTV VPN", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setContentTitle("ServersTV")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ServersTV")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    override fun onRevoke() {
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        worker.execute { stopTunnel() }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "tv.serverstv.app.START_VPN"
        const val ACTION_STOP = "tv.serverstv.app.STOP_VPN"
        const val ACTION_STATUS = "tv.serverstv.app.VPN_STATUS"
        const val EXTRA_CONFIG = "xray_config"
        const val EXTRA_STATUS = "vpn_status"
        const val EXTRA_DETAIL = "vpn_detail"
        private const val NOTIFICATION_ID = 1001
    }
}

private fun JSONArray?.isNullOrEmpty(): Boolean = this == null || length() == 0
