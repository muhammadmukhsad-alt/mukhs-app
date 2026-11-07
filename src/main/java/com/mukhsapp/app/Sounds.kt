package com.mukhsapp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Build

// Two different message sounds: one when I send, another when a message arrives.
object Sounds {
    private var pool: SoundPool? = null
    private var sentId = 0
    private var recvId = 0

    fun init(ctx: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
        sentId = p.load(ctx, R.raw.msg_sent, 1)
        recvId = p.load(ctx, R.raw.msg_received, 1)
        pool = p
    }

    fun sent() {
        pool?.play(sentId, 1f, 1f, 1, 0, 1f)
    }

    fun received() {
        pool?.play(recvId, 1f, 1f, 1, 0, 1f)
    }
}

// Notification shown when a message arrives while the app is not on screen.
object Notifier {
    private const val CH = "messages"

    private fun soundUri(ctx: Context): Uri =
        Uri.parse("android.resource://" + ctx.packageName + "/" + R.raw.msg_received)

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) != null) return
        val ch = NotificationChannel(CH, "Messages", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(
            soundUri(ctx),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        nm.createNotificationChannel(ch)
    }

    fun cancel(ctx: Context, key: String) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(key.hashCode())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun message(ctx: Context, title: String, text: String, key: String) {
        try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val intent = Intent(ctx, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH)
            else Notification.Builder(ctx)
            b.setContentTitle(title)
            b.setContentText(text)
            b.setSmallIcon(R.drawable.ic_notif)
            b.setAutoCancel(true)
            b.setContentIntent(pi)
            if (Build.VERSION.SDK_INT < 26) {
                b.setSound(soundUri(ctx))
                b.setPriority(Notification.PRIORITY_HIGH)
            }
            nm.notify(key.hashCode(), b.build())
        } catch (e: Exception) {
            // notifications are optional
        }
    }
}
