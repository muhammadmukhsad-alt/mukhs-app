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
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), NetListener {

    private lateinit var store: Store
    private lateinit var net: Net
    private val handler = Handler(Looper.getMainLooper())

    private var myName = ""
    private var myNumber = ""
    private var tmpName = ""
    private var tmpNumber = ""

    // 0 = splash/setup, 1 = home (tabs), 2 = chat
    private var screen = 0
    private var tab = 0
    private var currentChat: String? = null
    private var askedPerms = false
    private var userStopped = false

    private var content: FrameLayout? = null
    private var navBar: LinearLayout? = null
    private var chatBox: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatStatusView: TextView? = null
    private val tickViews = HashMap<String, TextView>()
    private var lastDayKey = ""

    private val tabNames = arrayOf("Chats", "Status", "Nearby", "Communities", "Calls")
    private val tabIcons = arrayOf("💬", "⭕", "📡", "👥", "📞")

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.FOREST
        window.navigationBarColor = C.FOREST
        L.ha = getSharedPreferences("mukhs", MODE_PRIVATE).getBoolean("ha", true)
        store = Store(this)
        net = Net(this, store, this)
        showSplash()
    }

    override fun onPause() {
        super.onPause()
        net.foreground = false
    }

    override fun onResume() {
        super.onResume()
        net.foreground = true
        val h = currentChat
        if (screen == 2 && h != null) showChat(h)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        net.stopAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == 2) {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    // ---------- events from the network layer ----------

    override fun onChanged() {
        refreshUi()
    }

    override fun onIncoming(peer: String, msg: Msg) {
        if (screen == 2 && currentChat == peer && net.foreground) {
            appendMsg(msg)
            refreshUi()
        } else {
            toast("${net.nameOf(peer)}: ${msg.body}")
            refreshUi()
        }
    }

    override fun onOpenChat(peer: String) {
        showChat(peer)
    }

    override fun onNotice(text: String) {
        toast(text)
    }

    private fun refreshUi() {
        if (screen == 1) {
            renderTab()
        } else if (screen == 2) {
            updateChatHeader()
            refreshTicks()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- time helpers ----------

    private fun fmtTime(ts: Long): String =
        SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(ts))

    private fun fmtDate(ts: Long): String =
        SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(ts))

    private fun dayStart(ts: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun dayDiff(ts: Long): Int =
        Math.round((dayStart(System.currentTimeMillis()) - dayStart(ts)) / 86400000.0).toInt()

    private fun dayLabel(ts: Long): String {
        val d = dayDiff(ts)
        return if (d == 0) L.s("Yau", "Today") else if (d == 1) L.s("Jiya", "Yesterday") else fmtDate(ts)
    }

    private fun listTime(ts: Long): String {
        val d = dayDiff(ts)
        return if (d == 0) fmtTime(ts)
        else if (d == 1) L.s("Jiya", "Yesterday")
        else SimpleDateFormat("dd/MM/yy", Locale.ENGLISH).format(Date(ts))
    }

    private fun lastSeenText(ts: Long): String {
        val t = fmtTime(ts)
        val d = dayDiff(ts)
        return if (d == 0) L.s("an gan shi yau da $t", "last seen today at $t")
        else if (d == 1) L.s("an gan shi jiya da $t", "last seen yesterday at $t")
        else L.s("an gan shi ${fmtDate(ts)} da $t", "last seen ${fmtDate(ts)} at $t")
    }

    // ---------- small view builders ----------

    private fun styledInput(hintText: String, type: Int): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 18f
            background = round(C.DEEP, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = type
            setSingleLine(true)
        }

    private fun badge(n: Int): TextView =
        TextView(this).apply {
            text = if (n > 99) "99+" else n.toString()
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C.FOREST)
            gravity = Gravity.CENTER
            background = round(C.LIME, 10)
            minWidth = dp(20)
            setPadding(dp(5), dp(1), dp(5), dp(1))
        }

    private fun avatar(name: String, size: Int, online: Boolean): View {
        val fl = FrameLayout(this)
        val palette = intArrayOf(
            Color.parseColor("#2E7D6B"), Color.parseColor("#1B5E4F"),
            Color.parseColor("#3F8F7B"), Color.parseColor("#0F766E"),
            Color.parseColor("#5B8C5A"), Color.parseColor("#4C7A34")
        )
        val bg = palette[(name.hashCode() and 0x7fffffff) % palette.size]
        val trimmed = name.trim()
        val initial = if (trimmed.isEmpty()) "?"
        else String(Character.toChars(trimmed.codePointAt(0))).uppercase()
        val t = TextView(this).apply {
            text = initial
            textSize = size * 0.4f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            }
        }
        fl.addView(t, FrameLayout.LayoutParams(dp(size), dp(size)))
        if (online) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(C.LIME)
                    setStroke(dp(2), C.FOREST)
                }
            }
            fl.addView(dot, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM or Gravity.END))
        }
        return fl
    }

    private fun setTick(tv: TextView, state: Int, onLime: Boolean) {
        tv.text = if (state == 0) "🕓" else if (state == 1) "✓" else "✓✓"
        tv.setTextColor(
            if (state >= 3) (if (onLime) C.BLUE_ON_LIME else C.BLUE_ON_DARK)
            else (if (onLime) C.GREY_ON_LIME else C.GREY_ON_DARK)
        )
    }

    private fun tickView(state: Int, onLime: Boolean, size: Float): TextView {
        val tv = TextView(this)
        tv.textSize = size
        tv.typeface = Typeface.DEFAULT_BOLD
        setTick(tv, state, onLime)
        return tv
    }

    // ---------- splash (animation + sound) ----------

    private fun showSplash() {
        screen = 0
        val root = FrameLayout(this).apply { setBackgroundColor(C.FOREST) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val logo = TextView(this).apply {
            text = "M"
            textSize = 56f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C.FOREST)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C.LIME)
            }
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
        }
        val title = txt("MukhsApp", 40f, C.LIME, true).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(24) }
        }
        val tag = txt(L.s("Magana. Kira. Kusa.", "Talk. Call. Nearby."), 18f, Color.WHITE).apply {
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
            applyIdentity()
            showHome()
        }
    }

    private fun applyIdentity() {
        net.myName = myName
        net.myHash = Num.hashOf(myNumber)
    }

    // ---------- setup screen ----------

    private fun showSetup() {
        screen = 0
        val root = col(C.FOREST, 28)
        root.gravity = Gravity.CENTER_VERTICAL
        root.addView(txt(L.s("Barka da zuwa", "Welcome"), 18f, Color.WHITE))
        root.addView(
            txt(L.s("Mu fara da bayananka", "Let's set up your profile"), 28f, C.LIME, true),
            lp(4)
        )
        val nameIn = styledInput(
            L.s("Sunanka", "Your name"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )
        nameIn.setText(if (tmpName.isNotEmpty()) tmpName else myName)
        val numIn = styledInput(
            L.s("Lambar wayarka (misali 08031234567)", "Your phone number (e.g. 08031234567)"),
            InputType.TYPE_CLASS_PHONE
        )
        numIn.setText(tmpNumber)

        val setLang = { ha: Boolean ->
            L.ha = ha
            getSharedPreferences("mukhs", MODE_PRIVATE).edit().putBoolean("ha", ha).apply()
            tmpName = nameIn.text.toString()
            tmpNumber = numIn.text.toString()
            showSetup()
        }
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        langRow.addView(
            pill("Hausa", if (L.ha) C.LIME else C.DEEP, if (L.ha) C.FOREST else C.LIME) { setLang(true) },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        langRow.addView(
            pill("English", if (!L.ha) C.LIME else C.DEEP, if (!L.ha) C.FOREST else C.LIME) { setLang(false) },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) }
        )
        root.addView(langRow, lp(20))
        root.addView(nameIn, lp(16))
        root.addView(numIn, lp(12))
        root.addView(
            txt(
                L.s(
                    "Mutane za su gane ka da lambarka. Ba a tura ta ko'ina, a wayarka take.",
                    "People will know you by your number. It never leaves your phone."
                ),
                13f, C.MUTED
            ),
            lp(8)
        )
        root.addView(pill(L.s("Ci gaba", "Continue"), C.LIME, C.FOREST) {
            val n = nameIn.text.toString().trim().replace("|", " ")
            val num = Num.normalize(numIn.text.toString())
            if (n.isEmpty()) {
                toast(L.s("Rubuta sunanka tukuna", "Please enter your name"))
            } else if (num == null) {
                toast(L.s("Lambar ba ta yi daidai ba. Misali: 08031234567", "That number looks wrong. Example: 08031234567"))
            } else {
                myName = n
                myNumber = num
                getSharedPreferences("mukhs", MODE_PRIVATE).edit()
                    .putString("name", n).putString("number", num).apply()
                applyIdentity()
                showHome()
            }
        }, lp(16))
        setContentView(root)
    }

    // ---------- home (tabs) ----------

    private fun showHome() {
        screen = 1
        currentChat = null
        net.activeChat = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.DEEP)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.FOREST)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        top.addView(txt("MukhsApp", 22f, C.LIME, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        val fc = FrameLayout(this)
        content = fc
        root.addView(fc, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.FOREST)
            setPadding(0, dp(6), 0, dp(6))
        }
        navBar = nav
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        renderTab()
        ensureNearby()
    }

    private fun renderTab() {
        val fc = content ?: return
        fc.removeAllViews()
        val v: View = if (tab == 0) chatsView() else if (tab == 2) nearbyView() else placeholder(tab)
        fc.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        renderNav()
    }

    private fun renderNav() {
        val nav = navBar ?: return
        nav.removeAllViews()
        val unread = store.totalUnread()
        for (i in 0 until 5) {
            val sel = i == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    tab = i
                    renderTab()
                }
            }
            val iconBox = FrameLayout(this)
            if (sel) iconBox.background = round(C.DEEP, 16)
            val icon = txt(tabIcons[i], 20f, Color.WHITE)
            icon.alpha = if (sel) 1f else 0.6f
            iconBox.addView(icon, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            if (i == 0 && unread > 0) {
                iconBox.addView(
                    badge(unread),
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
                )
            }
            item.addView(iconBox, LinearLayout.LayoutParams(dp(56), dp(30)))
            item.addView(txt(tabNames[i], 10f, if (sel) C.LIME else C.MUTED, sel))
            nav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun placeholder(t: Int): View {
        val box = col(C.DEEP, 24)
        box.gravity = Gravity.CENTER
        box.addView(txt(tabIcons[t], 48f, Color.WHITE).apply { gravity = Gravity.CENTER })
        box.addView(txt(tabNames[t], 22f, C.LIME, true).apply { gravity = Gravity.CENTER }, lp(12))
        box.addView(
            txt(L.s("Zai zo nan ba da daɗewa ba.", "Coming soon."), 14f, C.MUTED).apply {
                gravity = Gravity.CENTER
            },
            lp(6)
        )
        return box
    }

    // ---------- Chats tab ----------

    private fun chatsView(): View {
        val sv = ScrollView(this)
        val box = col(C.DEEP, 12)
        sv.addView(box)
        val convos = store.convos()
        if (convos.isEmpty()) {
            box.addView(
                txt(
                    L.s(
                        "Babu tattaunawa tukuna. Je tab ɗin Nearby ka nemo mutane kusa da kai.",
                        "No chats yet. Open the Nearby tab to find people around you."
                    ),
                    14f, C.MUTED
                ),
                lp(16)
            )
        }
        for (cv in convos) box.addView(chatRow(cv), lp(6))
        return sv
    }

    private fun chatRow(cv: Convo): View {
        val h = cv.peer
        val name = net.nameOf(h)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.FOREST, 16)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            setOnClickListener { openPeer(h) }
        }
        row.addView(avatar(name, 48, net.isNearby(h)))

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        mid.addView(txt(name, 17f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (cv.last.mine) {
            val tk = tickView(cv.last.state, false, 13f)
            tk.setPadding(0, 0, dp(4), 0)
            sub.addView(tk)
        }
        sub.addView(
            txt(cv.last.body, 14f, C.MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        mid.addView(sub)
        row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(txt(listTime(cv.last.ts), 12f, if (cv.unread > 0) C.LIME else C.MUTED))
        if (cv.unread > 0) {
            right.addView(badge(cv.unread), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        }
        row.addView(right)
        return row
    }

    // ---------- Nearby tab ----------

    private fun nearbyView(): View {
        val sv = ScrollView(this)
        val box = col(C.DEEP, 16)
        sv.addView(box)
        box.addView(txt(L.s("Mutanen kusa da kai", "People near you"), 18f, C.LIME, true))
        box.addView(
            txt(
                if (net.searching) L.s("Ana nemo... ka tabbata Bluetooth, Wi-Fi da Location a kunne suke.",
                    "Searching... make sure Bluetooth, Wi-Fi and Location are on.")
                else L.s("An daina nemo.", "Search is off."),
                13f, C.MUTED
            ),
            lp(4)
        )
        box.addView(
            pill(
                if (net.searching) L.s("Daina Nemo", "Stop searching") else L.s("Fara Nemo", "Start searching"),
                C.LIME, C.FOREST
            ) { toggleSearch() },
            lp(12)
        )
        val people = LinkedHashSet<String>()
        for (h in net.connected.values) people.add(h)
        for (h in net.nearby.values) people.add(h)
        if (people.isEmpty()) {
            box.addView(
                txt(
                    L.s(
                        "Babu kowa tukuna. Ka tabbata kana da lambar mutum a contacts ɗinka, kuma yana da MukhsApp a buɗe kusa da kai.",
                        "Nobody yet. Make sure you have the person's number in your contacts and they have MukhsApp open nearby."
                    ),
                    14f, C.MUTED
                ),
                lp(16)
            )
        }
        for (h in people) {
            val name = net.nameOf(h)
            val online = net.isConnected(h)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(C.FOREST, 16)
                setPadding(dp(12), dp(10), dp(14), dp(10))
                setOnClickListener { openPeer(h) }
            }
            row.addView(avatar(name, 48, true))
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            mid.addView(txt(name, 17f, Color.WHITE, true))
            mid.addView(
                txt(
                    if (online) "● online" else L.s("● kusa. Danna don haɗawa", "● nearby. Tap to connect"),
                    13f, C.LIME
                )
            )
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(txt("›", 26f, C.LIME, true))
            box.addView(row, lp(8))
        }
        return sv
    }

    private fun openPeer(h: String) {
        if (net.isConnected(h) || net.endpointOfNearby(h) == null) {
            showChat(h)
            return
        }
        toast(L.s("Ana haɗawa da ${net.nameOf(h)}...", "Connecting to ${net.nameOf(h)}..."))
        net.connect(h)
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
            net.loadContacts()
            net.start()
        } else {
            toast(
                L.s(
                    "Dole ka ba da duk izinin (contacts, Bluetooth, Location) don app ɗin ya yi aiki.",
                    "Please allow all permissions (contacts, Bluetooth, Location) for the app to work."
                )
            )
        }
    }

    private fun ensureNearby() {
        if (userStopped) return
        if (hasPerms()) {
            if (net.contacts.isEmpty()) net.loadContacts()
            net.start()
        } else if (!askedPerms) {
            askedPerms = true
            requestPermissions(requiredPerms().toTypedArray(), 1)
        }
    }

    private fun toggleSearch() {
        if (net.searching) {
            userStopped = true
            net.stop()
        } else {
            userStopped = false
            if (hasPerms()) {
                net.loadContacts()
                net.start()
            } else {
                requestPermissions(requiredPerms().toTypedArray(), 1)
            }
        }
    }

    // ---------- chat screen ----------

    private fun chatStatusText(h: String): String {
        if (net.isConnected(h)) return "online"
        if (net.isNearby(h)) return L.s("kusa (ba a haɗa ba)", "nearby (not connected)")
        val ls = store.lastSeen(h)
        return if (ls > 0) lastSeenText(ls) else ""
    }

    private fun updateChatHeader() {
        val h = currentChat ?: return
        chatStatusView?.text = chatStatusText(h)
    }

    private fun refreshTicks() {
        val h = currentChat ?: return
        if (tickViews.isEmpty()) return
        for (m in store.messages(h)) {
            if (m.mine) {
                val tv = tickViews[m.uid]
                if (tv != null) setTick(tv, m.state, true)
            }
        }
    }

    private fun showChat(h: String) {
        screen = 2
        currentChat = h
        net.activeChat = h
        tickViews.clear()
        lastDayKey = ""
        net.markChatRead(h)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.DEEP)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.FOREST)
            setPadding(dp(8), dp(10), dp(16), dp(10))
        }
        val back = txt("‹", 34f, C.LIME, true).apply {
            setPadding(dp(8), 0, dp(12), dp(4))
            setOnClickListener { showHome() }
        }
        header.addView(back)
        header.addView(avatar(net.nameOf(h), 40, net.isNearby(h)))
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleBox.addView(txt(net.nameOf(h), 18f, C.LIME, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val st = txt(chatStatusText(h), 12f, C.MUTED)
        chatStatusView = st
        titleBox.addView(st)
        header.addView(titleBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val cb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
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
            hint = L.s("Rubuta saƙo", "Type a message")
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            background = round(C.FOREST, 24)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(
            pill(L.s("Tura", "Send"), C.LIME, C.FOREST) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    val m = net.sendText(h, t)
                    appendMsg(m)
                    input.setText("")
                }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
        )
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        val history = store.messages(h)
        if (history.isEmpty()) {
            addNote(L.s("Babu saƙo tukuna. Ku fara magana!", "No messages yet. Say hello!"))
        }
        for (m in history) appendMsg(m)
        if (!net.isConnected(h)) {
            addNote(
                L.s(
                    "Ba a haɗa ba. Saƙonnin da ka rubuta za a tura su idan kun haɗu.",
                    "Not connected. Messages you write will be sent when you meet."
                )
            )
        }
    }

    private fun addNote(s: String) {
        val box = chatBox ?: return
        val v = txt(s, 13f, C.MUTED).apply { gravity = Gravity.CENTER }
        box.addView(v, lp(8))
    }

    private fun appendMsg(m: Msg) {
        val box = chatBox ?: return
        val key = dayStart(m.ts).toString()
        if (key != lastDayKey) {
            lastDayKey = key
            val chip = txt(dayLabel(m.ts), 12f, C.MUTED).apply {
                gravity = Gravity.CENTER
                background = round(C.FOREST, 10)
                setPadding(dp(12), dp(4), dp(12), dp(4))
            }
            box.addView(
                chip,
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(10)
                }
            )
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(if (m.mine) C.LIME else C.FOREST, 16)
            setPadding(dp(12), dp(8), dp(10), dp(6))
        }
        val body = txt(m.body, 16f, if (m.mine) C.FOREST else Color.WHITE).apply {
            maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
        }
        bubble.addView(body)
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        meta.addView(txt(fmtTime(m.ts), 11f, if (m.mine) C.GREY_ON_LIME else C.MUTED))
        if (m.mine) {
            val tv = tickView(m.state, true, 12f)
            tv.setPadding(dp(4), 0, 0, 0)
            meta.addView(tv)
            tickViews[m.uid] = tv
        }
        bubble.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
        val params = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(5)
            gravity = if (m.mine) Gravity.END else Gravity.START
        }
        box.addView(bubble, params)
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }
}
