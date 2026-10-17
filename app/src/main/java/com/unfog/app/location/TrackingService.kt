package com.unfog.app.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.unfog.app.data.TripDatabase
import com.unfog.app.ui.MainActivity

/** Records locations only during a user-started ride; runs as a location foreground service. */
class TrackingService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "com.unfog.app.START"
        const val ACTION_PAUSE = "com.unfog.app.PAUSE"
        const val ACTION_RESUME = "com.unfog.app.RESUME"
        const val ACTION_FINISH = "com.unfog.app.FINISH"
        const val ACTION_UPDATE = "com.unfog.app.TRACKING_UPDATED"
        const val EXTRA_ERROR = "error"
        const val EXTRA_SPEED = "speed_mps"
        const val EXTRA_FIX_TIME = "fix_time_ms"
        @Volatile var isRunning: Boolean = false
            private set
        private const val CHANNEL_ID = "unfog_tracking"
        private const val NOTIFICATION_ID = 52
    }

    private lateinit var db: TripDatabase
    private lateinit var locationManager: LocationManager
    private var currentTripId: Long? = null
    private var lastValidFix: Location? = null
    private var lastGpsElapsedMs = 0L
    private var smoothedSpeedMps = 0f

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        db = TripDatabase(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val channel = NotificationChannel(CHANNEL_ID, "GPS-поездка", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Запись маршрута во время активной поездки"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!hasFineLocation()) { broadcast("Разреши точное местоположение"); stopSelf(); return START_NOT_STICKY }
                val existing = db.unfinishedTrip()
                currentTripId = when (existing?.status) {
                    "active" -> existing.id
                    "paused" -> { db.resume(existing.id); existing.id }
                    else -> db.startTrip().id
                }
                showNotification(false)
                startGps()
                broadcast(speed = 0f, stateChanged = true)
            }
            ACTION_RESUME -> {
                db.unfinishedTrip()?.let {
                    currentTripId = it.id
                    db.resume(it.id)
                    showNotification(false)
                    startGps()
                    broadcast(speed = 0f, stateChanged = true)
                }
            }
            ACTION_PAUSE -> {
                stopGps()
                (currentTripId ?: db.unfinishedTrip()?.id)?.let(db::setPaused)
                showNotification(true)
                broadcast(speed = 0f, stateChanged = true)
            }
            ACTION_FINISH -> {
                stopGps()
                (currentTripId ?: db.unfinishedTrip()?.id)?.let(db::finish)
                currentTripId = null
                broadcast(speed = 0f, stateChanged = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun hasFineLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startGps() {
        if (!hasFineLocation()) return
        // Do not reuse a fix from before resuming: it would calculate a false speed.
        lastValidFix = null
        lastGpsElapsedMs = 0L
        smoothedSpeedMps = 0f
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            }
            // Network is a fallback only: never let a coarse network fix override recent GPS.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
            }
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                broadcast("Включи геолокацию устройства")
            }
        } catch (e: SecurityException) { broadcast("GPS недоступен: ${e.message}") }
          catch (e: IllegalArgumentException) { broadcast("Включи геолокацию устройства") }
    }

    private fun stopGps() {
        locationManager.removeUpdates(this)
        lastValidFix = null
        smoothedSpeedMps = 0f
    }

    override fun onLocationChanged(location: Location) {
        val id = currentTripId ?: return
        if (!location.hasAccuracy() || location.accuracy <= 0f || location.accuracy > 35f) return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs !in 0..5000L) return
        if (location.provider == LocationManager.NETWORK_PROVIDER &&
            lastGpsElapsedMs > 0L && SystemClock.elapsedRealtime() - lastGpsElapsedMs < 6000L) return
        if (location.provider == LocationManager.GPS_PROVIDER) lastGpsElapsedMs = SystemClock.elapsedRealtime()

        val previous = lastValidFix
        if (previous != null && location.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) return
        val elapsedS = if (previous == null) 0.0
            else (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
        val distanceM = if (previous != null) previous.distanceTo(location).toDouble() else 0.0
        val derived = if (elapsedS in 0.6..12.0 && distanceM / elapsedS <= 25.0 &&
            distanceM >= maxOf(2.0, minOf(5.0, (previous!!.accuracy + location.accuracy) * 0.16))) {
            (distanceM / elapsedS).toFloat()
        } else 0f

        val deviceSpeed = if (location.hasSpeed() && location.speed in 0f..25f) location.speed else null
        val selected = when {
            deviceSpeed == null -> derived
            deviceSpeed < 0.6f && derived > 0.9f -> derived
            else -> deviceSpeed
        }
        val target = selected.coerceIn(0f, 25f)
        smoothedSpeedMps = if (previous == null || elapsedS > 12.0) target
            else (0.50f * target + 0.50f * smoothedSpeedMps)
        lastValidFix = Location(location)

        // Persistence is separate from live speed; a rejected point must not freeze the speedometer.
        val changed = db.recordLocation(id, location)
        broadcast(speed = smoothedSpeedMps, fixTime = System.currentTimeMillis(), pointChanged = changed)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) broadcast("GPS выключен, маршрут может быть неточным")
    }

    private fun showNotification(paused: Boolean) {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (paused) "Unfog · пауза" else "Unfog · поездка записывается")
            .setContentText(if (paused) "Исследование приостановлено" else "GPS записывает маршрут и открывает карту")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun broadcast(error: String? = null, speed: Float? = null,
                          fixTime: Long? = null, pointChanged: Boolean = false, stateChanged: Boolean = false) {
        val intent = Intent(ACTION_UPDATE).setPackage(packageName)
        error?.let { intent.putExtra(EXTRA_ERROR, it) }
        speed?.let { intent.putExtra(EXTRA_SPEED, it) }
        fixTime?.let { intent.putExtra(EXTRA_FIX_TIME, it) }
        intent.putExtra("point_changed", pointChanged)
        intent.putExtra("state_changed", stateChanged)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopGps()
        currentTripId?.let { db.setPaused(it) }
        db.close()
        isRunning = false
        super.onDestroy()
    }
}
