package com.mukhsapp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.Manifest

// The one network + database that lives as long as the app process does,
// so messages keep arriving after the screen is closed.
object Hub {
    @Volatile
    var screen: NetListener? = null
    private var appCtx: Context? = null
    private var storeI: Store? = null
    private var netI: Net? = null

    private val proxy = object : NetListener {
        override fun onChanged() {
            screen?.onChanged()
            val c = appCtx
            if (c != null) MukhsService.refresh(c)
        }

        override fun onIncoming(peer: String, msg: Msg) {
            val s = screen
            if (s != null) {
                s.onIncoming(peer, msg)
            } else {
                val c = appCtx ?: return
                val n = netI ?: return
                val muted = c.getSharedPreferences("mukhs", Context.MODE_PRIVATE)
                    .getStringSet("muted", null)?.contains(peer) == true
                if (!muted) Notifier.message(c, n.nameOf(peer), preview(msg), peer)
            }
        }

        override fun onOpenChat(peer: String) {
            screen?.onOpenChat(peer)
        }

        override fun onNotice(text: String) {
            screen?.onNotice(text)
        }
    }

    private fun preview(m: Msg): String =
        if (m.kind == "a") "🎤 " + L.s("Saƙon murya", "Voice message") + " (" + Media.fmtDur(m.dur) + ")"
        else if (m.kind == "i") "📷 " + (if (m.body == "Photo" || m.body.isBlank()) L.s("Hoto", "Photo") else m.body)
        else if (m.kind == "d") "📄 " + m.body
        else m.body

    fun app(): Context? = appCtx

    @Synchronized
    fun store(ctx: Context): Store {
        appCtx = ctx.applicationContext
        var s = storeI
        if (s == null) {
            s = Store(ctx.applicationContext)
            storeI = s
        }
        return s
    }

    @Synchronized
    fun net(ctx: Context): Net {
        appCtx = ctx.applicationContext
        var n = netI
        if (n == null) {
            val c = ctx.applicationContext
            n = Net(c, store(c), proxy)
            val p = c.getSharedPreferences("mukhs", Context.MODE_PRIVATE)
            L.ha = p.getBoolean("ha", false)
            val number = p.getString("number", "") ?: ""
            if (number.isNotBlank()) {
                n.myName = p.getString("name", "") ?: ""
                n.myAbout = p.getString("about", "") ?: ""
                n.myHash = Num.hashOf(number)
            }
            netI = n
        }
        return n
    }

    // used by the background service after Android restarts it
    fun startIfReady(ctx: Context) {
        val n = net(ctx)
        if (n.myHash.isEmpty()) return
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            need.add(Manifest.permission.BLUETOOTH_CONNECT)
            need.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        need.add(Manifest.permission.READ_CONTACTS)
        for (p in need) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return
        }
        if (n.contacts.isEmpty()) n.loadContacts()
        n.start()
    }
}

// Keeps MukhsApp alive in the background with a small quiet notification,
// like WhatsApp does, so people can still find you and messages still arrive.
class MukhsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(this)
        Hub.startIfReady(this)
        return START_STICKY
    }

    private fun goForeground(ctx: Context) {
        val n = build(ctx)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // could not go to the foreground
        }
    }

    companion object {
        private const val ID = 7001
        private const val CH = "service"

        private fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CH) == null) {
                val ch = NotificationChannel(CH, "MukhsApp is running", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val intent = Intent(ctx, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else Notification.Builder(ctx)
            val n = try {
                Hub.net(ctx).connected.size
            } catch (e: Exception) {
                0
            }
            b.setContentTitle("MukhsApp")
            b.setContentText(
                if (n > 0) L.s("An haɗa da mutum $n", "Connected to $n") else L.s("Ana neman abokai a kusa…", "Looking for friends nearby…")
            )
            b.setSmallIcon(R.drawable.ic_notif)
            b.setOngoing(true)
            b.setContentIntent(pi)
            return b.build()
        }

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, MukhsService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // not allowed right now
            }
        }

        fun refresh(ctx: Context) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                nm.notify(ID, build(ctx))
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
