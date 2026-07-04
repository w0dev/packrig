package net.ft8vc.app.ui.operate

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.ft8vc.app.DecodeRow
import net.ft8vc.core.DecodeViewMode
import org.junit.Rule
import org.junit.Test

/**
 * Asserts the decode list FOLLOWS new traffic: when the operator is at the
 * bottom (newest decode visible), a newly prepended row must scroll into
 * view on its own; when the operator has scrolled up into history, the
 * viewport must hold position instead (field request 2026-07-03).
 *
 * Keyed LazyColumn items anchor scroll position by key on prepend, which by
 * itself un-pins the bottom — these tests cover the explicit re-pin.
 *
 * Requires a connected Android device or emulator via connectedDebugAndroidTest.
 * Method names must stay camelCase: backtick names with spaces fail D8 dexing
 * below DEX version 040 (minSdk 28).
 */
class DecodeListPanelFollowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(id: Long, message: String) = DecodeRow(
        id = id,
        timeUtc = "%02d00%02d".format(12, (id % 60).toInt()),
        snr = -10,
        dtSeconds = 0.1f,
        freqHz = 1500,
        message = message,
        isCq = true,
    )

    /** Newest-first slice, ids [count]..1, tall enough to overflow the panel. */
    private fun history(count: Int): List<DecodeRow> =
        (count downTo 1).map { row(it.toLong(), "CQ K${it}ABC FN42") }

    private fun setPanel(decodesState: () -> List<DecodeRow>) {
        composeTestRule.setContent {
            DecodeListPanel(
                decodes = decodesState(),
                myCall = "W0DEV",
                txToneHz = 1500,
                decodeViewMode = DecodeViewMode.ALL,
                onDecodeViewModeChange = {},
                cq73OnlyFilter = false,
                onCq73OnlyFilterChange = {},
                qsoDx = null,
                qsoActive = false,
                canAnswer = false,
                canResume = false,
                onClear = {},
                onAnswerCq = {},
                onResume = {},
                modifier = Modifier.height(280.dp),
            )
        }
    }

    @Test
    fun newDecodeScrollsIntoViewWhenPinnedAtBottom() {
        var decodes by mutableStateOf(history(30))
        setPanel { decodes }

        // Initial layout starts at index 0 = bottom = newest; operator is pinned.
        composeTestRule.onNodeWithText("CQ K1ABC FN42", substring = true)
            .assertIsDisplayed()

        composeTestRule.runOnIdle {
            decodes = listOf(row(31L, "CQ N0NEW EN50")) + decodes
        }

        composeTestRule.onNodeWithText("CQ N0NEW EN50", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun viewportHoldsPositionWhenScrolledIntoHistory() {
        var decodes by mutableStateOf(history(30))
        setPanel { decodes }

        // Scroll up into history, away from the newest row at the bottom.
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("CQ K30ABC FN42", substring = true))
        composeTestRule.onNodeWithText("CQ K30ABC FN42", substring = true)
            .assertIsDisplayed()

        composeTestRule.runOnIdle {
            decodes = listOf(row(31L, "CQ N0NEW EN50")) + decodes
        }

        // Reading history must not be yanked back to the bottom.
        composeTestRule.onNodeWithText("CQ K30ABC FN42", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("CQ N0NEW EN50", substring = true)
            .assertIsNotDisplayed()
    }
}
