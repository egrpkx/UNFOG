package com.unfog.app.exploration

import com.unfog.app.data.TripDatabase.TrackPoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI

/** Shared reveal rules for fog rendering AND city/country exploration progress. */
object TriangleCoverage {
    /** Visits only recorded GPS fixes and trustworthy legs in the same uninterrupted segment. */
    fun cover(points: List<TrackPoint>, fromIndex: Int = 0, visitor: (TriangleGrid.Tile) -> Unit) {
        for (i in fromIndex until points.size) {
            val point = points[i]
            if (!point.lat.isFinite() || !point.lon.isFinite() || point.lat !in -85.0..85.0 || point.lon !in -180.0..180.0) continue
            val prev = points.getOrNull(i - 1)
            if (prev != null && isContinuous(prev, point)) {
                TriangleGrid.visitLeg(
                    TriangleGrid.GeoPoint(prev.lat, prev.lon),
                    TriangleGrid.GeoPoint(point.lat, point.lon), visitor
                )
            } else TriangleGrid.visitAt(point.lat, point.lon, visitor)
        }
    }

    fun all(points: List<TrackPoint>): Set<TriangleGrid.Tile> = HashSet<TriangleGrid.Tile>().also { cells ->
        cover(points) { cells.add(it) }
    }

    private fun isContinuous(a: TrackPoint, b: TrackPoint): Boolean {
        if (a.sessionId != b.sessionId || a.segment != b.segment) return false
        if (b.time - a.time !in 1L..15000L) return false
        val x = TriangleGrid.project(a.lat, a.lon)
        val y = TriangleGrid.project(b.lat, b.lon)
        val scale = cos(((a.lat + b.lat) * 0.5) * PI / 180.0)
        return hypot(x.x - y.x, x.y - y.y) * scale < 300.0
    }
}
