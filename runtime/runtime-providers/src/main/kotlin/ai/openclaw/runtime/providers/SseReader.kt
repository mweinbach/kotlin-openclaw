package ai.openclaw.runtime.providers

import java.io.BufferedReader

/**
 * Reads SSE events from a BufferedReader per the Server-Sent Events specification.
 * Handles multi-line data payloads by concatenating consecutive `data:` lines with newlines.
 * Ignores `event:`, `id:`, `retry:`, and comment lines.
 * Returns a sequence of raw event data strings; stops on `[DONE]` sentinel.
 */
internal fun BufferedReader.sseEvents(): Sequence<String> = sequence {
    val eventData = StringBuilder()
    while (true) {
        val line = readLine() ?: break
        when {
            line.isEmpty() -> {
                if (eventData.isNotEmpty()) {
                    val data = eventData.toString()
                    eventData.clear()
                    if (data == "[DONE]") return@sequence
                    yield(data)
                }
            }
            line.startsWith("data:") -> {
                // SSE spec: optional single space after "data:"
                val payload = line.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.substring(1) else it
                }
                if (eventData.isNotEmpty()) eventData.append("\n")
                eventData.append(payload)
            }
            // Ignore event:, id:, retry:, and comments starting with ':'
        }
    }
    // Handle stream ending without a trailing blank line
    if (eventData.isNotEmpty()) {
        val data = eventData.toString()
        if (data != "[DONE]") {
            yield(data)
        }
    }
}
