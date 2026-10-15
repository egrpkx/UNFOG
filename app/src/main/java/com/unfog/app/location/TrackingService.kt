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
import android.widget.Toast
import com.unfog.app.data.TripDatabase
import com.unfog.app.ui.MainActivity

/** User-initiated foreground-only location service. No passive location tracking. */
class TrackingService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "com.unfog.app.START"
        const val ACTION_PAUSE = "com.unfog.app.PAUSE"
        const val ACTION_RESUME = "com.unfog.app.RESUME"
        const val ACTION_FINISH = "com.unfog.app.FINISH"
        const val ACTION_UPDATE = "com.unfog.app.TRACKING_UPDATED"
        const val EXTRA_ERROR = "error"
        const val EXTRA_SPEED = "speed_mps"
        @Volatile var isRunning: Boolean = false
            private set
        private const val CHANNEL_ID = "unfog_tracking"
        private const val NOTIFICATION_ID = 52
    }

    private lateinit var db: TripDatabase
    private lateinit var locationManager: LocationManager
    private var currentTripId: Long? = null
    private var lastAccuracy: Float = Float.MAX_VALUE

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
                broadcast()
            }
            ACTION_RESUME -> {
                db.unfinishedTrip()?.let {
                    currentTripId = it.id
                    db.resume(it.id)
                    showNotification(false)
                    startGps()
                    broadcast()
                }
            }
            ACTION_PAUSE -> {
                stopGps()
                (currentTripId ?: db.unfinishedTrip()?.id)?.let(db::setPaused)
                showNotification(true)
                broadcast()
            }
            ACTION_FINISH -> {
                stopGps()
                (currentTripId ?: db.unfinishedTrip()?.id)?.let(db::finish)
                currentTripId = null
                broadcast()
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
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2200L, 2f, this, Looper.getMainLooper())
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3500L, 4f, this, Looper.getMainLooper())
            }
        } catch (e: SecurityException) { broadcast("GPS недоступен: ${e.message}") }
          catch (e: IllegalArgumentException) { broadcast("Включи геолокацию устройства") }
    }

    private fun stopGps() { locationManager.removeUpdates(this) }

    override fun onLocationChanged(location: Location) {
        val id = currentTripId ?: return
        val changed = db.recordLocation(id, location)
        if (location.hasAccuracy()) lastAccuracy = location.accuracy
        if (changed || location.hasSpeed()) {
            broadcast(speed = if (location.hasAccuracy() && location.accuracy <= 35f && location.hasSpeed()) location.speed else 0f)
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) broadcast("GPS выключен, маршрут может быть неточным")
    }

    private fun showNotification(paused: Boolean) {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, activityIntent,
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

    private fun broadcast(error: String? = null, speed: Float? = null) {
        val i = Intent(ACTION_UPDATE).setPackage(packageName)
        error?.let { i.putExtra(EXTRA_ERROR, it) }
        speed?.let { i.putExtra(EXTRA_SPEED, it) }
        sendBroadcast(i)
    }

    override fun onDestroy() {
        stopGps()
        // If the OS stops the service, preserve the recorded route rather than deleting it.
        currentTripId?.let { db.setPaused(it) }
        db.close()
        isRunning = false
        super.onDestroy()
    }
}
