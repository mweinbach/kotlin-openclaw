package ai.openclaw.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `settings screen renders top section items`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                SettingsScreen(onNavigate = {})
            }
        }

        // These items are at the top and should be visible
        composeTestRule.onNodeWithText("API Keys").assertIsDisplayed()
        composeTestRule.onNodeWithText("Models").assertIsDisplayed()
        composeTestRule.onNodeWithText("Agents").assertIsDisplayed()
    }

    @Test
    fun `settings screen has all items in tree`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                SettingsScreen(onNavigate = {})
            }
        }

        // Check items exist in the semantic tree (may need scroll)
        composeTestRule.onNodeWithText("API Keys").assertExists()
        composeTestRule.onNodeWithText("Models").assertExists()
        composeTestRule.onNodeWithText("Agents").assertExists()
        composeTestRule.onNodeWithText("Plugins").assertExists()
    }

    @Test
    fun `clicking API Keys invokes navigation`() {
        var navigatedTo: String? = null
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                SettingsScreen(onNavigate = { navigatedTo = it })
            }
        }

        composeTestRule.onNodeWithText("API Keys").performClick()
        assertTrue(navigatedTo?.contains("apikeys") == true,
            "Should navigate to API keys route, got: $navigatedTo")
    }

    @Test
    fun `clicking Models invokes navigation`() {
        var navigatedTo: String? = null
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                SettingsScreen(onNavigate = { navigatedTo = it })
            }
        }

        composeTestRule.onNodeWithText("Models").performClick()
        assertTrue(navigatedTo?.contains("models") == true,
            "Should navigate to models route, got: $navigatedTo")
    }

    @Test
    fun `clicking Agents invokes navigation`() {
        var navigatedTo: String? = null
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                SettingsScreen(onNavigate = { navigatedTo = it })
            }
        }

        composeTestRule.onNodeWithText("Agents").performClick()
        assertTrue(navigatedTo?.contains("agents") == true,
            "Should navigate to agents route, got: $navigatedTo")
    }
}
