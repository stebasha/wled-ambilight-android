package dev.wledambilight.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import dev.wledambilight.AmbilightApplication
import dev.wledambilight.MainActivity
import dev.wledambilight.R
import dev.wledambilight.core.data.AppContainer
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.engine.EngineState
import dev.wledambilight.engine.OutputPlanner
import dev.wledambilight.engine.SessionSource
import dev.wledambilight.engine.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that owns an active capture session.
 *
 * Android 14+ requires the service to be in the foreground *before* `getMediaProjection` is called,
 * so [handleStart] starts the foreground notification first and only then asks for the projection.
 * The service never captures without the explicit system consent dialog.
 */
class AmbilightService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private var startedSession = false
    private var sawActiveState = false
    private var statsJob: Job? = null
    private var lastNotificationUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        container = (application as AmbilightApplication).container
        createNotificationChannel()
        observeEngineState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                container.engine.stop(StopReason.USER)
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                handleStart(resultCode, data)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            container.diagnostics.report(DiagnosticCode.PROJECTION_DENIED)
            stopSelf()
            return
        }
        startedSession = true
        startForegroundCompat(getString(R.string.notification_starting))

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (error: Exception) {
            container.diagnostics.report(DiagnosticCode.PROJECTION_START_FAILED, error.javaClass.simpleName)
            null
        }
        if (projection == null) {
            container.diagnostics.report(DiagnosticCode.PROJECTION_START_FAILED)
            stopForegroundCompat()
            stopSelf()
            return
        }

        serviceScope.launch {
            val config = container.settingsRepository.currentConfig()
            val device = container.deviceRepository.selectedDevice.first()
            val devices = container.deviceRepository.devices.first()
            val plan = OutputPlanner.plan(config, device, devices)
            val started = container.engine.start(
                SessionSource.Projection(projection),
                plan,
                config.layout,
                config.settings,
            )
            if (!started) {
                stopForegroundCompat()
                stopSelf()
            }
        }
        observeStats()
    }

    private fun observeEngineState() {
        serviceScope.launch {
            container.engine.state.collect { state ->
                when (state) {
                    is EngineState.Running -> {
                        sawActiveState = true
                        updateNotification(
                            getString(R.string.notification_running, state.deviceName, state.protocolLabel),
                        )
                    }
                    is EngineState.Failed -> {
                        // A Failed state can be left over from a previous session. Only react once
                        // this service has actually started a session.
                        if (!startedSession || !sawActiveState) return@collect
                        updateNotification(getString(R.string.notification_failed))
                        stopForegroundCompat()
                        stopSelf()
                    }
                    EngineState.Idle -> if (startedSession && sawActiveState) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                    EngineState.Starting -> sawActiveState = true
                }
            }
        }
    }

    private fun observeStats() {
        if (statsJob != null) return
        statsJob = serviceScope.launch {
            container.engine.stats.collect { stats ->
                if (!startedSession || !stats.running) return@collect
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotificationUpdate < NOTIFICATION_UPDATE_INTERVAL_MS) return@collect
                lastNotificationUpdate = now
                updateNotification(
                    getString(
                        R.string.notification_stats,
                        stats.fps.toInt(),
                        stats.analysisMs.toInt(),
                    ),
                )
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AmbilightService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_led)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun updateNotification(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS denied: the foreground service still runs, but is only visible in
            // the system task manager.
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        statsJob?.cancel()
        // Preserve a Failed state so the UI can explain why the session ended. Only an actually
        // running/starting session is torn down here.
        val state = container.engine.state.value
        if (state is EngineState.Running || state is EngineState.Starting) {
            container.engine.stop(StopReason.SERVICE_DESTROYED)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "ambilight_session"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 5_000L
        const val ACTION_START = "dev.wledambilight.action.START"
        const val ACTION_STOP = "dev.wledambilight.action.STOP"
        const val EXTRA_RESULT_CODE = "dev.wledambilight.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "dev.wledambilight.extra.RESULT_DATA"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, AmbilightService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, AmbilightService::class.java).setAction(ACTION_STOP)

        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(context, startIntent(context, resultCode, data))
        }
    }
}
