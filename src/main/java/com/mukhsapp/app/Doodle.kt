package com.mukhsapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

// The faint pattern behind chats and calls (little hearts, stars, bubbles, notes...),
// drawn by code so it works in both the dark and the light theme.
object Doodle {
    private var cache: Drawable.ConstantState? = null
    private var cacheKey = ""

    fun make(ctx: Context, bg: Int, ink: Int): Drawable {
        val key = "$bg-$ink-" + ctx.resources.displayMetrics.density
        val cs = cache
        if (cs != null && key == cacheKey) return cs.newDrawable()
        val d = ctx.resources.displayMetrics.density
        val size = (300 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * d
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = ink
        val u = 11f * d // half size of one little picture
        // where each little picture goes (x, y in 0..1, which one, turn in degrees)
        val spots = arrayOf(
            floatArrayOf(0.10f, 0.08f, 0f, -12f), floatArrayOf(0.40f, 0.05f, 1f, 8f), floatArrayOf(0.72f, 0.10f, 2f, -8f),
            floatArrayOf(0.25f, 0.24f, 3f, 14f), floatArrayOf(0.58f, 0.26f, 4f, -10f), floatArrayOf(0.90f, 0.28f, 5f, 12f),
            floatArrayOf(0.08f, 0.42f, 6f, 6f), floatArrayOf(0.40f, 0.45f, 7f, -14f), floatArrayOf(0.74f, 0.47f, 8f, 10f),
            floatArrayOf(0.22f, 0.62f, 9f, -6f), floatArrayOf(0.56f, 0.66f, 0f, 16f), floatArrayOf(0.90f, 0.64f, 1f, -12f),
            floatArrayOf(0.08f, 0.82f, 2f, 10f), floatArrayOf(0.40f, 0.86f, 3f, -8f), floatArrayOf(0.74f, 0.88f, 4f, 12f)
        )
        for (s in spots) {
            c.save()
            c.translate(s[0] * size, s[1] * size)
            c.rotate(s[3])
            shape(c, p, s[2].toInt(), u)
            c.restore()
        }
        val bd = BitmapDrawable(ctx.resources, bmp)
        bd.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        cache = bd.constantState
        cacheKey = key
        return bd
    }

    private fun shape(c: Canvas, p: Paint, kind: Int, u: Float) {
        val path = Path()
        when (kind) {
            0 -> { // heart
                path.moveTo(0f, u * 0.8f)
                path.cubicTo(-u * 1.6f, -u * 0.2f, -u * 0.6f, -u * 1.2f, 0f, -u * 0.35f)
                path.cubicTo(u * 0.6f, -u * 1.2f, u * 1.6f, -u * 0.2f, 0f, u * 0.8f)
                c.drawPath(path, p)
            }
            1 -> { // star
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) u else u * 0.42f
                    val a = Math.PI / 5 * i - Math.PI / 2
                    val x = (Math.cos(a) * r).toFloat()
                    val y = (Math.sin(a) * r).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
            }
            2 -> { // chat bubble
                val r = RectF(-u, -u * 0.8f, u, u * 0.55f)
                c.drawRoundRect(r, u * 0.4f, u * 0.4f, p)
                path.moveTo(-u * 0.4f, u * 0.55f)
                path.lineTo(-u * 0.7f, u * 1.05f)
                path.lineTo(0f, u * 0.55f)
                c.drawPath(path, p)
            }
            3 -> { // music note
                c.drawCircle(-u * 0.4f, u * 0.65f, u * 0.38f, p)
                c.drawLine(-u * 0.05f, u * 0.65f, -u * 0.05f, -u * 0.9f, p)
                c.drawLine(-u * 0.05f, -u * 0.9f, u * 0.7f, -u * 0.55f, p)
            }
            4 -> { // phone
                val r = RectF(-u * 0.55f, -u, u * 0.55f, u)
                c.drawRoundRect(r, u * 0.25f, u * 0.25f, p)
                c.drawLine(-u * 0.15f, u * 0.7f, u * 0.15f, u * 0.7f, p)
            }
            5 -> { // camera
                val r = RectF(-u, -u * 0.6f, u, u * 0.7f)
                c.drawRoundRect(r, u * 0.25f, u * 0.25f, p)
                c.drawCircle(0f, u * 0.05f, u * 0.38f, p)
            }
            6 -> { // smile
                c.drawCircle(0f, 0f, u * 0.95f, p)
                c.drawPoint(-u * 0.32f, -u * 0.25f, p)
                c.drawPoint(u * 0.32f, -u * 0.25f, p)
                val r = RectF(-u * 0.5f, -u * 0.35f, u * 0.5f, u * 0.55f)
                c.drawArc(r, 20f, 140f, false, p)
            }
            7 -> { // plus and ring
                c.drawLine(-u * 0.7f, 0f, u * 0.7f, 0f, p)
                c.drawLine(0f, -u * 0.7f, 0f, u * 0.7f, p)
                c.drawCircle(0f, 0f, u * 1.05f, p)
            }
            8 -> { // paper plane
                path.moveTo(-u, 0f)
                path.lineTo(u, -u * 0.8f)
                path.lineTo(u * 0.3f, u * 0.8f)
                path.lineTo(-u * 0.1f, u * 0.15f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(-u * 0.1f, u * 0.15f, u, -u * 0.8f, p)
            }
            else -> { // light bulb
                c.drawCircle(0f, -u * 0.25f, u * 0.65f, p)
                c.drawLine(-u * 0.3f, u * 0.6f, u * 0.3f, u * 0.6f, p)
                c.drawLine(-u * 0.2f, u * 0.95f, u * 0.2f, u * 0.95f, p)
            }
        }
    }
}
