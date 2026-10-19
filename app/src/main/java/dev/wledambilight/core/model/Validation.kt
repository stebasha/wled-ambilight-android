package dev.wledambilight.core.model

/** Validation errors are codes; user-facing text lives in `ui/DiagnosticText.kt` / strings. */
enum class HostError {
    EMPTY,
    CONTAINS_SCHEME,
    CONTAINS_PATH,
    CONTAINS_SPACES,
    INVALID_IPV4,
    INVALID_HOSTNAME,
    TOO_LONG,
}

enum class PortError {
    OUT_OF_RANGE,
}

object HostValidator {
    private val ipv4Regex = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val hostnameRegex = Regex("""^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$""")

    fun error(raw: String): HostError? {
        val value = raw.trim()
        if (value.isEmpty()) return HostError.EMPTY
        if (value.length > 253) return HostError.TOO_LONG
        if (value.contains("://")) return HostError.CONTAINS_SCHEME
        if (value.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '\\' }) {
            return HostError.CONTAINS_PATH
        }
        if (value.any { it.isWhitespace() }) return HostError.CONTAINS_SPACES
        if (value.contains(':')) return HostError.INVALID_HOSTNAME // IPv6 is not supported in the MVP UI.
        if (ipv4Regex.matches(value)) {
            val octets = value.split('.').mapNotNull { it.toIntOrNull() }
            return if (octets.size == 4 && octets.all { it in 0..255 }) null else HostError.INVALID_IPV4
        }
        return if (hostnameRegex.matches(value)) null else HostError.INVALID_HOSTNAME
    }

    fun isValid(raw: String): Boolean = error(raw) == null

    fun portError(port: Int): PortError? = if (port in 1..65535) null else PortError.OUT_OF_RANGE

    fun ledCountError(count: Int?): Boolean = count != null && count !in 1..WledDevice.MAX_LEDS
}
