package net.ft8vc.data

/**
 * Local QSO logbook. Phase 5 backs this with Room/SQLite and adds ADIF
 * import/export. Defined here so the rest of the app can depend on the
 * abstraction while the storage layer is still being built.
 */
interface Logbook {

    companion object {
        const val DESCRIPTION = "QSO log + ADIF export (Phase 5)"
    }
}
