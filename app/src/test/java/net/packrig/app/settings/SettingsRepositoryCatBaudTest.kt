package net.packrig.app.settings

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.packrig.app.controllers.SettingsBridge
import net.packrig.rig.RigController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * catBaud defaults to the rig-module default (38400, behavior parity with v1.0),
 * coerces unknown values, and propagates through the SettingsBridge slice.
 * Spec: docs/superpowers/specs/2026-07-04-radio-settings-design.md
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryCatBaudTest {

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
    fun catBaudDefaultsToRigDefault() {
        assertEquals(RigController.DEFAULT_CAT_BAUD, StationSettings().catBaud)
        assertEquals(38_400, StationSettings().catBaud)
    }

    @Test
    fun coerceCatBaudPassesKnownOptions() {
        SettingsRepository.CAT_BAUD_OPTIONS.forEach { baud ->
            assertEquals(baud, SettingsRepository.coerceCatBaud(baud))
        }
    }

    @Test
    fun coerceCatBaudFallsBackToDefaultForUnknown() {
        assertEquals(38_400, SettingsRepository.coerceCatBaud(0))
        assertEquals(38_400, SettingsRepository.coerceCatBaud(-1))
        assertEquals(38_400, SettingsRepository.coerceCatBaud(230_400))
    }

    @Test
    fun catBaudOptionsMatchFt891MenuPlusCivRates() {
        assertEquals(listOf(4800, 9600, 19200, 38400, 57600, 115200), SettingsRepository.CAT_BAUD_OPTIONS)
    }

    @Test
    fun sliceCarriesCatBaud_default() = runTest {
        val (repo, _) = makeRepo(initial = StationSettings())
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.slice.test {
            assertEquals(38_400, awaitItem().catBaud)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sliceCarriesCatBaud_roundTrip() = runTest {
        val (repo, _) = makeRepo(initial = StationSettings(catBaud = 4800))
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.slice.test {
            assertEquals(4800, awaitItem().catBaud)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
