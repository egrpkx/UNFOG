package com.unfog.app.exploration

import android.content.Context
import com.unfog.app.data.TripDatabase.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import android.os.SystemClock
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Experimental area-based game mode. Boundaries are taken from OSM's polygon data;
 * the percent estimates the area of revealed triangular cells around recorded rides.
 * Only explicit user search triggers a geocoding request. No GPS coordinates leave the phone.
 */
class TerritoryProgress(private val context: Context) {
    data class Hit(val displayName: String, val osmType: String, val osmId: Long)
    data class Progress(val name: String, val areaKm2: Double, val visitedKm2: Double, val visitedTriangles: Int) {
        val percentage: Double get() = if (areaKm2 > 0.0)
            (visitedKm2 / areaKm2 * 100.0).coerceIn(0.0, 100.0) else 0.0
    }

    private data class Vertex(val lon: Double, val lat: Double)
    private data class Ring(val vertices: List<Vertex>) {
        val minLat: Double = vertices.minOfOrNull { it.lat } ?: 91.0
        val maxLat: Double = vertices.maxOfOrNull { it.lat } ?: -91.0
    }
    private data class Polygon(val rings: List<Ring>)
    private data class Region(val name: String, val polygons: List<Polygon>)

    private val file: File get() = File(context.filesDir, "selected_territory.json")
    private val radiusEarth = 6371008.8
    private var lastRequestAt = 0L

    fun savedName(): String? = try { JSONObject(file.readText()).getString("name") } catch (_: Exception) { null }
    fun clearSelection() { file.delete() }

    fun search(query: String): List<Hit> {
        require(query.trim().length >= 2) { "Введи название города, региона или страны" }
        val raw = request("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&addressdetails=0&q=" +
            URLEncoder.encode(query.trim(), "UTF-8"))
        val json = JSONArray(raw)
        return (0 until json.length()).mapNotNull { i ->
            val item = json.getJSONObject(i)
            val type = item.optString("osm_type")
            val id = item.optLong("osm_id", 0L)
            if (type !in listOf("relation", "way") || id == 0L) null
            else Hit(item.getString("display_name"), type, id)
        }
    }

    /** Request exact polygon when a result is tapped, then keep it offline. */
    fun select(hit: Hit): String {
        val prefix = if (hit.osmType == "relation") "R" else "W"
        val raw = request("https://nominatim.openstreetmap.org/lookup?format=jsonv2&polygon_geojson=1&" +
            "polygon_threshold=0.001&osm_ids=$prefix${hit.osmId}")
        val array = JSONArray(raw)
        if (array.length() == 0) error("Границы не найдены")
        val geometry = array.getJSONObject(0).optJSONObject("geojson") ?: error("Для этой локации нет границ")
        val region = parseRegion(hit.displayName, geometry)
        if (region.polygons.isEmpty()) error("Выбери населённый пункт или административную территорию с границами")
        val objectToSave = JSONObject().put("name", hit.displayName).put("geometry", geometry)
        file.writeText(objectToSave.toString())
        return region.name
    }

    fun calculate(points: List<TrackPoint>): Progress? {
        if (!file.exists()) return null
        val data = JSONObject(file.readText())
        val region = parseRegion(data.getString("name"), data.getJSONObject("geometry"))
        val areaM2 = region.polygons.sumOf { poly ->
            poly.rings.mapIndexed { index, ring ->
                ringArea(ring) * if (index == 0) 1.0 else -1.0
            }.sum()
        }.coerceAtLeast(0.0)
        if (areaM2 <= 0.0) return Progress(region.name, 0.0, 0.0, 0)

        // The percentage and the visual fog use EXACTLY the same triangular
        // cells, so there is no discrepancy when the player switches locations.
        // A triangle is counted only if its centroid is within the selected
        // administrative polygon; border cells are an approximation.
        var exploredM2 = 0.0
        var triangleCount = 0
        TriangleCoverage.all(points).forEach { tile ->
            val center = TriangleGrid.unproject(TriangleGrid.centroid(tile))
            if (inside(region, center.lon, center.lat)) {
                exploredM2 += TriangleGrid.areaM2(tile)
                triangleCount++
            }
        }
        val covered = min(areaM2, exploredM2)
        return Progress(region.name, areaM2 / 1_000_000.0, covered / 1_000_000.0, triangleCount)
    }

    /** Cylindrical equal-area approximation using geodetic ring coordinates. */
    private fun ringArea(ring: Ring): Double {
        val v = ring.vertices
        if (v.size < 3) return 0.0
        var sum = 0.0
        for (i in v.indices) {
            val a = v[i]
            val b = v[(i + 1) % v.size]
            var dlon = (b.lon - a.lon) * PI / 180.0
            if (dlon > PI) dlon -= 2 * PI
            if (dlon < -PI) dlon += 2 * PI
            sum += dlon * (sin(a.lat * PI / 180.0) + sin(b.lat * PI / 180.0))
        }
        return abs(sum * radiusEarth * radiusEarth / 2.0)
    }

    private fun inside(region: Region, lon: Double, lat: Double): Boolean = region.polygons.any { polygon ->
        polygon.rings.isNotEmpty() && insideRing(polygon.rings.first(), lon, lat) &&
            polygon.rings.drop(1).none { insideRing(it, lon, lat) }
    }

    private fun insideRing(ring: Ring, lon: Double, lat: Double): Boolean {
        val vertices = ring.vertices
        if (vertices.size < 3 || lat < ring.minLat || lat > ring.maxLat) return false
        var found = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val a = vertices[i]; val b = vertices[j]
            // Shift each longitude near the test location, to handle the antimeridian.
            val ax = lon + normalizeLongitudeDelta(a.lon - lon)
            val bx = lon + normalizeLongitudeDelta(b.lon - lon)
            if ((a.lat > lat) != (b.lat > lat) &&
                lon < (bx - ax) * (lat - a.lat) / (b.lat - a.lat) + ax) found = !found
            j = i
        }
        return found
    }

    private fun normalizeLongitudeDelta(value: Double): Double {
        var delta = value
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        return delta
    }

    private fun parseRegion(name: String, geometry: JSONObject): Region {
        val type = geometry.optString("type")
        val coordinates = geometry.optJSONArray("coordinates") ?: return Region(name, emptyList())
        fun parseRing(arr: JSONArray): Ring = Ring((0 until arr.length()).map { idx ->
            val point = arr.getJSONArray(idx)
            Vertex(point.getDouble(0), point.getDouble(1))
        })
        fun parsePolygon(arr: JSONArray): Polygon = Polygon((0 until arr.length()).map { idx ->
            parseRing(arr.getJSONArray(idx))
        })
        val polys = when (type) {
            "Polygon" -> listOf(parsePolygon(coordinates))
            "MultiPolygon" -> (0 until coordinates.length()).map { parsePolygon(coordinates.getJSONArray(it)) }
            else -> emptyList()
        }
        return Region(name, polys)
    }

    @Synchronized private fun request(address: String): String {
        // Public Nominatim usage policy: at most one request per second per app.
        val elapsed = SystemClock.elapsedRealtime() - lastRequestAt
        if (lastRequestAt > 0L && elapsed < 1200L) Thread.sleep(1200L - elapsed)
        lastRequestAt = SystemClock.elapsedRealtime()
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("User-Agent", "UNFOG-Android/0.5 (user-initiated geocoding; OpenStreetMap prototype)")
            connection.setRequestProperty("Accept-Language", Locale.getDefault().language)
            if (connection.responseCode != 200) error("Сервис границ недоступен: HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}
