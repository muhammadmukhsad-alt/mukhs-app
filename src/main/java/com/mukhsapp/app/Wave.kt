package com.mukhsapp.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

// The bars of a voice note, like WhatsApp: the part already played is solid,
// the rest is faded, and a dot shows where you are. Drag it to move forward or back.
class WaveView(ctx: Context, seed: Int) : View(ctx) {
    var max = 1
    var progress = 0
        set(v) {
            field = v
            invalidate()
        }
    var tone = 0xFF1B5E20.toInt()

    // 0 = finger down, 1 = moving, 2 = finger up
    var onTouchSeek: ((Int, Float) -> Unit)? = null

    private val heights = FloatArray(BARS)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var dragging = false

    init {
        val rnd = java.util.Random(seed.toLong())
        for (i in 0 until BARS) heights[i] = 0.25f + rnd.nextFloat() * 0.75f
    }

    private fun frac(x: Float): Float {
        val f = x / Math.max(1, width).toFloat()
        return if (f < 0f) 0f else if (f > 1f) 1f else f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                progress = (frac(e.x) * max).toInt()
                onTouchSeek?.invoke(0, frac(e.x))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    progress = (frac(e.x) * max).toInt()
                    onTouchSeek?.invoke(1, frac(e.x))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    progress = (frac(e.x) * max).toInt()
                    onTouchSeek?.invoke(2, frac(e.x))
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onTouchSeek?.invoke(2, progress.toFloat() / Math.max(1, max))
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dot = h * 0.2f
        val usable = w - dot * 2
        val slot = usable / BARS
        val bw = slot * 0.55f
        val f = progress.toFloat() / Math.max(1, max)
        val playedX = dot + usable * f
        for (i in 0 until BARS) {
            val x = dot + slot * i + (slot - bw) / 2
            val bh = Math.max(bw, h * 0.78f * heights[i])
            p.color = if (x + bw / 2 <= playedX) tone else ((tone and 0x00FFFFFF) or 0x66000000)
            r.set(x, (h - bh) / 2, x + bw, (h + bh) / 2)
            c.drawRoundRect(r, bw / 2, bw / 2, p)
        }
        p.color = tone
        c.drawCircle(playedX, h / 2, dot, p)
    }

    companion object {
        const val BARS = 32
    }
}
