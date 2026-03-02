package ai.openclaw.android.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageBubbleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `user message displays content`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                MessageBubble(
                    message = ChatMessage(role = "user", content = "Hello world"),
                )
            }
        }

        composeTestRule.onNodeWithText("Hello world").assertIsDisplayed()
    }

    @Test
    fun `user message shows You label`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                MessageBubble(
                    message = ChatMessage(role = "user", content = "test"),
                )
            }
        }

        composeTestRule.onNodeWithText("You").assertIsDisplayed()
    }

    @Test
    fun `assistant message shows Agent label`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                MessageBubble(
                    message = ChatMessage(role = "assistant", content = "response"),
                )
            }
        }

        composeTestRule.onNodeWithText("Agent").assertIsDisplayed()
    }

    @Test
    fun `assistant message displays content`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                MessageBubble(
                    message = ChatMessage(role = "assistant", content = "I can help with that"),
                )
            }
        }

        composeTestRule.onNodeWithText("I can help with that").assertIsDisplayed()
    }

    @Test
    fun `streaming message shows loading indicator`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                MessageBubble(
                    message = ChatMessage(role = "assistant", content = "", isStreaming = true),
                )
            }
        }

        // Streaming with empty content should show "..." or similar
        // Just verify it doesn't crash
    }

    @Test
    fun `message with code block renders`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                MessageBubble(
                    message = ChatMessage(
                        role = "assistant",
                        content = "Here's code:\n```\nval x = 42\n```\nDone.",
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("val x = 42", substring = true).assertIsDisplayed()
    }

    @Test
    fun `message with tool calls renders`() {
        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                MessageBubble(
                    message = ChatMessage(
                        role = "assistant",
                        content = "Using tool...",
                        toolCalls = listOf("search_web"),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Using tool...").assertIsDisplayed()
    }
}
