package ai.openclaw.android

import android.app.Application
import kotlinx.coroutines.runBlocking

/**
 * Main Application class for OpenClaw.
 * Initializes the agent engine and subsystems on startup.
 */
class OpenClawApp : Application() {
    val engine: AgentEngine by lazy { AgentEngine(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
