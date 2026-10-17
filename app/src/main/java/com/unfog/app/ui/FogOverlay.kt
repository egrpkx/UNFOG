package com.unfog.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.unfog.app.data.TripDatabase.TrackPoint
import com.unfog.app.exploration.TriangleCoverage
import com.unfog.app.exploration.TriangleGrid
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Triangular fog of war. All revealed cells are geographic, not screen-based.
 * They are derived from persisted GPS samples, so changing zoom, restarting
 * the app, or deleting a ride gives deterministic exploration state.
 */
class FogOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private var map: MapLibreMap? = null
    private var stored: List<TrackPoint> = emptyList()
    private var preview: List<TrackPoint>? = null
    private var activeTripId: Long? = null
    private var isPaused = false

    private val visited = HashSet<TriangleGrid.Tile>()
    private var appearing: Set<TriangleGrid.Tile> = emptySet()
    private var revealProgress = 1f
    private var animator: ValueAnimator? = null

    private val dp = resources.displayMetrics.density
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(241, 15, 24, 34) }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.65f * dp
        color = Color.argb(35, 155, 236, 199)
    }
    private val trackGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // DST_OUT must operate within a separate fog layer, not on the map itself.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun connect(mapLibre: MapLibreMap) { map = mapLibre; invalidate() }

    fun updatePoints(points: List<TrackPoint>, tripId: Long?, paused: Boolean) {
        val previous = stored
        val alreadyCurrent = previous.size == points.size &&
            (previous.isEmpty() || previous.last() == points.lastOrNull()) &&
            (previous.isEmpty() || previous.first() == points.firstOrNull())
        val canAppend = previous.isNotEmpty() && points.size > previous.size &&
            previous.last() == points[previous.size - 1] && previous.first() == points.first()
        val discovered = HashSet<TriangleGrid.Tile>()

        when {
            canAppend -> TriangleCoverage.cover(points, previous.size) { cell ->
                if (visited.add(cell)) discovered.add(cell)
            }
            !alreadyCurrent -> {
                visited.clear()
                TriangleCoverage.cover(points) { cell -> visited.add(cell) }
                // Only the very first GPS fix of a fresh installation animates
                // here; reloading hundreds of existing fixes must not flash.
                if (previous.isEmpty() && points.size == 1) discovered.addAll(visited)
            }
        }
        stored = points
        activeTripId = tripId
        isPaused = paused

        animator?.cancel()
        if (discovered.isNotEmpty() && tripId != null && !paused &&
            points.lastOrNull()?.sessionId == tripId) {
            appearing = discovered
            revealProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 470L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    revealProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            appearing = emptySet()
            revealProgress = 1f
        }
        invalidate()
    }

    fun showPreview(points: List<TrackPoint>?) { preview = points; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = map
        val fogLayer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fogPaint)

        // Build one path for old discoveries to reduce Canvas draw calls.
        // A second path uses partial alpha during the newest cell animation.
        if (m != null && visited.isNotEmpty()) {
            val viewport = worldViewport(m)
            val permanent = Path()
            val entering = Path()
            val borders = if (m.cameraPosition.zoom >= 15.0) Path() else null
            for (tile in visited) {
                val midpoint = TriangleGrid.centroid(tile)
                if (viewport != null && (midpoint.x !in viewport[0]..viewport[2] ||
                    midpoint.y !in viewport[1]..viewport[3])) continue
                val path = projectedTriangle(m, tile)
                if (tile in appearing && revealProgress < 1f) entering.addPath(path)
                else permanent.addPath(path)
                borders?.addPath(path)
            }
            cutPaint.alpha = 255
            canvas.drawPath(permanent, cutPaint)
            if (appearing.isNotEmpty() && revealProgress < 1f) {
                cutPaint.alpha = (255 * revealProgress).toInt().coerceIn(0, 255)
                canvas.drawPath(entering, cutPaint)
            }
            canvas.restoreToCount(fogLayer)
            // Extremely subtle triangular facets on discovered map, shown only
            // at street zoom. No thick borders or distracting grid everywhere.
            if (borders != null) canvas.drawPath(borders, seamPaint)
        } else canvas.restoreToCount(fogLayer)

        if (m != null) {
            val highlighted = preview ?: stored.filter { it.sessionId == activeTripId }
            val groups = highlighted.groupBy { it.sessionId to it.segment }
            for (points in groups.values) {
                if (points.size < 2) continue
                val path = projectedPath(m, points)
                val selectedRoute = preview != null
                trackGlow.apply {
                    color = Color.argb(if (selectedRoute) 82 else 52, 130, 255, 193)
                    strokeWidth = (if (selectedRoute) 15f else 12f) * dp
                }
                canvas.drawPath(path, trackGlow)
                if (selectedRoute) {
                    trackGlow.apply { color = Color.argb(225, 18, 44, 43); strokeWidth = 6.5f * dp }
                    canvas.drawPath(path, trackGlow)
                }
                trackGlow.apply {
                    color = Color.argb(255, 151, 250, 194)
                    strokeWidth = (if (selectedRoute) 3.8f else 3.1f) * dp
                }
                canvas.drawPath(path, trackGlow)
            }
            preview?.takeIf { it.isNotEmpty() }?.let { selected ->
                val start = m.projection.toScreenLocation(LatLng(selected.first().lat, selected.first().lon))
                val finish = m.projection.toScreenLocation(LatLng(selected.last().lat, selected.last().lon))
                drawRouteEndpoint(canvas, start.x, start.y, true)
                drawRouteEndpoint(canvas, finish.x, finish.y, false)
            }
            if (preview == null && activeTripId != null && !isPaused) {
                stored.lastOrNull { it.sessionId == activeTripId }?.let { point ->
                    val xy = m.projection.toScreenLocation(LatLng(point.lat, point.lon))
                    drawMarker(canvas, xy.x, xy.y)
                }
            }
        }
    }

    // Expanded viewport; allow triangles crossing the screen edge to render.
    private fun worldViewport(m: MapLibreMap): DoubleArray? = try {
        val corners = arrayOf(
            PointF(0f, 0f), PointF(width.toFloat(), 0f),
            PointF(0f, height.toFloat()), PointF(width.toFloat(), height.toFloat())
        ).map { screen ->
            val point = m.projection.fromScreenLocation(screen)
            TriangleGrid.project(point.latitude, point.longitude)
        }
        val padding = TriangleGrid.SIDE_M * 2
        doubleArrayOf(corners.minOf { it.x } - padding, corners.minOf { it.y } - padding,
            corners.maxOf { it.x } + padding, corners.maxOf { it.y } + padding)
    } catch (_: Exception) { null }

    private fun projectedTriangle(m: MapLibreMap, tile: TriangleGrid.Tile): Path {
        val triangle = TriangleGrid.vertices(tile)
        val path = Path()
        triangle.forEachIndexed { index, projected ->
            val geo = TriangleGrid.unproject(projected)
            val screen = m.projection.toScreenLocation(LatLng(geo.lat, geo.lon))
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        path.close()
        return path
    }

    private fun projectedPath(m: MapLibreMap, points: List<TrackPoint>): Path {
        val path = Path()
        points.forEachIndexed { index, point ->
            val xy = m.projection.toScreenLocation(LatLng(point.lat, point.lon))
            if (index == 0) path.moveTo(xy.x, xy.y) else path.lineTo(xy.x, xy.y)
        }
        return path
    }

    private fun drawRouteEndpoint(canvas: Canvas, x: Float, y: Float, start: Boolean) {
        val r = 10f * dp
        dotPaint.shader = null
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = Color.argb(165, 14, 31, 36)
        canvas.drawCircle(x, y, r + 3f * dp, dotPaint)
        dotPaint.color = if (start) Color.rgb(151, 250, 194) else Color.rgb(255, 196, 142)
        canvas.drawCircle(x, y, r, dotPaint)
        dotPaint.color = Color.rgb(21, 37, 42)
        canvas.drawCircle(x, y, 3.4f * dp, dotPaint)
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float) {
        val r = 24f * dp
        dotPaint.shader = RadialGradient(x, y, r,
            intArrayOf(Color.argb(85, 141, 255, 193), Color.argb(0, 141, 255, 193)),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, dotPaint)
        dotPaint.shader = null
        dotPaint.color = Color.WHITE
        canvas.drawCircle(x, y, 8f * dp, dotPaint)
        dotPaint.color = Color.rgb(55, 164, 114)
        canvas.drawCircle(x, y, 5f * dp, dotPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
