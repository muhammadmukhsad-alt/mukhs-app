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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.Manifest
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

interface CallUi {
    fun onCallChanged()
    fun onRemoteFrame(b: Bitmap)
    fun onLocalFrame(b: Bitmap)
}

class CallEntry(val h: String, val ts: Long, val incoming: Boolean, val video: Boolean, val result: String, val dur: Int)

// Voice and video calls over the same Nearby link the messages use.
// Sound: 8 kHz, squeezed to one byte per sample (mu-law), 40 ms per packet.
// Video: a small JPEG picture about 8 times a second.
@Suppress("DEPRECATION")
object Calls {
    const val IDLE = 0
    const val OUT = 1
    const val IN = 2
    const val LIVE = 3

    @Volatile
    var state = IDLE
    var peer = ""
    var video = false
    var incoming = false
    var muted = false
    var speaker = false
    var startedAt = 0L

    @Volatile
    var ui: CallUi? = null

    private var callId = ""
    private val main = Handler(Looper.getMainLooper())
    private var ring: Ringtone? = null
    private var tone: ToneGenerator? = null

    @Volatile
    private var media = false
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private val queue = ArrayBlockingQueue<ShortArray>(20)
    private var camera: Camera? = null
    private var lastVideo = 0L
    @Volatile private var awaitingAck = false
    private var ackSince = 0L
    private var ctxRef: Context? = null
    private var camThread: android.os.HandlerThread? = null
    private val decoding = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadExecutor()
    private const val NOTIF = 7003

    private val ringTimeout = Runnable {
        val c = Hub.app()
        if (c != null && (state == OUT || state == IN)) hangup(c)
    }

    // ---------- starting and answering ----------

    fun dial(ctx: Context, h: String, vid: Boolean): Boolean {
        val net = Hub.net(ctx)
        if (state != IDLE || !net.isConnected(h)) return false
        peer = h
        video = vid
        incoming = false
        muted = false
        speaker = vid
        callId = System.currentTimeMillis().toString()
        state = OUT
        net.sendSignal(h, JSONObject().put("t", "cv").put("k", if (vid) "v" else "a").put("id", callId))
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
        } catch (e: Throwable) {
            tone = null
        }
        main.postDelayed(ringTimeout, 45000)
        CallService.start(ctx, vid)
        ui?.onCallChanged()
        return true
    }

    fun accept(ctx: Context) {
        if (state != IN) return
        main.removeCallbacks(ringTimeout)
        stopRing()
        cancelNotice(ctx)
        Hub.net(ctx).sendSignal(peer, JSONObject().put("t", "cy").put("id", callId))
        CallService.start(ctx, video)
        goLive(ctx)
    }

    // I end it (or say no to an incoming call)
    fun hangup(ctx: Context) {
        if (state == IDLE) return
        Hub.net(ctx).sendSignal(peer, JSONObject().put("t", "cn").put("id", callId))
        val res = if (state == IN) "declined" else if (state == OUT) "cancelled" else "done"
        finish(ctx, res)
    }

    fun onPeerLost(h: String) {
        val c = Hub.app() ?: return
        if (state != IDLE && h == peer) finish(c, if (state == LIVE) "done" else "missed")
    }

    fun onSignal(h: String, j: JSONObject) {
        val ctx = Hub.app() ?: return
        val id = j.optString("id")
        when (j.optString("t")) {
            "cv" -> {
                if (state != IDLE) {
                    Hub.net(ctx).sendSignal(h, JSONObject().put("t", "cn").put("id", id))
                    return
                }
                peer = h
                video = j.optString("k") == "v"
                incoming = true
                muted = false
                speaker = video
                callId = id
                state = IN
                startRing(ctx)
                showNotice(ctx)
                main.postDelayed(ringTimeout, 45000)
                ui?.onCallChanged()
            }
            "cy" -> {
                if (state == OUT && id == callId) {
                    main.removeCallbacks(ringTimeout)
                    stopTone()
                    goLive(ctx)
                }
            }
            "cn" -> {
                if (state != IDLE && id == callId) {
                    val res = if (state == IN) "missed" else if (state == OUT) "declined" else "done"
                    finish(ctx, res)
                }
            }
        }
    }

    private fun goLive(ctx: Context) {
        state = LIVE
        startedAt = System.currentTimeMillis()
        startMedia(ctx)
        ui?.onCallChanged()
    }

    private fun finish(ctx: Context, result: String) {
        main.removeCallbacks(ringTimeout)
        stopRing()
        stopTone()
        cancelNotice(ctx)
        val dur = if (state == LIVE) ((System.currentTimeMillis() - startedAt) / 1000).toInt() else 0
        addLog(ctx, CallEntry(peer, System.currentTimeMillis(), incoming, video, result, dur))
        // also write it in the chat with that person ("Missed voice call - Tap to call back")
        try {
            val st = Hub.store(ctx)
            val now = System.currentTimeMillis()
            val body = result + "|" + (if (video) "v" else "a") + "|" + dur
            val uid = "call-" + callId + (if (incoming) "-i" else "-o")
            if (peer.isNotEmpty()) {
                if (incoming) st.insertIn(peer, uid, body, now, result == "missed", "", false, "c", "", dur)
                else st.insertOut(peer, uid, body, now, 1, "", false, "c", "", dur)
            }
        } catch (x: Throwable) {
            // the list of calls still has it
        }
        if (incoming && result == "missed") {
            try {
                Notifier.message(ctx, Hub.net(ctx).nameOf(peer), if (video) "📹 " + L.s("Ba a ɗauki kiran bidiyo ba", "Missed video call") else "📞 " + L.s("Ba a ɗauki kiran murya ba", "Missed voice call"), peer)
            } catch (x: Throwable) {
                // ignore
            }
        }
        stopMedia(ctx)
        state = IDLE
        CallService.stop(ctx)
        ui?.onCallChanged()
    }

    // ---------- sound and picture ----------

    private fun lin2ulaw(sample: Int): Byte {
        var x = sample
        val sign = if (x < 0) {
            x = -x
            0x80
        } else {
            0
        }
        if (x > 32635) x = 32635
        x += 0x84
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (x and mask) == 0) {
            exp--
            mask = mask shr 1
        }
        val man = (x shr (exp + 3)) and 0x0F
        return ((sign or (exp shl 4) or man).inv() and 0xFF).toByte()
    }

    private fun ulaw2lin(b: Byte): Short {
        val u = (b.toInt() and 0xFF).inv() and 0xFF
        val sign = u and 0x80
        val exp = (u shr 4) and 7
        val man = u and 0x0F
        var s = ((man shl 3) + 0x84) shl exp
        s -= 0x84
        return (if (sign != 0) -s else s).toShort()
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        speaker = on
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = on
        } catch (e: Throwable) {
            // ignore
        }
    }

    private fun startMedia(ctx: Context) {
        ctxRef = ctx.applicationContext
        media = true
        queue.clear()
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = speaker
        } catch (e: Throwable) {
            // ignore
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                val minIn = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minIn, 6400)
                )
                r.startRecording()
                rec = r
                val me = peer
                val net = Hub.net(ctx)
                Thread {
                    val pcm = ShortArray(320)
                    while (media) {
                        val n = r.read(pcm, 0, 320)
                        if (n > 0 && !muted) {
                            val f = ByteArray(n + 1)
                            f[0] = 1
                            for (i in 0 until n) f[i + 1] = lin2ulaw(pcm[i].toInt())
                            net.sendBytes(me, f)
                        } else if (n <= 0) {
                            try {
                                Thread.sleep(10)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                }.start()
            } catch (e: Throwable) {
                rec = null
            }
        }
        try {
            val minOut = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack(
                AudioManager.STREAM_VOICE_CALL, 8000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minOut, 6400), AudioTrack.MODE_STREAM
            )
            t.play()
            track = t
            Thread {
                while (media) {
                    val s = try {
                        queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        null
                    }
                    if (s != null) t.write(s, 0, s.size)
                }
            }.start()
        } catch (e: Throwable) {
            track = null
        }
        if (video) startCamera(ctx)
    }

    private fun stopMedia(ctx: Context) {
        media = false
        try {
            rec?.stop()
            rec?.release()
        } catch (e: Throwable) {
            // ignore
        }
        rec = null
        try {
            track?.stop()
            track?.release()
        } catch (e: Throwable) {
            // ignore
        }
        track = null
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (e: Throwable) {
            // ignore
        }
        camera = null
        try {
            camThread?.quitSafely()
        } catch (e: Throwable) {
            // ignore
        }
        camThread = null
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Throwable) {
            // ignore
        }
    }

    private fun startCamera(ctx: Context) {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        awaitingAck = false
        // the camera works on its own thread so pictures keep flowing and the screen stays smooth
        val th = android.os.HandlerThread("callcam")
        th.start()
        camThread = th
        Handler(th.looper).post { openCamera(ctx) }
    }

    private fun openCamera(ctx: Context) {
        try {
            val info = Camera.CameraInfo()
            var id = 0
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    id = i
                    break
                }
            }
            Camera.getCameraInfo(id, info)
            val cam = Camera.open(id)
            val params = cam.parameters
            var best = params.supportedPreviewSizes[0]
            for (sz in params.supportedPreviewSizes) {
                if (Math.abs(sz.width - 240) < Math.abs(best.width - 240)) best = sz
            }
            params.setPreviewSize(best.width, best.height)
            params.previewFormat = ImageFormat.NV21
            cam.parameters = params
            cam.setPreviewTexture(SurfaceTexture(11))
            val w = best.width
            val hh = best.height
            val rot = info.orientation / 90
            val me = peer
            val net = Hub.net(ctx)
            cam.setPreviewCallback { data, _ ->
                val now = System.currentTimeMillis()
                // send the next picture only after the other phone has shown the last one
                // (or after 1.5 s), so the video never falls behind the voice
                if (awaitingAck && now - ackSince > 1500) awaitingAck = false
                if (media && !awaitingAck && now - lastVideo >= 110) {
                    lastVideo = now
                    awaitingAck = true
                    ackSince = now
                    try {
                        val bos = ByteArrayOutputStream()
                        YuvImage(data, ImageFormat.NV21, w, hh, null).compressToJpeg(Rect(0, 0, w, hh), 40, bos)
                        val jpg = bos.toByteArray()
                        val f = ByteArray(jpg.size + 2)
                        f[0] = 2
                        f[1] = rot.toByte()
                        System.arraycopy(jpg, 0, f, 2, jpg.size)
                        net.sendBytes(me, f)
                        val bmp = decode(f, rot, true)
                        if (bmp != null) ui?.onLocalFrame(bmp)
                    } catch (e: Throwable) {
                        // skip this picture
                    }
                }
            }
            cam.startPreview()
            camera = cam
        } catch (e: Throwable) {
            camera = null
        }
    }

    private fun decode(f: ByteArray, rot: Int, mirror: Boolean): Bitmap? {
        val b = BitmapFactory.decodeByteArray(f, 2, f.size - 2) ?: return null
        val m = Matrix()
        if (rot != 0) m.postRotate(rot * 90f)
        if (mirror) m.postScale(-1f, 1f)
        return try {
            Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        } catch (e: Throwable) {
            b
        }
    }

    fun onFrame(h: String, b: ByteArray) {
        if (state != LIVE || h != peer) return
        val kind = b[0].toInt()
        if (kind == 1) {
            val n = b.size - 1
            val s = ShortArray(n)
            for (i in 0 until n) s[i] = ulaw2lin(b[i + 1])
            while (!queue.offer(s)) queue.poll()
        } else if (kind == 3) {
            awaitingAck = false
        } else if (kind == 2 && b.size > 4) {
            // tell the sender this picture arrived, so it can send the next one
            Hub.net(ctxRef ?: return).sendBytes(h, byteArrayOf(3))
            if (!decoding.compareAndSet(false, true)) return
            pool.execute {
                try {
                    val bmp = decode(b, b[1].toInt(), false)
                    if (bmp != null) main.post { ui?.onRemoteFrame(bmp) }
                } finally {
                    decoding.set(false)
                }
            }
        }
    }

    // ---------- ringing ----------

    private fun startRing(ctx: Context) {
        try {
            val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ring = r
        } catch (e: Throwable) {
            ring = null
        }
    }

    private fun stopRing() {
        try {
            ring?.stop()
        } catch (e: Throwable) {
            // ignore
        }
        ring = null
    }

    private fun stopTone() {
        try {
            tone?.stopTone()
            tone?.release()
        } catch (e: Throwable) {
            // ignore
        }
        tone = null
    }

    private fun showNotice(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("calls") == null) {
                val ch = NotificationChannel("calls", "Calls", NotificationManager.IMPORTANCE_HIGH)
                ch.setSound(null, null)
                ch.enableVibration(true)
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                nm.createNotificationChannel(ch)
            }
            val i = Intent(ctx, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("call", true)
            val pi = PendingIntent.getActivity(ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "calls") else Notification.Builder(ctx)
            b.setContentTitle(Hub.net(ctx).nameOf(peer))
            b.setContentText(
                if (video) L.s("Kiran bidiyo ya shigo", "Incoming video call") else L.s("Kiran murya ya shigo", "Incoming voice call")
            )
            b.setSmallIcon(R.drawable.ic_notif)
            b.setCategory(Notification.CATEGORY_CALL)
            b.setOngoing(true)
            b.setContentIntent(pi)
            b.setFullScreenIntent(pi, true)
            nm.notify(NOTIF, b.build())
        } catch (e: Throwable) {
            // notification is optional
        }
    }

    private fun cancelNotice(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF)
        } catch (e: Throwable) {
            // ignore
        }
    }

    // ---------- the list of calls ----------

    private fun addLog(ctx: Context, e: CallEntry) {
        if (e.h.isEmpty()) return
        try {
            val p = ctx.getSharedPreferences("mukhs", Context.MODE_PRIVATE)
            val old = JSONArray(p.getString("calllog", "[]") ?: "[]")
            val out = JSONArray()
            out.put(
                JSONObject().put("h", e.h).put("ts", e.ts).put("in", e.incoming).put("v", e.video)
                    .put("r", e.result).put("d", e.dur)
            )
            for (i in 0 until Math.min(old.length(), 99)) out.put(old.get(i))
            p.edit().putString("calllog", out.toString()).apply()
        } catch (x: Throwable) {
            // ignore
        }
    }

    fun history(ctx: Context): List<CallEntry> {
        val l = ArrayList<CallEntry>()
        try {
            val a = JSONArray(ctx.getSharedPreferences("mukhs", Context.MODE_PRIVATE).getString("calllog", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                l.add(CallEntry(o.getString("h"), o.getLong("ts"), o.getBoolean("in"), o.getBoolean("v"), o.getString("r"), o.getInt("d")))
            }
        } catch (x: Throwable) {
            // ignore
        }
        return l
    }
}

// Keeps the microphone (and camera) allowed while the call is on, even if the screen is switched off.
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val withCam = intent?.getBooleanExtra("cam", false) == true
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel("callsvc") == null) {
            nm.createNotificationChannel(NotificationChannel("callsvc", "Call in progress", NotificationManager.IMPORTANCE_LOW))
        }
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        i.putExtra("call", true)
        val pi = PendingIntent.getActivity(this, 78, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "callsvc") else Notification.Builder(this)
        b.setContentTitle("MukhsApp")
        b.setContentText(L.s("Ana kira…", "Call in progress…"))
        b.setSmallIcon(R.drawable.ic_notif)
        b.setOngoing(true)
        b.setContentIntent(pi)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (withCam) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(7002, b.build(), type)
            } else {
                startForeground(7002, b.build())
            }
        } catch (e: Exception) {
            // keep going without it
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context, video: Boolean) {
            try {
                val i = Intent(ctx, CallService::class.java)
                i.putExtra("cam", video && ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // not allowed right now
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, CallService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
