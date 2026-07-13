package net.packrig.app.settings

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.packrig.app.controllers.SettingsBridge
import net.packrig.core.DecodeCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DecodeColorSchemeTest {

    private lateinit var bridgeScope: CoroutineScope

    @Before fun setUp() {
        bridgeScope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        bridgeScope.cancel()
    }

    @Test
    fun defaultsMatchSpec() {
        val d = DecodeColorScheme.DEFAULT
        assertEquals(0xFFFFB347.toInt(), d.ownTx)
        assertEquals(0xFFE63946.toInt(), d.partner)
        assertEquals(0xFFE63946.toInt(), d.myCall)
        assertEquals(0xFF3DDC97.toInt(), d.cqNew)
        assertEquals(0xFF4CC9F0.toInt(), d.cqWorkedOtherBand)
        assertEquals(0xFF9AA0A6.toInt(), d.cqWorkedThisBand)
    }

    @Test
    fun colorForCoversEveryConfigurableCategory() {
        val d = DecodeColorScheme.DEFAULT
        assertEquals(d.ownTx, d.colorFor(DecodeCategory.OWN_TX))
        assertEquals(d.partner, d.colorFor(DecodeCategory.PARTNER))
        assertEquals(d.myCall, d.colorFor(DecodeCategory.MY_CALL))
        assertEquals(d.cqNew, d.colorFor(DecodeCategory.CQ_NEW))
        assertEquals(d.cqWorkedOtherBand, d.colorFor(DecodeCategory.CQ_WORKED_OTHER_BAND))
        assertEquals(d.cqWorkedThisBand, d.colorFor(DecodeCategory.CQ_WORKED_THIS_BAND))
        assertNull(d.colorFor(DecodeCategory.OTHER))
    }

    @Test
    fun withColorUpdatesOnlyTheGivenCategory() {
        val updated = DecodeColorScheme.DEFAULT
            .withColor(DecodeCategory.MY_CALL, 0xFF4CC9F0.toInt())
        assertEquals(0xFF4CC9F0.toInt(), updated.myCall)
        assertEquals(DecodeColorScheme.DEFAULT.partner, updated.partner)
        assertEquals(DecodeColorScheme.DEFAULT.ownTx, updated.ownTx)
        // OTHER is not configurable — a no-op, not a crash.
        assertEquals(updated, updated.withColor(DecodeCategory.OTHER, 0x11223344))
    }

    @Test
    fun stationSettingsDefaultCarriesDefaultScheme() {
        assertEquals(DecodeColorScheme.DEFAULT, StationSettings().decodeColors)
    }

    @Test
    fun sliceCarriesDecodeColors() = runTest {
        val custom = DecodeColorScheme.DEFAULT
            .withColor(DecodeCategory.CQ_NEW, 0xFFFFD166.toInt())
        val flow = MutableStateFlow(StationSettings(decodeColors = custom))
        val repo = mockk<SettingsRepository> { every { settings } returns flow }
        val bridge = SettingsBridge(repo, bridgeScope)
        assertEquals(custom, bridge.slice.value.decodeColors)
    }
}
