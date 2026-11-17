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
import libXray.DialerController
import libXray.LibXray
import java.util.concurrent.Executors

class XrayVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var xrayStarted = false
    private var controllerRegistered = false
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
                sendStatus("starting", "Запускаю VPN-службу…")
                worker.execute { startTunnel(config) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(source: String) {
        try {
            if (tun != null || xrayStarted) {
                stopTunnel(stopService = false, notify = false)
            }
            sendStatus("starting", "Проверяю конфигурацию сервера…")
            val root = JSONObject(source)
            if (root.optJSONArray("outbounds").isNullOrEmpty()) {
                throw IllegalArgumentException("В конфигурации нет outbounds")
            }

            val descriptor = Builder()
                .setSession("ServersTV")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addAddress("fd00:1111:1111::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .establish() ?: throw IllegalStateException("Android не создал VPN-интерфейс")

            tun = descriptor
            sendStatus("starting", "VPN-интерфейс создан. Регистрирую защиту сокетов…")
            registerProtectController()
            sendStatus("starting", "Защита сети настроена. Запускаю Xray…")

            val env = root.optJSONObject("env") ?: JSONObject().also { root.put("env", it) }
            env.put("xray.tun.fd", descriptor.fd.toString())

            val inbounds = JSONArray()
            inbounds.put(JSONObject()
                .put("tag", "tun-in")
                .put("port", 0)
                .put("protocol", "tun")
                .put("settings", JSONObject()
                    .put("name", "ServersTV")
                    .put("mtu", 1500)))
            root.put("inbounds", inbounds)

            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "runXray")
                .put("payload", JSONObject().put("xrayJson", root.toString()))
                .toString()

            val response = invokeLibXray(request)
            val result = JSONObject(response)
            if (!result.optBoolean("success", false)) {
                throw IllegalStateException(result.optString("error", "Xray не запустился"))
            }
            xrayStarted = true
            val stateRequest = JSONObject()
                .put("apiVersion", 3)
                .put("method", "getXrayState")
                .put("payload", JSONObject())
                .toString()
            val stateResponse = JSONObject(invokeLibXray(stateRequest))
            val running = stateResponse.optJSONObject("data")?.optBoolean("running", false) == true
            if (!running) throw IllegalStateException("Xray сообщил, что VPN не запущен")
            updateNotification("VPN запущен")
            sendStatus("connected", "Подключено")
        } catch (e: Throwable) {
            // Keep native-library/linkage failures inside the VPN process.
            try { stopTunnel(stopService = false, notify = false) } catch (_: Throwable) { }
            val message = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            try { updateNotification("Ошибка VPN: $message") } catch (_: Throwable) { }
            sendStatus("error", message)
            stopSelf()
        }
    }

    private var dialerController: DialerController? = null

    private fun registerProtectController() {
        val controller = object : DialerController {
            override fun protectFd(fd: Int): Boolean = protect(fd)
        }
        dialerController = controller
        LibXray.registerDialerController(controller)
        controllerRegistered = true
        sendStatus("starting", "Защита сокетов включена. Настраиваю DNS…")
        LibXray.setDNS(controller, "1.1.1.1:53")
        sendStatus("starting", "DNS настроен. Подготавливаю запуск Xray…")
    }

    private fun invokeLibXray(request: String): String =
        LibXray.invoke(request)

    private fun stopTunnel(stopService: Boolean = true, notify: Boolean = true) {
        if (xrayStarted) {
            try {
                val request = JSONObject()
                    .put("apiVersion", 3)
                    .put("method", "stopXray")
                    .put("payload", JSONObject())
                    .toString()
                invokeLibXray(request)
            } catch (_: Throwable) {
            }
            xrayStarted = false
        }
        if (controllerRegistered) {
            try {
                LibXray.resetDNS()
            } catch (_: Throwable) {
            }
            controllerRegistered = false
        }
        dialerController = null
        try { tun?.close() } catch (_: Throwable) { }
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
