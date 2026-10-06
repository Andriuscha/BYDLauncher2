package com.ar.bydlauncher

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ar.bydlauncher.adb.AdbClient
import com.ar.bydlauncher.byd.AutoserviceClient
import kotlinx.coroutines.*

/**
 * Экран «Комфорт»: управление климатом BYD.
 *
 * Работает через service call autoservice:
 *  - DEV_AC (1000) — почти все климатические FID-ы
 *  - DEV_SETTING (1023) — обогрев сидений
 *
 * «Только вентилятор» реализован через выключение компрессора:
 *   AC_COMPRESSOR_MODE_SET = 501219344, 0 = off, 1 = on.
 */
class ComfortActivity : Activity() {

    companion object {
        private const val TAG = "ComfortActivity"

        // ── Устройства ──
        private const val DEV_AC = AutoserviceClient.DEV_AC              // 1000
        private const val DEV_SETTING = AutoserviceClient.DEV_SETTING    // 1023

        // ── FID-ы климата (проверено на DiLink) ──
        private const val FID_DEFROST_REAR_READ   = 1128267825   // Ac.AC_DEFROST_REAR_STATE
        private const val FID_DEFROST_REAR_SET    = 501219357    // Ac.AC_DEFROST_REAR_STATE_SET

        private const val FID_RECIRC_READ         = 1077936148   // Ac.AC_CYCLE_MODE
        private const val FID_RECIRC_SET          = 501219355    // Ac.AC_CYCLE_MODE_SET

        private const val FID_AC_POWER_READ       = 1077936144   // Ac.AC_POWER_STATE
        private const val FID_AC_POWER_SET        = 501219364    // Ac.AC_POWER_STATE_SET

        // Компрессор: 0 = off (только вентилятор), 1 = on (холод/тепло)
        private const val FID_COMPRESSOR_READ     = 1077936137   // Ac.AC_COMPRESSOR_MODE
        private const val FID_COMPRESSOR_SET      = 501219344    // Ac.AC_COMPRESSOR_MODE_SET

        private const val FID_FAN_LEVEL_READ      = 1077936156   // Ac.AC_WIND_LEVEL
        private const val FID_FAN_LEVEL_SET       = 501219340    // Ac.AC_WIND_LEVEL_SET

        private const val FID_TEMP_MAIN_READ      = 1077936168   // Ac.AC_TEMP_MAIN
        private const val FID_TEMP_MAIN_SET       = 501219368    // Ac.AC_TEMP_MAIN_SET

        // ── Сиденья (DEV_SETTING) ──
        private const val FID_SEAT_DRV_READ       = 1335885835   // SET_DRIVER_SEAT_HEATING_STATE
        private const val FID_SEAT_DRV_SET        = 1125122068   // SET_DRIVER_SEAT_HEATING_STATE_SET

        private const val FID_SEAT_PSG_READ       = 1335885843   // SET_PASSENGER_SEAT_HEATING_STATE
        private const val FID_SEAT_PSG_SET        = 1125122076   // SET_PASSENGER_SEAT_HEATING_STATE_SET

        // ── Диапазоны ──
        private const val TEMP_MIN = 17
        private const val TEMP_MAX = 32
        private const val FAN_MIN = 1
        private const val FAN_MAX = 7
        // Сиденья: 1 = OFF, 2 = слабо, 3 = сильно (проверено на DiLink)
        private const val SEAT_OFF = 1
        private const val SEAT_LO = 2
        private const val SEAT_HI = 3

        // ── Заводское приложение климата ──
        private const val BYD_CLIMATE_PKG = "com.byd.airconditioning"
        private const val BYD_CLIMATE_ACT =
            "com.byd.airconditioning.mainactivity.FullScreenMainActivity"

        // ── Цвета ──
        private const val C_ACCENT      = 0xFF2E93FA.toInt()
        private const val C_TEXT        = 0xFFFFFFFF.toInt()
        private const val C_TEXT_DIM    = 0xFFB0B4BD.toInt()
    }

    private lateinit var adb: AdbClient
    private lateinit var ac: AutoserviceClient

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // UI-холдеры
    private lateinit var togglesRow: LinearLayout
    private lateinit var tempValue: TextView
    private lateinit var tempRow: LinearLayout
    private lateinit var fanValue: TextView
    private lateinit var fanRow: LinearLayout
    private lateinit var seatsBlock: LinearLayout

    // Текущее состояние
    private var defrostOn = false
    private var recircOn = false
    private var compressorOn = true
    private var acPowerOn = true     // полное вкл/выкл климата
    private var currentTemp = 22
    private var currentFan = 3
    private var seatDrv = 0
    private var seatPsg = 0

    // View-ы toggle-кнопок
    private lateinit var btnDefrost: LinearLayout
    private lateinit var btnRecirc: LinearLayout
    private lateinit var btnCompressor: LinearLayout
    private lateinit var btnAcOff: LinearLayout

    // View-ы кнопок значений
    private val tempButtons = HashMap<Int, LinearLayout>()
    private val fanButtons = HashMap<Int, LinearLayout>()
    private val seatDrvButtons = HashMap<Int, LinearLayout>()
    private val seatPsgButtons = HashMap<Int, LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comfort)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        adb = AdbClient.ensureConnected(this)
        ac = AutoserviceClient(adb)

        togglesRow  = findViewById(R.id.comfortToggles)
        tempValue   = findViewById(R.id.comfortTempValue)
        tempRow     = findViewById(R.id.comfortTempRow)
        fanValue    = findViewById(R.id.comfortFanValue)
        fanRow      = findViewById(R.id.comfortFanRow)
        seatsBlock  = findViewById(R.id.comfortSeatsBlock)

        findViewById<View>(R.id.comfortClose).setOnClickListener { finish() }

        findViewById<View>(R.id.comfortOpenFactory).setOnClickListener {
            ioScope.launch {
                val cmd = "am start -n $BYD_CLIMATE_PKG/$BYD_CLIMATE_ACT"
                Log.i(TAG, "openFactoryClimate: $cmd")
                val r = adb.shell(cmd)
                Log.i(TAG, "result: $r")
            }
        }

        buildTogglesRow()
        buildTempRow()
        buildFanRow()
        buildSeatsBlock()

        ioScope.launch {
            delay(300)
            readAllState()
        }
    }

    // ═══════════════════════════════════════════════
    //  ЧТЕНИЕ
    // ═══════════════════════════════════════════════

    private suspend fun readAllState() {
        val defrost = withContext(Dispatchers.IO) {
            ac.getInt(DEV_AC, FID_DEFROST_REAR_READ)
        }
        val recirc = withContext(Dispatchers.IO) {
            ac.getInt(DEV_AC, FID_RECIRC_READ)
        }
        val compressor = withContext(Dispatchers.IO) {
            ac.getInt(DEV_AC, FID_COMPRESSOR_READ)
        }
        val acPower = withContext(Dispatchers.IO) {
            ac.getInt(DEV_AC, FID_AC_POWER_READ)
        }
        val temp = withContext(Dispatchers.IO) {
            ac.getInt(DEV_AC, FID_TEMP_MAIN_READ)
        }
        val fan = withContext(Dispatchers.IO) {
            ac.getInt(DEV_AC, FID_FAN_LEVEL_READ)
        }
        val drv = withContext(Dispatchers.IO) {
            ac.getInt(DEV_SETTING, FID_SEAT_DRV_READ)
        }
        val psg = withContext(Dispatchers.IO) {
            ac.getInt(DEV_SETTING, FID_SEAT_PSG_READ)
        }

        Log.i(
            TAG,
            "readAllState: defrost=$defrost recirc=$recirc compressor=$compressor " +
                    "acPower=$acPower temp=$temp fan=$fan seatDrv=$drv seatPsg=$psg"
        )

        withContext(Dispatchers.Main) {
            defrostOn    = (defrost == 1)
            recircOn     = (recirc == 1)
            compressorOn = (compressor != 0)      // null или 1 → считаем ON, 0 → OFF
            acPowerOn    = (acPower == 1)
            currentTemp  = (temp ?: currentTemp).coerceIn(TEMP_MIN, TEMP_MAX)
            currentFan   = (fan ?: currentFan).coerceIn(FAN_MIN, FAN_MAX)
            seatDrv      = drv ?: 0
            seatPsg      = psg ?: 0

            redrawAll()
        }
    }

    private fun refreshStateDelayed(delayMs: Long = 500) {
        scope.launch {
            delay(delayMs)
            readAllState()
        }
    }

    // ═══════════════════════════════════════════════
    //  ОТПРАВКА КОМАНД
    // ═══════════════════════════════════════════════

    private fun sendSet(fidSet: Int, value: Int, onSuccess: () -> Unit = {}) {
        ioScope.launch {
            val ok = ac.setInt(DEV_AC, fidSet, value)
            Log.i(TAG, "sendSet dev=$DEV_AC fid=$fidSet val=$value ok=$ok")
            withContext(Dispatchers.Main) {
                onSuccess()
                refreshStateDelayed()
            }
        }
    }

    private fun sendSeatSet(fidSet: Int, value: Int, onSuccess: () -> Unit = {}) {
        ioScope.launch {
            val ok = ac.setInt(DEV_SETTING, fidSet, value)
            Log.i(TAG, "sendSeatSet dev=$DEV_SETTING fid=$fidSet val=$value ok=$ok")
            withContext(Dispatchers.Main) {
                onSuccess()
                refreshStateDelayed()
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  ПОСТРОЕНИЕ UI
    // ═══════════════════════════════════════════════

    private fun buildTogglesRow() {
        togglesRow.removeAllViews()

        btnDefrost = buildToggleButton("🪞 ЗЕРКАЛА\n+ ЗАДНЕЕ", defrostOn) {
            val next = !defrostOn
            defrostOn = next
            sendSet(FID_DEFROST_REAR_SET, if (next) 1 else 0)
        }
        btnRecirc = buildToggleButton("🔄 РЕЦИРКУЛ.", recircOn) {
            val next = !recircOn
            recircOn = next
            sendSet(FID_RECIRC_SET, if (next) 1 else 0)
        }
        btnCompressor = buildToggleButton("❄ КОМПРЕССОР", compressorOn) {
            val next = !compressorOn
            compressorOn = next
            // next == true  → компрессор ВКЛ (1)
            // next == false → компрессор ВЫКЛ (0) → «только вентилятор»
            sendSet(FID_COMPRESSOR_SET, if (next) 1 else 0)
        }
        btnAcOff = buildToggleButton("⏻ AC OFF", !acPowerOn) {
            val next = !acPowerOn              // next = хотим ли включить
            acPowerOn = next
            // acPowerOn = true  → климат включён  → пишем 1
            // acPowerOn = false → климат выключен → пишем 0
            sendSet(FID_AC_POWER_SET, if (next) 1 else 0)
        }

        togglesRow.addView(btnDefrost, weightParams())
        togglesRow.addView(spacerH(dp(8)))
        togglesRow.addView(btnRecirc, weightParams())
        togglesRow.addView(spacerH(dp(8)))
        togglesRow.addView(btnCompressor, weightParams())
        togglesRow.addView(spacerH(dp(8)))
        togglesRow.addView(btnAcOff, weightParams())
    }

    private fun buildToggleButton(label: String, active: Boolean, onClick: () -> Unit): LinearLayout {
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            isFocusable = true
            background = getDrawable(if (active) R.drawable.bg_soh_box else R.drawable.bg_chip)
            setOnClickListener { onClick() }
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(if (active) C_ACCENT else C_TEXT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
        }
        btn.addView(tv)
        btn.tag = tv
        return btn
    }

    private fun buildTempRow() {
        tempRow.removeAllViews()
        tempButtons.clear()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
        }

        for (t in TEMP_MIN..TEMP_MAX) {
            val btn = buildValueButton("$t°", t == currentTemp) {
                currentTemp = t
                sendSet(FID_TEMP_MAIN_SET, t)
            }
            tempButtons[t] = btn
            val target = if (t <= (TEMP_MIN + TEMP_MAX) / 2) top else bottom
            target.addView(btn, weightParams())
        }

        tempRow.addView(top)
        tempRow.addView(bottom)
        tempValue.text = "$currentTemp°C"
    }

    private fun buildFanRow() {
        fanRow.removeAllViews()
        fanButtons.clear()

        for (f in FAN_MIN..FAN_MAX) {
            val btn = buildValueButton("$f", f == currentFan) {
                currentFan = f
                sendSet(FID_FAN_LEVEL_SET, f)
            }
            fanButtons[f] = btn
            fanRow.addView(btn, weightParams())
            if (f < FAN_MAX) fanRow.addView(spacerH(dp(4)))
        }
        fanValue.text = if (currentFan > 0) "$currentFan" else "—"
    }

    private fun buildSeatsBlock() {
        seatsBlock.removeAllViews()
        seatsBlock.addView(buildSeatRow("ВОДИТЕЛЬ", seatDrv, FID_SEAT_DRV_SET, seatDrvButtons))
        seatsBlock.addView(spacerV(dp(8)))
        seatsBlock.addView(buildSeatRow("ПАССАЖИР", seatPsg, FID_SEAT_PSG_SET, seatPsgButtons))
    }

    private fun buildSeatRow(
        label: String,
        current: Int,
        fidSet: Int,
        targetMap: HashMap<Int, LinearLayout>
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvLabel = TextView(this).apply {
            text = label
            setTextColor(C_TEXT_DIM)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f
            )
        }
        row.addView(tvLabel)

        targetMap.clear()

        val options = listOf(
            SEAT_OFF to "OFF",
            SEAT_LO to "СЛАБО",
            SEAT_HI to "СИЛЬНО"
        )

        options.forEachIndexed { i, (value, text) ->
            val btn = buildValueButton(text, value == current) {
                sendSeatSet(fidSet, value)
            }
            targetMap[value] = btn
            row.addView(btn, weightParams())
            if (i < options.size - 1) row.addView(spacerH(dp(6)))
        }

        return row
    }

    private fun buildValueButton(label: String, active: Boolean, onClick: () -> Unit): LinearLayout {
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(10), dp(2), dp(10))
            isClickable = true
            isFocusable = true
            background = getDrawable(if (active) R.drawable.bg_soh_box else R.drawable.bg_chip)
            setOnClickListener { onClick() }
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(if (active) C_ACCENT else C_TEXT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        btn.addView(tv)
        btn.tag = tv
        return btn
    }

    // ═══════════════════════════════════════════════
    //  ПЕРЕРИСОВКА ПОСЛЕ ЧТЕНИЯ
    // ═══════════════════════════════════════════════

    private fun redrawAll() {
        updateToggleVisual(btnDefrost, defrostOn)
        updateToggleVisual(btnRecirc, recircOn)
        updateToggleVisual(btnCompressor, compressorOn)
        // Кнопка AC OFF подсвечена, КОГДА климат выключен (индикатор «отключено»)
        updateToggleVisual(btnAcOff, !acPowerOn)


        tempValue.text = "$currentTemp°C"
        tempButtons.forEach { (t, btn) ->
            updateValueVisual(btn, t == currentTemp)
        }

        fanValue.text = if (currentFan > 0) "$currentFan" else "—"
        fanButtons.forEach { (f, btn) ->
            updateValueVisual(btn, f == currentFan)
        }

        seatDrvButtons.forEach { (v, btn) ->
            updateValueVisual(btn, v == seatDrv)
        }
        seatPsgButtons.forEach { (v, btn) ->
            updateValueVisual(btn, v == seatPsg)
        }
    }

    private fun updateToggleVisual(btn: LinearLayout, active: Boolean) {
        val tv = btn.tag as? TextView ?: return
        tv.setTextColor(if (active) C_ACCENT else C_TEXT)
        btn.background = getDrawable(if (active) R.drawable.bg_soh_box else R.drawable.bg_chip)
    }

    private fun updateValueVisual(btn: LinearLayout, active: Boolean) {
        val tv = btn.tag as? TextView ?: return
        tv.setTextColor(if (active) C_ACCENT else C_TEXT)
        btn.background = getDrawable(if (active) R.drawable.bg_soh_box else R.drawable.bg_chip)
    }

    // ═══════════════════════════════════════════════
    //  УТИЛИТЫ
    // ═══════════════════════════════════════════════

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun spacerH(px: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(px, 1)
    }

    private fun spacerV(px: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, px)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        ioScope.cancel()
        scope.cancel()
        super.onDestroy()
    }
}