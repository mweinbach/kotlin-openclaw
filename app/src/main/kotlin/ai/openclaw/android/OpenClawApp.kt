package ai.openclaw.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Main Application class for OpenClaw.
 * Initializes the agent engine and subsystems on startup.
 */
class OpenClawApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: AgentEngine by lazy { AgentEngine(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize engine asynchronously.
        appScope.launch {
            runCatching { engine.initialize() }
        }
    }

    override fun onTerminate() {
        runBlocking {
            runCatching { engine.shutdown() }
        }
        super.onTerminate()
    }

    companion object {
        lateinit var instance: OpenClawApp
            private set
    }
}
