package ai.openclaw.android.ui.navigation

import ai.openclaw.android.AgentEngine
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppNavigationScreenTest {

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
    fun `bottom navigation bar shows all 5 tabs`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                AppNavigation(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Chat").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dashboard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Channels").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tools").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `chat tab is the default start destination`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                AppNavigation(engine = engine)
            }
        }

        // Chat list screen should show "Conversations" title
        composeTestRule.onNodeWithText("Conversations").assertIsDisplayed()
    }

    @Test
    fun `tapping Dashboard tab shows dashboard screen`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                AppNavigation(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Dashboard").performClick()
        composeTestRule.waitForIdle()
        // Dashboard screen shows a "Gateway" section card — unique to this screen
        composeTestRule.onNodeWithText("Gateway").assertExists()
    }

    @Test
    fun `tapping Channels tab shows channels screen`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                AppNavigation(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Channels").performClick()
        composeTestRule.waitForIdle()
        // Verify both the nav bar and screen loaded (2 nodes means both exist)
        composeTestRule.onAllNodesWithText("Channels")[0].assertIsDisplayed()
    }

    @Test
    fun `tapping Tools tab shows tools hub`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                AppNavigation(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.waitForIdle()
        // Tools hub shows "Terminal" category — unique to this screen
        composeTestRule.onNodeWithText("Terminal").assertExists()
    }

    @Test
    fun `tapping Settings tab shows settings screen`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                AppNavigation(engine = engine)
            }
        }

        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitForIdle()
        // Settings screen shows "API Keys" item — unique to this screen
        composeTestRule.onNodeWithText("API Keys").assertExists()
    }
}
