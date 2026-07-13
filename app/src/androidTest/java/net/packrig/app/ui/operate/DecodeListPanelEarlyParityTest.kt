package net.packrig.app.ui.operate

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import net.packrig.app.DecodeRow
import net.packrig.core.DecodePassSource
import net.packrig.core.DecodeViewMode
import org.junit.Rule
import org.junit.Test

/**
 * Asserts that [DecodeListPanel] renders EARLY and FULL [DecodeRow]s with
 * identical text nodes — no EARLY-source-specific marker (chip, badge, label)
 * should appear in the UI.
 *
 * Pixel-parity acceptance criterion: "UI must NOT branch on passSource"
 * (see [DecodePassSource] KDoc and DecodeRow.passSource field KDoc).
 *
 * Requires a connected Android device or emulator via connectedDebugAndroidTest.
 * Method names must stay camelCase: backtick names with spaces fail D8 dexing
 * below DEX version 040 (minSdk 28).
 */
class DecodeListPanelEarlyParityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(passSource: DecodePassSource) = DecodeRow(
        id = 1_700_000_000L + when (passSource) {
            DecodePassSource.Early -> 1L
            DecodePassSource.Full -> 2L
        },
        timeUtc = "120000",
        snr = -10,
        dtSeconds = 0.1f,
        freqHz = 1500,
        message = "CQ K1ABC FN42",
        isCq = true,
        passSource = passSource,
    )

    @Test
    fun earlyAndFullRowsRenderTheSameSetOfTextNodes() {
        composeTestRule.setContent {
            DecodeListPanel(
                decodes = listOf(
                    row(DecodePassSource.Early),
                    row(DecodePassSource.Full),
                ),
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
            )
        }

        // Both rows display the same message text — two occurrences, one per row.
        // substring = true: rows render the message behind a DecodePrefix marker.
        composeTestRule.onAllNodesWithText("CQ K1ABC FN42", substring = true)
            .assertCountEquals(2)

        // No EARLY-source-specific marker text may appear anywhere in the tree.
        composeTestRule.onAllNodesWithText("EARLY").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Preview").assertCountEquals(0)
        // Note: "E" is not asserted as a standalone text node because the header
        // row and other UI text could legitimately contain the letter "E" and
        // substring matching would produce false positives.
    }
}
