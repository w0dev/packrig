package net.ft8vc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins MainActivity's launchMode to singleTask.
 *
 * MainActivity carries the USB_DEVICE_ATTACHED intent-filter. With the default
 * `standard` launchMode, every Digirig attach/re-enumeration launches a SECOND
 * MainActivity instance stacked on the first — a second ViewModelStore and a
 * second OperateViewModel, so two QsoSessionControllers (two QSO machines, two
 * DupeLogGuards, two capture pipelines, two rig controllers) run concurrently
 * as the same station. Field fallout 2026-07-03: duplicate log entries 15 s
 * apart with divergent snapshots, conflicting signal reports on air, and ANRs
 * from USB contention. singleTask routes the attach to the existing instance
 * via onNewIntent instead.
 */
class MainActivityLaunchModeTest {

    @Test
    fun mainActivityDeclaresSingleTaskLaunchMode() {
        // Gradle unit tests run with the module directory as the working dir.
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.exists())
        val text = manifest.readText()

        val activityBlock = Regex(
            """<activity[^>]*android:name="\.MainActivity"[^>]*>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)?.value
        requireNotNull(activityBlock) { "MainActivity <activity> element not found" }

        val launchMode = Regex("""android:launchMode="([^"]+)"""")
            .find(activityBlock)?.groupValues?.get(1)
        assertEquals(
            "MainActivity must declare launchMode=singleTask — the USB attach " +
                "intent-filter otherwise stacks a second activity/ViewModel instance",
            "singleTask",
            launchMode,
        )
    }
}
