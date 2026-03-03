package ai.openclaw.runtime.engine

import ai.openclaw.core.model.ToolLoopDetectionConfig
import ai.openclaw.core.model.ToolLoopDetectionDetectorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolLoopDetectorTest {
    @Test
    fun `generic repeat emits warning but does not block`() {
        val detector = ToolLoopDetector(
            ToolLoopDetectionConfig(
                enabled = true,
                warningThreshold = 2,
                criticalThreshold = 5,
                globalCircuitBreakerThreshold = 8,
                detectors = ToolLoopDetectionDetectorConfig(
                    genericRepeat = true,
                    knownPollNoProgress = false,
                    pingPong = false,
                ),
            ),
        )

        record(detector, "read", """{"path":"a"}""", "ok-1")
        record(detector, "read", """{"path":"a"}""", "ok-2")

        val result = detector.checkBeforeToolCall("read", """{"path":"a"}""")
        assertTrue(result.warning)
        assertFalse(result.critical)
        assertEquals(ToolLoopDetector.DetectorKind.GENERIC_REPEAT, result.detector)
    }

    @Test
    fun `known poll no progress escalates from warning to critical`() {
        val detector = ToolLoopDetector(
            ToolLoopDetectionConfig(
                enabled = true,
                warningThreshold = 2,
                criticalThreshold = 3,
                globalCircuitBreakerThreshold = 7,
            ),
        )

        val input = """{"action":"poll","id":"proc-1"}"""
        record(detector, "process", input, "still running")
        record(detector, "process", input, "still running")

        val warning = detector.checkBeforeToolCall("process", input)
        assertTrue(warning.warning)
        assertFalse(warning.critical)
        assertEquals(ToolLoopDetector.DetectorKind.KNOWN_POLL_NO_PROGRESS, warning.detector)

        record(detector, "process", input, "still running")

        val critical = detector.checkBeforeToolCall("process", input)
        assertFalse(critical.warning)
        assertTrue(critical.critical)
        assertEquals(ToolLoopDetector.DetectorKind.KNOWN_POLL_NO_PROGRESS, critical.detector)
    }

    @Test
    fun `ping pong with no progress becomes critical`() {
        val detector = ToolLoopDetector(
            ToolLoopDetectionConfig(
                enabled = true,
                warningThreshold = 2,
                criticalThreshold = 3,
                globalCircuitBreakerThreshold = 9,
            ),
        )

        record(detector, "read", """{"path":"a"}""", "same-a")
        record(detector, "read", """{"path":"b"}""", "same-b")
        record(detector, "read", """{"path":"a"}""", "same-a")
        record(detector, "read", """{"path":"b"}""", "same-b")

        val result = detector.checkBeforeToolCall("read", """{"path":"a"}""")
        assertTrue(result.critical)
        assertEquals(ToolLoopDetector.DetectorKind.PING_PONG, result.detector)
    }

    @Test
    fun `global circuit breaker blocks long no progress loops`() {
        val detector = ToolLoopDetector(
            ToolLoopDetectionConfig(
                enabled = true,
                warningThreshold = 2,
                criticalThreshold = 4,
                globalCircuitBreakerThreshold = 5,
            ),
        )

        val input = """{"path":"same"}"""
        repeat(5) {
            record(detector, "read", input, "same-output")
        }

        val result = detector.checkBeforeToolCall("read", input)
        assertTrue(result.critical)
        assertEquals(ToolLoopDetector.DetectorKind.GLOBAL_CIRCUIT_BREAKER, result.detector)
    }

    @Test
    fun `tool aliases normalize to shared loop signatures`() {
        val detector = ToolLoopDetector(
            ToolLoopDetectionConfig(
                enabled = true,
                warningThreshold = 2,
                criticalThreshold = 4,
                globalCircuitBreakerThreshold = 6,
                detectors = ToolLoopDetectionDetectorConfig(
                    genericRepeat = true,
                    knownPollNoProgress = false,
                    pingPong = false,
                ),
            ),
        )

        record(detector, "exec", """{"cmd":"ls"}""", "ok")
        record(detector, "bash", """{"cmd":"ls"}""", "ok")

        val result = detector.checkBeforeToolCall("bash", """{"cmd":"ls"}""")
        assertTrue(result.warning)
        assertEquals(ToolLoopDetector.DetectorKind.GENERIC_REPEAT, result.detector)
    }

    private fun record(
        detector: ToolLoopDetector,
        tool: String,
        input: String,
        output: String,
    ) {
        detector.recordToolCall(tool, input)
        detector.recordToolCallOutcome(tool, input, result = output)
    }
}
