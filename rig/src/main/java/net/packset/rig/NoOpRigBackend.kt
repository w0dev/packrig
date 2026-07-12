package net.packset.rig

import android.util.Log

/** Logs PTT actions; used when no serial rig is connected (e.g. emulator). */
class NoOpRigBackend : RigBackend {

    override fun keyPtt() {
        Log.i(TAG, "PTT keyed (no-op)")
    }

    override fun releasePtt() {
        Log.i(TAG, "PTT released (no-op)")
    }

    companion object {
        private const val TAG = "NoOpRigBackend"
    }
}
