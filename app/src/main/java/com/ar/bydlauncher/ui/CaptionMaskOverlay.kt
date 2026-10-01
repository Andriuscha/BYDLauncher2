package com.ar.bydlauncher.ui

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class CaptionMaskOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var maskView: FrameLayout? = null
    private var textView: TextView? = null
    private var isShown = false

    companion object {
        private const val TAG = "CaptionMaskOverlay"
    }

    fun show(top: Int, captionHeightPx: Int = 60, text: String = "Поездка не начата") {
        Log.i(TAG, "show() called: top=$top h=$captionHeightPx text='$text' isShown=$isShown")
        Log.i(TAG, "canDrawOverlays=${Settings.canDrawOverlays(context)}")

        if (isShown && maskView != null && textView != null) {
            textView?.text = text
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
        Log.i(TAG, "screenWidth=$screenWidth")

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
            y = top   // top=0 теперь будет сразу под статус-баром
        }

        val tv = TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(24), 0, dp(24), 0)
            maxLines = 1
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(0xFF0D1B2A.toInt())
            addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        try {
            wm.addView(root, params)
            maskView = root
            textView = tv
            isShown = true
            Log.i(TAG, "addView SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "addView FAILED: ${e.javaClass.name}: ${e.message}", e)
            maskView = null
            textView = null
            isShown = false
        }
    }

    fun updateText(newText: String) {
        Log.i(TAG, "updateText: '$newText' (textView=${textView != null})")
        textView?.text = newText
    }

    fun hide() {
        Log.i(TAG, "hide() called, maskView=${maskView != null}")
        maskView?.let { try { wm.removeView(it) } catch (e: Exception) {
            Log.e(TAG, "removeView FAILED", e)
        } }
        maskView = null
        textView = null
        isShown = false
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()
}