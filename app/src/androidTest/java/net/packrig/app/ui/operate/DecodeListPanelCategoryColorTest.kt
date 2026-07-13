package net.packrig.app.ui.operate

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import net.packrig.app.DecodeRow
import net.packrig.core.DecodeViewMode
import org.junit.Rule
import org.junit.Test

/**
 * Pins the decode-colorscheme spec's core fix: messages directed at my call
 * keep the strong (filled) treatment DURING an active QSO — partner replies
 * classify PARTNER, tail-enders classify MY_CALL, and neither renders as
 * plain CQ/chatter styling.
 *
 * Requires a connected Android device or emulator via connectedDebugAndroidTest.
 * Method names must stay camelCase: backtick names with spaces fail D8 dexing
 * below DEX version 040 (minSdk 28).
 */
class DecodeListPanelCategoryColorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setPanel(rows: List<DecodeRow>) {
        composeTestRule.setContent {
            DecodeListPanel(
                decodes = rows,
                myCall = "W0DEV",
                txToneHz = 1500,
                decodeViewMode = DecodeViewMode.ALL,
                onDecodeViewModeChange = {},
                cq73OnlyFilter = false,
                onCq73OnlyFilterChange = {},
                qsoDx = "K1ABC",
                qsoActive = true,
                canAnswer = false,
                canResume = false,
                onClear = {},
                onAnswerCq = {},
                onResume = {},
            )
        }
    }

    @Test
    fun partnerReplyMidQsoRendersAsPartnerCategory() {
        setPanel(
            listOf(
                DecodeRow(
                    id = 1L, timeUtc = "120015", snr = -5, dtSeconds = 0.2f,
                    freqHz = 1500, message = "W0DEV K1ABC -05",
                    isCq = false, isToMe = true,
                ),
            ),
        )
        composeTestRule.onAllNodesWithTag("decodeRow_PARTNER", useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("decodeRow_OTHER", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun tailEnderMidQsoRendersAsMyCallCategory() {
        setPanel(
            listOf(
                DecodeRow(
                    id = 2L, timeUtc = "120015", snr = -12, dtSeconds = 0.1f,
                    freqHz = 900, message = "W0DEV N5XYZ EM10",
                    isCq = false, isToMe = true,
                ),
            ),
        )
        composeTestRule.onAllNodesWithTag("decodeRow_MY_CALL", useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun unrelatedChatterMidQsoStaysOther() {
        setPanel(
            listOf(
                DecodeRow(
                    id = 3L, timeUtc = "120015", snr = -3, dtSeconds = 0.0f,
                    freqHz = 2200, message = "N5XYZ W1ABC RR73",
                    isCq = false, isToMe = false,
                ),
            ),
        )
        composeTestRule.onAllNodesWithTag("decodeRow_OTHER", useUnmergedTree = true)
            .assertCountEquals(1)
    }
}
