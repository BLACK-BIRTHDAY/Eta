package fuck.andes.data.repository

import fuck.andes.data.datastore.SettingsDataStore
import fuck.andes.data.model.AppearanceSettings
import kotlinx.coroutines.flow.Flow

object AppearanceSettingsRepository {
    fun settingsFlow(): Flow<AppearanceSettings> = SettingsDataStore.appearanceSettingsFlow()

    suspend fun settings(): AppearanceSettings = SettingsDataStore.settings().appearance

    suspend fun update(settings: AppearanceSettings) {
        SettingsDataStore.setAppearanceSettings(settings)
    }

    suspend fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        SettingsDataStore.updateAppearanceSettings(transform)
    }
}
