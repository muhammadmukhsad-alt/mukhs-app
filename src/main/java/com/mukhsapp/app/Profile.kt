package com.mukhsapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

// Profile pictures (DP). Key "me" is my own picture; any other key is a person's hash.
object Profile {
    private val cache = HashMap<String, Bitmap>()

    private fun file(ctx: Context, key: String): File = File(ctx.filesDir, "dp_$key.jpg")

    fun bitmap(ctx: Context, key: String): Bitmap? {
        val cached = cache[key]
        if (cached != null) return cached
        val f = file(ctx, key)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        cache[key] = b
        return b
    }

    // Picks a photo, crops it to a square, shrinks it so it is small enough to send nearby.
    fun saveFromUri(ctx: Context, uri: Uri, key: String): Boolean {
        try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > 700 || opts.outHeight / sample > 700) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            var src = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, o2)
            } ?: return false

            var orient = ExifInterface.ORIENTATION_NORMAL
            try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    orient = ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (e: Exception) {
                // no rotation info
            }
            val m = Matrix()
            if (orient == ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90f)
            else if (orient == ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180f)
            else if (orient == ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270f)
            if (!m.isIdentity) src = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)

            val size = minOf(src.width, src.height)
            val sq = Bitmap.createBitmap(src, (src.width - size) / 2, (src.height - size) / 2, size, size)
            val target = minOf(size, 480)
            val out = if (target == size) sq else Bitmap.createScaledBitmap(sq, target, target, true)

            var q = 88
            var bytes = ByteArray(0)
            do {
                val bos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, q, bos)
                bytes = bos.toByteArray()
                q -= 10
            } while (bytes.size > 90000 && q > 30)

            file(ctx, key).writeBytes(bytes)
            cache.remove(key)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun base64(ctx: Context, key: String): String {
        val f = file(ctx, key)
        if (!f.exists()) return ""
        return Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    fun saveBase64(ctx: Context, key: String, data: String) {
        try {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size > 150000) return
            file(ctx, key).writeBytes(bytes)
            cache.remove(key)
        } catch (e: Exception) {
            // ignore a bad picture
        }
    }
}
