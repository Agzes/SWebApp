package dev.agzes.swebapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import dev.agzes.swebapp.MainActivity
import dev.agzes.swebapp.R
import dev.agzes.swebapp.isServiceRunning
import dev.agzes.swebapp.receiver.ActionReceiver
import dev.agzes.swebapp.service.ServerService

class ServerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("last_url", null)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, savedUrl)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("last_url", null)
        updateAppWidget(context, appWidgetManager, appWidgetId, savedUrl)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET || intent.action == ACTION_REFRESH_WIDGET) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val savedUrl = prefs.getString("last_url", null)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = android.content.ComponentName(context, ServerWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, savedUrl)
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "dev.agzes.swebapp.UPDATE_WIDGET"
        const val ACTION_REFRESH_WIDGET = "dev.agzes.swebapp.REFRESH_WIDGET"

        private fun isSafeUrl(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://")

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, url: String?) {
            val isRunning = context.isServiceRunning()

            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

            val isSmallWidth = minWidth < 110
            val isTallHeight = minHeight > 160

            val layoutId = when {
                isSmallWidth && isTallHeight -> R.layout.widget_server_tall
                isSmallWidth -> R.layout.widget_server_small
                else -> R.layout.widget_server
            }
            val views = RemoteViews(context.packageName, layoutId)

            val finalToggleIntent = Intent(context, ActionReceiver::class.java).apply {
                action = if (isRunning) ActionReceiver.ACTION_STOP_SERVER else ActionReceiver.ACTION_START_SERVER
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, 0, finalToggleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetBtnToggle, togglePendingIntent)

            if (isSmallWidth && isTallHeight) {
                views.setInt(
                    R.id.widgetStatusIcon,
                    "setImageResource",
                    if (isRunning) R.drawable.ic_status_dot_online else R.drawable.ic_status_dot_offline
                )
                if (isRunning) {
                    views.setInt(R.id.widgetBtnToggle, "setBackgroundResource", R.drawable.widget_btn_bg_stop)
                    views.setImageViewResource(R.id.widgetToggleIcon, R.drawable.ic_stop)
                } else {
                    views.setInt(R.id.widgetBtnToggle, "setBackgroundResource", R.drawable.widget_btn_bg_start)
                    views.setImageViewResource(R.id.widgetToggleIcon, R.drawable.ic_play)
                }
                val appIntent = Intent(context, MainActivity::class.java)
                val openPendingIntent = PendingIntent.getActivity(
                    context, 1, appIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(R.id.widgetBtnApp, openPendingIntent)
            } else if (isSmallWidth) {
                if (isRunning) {
                    views.setInt(R.id.widgetBtnToggle, "setBackgroundResource", R.drawable.widget_btn_bg_stop)
                    views.setImageViewResource(R.id.widgetToggleIcon, R.drawable.ic_stop)
                } else {
                    views.setInt(R.id.widgetBtnToggle, "setBackgroundResource", R.drawable.widget_btn_bg_start)
                    views.setImageViewResource(R.id.widgetToggleIcon, R.drawable.ic_play)
                }
            } else {
                views.setTextViewText(R.id.widgetStatusText, if (isRunning) "Online" else "Offline")
                views.setInt(
                    R.id.widgetStatusIcon,
                    "setImageResource",
                    if (isRunning) R.drawable.ic_status_dot_online else R.drawable.ic_status_dot_offline
                )

                if (isRunning) {
                    views.setTextViewText(R.id.widgetUrlText, url ?: "Running...")
                } else {
                    views.setTextViewText(R.id.widgetUrlText, "Server is stopped")
                }

                if (isRunning) {
                    views.setTextViewText(R.id.widgetToggleText, "Stop")
                    views.setTextColor(
                        R.id.widgetToggleText,
                        androidx.core.content.ContextCompat.getColor(context, R.color.widget_btn_stop_text)
                    )
                    views.setInt(R.id.widgetBtnToggle, "setBackgroundResource", R.drawable.widget_btn_bg_stop)
                    views.setImageViewResource(R.id.widgetToggleIcon, R.drawable.ic_stop)

                    if (minWidth >= 180) {
                        views.setViewVisibility(R.id.widgetBtnBrowser, View.VISIBLE)
                        views.setViewVisibility(R.id.widgetSpaceBrowser, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widgetBtnBrowser, View.GONE)
                        views.setViewVisibility(R.id.widgetSpaceBrowser, View.GONE)
                    }

                    if (url != null && isSafeUrl(url)) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        val browserPendingIntent = PendingIntent.getActivity(
                            context, 3, browserIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        views.setOnClickPendingIntent(R.id.widgetBtnBrowser, browserPendingIntent)
                    }
                } else {
                    views.setTextViewText(R.id.widgetToggleText, "Start")
                    views.setTextColor(
                        R.id.widgetToggleText,
                        androidx.core.content.ContextCompat.getColor(context, R.color.widget_btn_start_text)
                    )
                    views.setInt(R.id.widgetBtnToggle, "setBackgroundResource", R.drawable.widget_btn_bg_start)
                    views.setImageViewResource(R.id.widgetToggleIcon, R.drawable.ic_play)

                    views.setViewVisibility(R.id.widgetBtnBrowser, View.GONE)
                    views.setViewVisibility(R.id.widgetSpaceBrowser, View.GONE)
                }

                val hideToggleText = minWidth < 250
                if (hideToggleText) {
                    views.setViewVisibility(R.id.widgetToggleText, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widgetToggleText, View.VISIBLE)
                }

                val isShortHeight = minHeight < 100
                val infoVisibility = if (isShortHeight) View.GONE else View.VISIBLE
                views.setViewVisibility(R.id.widgetInfoSection, infoVisibility)
                views.setViewVisibility(R.id.widgetInfoButtonsSpace, infoVisibility)

                val appIntent = Intent(context, MainActivity::class.java)
                val openPendingIntent = PendingIntent.getActivity(
                    context, 1, appIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(R.id.widgetBtnApp, openPendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
