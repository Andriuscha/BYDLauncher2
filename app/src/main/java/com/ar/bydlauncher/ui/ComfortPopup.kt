package com.ar.bydlauncher.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ar.bydlauncher.R
import com.ar.bydlauncher.adb.AdbClient
import com.ar.bydlauncher.byd.AutoserviceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Всплывающее окно «Климат» вместо отдельной ComfortActivity.
 *
 * Это оверлей-окно (TYPE_APPLICATION_OVERLAY, как CaptionMaskOverlay), а не часть окна
 * лаунчера: касания по нему не отдают фокус лаунчеру, поэтому панель Навигатора не мигает
 * и не пересоздаётся после каждого нажатия. Окно стоит над кнопкой «Климат» в левой части
 * экрана, тап вне окна, кнопка ✕ или повторный тап по «Климату» закрывают его.
 * Если разрешения на оверлеи нет, окно добавляется во view лаунчера (без закрытия по тапу вне).
 *
 * Раскладка слева направо:
 *   [ползунок температуры: HI / 32…17 / LO] [ползунок обдува: 7…1 / OFF]
 *   [большие кнопки подогрева сидений] [зеркала+стекло / рециркуляция / A/C]
 *
 * «OFF» на ползунке обдува — полное выключение климата (AC_POWER_STATE).
 * Выбор скорости при выключенном климате сначала включает его.
 */
class ComfortPopup(
    private val activity: Activity,
    private val adb: AdbClient,
    private val anchor: View,
    private val leftFraction: Float = 0.45f
) {

    var onShown: (() -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    companion object {
        private const val TAG = "ComfortPopup"

        private const val DEV_AC = AutoserviceClient.DEV_AC               // 1000
        private const val DEV_SETTING = AutoserviceClient.DEV_SETTING     // 1023

        // ── FID-ы климата (как в ComfortActivity, проверены на DiLink) ──
        private const val FID_DEFROST_READ = 1128267825
        private const val FID_DEFROST_SET = 501219357
        private const val FID_RECIRC_READ = 1077936148
        private const val FID_RECIRC_SET = 501219355
        private const val FID_AC_POWER_READ = 1077936144
        private const val FID_AC_POWER_SET = 501219364
        private const val FID_COMPRESSOR_READ = 1077936137
        private const val FID_COMPRESSOR_SET = 501219344
        private const val FID_FAN_READ = 1077936156
        private const val FID_FAN_SET = 501219340
        private const val FID_TEMP_READ = 1077936168
        private const val FID_TEMP_SET = 501219368

        // ── Сиденья (DEV_SETTING): 1 = OFF, 2 = слабо, 3 = сильно ──
        private const val FID_SEAT_DRV_READ = 1335885835
        private const val FID_SEAT_DRV_SET = 1125122068
        private const val FID_SEAT_PSG_READ = 1335885843
        private const val FID_SEAT_PSG_SET = 1125122076
        private val SEAT_VALUES = intArrayOf(1, 2, 3)   // индекс 0 = OFF, 1 = LOW, 2 = HIGH

        // ── Температура ──
        private const val TEMP_MIN = 17
        private const val TEMP_MAX = 32
        private const val TEMP_STEPS = TEMP_MAX - TEMP_MIN + 3   // LO + 17..32 + HI = 18

        // ВНИМАНИЕ, НЕ ПРОВЕРЕНО НА МАШИНЕ: коды, которыми BYD кодирует «LO» и «HI».
        // Чтение работает при любых значениях (всё ниже 17 — LO, выше 32 — HI),
        // а запись использует эти два числа. Как проверить: включить в заводском климате
        // HI (или LO), открыть это окно и найти в logcat строку "raw temp=" (тег ComfortPopup).
        private const val TEMP_LO_RAW = 16
        private const val TEMP_HI_RAW = 33

        private const val FAN_MAX = 7

        private const val FRESH_MS = 2500L          // после действия пользователя чтение не перетирает контрол
        private const val BYD_CLIMATE_PKG = "com.byd.airconditioning"
        private const val BYD_CLIMATE_ACT =
            "com.byd.airconditioning.mainactivity.FullScreenMainActivity"
    }

    private val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ac = AutoserviceClient(adb)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cmdMutex = Mutex()
    private var refreshJob: Job? = null
    private var lastDismissAt = 0L

    // ── состояние (переживает закрытие окна: следующее открытие сразу показывает последнее) ──
    private var loadedOnce = false
    private var acPowerOn = true
    private var fanLevel = 3
    private var tempIdx = 6                 // 22°C
    private var compressorOn = true
    private var recircOn = false
    private var defrostOn = false
    private var seatDrv = 0
    private var seatPsg = 0
    private val lastEdit = HashMap<String, Long>()

    // ── view ──
    private var root: LinearLayout? = null
    private var overlayAttached = false
    private var body: View? = null
    private var tempSlider: VerticalStepSlider? = null
    private var fanSlider: VerticalStepSlider? = null
    private var tempReadout: TextView? = null
    private var fanReadout: TextView? = null
    private var seatDrvBtn: SeatHeatButton? = null
    private var seatPsgBtn: SeatHeatButton? = null
    private var tileDefrost: ToggleTile? = null
    private var tileRecirc: ToggleTile? = null
    private var tileAc: ToggleTile? = null

    // ═══════════════════════════════════════════════
    //  ПОКАЗ / СКРЫТИЕ
    // ═══════════════════════════════════════════════

    fun toggle() {
        if (isShowing) dismiss()
        // Тап по «Климату» закрыл окно через ACTION_OUTSIDE: не открываем его заново этим же тапом
        else if (System.currentTimeMillis() - lastDismissAt > 350) show()
    }

    fun show() {
        if (root != null) return

        val screen = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(screen)
        val content = activity.findViewById<View>(android.R.id.content)
        val cl = IntArray(2); content.getLocationOnScreen(cl)
        val al = IntArray(2); anchor.getLocationOnScreen(al)

        val left = dpi(8f)
        val top = cl[1] + dpi(10f)
        val width = (screen.x * leftFraction).toInt() - dpi(16f)
        val bottom = al[1] - dpi(4f) - dpi(8f)          // над рядом дока
        val height = (bottom - top).coerceAtLeast(dpi(320f))
        val rect = Rect(left, top, left + width, top + height)

        val panel = buildPanel()
        if (!attach(panel, rect)) return
        root = panel

        panel.alpha = 0f
        panel.translationX = -dpi(36f).toFloat()
        panel.animate().alpha(1f).translationX(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).start()

        applyState()
        body?.alpha = if (loadedOnce) 1f else 0.45f
        onShown?.invoke()

        scope.launch {
            delay(120)
            readAllState()
        }
    }

    fun dismiss(animated: Boolean = true) {
        val panel = root ?: return
        root = null
        lastDismissAt = System.currentTimeMillis()
        refreshJob?.cancel()
        clearViewRefs()
        onDismissed?.invoke()

        if (animated) {
            panel.animate().alpha(0f).translationX(-dpi(24f).toFloat()).setDuration(140)
                .withEndAction { detach(panel) }.start()
        } else {
            panel.animate().cancel()
            detach(panel)
        }
    }

    fun release() {
        dismiss(false)
        scope.cancel()
        ioScope.cancel()
    }

    private fun attach(panel: View, rect: Rect): Boolean {
        if (Settings.canDrawOverlays(activity)) {
            try {
                val lp = WindowManager.LayoutParams(
                    rect.width(), rect.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = rect.left
                    y = rect.top
                }
                panel.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss()
                    false
                }
                wm.addView(panel, lp)
                overlayAttached = true
                return true
            } catch (e: Exception) {
                Log.e(TAG, "overlay addView failed, fallback to in-activity: ${e.message}", e)
            }
        }
        // Запасной вариант: внутри окна лаунчера
        return try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val cl = IntArray(2); content.getLocationOnScreen(cl)
            content.addView(panel, FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = rect.left - cl[0]
                topMargin = rect.top - cl[1]
            })
            overlayAttached = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "in-activity attach failed: ${e.message}", e)
            false
        }
    }

    private fun detach(panel: View) {
        try {
            if (overlayAttached) wm.removeViewImmediate(panel)
            else (panel.parent as? ViewGroup)?.removeView(panel)
        } catch (e: Exception) {
            Log.w(TAG, "detach failed: ${e.message}")
        }
    }

    private fun clearViewRefs() {
        body = null; tempSlider = null; fanSlider = null; tempReadout = null; fanReadout = null
        seatDrvBtn = null; seatPsgBtn = null; tileDefrost = null; tileRecirc = null; tileAc = null
    }

    // ═══════════════════════════════════════════════
    //  ПОСТРОЕНИЕ UI
    // ═══════════════════════════════════════════════

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(14f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Pal.PANEL_TOP, Pal.PANEL_BOTTOM)
            ).apply {
                cornerRadius = dp(24f)
                setStroke(dpi(1f), Pal.PANEL_STROKE)
            }
        }
        panel.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, dpi(44f)))

        val bodyRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        body = bodyRow

        // ── ползунок температуры ──
        val tSlider = VerticalStepSlider(activity).apply {
            thumbLabels = (0 until TEMP_STEPS).map { tempText(it) }
            gutterLabels = mapOf(0 to "LO", 1 to "17", 4 to "20", 9 to "25", 14 to "30", TEMP_STEPS - 1 to "HI")
            gradientColors = intArrayOf(0xFFFF6A3D.toInt(), 0xFFFFB04D.toInt(), 0xFFBFE3FF.toInt(), 0xFF3FA9FF.toInt())
            gradientStops = floatArrayOf(0f, 0.35f, 0.65f, 1f)
            onPreview = { idx -> showTempReadout(idx) }
            onCommit = { idx -> setTemp(idx) }
        }
        tempSlider = tSlider
        val tReadout = readoutText(26f)
        tempReadout = tReadout

        // ── ползунок обдува ──
        val fSlider = VerticalStepSlider(activity).apply {
            thumbLabels = listOf("OFF") + (1..FAN_MAX).map { it.toString() }
            gutterLabels = mapOf(0 to "OFF") + (1..FAN_MAX).associateWith { it.toString() }
            gradientColors = intArrayOf(0xFF8AD0FF.toInt(), 0xFF2E93FA.toInt())
            emptyIndex = 0
            onPreview = { idx -> showFanReadout(idx) }
            onCommit = { idx -> setFan(idx) }
        }
        fanSlider = fSlider
        val fReadout = readoutText(26f)
        fanReadout = fReadout

        bodyRow.addView(sliderColumn("ТЕМП.", tReadout, tSlider), LinearLayout.LayoutParams(0, MATCH, 1.1f))
        bodyRow.addView(sliderColumn("ОБДУВ", fReadout, fSlider), LinearLayout.LayoutParams(0, MATCH, 1.1f).apply {
            marginStart = dpi(4f)
        })
        bodyRow.addView(divider(), LinearLayout.LayoutParams(dpi(1f), MATCH).apply {
            marginStart = dpi(8f); marginEnd = dpi(8f); topMargin = dpi(14f)
        })

        // ── сиденья ──
        val drv = SeatHeatButton(activity, "ВОДИТЕЛЬ").apply { setOnClickListener { cycleSeat(true) } }
        val psg = SeatHeatButton(activity, "ПАССАЖИР").apply { setOnClickListener { cycleSeat(false) } }
        seatDrvBtn = drv; seatPsgBtn = psg
        val seatsCol = buttonColumn("ПОДОГРЕВ СИДЕНИЙ")
        seatsCol.addView(drv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        seatsCol.addView(psg, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(8f) })
        bodyRow.addView(seatsCol, LinearLayout.LayoutParams(0, MATCH, 1.4f))

        // ── функции ──
        val tDefrost = ToggleTile(activity, TileIcon.DEFROST, "ЗЕРКАЛА\n+ СТЕКЛО").apply { setOnClickListener { toggleDefrost() } }
        val tRecirc = ToggleTile(activity, TileIcon.RECIRC, "РЕЦИРКУ-\nЛЯЦИЯ").apply { setOnClickListener { toggleRecirc() } }
        val tAc = ToggleTile(activity, TileIcon.AC, "КОМПРЕССОР\nХОЛОД").apply { setOnClickListener { toggleCompressor() } }
        tileDefrost = tDefrost; tileRecirc = tRecirc; tileAc = tAc
        val tilesCol = buttonColumn("ФУНКЦИИ")
        tilesCol.addView(tDefrost, LinearLayout.LayoutParams(MATCH, 0, 1f))
        tilesCol.addView(tRecirc, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(8f) })
        tilesCol.addView(tAc, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(8f) })
        bodyRow.addView(tilesCol, LinearLayout.LayoutParams(0, MATCH, 1.4f).apply { marginStart = dpi(8f) })

        panel.addView(bodyRow, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(4f) })
        return panel
    }

    private fun buildHeader(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label("КЛИМАТ", 15f, Pal.TEXT).apply { letterSpacing = 0.14f },
            LinearLayout.LayoutParams(0, WRAP, 1f))

        val factory = label("ЗАВОДСКОЙ", 10.5f, Pal.TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(14f), 0, dpi(14f), 0)
            isClickable = true
            background = pillBackground(Pal.TILE_FILL, Pal.TILE_STROKE, 18f)
            setOnClickListener { openFactory() }
        }
        row.addView(factory, LinearLayout.LayoutParams(WRAP, dpi(36f)).apply { marginEnd = dpi(10f) })

        val close = label("✕", 15f, Pal.TEXT).apply {
            gravity = Gravity.CENTER
            isClickable = true
            background = pillBackground(Pal.TILE_FILL, Pal.TILE_STROKE, 18f)
            setOnClickListener { dismiss() }
        }
        row.addView(close, LinearLayout.LayoutParams(dpi(36f), dpi(36f)))
        return row
    }

    private fun sliderColumn(caption: String, readout: TextView, slider: View): LinearLayout {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(label(caption, 9f, Pal.TEXT_DIM).apply { letterSpacing = 0.1f },
            LinearLayout.LayoutParams(WRAP, dpi(14f)))
        col.addView(readout, LinearLayout.LayoutParams(MATCH, dpi(34f)))
        col.addView(slider, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(4f) })
        return col
    }

    /** Колонка кнопок: подпись стоит в той же строке, что и крупные значения над ползунками. */
    private fun buttonColumn(caption: String): LinearLayout {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(View(activity), LinearLayout.LayoutParams(MATCH, dpi(14f)))
        col.addView(label(caption, 9f, Pal.TEXT_DIM).apply {
            letterSpacing = 0.1f
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dpi(2f), 0, 0, dpi(4f))
        }, LinearLayout.LayoutParams(MATCH, dpi(34f)))
        return col
    }

    private fun divider(): View = View(activity).apply { setBackgroundColor(Pal.DIVIDER) }

    private fun readoutText(sp: Float): TextView = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(Pal.TEXT)
        text = "—"
    }

    private fun label(text: String, sp: Float, color: Int): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        maxLines = 2
    }

    private fun pillBackground(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp)
        setStroke(dpi(1f), stroke)
    }

    // ═══════════════════════════════════════════════
    //  СОСТОЯНИЕ → UI
    // ═══════════════════════════════════════════════

    private fun applyState() {
        tempSlider?.setIndex(tempIdx)
        fanSlider?.setIndex(if (acPowerOn) fanLevel else 0)
        updateReadouts()
        seatDrvBtn?.level = seatDrv
        seatPsgBtn?.level = seatPsg
        tileDefrost?.active = defrostOn
        tileRecirc?.active = recircOn
        tileAc?.active = compressorOn && acPowerOn
    }

    private fun updateReadouts() {
        showTempReadout(tempIdx)
        showFanReadout(if (acPowerOn) fanLevel else 0)
    }

    private fun showTempReadout(idx: Int) {
        val tv = tempReadout ?: return
        tv.text = tempText(idx)
        tv.setTextColor(tempSlider?.colorAtIndex(idx) ?: Pal.TEXT)
    }

    private fun showFanReadout(idx: Int) {
        val tv = fanReadout ?: return
        tv.text = if (idx == 0) "OFF" else idx.toString()
        tv.setTextColor(if (idx == 0) Pal.TEXT_DIM else fanSlider?.colorAtIndex(idx) ?: Pal.TEXT)
    }

    private fun tempText(idx: Int): String = when (idx) {
        0 -> "LO"
        TEMP_STEPS - 1 -> "HI"
        else -> "${idx + TEMP_MIN - 1}°"
    }

    private fun tempRaw(idx: Int): Int = when (idx) {
        0 -> TEMP_LO_RAW
        TEMP_STEPS - 1 -> TEMP_HI_RAW
        else -> idx + TEMP_MIN - 1
    }

    private fun tempIdxFromRaw(raw: Int): Int = when {
        raw < TEMP_MIN -> 0
        raw > TEMP_MAX -> TEMP_STEPS - 1
        else -> raw - TEMP_MIN + 1
    }

    // ═══════════════════════════════════════════════
    //  ДЕЙСТВИЯ ПОЛЬЗОВАТЕЛЯ
    // ═══════════════════════════════════════════════

    private fun mark(key: String) { lastEdit[key] = System.currentTimeMillis() }
    private fun fresh(key: String): Boolean =
        System.currentTimeMillis() - (lastEdit[key] ?: 0L) < FRESH_MS

    private fun setTemp(idx: Int) {
        mark("temp")
        tempIdx = idx
        showTempReadout(idx)
        sendSet(DEV_AC, FID_TEMP_SET, tempRaw(idx))
    }

    private fun setFan(idx: Int) {
        mark("fan")
        val wasOn = acPowerOn
        acPowerOn = idx > 0
        if (idx > 0) fanLevel = idx
        showFanReadout(idx)
        tileAc?.active = compressorOn && acPowerOn
        ioScope.launch {
            cmdMutex.withLock {
                if (idx == 0) {
                    log("power=0", ac.setInt(DEV_AC, FID_AC_POWER_SET, 0))
                } else {
                    if (!wasOn) {
                        log("power=1", ac.setInt(DEV_AC, FID_AC_POWER_SET, 1))
                        delay(150)
                    }
                    log("fan=$idx", ac.setInt(DEV_AC, FID_FAN_SET, idx))
                }
            }
            withContext(Dispatchers.Main) { scheduleRefresh() }
        }
    }

    private fun cycleSeat(driver: Boolean) {
        val key = if (driver) "seatDrv" else "seatPsg"
        mark(key)
        val next = ((if (driver) seatDrv else seatPsg) + 1) % 3
        if (driver) { seatDrv = next; seatDrvBtn?.level = next } else { seatPsg = next; seatPsgBtn?.level = next }
        sendSet(DEV_SETTING, if (driver) FID_SEAT_DRV_SET else FID_SEAT_PSG_SET, SEAT_VALUES[next])
    }

    private fun toggleDefrost() {
        mark("defrost"); defrostOn = !defrostOn; tileDefrost?.active = defrostOn
        sendSet(DEV_AC, FID_DEFROST_SET, if (defrostOn) 1 else 0)
    }

    private fun toggleRecirc() {
        mark("recirc"); recircOn = !recircOn; tileRecirc?.active = recircOn
        sendSet(DEV_AC, FID_RECIRC_SET, if (recircOn) 1 else 0)
    }

    private fun toggleCompressor() {
        mark("comp"); compressorOn = !compressorOn; tileAc?.active = compressorOn && acPowerOn
        sendSet(DEV_AC, FID_COMPRESSOR_SET, if (compressorOn) 1 else 0)
    }

    private fun openFactory() {
        ioScope.launch {
            val cmd = "am start -n $BYD_CLIMATE_PKG/$BYD_CLIMATE_ACT"
            Log.i(TAG, "openFactoryClimate: $cmd")
            Log.i(TAG, "result: ${adb.shell(cmd)}")
        }
        dismiss()
    }

    // ═══════════════════════════════════════════════
    //  ОБМЕН С МАШИНОЙ
    // ═══════════════════════════════════════════════

    private fun sendSet(dev: Int, fid: Int, value: Int) {
        ioScope.launch {
            cmdMutex.withLock { log("dev=$dev fid=$fid val=$value", ac.setInt(dev, fid, value)) }
            withContext(Dispatchers.Main) { scheduleRefresh() }
        }
    }

    private fun log(what: String, ok: Boolean) = Log.i(TAG, "set $what ok=$ok")

    private fun scheduleRefresh(delayMs: Long = 700) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(delayMs)
            readAllState()
        }
    }

    private suspend fun readAllState() {
        val r = withContext(Dispatchers.IO) {
            intArrayOf(
                ac.getInt(DEV_AC, FID_DEFROST_READ) ?: Int.MIN_VALUE,
                ac.getInt(DEV_AC, FID_RECIRC_READ) ?: Int.MIN_VALUE,
                ac.getInt(DEV_AC, FID_COMPRESSOR_READ) ?: Int.MIN_VALUE,
                ac.getInt(DEV_AC, FID_AC_POWER_READ) ?: Int.MIN_VALUE,
                ac.getInt(DEV_AC, FID_TEMP_READ) ?: Int.MIN_VALUE,
                ac.getInt(DEV_AC, FID_FAN_READ) ?: Int.MIN_VALUE,
                ac.getInt(DEV_SETTING, FID_SEAT_DRV_READ) ?: Int.MIN_VALUE,
                ac.getInt(DEV_SETTING, FID_SEAT_PSG_READ) ?: Int.MIN_VALUE
            )
        }
        val none = Int.MIN_VALUE
        val defrost = r[0]; val recirc = r[1]; val comp = r[2]; val power = r[3]
        val temp = r[4]; val fan = r[5]; val drv = r[6]; val psg = r[7]
        Log.i(TAG, "raw temp=${if (temp == none) null else temp} fan=${if (fan == none) null else fan} " +
                "power=${if (power == none) null else power} comp=${if (comp == none) null else comp} " +
                "recirc=${if (recirc == none) null else recirc} defrost=${if (defrost == none) null else defrost} " +
                "seatDrv=${if (drv == none) null else drv} seatPsg=${if (psg == none) null else psg}")

        if (defrost != none && !fresh("defrost")) defrostOn = defrost == 1
        if (recirc != none && !fresh("recirc")) recircOn = recirc == 1
        if (comp != none && !fresh("comp")) compressorOn = comp != 0
        if (power != none && !fresh("fan")) acPowerOn = power == 1
        if (temp != none && !fresh("temp")) tempIdx = tempIdxFromRaw(temp)
        if (fan != none && !fresh("fan") && fan > 0) fanLevel = fan.coerceIn(1, FAN_MAX)
        if (drv != none && !fresh("seatDrv")) seatDrv = seatLevelFromRaw(drv)
        if (psg != none && !fresh("seatPsg")) seatPsg = seatLevelFromRaw(psg)

        loadedOnce = true
        if (root != null) {
            applyState()
            body?.animate()?.alpha(1f)?.setDuration(160)?.start()
        }
    }

    private fun seatLevelFromRaw(raw: Int): Int = when (raw) {
        SEAT_VALUES[2] -> 2
        SEAT_VALUES[1] -> 1
        else -> 0
    }

    // ═══════════════════════════════════════════════
    //  УТИЛИТЫ
    // ═══════════════════════════════════════════════

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Float): Float = v * activity.resources.displayMetrics.density
    private fun dpi(v: Float): Int = dp(v).toInt()
}

// ═══════════════════════════════════════════════════════════════════
//  ПАЛИТРА
// ═══════════════════════════════════════════════════════════════════

internal object Pal {
    const val PANEL_TOP = 0xF21A2434.toInt()
    const val PANEL_BOTTOM = 0xF20B111C.toInt()
    const val PANEL_STROKE = 0x33FFFFFF
    const val DIVIDER = 0x1AFFFFFF

    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_DIM = 0xFFB0B4BD.toInt()
    const val ACCENT = 0xFF2E93FA.toInt()

    const val HEAT_LOW = 0xFFFFB84D.toInt()
    const val HEAT_HIGH = 0xFFFF5A36.toInt()

    const val TILE_FILL = 0x14FFFFFF
    const val TILE_FILL_PRESSED = 0x2BFFFFFF
    const val TILE_STROKE = 0x24FFFFFF
    const val ICON_OFF = 0xFFE9EDF5.toInt()

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

internal fun View.spPx(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

// ═══════════════════════════════════════════════════════════════════
//  ВЕРТИКАЛЬНЫЙ ПОЛЗУНОК С ФИКСИРОВАННЫМИ ШАГАМИ
// ═══════════════════════════════════════════════════════════════════

/**
 * Индекс 0 — нижний шаг (LO / OFF), последний — верхний (HI / 7).
 * Палец двигает «пилюлю» плавно, на отпускании она встаёт на ближайший шаг и вызывается onCommit.
 */
internal class VerticalStepSlider(ctx: Context) : View(ctx) {

    var thumbLabels: List<String> = listOf("0", "1")
        set(v) { field = v; invalidate() }
    var gutterLabels: Map<Int, String> = emptyMap()
        set(v) { field = v; invalidate() }
    var gradientColors: IntArray = intArrayOf(0xFFFFFFFF.toInt(), 0xFF888888.toInt())
        set(v) { field = v; shaderKey = -1f; invalidate() }
    var gradientStops: FloatArray? = null
        set(v) { field = v; shaderKey = -1f; invalidate() }
    /** При этом индексе заливка не рисуется (например, OFF). */
    var emptyIndex: Int = -1

    var onPreview: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val stepCount get() = thumbLabels.size
    private var shown = 0f
    private var committed = 0
    private var dragging = false
    private var lastPreview = -1
    private var anim: ValueAnimator? = null
    private var shaderKey = -1f
    private var shader: Shader? = null

    private val trackRect = RectF()
    private val thumbRect = RectF()
    private val clip = Path()
    private val argb = ArgbEvaluator()

    private val trackBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x26FFFFFF
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d; color = 0x55FFFFFF }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val thumbText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val gutterW get() = min(22f * d, width * 0.26f)
    private val thumbW get() = min(52f * d, width - gutterW - 2f * d)
    private val trackW get() = thumbW * 0.76f
    private val cx get() = gutterW + (width - gutterW) / 2f
    private val thumbH = 34f * d
    private val posTop get() = thumbH / 2f + 3f * d
    private val posBottom get() = height - thumbH / 2f - 3f * d

    private fun yFor(pos: Float): Float =
        posBottom - (pos / (stepCount - 1).coerceAtLeast(1)) * (posBottom - posTop)

    fun setIndex(i: Int, animate: Boolean = true) {
        val idx = i.coerceIn(0, stepCount - 1)
        committed = idx
        if (dragging) return
        if (animate && width > 0) animateTo(idx.toFloat()) else { shown = idx.toFloat(); invalidate() }
    }

    fun colorAtIndex(i: Int): Int = colorAtFraction(1f - i.toFloat() / (stepCount - 1).coerceAtLeast(1))

    private fun colorAtFraction(f: Float): Int {
        val colors = gradientColors
        val stops = gradientStops ?: FloatArray(colors.size) { it / (colors.size - 1f) }
        if (f <= stops.first()) return colors.first()
        if (f >= stops.last()) return colors.last()
        for (k in 0 until colors.size - 1) {
            if (f <= stops[k + 1]) {
                val t = (f - stops[k]) / (stops[k + 1] - stops[k])
                return argb.evaluate(t, colors[k], colors[k + 1]) as Int
            }
        }
        return colors.last()
    }

    private fun animateTo(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 170
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                anim?.cancel()
                lastPreview = -1
                moveTo(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> { if (dragging) moveTo(e.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return true
                dragging = false
                val target = shown.roundToInt().coerceIn(0, stepCount - 1)
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    animateTo(target.toFloat())
                    if (target != committed) { committed = target; onCommit?.invoke(target) }
                } else {
                    animateTo(committed.toFloat())
                    onPreview?.invoke(committed)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun moveTo(y: Float) {
        val span = (posBottom - posTop).coerceAtLeast(1f)
        val frac = ((posBottom - y) / span).coerceIn(0f, 1f)
        shown = frac * (stepCount - 1)
        val idx = shown.roundToInt()
        if (idx != lastPreview) {
            lastPreview = idx
            onPreview?.invoke(idx)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val n = stepCount
        if (n < 2 || width == 0 || height == 0) return

        val tl = cx - trackW / 2f
        val tr = cx + trackW / 2f
        val tTop = 2f * d
        val tBottom = height - 2f * d
        val rad = trackW / 2f
        trackRect.set(tl, tTop, tr, tBottom)
        c.drawRoundRect(trackRect, rad, rad, trackBg)
        c.drawRoundRect(trackRect, rad, rad, trackStroke)

        val idxNow = shown.roundToInt().coerceIn(0, n - 1)
        val ty = yFor(shown)
        val empty = idxNow == emptyIndex

        if (!empty) {
            if (shaderKey != tBottom) {
                shader = LinearGradient(
                    0f, tTop, 0f, tBottom, gradientColors,
                    gradientStops, Shader.TileMode.CLAMP
                )
                shaderKey = tBottom
            }
            fill.shader = shader
            fill.alpha = 0xE6
            c.save()
            clip.reset()
            clip.addRoundRect(trackRect, rad, rad, Path.Direction.CW)
            c.clipPath(clip)
            c.drawRect(tl, ty, tr, tBottom, fill)
            c.restore()
        }

        // риски шагов
        for (i in 0 until n) {
            val y = yFor(i.toFloat())
            if (Math.abs(y - ty) < thumbH * 0.62f) continue
            val half = if (gutterLabels.containsKey(i)) 6f * d else 4f * d
            c.drawLine(cx - half, y, cx + half, y, tick)
        }

        // подписи слева
        gutterPaint.textSize = spPx(9.5f)
        for ((i, text) in gutterLabels) {
            val y = yFor(i.toFloat())
            gutterPaint.color = if (i == idxNow) Pal.TEXT else Pal.TEXT_DIM
            c.drawText(text, gutterW - 3f * d, y + gutterPaint.textSize * 0.35f, gutterPaint)
        }

        // «пилюля»
        val half = thumbW / 2f
        thumbRect.set(cx - half, ty - thumbH / 2f, cx + half, ty + thumbH / 2f)
        if (!empty) {
            glow.color = Pal.withAlpha(colorAtFraction(((ty - posTop) / (posBottom - posTop)).coerceIn(0f, 1f)), 0x44)
            val g = 5f * d
            c.drawRoundRect(
                thumbRect.left - g, thumbRect.top - g, thumbRect.right + g, thumbRect.bottom + g,
                thumbH / 2f + g, thumbH / 2f + g, glow
            )
        }
        thumbPaint.color = if (empty) 0xCCDDE3EE.toInt() else 0xFFF4F7FB.toInt()
        c.drawRoundRect(thumbRect, thumbH / 2f, thumbH / 2f, thumbPaint)
        thumbText.textSize = spPx(14f)
        thumbText.color = if (empty) 0xFF55607A.toInt() else 0xFF0A0E18.toInt()
        val label = thumbLabels[idxNow]
        c.drawText(label, cx, ty + thumbText.textSize * 0.35f, thumbText)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

// ═══════════════════════════════════════════════════════════════════
//  БОЛЬШАЯ КНОПКА ПОДОГРЕВА СИДЕНЬЯ
// ═══════════════════════════════════════════════════════════════════

/** level: 0 — выключено, 1 — слабо (2 волны), 2 — сильно (3 волны). Тап переключает по кругу. */
internal class SeatHeatButton(ctx: Context, private val name: String) : View(ctx) {

    var level: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private val rect = RectF()
    private val body = RectF()
    private val path = Path()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val seat = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; letterSpacing = 0.08f
    }
    private val levelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; letterSpacing = 0.06f
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return super.performClick()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val active = level > 0
        val heat = if (level == 2) Pal.HEAT_HIGH else Pal.HEAT_LOW
        val r = 20f * d

        rect.set(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d)
        bg.color = when {
            active -> Pal.withAlpha(heat, 0x33)
            isPressed -> Pal.TILE_FILL_PRESSED
            else -> Pal.TILE_FILL
        }
        c.drawRoundRect(rect, r, r, bg)
        stroke.color = if (active) heat else Pal.TILE_STROKE
        stroke.strokeWidth = (if (active) 2f else 1f) * d
        c.drawRoundRect(rect, r, r, stroke)

        // сиденье + волны тепла
        val s = min(w * 0.52f, h * 0.46f)
        drawSeat(c, w / 2f, h * 0.35f, s, heat)

        nameP.textSize = spPx(10f)
        nameP.color = Pal.TEXT_DIM
        c.drawText(name, w / 2f, h * 0.70f, nameP)

        levelP.textSize = spPx(14f)
        levelP.color = if (active) heat else Pal.TEXT
        c.drawText(
            when (level) { 1 -> "СЛАБО"; 2 -> "СИЛЬНО"; else -> "ВЫКЛ" },
            w / 2f, h * 0.70f + spPx(20f), levelP
        )
    }

    private fun drawSeat(c: Canvas, cx: Float, cy: Float, s: Float, heat: Int) {
        val l = cx - s / 2f
        val t = cy - s / 2f
        seat.color = if (level > 0) 0xFFFFFFFF.toInt() else Pal.ICON_OFF
        // подушка
        body.set(l + 0.10f * s, t + 0.64f * s, l + 0.90f * s, t + 0.82f * s)
        c.drawRoundRect(body, 0.09f * s, 0.09f * s, seat)
        // спинка и подголовник (с небольшим наклоном)
        c.save()
        c.rotate(-9f, l + 0.22f * s, t + 0.74f * s)
        body.set(l + 0.10f * s, t + 0.20f * s, l + 0.34f * s, t + 0.74f * s)
        c.drawRoundRect(body, 0.09f * s, 0.09f * s, seat)
        body.set(l + 0.11f * s, t + 0.04f * s, l + 0.33f * s, t + 0.15f * s)
        c.drawRoundRect(body, 0.05f * s, 0.05f * s, seat)
        c.restore()
        // волны тепла: слабо — 2 из 3, сильно — 3 из 3
        val lit = when (level) { 1 -> 2; 2 -> 3; else -> 0 }
        wave.strokeWidth = 0.055f * s
        for (i in 0 until 3) {
            val x = l + (0.50f + i * 0.16f) * s
            wave.color = if (i < lit) heat else 0x33FFFFFF
            path.reset()
            path.moveTo(x, t + 0.56f * s)
            path.cubicTo(x - 0.07f * s, t + 0.46f * s, x + 0.07f * s, t + 0.38f * s, x, t + 0.28f * s)
            path.cubicTo(x - 0.07f * s, t + 0.20f * s, x + 0.05f * s, t + 0.14f * s, x, t + 0.08f * s)
            c.drawPath(path, wave)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ПЛИТКА-ПЕРЕКЛЮЧАТЕЛЬ
// ═══════════════════════════════════════════════════════════════════

internal enum class TileIcon { DEFROST, RECIRC, AC }

internal class ToggleTile(
    ctx: Context,
    private val icon: TileIcon,
    private val label: String
) : View(ctx) {

    var active: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private val rect = RectF()
    private val path = Path()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; letterSpacing = 0.04f
    }
    private val recircDrawable = ctx.getDrawable(R.drawable.ic_recirculation)?.mutate()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return super.performClick()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 18f * d
        rect.set(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d)

        bg.color = when {
            active -> Pal.withAlpha(Pal.ACCENT, 0x33)
            isPressed -> Pal.TILE_FILL_PRESSED
            else -> Pal.TILE_FILL
        }
        c.drawRoundRect(rect, r, r, bg)
        stroke.color = if (active) Pal.ACCENT else Pal.TILE_STROKE
        stroke.strokeWidth = (if (active) 2f else 1f) * d
        c.drawRoundRect(rect, r, r, stroke)

        val color = if (active) Pal.ACCENT else Pal.ICON_OFF
        val s = min(w * 0.30f, h * 0.38f)
        drawIcon(c, w / 2f, h * 0.34f, s, color)

        text.textSize = spPx(10f)
        text.color = if (active) Pal.ACCENT else Pal.TEXT
        val lines = label.split('\n')
        var y = h * 0.66f + text.textSize
        for (line in lines) {
            c.drawText(line, w / 2f, y, text)
            y += text.textSize * 1.25f
        }
    }

    private fun drawIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        when (icon) {
            TileIcon.DEFROST -> {
                ip.style = Paint.Style.STROKE
                ip.strokeWidth = 0.08f * s
                ip.strokeCap = Paint.Cap.ROUND
                ip.strokeJoin = Paint.Join.ROUND
                ip.color = color
                val l = cx - s / 2f
                val t = cy - s / 2f
                // стекло
                path.reset()
                path.moveTo(l + 0.04f * s, t + 0.84f * s)
                path.lineTo(l + 0.20f * s, t + 0.14f * s)
                path.lineTo(l + 0.80f * s, t + 0.14f * s)
                path.lineTo(l + 0.96f * s, t + 0.84f * s)
                path.close()
                c.drawPath(path, ip)
                // волны тепла
                for (i in 0 until 3) {
                    val x = l + (0.34f + i * 0.16f) * s
                    path.reset()
                    path.moveTo(x, t + 0.72f * s)
                    path.cubicTo(x - 0.08f * s, t + 0.62f * s, x + 0.08f * s, t + 0.52f * s, x, t + 0.42f * s)
                    path.cubicTo(x - 0.06f * s, t + 0.36f * s, x + 0.04f * s, t + 0.32f * s, x, t + 0.26f * s)
                    c.drawPath(path, ip)
                }
            }
            TileIcon.RECIRC -> {
                val dr = recircDrawable
                if (dr != null) {
                    dr.setTint(color)
                    val half = (s * 0.62f).toInt()
                    dr.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                    dr.draw(c)
                }
            }
            TileIcon.AC -> {
                ip.style = Paint.Style.FILL
                ip.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                ip.textAlign = Paint.Align.CENTER
                ip.color = color
                ip.textSize = s * 0.85f
                c.drawText("A/C", cx, cy + ip.textSize * 0.35f, ip)
            }
        }
    }
}
