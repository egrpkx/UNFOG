package com.unfog.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.unfog.app.data.TripDatabase.TrackPoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow

/**
 * World-space fog: every persistent GPS sample clears an approximately 40m radius.
 * 4 concentric alpha passes create feathered edges (not a sharp black cutout).
 * Only database-backed points are used: camera movement doesn't 'discover' anything.
 */
class FogOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private var map: MapLibreMap? = null
    private var stored: List<TrackPoint> = emptyList()
    private var preview: List<TrackPoint>? = null
    private var activeTripId: Long? = null
    private var lastRenderedSample: Long? = null
    private var expansion = 1f
    private var animator: ValueAnimator? = null
    private var isPaused = false

    private val dp = resources.displayMetrics.density
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 25, 34) }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val trackGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun connect(mapLibre: MapLibreMap) { map = mapLibre; invalidate() }

    fun updatePoints(points: List<TrackPoint>, tripId: Long?, paused: Boolean) {
        stored = points
        activeTripId = tripId
        isPaused = paused
        val last = points.lastOrNull { it.sessionId == tripId }
        if (last != null && last.time != lastRenderedSample && !paused) {
            lastRenderedSample = last.time
            animator?.cancel()
            expansion = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 780L
                interpolator = DecelerateInterpolator()
                addUpdateListener { expansion = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (paused) {
            expansion = 1f
        }
        invalidate()
    }

    fun showPreview(points: List<TrackPoint>?) { preview = points; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = map
        val region = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        fogPaint.color = Color.argb(240, 16, 25, 34)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fogPaint)
        if (m != null && stored.isNotEmpty()) {
            val groups = stored.groupBy { it.sessionId to it.segment }
            val activeSegment = stored.lastOrNull { it.sessionId == activeTripId }?.segment
            // Repeated alpha passes mimic a soft fog boundary and provide a clear core.
            val passes = arrayOf(1.45f to 28, 1.25f to 60, 1.10f to 114, 1.0f to 255)
            for ((scale, alpha) in passes) {
                cutPaint.alpha = alpha
                for ((key, points) in groups) {
                    val radiusPx = pixelsForMeters(m, 40.0, points.first().lat)
                    val animating = key.first == activeTripId && key.second == activeSegment && !isPaused
                    val path = projectedPath(m, points, animating)
                    val bloom = if (animating && points.size == 1) {
                        0.12f + 0.88f * expansion
                    } else 1f
                    cutPaint.strokeWidth = 2f * radiusPx * scale * bloom
                    canvas.drawPath(path, cutPaint)
                }
            }
        }
        canvas.restoreToCount(region)

        if (m != null) {
            val highlighted = preview ?: stored.filter { it.sessionId == activeTripId }
            val groups = highlighted.groupBy { it.sessionId to it.segment }
            for (points in groups.values) {
                if (points.size < 2) continue
                val path = projectedPath(m, points, false)
                val selectedRoute = preview != null
                trackGlow.apply { color = Color.argb(if (selectedRoute) 82 else 52, 130, 255, 193); strokeWidth = (if (selectedRoute) 15f else 12f) * dp }
                canvas.drawPath(path, trackGlow)
                if (selectedRoute) {
                    trackGlow.apply { color = Color.argb(225, 18, 44, 43); strokeWidth = 6.5f * dp }
                    canvas.drawPath(path, trackGlow)
                }
                trackGlow.apply { color = Color.argb(255, 151, 250, 194); strokeWidth = (if (selectedRoute) 3.8f else 3.1f) * dp }
                canvas.drawPath(path, trackGlow)
            }
            // Selected historical track: clearly visible with start/finish markers.
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

    private fun projectedPath(m: MapLibreMap, points: List<TrackPoint>, animateLast: Boolean): Path {
        val path = Path()
        val latest = if (animateLast && expansion < 1f && points.size > 1) points.dropLast(1) else points
        if (latest.isEmpty()) return path
        val first = m.projection.toScreenLocation(LatLng(latest[0].lat, latest[0].lon))
        path.moveTo(first.x, first.y)
        for (i in 1 until latest.size) {
            val xy = m.projection.toScreenLocation(LatLng(latest[i].lat, latest[i].lon))
            path.lineTo(xy.x, xy.y)
        }
        if (animateLast && expansion < 1f && points.size > 1) {
            val a = latest.last()
            val b = points.last()
            val xy = m.projection.toScreenLocation(LatLng(
                a.lat + (b.lat - a.lat) * expansion,
                a.lon + (b.lon - a.lon) * expansion
            ))
            path.lineTo(xy.x, xy.y)
        }
        // Canvas.drawPath doesn't render a zero-length stroked path on every Android version.
        if (points.size == 1) path.rLineTo(0.04f, 0.04f)
        return path
    }

    private fun pixelsForMeters(m: MapLibreMap, meters: Double, latitude: Double): Float {
        val mPerPixel = 156543.03392 * cos(latitude * PI / 180.0).coerceAtLeast(0.01) /
            2.0.pow(m.cameraPosition.zoom)
        return min(5000f, (meters / mPerPixel).toFloat().coerceAtLeast(0.15f))
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
