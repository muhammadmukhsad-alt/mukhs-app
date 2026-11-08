package com.mukhsapp.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    val LIME = Color.parseColor("#E6FF61")
    val FOREST = Color.parseColor("#004538")
    val DEEP = Color.parseColor("#00221B")
    val MUTED = Color.parseColor("#B8D8CF")
    val PILL = Color.parseColor("#0E6A55")
    val BLUE_ON_LIME = Color.parseColor("#0277BD")
    val GREY_ON_LIME = Color.parseColor("#4F6F66")

    // theme colours: change with light / dark
    var dark = true
    var BG = DEEP          // screen background
    var BAR = FOREST       // top bar and bottom tabs (always deep green)
    var CARD = FOREST      // rows and received bubbles
    var INPUT = FOREST     // text boxes
    var TEXT = Color.WHITE
    var SUB = MUTED
    var HINT = Color.parseColor("#88FFFFFF")
    var ACCENT = LIME      // titles on the background
    var BTN = LIME
    var ON_BTN = FOREST
    var SELECT = Color.parseColor("#2A7A66")
    var TICK_GREY = Color.parseColor("#9CC3B8")
    var TICK_BLUE = Color.parseColor("#34B7F1")

    fun apply(d: Boolean) {
        dark = d
        if (d) {
            BG = DEEP; CARD = FOREST; INPUT = FOREST
            TEXT = Color.WHITE; SUB = MUTED; HINT = Color.parseColor("#88FFFFFF")
            ACCENT = LIME; BTN = LIME; ON_BTN = FOREST
            SELECT = Color.parseColor("#2A7A66")
            TICK_GREY = Color.parseColor("#9CC3B8"); TICK_BLUE = Color.parseColor("#34B7F1")
        } else {
            BG = Color.parseColor("#F2F7F5"); CARD = Color.WHITE; INPUT = Color.parseColor("#E1EBE7")
            TEXT = Color.parseColor("#0B1F1A"); SUB = Color.parseColor("#5B7A71"); HINT = Color.parseColor("#7F938D")
            ACCENT = FOREST; BTN = FOREST; ON_BTN = LIME
            SELECT = Color.parseColor("#BFE3D6")
            TICK_GREY = Color.parseColor("#7F938D"); TICK_BLUE = Color.parseColor("#0277BD")
        }
    }
}

// Hausa / English text. Default is English; the language is changed in Settings.
object L {
    var ha = false
    fun s(hausa: String, english: String): String = if (ha) hausa else english
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.round(color: Int, r: Int = 16): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

fun Context.txt(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.pill(label: String, bg: Int, fg: Int, onClick: () -> Unit): Button =
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

fun Context.col(bg: Int, pad: Int = 20): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

fun Context.lp(top: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }
