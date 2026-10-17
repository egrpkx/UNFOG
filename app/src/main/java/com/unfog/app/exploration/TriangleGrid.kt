package com.unfog.app.exploration

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Stable, worldwide tessellation of equilateral triangles in Web Mercator.
 *
 * Cell IDs are tied to projected world coordinates, never to screen pixels,
 * camera zoom or the user's starting location. The visual side length on the
 * map stays the same geographic feature at every zoom level. Physical tile
 * area varies with latitude because Web Mercator is not equal-area.
 */
object TriangleGrid {
    private const val EARTH = 6378137.0
    private const val MAX_LAT = 85.05112878
    const val SIDE_M = 90.0 // projected metres; about 57m on the ground at latitude 51°
    const val REVEAL_RADIUS_M = 40.0 // true ground metres
    private val HEIGHT_M = SIDE_M * sqrt(3.0) / 2.0
    private val PROJECTED_TRIANGLE_AREA = SIDE_M * HEIGHT_M / 2.0

    data class WorldPoint(val x: Double, val y: Double)
    data class GeoPoint(val lat: Double, val lon: Double)
    data class Tile(val column: Int, val row: Int, val second: Boolean)

    fun project(lat: Double, lon: Double): WorldPoint {
        val safeLat = lat.coerceIn(-MAX_LAT, MAX_LAT)
        val radians = safeLat * PI / 180.0
        return WorldPoint(EARTH * lon * PI / 180.0,
            EARTH * ln(tan(PI / 4.0 + radians / 2.0)))
    }

    fun unproject(point: WorldPoint): GeoPoint = GeoPoint(
        atan(sinh(point.y / EARTH)) * 180.0 / PI,
        point.x / EARTH * 180.0 / PI
    )

    private fun vertex(column: Int, row: Int): WorldPoint = WorldPoint(
        (column + row * 0.5) * SIDE_M,
        row * HEIGHT_M
    )

    /** Each parallelogram is split along its down-left / up-right diagonal. */
    fun vertices(tile: Tile): Array<WorldPoint> {
        val a = vertex(tile.column, tile.row)
        val b = vertex(tile.column + 1, tile.row)
        val c = vertex(tile.column, tile.row + 1)
        val d = vertex(tile.column + 1, tile.row + 1)
        return if (tile.second) arrayOf(b, d, c) else arrayOf(a, b, c)
    }

    fun centroid(tile: Tile): WorldPoint {
        val v = vertices(tile)
        return WorldPoint((v[0].x + v[1].x + v[2].x) / 3.0,
            (v[0].y + v[1].y + v[2].y) / 3.0)
    }

    /** Approximate ground area near the tile's centroid. */
    fun areaM2(tile: Tile): Double {
        val latitude = unproject(centroid(tile)).lat
        val scale = cos(latitude * PI / 180.0)
        return PROJECTED_TRIANGLE_AREA * scale * scale
    }

    fun visitAt(lat: Double, lon: Double, visitor: (Tile) -> Unit) {
        val p = project(lat, lon)
        visitDisc(p, REVEAL_RADIUS_M / max(0.01, cos(lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0)), visitor)
    }

    /**
     * Reveal cells that actually intersect the 40m disc around each GPS fix.
     * Sampling along a recorded leg also reveals crossed cells, not merely
     * cells containing endpoints. The caller must reject disconnected GPS legs.
     */
    fun visitLeg(a: GeoPoint, b: GeoPoint, visitor: (Tile) -> Unit) {
        val p = project(a.lat, a.lon)
        val q = project(b.lat, b.lon)
        val distance = hypot(q.x - p.x, q.y - p.y)
        val lat = (a.lat + b.lat) / 2.0
        val radiusProjected = REVEAL_RADIUS_M / max(0.01, cos(lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0))
        val step = min(SIDE_M / 3.0, radiusProjected / 2.0)
        val steps = max(1, kotlin.math.ceil(distance / step).toInt())
        for (index in 0..steps) {
            val t = index.toDouble() / steps
            visitDisc(WorldPoint(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t),
                radiusProjected, visitor)
        }
    }

    /** Public for validation; expensive only near the tiny neighbourhood around a GPS fix. */
    fun visitDisc(center: WorldPoint, radius: Double, visitor: (Tile) -> Unit) {
        if (!center.x.isFinite() || !center.y.isFinite() || !radius.isFinite()) return
        val r = radius.coerceIn(0.0, 1000.0)
        val startRow = floor((center.y - r) / HEIGHT_M).toInt() - 1
        val endRow = floor((center.y + r) / HEIGHT_M).toInt() + 1
        val r2 = r * r
        for (row in startRow..endRow) {
            val startCol = floor((center.x - r) / SIDE_M - (row + 1) / 2.0).toInt() - 1
            val endCol = floor((center.x + r) / SIDE_M - row / 2.0).toInt() + 1
            for (col in startCol..endCol) {
                for (second in arrayOf(false, true)) {
                    val tile = Tile(col, row, second)
                    val vertices = vertices(tile)
                    if (intersects(vertices, center, r2)) visitor(tile)
                }
            }
        }
    }

    private fun intersects(v: Array<WorldPoint>, p: WorldPoint, radiusSquared: Double): Boolean {
        // All triangles here are counter-clockwise.
        fun cross(a: WorldPoint, b: WorldPoint): Double =
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        if (cross(v[0], v[1]) >= 0.0 && cross(v[1], v[2]) >= 0.0 && cross(v[2], v[0]) >= 0.0) return true
        return distanceToSegmentSq(p, v[0], v[1]) <= radiusSquared ||
            distanceToSegmentSq(p, v[1], v[2]) <= radiusSquared ||
            distanceToSegmentSq(p, v[2], v[0]) <= radiusSquared
    }

    private fun distanceToSegmentSq(p: WorldPoint, a: WorldPoint, b: WorldPoint): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        val x = p.x - a.x - t * dx
        val y = p.y - a.y - t * dy
        return x * x + y * y
    }
}
