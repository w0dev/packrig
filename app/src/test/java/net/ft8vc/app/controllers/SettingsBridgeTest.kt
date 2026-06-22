package net.ft8vc.app.controllers

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.app.settings.StationSettings
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.TxSlotParity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsBridgeTest {

    private val defaultSettings = StationSettings(
        myCall = "W0DEV",
        myGrid = "EM26",
        txToneHz = 1000,
        licenseAcknowledged = true,
        txEnabledInSettings = true,
        autoSeqEnabled = true,
        answerWhenCalledEnabled = true,
        autoAnswerCqEnabled = false,
        answerPolicy = AnswerPolicy.FIRST,
        maxUnansweredTxCycles = 5,
        inputGain = 1f,
        potaModeEnabled = false,
        potaParkRef = "",
        cq73OnlyFilter = false,
        decodeViewMode = DecodeViewMode.OPERATE,
        txSlotParity = TxSlotParity.EVEN,
        useDarkTheme = true,
    )

    // UnconfinedTestDispatcher runs coroutines eagerly — the bridge's collect fires
    // synchronously during SettingsBridge construction, so no advancing is needed.
    private lateinit var bridgeScope: CoroutineScope

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun setUp() {
        bridgeScope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        bridgeScope.cancel()
    }

    private fun makeRepo(initial: StationSettings = defaultSettings): Pair<SettingsRepository, MutableStateFlow<StationSettings>> {
        val flow = MutableStateFlow(initial)
        val repo = mockk<SettingsRepository> { every { settings } returns flow }
        return Pair(repo, flow)
    }

    @Test
    fun slice_emitsInitialSettingsImmediately() {
        val (repo, _) = makeRepo()
        val bridge = SettingsBridge(repo, bridgeScope)

        // StateFlow collects current value immediately; bridge's collect ran eagerly.
        val s = bridge.slice.value
        assertEquals("W0DEV", s.myCall)
        assertEquals("EM26", s.myGrid)
        assertTrue(s.licenseAcknowledged)
    }

    @Test
    fun slice_updatesWhenSettingsChange() = runTest {
        val (repo, flow) = makeRepo()
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.slice.test {
            awaitItem() // initial W0DEV/EM26

            flow.value = defaultSettings.copy(myCall = "K1ABC", myGrid = "FN42")

            val updated = awaitItem()
            assertEquals("K1ABC", updated.myCall)
            assertEquals("FN42", updated.myGrid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stationIdentityChanged_firesWhenCallChanges() = runTest {
        val (repo, flow) = makeRepo()
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.stationIdentityChanged.test {
            flow.value = defaultSettings.copy(myCall = "K1ABC")
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stationIdentityChanged_firesWhenGridChanges() = runTest {
        val (repo, flow) = makeRepo()
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.stationIdentityChanged.test {
            flow.value = defaultSettings.copy(myGrid = "FN42")
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stationIdentityChanged_firesWhenPotaModeChanges() = runTest {
        val (repo, flow) = makeRepo()
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.stationIdentityChanged.test {
            flow.value = defaultSettings.copy(potaModeEnabled = true)
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stationIdentityChanged_doesNotFireForNonIdentityChanges() = runTest {
        val (repo, flow) = makeRepo()
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.stationIdentityChanged.test {
            flow.value = defaultSettings.copy(txEnabledInSettings = false)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun slice_mapsAllFieldsCorrectly() {
        val settings = defaultSettings.copy(
            txToneHz = 1500,
            maxUnansweredTxCycles = 3,
            inputGain = 0.5f,
            answerPolicy = AnswerPolicy.BEST_SNR,
            decodeViewMode = DecodeViewMode.ALL,
            txSlotParity = TxSlotParity.ODD,
            useDarkTheme = false,
            cq73OnlyFilter = true,
        )
        val (repo, _) = makeRepo(initial = settings)
        val bridge = SettingsBridge(repo, bridgeScope)

        val s = bridge.slice.value
        assertEquals(1500, s.txToneHz)
        assertEquals(3, s.maxUnansweredTxCycles)
        assertEquals(0.5f, s.inputGain)
        assertEquals(AnswerPolicy.BEST_SNR, s.answerPolicy)
        assertEquals(DecodeViewMode.ALL, s.decodeViewMode)
        assertEquals(TxSlotParity.ODD, s.txSlotParity)
        assertFalse(s.useDarkTheme)
        assertTrue(s.cq73OnlyFilter)
    }
}
