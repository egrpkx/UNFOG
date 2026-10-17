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
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.unfog.app.R
import com.unfog.app.data.TripDatabase
import com.unfog.app.exploration.TerritoryProgress
import com.unfog.app.location.TrackingService
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
    private var lastSpeedFixAt = 0L
    private var lastLocatedAt = 0L
    private var previewing = false
    private var previewTripId: Long? = null

    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var distanceText: TextView
    private lateinit var averageText: TextView
    private lateinit var timeText: TextView
    private lateinit var primaryButton: TextView
    private lateinit var finishButton: TextView
    private lateinit var previewCloseButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { updateNumbers(); handler.postDelayed(this, 1000L) }
    }

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(TrackingService.EXTRA_ERROR)?.let { toast(it) }
            if (intent?.hasExtra(TrackingService.EXTRA_SPEED) == true) {
                lastLiveSpeedMps = intent.getFloatExtra(TrackingService.EXTRA_SPEED, 0f).coerceIn(0f, 25f)
            }
            if (intent?.hasExtra(TrackingService.EXTRA_FIX_TIME) == true) {
                lastSpeedFixAt = intent.getLongExtra(TrackingService.EXTRA_FIX_TIME, 0L)
            }
            val pointChanged = intent?.getBooleanExtra("point_changed", false) == true
            val stateChanged = intent?.getBooleanExtra("state_changed", false) == true
            if (pointChanged || stateChanged) reloadAll() else updateNumbers()
            val trip = db.unfinishedTrip()
            if (pointChanged && trip?.status == "active" && !previewing) panToLastPoint(trip.id, false)
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
        previewCloseButton = findViewById(R.id.previewCloseButton)

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
        findViewById<View>(R.id.territoryButton).setOnClickListener { showTerritorySettings() }
        previewCloseButton.setOnClickListener { clearPreview() }
        findViewById<View>(R.id.locateButton).setOnClickListener {
            if (hasFineLocation()) locateUser() else askLocationPermission(false)
        }
        findViewById<View>(R.id.attribution).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")))
        }
        primaryButton.setOnClickListener {
            clearPreview()
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
        if (force || System.currentTimeMillis() - lastLocatedAt > 850L) {
            val destination = LatLng(last.lat, last.lon)
            val update = if (force || (map?.cameraPosition?.zoom ?: 0.0) < 13.0)
                CameraUpdateFactory.newLatLngZoom(destination, 16.0)
            else CameraUpdateFactory.newLatLng(destination)
            map?.animateCamera(update, 800)
            lastLocatedAt = System.currentTimeMillis()
        }
    }

    private fun reloadAll() {
        val trip = db.unfinishedTrip()
        fog.updatePoints(db.points(), trip?.id, trip?.status == "paused")
        updateNumbers()
    }

    private fun updateNumbers() {
        val recordingTrip = db.unfinishedTrip()
        val viewedTrip = if (previewing) previewTripId?.let { db.getTrip(it) } else null
        val displayedTrip = viewedTrip ?: recordingTrip
        if (recordingTrip?.status != "active") lastLiveSpeedMps = 0f
        val gpsStale = recordingTrip?.status == "active" && lastSpeedFixAt > 0L &&
            System.currentTimeMillis() - lastSpeedFixAt > 5000L
        statusText.text = when {
            viewedTrip != null -> "ПРОСМОТР · МАКС. СКОРОСТЬ"
            recordingTrip?.status == "active" && gpsStale -> "●  ОЖИДАЕМ GPS · ПРОВЕРЬ ГЕОЛОКАЦИЮ"
            recordingTrip?.status == "active" -> "●  ИДЁТ ЗАПИСЬ ПОЕЗДКИ"
            recordingTrip?.status == "paused" -> "Ⅱ  ПАУЗА · ТУМАН НЕ ОТКРЫВАЕТСЯ"
            else -> "ГОТОВ К ИССЛЕДОВАНИЮ"
        }
        val speedKmh = if (viewedTrip != null) viewedTrip.maxSpeedMps * 3.6
            else if (recordingTrip?.status == "active" && !gpsStale) lastLiveSpeedMps * 3.6 else 0.0
        speedText.text = String.format(Locale.US, "%.1f", speedKmh)
        distanceText.text = String.format(Locale.US, "%.2f км", (displayedTrip?.distanceM ?: 0.0) / 1000.0)
        val duration = displayedTrip?.elapsed() ?: 0L
        val average = if (duration > 0L) ((displayedTrip?.distanceM ?: 0.0) / duration.toDouble()) * 3600.0 else 0.0
        averageText.text = String.format(Locale.US, "%.1f км/ч", average)
        val seconds = duration / 1000L
        timeText.text = if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
            else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        primaryButton.text = when (recordingTrip?.status) {
            "active" -> "Ⅱ   Пауза"
            "paused" -> "▶   Продолжить"
            else -> "●   Начать поездку"
        }
        finishButton.visibility = if (recordingTrip != null) View.VISIBLE else View.GONE
    }

    private fun clearPreview() {
        previewing = false
        previewTripId = null
        fog.showPreview(null)
        previewCloseButton.visibility = View.GONE
        updateNumbers()
    }

    /** Fit the complete selected trip between the top controls and bottom tracking panel. */
    private fun showTripOnMap(tripId: Long, points: List<TripDatabase.TrackPoint>) {
        previewing = true
        previewTripId = tripId
        previewCloseButton.visibility = View.VISIBLE
        fog.showPreview(points)
        updateNumbers()
        mapView.post {
            val current = map ?: return@post
            if (previewTripId != tripId || points.isEmpty()) return@post
            val distinct = points.any { it.lat != points[0].lat || it.lon != points[0].lon }
            if (!distinct) {
                current.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 16.0))
            } else {
                val bounds = LatLngBounds.Builder()
                for (point in points) bounds.include(LatLng(point.lat, point.lon))
                // The bottom data panel obscures ~260dp of the map.
                current.animateCamera(CameraUpdateFactory.newLatLngBounds(
                    bounds.build(), px(36), px(150), px(36), px(310)
                ))
            }
        }
    }

    private fun confirmDeleteTrip(trip: TripDatabase.Trip, historyDialog: Dialog) {
        AlertDialog.Builder(this)
            .setTitle("Удалить поездку?")
            .setMessage("Запись, её километры и GPS-точки будут удалены без восстановления. " +
                "Участки карты, открытые только этой поездкой, снова покроются туманом. " +
                "Территории других поездок сохранятся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                if (db.deleteFinishedTrip(trip.id)) {
                    if (previewTripId == trip.id) clearPreview()
                    reloadAll()
                    historyDialog.dismiss()
                    showHistory()
                    toast("Поездка удалена")
                } else toast("Заверши поездку перед удалением")
            }
            .show()
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
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val viewRoute = label(if (trip.status == "finished") "Просмотреть маршрут →" else "Текущая поездка →",
                13f, 0xFFB8F7CF.toInt(), true).apply {
                setPadding(0, px(9), px(8), px(7))
            }
            actions.addView(viewRoute, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trip.status == "finished") {
                val delete = label("Удалить", 13f, 0xFFFFB6AF.toInt(), false).apply {
                    setPadding(px(12), px(10), 0, px(8))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Удалить поездку ${formatter.format(Date(trip.startedAt))}"
                    setOnClickListener { confirmDeleteTrip(trip, dialog) }
                }
                actions.addView(delete)
            }
            card.addView(actions)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = px(11)
            content.addView(card, lp)
            val openRoute = View.OnClickListener {
                val points = db.points(trip.id)
                if (points.isEmpty()) { toast("Для этой поездки ещё нет GPS-точек") }
                else {
                    showTripOnMap(trip.id, points)
                    dialog.dismiss()
                }
            }
            card.setOnClickListener(openRoute)
            viewRoute.setOnClickListener(openRoute)
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

    /** Area exploration uses actual polygon boundaries, not a rectangle around a city name. */
    private fun showTerritorySettings() {
        val territory = TerritoryProgress(applicationContext)
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(20), px(22), px(22))
            background = rounded(Color.rgb(24, 36, 45), 25f)
        }
        val close = label("✕", 22f, Color.WHITE, false).apply {
            gravity = Gravity.END
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close, rowParams(34))
        content.addView(label("Настройки", 27f, Color.WHITE, true))
        content.addView(label("UNFOG 0.5.0 · тестовая версия", 12f, 0xFF9EADB5.toInt(), false).apply {
            setPadding(0, px(7), 0, px(19))
        })
        content.addView(label("Исследование территорий", 20f, Color.WHITE, true))
        content.addView(label("Выбери город, регион или страну. Все поездки сохраняются в общей истории — меняется только процент исследованной площади.",
            14f, 0xFFB9CAD0.toInt(), false).apply { setPadding(0, px(10), 0, px(16)) })
        val nameLabel = label(territory.savedName() ?: "Территория не выбрана", 17f, 0xFFB8F7CF.toInt(), true)
        content.addView(nameLabel)
        val stats = label("", 17f, Color.WHITE, true).apply { setPadding(0, px(12), 0, px(12)) }
        content.addView(stats)
        val input = EditText(this).apply {
            hint = "Например, Саратов, Россия"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9DAFB8.toInt())
            background = rounded(0xFF30414A.toInt(), 11f)
            setPadding(px(12), px(13), px(12), px(13))
        }
        content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50)))
        val searchButton = label("Найти территорию →", 16f, 0xFFB8F7CF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(16), 0, px(16))
        }
        content.addView(searchButton)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)
        content.addView(label("Туман войны: треугольная сетка. Новые ячейки открываются только во время активной записи поездки. На паузе прогресс не меняется.",
            13f, 0xFFBED8CB.toInt(), false).apply { setPadding(0, px(10), 0, px(2)) })
        val finePrint = label("Расчёт приблизительный: открываются треугольные ячейки рядом с GPS-треком (радиус 40 м), " +
            "площадь выбранных административных границ OpenStreetMap. " +
            "Поиск выполняется только по нажатию, для него нужен интернет. " +
            "Сами координаты поездок никуда не отправляются.",
            12f, 0xFF9DAFB8.toInt(), false).apply { setPadding(0, px(18), 0, 0) }
        content.addView(finePrint)

        fun refreshStats() {
            val saved = territory.savedName()
            nameLabel.text = saved ?: "Территория не выбрана"
            if (saved == null) {
                stats.text = "Выбери локацию, чтобы увидеть свой прогресс"
                return
            }
            stats.text = "Считаем исследованную площадь…"
            Thread {
                try {
                    val result = territory.calculate(db.points())
                    runOnUiThread {
                        if (!dialog.isShowing) return@runOnUiThread
                        if (result == null) {
                            stats.text = "Нет сохранённой территории"
                            return@runOnUiThread
                        }
                        val percent = when {
                            result.percentage == 0.0 -> "0%"
                            result.percentage < 0.000001 -> "<0,000001%"
                            result.percentage < 0.01 -> String.format(Locale("ru"), "%.6f%%", result.percentage)
                            else -> String.format(Locale("ru"), "%.2f%%", result.percentage)
                        }
                        stats.text = "$percent открыто\n" +
                            String.format(Locale("ru"), "%.3f км² из %.0f км²\nОткрыто треугольников: %d", result.visitedKm2, result.areaKm2, result.visitedTriangles)
                    }
                } catch (e: Exception) {
                    runOnUiThread { if (dialog.isShowing) stats.text = "Ошибка подсчёта: ${e.localizedMessage ?: "неизвестная"}" }
                }
            }.start()
        }

        searchButton.setOnClickListener {
            val query = input.text.toString().trim()
            if (query.length < 2) {
                toast("Введи название города или страны")
                return@setOnClickListener
            }
            searchButton.isEnabled = false
            results.removeAllViews()
            results.addView(label("Ищем границы…", 14f, 0xFFB9CAD0.toInt(), false))
            Thread {
                try {
                    val matches = territory.search(query)
                    runOnUiThread {
                        if (!dialog.isShowing) return@runOnUiThread
                        searchButton.isEnabled = true
                        results.removeAllViews()
                        if (matches.isEmpty()) {
                            results.addView(label("Локации с границами не найдены. Уточни название.",
                                14f, Color.WHITE, false))
                        }
                        matches.forEach { hit ->
                            val option = label(hit.displayName, 14f, Color.WHITE, false).apply {
                                setPadding(px(8), px(14), px(8), px(14))
                                background = rounded(0xFF2B3D46.toInt(), 10f)
                                setOnClickListener {
                                    searchButton.isEnabled = false
                                    results.removeAllViews()
                                    results.addView(label("Загружаем границы…", 14f, Color.WHITE, false))
                                    Thread {
                                        try {
                                            territory.select(hit)
                                            runOnUiThread {
                                                if (!dialog.isShowing) return@runOnUiThread
                                                searchButton.isEnabled = true
                                                results.removeAllViews()
                                                refreshStats()
                                            }
                                        } catch (e: Exception) {
                                            runOnUiThread {
                                                if (!dialog.isShowing) return@runOnUiThread
                                                searchButton.isEnabled = true
                                                results.removeAllViews()
                                                results.addView(label("Не удалось загрузить границы: ${e.localizedMessage}",
                                                    14f, 0xFFFFB6AF.toInt(), false))
                                            }
                                        }
                                    }.start()
                                }
                            }
                            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) }
                            results.addView(option, params)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!dialog.isShowing) return@runOnUiThread
                        searchButton.isEnabled = true
                        results.removeAllViews()
                        results.addView(label("Ошибка поиска: ${e.localizedMessage ?: "проверь интернет"}",
                            14f, 0xFFFFB6AF.toInt(), false))
                    }
                }
            }.start()
        }
        val scroll = ScrollView(this).apply { addView(content); setBackgroundColor(Color.TRANSPARENT) }
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.apply {
            setLayout(resources.displayMetrics.widthPixels - px(24),
                (resources.displayMetrics.heightPixels * 0.8).toInt())
            setGravity(Gravity.BOTTOM)
        }
        refreshStats()
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
