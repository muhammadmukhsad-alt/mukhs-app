package com.mukhsapp.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import android.provider.ContactsContract
import android.text.InputType
import android.text.TextUtils
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
import java.security.MessageDigest

// ---------- local database (chat history) ----------

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "mukhs.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT, " +
                "mine INTEGER, body TEXT, ts INTEGER, sent INTEGER)"
        )
        db.execSQL("CREATE TABLE peers(hash TEXT PRIMARY KEY, name TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

data class Msg(val id: Long, val mine: Boolean, val body: String, val sent: Boolean)

class MainActivity : Activity() {

    private val LIME = Color.parseColor("#E6FF61")
    private val FOREST = Color.parseColor("#004538")
    private val DEEP = Color.parseColor("#00221B")
    private val MUTED = Color.parseColor("#B8D8CF")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val SERVICE_ID = "com.mukhsapp.app.v1"

    private lateinit var client: ConnectionsClient
    private lateinit var db: Db
    private val handler = Handler(Looper.getMainLooper())

    // identity
    private var myName = ""
    private var myNumber = ""
    private var myHash = ""

    // contacts on this phone: hash -> (name, number)
    private val contacts = HashMap<String, Pair<String, String>>()

    // nearby: endpointId -> hash (only people who are in my contacts)
    private val nearby = LinkedHashMap<String, String>()
    // connected: endpointId -> hash
    private val connected = HashMap<String, String>()
    // connection requests in progress: endpointId -> (hash, name)
    private val pendingConn = HashMap<String, Pair<String, String>>()

    private var searching = false
    private var askedPerms = false
    private var openAfterConnect: String? = null
    private var currentChat: String? = null

    private var statusView: TextView? = null
    private var listBox: LinearLayout? = null
    private var searchBtn: Button? = null
    private var chatBox: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatStatusView: TextView? = null

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

    private fun styledInput(hintText: String, type: Int): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 18f
            background = round(DEEP, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = type
            setSingleLine(true)
        }

    // ---------- phone numbers ----------

    // Turns 0803 123 4567 or +234 803 123 4567 into 2348031234567
    private fun normalize(raw: String): String? {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.substring(2)
        if (d.startsWith("0") && d.length == 11) {
            d = "234" + d.substring(1)
        } else if (d.length == 10) {
            d = "234$d"
        }
        return if (d.length in 11..15) d else null
    }

    private fun hashOf(number: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(("mukhs:$number").toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 6) sb.append(String.format("%02x", bytes[i]))
        return sb.toString()
    }

    private fun loadContacts() {
        Thread {
            val map = HashMap<String, Pair<String, String>>()
            try {
                val cur = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null, null
                )
                if (cur != null) {
                    while (cur.moveToNext()) {
                        val num = normalize(cur.getString(1) ?: "")
                        if (num != null) {
                            map[hashOf(num)] = Pair(cur.getString(0) ?: num, num)
                        }
                    }
                    cur.close()
                }
            } catch (e: Exception) {
                // ignore
            }
            handler.post {
                contacts.clear()
                contacts.putAll(map)
                refreshList()
            }
        }.start()
    }

    // ---------- database helpers ----------

    private fun saveMsg(peer: String, mine: Boolean, body: String, sent: Boolean): Long {
        val cv = ContentValues()
        cv.put("peer", peer)
        cv.put("mine", if (mine) 1 else 0)
        cv.put("body", body)
        cv.put("ts", System.currentTimeMillis())
        cv.put("sent", if (sent) 1 else 0)
        return db.writableDatabase.insert("msgs", null, cv)
    }

    private fun savePeer(hash: String, name: String) {
        db.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO peers(hash, name) VALUES(?, ?)",
            arrayOf<Any?>(hash, name)
        )
    }

    private fun markSent(id: Long) {
        db.writableDatabase.execSQL("UPDATE msgs SET sent=1 WHERE id=?", arrayOf<Any?>(id))
    }

    private fun loadMsgs(peer: String): List<Msg> {
        val out = ArrayList<Msg>()
        val c = db.readableDatabase.rawQuery(
            "SELECT id, mine, body, sent FROM msgs WHERE peer=? ORDER BY id", arrayOf(peer)
        )
        while (c.moveToNext()) {
            out.add(Msg(c.getLong(0), c.getInt(1) == 1, c.getString(2) ?: "", c.getInt(3) == 1))
        }
        c.close()
        return out
    }

    private fun pendingMsgs(peer: String): List<Msg> = loadMsgs(peer).filter { it.mine && !it.sent }

    private fun lastMsgs(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        val c = db.readableDatabase.rawQuery(
            "SELECT peer, body FROM msgs WHERE id IN (SELECT MAX(id) FROM msgs GROUP BY peer) " +
                "ORDER BY id DESC",
            null
        )
        while (c.moveToNext()) {
            m[c.getString(0)] = c.getString(1) ?: ""
        }
        c.close()
        return m
    }

    private fun peerName(h: String): String {
        val fromContacts = contacts[h]
        if (fromContacts != null) return fromContacts.first
        val c = db.readableDatabase.rawQuery("SELECT name FROM peers WHERE hash=?", arrayOf(h))
        var name = "Aboki"
        if (c.moveToFirst()) name = c.getString(0) ?: "Aboki"
        c.close()
        return name
    }

    private fun connectedEndpoint(h: String): String? =
        connected.entries.firstOrNull { it.value == h }?.key

    private fun nearbyEndpoint(h: String): String? =
        nearby.entries.firstOrNull { it.value == h }?.key

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = FOREST
        window.navigationBarColor = FOREST
        client = Nearby.getConnectionsClient(this)
        db = Db(this)
        showSplash()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        client.stopAllEndpoints()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentChat != null) {
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
        val p = getSharedPreferences("mukhs", MODE_PRIVATE)
        myName = p.getString("name", "") ?: ""
        myNumber = p.getString("number", "") ?: ""
        if (myName.isBlank() || myNumber.isBlank()) {
            showSetup()
        } else {
            myHash = hashOf(myNumber)
            showMain()
        }
    }

    // ---------- setup screen (name + phone number) ----------

    private fun showSetup() {
        currentChat = null
        val root = col(FOREST, 28)
        root.gravity = Gravity.CENTER_VERTICAL
        root.addView(txt("Barka da zuwa", 18f, Color.WHITE))
        root.addView(txt("Mu fara da bayananka", 30f, LIME, true), lp(4))
        val nameIn = styledInput("Sunanka", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        nameIn.setText(myName)
        val numIn = styledInput("Lambar wayarka (misali 08031234567)", InputType.TYPE_CLASS_PHONE)
        root.addView(nameIn, lp(24))
        root.addView(numIn, lp(12))
        root.addView(
            txt("Mutane za su gane ka da lambarka. Ba a tura ta ko'ina, a wayarka take.", 13f, MUTED),
            lp(8)
        )
        root.addView(pill("Ci gaba", LIME, FOREST) {
            val n = nameIn.text.toString().trim().replace("|", " ")
            val num = normalize(numIn.text.toString())
            if (n.isEmpty()) {
                toast("Rubuta sunanka tukuna")
            } else if (num == null) {
                toast("Lambar ba ta yi daidai ba. Misali: 08031234567")
            } else {
                myName = n
                myNumber = num
                myHash = hashOf(num)
                getSharedPreferences("mukhs", MODE_PRIVATE).edit()
                    .putString("name", n).putString("number", num).apply()
                showMain()
            }
        }, lp(16))
        setContentView(root)
    }

    // ---------- main screen (chat list) ----------

    private fun showMain() {
        currentChat = null
        chatStatusView = null
        val root = col(DEEP)
        root.addView(txt("MukhsApp", 28f, LIME, true))
        root.addView(txt("Sannu, $myName (+$myNumber)", 15f, Color.WHITE), lp(2))
        val st = txt("Ana shirin nemo...", 14f, MUTED)
        statusView = st
        root.addView(st, lp(10))
        val sb = pill(if (searching) "Daina Nemo" else "Fara Nemo", LIME, FOREST) { toggleSearch() }
        searchBtn = sb
        root.addView(sb, lp(12))
        root.addView(txt("Tattaunawa", 16f, LIME, true), lp(20))
        val lb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = lb
        val sv = ScrollView(this)
        sv.addView(lb)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        setContentView(root)
        refreshList()

        if (hasPerms()) {
            if (contacts.isEmpty()) loadContacts()
            if (!searching) {
                startSearch()
            } else {
                setStatus("Ana nemo...")
            }
        } else if (!askedPerms) {
            askedPerms = true
            requestPermissions(requiredPerms().toTypedArray(), 1)
        } else {
            setStatus("Ana buƙatar izini. Danna \"Fara Nemo\".")
        }
    }

    private fun refreshList() {
        val box = listBox ?: return
        box.removeAllViews()
        val last = lastMsgs()
        val order = LinkedHashSet<String>()
        for (h in nearby.values) order.add(h)
        for (h in connected.values) order.add(h)
        for (h in last.keys) order.add(h)
        if (order.isEmpty()) {
            box.addView(
                txt(
                    "Babu kowa tukuna. Ka tabbata kana da lambar mutum a contacts ɗinka, " +
                        "kuma yana da MukhsApp a buɗe kusa da kai.",
                    14f, MUTED
                )
            )
            return
        }
        for (h in order) {
            val isConn = connectedEndpoint(h) != null
            val online = isConn || nearbyEndpoint(h) != null
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(FOREST, 16)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { openPeer(h) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(txt(peerName(h), 18f, Color.WHITE, true))
            val subText = if (isConn) "● an haɗa" else if (online) "● kusa" else (last[h] ?: "")
            val sub = txt(subText, 13f, if (online) LIME else MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            info.addView(sub)
            row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(txt("›", 26f, LIME, true))
            box.addView(row, lp(8))
        }
    }

    // ---------- permissions ----------

    private fun requiredPerms(): List<String> {
        val l = mutableListOf<String>()
        l.add(Manifest.permission.READ_CONTACTS)
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
        if (hasPerms()) {
            loadContacts()
            if (!searching) startSearch()
        } else {
            toast("Dole ka ba da duk izinin (contacts, Bluetooth, Location) don app ɗin ya yi aiki.")
        }
    }

    // ---------- nearby ----------

    private fun toggleSearch() {
        if (searching) {
            stopSearch()
            return
        }
        if (hasPerms()) {
            loadContacts()
            startSearch()
        } else {
            requestPermissions(requiredPerms().toTypedArray(), 1)
        }
    }

    private fun advName(): String = "$myHash|$myName"

    private fun startSearch() {
        nearby.clear()
        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val dis = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(advName(), SERVICE_ID, connCallback, adv)
            .addOnFailureListener { setStatus("Kuskure (advertise): ${it.message}") }
        client.startDiscovery(SERVICE_ID, discCallback, dis)
            .addOnFailureListener { setStatus("Kuskure (discover): ${it.message}") }
        searching = true
        searchBtn?.text = "Daina Nemo"
        setStatus("Ana nemo... ka tabbata Bluetooth, Wi-Fi da Location a kunne suke.")
        refreshList()
    }

    private fun stopSearch() {
        client.stopAdvertising()
        client.stopDiscovery()
        searching = false
        nearby.clear()
        searchBtn?.text = "Fara Nemo"
        setStatus("An daina nemo.")
        refreshList()
    }

    private fun openPeer(h: String) {
        if (connectedEndpoint(h) != null) {
            showChat(h)
            return
        }
        val ep = nearbyEndpoint(h)
        if (ep == null) {
            showChat(h)
            return
        }
        openAfterConnect = h
        setStatus("Ana haɗawa da ${peerName(h)}...")
        client.requestConnection(advName(), ep, connCallback)
            .addOnFailureListener {
                openAfterConnect = null
                toast("Haɗawa ta kasa: ${it.message}")
            }
    }

    private fun flushPending(h: String) {
        val ep = connectedEndpoint(h) ?: return
        val list = pendingMsgs(h)
        if (list.isEmpty()) return
        for (m in list) {
            client.sendPayload(ep, Payload.fromBytes(m.body.toByteArray(Charsets.UTF_8)))
            markSent(m.id)
        }
        if (currentChat == h) showChat(h)
    }

    private fun parseAdv(name: String): Pair<String, String>? {
        val i = name.indexOf('|')
        if (i <= 0) return null
        return Pair(name.substring(0, i), name.substring(i + 1))
    }

    private val discCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val p = parseAdv(info.endpointName) ?: return
            val h = p.first
            if (h == myHash) return
            // Only people whose number is in MY contacts are shown.
            if (!contacts.containsKey(h)) return
            nearby[endpointId] = h
            savePeer(h, p.second)
            refreshList()
            if (currentChat == h) updateChatStatus()
        }

        override fun onEndpointLost(endpointId: String) {
            val h = nearby.remove(endpointId)
            refreshList()
            if (h != null && currentChat == h) updateChatStatus()
        }
    }

    private val connCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val p = parseAdv(info.endpointName)
            if (p == null) {
                client.rejectConnection(endpointId)
                return
            }
            pendingConn[endpointId] = p
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val p = pendingConn.remove(endpointId)
            if (result.status.isSuccess && p != null) {
                val h = p.first
                connected[endpointId] = h
                savePeer(h, p.second)
                flushPending(h)
                if (openAfterConnect == h) {
                    openAfterConnect = null
                    showChat(h)
                } else {
                    toast("${peerName(h)} ya haɗu da kai")
                    refreshList()
                    if (currentChat == h) updateChatStatus()
                }
            } else {
                openAfterConnect = null
                toast("Haɗawa ta kasa. Gwada kuma.")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val h = connected.remove(endpointId)
            nearby.remove(endpointId)
            if (h != null) {
                refreshList()
                if (currentChat == h) updateChatStatus()
            }
            if (searching) {
                // look again so the person can be found and reconnected
                client.stopDiscovery()
                client.startDiscovery(
                    SERVICE_ID,
                    discCallback,
                    DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
                )
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val h = connected[endpointId] ?: return
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val text = String(bytes, Charsets.UTF_8)
            saveMsg(h, false, text, true)
            if (currentChat == h) {
                addMsg(false, text, true)
            } else {
                toast("${peerName(h)}: $text")
                refreshList()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ---------- chat ----------

    private fun chatStatusText(h: String): String =
        if (connectedEndpoint(h) != null) "● an haɗa"
        else if (nearbyEndpoint(h) != null) "● kusa (ba a haɗa ba)"
        else "ba ya kusa"

    private fun updateChatStatus() {
        val h = currentChat ?: return
        chatStatusView?.text = chatStatusText(h)
    }

    private fun showChat(h: String) {
        currentChat = h
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DEEP)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(FOREST)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(txt(peerName(h), 20f, LIME, true))
        val cs2 = txt(chatStatusText(h), 12f, MUTED)
        chatStatusView = cs2
        titleBox.addView(cs2)
        header.addView(titleBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(pill("Fita", LIME, FOREST) { showMain() })
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
                if (t.isNotEmpty()) {
                    val ep = connectedEndpoint(h)
                    if (ep != null) {
                        client.sendPayload(ep, Payload.fromBytes(t.toByteArray(Charsets.UTF_8)))
                        saveMsg(h, true, t, true)
                        addMsg(true, t, true)
                    } else {
                        saveMsg(h, true, t, false)
                        addMsg(true, t, false)
                    }
                    input.setText("")
                }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
        )
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        val history = loadMsgs(h)
        if (history.isEmpty()) addNote("Babu saƙo tukuna. Ku fara magana!")
        for (m in history) addMsg(m.mine, m.body, m.sent)
        if (connectedEndpoint(h) == null) {
            addNote("Ba a haɗa ba. Saƙonnin da ka rubuta za a tura su idan kun haɗu.")
        }
    }

    private fun addNote(s: String) {
        val box = chatBox ?: return
        val v = txt(s, 13f, MUTED).apply { gravity = Gravity.CENTER }
        box.addView(v, lp(6))
    }

    private fun addMsg(mine: Boolean, s: String, sent: Boolean) {
        val box = chatBox ?: return
        val shown = if (mine && !sent) "$s  ⏳" else s
        val v = txt(shown, 16f, if (mine) FOREST else Color.WHITE).apply {
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
