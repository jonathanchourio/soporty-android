package com.soporty.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.sin

/**
 * Dibuja sobre todas las apps el puntero y los trazos que hace el técnico.
 * Requiere el permiso «Mostrar sobre otras apps». No recibe toques: el cliente usa su teléfono normal.
 * Coordenadas normalizadas 0..1 respecto a la pantalla capturada (igual que en la versión web).
 */
object MarksOverlay {
    private class Stroke(val color: Int, val width: Float, val pts: ArrayList<FloatArray> = ArrayList())

    private var view: MarksView? = null
    private val strokes = LinkedHashMap<String, Stroke>()
    private var ptrX = 0f; private var ptrY = 0f; private var ptrAt = 0L; private var ptrColor = Color.parseColor("#C96442")

    fun canShow(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun show(ctx: Context) {
        if (view != null || !canShow(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = MarksView(ctx.applicationContext)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        // Desde Android 12 los toques solo atraviesan una capa encima de otras apps si su opacidad es ≤ 0.8
        lp.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { wm.addView(v, lp); view = v } catch (e: Exception) { view = null }
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        view = null
        strokes.clear()
    }

    /** Aplica un mensaje de control del técnico. Devuelve true si era una marca. Llamar en el hilo principal. */
    fun apply(m: JSONObject): Boolean {
        when (m.optString("t")) {
            "ptr" -> {
                ptrX = m.optDouble("x", 0.0).toFloat(); ptrY = m.optDouble("y", 0.0).toFloat()
                ptrAt = SystemClock.uptimeMillis()
                m.optString("c").takeIf { it.isNotEmpty() }?.let { ptrColor = parse(it) }
            }
            "stroke" -> {
                val id = m.optString("id")
                val s = strokes.getOrPut(id) { Stroke(parse(m.optString("c", "#E03C31")), m.optDouble("w", 4.0).toFloat()) }
                val p = m.optJSONArray("p")
                if (p != null) for (i in 0 until p.length()) {
                    val xy = p.optJSONArray(i) ?: continue
                    s.pts.add(floatArrayOf(xy.optDouble(0).toFloat(), xy.optDouble(1).toFloat()))
                }
            }
            "clear" -> { strokes.clear(); ptrAt = 0 }
            else -> return false
        }
        view?.invalidate()
        return true
    }

    private fun parse(c: String) = try { Color.parseColor(c) } catch (e: Exception) { Color.RED }

    @SuppressLint("ViewConstructor")
    private class MarksView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            for (s in strokes.values) {
                if (s.pts.isEmpty()) continue
                paint.color = s.color
                paint.strokeWidth = s.width * max(0.6f, w / 1000f) * resources.displayMetrics.density
                path.reset()
                s.pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p[0] * w, p[1] * h) else path.lineTo(p[0] * w, p[1] * h) }
                if (s.pts.size == 1) path.lineTo(s.pts[0][0] * w + 0.5f, s.pts[0][1] * h)
                canvas.drawPath(path, paint)
            }
            val age = SystemClock.uptimeMillis() - ptrAt
            if (ptrAt > 0 && age < 2500) {
                val d = resources.displayMetrics.density
                val a = 1f - age / 2500f
                val x = ptrX * w; val y = ptrY * h
                val r = (14 + 6 * sin(age / 120.0)).toFloat() * d
                fill.color = ptrColor; fill.alpha = (64 * a).toInt(); canvas.drawCircle(x, y, r + 8 * d, fill)
                paint.color = ptrColor; paint.alpha = (255 * a).toInt(); paint.strokeWidth = 3 * d; canvas.drawCircle(x, y, r, paint)
                paint.alpha = 255
                fill.alpha = (255 * a).toInt(); canvas.drawCircle(x, y, 4 * d, fill)
                postInvalidateOnAnimation()
            }
        }
    }
}
