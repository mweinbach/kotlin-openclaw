package ai.openclaw.android.ui.dashboard

import ai.openclaw.android.AgentEngine
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var engine: AgentEngine

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.filesDir.resolve("config").deleteRecursively()
        context.filesDir.resolve("sessions").deleteRecursively()
        context.filesDir.resolve("cron").deleteRecursively()
        engine = AgentEngine(context)
        engine.loadConfig()
    }

    @Test
    fun `dashboard screen renders title`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                DashboardScreen(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Dashboard").assertIsDisplayed()
    }

    @Test
    fun `dashboard screen shows gateway card`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                DashboardScreen(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Gateway").assertExists()
    }

    @Test
    fun `dashboard screen shows channels card`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                DashboardScreen(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Channels").assertExists()
    }

    @Test
    fun `dashboard screen shows sessions card`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                DashboardScreen(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Sessions").assertExists()
    }

    @Test
    fun `dashboard screen shows toolchains card`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                DashboardScreen(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Toolchains").assertExists()
    }
}
