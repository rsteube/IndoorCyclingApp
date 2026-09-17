/*
 * Copyright (C) 2026 Snapp Technology Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.indoorbike.IndoorBikeApp
import app.indoorbike.MainActivity
import app.indoorbike.R
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the workout running while the screen is off or the user is in another
 * app. Holds:
 *   - a foreground notification with current target / elapsed
 *   - a partial wake lock so the BLE connection and timer don't get suspended
 *
 * The session itself lives in [WorkoutSessionManager] (process-singleton via
 * AppContainer). This service just keeps the process alive long enough for
 * the singleton to do its job, and surfaces user-visible status.
 */
class RideSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserver: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startRide()
            ACTION_STOP -> {
                stopRide()
                return START_NOT_STICKY
            }
            else -> {
                // System-redelivered start (process kill recovery). Only stay
                // alive if the session actually has an active workout — otherwise
                // bail so we don't keep an orphan service running.
                val container = (applicationContext as IndoorBikeApp).container
                val state = container.sessionManager.state.value
                if (state == WorkoutState.Active || state == WorkoutState.Paused ||
                    state == WorkoutState.Standby || state == WorkoutState.Countdown ||
                    // Not written down is not the same as not happening: a
                    // redelivered start during a warm-up keeps the ride alive.
                    state == WorkoutState.Untracked
                ) {
                    startRide()
                } else {
                    stopRide()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun startRide() {
        startForegroundCompat(buildNotification("Ride starting…", null))
        acquireWake()

        val container = (applicationContext as IndoorBikeApp).container
        val session = container.sessionManager
        val trainer = container.trainerManager

        stateObserver?.cancel()
        stateObserver = combine(
            session.state,
            session.elapsedSeconds,
            trainer.targetPower
        ) { state, elapsed, target -> Triple(state, elapsed, target) }
            .onEach { (state, elapsed, target) ->
                if (state == WorkoutState.Completed || state == WorkoutState.Idle) {
                    stopRide()
                    return@onEach
                }
                updateNotification(buildNotification("$target W · ${formatHHMM(elapsed)}", state.name))
            }
            .launchIn(scope)
    }

    private fun stopRide() {
        stateObserver?.cancel()
        stateObserver = null
        releaseWake()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWake()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Ride session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Active workout status"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(line: String, subline: String?): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Indoor Bike - Ride in progress")
            .setContentText(line)
            .setSmallIcon(R.drawable.ic_stat_bike)
            .setOngoing(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Show notification text on the lock screen so the rider can glance
            // at target/elapsed without unlocking.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (subline != null) builder.setSubText(subline)
        return builder.build()
    }

    private fun updateNotification(notification: Notification) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("RideService", "Notification update denied (POST_NOTIFICATIONS)", e)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            // Success — clear any previous failure flag so the banner goes
            // away on a successful retry (e.g. rider granted the permission
            // and started a new ride).
            (applicationContext as? IndoorBikeApp)?.container
                ?.sessionManager?.clearForegroundServiceFailure()
        } catch (e: SecurityException) {
            // Android 14+ requires a runtime-granted BLUETOOTH_* permission to
            // start a connectedDevice FGS. If none is granted (e.g. brand-new
            // user starts a ride before ever pairing), the ride is no longer
            // protected from background-kills — surface that to the session
            // manager so it can pause + show a banner instead of dying silently.
            val msg = "Couldn't start ride session: Bluetooth permission required. " +
                "Pause and grant the permission, or finish your ride before backgrounding the app."
            Log.w("RideService", "$msg ($e)", e)
            (applicationContext as? IndoorBikeApp)?.container
                ?.sessionManager?.notifyForegroundServiceFailure(msg)
            stopSelf()
        } catch (e: IllegalStateException) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException
            // (an IllegalStateException) when the service is started from
            // background without a qualifying reason.
            val msg = "Couldn't keep the ride running in the background: Android refused " +
                "the request. Finish your ride before backgrounding the app, or grant the " +
                "Bluetooth permission and try again."
            Log.w("RideService", "$msg ($e)", e)
            (applicationContext as? IndoorBikeApp)?.container
                ?.sessionManager?.notifyForegroundServiceFailure(msg)
            stopSelf()
        }
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "IndoorBike::RideSession"
        ).apply { setReferenceCounted(false); acquire(MAX_WAKE_DURATION_MS) }
    }

    private fun releaseWake() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun formatHHMM(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
        else "$m:${sec.toString().padStart(2, '0')}"
    }

    companion object {
        private const val CHANNEL_ID = "ride_session"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_WAKE_DURATION_MS = 4 * 60 * 60 * 1000L // 4 hours hard cap

        const val ACTION_START = "app.indoorbike.action.START_RIDE"
        const val ACTION_STOP = "app.indoorbike.action.STOP_RIDE"

        /**
         * Both helpers guard against Android 12+ refusing a service start from
         * the background. That refusal is an [IllegalStateException] thrown at
         * the *caller*, and both are called from a bare `launch` on the
         * application scope, so an uncaught one takes the process down.
         *
         * The path that matters is a restart after a kill: the service is
         * START_STICKY, so Android brings the process back in the background,
         * `Application.onCreate` collects the session StateFlow, and its current
         * value (Idle, before the snapshot has loaded) routes straight to
         * [stop] - a background service start, moments after the app died.
         * Crashing there would turn one kill into a loop of them.
         */
        fun start(context: Context) {
            val intent = Intent(context, RideSessionService::class.java).apply { action = ACTION_START }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException and friends. The
                // ride is now unprotected from background kills, which the
                // rider needs to know about, so route it to the same banner
                // the in-service failure path uses.
                val msg = "Couldn't keep the ride running in the background: Android refused " +
                    "the request. Reopen the app to continue the ride."
                Log.w("RideService", "$msg ($e)", e)
                (context.applicationContext as? IndoorBikeApp)?.container
                    ?.sessionManager?.notifyForegroundServiceFailure(msg)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RideSessionService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // Refused because we're in the background. Nothing is riding in
                // that case anyway, and stopService carries no such restriction,
                // so tear the service down directly instead of dying over it.
                Log.w("RideService", "Background start refused for stop; stopping directly ($e)", e)
                try {
                    context.stopService(intent)
                } catch (inner: Exception) {
                    Log.w("RideService", "stopService also failed", inner)
                }
            }
        }
    }
}
