package ai.openclaw.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("timestamp"), Index("role")],
)
data class MessageEntity(
    @PrimaryKey
    val messageId: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val channel: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val threadId: String? = null,
    val replyToMessageId: String? = null,
    val tokenCount: Int? = null,
    val attachmentsJson: String? = null,
    val metadataJson: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
