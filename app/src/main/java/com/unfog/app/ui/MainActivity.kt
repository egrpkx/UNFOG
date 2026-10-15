package com.unfog.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.unfog.app.R
import com.unfog.app.data.TripDatabase
import com.unfog.app.location.TrackingService
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var mapView: MapView
    private lateinit var fog: FogOverlay
    private lateinit var db: TripDatabase
    private var map: MapLibreMap? = null
    private var pendingStart = false
    private var lastLiveSpeedMps = 0f
    private var lastLocatedAt = 0L
    private var previewing = false

    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var distanceText: TextView
    private lateinit var averageText: TextView
    private lateinit var timeText: TextView
    private lateinit var primaryButton: TextView
    private lateinit var finishButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { updateNumbers(); handler.postDelayed(this, 1000L) }
    }

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(TrackingService.EXTRA_ERROR)?.let { toast(it) }
            if (intent?.hasExtra("speed_mps") == true) {
                lastLiveSpeedMps = intent.getFloatExtra(TrackingService.EXTRA_SPEED, 0f).coerceIn(0f, 25f)
            }
            reloadAll()
            val trip = db.unfinishedTrip()
            if (trip?.status == "active") panToLastPoint(trip.id, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        db = TripDatabase(this)
        val orphan = db.unfinishedTrip()
        if (orphan?.status == "active" && !TrackingService.isRunning) {
            // After a process kill, never pretend the old foreground service is still recording.
            val lastFix = db.points(orphan.id).lastOrNull()?.time ?: orphan.legStartedAt ?: orphan.startedAt
            val safeEnd = minOf(System.currentTimeMillis(), maxOf(orphan.legStartedAt ?: lastFix, lastFix) + 3000L)
            db.setPaused(orphan.id, safeEnd)
        }
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.rgb(23, 35, 43)
        window.navigationBarColor = Color.rgb(24, 36, 45)

        mapView = findViewById(R.id.mapView)
        fog = findViewById(R.id.fogOverlay)
        statusText = findViewById(R.id.statusText)
        speedText = findViewById(R.id.speedText)
        distanceText = findViewById(R.id.distanceText)
        averageText = findViewById(R.id.averageText)
        timeText = findViewById(R.id.timeText)
        primaryButton = findViewById(R.id.primaryButton)
        finishButton = findViewById(R.id.finishButton)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.setStyle("https://tiles.openfreemap.org/styles/positron")
            m.cameraPosition = CameraPosition.Builder().target(LatLng(50.0, 18.0)).zoom(4.0).build()
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.setLogoMargins(px(14), 0, 0, px(272))
            m.uiSettings.setAttributionMargins(0, 0, px(14), px(272))
            m.addOnCameraMoveListener { fog.invalidate() }
            fog.connect(m)
            db.unfinishedTrip()?.let { panToLastPoint(it.id, true) }
            if (hasFineLocation()) locateUser()
        }

        findViewById<View>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<View>(R.id.locateButton).setOnClickListener {
            if (hasFineLocation()) locateUser() else askLocationPermission(false)
        }
        findViewById<View>(R.id.attribution).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")))
        }
        primaryButton.setOnClickListener {
            previewing = false
            fog.showPreview(null)
            when (db.unfinishedTrip()?.status) {
                "active" -> sendAction(TrackingService.ACTION_PAUSE)
                "paused" -> sendAction(TrackingService.ACTION_RESUME)
                else -> if (hasFineLocation()) sendAction(TrackingService.ACTION_START)
                        else askLocationPermission(true)
            }
        }
        finishButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Завершить поездку?")
                .setMessage("Маршрут и исследованные участки останутся сохранены на этом устройстве.")
                .setNegativeButton("Продолжить", null)
                .setPositiveButton("Завершить") { _, _ ->
                    sendAction(TrackingService.ACTION_FINISH)
                    lastLiveSpeedMps = 0f
                }.show()
        }
        reloadAll()
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        try {
            if (action == TrackingService.ACTION_START || action == TrackingService.ACTION_RESUME) {
                startForegroundService(intent)
            } else startService(intent)
        } catch (e: Exception) {
            toast("Не удалось запустить GPS: ${e.localizedMessage ?: "проверь настройки"}")
        }
    }

    private fun hasFineLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askLocationPermission(startAfter: Boolean) {
        pendingStart = startAfter
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 41) return
        if (hasFineLocation()) {
            locateUser()
            if (pendingStart) sendAction(TrackingService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("Нужно точное местоположение")
                .setMessage("Без точного GPS приложение не сможет надёжно записывать маршрут и открывать территорию.")
                .setPositiveButton("Понятно", null).show()
        }
        pendingStart = false
    }

    private fun locateUser() {
        if (!hasFineLocation()) return
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locations = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null }
        }
        val recent = locations.filter { System.currentTimeMillis() - it.time < 5 * 60_000L }
            .maxByOrNull { it.time }
        if (recent != null) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(recent.latitude, recent.longitude), 15.0))
        } else {
            val last = db.unfinishedTrip()?.let { db.points(it.id).lastOrNull() }
            if (last != null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lon), 15.0))
            else toast("Точка GPS пока не получена. Начни поездку на улице.")
        }
    }

    private fun panToLastPoint(tripId: Long, force: Boolean) {
        val last = db.points(tripId).lastOrNull() ?: return
        if (force || System.currentTimeMillis() - lastLocatedAt > 4500L) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lon), 16.0))
            lastLocatedAt = System.currentTimeMillis()
        }
    }

    private fun reloadAll() {
        val trip = db.unfinishedTrip()
        fog.updatePoints(db.points(), trip?.id, trip?.status == "paused")
        updateNumbers()
    }

    private fun updateNumbers() {
        val trip = db.unfinishedTrip()
        if (trip?.status != "active") lastLiveSpeedMps = 0f
        statusText.text = when (trip?.status) {
            "active" -> "●  ИДЁТ ЗАПИСЬ ПОЕЗДКИ"
            "paused" -> "Ⅱ  ПАУЗА · ТУМАН НЕ ОТКРЫВАЕТСЯ"
            else -> if (previewing) "ПРОСМОТР МАРШРУТА" else "ГОТОВ К ИССЛЕДОВАНИЮ"
        }
        val speedKmh = if (trip?.status == "active") lastLiveSpeedMps * 3.6 else 0.0
        speedText.text = String.format(Locale.US, "%.1f", speedKmh)
        distanceText.text = String.format(Locale.US, "%.2f км", (trip?.distanceM ?: 0.0) / 1000.0)
        val duration = trip?.elapsed() ?: 0L
        val average = if (duration > 0L) (trip!!.distanceM / duration.toDouble()) * 3600.0 else 0.0
        averageText.text = String.format(Locale.US, "%.1f км/ч", average)
        val seconds = duration / 1000L
        timeText.text = if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
            else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        primaryButton.text = when (trip?.status) {
            "active" -> "Ⅱ   Пауза"
            "paused" -> "▶   Продолжить"
            else -> "●   Начать поездку"
        }
        finishButton.visibility = if (trip != null) View.VISIBLE else View.GONE
    }

    private fun showHistory() {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(25), px(24), px(24))
            background = rounded(Color.rgb(24, 36, 45), 28f)
        }
        val close = label("✕", 22f, Color.WHITE, false).apply {
            gravity = Gravity.END
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close, rowParams(38))
        content.addView(label("Твои поездки", 28f, Color.WHITE, true))
        content.addView(label("Каждый маршрут открывает новую часть города", 13f, 0xFFACBEC6.toInt(), false).apply {
            setPadding(0, px(7), 0, px(20))
        })
        val trips = db.trips()
        val totalKm = trips.sumOf { it.distanceM } / 1000.0
        content.addView(label(String.format(Locale.US, "%d поездок    ·    %.1f км всего", trips.count { it.status == "finished" }, totalKm),
            16f, 0xFFB8F7CF.toInt(), true).apply { setPadding(0, 0, 0, px(18)) })
        if (trips.isEmpty()) content.addView(label("Пока нет поездок. Нажми «Начать поездку» на карте.", 15f, Color.WHITE, false))
        val formatter = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale("ru"))
        for (trip in trips) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(15), px(16), px(15))
                background = rounded(0xFF2A3A44.toInt(), 17f)
                isClickable = true
                isFocusable = true
            }
            card.addView(label(formatter.format(Date(trip.startedAt)), 13f, 0xFFC0CDD0.toInt(), false))
            val avg = if (trip.activeDurationMs > 0) trip.distanceM / trip.activeDurationMs * 3600.0 else 0.0
            card.addView(label(String.format(Locale.US, "%.2f км   ·   %.1f км/ч", trip.distanceM / 1000, avg), 19f, Color.WHITE, true).apply {
                setPadding(0, px(8), 0, px(4))
            })
            card.addView(label(String.format(Locale.US, "Макс. %.1f км/ч   ·   %d мин в пути",
                trip.maxSpeedMps * 3.6, trip.elapsed() / 60_000L),
                12f, 0xFFA9B9C2.toInt(), false).apply { setPadding(0, 0, 0, px(7)) })
            card.addView(label(if (trip.status == "finished") "Просмотреть маршрут →" else "Незавершённая поездка →",
                12f, 0xFFB8F7CF.toInt(), false))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = px(11)
            content.addView(card, lp)
            card.setOnClickListener {
                val points = db.points(trip.id)
                if (points.isEmpty()) { toast("Для этой поездки ещё нет GPS-точек"); return@setOnClickListener }
                previewing = true
                fog.showPreview(points)
                val first = points.first()
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.lat, first.lon), 15.0))
                updateNumbers()
                dialog.dismiss()
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content)
            setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(resources.displayMetrics.widthPixels - px(24), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.6f }
        }
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - px(24),
            (resources.displayMetrics.heightPixels * 0.78).toInt())
    }

    private fun label(value: String, sp: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radiusDp.toInt()).toFloat()
    }
    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    private fun rowParams(heightDp: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(heightDp))
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        val filter = IntentFilter(TrackingService.ACTION_UPDATE)
        registerTrackingReceiver(filter)
        reloadAll()
        handler.post(ticker)
    }
    @Suppress("DEPRECATION")
    private fun registerTrackingReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(updates, filter)
    }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        unregisterReceiver(updates)
        mapView.onPause()
        super.onPause()
    }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
    override fun onDestroy() { mapView.onDestroy(); db.close(); super.onDestroy() }
}
