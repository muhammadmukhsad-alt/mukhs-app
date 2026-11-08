package com.mukhsapp.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
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
    private var myAbout = ""
    private var tmpName = ""
    private var tmpNumber = ""
    private var tmpAbout = ""

    // 0 = splash/setup, 1 = home (tabs), 2 = chat, 3 = settings, 4 = select contact
    private var screen = 0
    private var tab = 0
    private var currentChat: String? = null
    private var askedPerms = false
    private var userStopped = false

    private var content: FrameLayout? = null
    private var navBar: LinearLayout? = null
    private var chatListBox: LinearLayout? = null
    private var chatQuery = ""
    private var contactBox: LinearLayout? = null
    private var contactQuery = ""
    private var chatBox: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatStatusView: TextView? = null
    private var headerAvatarBox: FrameLayout? = null
    private var normalHeader: LinearLayout? = null
    private var selHeader: LinearLayout? = null
    private var selCount: TextView? = null
    private var undoBar: LinearLayout? = null
    private var undoText: TextView? = null
    private val tickViews = HashMap<String, TextView>()
    private val bubbles = HashMap<Long, LinearLayout>()
    private val msgById = HashMap<Long, Msg>()
    private val selected = LinkedHashSet<Long>()
    private var lastDayKey = ""
    private val hideUndo = Runnable { undoBar?.visibility = View.GONE }

    private val tabNames = arrayOf("Chats", "Status", "Nearby", "Communities", "Calls")
    private val tabIcons = intArrayOf(
        R.drawable.ic_chat, R.drawable.ic_status, R.drawable.ic_nearby,
        R.drawable.ic_people, R.drawable.ic_call
    )

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.ha = prefs().getBoolean("ha", false)
        applyTheme()
        Sounds.init(this)
        Notifier.ensureChannel(this)
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeMode() == 0) {
            applyTheme()
            rebuild()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == 2 && selected.isNotEmpty()) {
            clearSelection()
        } else if (screen == 2 || screen == 3 || screen == 4) {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    // ---------- theme ----------

    private fun prefs() = getSharedPreferences("mukhs", MODE_PRIVATE)

    // 0 = follow the phone, 1 = light, 2 = dark
    private fun themeMode(): Int = prefs().getInt("theme", 0)

    private fun applyTheme() {
        val mode = themeMode()
        val sysDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        C.apply(if (mode == 1) false else if (mode == 2) true else sysDark)
        window.statusBarColor = C.BAR
        window.navigationBarColor = C.BAR
    }

    private fun rebuild() {
        val h = currentChat
        if (screen == 1) showHome()
        else if (screen == 2 && h != null) showChat(h)
        else if (screen == 3) showSettings()
        else if (screen == 4) showContacts()
    }

    private fun dlg(): AlertDialog.Builder =
        AlertDialog.Builder(
            this,
            if (C.dark) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert
        )

    private fun getSet(k: String): MutableSet<String> {
        val s = HashSet<String>()
        val saved = prefs().getStringSet(k, null)
        if (saved != null) s.addAll(saved)
        return s
    }

    private fun putSet(k: String, v: Set<String>) {
        prefs().edit().putStringSet(k, v).apply()
    }

    // ---------- events from the network layer ----------

    override fun onChanged() {
        refreshUi()
    }

    override fun onIncoming(peer: String, msg: Msg) {
        val muted = getSet("muted").contains(peer)
        if (!net.foreground) {
            if (!muted) Notifier.message(this, net.nameOf(peer), msg.body, peer)
        } else if (screen == 2 && currentChat == peer) {
            if (!muted) Sounds.received()
            appendMsg(msg)
        } else if (!muted) {
            Sounds.received()
            toast("${net.nameOf(peer)}: ${msg.body}")
        }
        refreshUi()
    }

    override fun onOpenChat(peer: String) {
        showChat(peer)
    }

    override fun onNotice(text: String) {
        toast(text)
    }

    private fun refreshUi() {
        if (screen == 1) {
            if (tab == 0 && chatListBox != null) {
                populateChats()
                renderNav()
            } else {
                renderTab()
            }
        } else if (screen == 2) {
            updateChatHeader()
            refreshTicks()
        } else if (screen == 4) {
            populateContacts()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun soon() = toast(L.s("Zai zo nan ba da daɗewa ba.", "Coming soon."))

    private fun defaultAbout(): String =
        L.s("Ina amfani da MukhsApp", "Hey there! I am using MukhsApp")

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
            setHintTextColor(C.HINT)
            setTextColor(C.TEXT)
            textSize = 18f
            background = round(C.INPUT, 14)
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

    // a tappable vector icon
    private fun icon(res: Int, sizeDp: Int, tint: Int, onClick: (View) -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp + 18), dp(sizeDp + 18))
            setOnClickListener { onClick(it) }
        }

    private fun palette(name: String): Int {
        val p = intArrayOf(
            Color.parseColor("#2E7D6B"), Color.parseColor("#1B5E4F"),
            Color.parseColor("#3F8F7B"), Color.parseColor("#0F766E"),
            Color.parseColor("#5B8C5A"), Color.parseColor("#4C7A34")
        )
        return p[(name.hashCode() and 0x7fffffff) % p.size]
    }

    private fun initialOf(name: String): String {
        val t = name.trim()
        return if (t.isEmpty()) "?" else String(Character.toChars(t.codePointAt(0))).uppercase()
    }

    // a round picture: the person's photo if we have it, otherwise their first letter
    private fun avatar(name: String, size: Int, online: Boolean, key: String?): View {
        val fl = FrameLayout(this)
        val bg = palette(name)
        val bmp = if (key != null) Profile.bitmap(this, key) else null
        val core: View
        if (bmp != null) {
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            }
            iv.outlineProvider = ViewOutlineProvider.BACKGROUND
            iv.clipToOutline = true
            core = iv
        } else {
            val t = TextView(this)
            t.text = initialOf(name)
            t.textSize = size * 0.4f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setTextColor(Color.WHITE)
            t.gravity = Gravity.CENTER
            t.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            }
            core = t
        }
        fl.addView(core, FrameLayout.LayoutParams(dp(size), dp(size)))
        if (online) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(C.LIME)
                    setStroke(dp(2), C.BG)
                }
            }
            fl.addView(dot, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM or Gravity.END))
        }
        return fl
    }

    private fun setTick(tv: TextView, state: Int, onLime: Boolean) {
        tv.text = if (state == 0) "🕓" else if (state == 1) "✓" else "✓✓"
        tv.setTextColor(
            if (state >= 3) (if (onLime) C.BLUE_ON_LIME else C.TICK_BLUE)
            else (if (onLime) C.GREY_ON_LIME else C.TICK_GREY)
        )
    }

    private fun tickView(state: Int, onLime: Boolean, size: Float): TextView {
        val tv = TextView(this)
        tv.textSize = size
        tv.typeface = Typeface.DEFAULT_BOLD
        setTick(tv, state, onLime)
        return tv
    }

    // a small panel of choices that opens under a button (like the 3-dot menu)
    private fun showMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(C.FOREST, 14)
            setPadding(0, dp(6), 0, dp(6))
        }
        val pw = PopupWindow(box, dp(250), WRAP, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        for (item in items) {
            val row = txt(item.first, 16f, Color.WHITE).apply {
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setOnClickListener {
                    pw.dismiss()
                    item.second()
                }
            }
            box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        pw.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun pillColored(label: String, selected: Boolean, onClick: () -> Unit): View =
        pill(
            label,
            if (selected) C.BTN else C.INPUT,
            if (selected) C.ON_BTN else C.TEXT,
            onClick
        )

    // ---------- splash (logo + sound) ----------

    private fun showSplash() {
        screen = 0
        val splashBg = Color.parseColor("#011C02")
        window.statusBarColor = splashBg
        window.navigationBarColor = splashBg
        val root = FrameLayout(this).apply { setBackgroundColor(splashBg) }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo_full)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(logo, FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER))
        setContentView(root)

        logo.alpha = 0f
        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(800).start()

        playChime()
        handler.postDelayed({ afterSplash() }, 2400)
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
        applyTheme()
        myName = prefs().getString("name", "") ?: ""
        myNumber = prefs().getString("number", "") ?: ""
        if (myName.isBlank() || myNumber.isBlank()) {
            showSetup()
        } else {
            applyIdentity()
            showHome()
        }
    }

    private fun applyIdentity() {
        myAbout = prefs().getString("about", "") ?: ""
        if (myAbout.isBlank()) myAbout = defaultAbout()
        net.myName = myName
        net.myAbout = myAbout
        net.myHash = Num.hashOf(myNumber)
    }

    // ---------- photo picking ----------

    private fun pickPhoto() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(Intent.createChooser(i, L.s("Zaɓi hoto", "Choose a photo")), 7)
        } catch (e: Exception) {
            toast(L.s("Ba a iya buɗe gallery ba.", "Could not open the gallery."))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (Profile.saveFromUri(this, uri, "me")) {
            net.broadcastProfile()
            if (screen == 0) showSetup()
            else if (screen == 3) showSettings()
        } else {
            toast(L.s("Ba a iya amfani da wannan hoton ba.", "Could not use that photo."))
        }
    }

    // ---------- setup screen ----------

    private fun showSetup() {
        screen = 0
        window.statusBarColor = C.BAR
        window.navigationBarColor = C.BG
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        val root = col(C.BG, 28)
        sv.addView(root)
        root.addView(txt(L.s("Barka da zuwa", "Welcome"), 18f, C.TEXT))
        root.addView(
            txt(L.s("Mu fara da bayananka", "Let's set up your profile"), 28f, C.ACCENT, true),
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
        val keep = {
            tmpName = nameIn.text.toString()
            tmpNumber = numIn.text.toString()
        }

        val av = avatar(if (nameIn.text.isNotEmpty()) nameIn.text.toString() else "?", 96, false, "me")
        av.setOnClickListener {
            keep()
            pickPhoto()
        }
        root.addView(av, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })
        root.addView(
            txt(L.s("Danna don saka hoto (ba dole ba)", "Tap to add a photo (optional)"), 13f, C.SUB)
                .apply { gravity = Gravity.CENTER },
            lp(6)
        )

        root.addView(nameIn, lp(16))
        root.addView(numIn, lp(12))
        root.addView(
            txt(
                L.s(
                    "Mutane za su gane ka da lambarka. Ba a tura ta ko'ina, a wayarka take.",
                    "People will know you by your number. It never leaves your phone."
                ),
                13f, C.SUB
            ),
            lp(8)
        )
        root.addView(pill(L.s("Ci gaba", "Continue"), C.BTN, C.ON_BTN) {
            val n = nameIn.text.toString().trim().replace("|", " ")
            val num = Num.normalize(numIn.text.toString())
            if (n.isEmpty()) {
                toast(L.s("Rubuta sunanka tukuna", "Please enter your name"))
            } else if (num == null) {
                toast(L.s("Lambar ba ta yi daidai ba. Misali: 08031234567", "That number looks wrong. Example: 08031234567"))
            } else {
                myName = n
                myNumber = num
                prefs().edit().putString("name", n).putString("number", num).apply()
                applyIdentity()
                showHome()
            }
        }, lp(16))
        setContentView(sv)
    }

    // ---------- settings screen ----------

    private fun section(title: String): TextView =
        txt(title, 13f, C.SUB, true).apply { setPadding(0, dp(22), 0, dp(6)) }

    private fun showSettings() {
        screen = 3
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR)
            setPadding(dp(8), dp(10), dp(16), dp(10))
        }
        header.addView(icon(R.drawable.ic_arrow_back, 24, C.LIME) { showHome() })
        header.addView(txt(L.s("Saituna", "Settings"), 20f, C.LIME, true))
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = col(C.BG, 20)
        root.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        val nameIn = styledInput(
            L.s("Sunanka", "Your name"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )
        nameIn.setText(if (tmpName.isNotEmpty()) tmpName else myName)
        val aboutIn = styledInput(L.s("Game da ni", "About"), InputType.TYPE_CLASS_TEXT)
        aboutIn.setText(if (tmpAbout.isNotEmpty()) tmpAbout else myAbout)
        val keep = {
            tmpName = nameIn.text.toString()
            tmpAbout = aboutIn.text.toString()
        }

        val av = avatar(myName, 110, false, "me")
        av.setOnClickListener {
            keep()
            pickPhoto()
        }
        body.addView(av, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(
            pill(L.s("Canza hoto", "Change photo"), C.CARD, C.ACCENT) {
                keep()
                pickPhoto()
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        )

        body.addView(section(L.s("Suna", "Name")))
        body.addView(nameIn)
        body.addView(section(L.s("Game da ni", "About")))
        body.addView(aboutIn)
        body.addView(section(L.s("Lambar waya", "Phone number")))
        body.addView(txt("+$myNumber", 18f, C.TEXT))

        // appearance
        body.addView(section(L.s("Kamanni (Theme)", "Appearance (Theme)")))
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = arrayOf(L.s("Na waya", "System"), "Light", "Dark")
        for (i in 0 until 3) {
            val b = pillColored(names[i], themeMode() == i) {
                keep()
                prefs().edit().putInt("theme", i).apply()
                applyTheme()
                showSettings()
            }
            themeRow.addView(
                b,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) }
            )
        }
        body.addView(themeRow)

        // language
        body.addView(section(L.s("Harshe (App language)", "App language")))
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val setLang = { ha: Boolean ->
            L.ha = ha
            prefs().edit().putBoolean("ha", ha).apply()
            keep()
            showSettings()
        }
        langRow.addView(
            pillColored("English", !L.ha) { setLang(false) },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        langRow.addView(
            pillColored("Hausa", L.ha) { setLang(true) },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) }
        )
        body.addView(langRow)

        body.addView(pill(L.s("Ajiye", "Save"), C.BTN, C.ON_BTN) {
            val n = nameIn.text.toString().trim().replace("|", " ")
            var a = aboutIn.text.toString().trim()
            if (a.isEmpty()) a = defaultAbout()
            if (n.isEmpty()) {
                toast(L.s("Rubuta sunanka tukuna", "Please enter your name"))
            } else {
                myName = n
                myAbout = a
                tmpName = ""
                tmpAbout = ""
                prefs().edit().putString("name", n).putString("about", a).apply()
                net.myName = n
                net.myAbout = a
                net.restartAdvertising()
                net.broadcastProfile()
                toast(L.s("An ajiye.", "Saved."))
                showHome()
            }
        }, lp(28))
        setContentView(sv)
    }

    // ---------- home (tabs) ----------

    private fun showHome() {
        screen = 1
        currentChat = null
        net.activeChat = null
        clearChatState()
        window.statusBarColor = C.BAR
        window.navigationBarColor = C.BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR)
            setPadding(dp(18), dp(6), dp(8), dp(6))
        }
        top.addView(txt("MukhsApp", 22f, C.LIME, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(icon(R.drawable.ic_more_vert, 24, Color.WHITE) { v ->
            showMenu(
                v,
                listOf<Pair<String, () -> Unit>>(
                    Pair(L.s("Sabon group", "New group")) { soon() },
                    Pair(L.s("Saituna", "Settings")) {
                        tmpName = ""
                        tmpAbout = ""
                        showSettings()
                    }
                )
            )
        })
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        val fc = FrameLayout(this)
        content = fc
        root.addView(fc, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.BAR)
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
        chatListBox = null
        val v: View = if (tab == 0) chatsView() else if (tab == 2) nearbyView() else placeholder(tab)
        fc.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        if (tab == 0) {
            val fab = ImageView(this)
            fab.setImageResource(R.drawable.ic_add_chat)
            fab.setColorFilter(C.FOREST)
            fab.setPadding(dp(16), dp(16), dp(16), dp(16))
            fab.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C.LIME)
            }
            fab.elevation = dp(6).toFloat()
            fab.setOnClickListener { showContacts() }
            fc.addView(
                fab,
                FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(18)
                    bottomMargin = dp(18)
                }
            )
        }
        renderNav()
    }

    // bottom tabs: an icon with its name under it, like WhatsApp
    private fun renderNav() {
        val nav = navBar ?: return
        nav.removeAllViews()
        val unread = store.totalUnread()
        for (i in 0 until 5) {
            val sel = i == tab
            val tint = if (sel) C.LIME else C.MUTED
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener {
                    tab = i
                    renderTab()
                }
            }
            val iconBox = FrameLayout(this)
            val pillBg = FrameLayout(this)
            if (sel) pillBg.background = round(C.PILL, 16)
            val iv = ImageView(this)
            iv.setImageResource(tabIcons[i])
            iv.setColorFilter(tint)
            pillBg.addView(iv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            iconBox.addView(pillBg, FrameLayout.LayoutParams(dp(58), dp(32), Gravity.CENTER))
            if (i == 0 && unread > 0) {
                iconBox.addView(
                    badge(unread),
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                        rightMargin = dp(2)
                    }
                )
            }
            item.addView(iconBox, LinearLayout.LayoutParams(dp(66), dp(32)))
            item.addView(
                txt(tabNames[i], 11f, tint, sel).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) }
            )
            nav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun placeholder(t: Int): View {
        val box = col(C.BG, 28)
        box.gravity = Gravity.CENTER
        val title: String
        val sub: String
        if (t == 3) {
            title = L.s("Ku haɗu da al'umma", "Stay connected with a community")
            sub = L.s(
                "Communities suna haɗa mambobi a cikin groups masu take. Zai zo nan ba da daɗewa ba.",
                "Communities bring members together in topic-based groups. Coming soon."
            )
        } else if (t == 4) {
            title = L.s("Yi kira cikin sirri", "Make private calls")
            sub = L.s(
                "Kira da murya da bidiyo ba tare da data ba. Zai zo nan ba da daɗewa ba.",
                "Voice and video calls with no data. Coming soon."
            )
        } else {
            title = tabNames[t]
            sub = L.s("Zai zo nan ba da daɗewa ba.", "Coming soon.")
        }
        val ic = ImageView(this)
        ic.setImageResource(tabIcons[t])
        ic.setColorFilter(C.ACCENT)
        box.addView(ic, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(txt(title, 22f, C.TEXT, true).apply { gravity = Gravity.CENTER }, lp(16))
        box.addView(txt(sub, 14f, C.SUB).apply { gravity = Gravity.CENTER }, lp(8))
        return box
    }

    // ---------- Chats tab ----------

    private fun chatsView(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.INPUT, 24)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val si = ImageView(this)
        si.setImageResource(R.drawable.ic_search)
        si.setColorFilter(C.SUB)
        bar.addView(si, LinearLayout.LayoutParams(dp(22), dp(22)))
        val q = EditText(this).apply {
            hint = L.s("Nemo", "Search")
            setHintTextColor(C.HINT)
            setTextColor(C.TEXT)
            textSize = 16f
            background = null
            setSingleLine(true)
            setPadding(dp(10), dp(12), dp(4), dp(12))
            setText(chatQuery)
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                chatQuery = s?.toString() ?: ""
                populateChats()
            }
        })
        bar.addView(q, LinearLayout.LayoutParams(0, WRAP, 1f))
        wrap.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(10), dp(12), dp(6))
        })

        val sv = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(90))
        }
        sv.addView(box)
        wrap.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        chatListBox = box
        populateChats()
        return wrap
    }

    private fun populateChats() {
        val box = chatListBox ?: return
        box.removeAllViews()
        val q = chatQuery.trim().lowercase()
        val pins = getSet("pins")
        var list = store.convos()
        if (q.isNotEmpty()) {
            list = list.filter {
                net.nameOf(it.peer).lowercase().contains(q) || it.last.body.lowercase().contains(q)
            }
        }
        list = list.sortedByDescending { pins.contains(it.peer) }
        if (list.isEmpty()) {
            box.addView(
                txt(
                    if (q.isNotEmpty()) L.s("Babu abin da aka samu.", "No results found.")
                    else L.s(
                        "Babu tattaunawa tukuna. Danna maɓallin kore ka zaɓi mutum, ko je tab ɗin Nearby.",
                        "No chats yet. Tap the green button to pick someone, or open the Nearby tab."
                    ),
                    14f, C.SUB
                ),
                lp(16)
            )
        }
        for (cv in list) box.addView(chatRow(cv, pins.contains(cv.peer)), lp(6))
        box.addView(
            txt(
                L.s(
                    "Saƙonninka suna tafiya kai tsaye daga waya zuwa waya. Babu intanet, babu sabar waje.",
                    "Your messages go directly phone to phone. No internet, no outside servers."
                ),
                12f, C.SUB
            ).apply { gravity = Gravity.CENTER },
            lp(22)
        )
    }

    private fun chatRow(cv: Convo, pinned: Boolean): View {
        val h = cv.peer
        val name = net.nameOf(h)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.CARD, 16)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            setOnClickListener { openPeer(h) }
            setOnLongClickListener {
                rowMenu(h, pinned)
                true
            }
        }
        val av = avatar(name, 48, net.isNearby(h), h)
        av.setOnClickListener { showPhotoCard(h) }
        row.addView(av)

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        mid.addView(txt(name, 17f, C.TEXT, true).apply {
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
            txt(cv.last.body, 14f, C.SUB).apply {
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
        right.addView(txt(listTime(cv.last.ts), 12f, if (cv.unread > 0) C.ACCENT else C.SUB))
        val badges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (pinned) {
            val pi = ImageView(this)
            pi.setImageResource(R.drawable.ic_pin)
            pi.setColorFilter(C.SUB)
            badges.addView(pi, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(4) })
        }
        if (cv.unread > 0) badges.addView(badge(cv.unread))
        right.addView(badges, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        row.addView(right)
        return row
    }

    // long-press on a chat in the list
    private fun rowMenu(h: String, pinned: Boolean) {
        val muted = getSet("muted").contains(h)
        val items = arrayOf(
            if (pinned) L.s("Cire pin", "Unpin chat") else L.s("Yi pin", "Pin chat"),
            if (muted) L.s("Buɗe sanarwa", "Unmute notifications") else L.s("Rufe sanarwa", "Mute notifications"),
            L.s("Share tattaunawa", "Delete chat")
        )
        dlg().setTitle(net.nameOf(h)).setItems(items) { _, which ->
            if (which == 0) {
                val p = getSet("pins")
                if (pinned) p.remove(h) else p.add(h)
                putSet("pins", p)
                populateChats()
            } else if (which == 1) {
                toggleMute(h)
                populateChats()
            } else {
                dlg()
                    .setTitle(L.s("Share tattaunawa?", "Delete chat?"))
                    .setMessage(
                        L.s(
                            "Za a share duk saƙonnin da ke wannan tattaunawar a wayarka.",
                            "All messages in this chat will be deleted from your phone."
                        )
                    )
                    .setPositiveButton(L.s("Share", "Delete")) { _, _ ->
                        store.clearChat(h)
                        populateChats()
                        renderNav()
                    }
                    .setNegativeButton(L.s("Soke", "Cancel"), null)
                    .show()
            }
        }.show()
    }

    private fun toggleMute(h: String) {
        val m = getSet("muted")
        if (m.contains(h)) {
            m.remove(h)
            toast(L.s("An buɗe sanarwa.", "Notifications on."))
        } else {
            m.add(h)
            toast(L.s("An rufe sanarwa.", "Notifications muted."))
        }
        putSet("muted", m)
    }

    // tap a person's picture in the list: a big photo with quick buttons
    private fun showPhotoCard(h: String) {
        val name = net.nameOf(h)
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(C.CARD, 14)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val top = FrameLayout(this)
        val bmp = Profile.bitmap(this, h)
        if (bmp != null) {
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            top.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val t = TextView(this)
            t.text = initialOf(name)
            t.textSize = 96f
            t.setTextColor(Color.WHITE)
            t.gravity = Gravity.CENTER
            t.setBackgroundColor(palette(name))
            top.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val nm = txt(name, 18f, Color.WHITE, true).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        top.addView(nm, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        card.addView(top, LinearLayout.LayoutParams(dp(280), dp(280)))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        actions.addView(icon(R.drawable.ic_chat, 24, C.ACCENT) {
            d.dismiss()
            openPeer(h)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(icon(R.drawable.ic_call, 24, C.ACCENT) { soon() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(icon(R.drawable.ic_videocam, 24, C.ACCENT) { soon() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(icon(R.drawable.ic_info, 24, C.ACCENT) {
            d.dismiss()
            viewContact(h)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
        d.setContentView(card)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
    }

    // ---------- Nearby tab ----------

    private fun nearbyView(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        val box = col(C.BG, 16)
        sv.addView(box)
        box.addView(txt(L.s("Mutanen kusa da kai", "People near you"), 18f, C.ACCENT, true))
        box.addView(
            txt(
                if (net.searching) L.s(
                    "Ana nemo... ka tabbata Bluetooth, Wi-Fi da Location a kunne suke.",
                    "Searching... make sure Bluetooth, Wi-Fi and Location are on."
                )
                else L.s("An daina nemo.", "Search is off."),
                13f, C.SUB
            ),
            lp(4)
        )
        box.addView(
            pill(
                if (net.searching) L.s("Daina Nemo", "Stop searching") else L.s("Fara Nemo", "Start searching"),
                C.BTN, C.ON_BTN
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
                    14f, C.SUB
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
                background = round(C.CARD, 16)
                setPadding(dp(12), dp(10), dp(14), dp(10))
                setOnClickListener { openPeer(h) }
            }
            row.addView(avatar(name, 48, true, h))
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            mid.addView(txt(name, 17f, C.TEXT, true))
            mid.addView(
                txt(
                    if (online) "● online" else L.s("● kusa. Danna don haɗawa", "● nearby. Tap to connect"),
                    13f, C.ACCENT
                )
            )
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(txt("›", 26f, C.ACCENT, true))
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

    // ---------- Select contact ----------

    private var contactSearchBar: LinearLayout? = null

    private fun showContacts() {
        screen = 4
        currentChat = null
        contactQuery = ""
        window.statusBarColor = C.BAR
        window.navigationBarColor = C.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        header.addView(icon(R.drawable.ic_arrow_back, 24, C.LIME) { showHome() })
        val tb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        tb.addView(txt(L.s("Zaɓi contact", "Select contact"), 18f, C.LIME, true))
        tb.addView(txt("${net.contacts.size} contacts", 12f, C.MUTED))
        header.addView(tb, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(icon(R.drawable.ic_search, 24, Color.WHITE) {
            val sb = contactSearchBar
            if (sb != null) sb.visibility = if (sb.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        header.addView(icon(R.drawable.ic_more_vert, 24, Color.WHITE) { v ->
            showMenu(
                v,
                listOf<Pair<String, () -> Unit>>(
                    Pair(L.s("Gayyaci aboki", "Invite a friend")) { soon() },
                    Pair(L.s("Sabunta", "Refresh")) {
                        net.loadContacts()
                        toast(L.s("Ana sabunta contacts...", "Refreshing contacts..."))
                    }
                )
            )
        })
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val sb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.INPUT, 24)
            setPadding(dp(14), 0, dp(14), 0)
            visibility = View.GONE
        }
        val q = EditText(this).apply {
            hint = L.s("Suna ko lamba", "Name or number")
            setHintTextColor(C.HINT)
            setTextColor(C.TEXT)
            textSize = 16f
            background = null
            setSingleLine(true)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                contactQuery = s?.toString() ?: ""
                populateContacts()
            }
        })
        sb.addView(q, LinearLayout.LayoutParams(0, WRAP, 1f))
        contactSearchBar = sb
        root.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(8), dp(12), dp(4))
        })

        val sv = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(20))
        }
        sv.addView(box)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        contactBox = box
        populateContacts()
        setContentView(root)
    }

    private fun actionRow(iconRes: Int, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { onClick() }
        }
        val ic = ImageView(this)
        ic.setImageResource(iconRes)
        ic.setColorFilter(C.ON_BTN)
        ic.setPadding(dp(11), dp(11), dp(11), dp(11))
        ic.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C.BTN)
        }
        row.addView(ic, LinearLayout.LayoutParams(dp(46), dp(46)))
        row.addView(
            txt(label, 17f, C.TEXT, true).apply { setPadding(dp(16), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        return row
    }

    private fun populateContacts() {
        val box = contactBox ?: return
        box.removeAllViews()
        val q = contactQuery.trim().lowercase()
        if (q.isEmpty()) {
            box.addView(actionRow(R.drawable.ic_people, L.s("Sabon group", "New group")) { soon() })
            box.addView(actionRow(R.drawable.ic_person, L.s("Sabon contact", "New contact")) {
                try {
                    startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI))
                } catch (e: Exception) {
                    toast(L.s("Ba a iya buɗe contacts ba.", "Could not open contacts."))
                }
            })
            box.addView(actionRow(R.drawable.ic_people, L.s("Sabuwar community", "New community")) { soon() })
        }
        val entries = net.contacts.entries
            .filter { it.key != net.myHash }
            .filter {
                q.isEmpty() || it.value.first.lowercase().contains(q) || it.value.second.contains(q)
            }
            .sortedBy { it.value.first.lowercase() }
        box.addView(
            txt(L.s("Contacts", "Contacts"), 13f, C.SUB, true).apply { setPadding(dp(8), dp(16), 0, dp(6)) }
        )
        if (entries.isEmpty()) {
            box.addView(
                txt(
                    L.s("Babu contact da aka samu.", "No contacts found."),
                    14f, C.SUB
                ).apply { setPadding(dp(8), dp(8), 0, 0) }
            )
        }
        var lastLetter = ""
        for (e in entries) {
            val h = e.key
            val name = e.value.first
            val letter = initialOf(name)
            if (letter != lastLetter) {
                lastLetter = letter
                box.addView(txt(letter, 14f, C.ACCENT, true).apply { setPadding(dp(8), dp(10), 0, dp(2)) })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { openPeer(h) }
            }
            row.addView(avatar(name, 44, net.isNearby(h), h))
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            mid.addView(txt(name, 16f, C.TEXT, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val sub = if (net.isConnected(h)) "online"
            else if (net.isNearby(h)) L.s("kusa", "nearby")
            else "+" + e.value.second
            mid.addView(txt(sub, 13f, C.SUB))
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(row)
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

    // the required ones, plus notifications (optional) on new Android versions
    private fun askForPerms() {
        val l = requiredPerms().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(l.toTypedArray(), 1)
    }

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
            askForPerms()
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
                askForPerms()
            }
        }
    }

    // ---------- chat screen ----------

    private fun clearChatState() {
        tickViews.clear()
        bubbles.clear()
        msgById.clear()
        selected.clear()
        normalHeader = null
        selHeader = null
        undoBar = null
        handler.removeCallbacks(hideUndo)
    }

    private fun chatStatusText(h: String): String {
        if (net.isConnected(h)) return "online"
        if (net.isNearby(h)) return L.s("kusa (ba a haɗa ba)", "nearby (not connected)")
        val ls = store.lastSeen(h)
        return if (ls > 0) lastSeenText(ls) else ""
    }

    private fun updateChatHeader() {
        val h = currentChat ?: return
        chatStatusView?.text = chatStatusText(h)
        val hab = headerAvatarBox
        if (hab != null) {
            hab.removeAllViews()
            hab.addView(avatar(net.nameOf(h), 40, net.isNearby(h), h))
        }
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

    private fun viewContact(h: String) {
        val msg = StringBuilder()
        val num = net.contacts[h]?.second
        if (num != null) msg.append("+").append(num).append("\n\n")
        val about = store.about(h)
        if (about.isNotEmpty()) msg.append(about).append("\n\n")
        msg.append(chatStatusText(h))
        dlg()
            .setTitle(net.nameOf(h))
            .setMessage(msg.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmClear(h: String) {
        dlg()
            .setTitle(L.s("Share tattaunawa?", "Clear chat?"))
            .setMessage(
                L.s(
                    "Za a share duk saƙonnin da ke wannan tattaunawar a wayarka.",
                    "All messages in this chat will be deleted from your phone."
                )
            )
            .setPositiveButton(L.s("Share", "Clear")) { _, _ ->
                store.clearChat(h)
                showChat(h)
            }
            .setNegativeButton(L.s("Soke", "Cancel"), null)
            .show()
    }

    private fun chatMenu(anchor: View, h: String) {
        val muted = getSet("muted").contains(h)
        showMenu(
            anchor,
            listOf<Pair<String, () -> Unit>>(
                Pair(L.s("Sabon group", "New group")) { soon() },
                Pair(L.s("Duba contact", "View contact")) { viewContact(h) },
                Pair(L.s("Bincika", "Search")) { soon() },
                Pair(L.s("Hotuna, links da docs", "Media, links, and docs")) { soon() },
                Pair(if (muted) L.s("Buɗe sanarwa", "Unmute notifications") else L.s("Rufe sanarwa", "Mute notifications")) {
                    toggleMute(h)
                },
                Pair(L.s("Salon chat", "Chat theme")) { soon() },
                Pair(L.s("Ƙari  ›", "More  ›")) {
                    showMenu(
                        anchor,
                        listOf<Pair<String, () -> Unit>>(
                            Pair(L.s("Share tattaunawa", "Clear chat")) { confirmClear(h) },
                            Pair(L.s("Fitar da chat", "Export chat")) { soon() },
                            Pair(L.s("Toshe", "Block")) { soon() }
                        )
                    )
                }
            )
        )
    }

    // ----- selecting messages (long-press) -----

    private fun bubbleColor(id: Long): Int {
        val m = msgById[id]
        return if (m != null && m.mine) C.LIME else C.CARD
    }

    private fun toggleSelect(id: Long) {
        val b = bubbles[id] ?: return
        if (selected.contains(id)) {
            selected.remove(id)
            b.background = round(bubbleColor(id), 16)
        } else {
            selected.add(id)
            b.background = round(C.SELECT, 16)
        }
        updateSelHeader()
    }

    private fun clearSelection() {
        for (id in selected) bubbles[id]?.background = round(bubbleColor(id), 16)
        selected.clear()
        updateSelHeader()
    }

    private fun updateSelHeader() {
        val nh = normalHeader ?: return
        val sh = selHeader ?: return
        if (selected.isEmpty()) {
            nh.visibility = View.VISIBLE
            sh.visibility = View.GONE
        } else {
            nh.visibility = View.GONE
            sh.visibility = View.VISIBLE
            selCount?.text = selected.size.toString()
        }
    }

    private fun copySelected() {
        val ids = selected.toList().sorted()
        val sb = StringBuilder()
        for (id in ids) {
            val m = msgById[id] ?: continue
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(m.body)
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", sb.toString()))
        toast(L.s("An kwafa.", "Copied."))
        clearSelection()
    }

    private fun deleteSelected(h: String) {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        store.setDeleted(ids, true)
        for (id in ids) {
            val b = bubbles.remove(id)
            if (b != null) (b.parent as? LinearLayout)?.removeView(b)
            val m = msgById.remove(id)
            if (m != null) tickViews.remove(m.uid)
        }
        selected.clear()
        updateSelHeader()
        showUndo(h, ids)
        populateChatsIfVisible()
    }

    private fun populateChatsIfVisible() {
        if (screen == 1 && chatListBox != null) populateChats()
    }

    private fun showUndo(h: String, ids: List<Long>) {
        val bar = undoBar ?: return
        undoText?.text = if (ids.size == 1) L.s("An share saƙo 1 a wurinka", "1 message deleted for me")
        else L.s("An share saƙonni ${ids.size} a wurinka", "${ids.size} messages deleted for me")
        bar.visibility = View.VISIBLE
        bar.findViewWithTag<TextView>("undo")?.setOnClickListener {
            store.setDeleted(ids, false)
            handler.removeCallbacks(hideUndo)
            bar.visibility = View.GONE
            showChat(h)
        }
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, 6000)
    }

    private fun showChat(h: String) {
        screen = 2
        currentChat = h
        net.activeChat = h
        clearChatState()
        lastDayKey = ""
        net.markChatRead(h)
        Notifier.cancel(this, h)
        window.statusBarColor = C.BAR
        window.navigationBarColor = C.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        normalHeader = header
        header.addView(icon(R.drawable.ic_arrow_back, 24, C.LIME) { showHome() })
        val hab = FrameLayout(this)
        hab.addView(avatar(net.nameOf(h), 40, net.isNearby(h), h))
        headerAvatarBox = hab
        header.addView(hab)
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { viewContact(h) }
        }
        titleBox.addView(txt(net.nameOf(h), 18f, C.LIME, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val st = txt(chatStatusText(h), 12f, C.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        chatStatusView = st
        titleBox.addView(st)
        header.addView(titleBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(icon(R.drawable.ic_videocam, 24, Color.WHITE) { soon() })
        header.addView(icon(R.drawable.ic_call, 24, Color.WHITE) { soon() })
        header.addView(icon(R.drawable.ic_more_vert, 24, Color.WHITE) { v -> chatMenu(v, h) })
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // header shown while messages are selected
        val sh = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            visibility = View.GONE
        }
        selHeader = sh
        sh.addView(icon(R.drawable.ic_arrow_back, 24, C.LIME) { clearSelection() })
        val sc = txt("0", 20f, C.LIME, true)
        selCount = sc
        sh.addView(sc, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        sh.addView(icon(R.drawable.ic_copy, 24, Color.WHITE) { copySelected() })
        sh.addView(icon(R.drawable.ic_delete, 24, Color.WHITE) { deleteSelected(h) })
        root.addView(sh, LinearLayout.LayoutParams(MATCH, WRAP))

        val cb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        chatBox = cb
        val cs = ScrollView(this)
        cs.addView(cb)
        chatScroll = cs
        root.addView(cs, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // "N messages deleted for me - UNDO"
        val ub = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        val ut = txt("", 14f, Color.WHITE)
        undoText = ut
        ub.addView(ut, LinearLayout.LayoutParams(0, WRAP, 1f))
        val undoBtn = txt("UNDO", 14f, C.LIME, true)
        undoBtn.tag = "undo"
        ub.addView(undoBtn)
        undoBar = ub
        root.addView(ub, LinearLayout.LayoutParams(MATCH, WRAP))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val input = EditText(this).apply {
            hint = L.s("Rubuta saƙo", "Type a message")
            setHintTextColor(C.HINT)
            setTextColor(C.TEXT)
            background = round(C.INPUT, 24)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))

        // round button: microphone when the box is empty, send arrow when there is text
        val actionBtn = FrameLayout(this)
        actionBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C.LIME)
        }
        val actionIcon = ImageView(this)
        actionIcon.setColorFilter(C.FOREST)
        actionIcon.setPadding(dp(13), dp(13), dp(13), dp(13))
        actionBtn.addView(actionIcon, FrameLayout.LayoutParams(dp(50), dp(50)))

        fun updateAction() {
            val has = input.text.toString().trim().isNotEmpty()
            actionIcon.setImageResource(if (has) R.drawable.ic_send else R.drawable.ic_mic)
        }
        updateAction()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAction()
            }
        })
        actionBtn.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isEmpty()) {
                toast(L.s("Voice note zai zo nan ba da daɗewa ba.", "Voice notes are coming soon."))
            } else {
                val m = net.sendText(h, t)
                Sounds.sent()
                appendMsg(m)
                input.setText("")
            }
        }
        bar.addView(
            actionBtn,
            LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) }
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
        val v = txt(s, 13f, C.SUB).apply { gravity = Gravity.CENTER }
        box.addView(v, lp(8))
    }

    private fun appendMsg(m: Msg) {
        val box = chatBox ?: return
        val key = dayStart(m.ts).toString()
        if (key != lastDayKey) {
            lastDayKey = key
            val chip = txt(dayLabel(m.ts), 12f, C.SUB).apply {
                gravity = Gravity.CENTER
                background = round(C.CARD, 10)
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
            background = round(if (m.mine) C.LIME else C.CARD, 16)
            setPadding(dp(12), dp(8), dp(10), dp(6))
        }
        val textColor = if (m.mine) C.FOREST else C.TEXT
        val limit = 600
        val long = m.body.length > limit
        val body = txt(if (long) m.body.substring(0, limit) + "…" else m.body, 16f, textColor).apply {
            maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
        }
        bubble.addView(body)
        if (long) {
            val more = txt("Read more", 15f, if (m.mine) C.BLUE_ON_LIME else C.ACCENT, true)
            more.setOnClickListener {
                body.text = m.body
                (more.parent as? LinearLayout)?.removeView(more)
            }
            bubble.addView(more)
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        meta.addView(txt(fmtTime(m.ts), 11f, if (m.mine) C.GREY_ON_LIME else C.SUB))
        if (m.mine) {
            val tv = tickView(m.state, true, 12f)
            tv.setPadding(dp(4), 0, 0, 0)
            meta.addView(tv)
            tickViews[m.uid] = tv
        }
        bubble.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
        bubbles[m.id] = bubble
        msgById[m.id] = m
        bubble.setOnLongClickListener {
            toggleSelect(m.id)
            true
        }
        bubble.setOnClickListener {
            if (selected.isNotEmpty()) toggleSelect(m.id)
        }
        val params = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(5)
            gravity = if (m.mine) Gravity.END else Gravity.START
        }
        box.addView(bubble, params)
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }
}
