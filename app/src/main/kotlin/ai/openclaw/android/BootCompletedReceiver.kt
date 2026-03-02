package ai.openclaw.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts background runtime after device reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> AgentForegroundService.start(context.applicationContext)
        }
    }
}
