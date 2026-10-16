package com.dji.sdk.sample.takpilot2

import java.util.Locale
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.dji.sdk.sample.tak.DjiObstacleState.Face
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R

/**
 * Obstacle proximity drawn as arcs on the edges of the FPV, one per aircraft face.
 *
 * The visual language is deliberately the conventional one, modelled on manufacturer flight
 * apps after a wall strike: an indication bowing in from the edge nearest the obstacle must mean
 * the same thing here as everywhere else a pilot has seen it. Amber at moderate range, red when
 * close, distance printed on the indicator.
 *
 * **Forward is drawn here, deliberately.** A common argument omits a front indicator on the
 * reasoning that an obstacle dead ahead is already in the video. That holds for an aircraft
 * that senses six ways and still has five indicators left. An aircraft that senses forward,
 * backward, up and down and has NO lateral sensors (the Air 2S, for one) would be left with a
 * blank display in the one direction it actually flies. Forward therefore gets a readout — a
 * captioned chevron pointing up-and-away, mirroring REAR, so the two read as a pair and neither
 * can be confused with the up/down arcs.
 *
 * **Up and down are bands on the top and bottom edges, NEAR ONLY** — the indicator shows only
 * when the hazard is very near. The same wash as the sides, so an edge band always means "the
 * hazard is off that edge" and a chevron always means fore/aft; they start at [VERT_NEAR_M],
 * not [WARN_M], because the downward sensor reads the ground at every low hover and the upward
 * one reads a ceiling indoors — a 39 ft start would paint both edges for most of a flight. The
 * feed is the same millimetre contract as the ring.
 *
 * **Units need no assumption here.** DJI reports metres by API contract. Feet for display is
 * one conversion at the point of drawing.
 *
 * Draws inside the VISIBLE part of [videoRect] — under the fill-crop the rect can overflow the
 * view at the sides, and an arc drawn at a negative x is an arc nobody sees. The video is
 * pillarboxed to the left so the HUD and mini-map can own the right strip, and an arc on the
 * view's right edge would sit under the instrument column instead of on the picture. Same rect
 * [CrosshairView] uses.
 */
class ObstacleEdgeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // Flight-tuned HUD colours, resolved once per view from the tokens in takpilot_colors.xml.
    private val COLOR_DANGER = ContextCompat.getColor(context, R.color.tp_hud_obstacle_danger)
    private val COLOR_WARN = ContextCompat.getColor(context, R.color.tp_hud_obstacle_warn)

    private val videoRect = RectF()

    /** Fed from [FpvTextureView.onVideoRectChanged], exactly as the crosshair is. */
    fun setVideoRect(rect: RectF) {
        videoRect.set(rect)
        invalidate()
    }

    /** Nearest obstacle per face, in METRES. Empty hides everything. */
    private var faces: Map<Face, Float> = emptyMap()

    fun update(newFaces: Map<Face, Float>) {
        faces = newFaces
        invalidate()
    }

    fun clear() {
        faces = emptyMap()
        invalidate()
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    /** Fills the gradient band. The shader carries the colour; [Paint.setAlpha] modulates it. */
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * ⚠ TWO SHADERS, BUILT ONCE, REUSED FOR EVER. Each is a
     * unit-length horizontal ramp for one colour; the LOCAL MATRIX stretches it to the band's
     * depth and points it at the right edge. Rebuilding a LinearGradient per face per frame
     * would allocate on a view that redraws at the sensor's push rate. Three stops, not two:
     * a straight ramp to transparent BANDS visibly over live video; the middle stop bends the
     * falloff so the dense part hugs the edge and the tail fades out of notice.
     */
    private val washShaders = HashMap<Int, android.graphics.LinearGradient>(2)
    private val washMatrix = android.graphics.Matrix()

    /**
     * ⚠ AMBER ON RED, NOT THE HUD'S WHITE ON BLACK. Every other
     * readout is white text with a black outline, and the obstacle distance read as one more
     * of them — on the one display whose job is to be told apart at a glance. The pair is
     * FIXED and does not follow the state: the band behind it carries amber-or-red already.
     * Sized with the warning banner's text (this panel's 11sp, by proportion), so the two
     * warning texts on the screen are one size.
     */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_hud_obstacle_warn)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = resources.getDimension(R.dimen.flight_warning_text_size)
    }
    private val textOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_hud_obstacle_danger)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.hud_text_outline_width)
        strokeJoin = Paint.Join.ROUND
        textSize = resources.getDimension(R.dimen.flight_warning_text_size)
    }

    // Reused across draws. This view redraws at the sensor's push rate, so onDraw must not
    // allocate: the chevron Path is reset and refilled, and one FontMetrics is filled in place
    // (the `fontMetrics` property allocates a fresh object on every read).
    private val chevronPath = Path()

    override fun onDraw(canvas: Canvas) {
        if (videoRect.isEmpty || faces.isEmpty()) return
        faces[Face.LEFT]?.let { drawEdge(canvas, it, Side.LEFT) }
        faces[Face.RIGHT]?.let { drawEdge(canvas, it, Side.RIGHT) }
        // Forward and rear as a matched chevron pair — see the class note.
        faces[Face.NOSE]?.let { drawChevron(canvas, it, forward = true) }
        faces[Face.TAIL]?.let { drawChevron(canvas, it, forward = false) }
        faces[Face.UP]?.let { drawVertical(canvas, it, top = true) }
        faces[Face.DOWN]?.let { drawVertical(canvas, it, top = false) }
    }

    /**
     * Up or down, as a wash from the top or bottom edge — the side band turned through 90°,
     * depth as a fraction of the HEIGHT, starting at [VERT_NEAR_M]. The top band starts at the
     * TRUE edge and its extent is the chrome inset PLUS the depth: the depth alone is
     * smaller than the toolbar at the far threshold, and a band wholly behind the toolbar would
     * show nothing. The label clears the chrome. Captioned, because an edge band with no
     * caption could be read as a side one.
     */
    private fun drawVertical(canvas: Canvas, meters: Float, top: Boolean) {
        if (meters > VERT_NEAR_M) return
        val t = (1f - (meters / VERT_NEAR_M)).coerceIn(0f, 1f)
        val danger = meters <= DANGER_M
        val color = if (danger) COLOR_DANGER else COLOR_WARN
        val w = width.toFloat()
        val h = height.toFloat()
        val depth = h * (MIN_DEPTH_FRAC + (MAX_DEPTH_FRAC - MIN_DEPTH_FRAC) * t * t) +
            (if (top) topInset else 0f)
        washPaint.shader = washShader(danger, color).also { sh ->
            washMatrix.reset()
            // The unit ramp runs along x; turn it to run along y, from the edge inward.
            washMatrix.setScale(depth, 1f)
            if (top) washMatrix.postRotate(90f)
            else { washMatrix.postRotate(-90f); washMatrix.postTranslate(0f, h) }
            sh.setLocalMatrix(washMatrix)
        }
        washPaint.alpha = (MIN_WASH_ALPHA + (MAX_WASH_ALPHA - MIN_WASH_ALPHA) * t).toInt()
        val cx = videoRect.centerX()
        val cy: Float
        if (top) {
            canvas.drawRect(0f, 0f, w, depth, washPaint)
            cy = topInset + dp(VERT_LABEL_INSET)
        } else {
            canvas.drawRect(0f, h - depth, w, h, washPaint)
            cy = h - dp(VERT_LABEL_INSET)
        }
        washPaint.shader = null
        arcPaint.color = color
        drawLabel(canvas, cx, cy, meters, if (top) "UP " else "DOWN ")
    }

    private enum class Side { LEFT, RIGHT }

    /**
     * One side face, as a GRADIENT bleeding in from its edge rather than the arc it replaced.
     *
     * Two things move with the distance and they are the whole design: DEPTH — the band
     * reaches further toward the centre as the obstacle gets closer, the part a pilot reads
     * without looking — and OPACITY. Colour keeps the flight-validated meaning the arc had:
     * amber beyond [DANGER_M], red inside it, a hard step rather than a blend, because "you
     * are now close" is a different statement from "something is there".
     *
     * ⚠ DEPTH RUNS ON t SQUARED (measured). A linear ramp spent its travel in the far
     * field — 5 % of the screen at 39 ft to 19 % at 13 ft, then only 19 % to 25 % across the
     * ENTIRE danger range, so 7 ft and 1.6 ft read as the same severity. Squaring puts the
     * travel where a pilot needs it. ALPHA STAYS LINEAR: the far-field hint must still show.
     *
     * The band spans the WHOLE edge of the VIEW, not 0.62 of the video rect: a wash needs no
     * ends to read, a full edge says "this side" plainly, and from the view edge it looks like
     * it comes from outside the frame — which under the fill-crop it does. The depth is a
     * fraction of the width, so it reads the same on every screen.
     */
    private fun drawEdge(canvas: Canvas, meters: Float, side: Side) {
        if (meters > WARN_M) return                      // nothing worth showing
        val t = (1f - (meters / WARN_M)).coerceIn(0f, 1f)
        val danger = meters <= DANGER_M
        val color = if (danger) COLOR_DANGER else COLOR_WARN
        val w = width.toFloat()
        val h = height.toFloat()
        val depth = w * (MIN_DEPTH_FRAC + (MAX_DEPTH_FRAC - MIN_DEPTH_FRAC) * t * t)
        washPaint.shader = washShader(danger, color).also { sh ->
            washMatrix.reset()
            when (side) {
                Side.LEFT -> washMatrix.setScale(depth, 1f)
                Side.RIGHT -> { washMatrix.setScale(-depth, 1f); washMatrix.postTranslate(w, 0f) }
            }
            sh.setLocalMatrix(washMatrix)
        }
        washPaint.alpha = (MIN_WASH_ALPHA + (MAX_WASH_ALPHA - MIN_WASH_ALPHA) * t).toInt()
        val cx: Float
        when (side) {
            Side.LEFT -> {
                canvas.drawRect(0f, 0f, depth, h, washPaint)
                // Beside the actions column when it stands here; at the edge otherwise.
                cx = maxOf(dp(LABEL_INSET), leftInset + dp(28f))
            }
            Side.RIGHT -> {
                canvas.drawRect(w - depth, 0f, w, h, washPaint)
                cx = w - dp(LABEL_INSET)
            }
        }
        washPaint.shader = null
        // The chevrons still use arcPaint's colour for their own label passes — set it here
        // so a side label drawn through drawLabel carries the band's colour semantics too.
        arcPaint.color = color
        drawLabel(canvas, cx, h / 2f, meters)
    }

    /** Cached per colour — see [washShaders]. */
    private fun washShader(danger: Boolean, color: Int): android.graphics.LinearGradient =
        washShaders.getOrPut(if (danger) 1 else 0) {
            android.graphics.LinearGradient(
                0f, 0f, 1f, 0f,
                intArrayOf(color, color and 0x00FFFFFF or (0x66 shl 24), color and 0x00FFFFFF),
                floatArrayOf(0f, MID_STOP, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }

    /**
     * Forward and rear proximity, as captioned chevrons rather than edge arcs.
     *
     * Neither direction has an honest edge to live on: the top edge means UP and the bottom edge
     * means DOWN, so hanging fore/aft on them would make two different hazards look identical on
     * a display whose whole job is to be read at a glance. A chevron is a shape nothing else here
     * uses — pointing away from the aircraft in the direction of the hazard, captioned so it can
     * never be misread. Rear was added after flying without it: behind the aircraft is the one
     * direction the camera cannot show, which is exactly where a readout earns the most.
     */
    /**
     * How far the top of this view's drawing may reach — the toolbar's height, set from the
     * activity's layout listener. A proximity warning the pilot cannot see is worse than none.
     *
     * ⚠ On today's geometry this CHANGES NOTHING: FORE_DROP (96dp) already puts the forward
     * chevrons about 57dp clear of the 56dp toolbar. That clearance was accidental — a magic
     * constant with no stated relationship to the toolbar — and this makes it a guarantee, so
     * a taller toolbar or a smaller FORE_DROP cannot silently hide a proximity warning.
     */
    private var topInset = 0f

    /** The actions column's right edge: the LEFT-face label draws beside the column rather than
     *  under it. The arc itself still draws from the video's edge — the wash is a warning, not
     *  a readout. */
    private var leftInset = 0f

    fun setLeftInset(px: Float) {
        if (leftInset == px) return
        leftInset = px
        invalidate()
    }

    fun setTopInset(px: Float) {
        if (topInset == px) return
        topInset = px
        invalidate()
    }

    private fun drawChevron(canvas: Canvas, meters: Float, forward: Boolean) {
        if (meters > WARN_M) return

        val t = (1f - (meters / WARN_M)).coerceIn(0f, 1f)
        arcPaint.style = Paint.Style.STROKE
        arcPaint.color = if (meters <= DANGER_M) COLOR_DANGER else COLOR_WARN
        arcPaint.alpha = (110 + 145 * t).toInt().coerceAtMost(255)
        arcPaint.strokeWidth = dp(4f) + dp(5f) * t

        val cx = videoRect.centerX()
        // The forward stack extends about 31dp above cy and the label rides at cy, so cy must
        // stay at least the stack's height below the inset for every part to be visible.
        val cy = if (forward) maxOf(videoRect.top + dp(FORE_DROP), topInset + dp(36f))
                 else videoRect.bottom - dp(REAR_LIFT)

        // Two stacked chevrons. Forward points UP (away from the pilot, into the scene); rear
        // points DOWN-AND-BACK. The opposite sense is what makes the pair instantly readable.
        val half = dp(20f)
        val drop = dp(9f)
        for (i in 0 until 2) {
            val yBase = if (forward) cy - dp(13f) - i * dp(9f) else cy + dp(13f) + i * dp(9f)
            val tip = if (forward) yBase - drop else yBase + drop
            chevronPath.reset()
            chevronPath.moveTo(cx - half, yBase)
            chevronPath.lineTo(cx, tip)
            chevronPath.lineTo(cx + half, yBase)
            canvas.drawPath(chevronPath, arcPaint)
        }

        drawLabel(canvas, cx, cy, meters, if (forward) "FWD " else "REAR ")
    }

    /** Distance in feet, amber outlined in red — see [textPaint]. The pill it sat on went
     *  with the arcs: outlined text needs no box measured around it. */
    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, meters: Float, caption: String = "") {
        val text = caption + "%.0fft".format(Locale.US, meters * FEET_PER_METRE)
        canvas.drawText(text, cx, cy, textOutline)
        canvas.drawText(text, cx, cy, textPaint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        /** Start drawing at this range, go red at this one. METRES — no inference needed, the
         *  SDK documents the unit. ~39 ft and ~13 ft. */
        private const val WARN_M = 12f
        private const val DANGER_M = 4f
        /** Up/down start here, not at WARN_M — see the class note. 3 m ≈ 10 ft: a hover at
         *  head height does not warn, a drift toward a ceiling or the ground does. */
        private const val VERT_NEAR_M = 3f
        /** dp from the top/bottom edge to the vertical label's baseline. */
        private const val VERT_LABEL_INSET = 18f

        // Precomputed so onDraw never runs Color.parseColor (a string parse + allocation) per
        // face per frame. Red inside DANGER_M, amber beyond it.

        private const val FEET_PER_METRE = 3.28084f

        /** dp in from the video's top/bottom edges for the fore/aft chevrons. The rear figure
         *  clears the bottom arc's maximum bow plus its label; the forward one clears the
         *  toolbar. */
        private const val REAR_LIFT = 118f
        private const val FORE_DROP = 96f
        /** The wash: depth as a fraction of the width, opacity 0..255. */
        private const val MIN_DEPTH_FRAC = 0.05f
        private const val MAX_DEPTH_FRAC = 0.26f
        private const val MIN_WASH_ALPHA = 50f
        private const val MAX_WASH_ALPHA = 185f
        private const val MID_STOP = 0.5f
        /** dp from the view edge to the side label's centre. */
        private const val LABEL_INSET = 34f
    }
}
