package ai.openclaw.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.openclaw.android.ui.navigation.AppNavigation
import ai.openclaw.android.ui.theme.OpenClawTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OpenClawApp
        handleOauthIntent(app, intent)
        requestNotificationPermissionIfNeeded()
        setContent {
            OpenClawTheme {
                AppNavigation(engine = app.engine)
            }
        }
        lifecycleScope.launch {
            runCatching { app.engine.initialize() }
                .onFailure { Log.w(TAG, "Engine initialization failed", it) }
            if (app.engine.keepAliveInBackgroundEnabled()) {
                runCatching { AgentForegroundService.start(applicationContext) }
                    .onFailure { Log.w(TAG, "Foreground service start skipped", it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val app = application as OpenClawApp
        handleOauthIntent(app, intent)
    }

    private fun handleOauthIntent(app: OpenClawApp, intent: Intent?) {
        val redirectUri = intent?.data ?: return
        if (!app.engine.isCodexOauthRedirect(redirectUri)) return
        lifecycleScope.launch {
            app.engine.completeCodexOauthRedirect(redirectUri)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
        private const val TAG = "MainActivity"
    }
}
