package ai.openclaw.android.ui

import ai.openclaw.android.ui.components.*
import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- EmptyState tests ---

    @Test
    fun `EmptyState displays message`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                EmptyState(
                    icon = Icons.Default.Info,
                    message = "Nothing to see here",
                )
            }
        }

        composeTestRule.onNodeWithText("Nothing to see here").assertIsDisplayed()
    }

    @Test
    fun `EmptyState with action shows button`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                EmptyState(
                    icon = Icons.Default.Info,
                    message = "No items",
                    action = "Add Item",
                    onAction = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add Item").assertIsDisplayed()
    }

    @Test
    fun `EmptyState action button triggers callback`() {
        var clicked = false
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                EmptyState(
                    icon = Icons.Default.Info,
                    message = "No items",
                    action = "Add Item",
                    onAction = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Add Item").performClick()
        assertTrue(clicked, "Action callback should have been invoked")
    }

    // --- SectionCard tests ---

    @Test
    fun `SectionCard displays title`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                SectionCard(title = "Test Card")
            }
        }

        composeTestRule.onNodeWithText("Test Card").assertIsDisplayed()
    }

    @Test
    fun `SectionCard displays subtitle`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                SectionCard(title = "Title", subtitle = "Subtitle text")
            }
        }

        composeTestRule.onNodeWithText("Subtitle text").assertIsDisplayed()
    }

    @Test
    fun `SectionCard onClick is invoked`() {
        var clicked = false
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                SectionCard(
                    title = "Clickable Card",
                    onClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Clickable Card").performClick()
        assertTrue(clicked, "Card onClick should have been invoked")
    }

    // --- StatusIndicator tests ---

    @Test
    fun `StatusIndicator renders for Connected status`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                StatusIndicator(status = Status.Connected)
            }
        }
        // Just verify it doesn't crash; visual validation not possible in unit test
    }

    @Test
    fun `StatusIndicator renders for Error status`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                StatusIndicator(status = Status.Error)
            }
        }
    }

    @Test
    fun `StatusIndicator renders for Offline status`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                StatusIndicator(status = Status.Offline)
            }
        }
    }

    @Test
    fun `StatusDot renders for connected state`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                StatusDot(connected = true)
            }
        }
    }

    @Test
    fun `StatusDot renders for disconnected state`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                StatusDot(connected = false)
            }
        }
    }
}
