package net.packset.app.ui.operate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.packset.app.OperateUiState
import net.packset.core.OperateTxOptions
import net.packset.core.QsoForm
import net.packset.core.QsoTxStep
import org.junit.Rule
import org.junit.Test

/**
 * Pins the 2026-07-04 field crash: opening the TX message dropdown during an
 * active QSO threw "Vertically scrollable component was measured with an
 * infinity maximum height constraints" — the menu wrapped its items in a
 * Column(verticalScroll) inside Material3's DropdownMenu, which already
 * scrolls its content internally and measures children with unbounded height.
 *
 * Requires a connected Android device or emulator via connectedDebugAndroidTest.
 * Method names must stay camelCase: backtick names with spaces fail D8 dexing
 * below DEX version 040 (minSdk 28).
 */
class OperateTxSelectorMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setSelector(state: OperateUiState) {
        composeTestRule.setContent {
            OperateTxSelector(
                state = state,
                onMessageChange = {},
                onSelectStep = {},
                onResetMessage = {},
            )
        }
    }

    private fun activeQsoState() = OperateUiState(
        isOperating = true,
        txEnabled = true,
        qsoActive = true,
        myCall = "W0DEV",
        myGrid = "EM12",
        operateTxText = "K1ABC W0DEV -05",
        operateTxStep = QsoTxStep.Report,
        operateTxForm = QsoForm(
            dxCall = "K1ABC",
            dxGrid = "FN42",
            reportSent = -5,
            txStep = QsoTxStep.Report,
        ),
    )

    @Test
    fun openingTxStepMenuMidQsoDoesNotCrash() {
        setSelector(activeQsoState())
        composeTestRule.onNodeWithText("Msg ▾").performClick()
        // Last menu item — below the 260.dp cutoff, reachable via the menu's own scroll.
        composeTestRule.onNodeWithText(OperateTxOptions.CUSTOM_LABEL)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun txStepMenuShowsAllSequenceSteps() {
        setSelector(activeQsoState())
        composeTestRule.onNodeWithText("Msg ▾").performClick()
        OperateTxOptions.qsoSequenceSteps.forEach { step ->
            composeTestRule.onNodeWithText(step.label).assertIsDisplayed()
        }
    }
}
