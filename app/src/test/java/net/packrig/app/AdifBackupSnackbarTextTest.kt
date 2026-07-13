package net.packrig.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AdifBackupSnackbarTextTest {

    @Test
    fun namesDocumentsDir_whenMirrorSucceeded() {
        assertEquals(
            "ADIF backup written to Documents/packrig",
            AdifAutoBackup.backupSnackbarText(mirrored = true),
        )
    }

    @Test
    fun admitsPrivateOnly_whenMirrorFailed() {
        assertEquals(
            "ADIF backup written (app-private storage only)",
            AdifAutoBackup.backupSnackbarText(mirrored = false),
        )
    }
}
