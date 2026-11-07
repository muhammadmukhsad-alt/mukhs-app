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
    val BLUE_ON_DARK = Color.parseColor("#34B7F1")
    val BLUE_ON_LIME = Color.parseColor("#0277BD")
    val GREY_ON_DARK = Color.parseColor("#9CC3B8")
    val GREY_ON_LIME = Color.parseColor("#4F6F66")
}

// Hausa / English text. Default is Hausa; the user picks the language on the setup screen.
object L {
    var ha = true
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
