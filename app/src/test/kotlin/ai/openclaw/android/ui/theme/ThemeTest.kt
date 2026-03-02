package ai.openclaw.android.ui.theme

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `OpenClawTheme provides MaterialTheme color scheme`() {
        var hasColors = false
        composeTestRule.setContent {
            OpenClawTheme(dynamicColor = false) {
                hasColors = true
                assertNotNull(MaterialTheme.colorScheme.primary)
                assertNotNull(MaterialTheme.colorScheme.background)
                assertNotNull(MaterialTheme.colorScheme.surface)
            }
        }
        assert(hasColors) { "Theme content block should have executed" }
    }

    @Test
    fun `LocalStatusColors provides status colors`() {
        var colors: StatusColors? = null
        composeTestRule.setContent {
            OpenClawTheme(dynamicColor = false) {
                colors = LocalStatusColors.current
            }
        }
        assertNotNull(colors)
        assertNotNull(colors?.connected)
        assertNotNull(colors?.warning)
        assertNotNull(colors?.error)
        assertNotNull(colors?.offline)
        assertNotNull(colors?.info)
    }

    @Test
    fun `status colors are distinct from each other`() {
        var colors: StatusColors? = null
        composeTestRule.setContent {
            OpenClawTheme(dynamicColor = false) {
                colors = LocalStatusColors.current
            }
        }
        val c = colors!!
        assertNotEquals(c.connected, c.error, "Connected and error colors should differ")
        assertNotEquals(c.connected, c.offline, "Connected and offline colors should differ")
        assertNotEquals(c.error, c.warning, "Error and warning colors should differ")
    }

    @Test
    fun `dark theme provides theme without crash`() {
        composeTestRule.setContent {
            OpenClawTheme(darkTheme = true, dynamicColor = false) {
                assertNotNull(MaterialTheme.colorScheme.primary)
            }
        }
    }

    @Test
    fun `light theme provides theme without crash`() {
        composeTestRule.setContent {
            OpenClawTheme(darkTheme = false, dynamicColor = false) {
                assertNotNull(MaterialTheme.colorScheme.primary)
            }
        }
    }
}
