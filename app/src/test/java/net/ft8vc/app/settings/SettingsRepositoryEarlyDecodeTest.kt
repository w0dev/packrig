package net.ft8vc.app.settings

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.controllers.SettingsBridge
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that earlyDecodeEnabled defaults to true and propagates correctly
 * through StationSettings and the SettingsBridge slice.
 *
 * Pattern mirrors SettingsBridgeTest.sliceCarriesLateStartTxEnabled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryEarlyDecodeTest {

    private lateinit var bridgeScope: CoroutineScope

    @Before fun setUp() {
        bridgeScope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        bridgeScope.cancel()
    }

    private fun makeRepo(initial: StationSettings): Pair<SettingsRepository, MutableStateFlow<StationSettings>> {
        val flow = MutableStateFlow(initial)
        val repo = mockk<SettingsRepository> { every { settings } returns flow }
        return Pair(repo, flow)
    }

    @Test
    fun earlyDecodeEnabledDefaultsTrue() {
        val s = StationSettings()
        assertTrue("earlyDecodeEnabled must default to ON", s.earlyDecodeEnabled)
    }

    @Test
    fun sliceCarriesEarlyDecodeEnabled_default() = runTest {
        val (repo, _) = makeRepo(initial = StationSettings())
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.slice.test {
            assertTrue(awaitItem().earlyDecodeEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sliceCarriesEarlyDecodeEnabled_roundTrip() = runTest {
        val (repo, flow) = makeRepo(initial = StationSettings(earlyDecodeEnabled = false))
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.slice.test {
            assertFalse(awaitItem().earlyDecodeEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
