package dev.agzes.swebapp.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COPY_URL = "dev.agzes.swebapp.ACTION_COPY_URL"
        const val ACTION_START_SERVER = "dev.agzes.swebapp.ACTION_START_SERVER"
        const val ACTION_STOP_SERVER = "dev.agzes.swebapp.ACTION_STOP_SERVER"
        const val EXTRA_URL = "extra_url"
    }

    private val receiverScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_COPY_URL) {
            val url = intent.getStringExtra(EXTRA_URL)
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                Toast.makeText(context, "URL Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        } else if (intent.action == ACTION_START_SERVER) {
            receiverScope.launch {
                val showNotification = dev.agzes.swebapp.data.SettingsRepository(context).showNotificationFlow.first()
                val serviceIntent = Intent(context, dev.agzes.swebapp.service.ServerService::class.java).apply {
                    action = dev.agzes.swebapp.service.ServerService.ACTION_START
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && showNotification) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } else if (intent.action == ACTION_STOP_SERVER) {
            val serviceIntent = Intent(context, dev.agzes.swebapp.service.ServerService::class.java).apply {
                action = dev.agzes.swebapp.service.ServerService.ACTION_STOP
            }
            context.startService(serviceIntent)
        }
    }
}
