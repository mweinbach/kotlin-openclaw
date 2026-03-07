package ai.openclaw.android.ui.tools.approvals

import ai.openclaw.core.security.ApprovalDecision
import ai.openclaw.core.security.ApprovalManager
import ai.openclaw.core.security.ApprovalPolicy
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApprovalsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        backgroundScope.cancel()
    }

    @Test
    fun `approvals screen reacts to approval manager events`() {
        val approvalManager = ApprovalManager(policy = AlwaysRequireApprovalPolicy)

        composeTestRule.setContent {
            ai.openclaw.android.ui.theme.OpenClawTheme {
                ApprovalsScreen(
                    approvalManager = approvalManager,
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No pending approvals").assertIsDisplayed()
        composeTestRule.waitForIdle()

        val decision = backgroundScope.async {
            approvalManager.checkApproval(
                toolName = "exec",
                toolInput = """{"command":"ls"}""",
                agentId = "agent-1",
                sessionKey = "session-1",
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 1_500) {
            runCatching {
                composeTestRule.onNodeWithText("exec").assertIsDisplayed()
            }.isSuccess
        }

        composeTestRule.onNodeWithText("exec").assertIsDisplayed()
        composeTestRule.onNodeWithText("Approve").performClick()

        composeTestRule.waitUntil(timeoutMillis = 1_500) { decision.isCompleted }
        assertEquals(ApprovalDecision.APPROVED, runBlocking { decision.await() })

        composeTestRule.waitUntil(timeoutMillis = 1_500) {
            runCatching {
                composeTestRule.onNodeWithText("No pending approvals").assertIsDisplayed()
            }.isSuccess
        }
        composeTestRule.onNodeWithText("No pending approvals").assertIsDisplayed()
    }
}

private object AlwaysRequireApprovalPolicy : ApprovalPolicy {
    override fun requiresApproval(
        toolName: String,
        toolInput: String,
        agentId: String,
        sessionKey: String,
    ): Boolean = true
}
