package com.mukhsapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File

class DocResult(val file: File?, val name: String, val tooBig: Boolean)

// photos, voice notes and documents that are sent inside chats
object Media {
    const val MAX_DOC = 1000000

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "media")
        d.mkdirs()
        return d
    }

    fun fmtDur(sec: Int): String = String.format("%d:%02d", sec / 60, sec % 60)

    // a small version of a picture, good enough for a screen
    fun bitmapFor(path: String, maxPx: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (o.outWidth / sample > maxPx * 2 || o.outHeight / sample > maxPx * 2) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            BitmapFactory.decodeFile(path, o2)
        } catch (e: Exception) {
            null
        }
    }

    // choose a photo: shrink it so it travels quickly between phones
    fun prepareImage(ctx: Context, uri: Uri): File? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / sample > 2600 || o.outHeight / sample > 2600) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: return null
            var deg = 0
            try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    val ex = ExifInterface(it)
                    val orient = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    deg = if (orient == ExifInterface.ORIENTATION_ROTATE_90) 90
                    else if (orient == ExifInterface.ORIENTATION_ROTATE_180) 180
                    else if (orient == ExifInterface.ORIENTATION_ROTATE_270) 270 else 0
                }
            } catch (e: Exception) {
                // no rotation info
            }
            val maxSide = 1280f
            val scale = Math.min(1f, maxSide / Math.max(bmp.width, bmp.height))
            val m = Matrix()
            if (deg != 0) m.postRotate(deg.toFloat())
            if (scale < 1f) m.postScale(scale, scale)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            var q = 82
            var bytes: ByteArray
            do {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                bytes = bos.toByteArray()
                q -= 8
            } while (bytes.size > 250000 && q > 30)
            val f = File(dir(ctx), "img_" + System.currentTimeMillis() + ".jpg")
            f.writeBytes(bytes)
            return f
        } catch (e: Exception) {
            return null
        }
    }

    // choose a document (up to 1 MB for now)
    fun copyDoc(ctx: Context, uri: Uri): DocResult {
        var name = "file"
        try {
            val c = ctx.contentResolver.query(uri, null, null, null, null)
            if (c != null) {
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i) ?: "file"
                }
                c.close()
            }
        } catch (e: Exception) {
            // keep the default name
        }
        try {
            val bos = ByteArrayOutputStream()
            var tooBig = false
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    bos.write(buf, 0, n)
                    if (bos.size() > MAX_DOC) {
                        tooBig = true
                        break
                    }
                }
            }
            if (tooBig) return DocResult(null, name, true)
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val f = File(dir(ctx), "doc_" + System.currentTimeMillis() + "_" + safe)
            f.writeBytes(bos.toByteArray())
            return DocResult(f, name, false)
        } catch (e: Exception) {
            return DocResult(null, name, false)
        }
    }
}
