package ai.openclaw.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.openclaw.android.ui.navigation.AppNavigation
import ai.openclaw.android.ui.theme.OpenClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OpenClawApp
        setContent {
            OpenClawTheme {
                AppNavigation(engine = app.engine)
            }
        }
    }
}
