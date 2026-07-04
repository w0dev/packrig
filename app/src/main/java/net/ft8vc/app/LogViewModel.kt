package net.ft8vc.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.core.ActivationProfile
import net.ft8vc.core.AppInfo
import net.ft8vc.data.Activation
import net.ft8vc.data.Activations
import net.ft8vc.data.Logbook
import net.ft8vc.data.RoomLogbook
import net.ft8vc.data.adif.AdifExportContext
import net.ft8vc.data.adif.AdifWriter
import net.ft8vc.data.db.Ft8vcDatabase
import net.ft8vc.data.model.QsoContact

class LogViewModel(app: Application) : AndroidViewModel(app) {

    private val logbook: Logbook = RoomLogbook(Ft8vcDatabase.get(app))
    private val settingsRepo = SettingsRepository(app)

    private val _contacts = MutableStateFlow<List<QsoContact>>(emptyList())
    val contacts: StateFlow<List<QsoContact>> = _contacts.asStateFlow()

    private val _activations = MutableStateFlow<List<Activation>>(emptyList())
    val activations: StateFlow<List<Activation>> = _activations.asStateFlow()

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
