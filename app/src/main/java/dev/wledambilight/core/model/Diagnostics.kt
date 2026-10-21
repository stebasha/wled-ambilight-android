package dev.wledambilight.core.model

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * Typed catalogue of everything the app can report. Messages and remediation steps are mapped to
 * localised strings in the UI; the stored event never contains screen content or secrets.
 */
enum class DiagnosticCode(val defaultSeverity: DiagnosticSeverity) {
    DEVICE_ADDED(DiagnosticSeverity.INFO),
    DEVICE_UPDATED(DiagnosticSeverity.INFO),
    DEVICE_REMOVED(DiagnosticSeverity.INFO),
    DEVICE_SELECTED(DiagnosticSeverity.INFO),
    DEVICE_NOT_FOUND(DiagnosticSeverity.ERROR),
    DEVICE_HTTP_UNREACHABLE(DiagnosticSeverity.ERROR),
    DEVICE_HTTP_TIMEOUT(DiagnosticSeverity.ERROR),
    DEVICE_HTTP_ERROR(DiagnosticSeverity.ERROR),
    DEVICE_HTTP_BAD_JSON(DiagnosticSeverity.ERROR),
    DEVICE_HTTP_TOO_LARGE(DiagnosticSeverity.ERROR),
    HTTP_PROBE_OK(DiagnosticSeverity.INFO),
    HTTP_COLOR_TEST_SENT(DiagnosticSeverity.INFO),
    UDP_TEST_SENT(DiagnosticSeverity.INFO),
    UDP_SEND_FAILED(DiagnosticSeverity.ERROR),
    UNSUPPORTED_PROTOCOL(DiagnosticSeverity.ERROR),
    DISCOVERY_STARTED(DiagnosticSeverity.INFO),
    DISCOVERY_FOUND(DiagnosticSeverity.INFO),
    DISCOVERY_UNAVAILABLE(DiagnosticSeverity.WARNING),
    DISCOVERY_FINISHED(DiagnosticSeverity.INFO),
    SCAN_STARTED(DiagnosticSeverity.INFO),
    SCAN_FINISHED(DiagnosticSeverity.INFO),
    PROJECTION_DENIED(DiagnosticSeverity.WARNING),
    PROJECTION_START_FAILED(DiagnosticSeverity.ERROR),
    PROJECTION_STOPPED(DiagnosticSeverity.WARNING),
    CAPTURE_FAILED(DiagnosticSeverity.ERROR),
    BLACK_FRAME_SUSPECTED(DiagnosticSeverity.WARNING),
    NETWORK_LOST(DiagnosticSeverity.ERROR),
    LED_COUNT_MISMATCH(DiagnosticSeverity.WARNING),
    REALTIME_OCCUPIED(DiagnosticSeverity.WARNING),
    SESSION_STARTED(DiagnosticSeverity.INFO),
    SESSION_STOPPED(DiagnosticSeverity.INFO),
    PERFORMANCE_DEGRADED(DiagnosticSeverity.WARNING),
    CONFIG_CORRUPT(DiagnosticSeverity.WARNING),
    CONFIG_SAVED(DiagnosticSeverity.INFO),
    CONFIG_RESET(DiagnosticSeverity.INFO),
    ZONE_SKIPPED(DiagnosticSeverity.WARNING),
    GOVEE_TEST_SENT(DiagnosticSeverity.INFO),
    WLED_CONTROL_SENT(DiagnosticSeverity.INFO),
    HA_OK(DiagnosticSeverity.INFO),
    HA_UNAUTHORIZED(DiagnosticSeverity.ERROR),
}

data class DiagnosticEvent(
    val id: Long,
    val timestampMillis: Long,
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity = code.defaultSeverity,
    /** Short technical detail (host, exception class, byte counts). Never screen content. */
    val detail: String? = null,
)

data class SessionStats(
    val running: Boolean = false,
    val fps: Float = 0f,
    val analysisMs: Float = 0f,
    val sendMs: Float = 0f,
    val packetsPerSecond: Float = 0f,
    val droppedFrames: Long = 0,
    val blackFrameSeconds: Float = 0f,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val ledCount: Int = 0,
    val protocolLabel: String = "",
    val startedEpochMillis: Long? = null,
    /** Number of outputs (WLED controllers + Govee lamps) in the session. */
    val outputs: Int = 0,
    /** Outputs currently in send-failure backoff. */
    val outputsDown: Int = 0,
    val demo: Boolean = false,
)
