package net.packrig.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.packrig.app.settings.SettingsRepository
import net.packrig.app.ui.log.filterByCall
import net.packrig.core.ActivationProfile
import net.packrig.core.AppInfo
import net.packrig.data.Activation
import net.packrig.data.Activations
import net.packrig.data.Logbook
import net.packrig.data.RoomLogbook
import net.packrig.data.adif.AdifExportContext
import net.packrig.data.adif.AdifWriter
import net.packrig.data.db.PackRigDatabase
import net.packrig.data.model.QsoContact

class LogViewModel(app: Application) : AndroidViewModel(app) {

    private val logbook: Logbook = RoomLogbook(PackRigDatabase.get(app))
    private val settingsRepo = SettingsRepository(app)

    private val _contacts = MutableStateFlow<List<QsoContact>>(emptyList())
    val contacts: StateFlow<List<QsoContact>> = _contacts.asStateFlow()

    private val _activations = MutableStateFlow<List<Activation>>(emptyList())
    val activations: StateFlow<List<Activation>> = _activations.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Contacts narrowed by the call sign search. Display-only: export/clear use [contacts]. */
    val filteredContacts: StateFlow<List<QsoContact>> =
        combine(_contacts, _searchQuery) { list, query -> filterByCall(list, query) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    init {
        viewModelScope.launch {
            logbook.contacts().collect { list ->
                _contacts.value = list
                _activations.value = Activations.groupActivations(list)
            }
        }
    }

    suspend fun exportAdif(): String =
        logbook.exportAdif(
            AdifExportContext(
                programId = AppInfo.APP_NAME,
                programVersion = AppInfo.VERSION_NAME,
            ),
        )

    /** Build one activation's upload file. Returns (fileName, adif content). */
    suspend fun exportActivation(activation: Activation): Pair<String, String> {
        val myCall = settingsRepo.settings.first().myCall
        val group = Activations.contactsFor(_contacts.value, activation.parkRef, activation.utcDate)
        val adif = AdifWriter.export(
            group,
            AdifExportContext(
                programId = AppInfo.APP_NAME,
                programVersion = AppInfo.VERSION_NAME,
                activationParkRef = activation.parkRef,
            ),
        )
        return Activations.fileName(myCall, activation.parkRef, activation.utcDate) to adif
    }

    /**
     * Replace park refs on [ids]. Blank input clears parks (home QSOs).
     * Invokes [onDone] with false and writes nothing when the input is invalid.
     */
    fun setParksOnContacts(ids: List<Long>, rawInput: String, onDone: (Boolean) -> Unit = {}) {
        val trimmed = rawInput.trim()
        val parks: String? = if (trimmed.isEmpty()) {
            null
        } else {
            if (!ActivationProfile.isValidParkRefList(trimmed)) {
                onDone(false)
                return
            }
            ActivationProfile.formatParkRefs(ActivationProfile.parseParkRefs(trimmed))
        }
        viewModelScope.launch {
            logbook.setParkRefs(ids, parks)
            AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
            onDone(true)
        }
    }

    /** Delete the QSOs with [ids], then refresh the ADIF auto-backup to match. */
    fun deleteContacts(ids: List<Long>) {
        viewModelScope.launch {
            logbook.delete(ids)
            AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
        }
    }

    fun clearAll() {
        viewModelScope.launch { logbook.clearAll() }
    }
}
