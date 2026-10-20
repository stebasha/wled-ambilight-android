package dev.wledambilight.ui

import androidx.annotation.StringRes
import dev.wledambilight.R
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.DiagnosticSeverity
import dev.wledambilight.ui.theme.Danger
import dev.wledambilight.ui.theme.TextSecondary
import dev.wledambilight.ui.theme.Warning
import androidx.compose.ui.graphics.Color

@StringRes
fun DiagnosticCode.titleRes(): Int = when (this) {
    DiagnosticCode.DEVICE_ADDED -> R.string.diag_device_added
    DiagnosticCode.DEVICE_UPDATED -> R.string.diag_device_updated
    DiagnosticCode.DEVICE_REMOVED -> R.string.diag_device_removed
    DiagnosticCode.DEVICE_SELECTED -> R.string.diag_device_selected
    DiagnosticCode.DEVICE_NOT_FOUND -> R.string.diag_device_not_found
    DiagnosticCode.DEVICE_HTTP_UNREACHABLE -> R.string.diag_http_unreachable
    DiagnosticCode.DEVICE_HTTP_TIMEOUT -> R.string.diag_http_timeout
    DiagnosticCode.DEVICE_HTTP_ERROR -> R.string.diag_http_error
    DiagnosticCode.DEVICE_HTTP_BAD_JSON -> R.string.diag_http_bad_json
    DiagnosticCode.DEVICE_HTTP_TOO_LARGE -> R.string.diag_http_too_large
    DiagnosticCode.HTTP_PROBE_OK -> R.string.diag_http_probe_ok
    DiagnosticCode.HTTP_COLOR_TEST_SENT -> R.string.diag_http_color_sent
    DiagnosticCode.UDP_TEST_SENT -> R.string.diag_udp_test_sent
    DiagnosticCode.UDP_SEND_FAILED -> R.string.diag_udp_send_failed
    DiagnosticCode.UNSUPPORTED_PROTOCOL -> R.string.diag_unsupported_protocol
    DiagnosticCode.DISCOVERY_STARTED -> R.string.diag_discovery_started
    DiagnosticCode.DISCOVERY_FOUND -> R.string.diag_discovery_found
    DiagnosticCode.DISCOVERY_UNAVAILABLE -> R.string.diag_discovery_unavailable
    DiagnosticCode.DISCOVERY_FINISHED -> R.string.diag_discovery_finished
    DiagnosticCode.SCAN_STARTED -> R.string.diag_scan_started
    DiagnosticCode.SCAN_FINISHED -> R.string.diag_scan_finished
    DiagnosticCode.PROJECTION_DENIED -> R.string.diag_projection_denied
    DiagnosticCode.PROJECTION_START_FAILED -> R.string.diag_projection_start_failed
    DiagnosticCode.PROJECTION_STOPPED -> R.string.diag_projection_stopped
    DiagnosticCode.CAPTURE_FAILED -> R.string.diag_capture_failed
    DiagnosticCode.BLACK_FRAME_SUSPECTED -> R.string.diag_black_frame
    DiagnosticCode.NETWORK_LOST -> R.string.diag_network_lost
    DiagnosticCode.LED_COUNT_MISMATCH -> R.string.diag_led_mismatch
    DiagnosticCode.REALTIME_OCCUPIED -> R.string.diag_realtime_occupied
    DiagnosticCode.SESSION_STARTED -> R.string.diag_session_started
    DiagnosticCode.SESSION_STOPPED -> R.string.diag_session_stopped
    DiagnosticCode.PERFORMANCE_DEGRADED -> R.string.diag_performance_degraded
    DiagnosticCode.CONFIG_CORRUPT -> R.string.diag_config_corrupt
    DiagnosticCode.CONFIG_SAVED -> R.string.diag_config_saved
    DiagnosticCode.CONFIG_RESET -> R.string.diag_config_reset
    DiagnosticCode.ZONE_SKIPPED -> R.string.diag_zone_skipped
    DiagnosticCode.GOVEE_TEST_SENT -> R.string.diag_govee_test_sent
    DiagnosticCode.WLED_CONTROL_SENT -> R.string.diag_wled_control_sent
}

@StringRes
fun DiagnosticCode.hintRes(): Int? = when (this) {
    DiagnosticCode.DEVICE_HTTP_UNREACHABLE -> R.string.diag_hint_http_unreachable
    DiagnosticCode.DEVICE_HTTP_TIMEOUT -> R.string.diag_hint_http_timeout
    DiagnosticCode.DEVICE_HTTP_ERROR -> R.string.diag_hint_http_error
    DiagnosticCode.DEVICE_HTTP_BAD_JSON -> R.string.diag_hint_http_bad_json
    DiagnosticCode.DEVICE_HTTP_TOO_LARGE -> R.string.diag_hint_http_bad_json
    DiagnosticCode.UDP_SEND_FAILED -> R.string.diag_hint_udp_send_failed
    DiagnosticCode.UNSUPPORTED_PROTOCOL -> R.string.diag_hint_unsupported_protocol
    DiagnosticCode.PROJECTION_DENIED -> R.string.diag_hint_projection_denied
    DiagnosticCode.PROJECTION_STOPPED -> R.string.diag_hint_projection_stopped
    DiagnosticCode.BLACK_FRAME_SUSPECTED -> R.string.diag_hint_black_frame
    DiagnosticCode.NETWORK_LOST -> R.string.diag_hint_network_lost
    DiagnosticCode.LED_COUNT_MISMATCH -> R.string.diag_hint_led_mismatch
    DiagnosticCode.CONFIG_CORRUPT -> R.string.diag_hint_config_corrupt
    DiagnosticCode.DISCOVERY_UNAVAILABLE -> R.string.diag_hint_discovery_unavailable
    DiagnosticCode.REALTIME_OCCUPIED -> R.string.diag_hint_realtime_occupied
    DiagnosticCode.ZONE_SKIPPED -> R.string.diag_hint_zone_skipped
    else -> null
}

fun DiagnosticSeverity.color(): Color = when (this) {
    DiagnosticSeverity.INFO -> TextSecondary
    DiagnosticSeverity.WARNING -> Warning
    DiagnosticSeverity.ERROR -> Danger
}
