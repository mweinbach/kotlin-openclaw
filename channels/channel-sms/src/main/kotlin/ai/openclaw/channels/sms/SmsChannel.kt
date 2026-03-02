package ai.openclaw.channels.sms

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*
import kotlinx.coroutines.*

/**
 * SMS channel implementation for Android.
 * Uses Android's SmsManager for sending and provides an inbound handler
 * for BroadcastReceiver integration (SMS_RECEIVED intent).
 *
 * Since this is an Android library module, the actual BroadcastReceiver
 * must be registered by the host application. Call [onSmsReceived] from
 * your BroadcastReceiver when an SMS arrives.
 */
class SmsChannel(
    private val smsSender: SmsSender = DefaultSmsSender(),
) : BaseChannelAdapter() {

    override val channelId: ChannelId = "sms"
    override val displayName: String = "SMS"
    override val capabilities = ChannelCapabilities(
        text = true,
    )

    // --- Lifecycle ---

    override suspend fun onStart() {
        // No active polling needed - inbound messages are pushed via onSmsReceived()
    }

    override suspend fun onStop() {
        // Nothing to clean up
    }

    // --- Inbound ---

    /**
     * Call this method from an Android BroadcastReceiver when an SMS is received.
     * Typically called from an SMS_RECEIVED_ACTION intent handler.
     *
     * @param senderAddress The phone number of the sender
     * @param messageBody The text content of the SMS
     * @param timestamp The timestamp of the message in millis (defaults to now)
     */
    suspend fun onSmsReceived(
        senderAddress: String,
        messageBody: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val inbound = InboundMessage(
            channelId = channelId,
            chatType = ChatType.DIRECT,
            senderId = senderAddress,
            senderName = senderAddress,
            targetId = senderAddress,
            text = messageBody,
            timestamp = timestamp,
            metadata = mapOf(
                "sms_sender" to senderAddress,
            ),
        )
        dispatchInbound(inbound)
    }

    // --- Outbound ---

    override suspend fun send(message: OutboundMessage): Boolean = withContext(Dispatchers.IO) {
        val phoneNumber = message.targetId
        val text = message.text
        smsSender.sendSms(phoneNumber, text)
    }

    /**
     * Interface for sending SMS messages. Allows abstraction over Android SmsManager
     * for testability and compilation on non-Android environments.
     */
    interface SmsSender {
        fun sendSms(phoneNumber: String, text: String): Boolean
    }

    /**
     * Default SMS sender that uses Android's SmsManager via reflection.
     * This allows the code to compile even when Android SDK classes are not available
     * at compile time (e.g., unit test environments).
     */
    class DefaultSmsSender : SmsSender {
        override fun sendSms(phoneNumber: String, text: String): Boolean {
            return try {
                // Use reflection to access SmsManager so the class compiles
                // even in environments without Android framework classes
                val smsManagerClass = Class.forName("android.telephony.SmsManager")
                val getDefault = smsManagerClass.getMethod("getDefault")
                val smsManager = getDefault.invoke(null)

                if (text.length <= 160) {
                    val sendMethod = smsManagerClass.getMethod(
                        "sendTextMessage",
                        String::class.java,  // destinationAddress
                        String::class.java,  // scAddress
                        String::class.java,  // text
                        Class.forName("android.app.PendingIntent"),  // sentIntent
                        Class.forName("android.app.PendingIntent"),  // deliveryIntent
                    )
                    sendMethod.invoke(smsManager, phoneNumber, null, text, null, null)
                } else {
                    // Multi-part SMS
                    val divideMethod = smsManagerClass.getMethod(
                        "divideMessage",
                        String::class.java,
                    )
                    @Suppress("UNCHECKED_CAST")
                    val parts = divideMethod.invoke(smsManager, text) as java.util.ArrayList<String>

                    val sendMultiMethod = smsManagerClass.getMethod(
                        "sendMultipartTextMessage",
                        String::class.java,             // destinationAddress
                        String::class.java,             // scAddress
                        java.util.ArrayList::class.java, // parts
                        java.util.ArrayList::class.java, // sentIntents
                        java.util.ArrayList::class.java, // deliveryIntents
                    )
                    sendMultiMethod.invoke(smsManager, phoneNumber, null, parts, null, null)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
