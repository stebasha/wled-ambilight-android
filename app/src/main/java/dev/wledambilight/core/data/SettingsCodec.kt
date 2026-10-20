package dev.wledambilight.core.data

import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.LightZone
import dev.wledambilight.core.model.WledDevice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class ConfigEnvelope(
    val version: Int,
    val config: AmbilightConfig,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class DevicesEnvelope(
    val version: Int,
    val devices: List<WledDevice>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class DecodeResult<T>(val value: T, val corrupted: Boolean)

/**
 * Pure JSON codec for persisted state. Kept separate from DataStore so migrations and corruption
 * handling are unit-testable without Android instrumentation.
 */
class SettingsCodec(private val json: Json) {

    fun encodeConfig(config: AmbilightConfig): String =
        json.encodeToString(
            ConfigEnvelope.serializer(),
            ConfigEnvelope(version = ConfigEnvelope.CURRENT_VERSION, config = config),
        )

    fun decodeConfig(raw: String?): DecodeResult<AmbilightConfig> {
        if (raw.isNullOrBlank()) return DecodeResult(AmbilightConfig(), false)
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val objectValue = element as? JsonObject
            ?: return DecodeResult(AmbilightConfig(), true)
        // Current format is an envelope with "version" + "config". Legacy format has "settings"
        // at the root. Refusing to silently downgrade a broken envelope is important: otherwise a
        // typo in one enum value would wipe every setting instead of being reported.
        val isEnvelope = objectValue.containsKey("version") || objectValue.containsKey("config")
        if (isEnvelope) {
            return try {
                val envelope = json.decodeFromString(ConfigEnvelope.serializer(), raw)
                DecodeResult(migrateConfig(envelope), false)
            } catch (error: Exception) {
                DecodeResult(AmbilightConfig(), true)
            }
        }
        return try {
            val legacy = json.decodeFromString(AmbilightConfig.serializer(), raw)
            DecodeResult(legacy.sanitized(), false)
        } catch (error: Exception) {
            DecodeResult(AmbilightConfig(), true)
        }
    }

    fun encodeDevices(devices: List<WledDevice>): String =
        json.encodeToString(
            DevicesEnvelope.serializer(),
            DevicesEnvelope(version = DevicesEnvelope.CURRENT_VERSION, devices = devices),
        )

    fun decodeDevices(raw: String?): DecodeResult<List<WledDevice>> {
        if (raw.isNullOrBlank()) return DecodeResult(emptyList(), false)
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return DecodeResult(emptyList(), true)
        if (element is JsonArray) {
            return try {
                val legacy = json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(WledDevice.serializer()),
                    raw,
                )
                DecodeResult(legacy.map { it.normalized() }, false)
            } catch (error: Exception) {
                DecodeResult(emptyList(), true)
            }
        }
        if (element !is JsonObject) return DecodeResult(emptyList(), true)
        return try {
            val envelope = json.decodeFromString(DevicesEnvelope.serializer(), raw)
            DecodeResult(envelope.devices.map { it.normalized() }, false)
        } catch (error: Exception) {
            DecodeResult(emptyList(), true)
        }
    }

    private fun migrateConfig(envelope: ConfigEnvelope): AmbilightConfig {
        // Version 1 is the first persisted schema; unknown future versions fall back to defaults
        // field-by-field because all fields have defaults.
        return envelope.config.sanitized()
    }
}

fun AmbilightConfig.sanitized(): AmbilightConfig = copy(
    settings = settings.sanitized(),
    zones = zones.take(LightZone.MAX_ZONES).map { it.sanitized() }.distinctBy { it.id },
)
