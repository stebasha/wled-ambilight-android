package dev.wledambilight.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.WledDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private val Context.ambilightDataStore: DataStore<Preferences> by preferencesDataStore(name = "ambilight")

class SettingsRepository(
    context: Context,
    private val codec: SettingsCodec,
    private val diagnostics: DiagnosticsRepository,
) {
    private val store = context.applicationContext.ambilightDataStore
    private val configCorruptionReported = AtomicBoolean(false)
    private val devicesCorruptionReported = AtomicBoolean(false)

    private val preferences: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val config: Flow<AmbilightConfig> = preferences
        .map { prefs ->
            val decoded = codec.decodeConfig(prefs[Keys.CONFIG])
            if (decoded.corrupted && configCorruptionReported.compareAndSet(false, true)) {
                diagnostics.report(DiagnosticCode.CONFIG_CORRUPT, "config JSON")
            }
            decoded.value
        }
        .distinctUntilChanged()

    val devices: Flow<List<WledDevice>> = preferences
        .map { prefs ->
            val decoded = codec.decodeDevices(prefs[Keys.DEVICES])
            if (decoded.corrupted && devicesCorruptionReported.compareAndSet(false, true)) {
                diagnostics.report(DiagnosticCode.CONFIG_CORRUPT, "devices JSON")
            }
            decoded.value
        }
        .distinctUntilChanged()

    val selectedDeviceId: Flow<String?> = preferences
        .map { it[Keys.SELECTED_DEVICE] }
        .distinctUntilChanged()

    val onboardingComplete: Flow<Boolean> = preferences
        .map { it[Keys.ONBOARDING_COMPLETE] ?: false }
        .distinctUntilChanged()

    suspend fun currentConfig(): AmbilightConfig = config.first()

    suspend fun updateConfig(transform: (AmbilightConfig) -> AmbilightConfig) {
        store.edit { prefs ->
            val current = codec.decodeConfig(prefs[Keys.CONFIG]).value
            prefs[Keys.CONFIG] = codec.encodeConfig(transform(current).sanitized())
        }
    }

    suspend fun updateSettings(transform: (AmbilightSettings) -> AmbilightSettings) {
        updateConfig { it.copy(settings = transform(it.settings)) }
    }

    suspend fun updateLayout(transform: (LedLayout) -> LedLayout) {
        updateConfig { it.copy(layout = transform(it.layout)) }
    }

    suspend fun saveDevice(device: WledDevice) {
        val normalized = device.normalized()
        store.edit { prefs ->
            val current = codec.decodeDevices(prefs[Keys.DEVICES]).value
            val index = current.indexOfFirst { it.id == normalized.id }
            val next = if (index >= 0) {
                current.toMutableList().also { it[index] = normalized }
            } else {
                current + normalized
            }
            prefs[Keys.DEVICES] = codec.encodeDevices(next)
            if (prefs[Keys.SELECTED_DEVICE] == null && normalized.isWled) {
                prefs[Keys.SELECTED_DEVICE] = normalized.id
            }
        }
    }

    suspend fun deleteDevice(id: String) {
        store.edit { prefs ->
            val current = codec.decodeDevices(prefs[Keys.DEVICES]).value
            val next = current.filterNot { it.id == id }
            prefs[Keys.DEVICES] = codec.encodeDevices(next)
            // Zones that pointed at the removed device are kept (so the user sees them) but disabled.
            val config = codec.decodeConfig(prefs[Keys.CONFIG]).value
            if (config.zones.any { it.deviceId == id }) {
                prefs[Keys.CONFIG] = codec.encodeConfig(
                    config.copy(
                        zones = config.zones.map { if (it.deviceId == id) it.copy(enabled = false, deviceId = "") else it },
                    ),
                )
            }
            if (prefs[Keys.SELECTED_DEVICE] == id) {
                val fallback = next.firstOrNull { it.isWled }?.id
                if (fallback == null) prefs.remove(Keys.SELECTED_DEVICE) else prefs[Keys.SELECTED_DEVICE] = fallback
            }
        }
    }

    suspend fun selectDevice(id: String) {
        store.edit { prefs ->
            val current = codec.decodeDevices(prefs[Keys.DEVICES]).value
            if (current.any { it.id == id && it.isWled }) prefs[Keys.SELECTED_DEVICE] = id
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        store.edit { prefs -> prefs[Keys.ONBOARDING_COMPLETE] = complete }
    }

    suspend fun resetAll() {
        store.edit { prefs -> prefs.clear() }
        diagnostics.report(DiagnosticCode.CONFIG_RESET)
    }

    private object Keys {
        val CONFIG = stringPreferencesKey("ambilight_config_v1")
        val DEVICES = stringPreferencesKey("devices_v1")
        val SELECTED_DEVICE = stringPreferencesKey("selected_device_id")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
