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
import net.ft8vc.data.Logbook
import net.ft8vc.data.RoomLogbook
import net.ft8vc.data.adif.AdifExportContext
import net.ft8vc.data.adif.AdifExportException
import net.ft8vc.data.db.Ft8vcDatabase
import net.ft8vc.data.model.QsoContact

class LogViewModel(app: Application) : AndroidViewModel(app) {

    private val logbook: Logbook = RoomLogbook(Ft8vcDatabase.get(app))
    private val settingsRepo = SettingsRepository(app)

    private val _contacts = MutableStateFlow<List<QsoContact>>(emptyList())
    val contacts: StateFlow<List<QsoContact>> = _contacts.asStateFlow()

    init {
        viewModelScope.launch {
            logbook.contacts().collect { _contacts.value = it }
        }
    }

    suspend fun exportAdif(): String {
        val settings = settingsRepo.settings.first()
        val parkRef = if (settings.potaModeEnabled) {
            ActivationProfile.normalizeParkRef(settings.potaParkRef)
        } else {
            null
        }
        if (settings.potaModeEnabled && parkRef == null) {
            throw AdifExportException("POTA mode is on but park reference is missing or invalid")
        }
        return logbook.exportAdif(
            AdifExportContext(
                programId = AppInfo.APP_NAME,
                programVersion = AppInfo.VERSION_NAME,
                potaEnabled = settings.potaModeEnabled,
                potaParkRef = parkRef,
            ),
        )
    }

    fun clearAll() {
        viewModelScope.launch { logbook.clearAll() }
    }
}
