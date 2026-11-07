package com.mukhsapp.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

class MainActivity : Activity() {

    private val LIME = Color.parseColor("#E6FF61")
    private val FOREST = Color.parseColor("#004538")
    private val DEEP = Color.parseColor("#00221B")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val SERVICE_ID = "com.mukhsapp.app.v1"

    private lateinit var client: ConnectionsClient
    private val handler = Handler(Looper.getMainLooper())
    private val peers = LinkedHashMap<String, String>()
    private val pendingNames = HashMap<String, String>()
    private var myName = ""
    private var searching = false
    private var connectedId: String? = null
    private var connectedName = ""

    private var statusView: TextView? = null
    private var listBox: LinearLayout? = null
    private var searchBtn: Button? = null
    private var chatBox: LinearLayout? = null
    private var chatScroll: ScrollView? = null

    // ---------- helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int = 16): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

    private fun txt(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(fg)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = round(bg, 28)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { onClick() }
        }

    private fun col(bg: Int, pad: Int = 20): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun setStatus(s: String) {
        statusView?.text = s
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = FOREST
        window.navigationBarColor = FOREST
        client = Nearby.getConnectionsClient(this)
        showSplash()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        client.stopAllEndpoints()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val id = connectedId
        if (id != null) {
            client.disconnectFromEndpoint(id)
            connectedId = null
            showMain()
        } else {
            super.onBackPressed()
        }
    }

    // ---------- splash (animation + sound) ----------

    private fun showSplash() {
        val root = FrameLayout(this).apply { setBackgroundColor(FOREST) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val logo = TextView(this).apply {
            text = "M"
            textSize = 56f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FOREST)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(LIME)
            }
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
        }
        val title = txt("MukhsApp", 40f, LIME, true).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(24) }
        }
        val tag = txt("Magana. Kira. Kusa.", 18f, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) }
        }
        box.addView(logo)
        box.addView(title)
        box.addView(tag)
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        logo.alpha = 0f
        logo.scaleX = 0.3f
        logo.scaleY = 0.3f
        title.alpha = 0f
        title.translationY = dp(30).toFloat()
        tag.alpha = 0f

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).start()
        title.animate().alpha(1f).translationY(0f).setStartDelay(500).setDuration(600).start()
        tag.animate().alpha(1f).setStartDelay(1000).setDuration(600).start()

        playChime()
        handler.postDelayed({ afterSplash() }, 2600)
    }

    private fun playChime() {
        Thread {
            try {
                val sr = 22050
                val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
                val noteLen = sr * 160 / 1000
                val samples = ShortArray(noteLen * notes.size)
                var idx = 0
                for (f in notes) {
                    for (i in 0 until noteLen) {
                        val env = Math.min(1.0, Math.min(i / 200.0, (noteLen - i) / 900.0))
                        val v = Math.sin(2.0 * Math.PI * f * i / sr) * env * 0.5
                        samples[idx++] = (v * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(160L * notes.size + 300L)
                track.release()
            } catch (e: Exception) {
                // sound is optional
            }
        }.start()
    }

    private fun afterSplash() {
        val saved = getSharedPreferences("mukhs", MODE_PRIVATE).getString("name", "") ?: ""
        if (saved.isBlank()) {
            showNameScreen()
        } else {
            myName = saved
            showMain()
        }
    }

    // ---------- name screen ----------

    private fun showNameScreen() {
        val root = col(FOREST, 28)
        root.gravity = Gravity.CENTER_VERTICAL
        root.addView(txt("Barka da zuwa", 18f, Color.WHITE))
        root.addView(txt("Mene ne sunanka?", 30f, LIME, true), lp(4))
        val input = EditText(this).apply {
            hint = "Rubuta sunanka"
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 18f
            background = round(DEEP, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
        }
        root.addView(input, lp(24))
        root.addView(pill("Ci gaba", LIME, FOREST) {
            val n = input.text.toString().trim()
            if (n.isEmpty()) {
                toast("Rubuta sunanka tukuna")
            } else {
                myName = n
                getSharedPreferences("mukhs", MODE_PRIVATE).edit().putString("name", n).apply()
                showMain()
            }
        }, lp(16))
        setContentView(root)
    }

    // ---------- main screen ----------

    private fun showMain() {
        val root = col(DEEP)
        root.addView(txt("MukhsApp", 28f, LIME, true))
        root.addView(txt("Sannu, $myName", 16f, Color.WHITE), lp(2))
        val st = txt("Danna \"Fara Nemo\" don gano mutane kusa da kai.", 14f, Color.parseColor("#B8D8CF"))
        statusView = st
        root.addView(st, lp(12))
        val sb = pill(if (searching) "Daina Nemo" else "Fara Nemo", LIME, FOREST) { toggleSearch() }
        searchBtn = sb
        root.addView(sb, lp(14))
        root.addView(txt("Mutanen da aka gano", 16f, LIME, true), lp(24))
        val lb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = lb
        val sv = ScrollView(this)
        sv.addView(lb)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        setContentView(root)
        refreshList()
    }

    private fun refreshList() {
        val box = listBox ?: return
        box.removeAllViews()
        if (peers.isEmpty()) {
            box.addView(txt("Ba a gano kowa ba tukuna.", 14f, Color.parseColor("#B8D8CF")))
            return
        }
        for ((id, name) in peers) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(FOREST, 16)
                setPadding(dp(16), dp(10), dp(12), dp(10))
            }
            row.addView(
                txt(name, 18f, Color.WHITE, true),
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            row.addView(pill("Haɗa", LIME, FOREST) { connectTo(id) })
            box.addView(row, lp(8))
        }
    }

    // ---------- permissions ----------

    private fun requiredPerms(): List<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT <= 32) {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return l
    }

    private fun hasPerms(): Boolean =
        requiredPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) startSearch() else toast("Dole ka ba da izini don a gano mutane kusa.")
    }

    // ---------- nearby ----------

    private fun toggleSearch() {
        if (searching) {
            stopSearch()
            return
        }
        if (hasPerms()) startSearch() else requestPermissions(requiredPerms().toTypedArray(), 1)
    }

    private fun startSearch() {
        peers.clear()
        refreshList()
        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val dis = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(myName, SERVICE_ID, connCallback, adv)
            .addOnFailureListener { setStatus("Kuskure (advertise): ${it.message}") }
        client.startDiscovery(SERVICE_ID, discCallback, dis)
            .addOnFailureListener { setStatus("Kuskure (discover): ${it.message}") }
        searching = true
        searchBtn?.text = "Daina Nemo"
        setStatus("Ana nemo... ka tabbata Bluetooth, Wi-Fi da Location a kunne suke a wayoyin biyu.")
    }

    private fun stopSearch() {
        client.stopAdvertising()
        client.stopDiscovery()
        searching = false
        searchBtn?.text = "Fara Nemo"
        setStatus("An daina nemo.")
    }

    private fun connectTo(id: String) {
        setStatus("Ana haɗawa...")
        client.requestConnection(myName, id, connCallback)
            .addOnFailureListener { toast("Haɗawa ta kasa: ${it.message}") }
    }

    private val discCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            peers[endpointId] = info.endpointName
            refreshList()
        }

        override fun onEndpointLost(endpointId: String) {
            peers.remove(endpointId)
            refreshList()
        }
    }

    private val connCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedId = endpointId
                connectedName = pendingNames[endpointId] ?: "Aboki"
                stopSearch()
                showChat()
            } else {
                toast("Haɗawa ta kasa. Gwada kuma.")
                setStatus("Haɗawa ta kasa.")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId == connectedId) {
                connectedId = null
                toast("An yanke haɗin.")
                showMain()
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()
                if (bytes != null) addMsg(false, String(bytes, Charsets.UTF_8))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ---------- chat ----------

    private fun showChat() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DEEP)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(FOREST)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        header.addView(txt(connectedName, 20f, LIME, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(pill("Fita", LIME, FOREST) { onBackPressed() })
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val cb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        chatBox = cb
        val cs = ScrollView(this)
        cs.addView(cb)
        chatScroll = cs
        root.addView(cs, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val input = EditText(this).apply {
            hint = "Rubuta saƙo"
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            background = round(FOREST, 24)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(
            pill("Tura", LIME, FOREST) {
                val t = input.text.toString().trim()
                val id = connectedId
                if (t.isNotEmpty() && id != null) {
                    client.sendPayload(id, Payload.fromBytes(t.toByteArray(Charsets.UTF_8)))
                    addMsg(true, t)
                    input.setText("")
                }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
        )
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        addNote("An haɗa da $connectedName. Ku fara magana!")
    }

    private fun addNote(s: String) {
        val box = chatBox ?: return
        val v = txt(s, 13f, Color.parseColor("#B8D8CF")).apply { gravity = Gravity.CENTER }
        box.addView(v, lp(6))
    }

    private fun addMsg(mine: Boolean, s: String) {
        val box = chatBox ?: return
        val v = txt(s, 16f, if (mine) FOREST else Color.WHITE).apply {
            background = round(if (mine) LIME else FOREST, 18)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
        }
        val params = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(6)
            gravity = if (mine) Gravity.END else Gravity.START
        }
        box.addView(v, params)
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }
} 
