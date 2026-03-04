package ai.openclaw.runtime.providers

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.executeCancellable(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }

    try {
        val response = execute()
        if (continuation.isCancelled) {
            response.close()
            return@suspendCancellableCoroutine
        }
        continuation.resume(response)
    } catch (err: Throwable) {
        if (!continuation.isCancelled) {
            continuation.resumeWithException(err)
        }
    }
}
