package ai.openclaw.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Role label
        Text(
            text = if (isUser) "You" else "Agent",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        // Message bubble
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Tool calls
                if (message.toolCalls.isNotEmpty()) {
                    for (toolCall in message.toolCalls) {
                        ToolCallCard(toolName = toolCall)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                // Message content with code block support
                if (message.content.isNotEmpty()) {
                    RichContent(
                        text = message.content,
                        isUser = isUser,
                    )
                }

                // Streaming indicator
                if (message.isStreaming) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(toolName: String) {
    OutlinedCard(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = toolName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RichContent(text: String, isUser: Boolean) {
    val segments = parseCodeBlocks(text)
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    for (segment in segments) {
        when (segment) {
            is ContentSegment.Plain -> {
                if (segment.text.isNotBlank()) {
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
            }
            is ContentSegment.Code -> {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .padding(8.dp),
                ) {
                    Text(
                        text = segment.code,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = textColor,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

private sealed class ContentSegment {
    data class Plain(val text: String) : ContentSegment()
    data class Code(val code: String, val language: String?) : ContentSegment()
}

private fun parseCodeBlocks(text: String): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    val pattern = Regex("```(\\w*)\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    var lastIndex = 0

    for (match in pattern.findAll(text)) {
        val before = text.substring(lastIndex, match.range.first)
        if (before.isNotEmpty()) {
            segments.add(ContentSegment.Plain(before))
        }
        val language = match.groupValues[1].ifBlank { null }
        val code = match.groupValues[2].trimEnd()
        segments.add(ContentSegment.Code(code, language))
        lastIndex = match.range.last + 1
    }

    val remaining = text.substring(lastIndex)
    if (remaining.isNotEmpty()) {
        segments.add(ContentSegment.Plain(remaining))
    }

    return segments
}
