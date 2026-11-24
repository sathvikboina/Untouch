package com.untouch.app.engine

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.untouch.app.data.SecureStorage
import com.untouch.app.data.StorageResult
import com.untouch.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProtectionForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private lateinit var storage: SecureStorage

    override fun onCreate() {
        super.onCreate()
        storage = SecureStorage(this)
        createNotificationChannels(this)
        startForeground(NOTIFICATION_ID_STATUS, buildStatusNotification("Strict Mode monitoring active"))
        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            while (isActive) {
                when (val configResult = storage.readConfig()) {
                    is StorageResult.Error -> {
                        nm.notify(
                            NOTIFICATION_ID_STATUS,
                            buildStatusNotification("Storage Error — protection unavailable")
                        )
                        postAlertNotification(
                            this@ProtectionForegroundService,
                            "Storage Error",
                            "UNTOUCH cannot read its encrypted vault. Tap to open recovery."
                        )
                    }
                    is StorageResult.Success -> {
                        val config = configResult.value

                        if (!config.isStrictModeActive) {
                            nm.cancel(NOTIFICATION_ID_ALERT)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            return@launch
                        }

                        val perms = PermissionChecker.checkAll(this@ProtectionForegroundService)
                        if (!perms.accessibilityEnabled) {
                            nm.notify(
                                NOTIFICATION_ID_STATUS,
                                buildStatusNotification("Protection PAUSED — Accessibility disabled")
                            )
                            postAlertNotification(
                                this@ProtectionForegroundService,
                                "Protection Paused",
                                "Accessibility service was turned off. Tap to restore UNTOUCH."
                            )

                            if (perms.usageStatsGranted && isBlockedAppInForeground(config.blockedPackages)) {
                                postAlertNotification(
                                    this@ProtectionForegroundService,
                                    "Blocked App Opened While Paused",
                                    "A restricted app was detected while Accessibility is disabled. Restore protection now."
                                )
                            }
                        } else {
                            nm.cancel(NOTIFICATION_ID_ALERT)
                            nm.notify(
                                NOTIFICATION_ID_STATUS,
                                buildStatusNotification("Strict Mode active (${config.blockedPackages.size} locked)")
                            )
                        }
                    }
                }
                delay(3000L)
            }
        }
    }

    private fun isBlockedAppInForeground(blockedPackages: Set<String>): Boolean {
        if (blockedPackages.isEmpty()) return false
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 4000L
        val events = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var latestPkg: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestPkg = event.packageName
            }
        }
        return latestPkg != null && latestPkg in blockedPackages
    }

    private fun buildStatusNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("UNTOUCH")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_STATUS = "untouch_status"
        private const val CHANNEL_ALERT = "untouch_alert"
        private const val NOTIFICATION_ID_STATUS = 1001
        private const val NOTIFICATION_ID_ALERT = 1002
        private const val ACTION_STOP = "com.untouch.app.ACTION_STOP_MONITOR"

        fun createNotificationChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val statusChannel = NotificationChannel(
                CHANNEL_STATUS, "Protection Status", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Persistent indicator of UNTOUCH state" }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERT, "Protection Alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "High-priority alerts when protection is paused or tampered with" }

            nm.createNotificationChannel(statusChannel)
            nm.createNotificationChannel(alertChannel)
        }

        fun postAlertNotification(context: Context, title: String, message: String) {
            createNotificationChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val openIntent = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
            nm.notify(NOTIFICATION_ID_ALERT, notification)
        }

        fun safeStart(context: Context) {
            val intent = Intent(context, ProtectionForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    postAlertNotification(
                        context,
                        "Tap to Resume Monitor",
                        "Android restricted background start. Open UNTOUCH to resume monitoring."
                    )
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProtectionForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
