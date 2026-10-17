package com.unfog.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location

/** Offline-first storage. A GPS sample is committed before the fog is redrawn. */
class TripDatabase(context: Context) : SQLiteOpenHelper(context, "unfog_trips.db", null, 1) {
    data class Trip(
        val id: Long,
        val startedAt: Long,
        val endedAt: Long?,
        val status: String,
        val distanceM: Double,
        val maxSpeedMps: Double,
        val activeDurationMs: Long,
        val legStartedAt: Long?,
        val segment: Int
    ) {
        fun elapsed(now: Long = System.currentTimeMillis()): Long =
            activeDurationMs + if (status == "active" && legStartedAt != null) {
                (now - legStartedAt).coerceAtLeast(0L)
            } else 0L
    }

    data class TrackPoint(
        val sessionId: Long,
        val segment: Int,
        val time: Long,
        val lat: Double,
        val lon: Double,
        val speedMps: Float,
        val accuracyM: Float
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE trips (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at INTEGER NOT NULL,
            ended_at INTEGER,
            status TEXT NOT NULL,
            distance_m REAL NOT NULL DEFAULT 0,
            max_speed_mps REAL NOT NULL DEFAULT 0,
            active_duration_ms INTEGER NOT NULL DEFAULT 0,
            leg_started_at INTEGER,
            segment INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE track_points (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER NOT NULL,
            segment INTEGER NOT NULL,
            time_ms INTEGER NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            speed_mps REAL NOT NULL,
            accuracy_m REAL NOT NULL,
            FOREIGN KEY(trip_id) REFERENCES trips(id)
        )""")
        db.execSQL("CREATE INDEX points_trip_time ON track_points(trip_id, time_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun startTrip(now: Long = System.currentTimeMillis()): Trip {
        val values = ContentValues().apply {
            put("started_at", now)
            put("status", "active")
            put("leg_started_at", now)
        }
        val id = writableDatabase.insertOrThrow("trips", null, values)
        return getTrip(id)!!
    }

    @Synchronized fun getTrip(id: Long): Trip? {
        readableDatabase.rawQuery("SELECT * FROM trips WHERE id=?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    @Synchronized fun unfinishedTrip(): Trip? {
        readableDatabase.rawQuery("SELECT * FROM trips WHERE status!='finished' ORDER BY id DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    @Synchronized fun trips(): List<Trip> {
        val all = mutableListOf<Trip>()
        readableDatabase.rawQuery("SELECT * FROM trips ORDER BY started_at DESC", null).use { c ->
            while (c.moveToNext()) all.add(fromCursor(c))
        }
        return all
    }

    @Synchronized fun setPaused(id: Long, now: Long = System.currentTimeMillis()) {
        val trip = getTrip(id) ?: return
        if (trip.status != "active") return
        val values = ContentValues().apply {
            put("status", "paused")
            put("active_duration_ms", trip.elapsed(now))
            putNull("leg_started_at")
        }
        writableDatabase.update("trips", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun resume(id: Long, now: Long = System.currentTimeMillis()) {
        val trip = getTrip(id) ?: return
        if (trip.status != "paused") return
        val values = ContentValues().apply {
            put("status", "active")
            put("segment", trip.segment + 1)
            put("leg_started_at", now)
        }
        writableDatabase.update("trips", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun finish(id: Long, now: Long = System.currentTimeMillis()) {
        val trip = getTrip(id) ?: return
        if (trip.status == "finished") return
        val values = ContentValues().apply {
            put("status", "finished")
            put("ended_at", now)
            put("active_duration_ms", trip.elapsed(now))
            putNull("leg_started_at")
        }
        writableDatabase.update("trips", values, "id=?", arrayOf(id.toString()))
    }

    /**
     * Deletes one finished trip and its GPS samples atomically. The fog is reconstructed
     * from every remaining trip; regions visited on other trips remain uncovered.
     * An active or paused trip must first be finished, preventing service/write races.
     */
    @Synchronized fun deleteFinishedTrip(tripId: Long): Boolean {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val trip = getTrip(tripId) ?: return false
            if (trip.status != "finished") return false
            val where = arrayOf(tripId.toString())
            database.delete("track_points", "trip_id=?", where)
            val deleted = database.delete("trips", "id=? AND status='finished'", where) == 1
            if (deleted) database.setTransactionSuccessful()
            return deleted
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized fun lastPoint(tripId: Long, segment: Int): TrackPoint? {
        readableDatabase.rawQuery("""SELECT trip_id, segment, time_ms, lat, lon, speed_mps, accuracy_m
            FROM track_points WHERE trip_id=? AND segment=? ORDER BY id DESC LIMIT 1""",
            arrayOf(tripId.toString(), segment.toString())).use { c ->
            return if (c.moveToFirst()) pointFromCursor(c) else null
        }
    }

    /** Record frequent, trustworthy fixes. Large GPS gaps start a new line segment. */
    @Synchronized fun recordLocation(tripId: Long, location: Location): Boolean {
        val trip = getTrip(tripId) ?: return false
        if (trip.status != "active") return false
        if (!location.hasAccuracy() || location.accuracy > 35f || location.accuracy <= 0f) return false
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return false

        val last = lastPoint(tripId, trip.segment)
        val now = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        if (now < trip.startedAt - 2000L || now > System.currentTimeMillis() + 2000L) return false
        val distance = if (last != null) {
            val results = FloatArray(1)
            Location.distanceBetween(last.lat, last.lon, location.latitude, location.longitude, results)
            results[0].toDouble()
        } else 0.0
        val elapsed = if (last != null) (now - last.time) / 1000.0 else 0.0
        if (last != null) {
            if (elapsed <= 0.0) return false
            // Previously this was up to 23 m; 2.5–6 m now preserves turns.
            val minimumMovement = maxOf(2.5, minOf(6.0, (last.accuracyM + location.accuracy) * 0.16))
            if (distance < minimumMovement) return false
            if (elapsed < 12.0 && distance / elapsed > 25.0) return false
        }
        val gpsGap = last != null && (elapsed > 15.0 || (distance > 60.0 && elapsed > 8.0))
        val newSegment = if (gpsGap) trip.segment + 1 else trip.segment
        val speed = when {
            location.hasSpeed() && location.speed in 0f..25f && location.speed > 0.4f -> location.speed
            last != null && !gpsGap && elapsed > 0.0 -> (distance / elapsed).toFloat().coerceIn(0f, 25f)
            else -> 0f
        }
        val values = ContentValues().apply {
            put("trip_id", trip.id)
            put("segment", newSegment)
            put("time_ms", now)
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("speed_mps", speed)
            put("accuracy_m", location.accuracy)
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertOrThrow("track_points", null, values)
            val updated = ContentValues().apply {
                // Never invent the path/distance across a long period without GPS.
                put("distance_m", trip.distanceM + if (gpsGap) 0.0 else distance)
                put("max_speed_mps", maxOf(trip.maxSpeedMps, speed.toDouble()))
                if (gpsGap) put("segment", newSegment)
            }
            writableDatabase.update("trips", updated, "id=?", arrayOf(trip.id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return true
    }

    @Synchronized fun points(tripId: Long? = null): List<TrackPoint> {
        val all = mutableListOf<TrackPoint>()
        val where = if (tripId == null) "" else "WHERE trip_id=?"
        readableDatabase.rawQuery("""SELECT trip_id, segment, time_ms, lat, lon, speed_mps, accuracy_m
            FROM track_points $where ORDER BY trip_id, segment, id""",
            if (tripId == null) null else arrayOf(tripId.toString())).use { c ->
            while (c.moveToNext()) all.add(pointFromCursor(c))
        }
        return all
    }

    private fun fromCursor(c: android.database.Cursor): Trip = Trip(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
        endedAt = c.getColumnIndexOrThrow("ended_at").let { if (c.isNull(it)) null else c.getLong(it) },
        status = c.getString(c.getColumnIndexOrThrow("status")),
        distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m")),
        maxSpeedMps = c.getDouble(c.getColumnIndexOrThrow("max_speed_mps")),
        activeDurationMs = c.getLong(c.getColumnIndexOrThrow("active_duration_ms")),
        legStartedAt = c.getColumnIndexOrThrow("leg_started_at").let { if (c.isNull(it)) null else c.getLong(it) },
        segment = c.getInt(c.getColumnIndexOrThrow("segment"))
    )

    private fun pointFromCursor(c: android.database.Cursor): TrackPoint = TrackPoint(
        sessionId = c.getLong(0), segment = c.getInt(1), time = c.getLong(2),
        lat = c.getDouble(3), lon = c.getDouble(4), speedMps = c.getFloat(5), accuracyM = c.getFloat(6)
    )
}
