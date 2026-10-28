package com.example.gtrspeedometer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen dashboard in the style of the Nissan GT-R R35 cluster.
 *
 * Everything is drawn in a fixed virtual space of [VW] x [VH] units that is
 * scaled uniformly to fit the screen, so the layout looks the same on every
 * phone held upright.
 */
class DashboardView(context: Context) : View(context) {

    companion object {
        const val VW = 800f
        const val VH = 1720f
        const val MAX_SPEED = 300f

        private const val GAUGE_START = 135f
        private const val GAUGE_SWEEP = 270f

        private val RED = Color.rgb(225, 6, 0)
        private val RED_BRIGHT = Color.rgb(255, 48, 32)
        private val GREY_TEXT = Color.rgb(150, 156, 166)
        private val PANEL_FILL = Color.argb(200, 14, 16, 20)
        private val PANEL_STROKE = Color.rgb(48, 53, 62)
    }

    // ---- Live data, set from MainActivity ----
    var targetSpeed = 0f
    var maxSpeed = 0f
    var distanceKm = 0.0
    var tripMillis = 0L
    var altitude: Double? = null
    var latitude: Double? = null
    var longitude: Double? = null
    var hasFix = false
    var satellitesUsed = 0
    var gForceX = 0f
    var gForceY = 0f
    var batteryPercent = 100

    var onMapClick: (() -> Unit)? = null
    var onResetTrip: (() -> Unit)? = null

    private var shownSpeed = 0f
    private var lastFrameNanos = 0L

    // ---- Layout (virtual units) ----
    private val gCx = 400f
    private val gCy = 592f
    private val gR = 340f

    private val gfCx = 205f
    private val gfCy = 1150f
    private val gfR = 150f

    private val batCx = 595f
    private val batCy = 1150f
    private val batR = 150f

    private val statsRect = RectF(40f, 1370f, 760f, 1530f)
    private val altRect = RectF(40f, 1550f, 390f, 1636f)
    private val mapRect = RectF(420f, 1556f, 760f, 1630f)

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    // ---- Paints ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val typeRegular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val typeBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val typeBoldItalic = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)

    private val path = Path()
    private val rect = RectF()

    // Shaders that live in virtual space can be built once.
    private val bezelShader = SweepGradient(
        gCx, gCy,
        intArrayOf(
            Color.rgb(90, 92, 98), Color.rgb(235, 236, 240), Color.rgb(70, 72, 78),
            Color.rgb(200, 202, 208), Color.rgb(60, 62, 68), Color.rgb(225, 226, 230),
            Color.rgb(90, 92, 98)
        ),
        null
    )
    private val faceShader: Shader = run {
        // Woven carbon fibre tile.
        val tile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        c.drawColor(Color.rgb(16, 17, 19))
        val p = Paint()
        p.color = Color.rgb(28, 29, 32)
        c.drawRect(0f, 0f, 8f, 8f, p)
        c.drawRect(8f, 8f, 16f, 16f, p)
        p.color = Color.rgb(36, 37, 41)
        c.drawRect(0f, 0f, 8f, 2f, p)
        c.drawRect(8f, 8f, 10f, 16f, p)
        BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val faceVignette = RadialGradient(
        gCx, gCy, gR,
        intArrayOf(Color.argb(40, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(235, 0, 0, 0)),
        floatArrayOf(0f, 0.7f, 1f),
        Shader.TileMode.CLAMP
    )
    private val bgShader = RadialGradient(
        VW / 2, VH / 2, VW * 0.65f,
        intArrayOf(Color.rgb(24, 25, 29), Color.rgb(8, 9, 11)),
        null,
        Shader.TileMode.CLAMP
    )

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val x = (e.x - offX) / scale
            val y = (e.y - offY) / scale
            if (mapRect.contains(x, y)) {
                onMapClick?.invoke()
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            val x = (e.x - offX) / scale
            val y = (e.y - offY) / scale
            if (statsRect.contains(x, y)) onResetTrip?.invoke()
        }
    })

    init {
        keepScreenOn = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w / VW, h / VH)
        offX = (w - VW * scale) / 2f
        offY = (h - VH * scale) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        animateNeedle()

        canvas.drawColor(Color.rgb(8, 9, 11))
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)

        fill.shader = bgShader
        canvas.drawRect(0f, 0f, VW, VH, fill)
        fill.shader = null

        drawHeader(canvas)
        drawGForce(canvas)
        drawAltitude(canvas)
        drawBottomTrim(canvas)
        drawSpeedometer(canvas)
        drawBatteryGauge(canvas)
        drawStats(canvas)
        drawMapButton(canvas)

        canvas.restore()

        if (shownSpeed != targetSpeed) postInvalidateOnAnimation()
    }

    /** Eases the needle toward the latest GPS speed so it sweeps instead of jumping. */
    private fun animateNeedle() {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        val k = 1f - Math.exp((-dt * 6f).toDouble()).toFloat()
        shownSpeed += (targetSpeed - shownSpeed) * k
        if (kotlin.math.abs(targetSpeed - shownSpeed) < 0.05f) shownSpeed = targetSpeed
    }

    // ---------------------------------------------------------------- header

    private fun drawHeader(canvas: Canvas) {
        val clock = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
        text.typeface = typeBold
        text.textSize = 34f
        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(clock, 48f, 58f, text)

        // Satellite icon
        canvas.save()
        canvas.translate(64f, 106f)
        canvas.rotate(-45f)
        fill.color = Color.rgb(220, 222, 228)
        canvas.drawRect(-6f, -8f, 6f, 8f, fill)
        canvas.drawRect(-22f, -5f, -9f, 5f, fill)
        canvas.drawRect(9f, -5f, 22f, 5f, fill)
        canvas.restore()

        text.textSize = 32f
        canvas.drawText("GPS Speedometer", 96f, 118f, text)
        val titleEnd = 96f + text.measureText("GPS Speedometer")

        // GPS badge
        val ok = hasFix
        val badgeColor = if (ok) Color.rgb(46, 204, 64) else Color.rgb(240, 160, 30)
        val label = if (ok) "GPS" else "กำลังค้นหา GPS"
        text.textSize = 22f
        val bw = text.measureText(label) + 62f
        rect.set(titleEnd + 18f, 90f, titleEnd + 18f + bw, 128f)
        fill.color = Color.argb(60, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor))
        canvas.drawRoundRect(rect, 19f, 19f, fill)
        stroke.color = badgeColor
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(rect, 19f, 19f, stroke)
        text.color = badgeColor
        canvas.drawText(label, rect.left + 14f, 117f, text)
        val bars = when {
            !ok -> 0
            satellitesUsed >= 10 -> 4
            satellitesUsed >= 7 -> 3
            satellitesUsed >= 5 -> 2
            else -> 1
        }
        for (i in 0 until 4) {
            fill.color = if (i < bars) badgeColor else Color.argb(80, 255, 255, 255)
            val bx = rect.right - 40f + i * 7f
            canvas.drawRect(bx, 120f - (i + 1) * 5f, bx + 4f, 120f, fill)
        }

        // Location pin + coordinates
        drawPin(canvas, 64f, 170f)
        text.typeface = typeRegular
        text.textSize = 24f
        text.color = GREY_TEXT
        val lat = latitude
        val lon = longitude
        if (lat != null && lon != null) {
            canvas.drawText(String.format(Locale.US, "%.4f° %s", kotlin.math.abs(lat), if (lat >= 0) "N" else "S"), 96f, 164f, text)
            canvas.drawText(String.format(Locale.US, "%.4f° %s", kotlin.math.abs(lon), if (lon >= 0) "E" else "W"), 96f, 194f, text)
        } else {
            canvas.drawText("--.----° N", 96f, 164f, text)
            canvas.drawText("---.----° E", 96f, 194f, text)
        }
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float) {
        fill.color = GREY_TEXT
        path.reset()
        path.addCircle(x, y - 6f, 11f, Path.Direction.CW)
        path.moveTo(x - 10f, y - 1f)
        path.lineTo(x, y + 16f)
        path.lineTo(x + 10f, y - 1f)
        path.close()
        canvas.drawPath(path, fill)
        fill.color = Color.rgb(12, 13, 15)
        canvas.drawCircle(x, y - 6f, 4.5f, fill)
    }

    // --------------------------------------------------------------- G-force

    private fun drawGForce(canvas: Canvas) {
        drawRoundBezel(canvas, gfCx, gfCy, gfR)

        stroke.strokeWidth = 1.5f
        stroke.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(gfCx, gfCy, gfR * 0.43f, stroke)
        canvas.drawCircle(gfCx, gfCy, gfR * 0.86f, stroke)
        stroke.color = Color.argb(35, 255, 255, 255)
        canvas.drawCircle(gfCx, gfCy, gfR * 0.645f, stroke)
        stroke.color = Color.argb(90, 255, 255, 255)
        canvas.drawLine(gfCx - gfR * 0.9f, gfCy, gfCx + gfR * 0.9f, gfCy, stroke)
        canvas.drawLine(gfCx, gfCy - gfR * 0.9f, gfCx, gfCy + gfR * 0.9f, stroke)

        text.typeface = typeBold
        text.textAlign = Paint.Align.CENTER
        text.color = Color.rgb(210, 212, 218)
        text.textSize = 20f
        canvas.drawText("G-FORCE", gfCx, gfCy - gfR - 18f, text)
        text.typeface = typeRegular
        text.textSize = 17f
        text.color = GREY_TEXT
        canvas.drawText("1.0", gfCx + 18f, gfCy - gfR * 0.86f + 18f, text)
        canvas.drawText("0.5", gfCx + 18f, gfCy - gfR * 0.43f + 18f, text)
        canvas.drawText("0.5", gfCx - gfR * 0.43f - 18f, gfCy - 8f, text)
        canvas.drawText("0.5", gfCx + gfR * 0.43f + 18f, gfCy - 8f, text)
        canvas.drawText("1.0", gfCx - gfR * 0.86f + 2f, gfCy - 8f, text)
        canvas.drawText("1.0", gfCx + gfR * 0.86f - 2f, gfCy - 8f, text)

        val reach = gfR * 0.86f
        val dx = gfCx + gForceX.coerceIn(-1.1f, 1.1f) * reach
        val dy = gfCy + gForceY.coerceIn(-1.1f, 1.1f) * reach
        for (i in 4 downTo 1) {
            fill.color = Color.argb(28, 255, 30, 20)
            canvas.drawCircle(dx, dy, 9f + i * 5f, fill)
        }
        fill.color = RED_BRIGHT
        canvas.drawCircle(dx, dy, 10f, fill)
        fill.color = Color.argb(200, 255, 200, 190)
        canvas.drawCircle(dx - 3f, dy - 3f, 3f, fill)

        val g = kotlin.math.sqrt(gForceX * gForceX + gForceY * gForceY)
        text.typeface = typeBold
        text.textSize = 20f
        text.color = Color.WHITE
        canvas.drawText(String.format(Locale.US, "%.2f G", g), gfCx, gfCy + gfR + 36f, text)
        text.textAlign = Paint.Align.LEFT
    }

    /** Chrome ring with a dark face, shared by the small gauges. */
    private fun drawRoundBezel(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = Color.BLACK
        canvas.drawCircle(cx, cy + 4f, r + 10f, fill)
        stroke.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(Color.rgb(220, 222, 228), Color.rgb(70, 72, 78), Color.rgb(170, 172, 178)),
            null, Shader.TileMode.CLAMP
        )
        stroke.strokeWidth = 7f
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.shader = null
        fill.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.rgb(26, 27, 31), Color.rgb(10, 11, 13)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r - 4f, fill)
        fill.shader = null
    }

    // -------------------------------------------------------------- altitude

    private fun drawAltitude(canvas: Canvas) {
        path.reset()
        path.moveTo(altRect.left + 12f, altRect.top)
        path.lineTo(altRect.right - 46f, altRect.top)
        path.lineTo(altRect.right, altRect.bottom)
        path.lineTo(altRect.left + 12f, altRect.bottom)
        path.quadTo(altRect.left, altRect.bottom, altRect.left, altRect.bottom - 12f)
        path.lineTo(altRect.left, altRect.top + 12f)
        path.quadTo(altRect.left, altRect.top, altRect.left + 12f, altRect.top)
        path.close()
        fill.color = PANEL_FILL
        canvas.drawPath(path, fill)
        stroke.color = PANEL_STROKE
        stroke.strokeWidth = 2f
        canvas.drawPath(path, stroke)

        // Mountains
        val bx = altRect.left + 36f
        val by = altRect.bottom - 26f
        fill.color = Color.rgb(170, 176, 188)
        path.reset()
        path.moveTo(bx, by)
        path.lineTo(bx + 22f, by - 34f)
        path.lineTo(bx + 33f, by - 18f)
        path.lineTo(bx + 42f, by - 28f)
        path.lineTo(bx + 60f, by)
        path.close()
        canvas.drawPath(path, fill)

        text.typeface = typeRegular
        text.textSize = 22f
        text.color = GREY_TEXT
        canvas.drawText("ความสูง", altRect.left + 120f, altRect.top + 32f, text)
        text.typeface = typeBold
        text.textSize = 36f
        text.color = Color.WHITE
        val alt = altitude
        canvas.drawText(if (alt != null) "${alt.roundToInt()} m" else "-- m", altRect.left + 120f, altRect.top + 74f, text)
    }

    // ----------------------------------------------------------- bottom trim

    private fun drawBottomTrim(canvas: Canvas) {
        path.reset()
        path.moveTo(20f, 1700f)
        path.lineTo(150f, 1700f)
        path.lineTo(195f, 1662f)
        path.lineTo(605f, 1662f)
        path.lineTo(650f, 1700f)
        path.lineTo(780f, 1700f)

        // Dark carbon strip under the trim line
        fill.shader = faceShader
        canvas.drawRect(150f, 1662f, 650f, VH, fill)
        fill.shader = null

        stroke.strokeCap = Paint.Cap.ROUND
        for (i in 4 downTo 1) {
            stroke.color = Color.argb(30, 255, 20, 10)
            stroke.strokeWidth = i * 5f
            canvas.drawPath(path, stroke)
        }
        stroke.color = RED
        stroke.strokeWidth = 3f
        canvas.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        text.typeface = typeBold
        text.textSize = 22f
        text.color = Color.rgb(170, 174, 182)
        text.letterSpacing = 0.5f
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("NISSAN", 352f, 1700f, text)
        text.letterSpacing = 0f
        drawGtrLogo(canvas, 400f, 1690f, 0.42f)
        text.typeface = typeBoldItalic
        text.textSize = 32f
        text.textAlign = Paint.Align.LEFT
        text.color = Color.rgb(120, 124, 132)
        canvas.drawText("R35", 450f, 1702f, text)
    }

    /** Stylised "GT" over a big red "R", centred on [cx]. */
    private fun drawGtrLogo(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(s, s)
        text.textAlign = Paint.Align.CENTER
        text.typeface = typeBoldItalic
        text.textSkewX = -0.15f

        text.textSize = 70f
        text.color = Color.rgb(225, 228, 234)
        canvas.drawText("GT", 0f, -32f, text)

        text.textSize = 120f
        stroke.color = Color.rgb(215, 218, 224)
        stroke.strokeWidth = 8f
        // Outline first, then the red fill, for a chrome-edged badge.
        val outline = Paint(text)
        outline.style = Paint.Style.STROKE
        outline.strokeWidth = 7f
        outline.color = Color.rgb(215, 218, 224)
        canvas.drawText("R", 0f, 70f, outline)
        text.color = Color.rgb(200, 8, 16)
        canvas.drawText("R", 0f, 70f, text)

        text.textSkewX = 0f
        text.textAlign = Paint.Align.LEFT
        canvas.restore()
    }

    // ------------------------------------------------------------ speedometer

    private fun speedAngle(v: Float) = GAUGE_START + (v.coerceIn(0f, MAX_SPEED) / MAX_SPEED) * GAUGE_SWEEP

    private fun drawSpeedometer(canvas: Canvas) {
        val cx = gCx
        val cy = gCy
        val r = gR

        // Drop shadow and chrome bezel
        fill.color = Color.BLACK
        canvas.drawCircle(cx, cy + 6f, r + 18f, fill)
        stroke.shader = bezelShader
        stroke.strokeWidth = 16f
        canvas.drawCircle(cx, cy, r + 4f, stroke)
        stroke.shader = null
        fill.color = Color.rgb(12, 12, 14)
        canvas.drawCircle(cx, cy, r - 4f, fill)

        // Red illuminated ring
        for (i in 6 downTo 1) {
            stroke.color = Color.argb(26, 255, 20, 10)
            stroke.strokeWidth = 4f + i * 5f
            canvas.drawCircle(cx, cy, r - 14f, stroke)
        }
        stroke.color = RED
        stroke.strokeWidth = 4f
        canvas.drawCircle(cx, cy, r - 14f, stroke)

        // Carbon face with vignette
        val faceR = r - 22f
        fill.shader = faceShader
        canvas.drawCircle(cx, cy, faceR, fill)
        fill.shader = faceVignette
        canvas.drawCircle(cx, cy, faceR, fill)
        fill.shader = null

        // Red-line band
        val bandR = r - 40f
        rect.set(cx - bandR, cy - bandR, cx + bandR, cy + bandR)
        stroke.color = Color.argb(170, 225, 6, 0)
        stroke.strokeWidth = 8f
        canvas.drawArc(rect, speedAngle(260f), speedAngle(300f) - speedAngle(260f), false, stroke)

        // Ticks
        var v = 0
        while (v <= MAX_SPEED.toInt()) {
            val a = Math.toRadians(speedAngle(v.toFloat()).toDouble())
            val (len, w) = when {
                v % 20 == 0 -> 36f to 6f
                v % 10 == 0 -> 24f to 3.5f
                else -> 13f to 2f
            }
            val r1 = r - 34f
            val r2 = r1 - len
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            stroke.color = if (v >= 260) Color.rgb(255, 70, 60) else Color.WHITE
            stroke.strokeWidth = w
            canvas.drawLine(cx + ca * r1, cy + sa * r1, cx + ca * r2, cy + sa * r2, stroke)
            v += 2
        }

        // Numerals
        text.typeface = typeBoldItalic
        text.textAlign = Paint.Align.CENTER
        text.textSize = 34f
        text.textSkewX = -0.12f
        for (n in 0..300 step 20) {
            val a = Math.toRadians(speedAngle(n.toFloat()).toDouble())
            val nr = r - 112f
            text.color = if (n >= 260) Color.rgb(255, 80, 70) else Color.WHITE
            canvas.drawText(n.toString(), cx + cos(a).toFloat() * nr, cy + sin(a).toFloat() * nr + 12f, text)
        }
        text.textSkewX = 0f

        text.typeface = typeRegular
        text.textSize = 26f
        text.color = Color.rgb(220, 222, 228)
        canvas.drawText("km/h", cx, cy - 148f, text)

        drawGtrLogo(canvas, cx, cy - 80f, 0.6f)

        drawSpeedProgress(canvas, cx, cy, r)
        drawDigitalReadout(canvas, cx, cy, faceR)
        drawNeedle(canvas, cx, cy, r)
        text.textAlign = Paint.Align.LEFT
    }

    /** Bright red arc from 0 up to the current speed, just inside the ticks. */
    private fun drawSpeedProgress(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (shownSpeed <= 0.2f) return
        val pr = r - 26f
        rect.set(cx - pr, cy - pr, cx + pr, cy + pr)
        val sweep = speedAngle(shownSpeed) - GAUGE_START
        stroke.strokeCap = Paint.Cap.ROUND
        for (i in 4 downTo 1) {
            stroke.color = Color.argb(40, 255, 40, 20)
            stroke.strokeWidth = 3f + i * 4f
            canvas.drawArc(rect, GAUGE_START, sweep, false, stroke)
        }
        stroke.color = RED_BRIGHT
        stroke.strokeWidth = 3f
        canvas.drawArc(rect, GAUGE_START, sweep, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawDigitalReadout(canvas: Canvas, cx: Float, cy: Float, faceR: Float) {
        canvas.save()
        path.reset()
        path.addCircle(cx, cy, faceR, Path.Direction.CW)
        canvas.clipPath(path)

        val top = cy + 168f
        path.reset()
        path.moveTo(cx - 128f, top)
        path.lineTo(cx + 128f, top)
        path.lineTo(cx + 250f, top + 96f)
        path.lineTo(cx + 250f, cy + faceR + 10f)
        path.lineTo(cx - 250f, cy + faceR + 10f)
        path.lineTo(cx - 250f, top + 96f)
        path.close()
        fill.shader = LinearGradient(
            0f, top, 0f, cy + faceR,
            intArrayOf(Color.rgb(20, 20, 23), Color.rgb(6, 6, 7), Color.rgb(70, 6, 4)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null
        for (i in 3 downTo 1) {
            stroke.color = Color.argb(45, 255, 30, 20)
            stroke.strokeWidth = i * 4f
            canvas.drawPath(path, stroke)
        }
        stroke.color = Color.rgb(200, 20, 10)
        stroke.strokeWidth = 2f
        canvas.drawPath(path, stroke)
        canvas.restore()

        text.textAlign = Paint.Align.CENTER
        text.typeface = typeBold
        text.textSize = 112f
        text.color = Color.WHITE
        canvas.drawText(shownSpeed.roundToInt().toString(), cx, cy + 240f, text)
        text.typeface = typeRegular
        text.textSize = 28f
        canvas.drawText("km/h", cx, cy + 278f, text)
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(speedAngle(shownSpeed))

        val tip = r - 48f
        path.reset()
        path.moveTo(-46f, -7f)
        path.lineTo(tip, -1.5f)
        path.lineTo(tip + 4f, 0f)
        path.lineTo(tip, 1.5f)
        path.lineTo(-46f, 7f)
        path.close()

        for (i in 5 downTo 1) {
            stroke.color = Color.argb(30, 255, 40, 20)
            stroke.strokeWidth = i * 5f
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, stroke)
        }
        fill.shader = LinearGradient(
            0f, -7f, 0f, 7f,
            intArrayOf(Color.rgb(255, 120, 90), Color.rgb(255, 40, 20), Color.rgb(170, 10, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null
        canvas.restore()

        // Hub
        fill.shader = RadialGradient(
            cx - 8f, cy - 8f, 44f,
            intArrayOf(Color.rgb(80, 82, 88), Color.rgb(22, 23, 26), Color.rgb(8, 8, 9)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, 36f, fill)
        fill.shader = null
        stroke.color = Color.rgb(90, 92, 98)
        stroke.strokeWidth = 2f
        canvas.drawCircle(cx, cy, 36f, stroke)
        fill.color = Color.rgb(30, 31, 34)
        canvas.drawCircle(cx, cy, 14f, fill)
    }

    // --------------------------------------------------- battery ("fuel") gauge

    private fun drawBatteryGauge(canvas: Canvas) {
        val cx = batCx
        val cy = batCy
        val r = batR
        drawRoundBezel(canvas, cx, cy, r)

        // E at 120° (lower left), F at 300° (upper right), sweeping over the left side.
        val start = 120f
        val sweep = 180f
        val ar = r - 18f
        rect.set(cx - ar, cy - ar, cx + ar, cy + ar)
        for (i in 4 downTo 1) {
            stroke.color = Color.argb(30, 255, 20, 10)
            stroke.strokeWidth = 4f + i * 4f
            canvas.drawArc(rect, start, sweep, false, stroke)
        }
        stroke.color = RED
        stroke.strokeWidth = 4f
        canvas.drawArc(rect, start, sweep, false, stroke)

        for (i in 0..8) {
            val a = Math.toRadians((start + sweep * i / 8f).toDouble())
            val r1 = r - 26f
            val r2 = r1 - if (i % 4 == 0) 22f else 12f
            stroke.color = if (i == 0) RED_BRIGHT else Color.WHITE
            stroke.strokeWidth = if (i % 4 == 0) 5f else 3f
            canvas.drawLine(
                cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1,
                cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, stroke
            )
        }

        text.typeface = typeBold
        text.textSize = 24f
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        val fa = Math.toRadians(start + sweep - 14.0)
        val ea = Math.toRadians(start + 14.0)
        canvas.drawText("F", cx + cos(fa).toFloat() * (r - 62f), cy + sin(fa).toFloat() * (r - 62f) + 9f, text)
        canvas.drawText("E", cx + cos(ea).toFloat() * (r - 62f), cy + sin(ea).toFloat() * (r - 62f) + 9f, text)

        // Battery icon
        val ix = cx + 44f
        val iy = cy + 6f
        stroke.color = Color.rgb(220, 222, 228)
        stroke.strokeWidth = 3f
        rect.set(ix - 14f, iy - 22f, ix + 14f, iy + 22f)
        canvas.drawRoundRect(rect, 4f, 4f, stroke)
        fill.color = Color.rgb(220, 222, 228)
        canvas.drawRect(ix - 6f, iy - 28f, ix + 6f, iy - 22f, fill)
        val level = batteryPercent.coerceIn(0, 100) / 100f
        fill.color = if (batteryPercent <= 15) RED_BRIGHT else Color.rgb(220, 222, 228)
        canvas.drawRect(ix - 9f, iy + 17f - 34f * level, ix + 9f, iy + 17f, fill)

        text.textSize = 20f
        text.typeface = typeRegular
        text.color = GREY_TEXT
        canvas.drawText("$batteryPercent%", ix, iy + 52f, text)

        // Needle
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(start + sweep * level)
        path.reset()
        path.moveTo(-14f, -3.5f)
        path.lineTo(r - 32f, -1f)
        path.lineTo(r - 32f, 1f)
        path.lineTo(-14f, 3.5f)
        path.close()
        for (i in 3 downTo 1) {
            stroke.color = Color.argb(35, 255, 40, 20)
            stroke.strokeWidth = i * 4f
            canvas.drawPath(path, stroke)
        }
        fill.color = RED_BRIGHT
        canvas.drawPath(path, fill)
        canvas.restore()
        fill.color = Color.rgb(40, 41, 45)
        canvas.drawCircle(cx, cy, 14f, fill)
        stroke.color = Color.rgb(90, 92, 98)
        stroke.strokeWidth = 2f
        canvas.drawCircle(cx, cy, 14f, stroke)
        text.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------ stats panel

    private fun drawStats(canvas: Canvas) {
        fill.color = PANEL_FILL
        canvas.drawRoundRect(statsRect, 14f, 14f, fill)
        stroke.color = PANEL_STROKE
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(statsRect, 14f, 14f, stroke)

        val cellW = statsRect.width() / 2f
        val cellH = statsRect.height() / 2f
        stroke.color = Color.argb(60, 255, 255, 255)
        stroke.strokeWidth = 1.5f
        canvas.drawLine(statsRect.centerX(), statsRect.top + 14f, statsRect.centerX(), statsRect.bottom - 14f, stroke)
        canvas.drawLine(statsRect.left + 18f, statsRect.centerY(), statsRect.right - 18f, statsRect.centerY(), stroke)
        val rows = arrayOf(
            Triple("ความเร็วปัจจุบัน", shownSpeed.roundToInt().toString(), "km/h"),
            Triple("ความเร็วสูงสุด", maxSpeed.roundToInt().toString(), "km/h"),
            Triple("ระยะทางรวม", String.format(Locale.US, "%.1f", distanceKm), "km"),
            Triple("เวลาเดินทาง", formatDuration(tripMillis), "")
        )
        for ((i, row) in rows.withIndex()) {
            val left = statsRect.left + (i % 2) * cellW
            val top = statsRect.top + (i / 2) * cellH
            val iconX = left + 44f
            val iconY = top + cellH / 2f
            drawStatIcon(canvas, i, iconX, iconY)

            text.typeface = typeRegular
            text.textSize = 20f
            text.color = GREY_TEXT
            text.textAlign = Paint.Align.LEFT
            val tx = left + 88f
            canvas.drawText(row.first, tx, top + 30f, text)

            text.typeface = typeBold
            text.textSize = 34f
            text.color = Color.WHITE
            canvas.drawText(row.second, tx, top + 68f, text)
            if (row.third.isNotEmpty()) {
                val w = text.measureText(row.second)
                text.typeface = typeRegular
                text.textSize = 22f
                canvas.drawText(row.third, tx + w + 8f, top + 68f, text)
            }
        }
    }

    private fun drawStatIcon(canvas: Canvas, kind: Int, x: Float, y: Float) {
        stroke.color = Color.rgb(150, 170, 200)
        stroke.strokeWidth = 3f
        stroke.strokeCap = Paint.Cap.ROUND
        when (kind) {
            0 -> { // gauge
                rect.set(x - 20f, y - 20f, x + 20f, y + 20f)
                canvas.drawArc(rect, 140f, 260f, false, stroke)
                canvas.drawLine(x, y + 2f, x + 10f, y - 10f, stroke)
                for (t in 0..4) {
                    val a = Math.toRadians(160.0 + t * 55.0)
                    canvas.drawLine(
                        x + cos(a).toFloat() * 14f, y + sin(a).toFloat() * 14f,
                        x + cos(a).toFloat() * 17f, y + sin(a).toFloat() * 17f, stroke
                    )
                }
            }
            1 -> { // road
                canvas.drawLine(x - 8f, y - 20f, x - 20f, y + 20f, stroke)
                canvas.drawLine(x + 8f, y - 20f, x + 20f, y + 20f, stroke)
                canvas.drawLine(x, y - 16f, x, y - 8f, stroke)
                canvas.drawLine(x, y - 2f, x, y + 6f, stroke)
                canvas.drawLine(x, y + 12f, x, y + 20f, stroke)
            }
            2 -> { // clock
                canvas.drawCircle(x, y, 20f, stroke)
                canvas.drawLine(x, y, x, y - 12f, stroke)
                canvas.drawLine(x, y, x + 9f, y + 4f, stroke)
            }
            else -> { // stopwatch
                canvas.drawCircle(x, y + 3f, 18f, stroke)
                canvas.drawLine(x - 6f, y - 20f, x + 6f, y - 20f, stroke)
                canvas.drawLine(x, y - 20f, x, y - 15f, stroke)
                canvas.drawLine(x, y + 3f, x + 7f, y - 6f, stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    // ------------------------------------------------------------- map button

    private fun drawMapButton(canvas: Canvas) {
        val rr = mapRect.height() / 2f
        fill.color = Color.rgb(16, 19, 25)
        canvas.drawRoundRect(mapRect, rr, rr, fill)
        stroke.color = Color.rgb(60, 70, 88)
        stroke.strokeWidth = 2.5f
        canvas.drawRoundRect(mapRect, rr, rr, stroke)

        // Folded map icon
        val x = mapRect.left + 48f
        val y = mapRect.centerY()
        stroke.color = Color.rgb(170, 190, 220)
        stroke.strokeWidth = 3f
        stroke.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(x - 20f, y - 12f)
        path.lineTo(x - 7f, y - 18f)
        path.lineTo(x + 7f, y - 12f)
        path.lineTo(x + 20f, y - 18f)
        path.lineTo(x + 20f, y + 12f)
        path.lineTo(x + 7f, y + 18f)
        path.lineTo(x - 7f, y + 12f)
        path.lineTo(x - 20f, y + 18f)
        path.close()
        canvas.drawPath(path, stroke)
        canvas.drawLine(x - 7f, y - 18f, x - 7f, y + 12f, stroke)
        canvas.drawLine(x + 7f, y - 12f, x + 7f, y + 18f, stroke)

        text.typeface = typeRegular
        text.textSize = 28f
        text.color = Color.rgb(210, 222, 240)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("แผนที่", mapRect.left + 92f, y + 10f, text)

        val chevX = mapRect.right - 40f
        canvas.drawLine(chevX - 6f, y - 12f, chevX + 6f, y, stroke)
        canvas.drawLine(chevX + 6f, y, chevX - 6f, y + 12f, stroke)
        stroke.strokeJoin = Paint.Join.MITER
    }
}
