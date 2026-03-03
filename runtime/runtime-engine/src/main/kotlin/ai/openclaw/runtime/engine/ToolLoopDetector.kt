package ai.openclaw.runtime.engine

import ai.openclaw.core.model.ToolLoopDetectionConfig
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Detects repetitive no-progress tool loops.
 *
 * Ported from src/agents/tool-loop-detection.ts and adapted to Kotlin runtime state.
 */
class ToolLoopDetector(config: ToolLoopDetectionConfig? = null) {
    private val resolved = ResolvedConfig.from(config)
    private val history = ArrayDeque<ToolCallRecord>()
    private val warningBuckets = linkedMapOf<String, Int>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    data class CheckResult(
        val warning: Boolean,
        val critical: Boolean,
        val count: Int,
        val detector: DetectorKind? = null,
        val message: String? = null,
        val pairedToolName: String? = null,
        val warningKey: String? = null,
        val shouldEmitWarning: Boolean = warning,
    )

    enum class DetectorKind {
        GENERIC_REPEAT,
        KNOWN_POLL_NO_PROGRESS,
        GLOBAL_CIRCUIT_BREAKER,
        PING_PONG,
    }

    fun check(toolName: String, input: String): CheckResult = checkBeforeToolCall(toolName, input)

    fun checkBeforeToolCall(toolName: String, input: String): CheckResult {
        if (!resolved.enabled) return CheckResult(warning = false, critical = false, count = 0)

        val normalizedTool = normalizeToolName(toolName)
        val argsHash = hashToolCall(normalizedTool, input)
        val noProgress = getNoProgressStreak(normalizedTool, argsHash)
        val noProgressStreak = noProgress.count
        val isKnownPoll = isKnownPollToolCall(normalizedTool, input)
        val pingPong = getPingPongStreak(currentSignature = argsHash)

        if (noProgressStreak >= resolved.globalCircuitBreakerThreshold) {
            val warningKey = "global:$normalizedTool:$argsHash:${noProgress.latestResultHash ?: "none"}"
            return CheckResult(
                warning = false,
                critical = true,
                count = noProgressStreak,
                detector = DetectorKind.GLOBAL_CIRCUIT_BREAKER,
                warningKey = warningKey,
                message = "CRITICAL: $normalizedTool has repeated identical no-progress outcomes " +
                    "$noProgressStreak times. Session execution blocked by global circuit breaker.",
            )
        }

        if (isKnownPoll && resolved.detectors.knownPollNoProgress) {
            if (noProgressStreak >= resolved.criticalThreshold) {
                val warningKey = "poll:$normalizedTool:$argsHash:${noProgress.latestResultHash ?: "none"}"
                return CheckResult(
                    warning = false,
                    critical = true,
                    count = noProgressStreak,
                    detector = DetectorKind.KNOWN_POLL_NO_PROGRESS,
                    warningKey = warningKey,
                    message = "CRITICAL: Called $normalizedTool with identical arguments and no progress " +
                        "$noProgressStreak times. This appears to be a stuck polling loop.",
                )
            }
            if (noProgressStreak >= resolved.warningThreshold) {
                val warningKey = "poll:$normalizedTool:$argsHash:${noProgress.latestResultHash ?: "none"}"
                val shouldEmit = shouldEmitWarning(warningKey, noProgressStreak)
                return CheckResult(
                    warning = true,
                    critical = false,
                    count = noProgressStreak,
                    detector = DetectorKind.KNOWN_POLL_NO_PROGRESS,
                    warningKey = warningKey,
                    shouldEmitWarning = shouldEmit,
                    message = "WARNING: You have called $normalizedTool $noProgressStreak times with " +
                        "identical arguments and no progress. Stop polling if the task is stuck.",
                )
            }
        }

        val pingPongWarningKey = pingPong.pairedSignature?.let { signature ->
            "pingpong:${canonicalPairKey(argsHash, signature)}"
        } ?: "pingpong:$normalizedTool:$argsHash"

        if (resolved.detectors.pingPong && pingPong.count >= resolved.criticalThreshold && pingPong.noProgressEvidence) {
            return CheckResult(
                warning = false,
                critical = true,
                count = pingPong.count,
                detector = DetectorKind.PING_PONG,
                pairedToolName = pingPong.pairedToolName,
                warningKey = pingPongWarningKey,
                message = "CRITICAL: You are alternating between repeated tool-call patterns " +
                    "(${pingPong.count} consecutive calls) with no progress.",
            )
        }

        if (resolved.detectors.pingPong && pingPong.count >= resolved.warningThreshold) {
            val shouldEmit = shouldEmitWarning(pingPongWarningKey, pingPong.count)
            return CheckResult(
                warning = true,
                critical = false,
                count = pingPong.count,
                detector = DetectorKind.PING_PONG,
                pairedToolName = pingPong.pairedToolName,
                warningKey = pingPongWarningKey,
                shouldEmitWarning = shouldEmit,
                message = "WARNING: You are alternating between repeated tool-call patterns " +
                    "(${pingPong.count} consecutive calls). This looks like a ping-pong loop.",
            )
        }

        val recentCount = history.count { it.toolName == normalizedTool && it.argsHash == argsHash }
        if (!isKnownPoll && resolved.detectors.genericRepeat && recentCount >= resolved.warningThreshold) {
            val warningKey = "generic:$normalizedTool:$argsHash"
            val shouldEmit = shouldEmitWarning(warningKey, recentCount)
            return CheckResult(
                warning = true,
                critical = false,
                count = recentCount,
                detector = DetectorKind.GENERIC_REPEAT,
                warningKey = warningKey,
                shouldEmitWarning = shouldEmit,
                message = "WARNING: You have called $normalizedTool $recentCount times with identical arguments.",
            )
        }

        return CheckResult(warning = false, critical = false, count = 0)
    }

    fun recordToolCall(toolName: String, input: String, toolCallId: String? = null) {
        val normalizedTool = normalizeToolName(toolName)
        history.addLast(
            ToolCallRecord(
                toolName = normalizedTool,
                argsHash = hashToolCall(normalizedTool, input),
                toolCallId = toolCallId,
            ),
        )
        trimHistory()
    }

    fun recordToolCallOutcome(
        toolName: String,
        input: String,
        toolCallId: String? = null,
        result: String? = null,
        error: String? = null,
    ) {
        val normalizedTool = normalizeToolName(toolName)
        val resultHash = hashToolOutcome(
            toolName = normalizedTool,
            input = input,
            result = result,
            error = error,
        ) ?: return
        val argsHash = hashToolCall(normalizedTool, input)

        var matched = false
        for (idx in history.indices.reversed()) {
            val call = history[idx]
            if (toolCallId != null && call.toolCallId != toolCallId) continue
            if (call.toolName != normalizedTool || call.argsHash != argsHash) continue
            if (call.resultHash != null) continue
            history[idx] = call.copy(resultHash = resultHash)
            matched = true
            break
        }

        if (!matched) {
            history.addLast(
                ToolCallRecord(
                    toolName = normalizedTool,
                    argsHash = argsHash,
                    toolCallId = toolCallId,
                    resultHash = resultHash,
                ),
            )
        }
        trimHistory()
    }

    private fun shouldEmitWarning(warningKey: String, count: Int): Boolean {
        val bucket = count / LOOP_WARNING_BUCKET_SIZE
        val lastBucket = warningBuckets[warningKey] ?: 0
        if (bucket <= lastBucket) return false
        warningBuckets[warningKey] = bucket
        if (warningBuckets.size > MAX_LOOP_WARNING_KEYS) {
            val oldest = warningBuckets.keys.firstOrNull()
            if (oldest != null) warningBuckets.remove(oldest)
        }
        return true
    }

    private fun trimHistory() {
        while (history.size > resolved.historySize) {
            history.removeFirst()
        }
    }

    private fun hashToolCall(toolName: String, params: String): String {
        val normalizedTool = normalizeToolName(toolName)
        return "$normalizedTool:${sha256(stableInputDigest(params))}"
    }

    private fun hashToolOutcome(
        toolName: String,
        input: String,
        result: String?,
        error: String?,
    ): String? {
        if (!error.isNullOrBlank()) return "error:${sha256(error)}"
        if (result == null) return null
        val parsed = runCatching { json.parseToJsonElement(result.trim()) }.getOrNull()
        if (parsed !is JsonObject) {
            return sha256(stableInputDigest(result))
        }

        val details = parsed["details"] as? JsonObject
        val text = extractTextContent(parsed)
        if (isKnownPollToolCall(toolName, input) && toolName == "process") {
            val action = processAction(input) ?: return sha256(stableStringify(parsed))
            if (action == "poll") {
                return sha256(
                    stableStringify(
                        buildJsonObject {
                            put("action", JsonPrimitive(action))
                            put("status", details?.get("status") ?: JsonNull)
                            put("exitCode", details?.get("exitCode") ?: JsonNull)
                            put("exitSignal", details?.get("exitSignal") ?: JsonNull)
                            put("aggregated", details?.get("aggregated") ?: JsonNull)
                            put("text", JsonPrimitive(text))
                        },
                    ),
                )
            }
            if (action == "log") {
                return sha256(
                    stableStringify(
                        buildJsonObject {
                            put("action", JsonPrimitive(action))
                            put("status", details?.get("status") ?: JsonNull)
                            put("totalLines", details?.get("totalLines") ?: JsonNull)
                            put("totalChars", details?.get("totalChars") ?: JsonNull)
                            put("truncated", details?.get("truncated") ?: JsonNull)
                            put("exitCode", details?.get("exitCode") ?: JsonNull)
                            put("exitSignal", details?.get("exitSignal") ?: JsonNull)
                            put("text", JsonPrimitive(text))
                        },
                    ),
                )
            }
        }

        return sha256(
            stableStringify(
                buildJsonObject {
                    put("details", details ?: JsonObject(emptyMap()))
                    put("text", JsonPrimitive(text))
                },
            ),
        )
    }

    private fun stableInputDigest(input: String): String {
        val trimmed = input.trim()
        val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
        return stableStringify(parsed)
    }

    private fun stableStringify(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) {
            "\"${value.content.replace("\"", "\\\"")}\""
        } else {
            value.content
        }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { stableStringify(it) }
        is JsonObject -> value.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, item) ->
                "\"${key.replace("\"", "\\\"")}\":${stableStringify(item)}"
            }
    }

    private fun isKnownPollToolCall(toolName: String, params: String): Boolean {
        val normalizedTool = normalizeToolName(toolName)
        if (normalizedTool == "command_status") return true
        if (normalizedTool != "process") return false
        val action = processAction(params) ?: return false
        return action == "poll" || action == "log"
    }

    private fun processAction(params: String): String? {
        val parsed = runCatching { json.parseToJsonElement(params) }.getOrNull() as? JsonObject ?: return null
        return parsed["action"]?.jsonPrimitive?.contentOrNull
    }

    private fun extractTextContent(result: JsonObject): String {
        val content = result["content"] as? JsonArray ?: return ""
        val lines = buildList {
            for (entry in content) {
                val item = entry as? JsonObject ?: continue
                val type = item["type"]?.jsonPrimitive?.contentOrNull ?: continue
                if (type != "text") continue
                val text = item["text"]?.jsonPrimitive?.contentOrNull ?: continue
                add(text)
            }
        }
        return lines.joinToString("\n").trim()
    }

    private fun normalizeToolName(name: String): String {
        val normalized = name.trim().lowercase()
        return when (normalized) {
            "bash" -> "exec"
            "apply-patch" -> "apply_patch"
            else -> normalized
        }
    }

    private data class NoProgressStreak(
        val count: Int,
        val latestResultHash: String?,
    )

    private fun getNoProgressStreak(toolName: String, argsHash: String): NoProgressStreak {
        var streak = 0
        var latestResultHash: String? = null
        for (idx in history.indices.reversed()) {
            val record = history[idx]
            if (record.toolName != toolName || record.argsHash != argsHash) continue
            val resultHash = record.resultHash ?: continue
            if (latestResultHash == null) {
                latestResultHash = resultHash
                streak = 1
                continue
            }
            if (resultHash != latestResultHash) {
                break
            }
            streak++
        }
        return NoProgressStreak(count = streak, latestResultHash = latestResultHash)
    }

    private data class PingPongStreak(
        val count: Int,
        val pairedToolName: String? = null,
        val pairedSignature: String? = null,
        val noProgressEvidence: Boolean = false,
    )

    private fun getPingPongStreak(currentSignature: String): PingPongStreak {
        val last = history.lastOrNull() ?: return PingPongStreak(count = 0)

        var otherSignature: String? = null
        var otherToolName: String? = null
        if (history.size >= 2) {
            for (idx in history.size - 2 downTo 0) {
                val call = history[idx]
                if (call.argsHash != last.argsHash) {
                    otherSignature = call.argsHash
                    otherToolName = call.toolName
                    break
                }
            }
        }
        if (otherSignature == null || otherToolName == null) return PingPongStreak(count = 0)

        var alternatingTailCount = 0
        for (idx in history.indices.reversed()) {
            val call = history[idx]
            val expected = if (alternatingTailCount % 2 == 0) last.argsHash else otherSignature
            if (call.argsHash != expected) break
            alternatingTailCount++
        }
        if (alternatingTailCount < 2) return PingPongStreak(count = 0)

        if (currentSignature != otherSignature) return PingPongStreak(count = 0)

        val tailStart = (history.size - alternatingTailCount).coerceAtLeast(0)
        var firstHashA: String? = null
        var firstHashB: String? = null
        var noProgressEvidence = true
        for (idx in tailStart until history.size) {
            val call = history[idx]
            val resultHash = call.resultHash
            if (resultHash == null) {
                noProgressEvidence = false
                break
            }
            if (call.argsHash == last.argsHash) {
                if (firstHashA == null) {
                    firstHashA = resultHash
                } else if (firstHashA != resultHash) {
                    noProgressEvidence = false
                    break
                }
                continue
            }
            if (call.argsHash == otherSignature) {
                if (firstHashB == null) {
                    firstHashB = resultHash
                } else if (firstHashB != resultHash) {
                    noProgressEvidence = false
                    break
                }
                continue
            }
            noProgressEvidence = false
            break
        }
        if (firstHashA == null || firstHashB == null) {
            noProgressEvidence = false
        }

        return PingPongStreak(
            count = alternatingTailCount + 1,
            pairedToolName = last.toolName,
            pairedSignature = last.argsHash,
            noProgressEvidence = noProgressEvidence,
        )
    }

    private fun canonicalPairKey(a: String, b: String): String {
        return listOf(a, b).sorted().joinToString("|")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class ToolCallRecord(
        val toolName: String,
        val argsHash: String,
        val toolCallId: String? = null,
        val resultHash: String? = null,
    )

    private data class ResolvedConfig(
        val enabled: Boolean,
        val historySize: Int,
        val warningThreshold: Int,
        val criticalThreshold: Int,
        val globalCircuitBreakerThreshold: Int,
        val detectors: DetectorConfig,
    ) {
        companion object {
            fun from(config: ToolLoopDetectionConfig?): ResolvedConfig {
                var warningThreshold = asPositiveInt(config?.warningThreshold, WARNING_THRESHOLD)
                var criticalThreshold = asPositiveInt(config?.criticalThreshold, CRITICAL_THRESHOLD)
                var globalThreshold = asPositiveInt(
                    config?.globalCircuitBreakerThreshold,
                    GLOBAL_CIRCUIT_BREAKER_THRESHOLD,
                )
                if (criticalThreshold <= warningThreshold) {
                    criticalThreshold = warningThreshold + 1
                }
                if (globalThreshold <= criticalThreshold) {
                    globalThreshold = criticalThreshold + 1
                }
                return ResolvedConfig(
                    enabled = config?.enabled ?: false,
                    historySize = asPositiveInt(config?.historySize, TOOL_CALL_HISTORY_SIZE),
                    warningThreshold = warningThreshold,
                    criticalThreshold = criticalThreshold,
                    globalCircuitBreakerThreshold = globalThreshold,
                    detectors = DetectorConfig(
                        genericRepeat = config?.detectors?.genericRepeat ?: true,
                        knownPollNoProgress = config?.detectors?.knownPollNoProgress ?: true,
                        pingPong = config?.detectors?.pingPong ?: true,
                    ),
                )
            }
        }
    }

    private data class DetectorConfig(
        val genericRepeat: Boolean,
        val knownPollNoProgress: Boolean,
        val pingPong: Boolean,
    )

    companion object {
        private const val TOOL_CALL_HISTORY_SIZE = 30
        private const val WARNING_THRESHOLD = 10
        private const val CRITICAL_THRESHOLD = 20
        private const val GLOBAL_CIRCUIT_BREAKER_THRESHOLD = 30
        private const val LOOP_WARNING_BUCKET_SIZE = 10
        private const val MAX_LOOP_WARNING_KEYS = 256

        private fun asPositiveInt(value: Int?, fallback: Int): Int {
            if (value == null || value <= 0) return fallback
            return value
        }
    }
}
