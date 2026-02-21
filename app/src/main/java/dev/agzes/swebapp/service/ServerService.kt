package dev.agzes.swebapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.agzes.swebapp.MainActivity
import dev.agzes.swebapp.R
import dev.agzes.swebapp.data.SettingsRepository
import dev.agzes.swebapp.server.SpaServer
import dev.agzes.swebapp.server.ServerLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ServerService : Service() {

    companion object {
        const val ACTION_START = "dev.agzes.swebapp.ACTION_START"
        const val ACTION_STOP = "dev.agzes.swebapp.ACTION_STOP"
        private const val CHANNEL_ID = "SWebAppServerChannel_v3"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var spaServer: SpaServer? = null
    private lateinit var settingsRepository: SettingsRepository
    private var batteryReceiver: android.content.BroadcastReceiver? = null

    private var nsdManager: NsdManager? = null
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {}
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                val folderUri = settingsRepository.selectedFolderUriFlow.first()
                if (folderUri.isNullOrEmpty()) {
                    stopSelf()
                    return@launch
                }

                val port = settingsRepository.portFlow.first().coerceIn(1024, 65535)
                val localhostOnly = settingsRepository.localhostOnlyFlow.first()
                val spaMode = settingsRepository.spaModeFlow.first()
                val hotReload = settingsRepository.hotReloadFlow.first()
                val batterySaver = settingsRepository.batterySaverFlow.first()
                val showNotification = settingsRepository.showNotificationFlow.first()
                val restrictWebFeatures = settingsRepository.restrictWebFeaturesFlow.first()
                val currentThemeMode = settingsRepository.themeModeFlow.first()
                val isSystemDark =
                    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val isDarkTheme = when (currentThemeMode) {
                    1 -> false // light
                    2 -> true  // dark
                    else -> isSystemDark
                }

                if (batterySaver) {
                    batteryReceiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == Intent.ACTION_BATTERY_LOW) {
                                ServerLogger.log("Battery Low: Auto-stopping server")
                                stopServer()
                            }
                        }
                    }
                    val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_LOW)
                    registerReceiver(batteryReceiver, filter)
                }

                spaServer = SpaServer(applicationContext)
                spaServer?.start(port, localhostOnly, spaMode, hotReload, folderUri, isDarkTheme, restrictWebFeatures)
                isRunning = true

                val displayHost =
                    if (localhostOnly) "127.0.0.1" else (dev.agzes.swebapp.getLocalIpAddress() ?: "0.0.0.0")
                val url = "http://$displayHost:$port"
                if (showNotification) {
                    startForeground(NOTIFICATION_ID, createNotification(url))
                }

                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = "SWebApp"
                    serviceType = "_http._tcp."
                    setPort(port)
                }
                nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

                updateWidgetInfo(url)
            } catch (e: Exception) {
                ServerLogger.log("Server start error: ${e.javaClass.simpleName}: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun updateWidgetInfo(url: String? = null) {
        val widgetCls = dev.agzes.swebapp.widget.ServerWidgetProvider::class.java
        if (url != null) {
            val intent = Intent(this, widgetCls).apply {
                action = dev.agzes.swebapp.widget.ServerWidgetProvider.ACTION_UPDATE_WIDGET
                putExtra("extra_url", url)
            }
            sendBroadcast(intent)
        } else {
            val intent = Intent(this, widgetCls).apply {
                action = dev.agzes.swebapp.widget.ServerWidgetProvider.ACTION_REFRESH_WIDGET
            }
            sendBroadcast(intent)
        }
    }

    private fun stopServer() {
        isRunning = false
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (e: Exception) {
        }
        try {
            batteryReceiver?.let {
                unregisterReceiver(it)
                batteryReceiver = null
            }
        } catch (e: Exception) {
        }
        spaServer?.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        updateWidgetInfo()
        stopSelf()
    }

    private fun createNotification(url: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val copyIntent = Intent(this, dev.agzes.swebapp.receiver.ActionReceiver::class.java).apply {
            action = dev.agzes.swebapp.receiver.ActionReceiver.ACTION_COPY_URL
            putExtra(dev.agzes.swebapp.receiver.ActionReceiver.EXTRA_URL, url)
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            this, 2, copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SWebApp Server")
            .setContentText("Running on $url")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_edit, "Copy URL", copyPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SWebApp Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background server notification"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        spaServer?.stop()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
