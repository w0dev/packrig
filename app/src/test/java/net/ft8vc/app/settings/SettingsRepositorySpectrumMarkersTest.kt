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

/** Mirrors SettingsRepositoryEarlyDecodeTest: default ON + slice round-trip. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositorySpectrumMarkersTest {

    private lateinit var bridgeScope: CoroutineScope

    @Before fun setUp() {
        bridgeScope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        bridgeScope.cancel()
    }

    private fun makeRepo(initial: StationSettings): SettingsRepository {
        val flow = MutableStateFlow(initial)
        return mockk<SettingsRepository> { every { settings } returns flow }
    }

    @Test
    fun spectrumMarkersEnabledDefaultsTrue() {
        assertTrue(StationSettings().spectrumMarkersEnabled)
    }

    @Test
    fun sliceCarriesSpectrumMarkersEnabled_default() = runTest {
        val bridge = SettingsBridge(makeRepo(StationSettings()), bridgeScope)
        bridge.slice.test {
            assertTrue(awaitItem().spectrumMarkersEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sliceCarriesSpectrumMarkersEnabled_roundTrip() = runTest {
        val bridge = SettingsBridge(makeRepo(StationSettings(spectrumMarkersEnabled = false)), bridgeScope)
        bridge.slice.test {
            assertFalse(awaitItem().spectrumMarkersEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
