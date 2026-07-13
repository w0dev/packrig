package net.packset.app.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTextFieldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun typedTextIsTransformedAsTyped() {
        val persisted = mutableStateOf("")
        val committed = mutableListOf<String>()
        composeTestRule.setContent {
            SettingsTextField(
                persistedValue = persisted.value,
                onValueChange = { committed += it },
                transform = { it.uppercase() },
                label = { Text("Callsign") },
                modifier = Modifier.testTag("field"),
            )
        }

        composeTestRule.onNodeWithTag("field").performTextInput("w0dev")

        composeTestRule.onNodeWithTag("field").assertTextContains("W0DEV")
        assertEquals("W0DEV", committed.last())
    }

    @Test
    fun stalePersistedEchoDoesNotClobberEdits() {
        val persisted = mutableStateOf("")
        composeTestRule.setContent {
            SettingsTextField(
                persistedValue = persisted.value,
                onValueChange = {},
                transform = { it.uppercase() },
                label = { Text("Callsign") },
                modifier = Modifier.testTag("field"),
            )
        }

        composeTestRule.onNodeWithTag("field").performTextInput("w0")
        // A stale persisted value arrives after the user has typed further —
        // the round-trip through DataStore must not rewind the field.
        persisted.value = "W"
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("field").assertTextContains("W0")
    }

    @Test
    fun persistedValueSeedsFieldBeforeFirstEdit() {
        val persisted = mutableStateOf("")
        composeTestRule.setContent {
            SettingsTextField(
                persistedValue = persisted.value,
                onValueChange = {},
                label = { Text("Callsign") },
                modifier = Modifier.testTag("field"),
            )
        }

        // Settings load asynchronously from DataStore; the stored value must
        // appear once it arrives as long as the user hasn't typed.
        persisted.value = "W0DEV"
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("field").assertTextContains("W0DEV")
    }

    @Test
    fun validationTracksShownTextNotPersistedEcho() {
        val persisted = mutableStateOf("")
        composeTestRule.setContent {
            SettingsTextField(
                persistedValue = persisted.value,
                onValueChange = {},
                transform = { it.uppercase() },
                label = { Text("Callsign") },
                modifier = Modifier.testTag("field"),
                isError = { it == "BAD" },
                supportingText = { shown -> if (shown == "BAD") Text("Doesn't look right") },
            )
        }

        composeTestRule.onNodeWithTag("field").performTextInput("bad")

        composeTestRule.onNodeWithTag("field").assertTextContains("BAD")
        composeTestRule.onNodeWithText("Doesn't look right").assertExists()
    }
}
