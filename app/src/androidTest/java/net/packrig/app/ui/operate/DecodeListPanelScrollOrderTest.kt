package net.packrig.app.ui.operate

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import net.packrig.app.DecodeRow
import net.packrig.core.DecodeViewMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Asserts that [DecodeListPanel] renders the most recent decode BELOW older
 * ones — terminal-style ordering so new traffic lands where the eye rests,
 * with the list sticking to the bottom as slots arrive (field request
 * 2026-07-03). The slice remains newest-first; the panel is responsible for
 * presenting it bottom-up.
 *
 * Requires a connected Android device or emulator via connectedDebugAndroidTest.
 * Method names must stay camelCase: backtick names with spaces fail D8 dexing
 * below DEX version 040 (minSdk 28).
 */
class DecodeListPanelScrollOrderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(id: Long, timeUtc: String, message: String) = DecodeRow(
        id = id,
        timeUtc = timeUtc,
        snr = -10,
        dtSeconds = 0.1f,
        freqHz = 1500,
        message = message,
        isCq = true,
    )

    @Test
    fun newestDecodeRendersBelowOlderDecode() {
        composeTestRule.setContent {
            DecodeListPanel(
                // Slice order is newest-first (DecodeController prepends).
                decodes = listOf(
                    row(id = 2L, timeUtc = "120015", message = "CQ N0XYZ EN50"),
                    row(id = 1L, timeUtc = "120000", message = "CQ K1ABC FN42"),
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

        val newerTop = composeTestRule
            .onNodeWithText("CQ N0XYZ EN50", substring = true)
            .getUnclippedBoundsInRoot().top
        val olderTop = composeTestRule
            .onNodeWithText("CQ K1ABC FN42", substring = true)
            .getUnclippedBoundsInRoot().top

        assertTrue(
            "newest decode (top=$newerTop) must render below the older one (top=$olderTop)",
            newerTop > olderTop,
        )
    }
}
