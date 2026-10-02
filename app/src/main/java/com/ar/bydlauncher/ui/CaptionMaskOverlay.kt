package com.ar.bydlauncher.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class CaptionMaskOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var maskView: FrameLayout? = null
    private var isShown = false

    private var tvDistance: TextView? = null
    private var tvEnergy: TextView? = null
    private var tvConsumption: TextView? = null
    private var tvDuration: TextView? = null
    private var tvTitle: TextView? = null

    companion object {
        private const val TAG = "CaptionMaskOverlay"

        private const val BG_COLOR = 0xE60A0E18.toInt()
        private const val BORDER_COLOR = 0x33FFFFFF
        private const val TEXT_SECONDARY = 0xF0FFFFFF.toInt()
        private const val TEXT_TERTIARY = 0xB0FFFFFF.toInt()

        private const val ACCENT_DISTANCE    = 0xFF4FD1C5.toInt()
        private const val ACCENT_ENERGY      = 0xFF5B9CFF.toInt()
        private const val ACCENT_CONSUMPTION = 0xFFFFB84D.toInt()
        private const val ACCENT_DURATION    = 0xFFA88BFF.toInt()
    }

    fun show(top: Int, captionHeightPx: Int = 56, text: String = "Поездка не начата") {
        Log.i(TAG, "show: top=$top h=$captionHeightPx text='$text' isShown=$isShown")

        if (isShown && maskView != null) {
            updateStatus(text)
            (maskView?.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                if (lp.y != top || lp.height != captionHeightPx) {
                    lp.y = top
                    lp.height = captionHeightPx
                    try { wm.updateViewLayout(maskView, lp) } catch (e: Exception) {
                        Log.e(TAG, "updateViewLayout FAILED", e)
                    }
                }
            }
            return
        }

        val screenWidth = context.resources.displayMetrics.widthPixels

        val params = WindowManager.LayoutParams(
            screenWidth,
            captionHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = top
        }

        val root = buildRoot()
        try {
            wm.addView(root, params)
            maskView = root
            isShown = true
            updateStatus(text)
            Log.i(TAG, "addView SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "addView FAILED: ${e.javaClass.name}: ${e.message}", e)
            maskView = null
            isShown = false
        }
    }

    private fun buildRoot(): FrameLayout {
        val root = FrameLayout(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = makeGlassBackground()
        }

        // ── 1. Иконка машины ──
        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_myplaces)
            setColorFilter(ACCENT_DISTANCE)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(10)
            }
        }
        container.addView(icon)

        // ── 2. Заголовок "В ПОЕЗДКЕ" / "НЕ НАЧАТА" ──
        tvTitle = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT_SECONDARY)
            textSize = 11f
            letterSpacing = 0.12f
            isAllCaps = true
            text = "НЕ НАЧАТА"
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(14)
            }
        }
        container.addView(tvTitle)

        container.addView(makeDivider())

        // ── 3. Четыре колонки с данными, всё в одну строку ──

        val (colDist, vDist) = makeStatColumn("ПРОБЕГ", "—", ACCENT_DISTANCE)
        tvDistance = vDist
        container.addView(colDist)

        container.addView(makeDivider())

        val (colEnergy, vEnergy) = makeStatColumn("ЭНЕРГИЯ", "—", ACCENT_ENERGY)
        tvEnergy = vEnergy
        container.addView(colEnergy)

        container.addView(makeDivider())

        val (colCons, vCons) = makeStatColumn("РАСХОД", "—", ACCENT_CONSUMPTION)
        tvConsumption = vCons
        container.addView(colCons)

        container.addView(makeDivider())

        val (colDur, vDur) = makeStatColumn("ВРЕМЯ", "—", ACCENT_DURATION)
        tvDuration = vDur
        container.addView(colDur)

        val wrapperParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }

        root.addView(container, wrapperParams)
        return root
    }

    /**
     * Колонка со всем в ОДНУ строку: подпись слева, значение справа.
     * layout_weight = 1 — колонки равномерно распределяются по ширине.
     */
    private fun makeStatColumn(
        label: String,
        value: String,
        accent: Int
    ): Pair<LinearLayout, TextView> {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL           // ← ГОРИЗОНТАЛЬНО!
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setPadding(dp(12), 0, dp(12), 0)
        }

        // подпись (мелкая, слева)
        val tvLabel = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT_TERTIARY)
            textSize = 10f
            letterSpacing = 0.10f
            isAllCaps = true
            text = label
            includeFontPadding = false
        }

        // значение (крупное, справа)
        val tvValue = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(accent)
            textSize = 17f
            letterSpacing = -0.02f
            text = value
            includeFontPadding = false
            setPadding(dp(8), 0, 0, 0)
        }

        col.addView(tvLabel)
        col.addView(tvValue)
        return col to tvValue
    }

    private fun makeDivider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(24)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        setBackgroundColor(0x33FFFFFF)
    }

    private fun makeGlassBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(BG_COLOR)
            setStroke(dp(1), BORDER_COLOR)
        }

    // ==================================================
    // UPDATE
    // ==================================================

    fun updateTrip(
        active: Boolean,
        distanceKm: Double,
        energyKwh: Double,
        consumption: Double,
        durationMin: Int
    ) {
        tvDistance?.text    = "%.1f км".format(distanceKm)
        tvEnergy?.text      = "%.2f кВт·ч".format(energyKwh)
        tvConsumption?.text = "%.1f".format(consumption)
        tvDuration?.text    = "%d мин".format(durationMin)

        tvTitle?.text = if (active) "В ПОЕЗДКЕ" else "НЕ НАЧАТА"
    }

    fun updateText(newText: String) {
        Log.i(TAG, "updateText (legacy): '$newText'")
    }

    fun updateStatus(text: String) {
        tvTitle?.text = text.uppercase()
    }

    fun hide() {
        maskView?.let {
            try { wm.removeView(it) } catch (e: Exception) {
                Log.e(TAG, "removeView FAILED", e)
            }
        }
        maskView = null
        tvDistance = null
        tvEnergy = null
        tvConsumption = null
        tvDuration = null
        tvTitle = null
        isShown = false
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()
}