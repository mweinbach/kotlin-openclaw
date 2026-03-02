package ai.openclaw.runtime.devices

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent tool for creating Android notifications with optional action buttons.
 */
class NotificationTool(
    private val context: Context,
    private val launchActivityClass: Class<*>? = null,
) : AgentTool {

    override val name: String = "notify"

    override val description: String =
        "Show a notification on the device. Supports title, body text, priority levels, " +
        "and optional action buttons."

    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "Notification title."
            },
            "body": {
              "type": "string",
              "description": "Notification body text."
            },
            "priority": {
              "type": "string",
              "enum": ["high", "default", "low"],
              "description": "Notification priority (default: default)."
            },
            "actions": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "label": { "type": "string", "description": "Button label." },
                  "action_id": { "type": "string", "description": "Identifier for this action." }
                },
                "required": ["label", "action_id"]
              },
              "description": "Optional action buttons (max 3)."
            }
          },
          "required": ["title", "body"]
        }
    """.trimIndent()

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationIdCounter = AtomicInteger(NOTIFICATION_ID_START)

    init {
        ensureChannels()
    }

    override suspend fun execute(input: String, context: ToolContext): String {
        val params = Json.parseToJsonElement(input).jsonObject
        val title = params["title"]?.jsonPrimitive?.content
            ?: return "Error: 'title' is required."
        val body = params["body"]?.jsonPrimitive?.content
            ?: return "Error: 'body' is required."
        val priority = params["priority"]?.jsonPrimitive?.content ?: "default"
        val actions = params["actions"]?.jsonArray

        val channelId = when (priority) {
            "high" -> CHANNEL_HIGH
            "low" -> CHANNEL_LOW
            else -> CHANNEL_DEFAULT
        }

        val notificationPriority = when (priority) {
            "high" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val notificationId = notificationIdCounter.getAndIncrement()

        val builder = NotificationCompat.Builder(this.context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(notificationPriority)
            .setAutoCancel(true)

        // Set content intent if launch activity is configured
        if (launchActivityClass != null) {
            val intent = Intent(this.context, launchActivityClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("notification_id", notificationId)
            }
            val pendingIntent = PendingIntent.getActivity(
                this.context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pendingIntent)
        }

        // Add action buttons (max 3 per Android spec)
        actions?.take(3)?.forEachIndexed { index, actionJson ->
            val actionObj = actionJson.jsonObject
            val label = actionObj["label"]?.jsonPrimitive?.content ?: "Action"
            val actionId = actionObj["action_id"]?.jsonPrimitive?.content ?: "action_$index"

            val actionIntent = Intent(ACTION_NOTIFICATION_ACTION).apply {
                putExtra("action_id", actionId)
                putExtra("notification_id", notificationId)
                setPackage(this@NotificationTool.context.packageName)
            }
            val pendingActionIntent = PendingIntent.getBroadcast(
                this.context, notificationId * 10 + index, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, label, pendingActionIntent)
        }

        // Use long text style if body is long
        if (body.length > 40) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        notificationManager.notify(notificationId, builder.build())

        return """{"status":"ok","notification_id":$notificationId,"channel":"$channelId"}"""
    }

    private fun ensureChannels() {
        val channels = listOf(
            NotificationChannel(CHANNEL_HIGH, "High Priority", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Urgent notifications from AI agent"
            },
            NotificationChannel(CHANNEL_DEFAULT, "Default", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Standard notifications from AI agent"
            },
            NotificationChannel(CHANNEL_LOW, "Low Priority", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background notifications from AI agent"
            },
        )
        channels.forEach { notificationManager.createNotificationChannel(it) }
    }

    companion object {
        const val CHANNEL_HIGH = "openclaw_high"
        const val CHANNEL_DEFAULT = "openclaw_default"
        const val CHANNEL_LOW = "openclaw_low"
        const val ACTION_NOTIFICATION_ACTION = "ai.openclaw.runtime.devices.NOTIFICATION_ACTION"
        private const val NOTIFICATION_ID_START = 10000
    }
}
