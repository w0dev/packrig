package net.ft8vc.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.core.AppInfo
import net.ft8vc.data.Logbook
import net.ft8vc.data.RoomLogbook
import net.ft8vc.data.adif.AdifExportContext
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

    suspend fun exportAdif(): String =
        logbook.exportAdif(
            AdifExportContext(
                programId = AppInfo.APP_NAME,
                programVersion = AppInfo.VERSION_NAME,
            ),
        )

    fun clearAll() {
        viewModelScope.launch { logbook.clearAll() }
    }
}
