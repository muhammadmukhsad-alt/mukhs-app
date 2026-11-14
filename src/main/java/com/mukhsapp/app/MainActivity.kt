package com.mukhsapp.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ContentUris
import android.view.WindowManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import java.io.File
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.EditText
import android.widget.HorizontalScrollView
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

    // 0 = splash/setup, 1 = home (tabs), 2 = chat, 3 = settings, 4 = select contact, 5 = MukhsAI,
    // 6 = watching a status, 7 = making a status
    private var screen = 0
    private var tab = 0
    private var currentChat: String? = null
    private var askedPerms = false
    private var userStopped = false

    private var content: FrameLayout? = null
    private var navBar: LinearLayout? = null
    private var chatListBox: LinearLayout? = null
    private var chatQuery = ""
    private var chatFilter = 0 // 0 All, 1 Unread, 2 Favourites, 3 Groups
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
    private val reactViews = HashMap<String, TextView>()
    private val starViews = HashMap<Long, TextView>()
    private val bubbles = HashMap<Long, LinearLayout>()
    private var replyTo: Msg? = null
    private var replyBar: LinearLayout? = null
    private var replyName: TextView? = null
    private var replyText: TextView? = null
    private var reactBar: LinearLayout? = null
    private var selReply: View? = null
    private var forwardTexts: List<String>? = null
    private var chatInput: EditText? = null
    private var emojiPanel: View? = null
    private var inputBoxView: View? = null
    private var recRowView: View? = null
    private var recTimer: TextView? = null
    private var recHint: TextView? = null
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var recCancel = false
    private var recDownX = 0f
    private var camUri: Uri? = null
    private var player: MediaPlayer? = null
    private var playingUid = ""
    private class VoiceUi(
        val icon: ImageView,
        val seek: WaveView,
        val time: TextView,
        val chip: TextView,
        val dur: Int
    )
    private val voiceUis = HashMap<String, VoiceUi>()
    private var playSpeed = 1f
    private var seeking = false
    private val recTick = object : Runnable {
        override fun run() {
            recTimer?.text = Media.fmtDur(((System.currentTimeMillis() - recStart) / 1000).toInt())
            handler.postDelayed(this, 400)
        }
    }
    private val playTick = object : Runnable {
        override fun run() {
            val p = player
            try {
                if (p != null && p.isPlaying) {
                    val ui = voiceUis[playingUid]
                    if (ui != null && !seeking) {
                        ui.seek.progress = p.currentPosition
                        ui.time.text = Media.fmtDur(p.currentPosition / 1000)
                    }
                    handler.postDelayed(this, 100)
                }
            } catch (e: Exception) {
                // the player was released
            }
        }
    }
    private var downX = 0f
    private var downY = 0f
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
        store = Hub.store(this)
        net = Hub.net(this)
        Hub.screen = this
        Calls.ui = callUi
        pendingPeer = intent?.getStringExtra("peer")
        showSplash()
    }

    private var pendingPeer: String? = null

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val p = intent?.getStringExtra("peer") ?: return
        if (screen >= 1) showChat(p) else pendingPeer = p
    }

    override fun onPause() {
        super.onPause()
        net.foreground = false
        if (recorder != null) finishRecording(false)
        stopPlay()
        handler.removeCallbacks(viewerTick)
        stopStatusAudio()
    }

    override fun onResume() {
        super.onResume()
        net.foreground = true
        if (Calls.state != Calls.IDLE && screen >= 1 && screen != 8) {
            showCall()
            return
        }
        val h = currentChat
        if (screen == 2 && h != null) showChat(h)
        if (screen == 6 && viewerList.isNotEmpty()) showViewer()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        net.foreground = false
        if (Hub.screen === this) Hub.screen = null
        if (Calls.ui === callUi) Calls.ui = null
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
        } else if (screen == 8) {
            moveTaskToBack(true)
        } else if (screen == 6) {
            closeViewer()
        } else if (screen == 7) {
            cancelComposer()
        } else if (screen == 2 || screen == 3 || screen == 4 || screen == 5) {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    // slide a finger left or right to move between the tabs
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (screen == 1) {
            if (ev.action == MotionEvent.ACTION_DOWN) {
                downX = ev.x
                downY = ev.y
            } else if (ev.action == MotionEvent.ACTION_UP) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (Math.abs(dx) > dp(90) && Math.abs(dx) > Math.abs(dy) * 2) {
                    val nt = if (dx < 0) tab + 1 else tab - 1
                    if (nt in 0..4) {
                        tab = nt
                        renderTab()
                        val c = content
                        if (c != null) {
                            c.translationX = (if (dx < 0) 1 else -1) * dp(40).toFloat()
                            c.alpha = 0.4f
                            c.animate().translationX(0f).alpha(1f).setDuration(180).start()
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
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
        // dark icons on the light theme
        var flags = 0
        if (!C.dark) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun rebuild() {
        val h = currentChat
        if (screen == 1) showHome()
        else if (screen == 2 && h != null) showChat(h)
        else if (screen == 3) showSettings()
        else if (screen == 4) showContacts()
        else if (screen == 5) showAi()
        else if (screen == 6) showViewer()
        else if (screen == 7) showComposer()
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
            if (!muted) Notifier.message(this, net.nameOf(peer), previewOf(msg), peer)
        } else if (screen == 2 && currentChat == peer) {
            if (!muted) Sounds.received()
            appendMsg(msg)
        } else if (!muted) {
            Sounds.received()
            toast("${net.nameOf(peer)}: ${previewOf(msg)}")
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

    // ---------- calls ----------

    private var pendingCallAction: (() -> Unit)? = null
    private var callStatus: TextView? = null
    private var callRemote: ImageView? = null
    private var callLocal: ImageView? = null
    private var callBar: LinearLayout? = null
    private var callAvatarBox: View? = null
    private var callShown = -1

    private val callUi = object : CallUi {
        override fun onCallChanged() {
            handler.post { onCallState() }
        }

        override fun onRemoteFrame(b: Bitmap) {
            callRemote?.setImageBitmap(b)
            callAvatarBox?.visibility = View.GONE
        }

        override fun onLocalFrame(b: Bitmap) {
            handler.post { callLocal?.setImageBitmap(b) }
        }
    }

    private val callTick = object : Runnable {
        override fun run() {
            updateCallStatus()
            if (screen == 8 && Calls.state != Calls.IDLE) handler.postDelayed(this, 1000)
        }
    }

    private fun onCallState() {
        if (Calls.state == Calls.IDLE) {
            if (screen == 8) {
                if (Build.VERSION.SDK_INT >= 27) {
                    setShowWhenLocked(false)
                    setTurnScreenOn(false)
                }
                val back = Calls.peer
                if (back.isNotEmpty()) showChat(back) else showHome()
            }
            return
        }
        if (screen != 8) showCall() else {
            buildCallBar()
            updateCallStatus()
        }
    }

    private fun ensureCallPerms(vid: Boolean, then: () -> Unit) {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (vid && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA)
        }
        if (need.isEmpty()) {
            then()
        } else {
            pendingCallAction = then
            requestPermissions(need.toTypedArray(), 5)
        }
    }

    private fun startCall(h: String, vid: Boolean) {
        if (Calls.state != Calls.IDLE) {
            showCall()
            return
        }
        if (!net.isConnected(h)) {
            toast(L.s("Ba a haɗe da shi ba tukuna. Jira kaɗan ya bayyana a Nearby.", "Not connected to them yet. Wait a moment."))
            return
        }
        ensureCallPerms(vid) {
            if (Calls.dial(this, h, vid)) showCall()
        }
    }

    private fun callBtn(ic: Int, bg: Int, on: Boolean = false, rotate: Float = 0f, go: () -> Unit): View {
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) Color.WHITE else bg)
        }
        val i = ImageView(this)
        i.setImageResource(ic)
        i.setColorFilter(if (on) Color.BLACK else Color.WHITE)
        i.rotation = rotate
        i.setPadding(dp(18), dp(18), dp(18), dp(18))
        f.addView(i, FrameLayout.LayoutParams(dp(64), dp(64)))
        f.setOnClickListener { go() }
        return f
    }

    private fun buildCallBar() {
        val bar = callBar ?: return
        bar.removeAllViews()
        val red = Color.parseColor("#E53935")
        val green = Color.parseColor("#2E9E5B")
        val grey = Color.parseColor("#33FFFFFF")
        fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
        if (Calls.state == Calls.IN) {
            add(callBtn(R.drawable.ic_call, red, false, 135f) { Calls.hangup(this) })
            add(callBtn(if (Calls.video) R.drawable.ic_videocam else R.drawable.ic_call, green) {
                ensureCallPerms(Calls.video) { Calls.accept(this) }
            })
        } else {
            if (Calls.state == Calls.LIVE) {
                add(callBtn(if (Calls.muted) R.drawable.ic_mic_off else R.drawable.ic_mic, grey, Calls.muted) {
                    Calls.muted = !Calls.muted
                    buildCallBar()
                })
            }
            add(callBtn(R.drawable.ic_speaker, grey, Calls.speaker) {
                Calls.setSpeaker(this, !Calls.speaker)
                buildCallBar()
            })
            add(callBtn(R.drawable.ic_call, red, false, 135f) { Calls.hangup(this) })
        }
    }

    private fun updateCallStatus() {
        val t = callStatus ?: return
        t.text = when (Calls.state) {
            Calls.IN -> if (Calls.video) L.s("Kiran bidiyo ya shigo…", "Incoming video call…") else L.s("Kiran murya ya shigo…", "Incoming voice call…")
            Calls.OUT -> L.s("Ana kira…", "Calling…")
            Calls.LIVE -> {
                val sec = ((System.currentTimeMillis() - Calls.startedAt) / 1000).toInt()
                String.format("%d:%02d", sec / 60, sec % 60)
            }
            else -> ""
        }
    }

    private fun showCall() {
        screen = 8
        currentChat = null
        net.activeChat = null
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.statusBarColor = Color.parseColor("#06261A")
        window.navigationBarColor = Color.parseColor("#06261A")
        window.decorView.systemUiVisibility = 0
        val h = Calls.peer
        val root = FrameLayout(this)
        root.background = Doodle.make(this, Color.parseColor("#072A1C"), 0x26FFFFFF)

        val remote = ImageView(this)
        remote.scaleType = ImageView.ScaleType.CENTER_CROP
        callRemote = remote
        root.addView(remote, FrameLayout.LayoutParams(MATCH, MATCH))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val av = FrameLayout(this)
        av.addView(avatar(net.nameOf(h), 130, false, h))
        callAvatarBox = av
        val name = txt(net.nameOf(h), 26f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val st = txt("", 16f, Color.parseColor("#E6FFFFFF")).apply {
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        callStatus = st
        box.addView(name, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(70) })
        box.addView(st, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        box.addView(av, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(36) })
        root.addView(box, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        if (Calls.video) {
            val loc = ImageView(this)
            loc.scaleType = ImageView.ScaleType.CENTER_CROP
            loc.background = round(Color.parseColor("#55000000"), 12)
            loc.clipToOutline = true
            callLocal = loc
            root.addView(
                loc,
                FrameLayout.LayoutParams(dp(100), dp(140), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(40)
                    rightMargin = dp(14)
                }
            )
        } else {
            callLocal = null
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        callBar = bar
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
        buildCallBar()
        updateCallStatus()
        setContentView(root)
        handler.removeCallbacks(callTick)
        handler.post(callTick)
    }

    // the list of calls (the Calls tab)
    private fun callsView(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        val log = Calls.history(this)
        if (log.isEmpty()) {
            box.addView(
                txt(L.s("Babu kira tukuna. Ka buɗe chat ka danna alamar waya.", "No calls yet. Open a chat and tap the phone icon."), 15f, C.SUB).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(32), dp(60), dp(32), 0)
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
            return sv
        }
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault())
        for (e in log) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            row.addView(avatar(net.nameOf(e.h), 46, false, e.h))
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(txt(net.nameOf(e.h), 17f, if (e.result == "missed") Color.parseColor("#E53935") else C.TEXT, true))
            val arrow = if (e.incoming) "↙ " else "↗ "
            val how = if (e.result == "missed") L.s("Ba a ɗauka ba", "Missed") else if (e.dur > 0) String.format("%d:%02d", e.dur / 60, e.dur % 60) else
                if (e.incoming) L.s("An ƙi", "Declined") else L.s("Ba a amsa ba", "No answer")
            mid.addView(txt(arrow + fmt.format(java.util.Date(e.ts)) + "  ·  " + how, 13f, C.SUB))
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
            row.addView(icon(if (e.video) R.drawable.ic_videocam else R.drawable.ic_call, 24, C.ACCENT) { startCall(e.h, e.video) })
            box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return sv
    }

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
            background = round(C.CARD, 14)
            setPadding(0, dp(6), 0, dp(6))
        }
        val pw = PopupWindow(box, dp(250), WRAP, true)
        pw.elevation = dp(8).toFloat()
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        for (item in items) {
            val row = txt(item.first, 16f, C.TEXT).apply {
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
        window.decorView.systemUiVisibility = 0
        val root = FrameLayout(this).apply { setBackgroundColor(splashBg) }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo_full)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(logo, FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER))
        val fromBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fromBox.addView(txt("from", 14f, C.MUTED).apply { gravity = Gravity.CENTER })
        fromBox.addView(
            txt("MUKHSAD", 20f, C.LIME, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.18f
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) }
        )
        root.addView(
            fromBox,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(52)
            }
        )
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
            val pp = pendingPeer
            if (pp != null) {
                pendingPeer = null
                showChat(pp)
            }
            if (Calls.state != Calls.IDLE) showCall()
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
        if (requestCode == 11 && resultCode == RESULT_OK) {
            val clip = data?.clipData
            if (clip != null && clip.itemCount > 1) {
                val uris = ArrayList<Uri>()
                for (k in 0 until Math.min(clip.itemCount, 10)) uris.add(clip.getItemAt(k).uri)
                sendImageUris(uris)
            } else {
                val u = data?.data ?: clip?.getItemAt(0)?.uri
                if (u != null) sendImageUri(u)
            }
            return
        }
        if (requestCode == 12) {
            val u = camUri
            camUri = null
            if (u != null) {
                if (resultCode == RESULT_OK) {
                    sendImageUri(u)
                } else {
                    try {
                        contentResolver.delete(u, null, null)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
            return
        }
        if (requestCode == 21 && resultCode == RESULT_OK) {
            val u = data?.data
            if (u != null) statusImageUri(u)
            return
        }
        if (requestCode == 22) {
            val u = camUri
            camUri = null
            if (u != null) {
                if (resultCode == RESULT_OK) {
                    statusImageUri(u)
                } else {
                    try {
                        contentResolver.delete(u, null, null)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
            return
        }
        if (requestCode == 23 && resultCode == RESULT_OK) {
            val u = data?.data
            if (u != null) statusAudioUri(u)
            return
        }
        if (requestCode == 13 && resultCode == RESULT_OK) {
            val u = data?.data
            if (u != null) sendDocUri(u)
            return
        }
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
        header.addView(icon(R.drawable.ic_arrow_back, 24, C.BAR_ICON) { showHome() })
        header.addView(txt(L.s("Saituna", "Settings"), 20f, C.BAR_TEXT, true))
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
            val pic = Profile.bitmap(this, "me")
            if (pic != null) {
                showImageViewer(pic, myName, "", "", null)
            } else {
                keep()
                pickPhoto()
            }
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
        forwardTexts = null
        replyTo = null
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
        top.addView(txt("MukhsApp", 22f, C.BAR_TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(icon(R.drawable.ic_more_vert, 24, C.BAR_ICON) { v ->
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
        val v: View = if (tab == 0) chatsView() else if (tab == 1) statusView() else if (tab == 2) nearbyView() else if (tab == 4) callsView() else placeholder(tab)
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
            fab.setOnClickListener {
                forwardTexts = null
                showContacts()
            }
            val ai = ImageView(this)
            ai.setImageResource(R.drawable.ic_sparkle)
            ai.setColorFilter(C.ACCENT)
            ai.setPadding(dp(11), dp(11), dp(11), dp(11))
            ai.background = round(C.CARD, 14)
            ai.elevation = dp(4).toFloat()
            ai.setOnClickListener { showAi() }
            fc.addView(
                ai,
                FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(24)
                    bottomMargin = dp(18 + 58 + 14)
                }
            )
            fc.addView(
                fab,
                FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(18)
                    bottomMargin = dp(18)
                }
            )
        }
        if (tab == 1) {
            val pen = ImageView(this)
            pen.setImageResource(R.drawable.ic_edit)
            pen.setColorFilter(C.ACCENT)
            pen.setPadding(dp(11), dp(11), dp(11), dp(11))
            pen.background = round(C.CARD, 14)
            pen.elevation = dp(4).toFloat()
            pen.setOnClickListener { composeText() }
            val cam = ImageView(this)
            cam.setImageResource(R.drawable.ic_camera)
            cam.setColorFilter(C.FOREST)
            cam.setPadding(dp(16), dp(16), dp(16), dp(16))
            cam.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C.LIME)
            }
            cam.elevation = dp(6).toFloat()
            cam.setOnClickListener { showAddStatusSheet() }
            fc.addView(
                pen,
                FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(24)
                    bottomMargin = dp(18 + 58 + 14)
                }
            )
            fc.addView(
                cam,
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
            val tint = if (sel) C.NAV_SEL else C.NAV_UNSEL
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener {
                    tab = i
                    if (i == 1) net.announceAll()
                    renderTab()
                }
            }
            val iconBox = FrameLayout(this)
            val pillBg = FrameLayout(this)
            if (sel) pillBg.background = round(C.NAV_PILL, 16)
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
            if (i == 1 && store.totalUnviewedStatuses() > 0) {
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(C.RING_NEW)
                    }
                }
                iconBox.addView(
                    dot,
                    FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply {
                        rightMargin = dp(8)
                        topMargin = dp(2)
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

    // ---------- MukhsAI ----------

    private fun showAi() {
        screen = 5
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
            setPadding(dp(4), dp(8), dp(16), dp(8))
        }
        header.addView(icon(R.drawable.ic_arrow_back, 24, C.BAR_ICON) { showHome() })
        header.addView(txt("MukhsAI", 20f, C.BAR_TEXT, true))
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        val box = col(C.BG, 28)
        box.gravity = Gravity.CENTER
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_sparkle)
        ic.setColorFilter(C.ACCENT)
        box.addView(ic, LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(txt("MukhsAI", 26f, C.TEXT, true).apply { gravity = Gravity.CENTER }, lp(16))
        box.addView(
            txt(
                L.s(
                    "Mataimaki da zai yi aiki a cikin wayarka, ba tare da intanet ko data ba. Yana buƙatar ƙwaƙwalwar waya mai yawa, don haka zai zo daga baya a matsayin zaɓi.",
                    "An assistant that will run inside your phone, with no internet or data. It needs a lot of phone memory, so it will arrive later as an optional add-on."
                ),
                15f, C.SUB
            ).apply { gravity = Gravity.CENTER },
            lp(10)
        )
        root.addView(box, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
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

        // filter chips: All / Unread / Favourites / Groups
        val names = arrayOf(L.s("Duka", "All"), L.s("Ba a karanta ba", "Unread"), L.s("Abokan so", "Favourites"), L.s("Groups", "Groups"))
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        val chipViews = ArrayList<TextView>()
        fun paint() {
            for (i in chipViews.indices) {
                val on = i == chatFilter
                chipViews[i].background = round(if (on) C.NAV_PILL else C.INPUT, 18)
                chipViews[i].setTextColor(if (on) C.TEXT else C.SUB)
            }
        }
        for (i in names.indices) {
            val c = txt(names[i], 14f, C.SUB, true)
            c.setPadding(dp(14), dp(7), dp(14), dp(7))
            c.setOnClickListener {
                chatFilter = i
                paint()
                populateChats()
            }
            chipViews.add(c)
            chips.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        paint()
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips)
        wrap.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })

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
                net.nameOf(it.peer).lowercase().contains(q) || previewOf(it.last).lowercase().contains(q)
            }
        }
        val favs = getSet("favs")
        if (chatFilter == 1) list = list.filter { it.unread > 0 }
        else if (chatFilter == 2) list = list.filter { favs.contains(it.peer) }
        else if (chatFilter == 3) list = emptyList()
        list = list.sortedByDescending { pins.contains(it.peer) }
        if (list.isEmpty()) {
            box.addView(
                txt(
                    if (chatFilter == 1) L.s("Babu saƙon da ba a karanta ba.", "No unread chats.")
                    else if (chatFilter == 2) L.s("Babu abokan so tukuna. Dogon latsa chat ka zaɓi Add to favourites.", "No favourites yet. Long-press a chat and choose Add to favourites.")
                    else if (chatFilter == 3) L.s("Babu groups tukuna.", "No groups yet.")
                    else if (q.isNotEmpty()) L.s("Babu abin da aka samu.", "No results found.")
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
        val av = avatarRing(name, 48, net.isNearby(h), h, h)
        av.setOnClickListener { avatarTap(h) }
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
            txt(previewOf(cv.last), 14f, C.SUB).apply {
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
            if (getSet("favs").contains(h)) L.s("Cire daga abokan so", "Remove from favourites") else L.s("Ƙara cikin abokan so", "Add to favourites"),
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
            } else if (which == 2) {
                val f = getSet("favs")
                if (f.contains(h)) f.remove(h) else f.add(h)
                putSet("favs", f)
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
            // tap the big picture to see it full screen
            iv.setOnClickListener {
                d.dismiss()
                showImageViewer(bmp, name, "", "", null)
            }
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
        actions.addView(icon(R.drawable.ic_call, 24, C.ACCENT) {
            d.dismiss()
            startCall(h, false)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(icon(R.drawable.ic_videocam, 24, C.ACCENT) {
            d.dismiss()
            startCall(h, true)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(icon(R.drawable.ic_info, 24, C.ACCENT) {
            d.dismiss()
            viewContact(h)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
        d.setContentView(card)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
    }

    // ---------- Status ----------

    private val statusBgs = intArrayOf(
        Color.parseColor("#00796B"), Color.parseColor("#1B5E20"), Color.parseColor("#6A1B9A"),
        Color.parseColor("#AD1457"), Color.parseColor("#E65100"), Color.parseColor("#1565C0"),
        Color.parseColor("#37474F"), Color.parseColor("#B71C1C")
    )
    private var viewedOpen = false
    private var viewerOwner = ""
    private var viewerList: List<Status> = emptyList()
    private var viewerIdx = 0
    private var viewerStart = 0L
    private var viewerDur = 6000L
    private var viewerBars = ArrayList<ProgressBar>()
    private var statusPlayer: MediaPlayer? = null
    private var compKind = "t"
    private var compBg = Color.parseColor("#00796B")
    private var compText = ""
    private var compImg: File? = null
    private var compAud: File? = null
    private var compAudName = ""

    private val viewerTick = object : Runnable {
        override fun run() {
            val el = System.currentTimeMillis() - viewerStart
            val b = viewerBars.getOrNull(viewerIdx)
            b?.progress = Math.min(1000L, el * 1000L / Math.max(1L, viewerDur)).toInt()
            if (el >= viewerDur) stepViewer(1) else handler.postDelayed(this, 50)
        }
    }

    // a picture with a ring around it when the person has a status
    private fun avatarRing(name: String, size: Int, online: Boolean, key: String?, hash: String): View {
        val av = avatar(name, size, online, key)
        val info = if (hash.isNotEmpty()) store.statusInfo(hash) else null
        if (info == null) return av
        val fl = FrameLayout(this)
        val r = RingView(this)
        r.total = info.total
        r.unviewed = info.unviewed
        fl.addView(r, FrameLayout.LayoutParams(dp(size + 10), dp(size + 10)))
        fl.addView(av, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
        return fl
    }

    private fun avatarTap(h: String) {
        if (store.statusInfo(h) != null) openStatusViewer(h) else showPhotoCard(h)
    }

    private fun statusView(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        val box = col(C.BG, 16)
        sv.addView(box)
        val mine = store.statuses("")
        val mineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.CARD, 16)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            setOnClickListener { if (mine.isEmpty()) composeText() else openStatusViewer("") }
        }
        val holder = FrameLayout(this)
        val myAv = avatar(myName, 48, false, "me")
        if (mine.isNotEmpty()) {
            val r = RingView(this)
            r.total = mine.size
            r.unviewed = mine.size
            holder.addView(r, FrameLayout.LayoutParams(dp(58), dp(58)))
        }
        holder.addView(myAv, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        if (mine.isEmpty()) {
            val plus = txt("+", 16f, C.FOREST, true)
            plus.gravity = Gravity.CENTER
            plus.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C.LIME)
            }
            holder.addView(plus, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END))
        }
        mineRow.addView(holder, LinearLayout.LayoutParams(dp(58), dp(58)))
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        mid.addView(txt(L.s("Matsayina", "My status"), 17f, C.TEXT, true))
        mid.addView(
            txt(
                if (mine.isEmpty()) L.s("Ƙara matsayi – yana ɓacewa bayan awa 24", "Add status – Disappears after 24 hours")
                else mine.size.toString() + " " + L.s("matsayi", if (mine.size == 1) "update" else "updates") + " · " + listTime(mine.last().ts),
                14f, C.SUB
            )
        )
        mineRow.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(mineRow, lp(0))

        val owners = store.statusOwners()
        val fresh = owners.filter { it.unviewed > 0 }
        val seen = owners.filter { it.unviewed == 0 }
        if (fresh.isNotEmpty()) {
            box.addView(section(L.s("Sababbin", "Recent updates")), lp(18))
            for (o in fresh) box.addView(statusRow(o), lp(6))
        }
        if (seen.isNotEmpty()) {
            val hdr = txt(
                L.s("Waɗanda aka gani", "Viewed updates") + (if (viewedOpen) "  ▴" else "  ▾"),
                14f, C.SUB, true
            )
            hdr.setPadding(0, dp(8), 0, dp(8))
            hdr.setOnClickListener {
                viewedOpen = !viewedOpen
                renderTab()
            }
            box.addView(hdr, lp(14))
            if (viewedOpen) for (o in seen) box.addView(statusRow(o), lp(6))
        }
        if (owners.isEmpty()) {
            box.addView(
                txt(
                    L.s(
                        "Babu sabon matsayi. Matsayin abokanka yana zuwa ne idan wayoyinku sun haɗu.",
                        "No recent updates. Your contacts' statuses arrive when your phones meet."
                    ),
                    14f, C.SUB
                ).apply { gravity = Gravity.CENTER },
                lp(26)
            )
        }
        box.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(150)))
        return sv
    }

    private fun statusRow(o: StatusOwner): View {
        val name = net.nameOf(o.owner)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.CARD, 16)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            setOnClickListener { openStatusViewer(o.owner) }
        }
        row.addView(avatarRing(name, 48, false, o.owner, o.owner))
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        mid.addView(txt(name, 17f, C.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        mid.addView(txt(listTime(o.latest), 14f, C.SUB))
        row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    // ----- watching a status -----

    private fun openStatusViewer(owner: String) {
        val list = store.statuses(owner)
        if (list.isEmpty()) {
            toast(L.s("Babu matsayi.", "No status to show."))
            return
        }
        var start = 0
        if (owner != "") {
            val i = list.indexOfFirst { !it.viewed }
            if (i >= 0) start = i
        }
        viewerOwner = owner
        viewerList = list
        viewerIdx = start
        showViewer()
    }

    private fun stopStatusAudio() {
        try {
            statusPlayer?.release()
        } catch (e: Exception) {
            // ignore
        }
        statusPlayer = null
    }

    private fun closeViewer() {
        handler.removeCallbacks(viewerTick)
        stopStatusAudio()
        viewerList = emptyList()
        applyTheme()
        showHome()
    }

    private var viewerHeld = false
    private var replyOpen = false
    private var closeReplyRef: (() -> Unit)? = null
    private var viewerHeldAt = 0L

    // press and hold on a status: stop the timer and the music, hide the bars so you can read
    private fun holdViewer(on: Boolean, top: View?) {
        if (on && !viewerHeld) {
            viewerHeld = true
            viewerHeldAt = System.currentTimeMillis()
            handler.removeCallbacks(viewerTick)
            try {
                statusPlayer?.pause()
            } catch (e: Exception) {
                // ignore
            }
            top?.visibility = View.INVISIBLE
        } else if (!on && viewerHeld) {
            viewerHeld = false
            viewerStart += System.currentTimeMillis() - viewerHeldAt
            try {
                statusPlayer?.start()
            } catch (e: Exception) {
                // ignore
            }
            top?.visibility = View.VISIBLE
            handler.post(viewerTick)
        }
    }

    private fun stepViewer(d: Int) {
        val ni = viewerIdx + d
        if (ni >= viewerList.size) {
            closeViewer()
            return
        }
        viewerIdx = if (ni < 0) 0 else ni
        showViewer()
    }

    private fun showViewer() {
        screen = 6
        currentChat = null
        net.activeChat = null
        handler.removeCallbacks(viewerTick)
        stopStatusAudio()
        if (viewerList.isEmpty()) {
            closeViewer()
            return
        }
        if (viewerIdx >= viewerList.size) viewerIdx = viewerList.size - 1
        val st = viewerList[viewerIdx]
        val own = viewerOwner == ""
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
        val root = FrameLayout(this)
        root.setBackgroundColor(if (st.kind == "i") Color.BLACK else st.bg)
        if (st.kind == "i") {
            val bmp = Media.bitmapFor(st.img, 1280)
            if (bmp != null) {
                val iv = ImageView(this)
                iv.setImageBitmap(bmp)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                root.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }
        if (st.text.isNotEmpty()) {
            if (st.kind == "i") {
                val cap = txt(st.text, 17f, Color.WHITE)
                cap.setBackgroundColor(Color.parseColor("#99000000"))
                cap.setPadding(dp(18), dp(14), dp(18), dp(14))
                cap.gravity = Gravity.CENTER
                root.addView(
                    cap,
                    FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = if (own) 0 else dp(64) }
                )
            } else {
                val big = txt(st.text, 28f, Color.WHITE, true)
                big.gravity = Gravity.CENTER
                big.setPadding(dp(28), dp(90), dp(28), dp(60))
                root.addView(big, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }
        if (st.aud.isNotEmpty()) {
            val chip = txt("♪  " + L.s("Kiɗa", "Music"), 14f, Color.WHITE, true)
            chip.background = round(Color.parseColor("#66000000"), 20)
            chip.setPadding(dp(16), dp(8), dp(16), dp(8))
            root.addView(
                chip,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(90)
                }
            )
        }
        // tap on the left = back, on the right = next, press and hold = pause (like WhatsApp)
        viewerHeld = false
        replyOpen = false
        closeReplyRef = null
        var topRef: View? = null
        var tapX = 0f
        val holdRun = Runnable { holdViewer(true, topRef) }
        val zone = View(this)
        zone.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapX = e.x
                    handler.postDelayed(holdRun, 220)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(holdRun)
                    if (replyOpen) {
                        closeReplyRef?.invoke()
                    } else if (viewerHeld) {
                        holdViewer(false, topRef)
                    } else if (tapX < v.width * 0.3f) {
                        stepViewer(-1)
                    } else {
                        stepViewer(1)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRun)
                    holdViewer(false, topRef)
                }
            }
            true
        }
        root.addView(zone, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#99000000"), Color.TRANSPARENT)
            )
        }
        val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        viewerBars = ArrayList()
        for (i in viewerList.indices) {
            val b = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            b.max = 1000
            b.progress = if (i < viewerIdx) 1000 else 0
            b.progressTintList = ColorStateList.valueOf(Color.WHITE)
            b.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#66FFFFFF"))
            viewerBars.add(b)
            bars.addView(b, LinearLayout.LayoutParams(0, dp(3), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        top.addView(bars, LinearLayout.LayoutParams(MATCH, WRAP))
        val hr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hr.addView(icon(R.drawable.ic_arrow_back, 24, Color.WHITE) { closeViewer() })
        val who = if (own) myName else net.nameOf(viewerOwner)
        hr.addView(avatar(who, 36, false, if (own) "me" else viewerOwner))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        names.addView(txt(if (own) L.s("Matsayina", "My status") else who, 16f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        names.addView(txt(listTime(st.ts), 12f, Color.parseColor("#CCFFFFFF")))
        hr.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (own) {
            hr.addView(icon(R.drawable.ic_eye, 24, Color.WHITE) { showViewersDialog(st) })
            hr.addView(txt(store.views(st.uid).size.toString(), 14f, Color.WHITE, true))
            hr.addView(icon(R.drawable.ic_delete, 24, Color.WHITE) { confirmDeleteStatus(st) })
        }
        top.addView(hr, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        topRef = top
        if (!own) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val bb = FrameLayout(this)
            bb.setBackgroundColor(Color.TRANSPARENT)
            val normal = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(14))
            }
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(Color.parseColor("#88000000"), 28)
                setPadding(dp(20), 0, dp(12), 0)
            }
            pill.addView(
                txt(L.s("Amsa", "Reply"), 16f, Color.WHITE).apply { setPadding(0, dp(14), 0, dp(14)) },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            for (e in arrayOf("😍", "😂")) {
                pill.addView(
                    txt(e, 26f, Color.WHITE).apply {
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        setOnClickListener { sendStatusReply(viewerOwner, e, st) }
                    }
                )
            }
            normal.addView(pill, LinearLayout.LayoutParams(0, WRAP, 1f))
            val heart = txt("❤️", 24f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#88000000"))
                }
                setOnClickListener { sendStatusReply(viewerOwner, "❤️", st) }
            }
            normal.addView(heart, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(10) })
            bb.addView(normal, FrameLayout.LayoutParams(MATCH, WRAP))

            val typing = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(10))
                setBackgroundColor(Color.parseColor("#CC000000"))
                visibility = View.GONE
            }
            val et = EditText(this).apply {
                hint = L.s("Rubuta amsa…", "Type a reply…")
                setHintTextColor(Color.parseColor("#99FFFFFF"))
                setTextColor(Color.WHITE)
                background = round(Color.parseColor("#33FFFFFF"), 24)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 4
            }
            typing.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
            val goBtn = ImageView(this).apply {
                setImageResource(R.drawable.ic_send)
                setColorFilter(C.FOREST)
                setPadding(dp(13), dp(13), dp(13), dp(13))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(C.LIME)
                }
            }
            typing.addView(goBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
            bb.addView(typing, FrameLayout.LayoutParams(MATCH, WRAP))

            closeReplyRef = {
                imm.hideSoftInputFromWindow(et.windowToken, 0)
                typing.visibility = View.GONE
                normal.visibility = View.VISIBLE
                et.setText("")
                replyOpen = false
                holdViewer(false, topRef)
            }
            pill.setOnClickListener {
                replyOpen = true
                holdViewer(true, topRef)
                normal.visibility = View.GONE
                typing.visibility = View.VISIBLE
                et.requestFocus()
                imm.showSoftInput(et, 0)
            }
            goBtn.setOnClickListener {
                android.view.inputmethod.BaseInputConnection.removeComposingSpans(et.text)
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) sendStatusReply(viewerOwner, t, st)
                closeReplyRef?.invoke()
            }
            root.addView(bb, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }
        setContentView(root)

        var dur = 6000L
        if (st.aud.isNotEmpty() && File(st.aud).exists()) {
            try {
                val mp = MediaPlayer()
                mp.setDataSource(st.aud)
                mp.prepare()
                dur = Math.max(6000L, Math.min(mp.duration.toLong(), 60000L))
                mp.start()
                statusPlayer = mp
            } catch (e: Exception) {
                stopStatusAudio()
            }
        }
        viewerDur = dur
        viewerStart = System.currentTimeMillis()
        handler.post(viewerTick)
        if (!own && store.markStatusViewed(st.uid)) net.sendStatusView(viewerOwner, st.uid)
    }

    // answer someone's status: it arrives in our chat as a reply with the status quoted
    private fun sendStatusReply(owner: String, text: String, st: Status) {
        val what = if (st.kind == "i") "📷 " + (if (st.text.isEmpty()) L.s("Matsayi", "Status") else st.text)
        else L.s("Matsayi", "Status") + ": " + st.text
        val q = if (what.length > 100) what.substring(0, 100) + "…" else what
        net.sendText(owner, text, q, false)
        Sounds.sent()
        toast(L.s("An aika amsa.", "Reply sent."))
    }

    private fun showViewersDialog(st: Status) {
        val v = store.views(st.uid)
        handler.removeCallbacks(viewerTick)
        val el = System.currentTimeMillis() - viewerStart
        try {
            statusPlayer?.pause()
        } catch (e: Exception) {
            // ignore
        }
        val items = v.map { net.nameOf(it.first) + "  ·  " + listTime(it.second) }.toTypedArray()
        val b = dlg().setTitle(L.s("Waɗanda suka gani", "Viewed by") + " (" + v.size + ")")
        if (items.isEmpty()) b.setMessage(L.s("Babu wanda ya gani tukuna.", "No views yet.")) else b.setItems(items, null)
        b.setOnDismissListener {
            if (screen == 6) {
                viewerStart = System.currentTimeMillis() - el
                try {
                    statusPlayer?.start()
                } catch (e: Exception) {
                    // ignore
                }
                handler.post(viewerTick)
            }
        }
        b.show()
    }

    private fun confirmDeleteStatus(st: Status) {
        dlg().setMessage(L.s("Goge wannan matsayin?", "Delete this status update?"))
            .setPositiveButton(L.s("Goge", "Delete")) { _, _ ->
                store.deleteStatus(st.uid)
                net.announceAll()
                viewerList = store.statuses("")
                if (viewerList.isEmpty()) {
                    closeViewer()
                } else {
                    if (viewerIdx >= viewerList.size) viewerIdx = viewerList.size - 1
                    showViewer()
                }
            }
            .setNegativeButton(L.s("Soke", "Cancel"), null)
            .show()
    }

    // ----- the "Add status" sheet, like WhatsApp: Text / Music / Voice and your recent photos -----

    private fun imagePerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun thumbOf(uri: Uri, px: Int): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Throwable) {
            return null
        }
    }

    private fun recentImages(max: Int): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            val c = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null, MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            if (c != null) {
                while (c.moveToNext() && out.size < max) {
                    out.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
                }
                c.close()
            }
        } catch (e: Throwable) {
            // no access
        }
        return out
    }

    private fun showAddStatusSheet() {
        val d = Dialog(
            this,
            if (C.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        head.addView(icon(R.drawable.ic_close, 24, C.TEXT) { d.dismiss() })
        head.addView(txt(L.s("Ƙara matsayi", "Add status"), 20f, C.TEXT).apply { setPadding(dp(14), 0, 0, 0) })
        root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        fun action(ic: Int, label: String, go: () -> Unit): View {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { go() }
            }
            val b = ImageView(this)
            b.setImageResource(ic)
            b.setColorFilter(C.TEXT)
            b.setPadding(dp(18), dp(18), dp(18), dp(18))
            b.background = round(C.CARD, 32)
            c.addView(b, LinearLayout.LayoutParams(dp(64), dp(64)))
            c.addView(txt(label, 14f, C.TEXT).apply { setPadding(0, dp(6), 0, 0) })
            return c
        }
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        acts.addView(action(R.drawable.ic_edit, L.s("Rubutu", "Text")) {
            d.dismiss()
            composeText()
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        acts.addView(action(R.drawable.ic_music, L.s("Kiɗa", "Music")) {
            d.dismiss()
            composeText()
            pickAudio()
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        acts.addView(action(R.drawable.ic_gallery, "Gallery") {
            d.dismiss()
            pickImage(21)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        acts.addView(action(R.drawable.ic_mic, L.s("Murya", "Voice")) { soon() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(acts, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(txt("Recents", 16f, C.SUB, true).apply { setPadding(dp(16), dp(6), dp(16), dp(8)) })

        val sv = ScrollView(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        if (checkSelfPermission(imagePerm()) != PackageManager.PERMISSION_GRANTED) {
            val ask = pill(L.s("Ba da izinin ganin hotuna", "Allow photos"), C.LIME, C.FOREST) {
                d.dismiss()
                requestPermissions(arrayOf(imagePerm()), 4)
            }
            grid.addView(ask, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(24), dp(20), dp(24), dp(20)) })
        } else {
            val side = resources.displayMetrics.widthPixels / 3
            val uris = ArrayList<Uri?>()
            uris.add(null)
            uris.addAll(recentImages(59))
            val views = ArrayList<ImageView>()
            var rowL: LinearLayout? = null
            for (i in uris.indices) {
                if (i % 3 == 0) {
                    rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(rowL, LinearLayout.LayoutParams(MATCH, side))
                }
                val u = uris[i]
                val cell = FrameLayout(this)
                cell.setPadding(dp(1), dp(1), dp(1), dp(1))
                if (u == null) {
                    cell.setBackgroundColor(C.CARD)
                    val ic = ImageView(this)
                    ic.setImageResource(R.drawable.ic_camera)
                    ic.setColorFilter(C.ACCENT)
                    ic.setPadding(dp(36), dp(36), dp(36), dp(36))
                    cell.addView(ic, FrameLayout.LayoutParams(MATCH, MATCH))
                    cell.setOnClickListener {
                        d.dismiss()
                        takePhoto(22)
                    }
                } else {
                    val iv = ImageView(this)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    iv.setBackgroundColor(C.CARD)
                    cell.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                    views.add(iv)
                    cell.setOnClickListener {
                        d.dismiss()
                        statusImageUri(u)
                    }
                }
                rowL?.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
            val real = uris.filterNotNull()
            Thread {
                for (k in real.indices) {
                    val bmp = thumbOf(real[k], 260)
                    if (bmp != null) handler.post { if (d.isShowing) views[k].setImageBitmap(bmp) }
                }
            }.start()
        }
        d.setContentView(root)
        d.show()
        d.window?.setLayout(MATCH, MATCH)
    }

    // ----- making a status -----

    private fun composeText() {
        compKind = "t"
        compImg = null
        compAud = null
        compAudName = ""
        compText = ""
        compBg = statusBgs[0]
        showComposer()
    }

    private fun choosePhotoStatus() {
        val items = arrayOf(L.s("Camera", "Camera"), L.s("Gallery", "Gallery"))
        dlg().setItems(items) { _, w ->
            if (w == 0) takePhoto(22) else pickImage(21)
        }.show()
    }

    private fun statusImageUri(uri: Uri) {
        Thread {
            val f = Media.prepareImage(this, uri)
            handler.post {
                if (f == null) {
                    toast(L.s("Ba a iya amfani da wannan hoton ba.", "Could not use that photo."))
                } else {
                    if (screen != 7) {
                        compAud = null
                        compAudName = ""
                    }
                    compKind = "i"
                    compImg = f
                    compText = ""
                    showComposer()
                }
            }
        }.start()
    }

    private fun statusAudioUri(uri: Uri) {
        Thread {
            val d = Media.copyAudio(this, uri)
            handler.post {
                val f = d.file
                if (d.tooBig) {
                    toast(L.s("Kiɗan ya yi girma. Iyaka yanzu 10 MB.", "That music is too big. The limit for now is 10 MB."))
                } else if (f == null) {
                    toast(
                        L.s("Ba a iya amfani da wannan kiɗan ba. Zaɓi fayil na audio (mp3, m4a).", "Could not use that file. Choose an audio file (mp3, m4a).") +
                            " [" + d.err + "]"
                    )
                } else if (screen == 7) {
                    compAud = f
                    compAudName = d.name
                    showComposer()
                }
            }
        }.start()
    }

    private fun pickAudio() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.type = "audio/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(i, 23)
        } catch (e: Exception) {
            toast(L.s("Ba a iya buɗe fayiloli ba.", "Could not open files."))
        }
    }

    private fun cancelComposer() {
        tab = 1
        applyTheme()
        showHome()
    }

    private fun postStatus() {
        val text = compText.trim()
        if (compKind == "t" && text.isEmpty() && compAud == null) {
            toast(L.s("Rubuta wani abu tukuna.", "Type something first."))
            return
        }
        val st = Status(
            0, "", java.util.UUID.randomUUID().toString(), compKind, text, compBg,
            compImg?.path ?: "", compAud?.path ?: "", System.currentTimeMillis(), true
        )
        store.addStatus(st)
        net.announceAll()
        toast(L.s("An saka matsayi. Zai isa ga abokanka idan sun haɗu da kai.", "Status posted. Your contacts get it when your phones meet."))
        cancelComposer()
    }

    private fun showComposer() {
        screen = 7
        currentChat = null
        net.activeChat = null
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
        val img = compImg
        val isImg = compKind == "i" && img != null
        val root = FrameLayout(this)
        root.setBackgroundColor(if (isImg) Color.BLACK else compBg)
        if (isImg && img != null) {
            val bmp = Media.bitmapFor(img.path, 1280)
            if (bmp != null) {
                val iv = ImageView(this)
                iv.setImageBitmap(bmp)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                root.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }
        val input = EditText(this)
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.parseColor("#B3FFFFFF"))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (isImg) {
            input.hint = L.s("Ƙara rubutu…", "Add a caption…")
            input.textSize = 17f
            input.setBackgroundColor(Color.parseColor("#99000000"))
            input.setPadding(dp(16), dp(14), dp(84), dp(14))
            input.maxLines = 4
            root.addView(input, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        } else {
            input.hint = L.s("Rubuta matsayi", "Type a status")
            input.textSize = 28f
            input.typeface = Typeface.DEFAULT_BOLD
            input.gravity = Gravity.CENTER
            input.setBackgroundColor(Color.TRANSPARENT)
            input.setPadding(dp(24), dp(80), dp(24), dp(80))
            root.addView(input, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        input.setText(compText)
        input.setSelection(input.text.length)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                compText = s?.toString() ?: ""
            }
        })
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        top.addView(icon(R.drawable.ic_close, 24, Color.WHITE) { cancelComposer() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (!isImg) {
            top.addView(icon(R.drawable.ic_palette, 24, Color.WHITE) {
                val i = statusBgs.indexOf(compBg)
                compBg = statusBgs[(i + 1) % statusBgs.size]
                root.setBackgroundColor(compBg)
            })
        }
        top.addView(icon(R.drawable.ic_music, 24, Color.WHITE) { pickAudio() })
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        if (compAud != null) {
            val chip = txt("♪  " + compAudName + "   ✕", 14f, Color.WHITE, true)
            chip.background = round(Color.parseColor("#66000000"), 20)
            chip.setPadding(dp(16), dp(8), dp(16), dp(8))
            chip.maxLines = 1
            chip.ellipsize = TextUtils.TruncateAt.END
            chip.setOnClickListener {
                compAud = null
                compAudName = ""
                showComposer()
            }
            root.addView(
                chip,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = dp(64)
                    leftMargin = dp(24)
                    rightMargin = dp(24)
                }
            )
        }
        val send = ImageView(this)
        send.setImageResource(R.drawable.ic_send)
        send.setColorFilter(C.FOREST)
        send.setPadding(dp(15), dp(15), dp(15), dp(15))
        send.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C.LIME)
        }
        send.elevation = dp(6).toFloat()
        send.setOnClickListener { postStatus() }
        root.addView(
            send,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(14)
                bottomMargin = dp(14)
            }
        )
        setContentView(root)
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
        header.addView(icon(R.drawable.ic_arrow_back, 24, C.BAR_ICON) { showHome() })
        val tb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        tb.addView(txt(if (forwardTexts != null) L.s("Tura zuwa...", "Forward to...") else L.s("Zaɓi contact", "Select contact"), 18f, C.BAR_TEXT, true))
        tb.addView(txt("${net.contacts.size} contacts", 12f, C.BAR_SUB))
        header.addView(tb, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(icon(R.drawable.ic_search, 24, C.BAR_ICON) {
            val sb = contactSearchBar
            if (sb != null) sb.visibility = if (sb.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        header.addView(icon(R.drawable.ic_more_vert, 24, C.BAR_ICON) { v ->
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
                setOnClickListener {
                    val fw = forwardTexts
                    if (fw != null) {
                        forwardTexts = null
                        for (t in fw) net.sendText(h, t)
                        Sounds.sent()
                        showChat(h)
                    } else {
                        openPeer(h)
                    }
                }
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
        if (requestCode == 2) {
            toast(L.s("Yanzu riƙe mic ɗin don yin rikodi.", "Now hold the mic button to record."))
            return
        }
        if (requestCode == 4) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) showAddStatusSheet()
            return
        }
        if (requestCode == 5) {
            val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            val act = pendingCallAction
            pendingCallAction = null
            if (ok && act != null) act() else toast(L.s("Ana buƙatar izinin mic/camera don kira.", "Mic/camera permission is needed for calls."))
            return
        }
        if (requestCode == 6) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) takePhoto(pendingPhotoCode)
            return
        }
        if (hasPerms()) {
            net.loadContacts()
            net.start()
            MukhsService.start(this)
            askBatteryOnce()
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
            MukhsService.start(this)
            askBatteryOnce()
        } else if (!askedPerms) {
            askedPerms = true
            askForPerms()
        }
    }

    // asks once to let MukhsApp run in the background (phones like Tecno/Infinix close apps otherwise)
    private fun askNotificationsOn() {
        if (Notifier.enabled(this) || prefs().getBoolean("notifAsked", false)) return
        prefs().edit().putBoolean("notifAsked", true).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle(L.s("Kunna sanarwa", "Turn on notifications"))
            .setMessage(L.s("Don ka ga saƙonni da sunan mai aikawa da sauti ko da app a rufe, kunna Notifications.", "To get message alerts with the sender's name and sound even when the app is closed, turn on Notifications."))
            .setPositiveButton(L.s("Buɗe saiti", "Open settings")) { _, _ ->
                try {
                    val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    startActivity(i)
                } catch (e: Exception) {
                    // ignore
                }
            }
            .setNegativeButton(L.s("Daga baya", "Later"), null)
            .show()
    }

    private fun askBatteryOnce() {
        askNotificationsOn()
        if (prefs().getBoolean("batteryAsked", false)) return
        prefs().edit().putBoolean("batteryAsked", true).apply()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            }
        } catch (e: Exception) {
            // not available on this phone
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
        reactViews.clear()
        starViews.clear()
        bubbles.clear()
        voiceUis.clear()
        msgById.clear()
        selected.clear()
        normalHeader = null
        selHeader = null
        undoBar = null
        replyBar = null
        reactBar = null
        selReply = null
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
        if (tickViews.isEmpty() && reactViews.isEmpty()) return
        for (m in store.messages(h)) {
            if (m.mine) {
                val tv = tickViews[m.uid]
                if (tv != null) setTick(tv, m.state, true)
            }
            val old = msgById[m.id]
            if (old != null && old.react != m.react) {
                msgById[m.id] = old.copy(react = m.react)
                reactViews[m.uid]?.text = m.react
                reactViews[m.uid]?.visibility = if (m.react.isEmpty()) View.GONE else View.VISIBLE
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

    private fun toggleSelect(id: Long) {
        val b = bubbles[id] ?: return
        if (selected.contains(id)) {
            selected.remove(id)
            b.setBackgroundColor(Color.TRANSPARENT)
        } else {
            selected.add(id)
            b.setBackgroundColor(C.SELECT)
        }
        updateSelHeader()
    }

    private fun clearSelection() {
        for (id in selected) bubbles[id]?.setBackgroundColor(Color.TRANSPARENT)
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
        val one = selected.size == 1
        selReply?.visibility = if (one) View.VISIBLE else View.GONE
        reactBar?.visibility = if (one) View.VISIBLE else View.GONE
    }

    private fun snippet(m: Msg): String =
        previewOf(m).let { if (it.length > 120) it.substring(0, 120) + "…" else it }

    private fun startReply() {
        val id = selected.firstOrNull() ?: return
        val m = msgById[id] ?: return
        startReplyTo(m)
    }

    private fun startReplyTo(m: Msg) {
        replyTo = m
        replyName?.text = if (m.mine) L.s("Kai", "You") else net.nameOf(currentChat ?: "")
        replyText?.text = snippet(m)
        replyBar?.visibility = View.VISIBLE
        clearSelection()
        chatInput?.requestFocus()
    }

    private fun cancelReply() {
        replyTo = null
        replyBar?.visibility = View.GONE
    }

    private fun reactTo(e: String) {
        val h = currentChat ?: return
        val id = selected.firstOrNull() ?: return
        val m = msgById[id] ?: return
        val ne = if (m.react == e) "" else e
        store.setReact(m.uid, ne)
        net.sendReaction(h, m.uid, ne)
        msgById[id] = m.copy(react = ne)
        reactViews[m.uid]?.text = ne
        reactViews[m.uid]?.visibility = if (ne.isEmpty()) View.GONE else View.VISIBLE
        clearSelection()
    }

    private fun starSelected() {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        val allStarred = ids.all { msgById[it]?.star == true }
        store.setStar(ids, !allStarred)
        for (id in ids) {
            val m = msgById[id] ?: continue
            msgById[id] = m.copy(star = !allStarred)
            starViews[id]?.visibility = if (!allStarred) View.VISIBLE else View.GONE
        }
        toast(if (!allStarred) L.s("An saka tauraro.", "Starred.") else L.s("An cire tauraro.", "Unstarred."))
        clearSelection()
    }

    private fun forwardSelected() {
        val ids = selected.toList().sorted()
        val texts = ArrayList<String>()
        for (id in ids) {
            val m = msgById[id] ?: continue
            if (m.kind == "t") texts.add(m.body)
        }
        if (texts.isEmpty()) {
            toast(L.s("Rubutu kaɗai za a iya turawa yanzu.", "Only text can be forwarded for now."))
            return
        }
        clearSelection()
        forwardTexts = texts
        showContacts()
    }

    private fun copySelected() {
        val ids = selected.toList().sorted()
        val sb = StringBuilder()
        for (id in ids) {
            val m = msgById[id] ?: continue
            if (m.kind != "t") continue
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

    // the doodle wallpaper: faint white pictures on the dark theme, faint green on the light theme
    private fun chatWallpaper(): android.graphics.drawable.Drawable =
        Doodle.make(this, C.BG, if (C.dark) 0x18FFFFFF else 0x2A2E7D32)

    private fun showChat(h: String) {
        screen = 2
        currentChat = h
        net.activeChat = h
        stopPlay()
        clearChatState()
        replyTo = null
        lastDayKey = ""
        lastAppended = null
        lastRow = null
        album = null
        net.markChatRead(h)
        Notifier.cancel(this, h)
        window.statusBarColor = C.BAR
        window.navigationBarColor = C.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = chatWallpaper()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        normalHeader = header
        header.addView(icon(R.drawable.ic_arrow_back, 24, C.BAR_ICON) { showHome() })
        val hab = FrameLayout(this)
        hab.addView(avatar(net.nameOf(h), 40, net.isNearby(h), h))
        headerAvatarBox = hab
        hab.setOnClickListener {
            val pic = Profile.bitmap(this, h)
            if (pic != null) showImageViewer(pic, net.nameOf(h), "", "", null) else viewContact(h)
        }
        header.addView(hab)
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { viewContact(h) }
        }
        titleBox.addView(txt(net.nameOf(h), 18f, C.BAR_TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val st = txt(chatStatusText(h), 12f, C.BAR_SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        chatStatusView = st
        titleBox.addView(st)
        header.addView(titleBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(icon(R.drawable.ic_videocam, 24, C.BAR_ICON) { startCall(h, true) })
        header.addView(icon(R.drawable.ic_call, 24, C.BAR_ICON) { startCall(h, false) })
        header.addView(icon(R.drawable.ic_more_vert, 24, C.BAR_ICON) { v -> chatMenu(v, h) })
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
        sh.addView(icon(R.drawable.ic_arrow_back, 24, C.BAR_ICON) { clearSelection() })
        val sc = txt("0", 20f, C.BAR_TEXT, true)
        selCount = sc
        sh.addView(sc, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        val rp = icon(R.drawable.ic_reply, 24, C.BAR_ICON) { startReply() }
        selReply = rp
        sh.addView(rp)
        sh.addView(icon(R.drawable.ic_copy, 24, C.BAR_ICON) { copySelected() })
        sh.addView(icon(R.drawable.ic_star, 24, C.BAR_ICON) { starSelected() })
        sh.addView(icon(R.drawable.ic_delete, 24, C.BAR_ICON) { deleteSelected(h) })
        sh.addView(icon(R.drawable.ic_forward, 24, C.BAR_ICON) { forwardSelected() })
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

        // the message I am replying to
        val rb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.CARD, 12)
            setPadding(dp(10), dp(8), dp(4), dp(8))
            visibility = View.GONE
        }
        val strip = View(this)
        strip.setBackgroundColor(C.ACCENT)
        rb.addView(strip, LinearLayout.LayoutParams(dp(4), dp(40)))
        val rcol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        val rn = txt("", 13f, C.ACCENT, true)
        val rt = txt("", 14f, C.SUB).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        rcol.addView(rn)
        rcol.addView(rt)
        replyName = rn
        replyText = rt
        rb.addView(rcol, LinearLayout.LayoutParams(0, WRAP, 1f))
        rb.addView(icon(R.drawable.ic_close, 20, C.SUB) { cancelReply() })
        replyBar = rb
        root.addView(rb, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(4), dp(10), 0) })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val inputBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.INPUT, 24)
        }
        inputBox.addView(icon(R.drawable.ic_emoji, 22, C.SUB) { toggleEmoji() })
        val input = EditText(this).apply {
            hint = L.s("Rubuta saƙo", "Message")
            setHintTextColor(C.HINT)
            setTextColor(C.TEXT)
            background = null
            setPadding(dp(4), dp(10), dp(4), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        chatInput = input
        input.setOnClickListener { emojiPanel?.visibility = View.GONE }
        inputBox.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        inputBox.addView(icon(R.drawable.ic_attach, 22, C.SUB) { showAttachSheet() })
        val camBtn = icon(R.drawable.ic_camera, 22, C.SUB) { takePhoto() }
        inputBox.addView(camBtn)
        bar.addView(inputBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        inputBoxView = inputBox

        // shown while a voice note is being recorded
        val recRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.INPUT, 24)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        val redDot = View(this)
        redDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E53935"))
        }
        recRow.addView(redDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        val rtimer = txt("0:00", 16f, C.TEXT).apply { setPadding(dp(10), 0, 0, 0) }
        recTimer = rtimer
        recRow.addView(rtimer)
        val rhint = txt(L.s("‹ Zare hagu don soke", "‹ Slide left to cancel"), 14f, C.SUB).apply {
            gravity = Gravity.END
        }
        recHint = rhint
        recRow.addView(rhint, LinearLayout.LayoutParams(0, WRAP, 1f))
        recRowView = recRow
        bar.addView(recRow, LinearLayout.LayoutParams(0, WRAP, 1f))

        // round button: microphone when the box is empty (hold to record), send arrow when there is text
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
            camBtn.visibility = if (has) View.GONE else View.VISIBLE
        }
        updateAction()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAction()
            }
        })

        fun sendTyped() {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return
            val rep = replyTo
            val m = if (rep != null) net.sendText(h, t, snippet(rep), rep.mine) else net.sendText(h, t)
            cancelReply()
            Sounds.sent()
            appendMsg(m)
            input.setText("")
        }

        fun handleTouch(ev: MotionEvent): Boolean {
            val has = input.text.toString().trim().isNotEmpty()
            if (has) {
                if (ev.action == MotionEvent.ACTION_UP) sendTyped()
                return true
            }
            if (ev.action == MotionEvent.ACTION_DOWN) {
                if (startRecording()) {
                    recCancel = false
                    recDownX = ev.rawX
                }
            } else if (ev.action == MotionEvent.ACTION_MOVE) {
                if (recorder != null) {
                    recCancel = recDownX - ev.rawX > dp(110)
                    recHint?.text = if (recCancel) L.s("Saki don soke", "Release to cancel")
                    else L.s("‹ Zare hagu don soke", "‹ Slide left to cancel")
                }
            } else if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                if (recorder != null) finishRecording(ev.action == MotionEvent.ACTION_UP && !recCancel)
            }
            return true
        }
        actionBtn.setOnTouchListener { _, ev -> handleTouch(ev) }
        bar.addView(
            actionBtn,
            LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) }
        )
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        val ep = buildEmojiPanel()
        emojiPanel = ep
        root.addView(ep, LinearLayout.LayoutParams(MATCH, dp(250)))

        // quick reactions, shown above a selected message
        val rbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(C.CARD, 28)
            elevation = dp(8).toFloat()
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        for (e in arrayOf("👍", "❤️", "😂", "😮", "😢", "🙏")) {
            rbar.addView(
                txt(e, 26f, C.TEXT).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener { reactTo(e) }
                }
            )
        }
        reactBar = rbar
        val outer = FrameLayout(this)
        outer.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        outer.addView(
            rbar,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(72)
            }
        )
        setContentView(outer)

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

    // ----- what a message looks like in lists and replies -----

    private fun previewOf(m: Msg): String =
        if (m.kind == "c") callText(m)
        else if (m.kind == "a") L.s("Saƙon murya", "Voice message") + " (" + Media.fmtDur(m.dur) + ")"
        else if (m.kind == "i") (if (captionOf(m).isEmpty()) L.s("Hoto", "Photo") else "📷 " + captionOf(m))
        else m.body

    // the words written under a photo ("Photo" is only the default name, not a caption)
    private fun captionOf(m: Msg): String =
        if (m.kind == "i" && m.body != "Photo") m.body.trim() else ""

    // ----- voice notes -----

    private fun startRecording(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return false
        }
        stopPlay()
        try {
            val f = File(Media.dir(this), "rec_" + System.currentTimeMillis() + ".m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(24000)
            r.setAudioChannels(1)
            r.setMaxDuration(180000)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            recorder = r
            recFile = f
            recStart = System.currentTimeMillis()
            showRecUi(true)
            return true
        } catch (e: Exception) {
            recorder = null
            toast(L.s("Ba a iya fara rikodi ba.", "Could not start recording."))
            return false
        }
    }

    private fun showRecUi(on: Boolean) {
        inputBoxView?.visibility = if (on) View.GONE else View.VISIBLE
        recRowView?.visibility = if (on) View.VISIBLE else View.GONE
        handler.removeCallbacks(recTick)
        if (on) {
            recTimer?.text = "0:00"
            handler.post(recTick)
        }
    }

    private fun finishRecording(send: Boolean) {
        val r = recorder ?: return
        val f = recFile
        recorder = null
        recFile = null
        showRecUi(false)
        var ok = true
        try {
            r.stop()
        } catch (e: Exception) {
            ok = false
        }
        try {
            r.release()
        } catch (e: Exception) {
            // ignore
        }
        val secs = ((System.currentTimeMillis() - recStart) / 1000).toInt()
        if (f == null) return
        if (!send || !ok || secs < 1) {
            f.delete()
            if (send) toast(L.s("Riƙe don yin rikodi, saki don aikawa.", "Hold to record, release to send."))
            return
        }
        val h = currentChat ?: return
        sendMediaMessage(h, "a", f, "", secs)
    }

    private fun stopPlay() {
        handler.removeCallbacks(playTick)
        try {
            player?.release()
        } catch (e: Exception) {
            // ignore
        }
        player = null
        val ui = voiceUis[playingUid]
        if (ui != null) {
            ui.icon.setImageResource(R.drawable.ic_play)
            ui.seek.progress = 0
            ui.time.text = Media.fmtDur(ui.dur)
            ui.chip.visibility = View.GONE
        }
        playingUid = ""
    }

    private fun speedLabel(): String = if (playSpeed < 1.2f) "1x" else if (playSpeed < 1.7f) "1.5x" else "2x"

    private fun applySpeed(mp: MediaPlayer) {
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(playSpeed)
        } catch (e: Exception) {
            // this phone cannot change the speed
        }
    }

    // get a player ready for this voice note (not started yet)
    private fun ensurePlayer(m: Msg): Boolean {
        if (playingUid == m.uid && player != null) return true
        stopPlay()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(m.path)
            mp.prepare()
            mp.setOnCompletionListener { stopPlay() }
            player = mp
            playingUid = m.uid
            voiceUis[m.uid]?.seek?.max = Math.max(1, mp.duration)
            return true
        } catch (e: Exception) {
            stopPlay()
            toast(L.s("Ba a iya kunna sautin ba.", "Could not play this voice note."))
            return false
        }
    }

    // play / pause: pausing keeps the place, like WhatsApp
    private fun togglePlay(m: Msg) {
        if (!ensurePlayer(m)) return
        val mp = player ?: return
        val ui = voiceUis[m.uid]
        try {
            handler.removeCallbacks(playTick)
            if (mp.isPlaying) {
                mp.pause()
                ui?.icon?.setImageResource(R.drawable.ic_play)
            } else {
                mp.start()
                applySpeed(mp)
                ui?.icon?.setImageResource(R.drawable.ic_pause)
                ui?.chip?.visibility = View.VISIBLE
                handler.post(playTick)
            }
        } catch (e: Exception) {
            stopPlay()
        }
    }

    private fun cycleSpeed() {
        playSpeed = if (playSpeed < 1.2f) 1.5f else if (playSpeed < 1.7f) 2f else 1f
        for (u in voiceUis.values) u.chip.text = speedLabel()
        val mp = player
        try {
            if (mp != null && mp.isPlaying) applySpeed(mp)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ----- photos, documents, emoji -----

    private fun sendMediaMessage(h: String, kind: String, f: File, name: String, dur: Int) {
        val rep = replyTo
        val m = if (rep != null) net.sendMedia(h, kind, f.path, name, dur, snippet(rep), rep.mine)
        else net.sendMedia(h, kind, f.path, name, dur)
        cancelReply()
        Sounds.sent()
        if (screen == 2 && currentChat == h) appendMsg(m)
    }

    private fun sendImageUri(uri: Uri) = sendImageUris(listOf(uri))

    private fun sendImageUris(uris: List<Uri>) {
        val h = currentChat ?: return
        Thread {
            val made = ArrayList<File>()
            for (u in uris) {
                val f = Media.prepareImage(this, u)
                if (f != null) made.add(f)
            }
            handler.post {
                if (made.isEmpty()) toast(L.s("Ba a iya amfani da wannan hoton ba.", "Could not use that photo."))
                else showSendPreview(h, made)
            }
        }.start()
    }

    private fun sendDocUri(uri: Uri) {
        val h = currentChat ?: return
        Thread {
            val d = Media.copyDoc(this, uri)
            handler.post {
                val f = d.file
                if (d.tooBig) toast(L.s("Fayil ɗin ya yi girma. Iyaka yanzu 1 MB.", "That file is too big. The limit for now is 1 MB."))
                else if (f == null) toast(L.s("Ba a iya amfani da fayil ɗin ba.", "Could not use that file."))
                else sendMediaMessage(h, "d", f, d.name, 0)
            }
        }.start()
    }

    private fun pickImage(code: Int = 11) {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            if (code == 11) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(Intent.createChooser(i, L.s("Zaɓi hoto", "Choose a photo")), code)
        } catch (e: Exception) {
            toast(L.s("Ba a iya buɗe gallery ba.", "Could not open the gallery."))
        }
    }

    private fun pickDoc() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "*/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(Intent.createChooser(i, L.s("Zaɓi fayil", "Choose a file")), 13)
        } catch (e: Exception) {
            toast(L.s("Ba a iya buɗe fayiloli ba.", "Could not open files."))
        }
    }

    private var pendingPhotoCode = 12

    private fun takePhoto(code: Int = 12) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPhotoCode = code
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 6)
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            toast(L.s("Wannan wayar ba ta tallafa wa camera a nan tukuna. Yi amfani da Gallery.", "Camera is not supported on this Android version yet. Use Gallery."))
            return
        }
        try {
            val cv = ContentValues()
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "MukhsApp_" + System.currentTimeMillis() + ".jpg")
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MukhsApp")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) {
                toast(L.s("Ba a iya buɗe camera ba.", "Could not open the camera."))
                return
            }
            camUri = uri
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(i, code)
        } catch (e: Exception) {
            toast(L.s("Ba a iya buɗe camera ba.", "Could not open the camera."))
        }
    }

    private fun showAttachSheet() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = round(C.CARD, 22)
            setPadding(dp(12), dp(22), dp(12), dp(18))
        }
        fun item(res: Int, label: String, color: String, action: () -> Unit) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener {
                    d.dismiss()
                    action()
                }
            }
            val ic = ImageView(this)
            ic.setImageResource(res)
            ic.setColorFilter(Color.WHITE)
            ic.setPadding(dp(15), dp(15), dp(15), dp(15))
            ic.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color))
            }
            col.addView(ic, LinearLayout.LayoutParams(dp(58), dp(58)))
            col.addView(
                txt(label, 13f, C.TEXT).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) }
            )
            box.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        item(R.drawable.ic_gallery, "Gallery", "#7E57C2") { pickImage() }
        item(R.drawable.ic_camera, "Camera", "#EC407A") { takePhoto() }
        item(R.drawable.ic_doc, L.s("Fayil", "Document"), "#42A5F5") { pickDoc() }
        val frame = FrameLayout(this)
        frame.setPadding(dp(12), 0, dp(12), dp(12))
        frame.addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        d.setContentView(frame)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setGravity(Gravity.BOTTOM)
        d.show()
        d.window?.setLayout(MATCH, WRAP)
    }

    private val emojiList = (
        "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😍 🥰 😘 😗 😋 😛 😜 🤪 😎 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 😶 😐 😑 😬 🙄 😯 😴 🤤 😪 😵 🤐 😷 🤒 🤕 🤢 🤮 🥴 😈 👿 💀 👻 👽 🤖 💩 " +
            "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ✋ 🤚 👋 👏 🙌 👐 🤲 🙏 🤝 💪 ✍️ 💅 " +
            "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 🔥 ⭐ 🌟 ✨ 🎉 🎊 🎁 🏆 " +
            "🐶 🐱 🐭 🐰 🦊 🐻 🐼 🦁 🐯 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦅 🦋 🌹 🌺 🌻 🌴 🌍 🌙 ☀️ ⛅ 🌧️ " +
            "🍎 🍌 🍉 🍇 🍓 🍕 🍔 🍟 🍗 🍚 ☕ 🍵 🥤 🍰 🍫 🍬 ⚽ 🏀 🚗 ✈️ 🏠 📱 💻 🎧 📷 💰 🕌 📖"
        ).split(" ")

    private fun buildEmojiPanel(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        sv.visibility = View.GONE
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        var row: LinearLayout? = null
        var n = 0
        for (e in emojiList) {
            if (e.isEmpty()) continue
            if (n % 8 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val t = txt(e, 26f, C.TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    val et = chatInput
                    if (et != null) {
                        val st = Math.max(0, et.selectionStart)
                        val en = Math.max(0, et.selectionEnd)
                        et.text.replace(Math.min(st, en), Math.max(st, en), e)
                    }
                }
            }
            row?.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            n++
        }
        sv.addView(col)
        return sv
    }

    private fun toggleEmoji() {
        val p = emojiPanel ?: return
        val et = chatInput
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (p.visibility == View.VISIBLE) {
            p.visibility = View.GONE
            if (et != null) {
                et.requestFocus()
                imm.showSoftInput(et, 0)
            }
        } else {
            if (et != null) imm.hideSoftInputFromWindow(et.windowToken, 0)
            p.visibility = View.VISIBLE
        }
    }

    // ----- how photos, voice notes and documents look inside a bubble -----

    private fun mediaView(m: Msg, textColor: Int): View {
        if (m.kind == "a") {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tone = if (m.mine) C.FOREST else C.ACCENT
            val iv = ImageView(this)
            iv.setImageResource(R.drawable.ic_play)
            iv.setColorFilter(tone)
            row.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)))
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val seek = WaveView(this, m.uid.hashCode())
            seek.tone = tone
            seek.max = Math.max(1, m.dur * 1000)
            seek.progress = 0
            val time = txt(Media.fmtDur(m.dur), 12f, if (m.mine) C.GREY_ON_LIME else C.SUB)
            time.setPadding(dp(2), 0, 0, 0)
            mid.addView(seek, LinearLayout.LayoutParams(dp(150), dp(32)))
            mid.addView(time)
            row.addView(mid)
            val chip = txt(speedLabel(), 12f, textColor, true).apply {
                gravity = Gravity.CENTER
                background = round(Color.parseColor("#22000000"), 12)
                setPadding(dp(9), dp(4), dp(9), dp(4))
                visibility = if (playingUid == m.uid && player?.isPlaying == true) View.VISIBLE else View.GONE
                setOnClickListener { cycleSpeed() }
            }
            row.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            voiceUis[m.uid] = VoiceUi(iv, seek, time, chip, m.dur)
            seek.onTouchSeek = { ev, frac ->
                if (ev == 0) {
                    seeking = true
                } else if (ev == 1) {
                    time.text = Media.fmtDur((frac * seek.max).toInt() / 1000)
                } else {
                    seeking = false
                    if (ensurePlayer(m)) {
                        val mp = player
                        if (mp != null) {
                            val t = (frac * mp.duration).toInt()
                            try {
                                mp.seekTo(t)
                            } catch (e: Exception) {
                                // ignore
                            }
                            seek.progress = t
                            time.text = Media.fmtDur(t / 1000)
                        }
                    }
                }
            }
            val pick = View.OnLongClickListener {
                toggleSelect(m.id)
                true
            }
            row.setOnLongClickListener(pick)
            chip.setOnLongClickListener(pick)
            iv.setOnLongClickListener(pick)
            iv.setOnClickListener {
                if (selected.isNotEmpty()) toggleSelect(m.id) else togglePlay(m)
            }
            return row
        }
        if (m.kind == "i") {
            val bmp = Media.bitmapFor(m.path, 600)
            if (bmp == null) {
                return txt(L.s("Hoto (ba a samu ba)", "Photo (missing)"), 14f, textColor)
            }
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.background = round(Color.parseColor("#22000000"), 12)
            iv.outlineProvider = ViewOutlineProvider.BACKGROUND
            iv.clipToOutline = true
            val w = dp(230)
            val ratio = bmp.height.toFloat() / Math.max(1, bmp.width)
            val hh = Math.min(dp(320), Math.max(dp(120), (w * ratio).toInt()))
            iv.layoutParams = LinearLayout.LayoutParams(w, hh)
            iv.setOnClickListener {
                if (selected.isNotEmpty()) toggleSelect(m.id) else showFullImage(m)
            }
            iv.setOnLongClickListener {
                toggleSelect(m.id)
                true
            }
            val cap = captionOf(m)
            if (cap.isEmpty()) return iv
            val colm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            colm.addView(iv)
            colm.addView(txt(cap, 16f, textColor).apply {
                maxWidth = w
                setPadding(0, dp(6), 0, 0)
            })
            return colm
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_doc)
        ic.setColorFilter(if (m.mine) C.FOREST else C.ACCENT)
        row.addView(ic, LinearLayout.LayoutParams(dp(36), dp(36)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
        }
        col.addView(txt(m.body, 15f, textColor).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(190)
        })
        col.addView(txt(L.s("Danna don ajiyewa", "Tap to save"), 12f, if (m.mine) C.GREY_ON_LIME else C.SUB))
        row.addView(col)
        row.setOnClickListener { saveDoc(m) }
        return row
    }

    private fun showFullImage(m: Msg) {
        val bmp = Media.bitmapFor(m.path, 1600) ?: return
        val who = if (m.mine) L.s("Kai", "You") else net.nameOf(currentChat ?: "")
        showImageViewer(bmp, who, dayLabel(m.ts) + ", " + fmtTime(m.ts), captionOf(m), m.path)
    }

    // full screen picture: pinch or double-tap to zoom, drag to move, tap to hide the bars,
    // drag down to close. savePath is set when the picture can be saved to the gallery.
    private fun showImageViewer(bmp: Bitmap, title: String, sub: String, caption: String, savePath: String?) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val zv = ZoomImageView(this)
        zv.setBitmap(bmp)
        root.addView(zv, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(4), dp(10), dp(8), dp(8))
        }
        top.addView(icon(R.drawable.ic_arrow_back, 24, Color.WHITE) { d.dismiss() })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        titles.addView(txt(title, 17f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (sub.isNotEmpty()) titles.addView(txt(sub, 12f, Color.parseColor("#CCFFFFFF")))
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (savePath != null) top.addView(icon(R.drawable.ic_download, 24, Color.WHITE) { saveImageToGallery(savePath) })
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        var capBar: View? = null
        if (caption.isNotEmpty()) {
            val cb = txt(caption, 16f, Color.WHITE).apply {
                setBackgroundColor(Color.parseColor("#88000000"))
                setPadding(dp(16), dp(12), dp(16), dp(16))
                maxLines = 5
            }
            capBar = cb
            root.addView(cb, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }
        zv.onTap = {
            val vis = if (top.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            top.visibility = vis
            capBar?.visibility = vis
        }
        zv.onDismiss = { d.dismiss() }
        d.setContentView(root)
        d.show()
    }

    // the "download" button in the picture viewer
    private fun saveImageToGallery(path: String) {
        if (Build.VERSION.SDK_INT < 29) {
            toast(L.s("Wannan sigar Android ba ta tallafa wa ajiyewa a nan ba.", "Saving is not supported on this Android version."))
            return
        }
        try {
            val cv = ContentValues()
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "MukhsApp_" + System.currentTimeMillis() + ".jpg")
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MukhsApp")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) {
                toast(L.s("Ba a iya ajiyewa ba.", "Could not save the photo."))
                return
            }
            contentResolver.openOutputStream(uri)?.use { out -> out.write(File(path).readBytes()) }
            toast(L.s("An ajiye a Gallery.", "Saved to Gallery."))
        } catch (e: Exception) {
            toast(L.s("Ba a iya ajiyewa ba.", "Could not save the photo."))
        }
    }

    // after choosing or taking a photo: look at it, write a caption, draw / add text / turn it, then send
    private class PrevItem(val file: File, var caption: String = "", var edited: Bitmap? = null)

    // the page that opens before photos are sent: one or many pictures, each can be cropped,
    // turned, drawn on, given text and its own caption, or removed from the list
    private fun showSendPreview(h: String, files: List<File>) {
        val items = ArrayList<PrevItem>()
        for (f in files) items.add(PrevItem(f))
        if (items.isEmpty()) return
        var cur = 0
        val first = Media.bitmapFor(items[0].file.path, 1280)
        if (first == null) {
            toast(L.s("Ba a iya amfani da wannan hoton ba.", "Could not use that photo."))
            return
        }
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        var sent = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val ev = EditImageView(this)
        ev.setBitmap(first)
        val capIn = EditText(this)

        // colours for drawing and text
        val colors = intArrayOf(
            Color.WHITE, Color.parseColor("#F44336"), Color.parseColor("#FFEB3B"),
            Color.parseColor("#4CAF50"), Color.parseColor("#2196F3"), Color.parseColor("#9C27B0"), Color.BLACK
        )
        val dots = ArrayList<View>()
        fun markColors() {
            for (i in dots.indices) {
                dots[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colors[i])
                    setStroke(dp(if (colors[i] == ev.color) 3 else 1), if (colors[i] == ev.color) C.LIME else Color.GRAY)
                }
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        topBar.addView(icon(R.drawable.ic_close, 24, Color.WHITE) { d.dismiss() })
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val cropBtn = icon(R.drawable.ic_crop, 24, Color.WHITE) {}
        cropBtn.setOnClickListener {
            if (ev.cropMode) {
                ev.applyCrop()
                cropBtn.setColorFilter(Color.WHITE)
            } else {
                ev.startCrop()
                cropBtn.setColorFilter(C.LIME)
                toast(L.s("Ja kusurwoyi, sannan ka sake danna don gamawa.", "Drag the corners, then tap again to finish."))
            }
        }
        topBar.addView(cropBtn)
        topBar.addView(icon(R.drawable.ic_rotate, 24, Color.WHITE) { ev.rotate() })
        topBar.addView(icon(R.drawable.ic_text, 24, Color.WHITE) {
            val et = EditText(this)
            et.setSingleLine(true)
            dlg()
                .setTitle(L.s("Rubuta rubutu", "Add text"))
                .setView(et)
                .setPositiveButton("OK") { _, _ ->
                    val s = et.text.toString().trim()
                    if (s.isNotEmpty()) {
                        ev.drawMode = false
                        ev.addText(s)
                    }
                }
                .setNegativeButton(L.s("Soke", "Cancel"), null)
                .show()
        })
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE
        }
        val penBtn = icon(R.drawable.ic_edit, 24, Color.WHITE) {}
        penBtn.setOnClickListener {
            ev.drawMode = !ev.drawMode
            penBtn.setColorFilter(if (ev.drawMode) C.LIME else Color.WHITE)
            colorRow.visibility = if (ev.drawMode) View.VISIBLE else View.GONE
        }
        topBar.addView(penBtn)
        root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))

        for (i in colors.indices) {
            val dot = View(this)
            dot.setOnClickListener {
                ev.color = colors[i]
                markColors()
            }
            dots.add(dot)
            colorRow.addView(dot, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
        }
        colorRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        colorRow.addView(icon(R.drawable.ic_undo, 22, Color.WHITE) { ev.undo() })
        markColors()
        root.addView(colorRow, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(ev, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // the little pictures at the bottom, each with a bin to take it out
        val stripScroll = HorizontalScrollView(this)
        stripScroll.isHorizontalScrollBarEnabled = false
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        stripScroll.addView(strip)
        root.addView(stripScroll, LinearLayout.LayoutParams(MATCH, WRAP))

        fun commit() {
            if (ev.cropMode) ev.applyCrop()
            items[cur].caption = capIn.text.toString()
            if (ev.isEdited()) items[cur].edited = ev.bake()
        }

        fun refreshStrip() {
            strip.removeAllViews()
            stripScroll.visibility = if (items.size > 1) View.VISIBLE else View.GONE
            for (i in items.indices) {
                val cell = FrameLayout(this)
                val th = ImageView(this)
                th.scaleType = ImageView.ScaleType.CENTER_CROP
                val tb = items[i].edited ?: Media.bitmapFor(items[i].file.path, 160)
                if (tb != null) th.setImageBitmap(tb)
                th.background = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(if (i == cur) 3 else 0), C.LIME)
                }
                th.clipToOutline = true
                cell.addView(th, FrameLayout.LayoutParams(dp(60), dp(60)))
                val bin = ImageView(this)
                bin.setImageResource(R.drawable.ic_delete)
                bin.setColorFilter(Color.WHITE)
                bin.setPadding(dp(3), dp(3), dp(3), dp(3))
                bin.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC000000"))
                }
                cell.addView(bin, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
                th.setOnClickListener {
                    if (i != cur) {
                        commit()
                        cur = i
                        val nb = items[i].edited ?: Media.bitmapFor(items[i].file.path, 1280)
                        if (nb != null) ev.loadFresh(nb)
                        capIn.setText(items[i].caption)
                        refreshStrip()
                    }
                }
                bin.setOnClickListener {
                    val gone = items.removeAt(i)
                    gone.file.delete()
                    if (items.isEmpty()) {
                        d.dismiss()
                    } else {
                        if (cur >= i && cur > 0) cur -= 1
                        val nb = items[cur].edited ?: Media.bitmapFor(items[cur].file.path, 1280)
                        if (nb != null) ev.loadFresh(nb)
                        capIn.setText(items[cur].caption)
                        refreshStrip()
                    }
                }
                strip.addView(cell, LinearLayout.LayoutParams(dp(60), dp(60)).apply { rightMargin = dp(8) })
            }
        }
        refreshStrip()

        capIn.apply {
            hint = L.s("Ƙara bayani...", "Add a caption...")
            setHintTextColor(Color.parseColor("#99FFFFFF"))
            setTextColor(Color.WHITE)
            background = null
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
        }
        val capBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(Color.parseColor("#33FFFFFF"), 24)
        }
        capBox.addView(capIn, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(capBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(8), dp(10), dp(4)) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        val who = txt(net.nameOf(h), 15f, Color.WHITE).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = round(Color.parseColor("#33FFFFFF"), 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bottom.addView(who, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sendBtn = FrameLayout(this)
        sendBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C.LIME)
        }
        val sendIc = ImageView(this)
        sendIc.setImageResource(R.drawable.ic_send)
        sendIc.setColorFilter(C.FOREST)
        sendIc.setPadding(dp(13), dp(13), dp(13), dp(13))
        sendBtn.addView(sendIc, FrameLayout.LayoutParams(dp(50), dp(50)))
        sendBtn.setOnClickListener {
            commit()
            sent = true
            val list = ArrayList<PrevItem>(items)
            d.dismiss()
            Thread {
                for (it2 in list) {
                    val cap = it2.caption.trim()
                    val name = if (cap.isEmpty()) "Photo" else cap
                    val eb = it2.edited
                    var out = it2.file
                    if (eb != null) {
                        val nf = Media.saveBitmap(this, eb)
                        if (nf != null) {
                            it2.file.delete()
                            out = nf
                        }
                    }
                    val fin = out
                    handler.post { sendMediaMessage(h, "i", fin, name, 0) }
                    try {
                        Thread.sleep(150)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }.start()
        }
        bottom.addView(sendBtn, LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) })
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))

        d.setOnDismissListener {
            if (!sent) for (it2 in items) it2.file.delete()
        }
        d.setContentView(root)
        d.show()
        d.window?.setLayout(MATCH, MATCH)
    }

    // documents are saved into the phone's Downloads folder
    private fun saveDoc(m: Msg) {
        if (Build.VERSION.SDK_INT < 29) {
            toast(L.s("Wannan sigar Android ba ta tallafa wa ajiyewa a nan ba.", "Saving files is not supported on this Android version."))
            return
        }
        try {
            val name = if (m.body.isEmpty()) "file" else m.body
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val cv = ContentValues()
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
            cv.put(MediaStore.Downloads.MIME_TYPE, mime)
            cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) {
                toast(L.s("Ba a iya ajiyewa ba.", "Could not save the file."))
                return
            }
            contentResolver.openOutputStream(uri)?.use { out -> out.write(File(m.path).readBytes()) }
            toast(L.s("An ajiye a Downloads.", "Saved to Downloads."))
        } catch (e: Exception) {
            toast(L.s("Ba a iya ajiyewa ba.", "Could not save the file."))
        }
    }

    private fun addNote(s: String) {
        val box = chatBox ?: return
        val v = txt(s, 13f, C.SUB).apply { gravity = Gravity.CENTER }
        box.addView(v, lp(8))
    }

    // Gives the words exactly the width they need. Without this a bubble could end up narrower
    // than its words, and the end of the message was cut off ("Good morning" showed as "Good").
    private fun fitText(tv: TextView) {
        val maxW = (resources.displayMetrics.widthPixels * 0.72).toInt()
        val t = tv.text
        if (t.isEmpty()) return
        val sl = android.text.StaticLayout.Builder.obtain(t, 0, t.length, tv.paint, maxW).build()
        var need = 0f
        for (i in 0 until sl.lineCount) need = Math.max(need, sl.getLineWidth(i))
        tv.layoutParams = LinearLayout.LayoutParams(Math.min(maxW, Math.ceil(need.toDouble()).toInt() + dp(3)), WRAP)
    }

    private fun callParts(m: Msg): Triple<String, Boolean, Int> {
        val p = m.body.split("|")
        return Triple(p.getOrElse(0) { "" }, p.getOrElse(1) { "a" } == "v", p.getOrElse(2) { "0" }.toIntOrNull() ?: 0)
    }

    private fun callText(m: Msg): String {
        val (res, vid, _) = callParts(m)
        val what = if (vid) L.s("kiran bidiyo", "video call") else L.s("kiran murya", "voice call")
        val whatEn = if (vid) "video call" else "voice call"
        return if (res == "missed") L.s("Ba a ɗauki $what ba", "Missed $whatEn")
        else if (vid) L.s("Kiran bidiyo", "Video call") else L.s("Kiran murya", "Voice call")
    }

    // "Missed voice call - Tap to call back", the same as WhatsApp shows inside the chat
    private fun addCallCard(m: Msg) {
        val box = chatBox ?: return
        val (res, vid, dur) = callParts(m)
        val missed = res == "missed"
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(if (m.mine) C.MINE else C.CARD, 16)
            setPadding(dp(10), dp(10), dp(12), dp(8))
        }
        val ic = ImageView(this)
        ic.setImageResource(if (vid) R.drawable.ic_videocam else R.drawable.ic_call)
        ic.setColorFilter(if (missed) Color.parseColor("#E53935") else if (m.mine) C.FOREST else C.ACCENT)
        ic.setPadding(dp(10), dp(10), dp(10), dp(10))
        ic.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#22000000"))
        }
        bubble.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(14), 0)
        }
        col.addView(txt(callText(m), 16f, if (m.mine) C.FOREST else C.TEXT, true))
        val sub = if (missed) L.s("Danna don sake kira", "Tap to call back")
        else if (dur > 0) String.format("%d:%02d", dur / 60, dur % 60)
        else if (m.mine) L.s("Ba a amsa ba", "No answer") else L.s("An ƙi", "Declined")
        col.addView(txt(sub, 13f, if (m.mine) C.GREY_ON_LIME else C.SUB))
        bubble.addView(col)
        bubble.addView(txt(fmtTime(m.ts), 11f, if (m.mine) C.GREY_ON_LIME else C.SUB))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (m.mine) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP))
        bubbles[m.id] = row
        msgById[m.id] = m
        row.setOnClickListener {
            if (selected.isNotEmpty()) toggleSelect(m.id) else currentChat?.let { startCall(it, vid) }
        }
        row.setOnLongClickListener {
            toggleSelect(m.id)
            true
        }
        box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        lastAppended = null
        album = null
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendMsg(m: Msg) {
        val box = chatBox ?: return
        val key = dayStart(m.ts).toString()
        if (key != lastDayKey) {
            lastAppended = null
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
        if (m.kind == "c") {
            addCallCard(m)
            return
        }
        val before = lastAppended
        if (m.kind == "i" && before != null && before.kind == "i" && before.mine == m.mine &&
            Math.abs(m.ts - before.ts) < 40000 && m.quote.isEmpty()
        ) {
            addToAlbum(m)
            return
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(if (m.mine) C.MINE else C.CARD, 16)
            setPadding(dp(12), dp(8), dp(10), dp(6))
        }
        val textColor = if (m.mine) C.FOREST else C.TEXT
        if (m.quote.isNotEmpty()) {
            val qb = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = round(Color.parseColor("#22000000"), 8)
                setPadding(0, 0, dp(8), 0)
            }
            val qs = View(this)
            qs.setBackgroundColor(if (m.mine) C.FOREST else C.ACCENT)
            qb.addView(qs, LinearLayout.LayoutParams(dp(4), MATCH))
            val qc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), 0, dp(4))
            }
            qc.addView(
                txt(
                    if (m.qmine) L.s("Kai", "You") else net.nameOf(currentChat ?: ""),
                    12f, if (m.mine) C.FOREST else C.ACCENT, true
                )
            )
            qc.addView(txt(m.quote, 13f, if (m.mine) C.GREY_ON_LIME else C.SUB).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            qb.addView(qc, LinearLayout.LayoutParams(WRAP, WRAP))
            bubble.addView(qb, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(4) })
        }
        val limit = 2000
        val long = m.body.length > limit
        val body = txt(if (long) m.body.substring(0, limit) + "…" else m.body, 16f, textColor).apply {
            maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
            setHorizontallyScrolling(false)
            setSingleLine(false)
        }
        if (m.kind == "t") fitText(body)
        if (m.kind == "t") bubble.addView(body) else bubble.addView(mediaView(m, textColor))
        if (long && m.kind == "t") {
            val more = txt("Read more", 15f, if (m.mine) C.BLUE_ON_LIME else C.ACCENT, true)
            more.setOnClickListener {
                body.text = m.body
                fitText(body)
                (more.parent as? LinearLayout)?.removeView(more)
            }
            bubble.addView(more)
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val rv = TextView(this)
        rv.textSize = 15f
        rv.text = m.react
        rv.visibility = if (m.react.isEmpty()) View.GONE else View.VISIBLE
        reactViews[m.uid] = rv
        meta.addView(rv, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        val sv = txt("★", 11f, if (m.mine) C.GREY_ON_LIME else C.SUB)
        sv.visibility = if (m.star) View.VISIBLE else View.GONE
        sv.setPadding(0, 0, dp(4), 0)
        starViews[m.id] = sv
        meta.addView(sv)
        meta.addView(txt(fmtTime(m.ts), 11f, if (m.mine) C.GREY_ON_LIME else C.SUB))
        if (m.mine) {
            val tv = tickView(m.state, true, 12f)
            tv.setPadding(dp(4), 0, 0, 0)
            meta.addView(tv)
            tickViews[m.uid] = tv
        }
        bubble.addView(meta, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (m.mine) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP))
        bubbles[m.id] = row
        msgById[m.id] = m
        row.setOnLongClickListener {
            toggleSelect(m.id)
            true
        }
        row.setOnClickListener {
            if (selected.isNotEmpty()) toggleSelect(m.id)
        }
        // slide a message to the right to reply to it (text, voice note or photo)
        var sx = 0f
        var sy = 0f
        var sliding = false
        var buzzed = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        row.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX
                    sy = ev.rawY
                    sliding = false
                    buzzed = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - sx
                    val dy = ev.rawY - sy
                    if (!sliding && dx > slop * 2 && dx > Math.abs(dy) * 1.5f && selected.isEmpty()) {
                        sliding = true
                        row.cancelLongPress()
                        row.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (sliding) {
                        val shift = Math.min(dx, dp(90).toFloat())
                        bubble.translationX = shift
                        if (!buzzed && dx > dp(64)) {
                            buzzed = true
                            row.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sliding) {
                        sliding = false
                        val done = ev.actionMasked == MotionEvent.ACTION_UP && ev.rawX - sx > dp(64)
                        bubble.animate().translationX(0f).setDuration(150).start()
                        if (done) startReplyTo(m)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        lastAppended = m
        lastRow = row
        album = null
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ----- several photos sent together are shown as one collage bubble -----

    private class Album(val mine: Boolean, val msgs: ArrayList<Msg>)

    private var album: Album? = null
    private var lastAppended: Msg? = null
    private var lastRow: View? = null

    private fun addToAlbum(m: Msg) {
        val box = chatBox ?: return
        val prev = lastAppended ?: return
        val al = album ?: Album(m.mine, arrayListOf(prev)).also { album = it }
        al.msgs.add(m)
        val old = lastRow
        val idx = if (old != null) box.indexOfChild(old) else -1
        if (old != null) box.removeView(old)
        val row = buildAlbumRow(al)
        if (idx >= 0) box.addView(row, idx, LinearLayout.LayoutParams(MATCH, WRAP))
        else box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        lastRow = row
        lastAppended = m
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun buildAlbumRow(al: Album): View {
        val last = al.msgs[al.msgs.size - 1]
        val mine = al.mine
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(if (mine) C.MINE else C.CARD, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val total = dp(240)
        val gap = dp(2)
        val cell = (total - gap) / 2
        val shown = Math.min(al.msgs.size, 4)
        var line: LinearLayout? = null
        for (i in 0 until shown) {
            if (i % 2 == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                bubble.addView(line, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) topMargin = gap })
            }
            val msg = al.msgs[i]
            val fl = FrameLayout(this)
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val bm = Media.bitmapFor(msg.path, 360)
            if (bm != null) iv.setImageBitmap(bm)
            iv.background = round(Color.parseColor("#22000000"), 10)
            iv.outlineProvider = ViewOutlineProvider.BACKGROUND
            iv.clipToOutline = true
            fl.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            if (i == 3 && al.msgs.size > 4) {
                val more = txt("+" + (al.msgs.size - 3), 26f, Color.WHITE, true)
                more.gravity = Gravity.CENTER
                more.background = round(Color.parseColor("#88000000"), 10)
                fl.addView(more, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            fl.setOnClickListener {
                if (selected.isNotEmpty()) toggleSelect(last.id) else showFullImage(msg)
            }
            fl.setOnLongClickListener {
                toggleSelect(last.id)
                true
            }
            val wide = if (shown == 3 && i == 2) total else cell
            line!!.addView(fl, LinearLayout.LayoutParams(wide, cell).apply { if (wide == cell && i % 2 == 0 && shown > i + 1) rightMargin = gap })
        }
        var cap = ""
        for (x in al.msgs) {
            if (cap.isEmpty()) cap = captionOf(x)
        }
        if (cap.isNotEmpty()) {
            bubble.addView(txt(cap, 16f, if (mine) C.FOREST else C.TEXT).apply {
                maxWidth = total
                setPadding(dp(8), dp(6), dp(8), 0)
            })
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), 0)
        }
        val rv = TextView(this)
        rv.textSize = 15f
        rv.text = last.react
        rv.visibility = if (last.react.isEmpty()) View.GONE else View.VISIBLE
        reactViews[last.uid] = rv
        meta.addView(rv, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        val sv = txt("★", 11f, if (mine) C.GREY_ON_LIME else C.SUB)
        sv.visibility = if (last.star) View.VISIBLE else View.GONE
        sv.setPadding(0, 0, dp(4), 0)
        starViews[last.id] = sv
        meta.addView(sv)
        meta.addView(txt(fmtTime(last.ts), 11f, if (mine) C.GREY_ON_LIME else C.SUB))
        if (mine) {
            val tv = tickView(last.state, true, 12f)
            tv.setPadding(dp(4), 0, 0, 0)
            meta.addView(tv)
            tickViews[last.uid] = tv
        }
        bubble.addView(meta, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP))
        for (x in al.msgs) {
            bubbles[x.id] = row
            msgById[x.id] = x
        }
        row.setOnLongClickListener {
            toggleSelect(last.id)
            true
        }
        row.setOnClickListener {
            if (selected.isNotEmpty()) toggleSelect(last.id)
        }
        return row
    }
}
