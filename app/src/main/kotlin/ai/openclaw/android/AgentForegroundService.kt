package ai.openclaw.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

/**
 * Foreground service that keeps the agent engine running.
 * Enhanced with channel/session counts and notification actions.
 */
class AgentForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting...", "Initializing engine"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                scope.launch {
                    val app = application as OpenClawApp
                    app.engine.channelManager.stopAll()
                    updateNotification("Paused", "Channels stopped")
                }
            }
            else -> {
                scope.launch {
                    val app = application as OpenClawApp
                    app.engine.initialize()
                    updateStatusNotification(app.engine)

                    // Periodically update notification with live stats
                    while (isActive) {
                        delay(30_000)
                        updateStatusNotification(app.engine)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatusNotification(engine: AgentEngine) {
        val sessionCount = try {
            engine.sessionPersistence.listSessionKeys().size
        } catch (_: Exception) { 0 }

        val channelSnapshot = try {
            engine.channelManager.getSnapshot()
        } catch (_: Exception) { emptyMap() }

        val connectedChannels = channelSnapshot.values.count {
            it.status == "RUNNING"
        }
        val totalChannels = channelSnapshot.size

        val status = "Running"
        val detail = buildString {
            append("$sessionCount sessions")
            if (totalChannels > 0) {
                append(" | $connectedChannels/$totalChannels channels")
            }
        }
        updateNotification(status, detail)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenClaw Agent",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "OpenClaw agent engine status"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String, detail: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val pauseIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_PAUSE
        }.let {
            PendingIntent.getService(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw — $status")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null, "Pause", pauseIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null, "Open", openIntent,
                ).build(),
            )
            .build()
    }

    private fun updateNotification(status: String, detail: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status, detail))
    }

    companion object {
        private const val CHANNEL_ID = "openclaw_agent"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "ai.openclaw.android.PAUSE"
    }
}
