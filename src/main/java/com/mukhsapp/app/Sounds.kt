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
// Looks like the big apps: sender name + photo, the phone's own notification sound,
// pops up on top of the screen, and several messages from one person stack together.
object Notifier {
    private const val CH = "messages3"
    private val lines = HashMap<String, ArrayList<Pair<String, Long>>>()

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.deleteNotificationChannel("messages")
            nm.deleteNotificationChannel("messages2")
        } catch (e: Exception) {
            // ignore
        }
        if (nm.getNotificationChannel(CH) != null) return
        val ch = NotificationChannel(CH, L.s("Saƙonni", "Messages"), NotificationManager.IMPORTANCE_HIGH)
        ch.description = L.s("Sabbin saƙonni daga abokai", "New messages from your friends")
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 300, 150, 300)
        ch.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        ch.enableLights(true)
        ch.lightColor = 0xFFE6FF61.toInt()
        ch.setShowBadge(true)
        ch.setSound(
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        nm.createNotificationChannel(ch)
    }

    fun enabled(ctx: Context): Boolean = try {
        androidx_enabled(ctx)
    } catch (e: Exception) {
        true
    }

    private fun androidx_enabled(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return true
        return nm.areNotificationsEnabled()
    }

    fun cancel(ctx: Context, key: String) {
        lines.remove(key)
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
            intent.putExtra("peer", key)
            val pi = PendingIntent.getActivity(
                ctx, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val now = System.currentTimeMillis()
            val list = lines.getOrPut(key) { ArrayList() }
            list.add(Pair(text, now))
            while (list.size > 7) list.removeAt(0)

            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else Notification.Builder(ctx)
            b.setContentTitle(title)
            b.setContentText(text)
            b.setSmallIcon(R.drawable.ic_notif)
            b.setColor(0xFF004538.toInt())
            b.setCategory(Notification.CATEGORY_MESSAGE)
            b.setVisibility(Notification.VISIBILITY_PRIVATE)
            b.setWhen(now)
            b.setShowWhen(true)
            b.setNumber(list.size)
            b.setOnlyAlertOnce(false)
            b.setGroup("mukhs_msgs")

            var big: android.graphics.Bitmap? = Profile.bitmap(ctx, key)
            if (big == null) {
                try {
                    val o = android.graphics.BitmapFactory.Options()
                    o.inSampleSize = 4
                    big = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_full, o)
                } catch (e: Throwable) {
                    big = null
                }
            }
            if (big != null) b.setLargeIcon(big)

            if (Build.VERSION.SDK_INT >= 28) {
                val pb = android.app.Person.Builder().setName(title).setKey(key)
                if (big != null) pb.setIcon(android.graphics.drawable.Icon.createWithBitmap(big))
                val who = pb.build()
                val st = Notification.MessagingStyle(android.app.Person.Builder().setName(L.s("Ni", "Me")).build())
                for (l in list) st.addMessage(l.first, l.second, who)
                b.setStyle(st)
            } else {
                val ib = Notification.InboxStyle()
                for (l in list) ib.addLine(l.first)
                ib.setBigContentTitle(title)
                b.setStyle(ib)
            }
            b.setAutoCancel(true)
            b.setContentIntent(pi)
            b.setDefaults(Notification.DEFAULT_ALL)
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_HIGH)
            }
            nm.notify(key.hashCode(), b.build())
        } catch (e: Exception) {
            // notifications are optional
        }
    }
}
