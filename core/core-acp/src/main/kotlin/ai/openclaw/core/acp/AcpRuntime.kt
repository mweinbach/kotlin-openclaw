package ai.openclaw.core.acp

import ai.openclaw.core.model.*
import kotlinx.coroutines.flow.Flow

/**
 * ACP Runtime interface - the contract for backend implementations.
 * Ported from src/acp/runtime/types.ts
 */
interface AcpRuntime {
    suspend fun ensureSession(input: AcpRuntimeEnsureInput): AcpRuntimeHandle
    fun runTurn(input: AcpRuntimeTurnInput): Flow<AcpRuntimeEvent>
    suspend fun getCapabilities(handle: AcpRuntimeHandle?): AcpRuntimeCapabilities
    suspend fun getStatus(handle: AcpRuntimeHandle): AcpRuntimeStatus
    suspend fun setMode(handle: AcpRuntimeHandle, mode: String)
    suspend fun setConfigOption(handle: AcpRuntimeHandle, key: String, value: String)
    suspend fun doctor(): AcpRuntimeDoctorReport
    suspend fun cancel(handle: AcpRuntimeHandle, reason: String? = null)
    suspend fun close(handle: AcpRuntimeHandle, reason: String)
}
