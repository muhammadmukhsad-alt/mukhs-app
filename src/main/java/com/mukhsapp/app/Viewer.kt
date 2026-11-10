package com.mukhsapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

// A picture that can be pinched, double-tapped and dragged, like in WhatsApp.
// When it is not zoomed, dragging up or down closes it.
class ZoomImageView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val m = Matrix()
    private val v = FloatArray(9)
    private var fit = 1f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastX = 0f
    private var lastY = 0f
    private var pid = 0
    private var dragY = 0f
    private var dismissing = false
    private val slop = (resources.displayMetrics.density * 10).toInt()

    var onTap: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val cur = scaleNow()
            val target = Math.max(fit, Math.min(fit * 6f, cur * detector.scaleFactor))
            val f = target / cur
            m.postScale(f, f, detector.focusX, detector.focusY)
            fix()
            invalidate()
            return true
        }
    })

    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cur = scaleNow()
            if (cur > fit * 1.05f) {
                reset()
            } else {
                val f = (fit * 2.5f) / cur
                m.postScale(f, f, e.x, e.y)
                fix()
            }
            invalidate()
            return true
        }
    })

    fun setBitmap(b: Bitmap) {
        bmp = b
        reset()
        invalidate()
    }

    private fun scaleNow(): Float {
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun reset() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        fit = Math.min(width.toFloat() / b.width, height.toFloat() / b.height)
        m.reset()
        m.postScale(fit, fit)
        m.postTranslate((width - b.width * fit) / 2f, (height - b.height * fit) / 2f)
    }

    // keep the picture inside the screen (or centred when smaller than it)
    private fun fix() {
        val b = bmp ?: return
        m.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val w = b.width * s
        val h = b.height * s
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (w <= width) (width - w) / 2f else Math.max(width - w, Math.min(0f, tx))
        ty = if (h <= height) (height - h) / 2f else Math.max(height - h, Math.min(0f, ty))
        v[Matrix.MTRANS_X] = tx
        v[Matrix.MTRANS_Y] = ty
        m.setValues(v)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        reset()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, m, paint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)
        taps.onTouchEvent(ev)
        val zoomed = scaleNow() > fit * 1.05f
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                pid = ev.getPointerId(0)
                dragY = 0f
                dismissing = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dismissing = false
                translationY = 0f
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val n = if (ev.actionIndex == 0) 1 else 0
                if (n < ev.pointerCount) {
                    pid = ev.getPointerId(n)
                    lastX = ev.getX(n)
                    lastY = ev.getY(n)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount == 1 && !scaler.isInProgress) {
                    val i = ev.findPointerIndex(pid)
                    if (i >= 0) {
                        val dx = ev.getX(i) - lastX
                        val dy = ev.getY(i) - lastY
                        lastX = ev.getX(i)
                        lastY = ev.getY(i)
                        if (zoomed) {
                            m.postTranslate(dx, dy)
                            fix()
                            invalidate()
                        } else {
                            dragY += dy
                            if (!dismissing && Math.abs(dragY) > slop) dismissing = true
                            if (dismissing) translationY = dragY
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dismissing) {
                    if (Math.abs(dragY) > resources.displayMetrics.density * 110) {
                        onDismiss?.invoke()
                    } else {
                        animate().translationY(0f).setDuration(150).start()
                    }
                }
                dismissing = false
                dragY = 0f
            }
        }
        return true
    }
}

// The picture you are about to send: you can draw on it, add text, and turn it.
class EditImageView(ctx: Context) : View(ctx) {
    private class Stroke(val path: Path, val color: Int, val width: Float)
    private class TextItem(val text: String, var x: Float, var y: Float, val color: Int)

    private var base: Bitmap? = null
    private val strokes = ArrayList<Stroke>()
    private val texts = ArrayList<TextItem>()
    private val history = ArrayList<Any>()
    private val mat = Matrix()
    private val inv = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cur: Stroke? = null
    private var dragText: TextItem? = null
    private var rotated = false

    var drawMode = false
    var color = Color.WHITE

    fun setBitmap(b: Bitmap) {
        base = b
        layoutMat()
        invalidate()
    }

    fun isEdited(): Boolean = rotated || history.isNotEmpty()

    private fun sideOf(b: Bitmap): Float = Math.max(b.width, b.height).toFloat()

    private fun layoutMat() {
        val b = base ?: return
        if (width == 0 || height == 0) return
        val s = Math.min(width.toFloat() / b.width, height.toFloat() / b.height)
        mat.reset()
        mat.postScale(s, s)
        mat.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        mat.invert(inv)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutMat()
    }

    private fun drawEdits(canvas: Canvas, b: Bitmap) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        for (s in strokes) {
            paint.color = s.color
            paint.strokeWidth = s.width
            canvas.drawPath(s.path, paint)
        }
        val ts = sideOf(b) * 0.06f
        paint.textSize = ts
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        for (t in texts) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ts * 0.12f
            paint.color = Color.BLACK
            canvas.drawText(t.text, t.x, t.y, paint)
            paint.style = Paint.Style.FILL
            paint.color = t.color
            canvas.drawText(t.text, t.x, t.y, paint)
        }
        paint.isFakeBoldText = false
    }

    override fun onDraw(canvas: Canvas) {
        val b = base ?: return
        canvas.save()
        canvas.concat(mat)
        canvas.drawBitmap(b, 0f, 0f, null)
        drawEdits(canvas, b)
        canvas.restore()
    }

    fun addText(s: String) {
        val b = base ?: return
        val t = TextItem(s, b.width / 2f, b.height / 2f, color)
        texts.add(t)
        history.add(t)
        invalidate()
    }

    fun undo() {
        if (history.isEmpty()) return
        val last = history.removeAt(history.size - 1)
        strokes.removeAll { it === last }
        texts.removeAll { it === last }
        invalidate()
    }

    // draw everything onto a copy of the picture
    fun bake(): Bitmap? {
        val b = base ?: return null
        return try {
            val out = b.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            drawEdits(c, out)
            out
        } catch (e: Exception) {
            null
        }
    }

    fun rotate() {
        val flat = bake() ?: return
        val mx = Matrix()
        mx.postRotate(90f)
        try {
            base = Bitmap.createBitmap(flat, 0, 0, flat.width, flat.height, mx, true)
        } catch (e: Exception) {
            return
        }
        strokes.clear()
        texts.clear()
        history.clear()
        rotated = true
        layoutMat()
        invalidate()
    }

    private fun hitText(x: Float, y: Float, b: Bitmap): TextItem? {
        val ts = sideOf(b) * 0.06f
        paint.textSize = ts
        for (i in texts.indices.reversed()) {
            val t = texts[i]
            val halfW = Math.max(paint.measureText(t.text) / 2f, ts)
            if (Math.abs(x - t.x) < halfW && Math.abs(y - (t.y - ts / 3f)) < ts) return t
        }
        return null
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val b = base ?: return false
        val pts = floatArrayOf(ev.x, ev.y)
        inv.mapPoints(pts)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (drawMode) {
                    val p = Path()
                    p.moveTo(pts[0], pts[1])
                    p.lineTo(pts[0] + 0.01f, pts[1])
                    val s = Stroke(p, color, sideOf(b) * 0.008f)
                    strokes.add(s)
                    history.add(s)
                    cur = s
                    invalidate()
                } else {
                    dragText = hitText(pts[0], pts[1], b)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawMode) {
                    cur?.path?.lineTo(pts[0], pts[1])
                    invalidate()
                } else {
                    val t = dragText
                    if (t != null) {
                        t.x = pts[0]
                        t.y = pts[1]
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cur = null
                dragText = null
            }
        }
        return true
    }
}
