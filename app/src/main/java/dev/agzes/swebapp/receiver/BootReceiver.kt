package dev.agzes.swebapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.agzes.swebapp.data.SettingsRepository
import dev.agzes.swebapp.service.ServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            val settings = SettingsRepository(context)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val autoStart = settings.autoStartFlow.first()
                    val folderUri = settings.selectedFolderUriFlow.first()
                    if (autoStart && !folderUri.isNullOrEmpty()) {
                        val serviceIntent = Intent(context, ServerService::class.java).apply {
                            action = ServerService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
