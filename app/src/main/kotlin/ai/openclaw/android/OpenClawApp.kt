package ai.openclaw.android

import android.app.Application

/**
 * Main Application class for OpenClaw.
 * Initializes the agent engine and subsystems lazily.
 */
class OpenClawApp : Application() {

    val engine: AgentEngine by lazy { AgentEngine(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: OpenClawApp
            private set
    }
}
