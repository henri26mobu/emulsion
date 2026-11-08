package dev.rangermobu.emulsion.kotlin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/** Éditeur de courbes : 4 canaux (L, R, V, B) × 3 points déplaçables. Écrit directement dans [params] (clés cL1..cB3). */
class CurveView(c: Context, private val params: () -> FloatArray, private val onChange: () -> Unit) : View(c) {
    private val tabs = listOf(0xFFE8ECE9, 0xFFE5534B, 0xFF4BC26B, 0xFF4B8CE5).map { it.toInt() }
    private var ch = 0
    private var drag = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tab = 30f * resources.displayMetrics.density
    private fun idx(k: Int) = SLIDERS.indexOfFirst { it.key == "${CURVE_CH[ch]}${k + 1}" }
    private fun off(k: Int) = params()[idx(k)]
    private fun bump(x: Float, c: Float) = (1f - Math.abs(x - c) * 4f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    private fun y(x: Float) = x + off(0) * bump(x, .25f) + off(1) * bump(x, .5f) + off(2) * bump(x, .75f)

    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, s + tab.toInt()) }

    private fun box() = minOf(width.toFloat(), height - tab)

    override fun onDraw(cv: Canvas) {
        val s = box()
        p.style = Paint.Style.FILL
        for (i in tabs.indices) { p.color = tabs[i]; p.alpha = if (i == ch) 255 else 80; cv.drawCircle(tab * (i + .7f), tab / 2, tab / 3, p) }
        p.alpha = 255; p.color = 0xFF1F2422.toInt(); cv.drawRect(0f, tab, s, tab + s, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0xFF3A413E.toInt()
        cv.drawLine(0f, tab + s, s, tab, p)
        p.color = tabs[ch]; p.strokeWidth = 3f
        val path = Path()
        for (i in 0..64) { val x = i / 64f; val yy = y(x).coerceIn(0f, 1f); if (i == 0) path.moveTo(x * s, tab + (1 - yy) * s) else path.lineTo(x * s, tab + (1 - yy) * s) }
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL
        for (k in 0..2) { val x = .25f * (k + 1); cv.drawCircle(x * s, tab + (1 - (x + off(k))) * s, 10f, p) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = box()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                if (e.y < tab) { ch = (e.x / tab).toInt().coerceIn(0, 3); invalidate(); return true }
                drag = (0..2).minByOrNull { Math.abs(e.x / s - .25f * (it + 1)) } ?: -1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
        if (drag >= 0 && e.actionMasked != MotionEvent.ACTION_UP) {
            val yy = 1f - (e.y - tab) / s
            params()[idx(drag)] = (yy - .25f * (drag + 1)).coerceIn(-.5f, .5f)
            invalidate(); onChange()
        }
        return true
    }
}
