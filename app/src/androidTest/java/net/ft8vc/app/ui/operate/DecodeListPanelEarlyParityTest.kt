package net.ft8vc.app.ui.operate

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import net.ft8vc.app.DecodeRow
import net.ft8vc.core.DecodePassSource
import net.ft8vc.core.DecodeViewMode
import org.junit.Ignore
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
 * @Ignore reason: this module has no Compose UI test infrastructure
 * (no createComposeRule in any test source set, no Paparazzi/Roborazzi wired up,
 * no androidTest source set populated before this task). These tests require a
 * connected Android device or emulator via connectedDebugAndroidTest.
 * Pixel-parity is verified manually via field-session smoke check (Task 8).
 * To activate: wire up androidx.compose.ui:ui-test-junit4 in androidTestImplementation
 * and run: ./gradlew :app:connectedDebugAndroidTest --tests
 * "net.ft8vc.app.ui.operate.DecodeListPanelEarlyParityTest"
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

    @Ignore(
        "No Compose UI test infrastructure in this module; pixel parity verified manually " +
            "via field session. See class KDoc for activation instructions."
    )
    @Test
    fun `early and full rows render the same set of text nodes`() {
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
        composeTestRule.onAllNodesWithText("CQ K1ABC FN42").assertCountEquals(2)

        // No EARLY-source-specific marker text may appear anywhere in the tree.
        composeTestRule.onAllNodesWithText("EARLY").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Preview").assertCountEquals(0)
        // Note: "E" is not asserted as a standalone text node because the header
        // row and other UI text could legitimately contain the letter "E" and
        // substring matching would produce false positives.
    }
}
