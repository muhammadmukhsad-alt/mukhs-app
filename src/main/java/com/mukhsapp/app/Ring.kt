package com.mukhsapp.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

// The ring around a picture when the person has a status: one piece for each status.
// Soft green = not seen yet, grey = already seen.
class RingView(ctx: Context) : View(ctx) {
    var total = 1
    var unviewed = 1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val sw = width * 0.055f
        p.strokeWidth = sw
        rect.set(sw / 2, sw / 2, width - sw / 2, height - sw / 2)
        val n = Math.max(1, total)
        val gap = if (n == 1) 0f else 14f
        val step = 360f / n
        for (i in 0 until n) {
            p.color = if (i >= n - unviewed) C.RING_NEW else C.RING_SEEN
            c.drawArc(rect, -90f + i * step + gap / 2, step - gap, false, p)
        }
    }
}
