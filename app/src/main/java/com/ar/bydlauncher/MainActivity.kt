package com.ar.bydlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.ar.bydlauncher.adb.AdbClient
import com.ar.bydlauncher.byd.AutoserviceClient
import com.ar.bydlauncher.byd.BatterySnapshot
import com.ar.bydlauncher.byd.BmsReader
import com.ar.bydlauncher.byd.SohCalculator
import kotlinx.coroutines.*
import java.io.File

class MainActivity : Activity() {

    private lateinit var adb: AdbClient
    private lateinit var ac: AutoserviceClient
    private lateinit var reader: BmsReader
    private lateinit var contentText: TextView
    private lateinit var navContainer: FrameLayout
    private lateinit var navPlaceholder: TextView
    private lateinit var btnBack: FrameLayout
    private lateinit var btnHome: FrameLayout
    private lateinit var sohCalc: SohCalculator
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "MainActivity"
        private const val POLL_INTERVAL_MS = 1000L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_CONSECUTIVE_FAILURES = 3

        private val COLOR_CHARGE    = Color.parseColor("#5BE05B")
        private val COLOR_DISCHARGE = Color.parseColor("#FF5A5A")

        private const val YANDEX_PKG = "ru.yandex.yandexnavi"
        private const val YANDEX_ACTIVITY = "ru.yandex.yandexnavi.core.NavigatorActivity"
        private const val NOMINAL_KWH = 44.9

        // Пропорция: левая панель BMS / правая панель Яндекс
        private const val SPLIT_RATIO = 0.42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        contentText = findViewById(R.id.contentText)
        navContainer = findViewById(R.id.navContainer)
        navPlaceholder = findViewById(R.id.navPlaceholder)
        btnBack = findViewById(R.id.btnBack)
        btnHome = findViewById(R.id.btnHome)

        adb = AdbClient(this)
        ac = AutoserviceClient(adb)
        reader = BmsReader(ac)
        sohCalc = SohCalculator(File(filesDir, "soh_data.json"), nominalKwh = NOMINAL_KWH)

        setupBottomBar()

        // Запускаем Яндекс в правой панели через 1.5 сек после старта
        scope.launch {
            delay(1500)
            launchYandexInFreeform()
        }

        scope.launch { runLoop() }
    }

    // ────────────────────────────────────────────────────────
    //  Freeform-запуск Яндекс Навигатора
    // ────────────────────────────────────────────────────────

    /**
     * Запускает Яндекс в правой половине экрана через freeform-стек.
     *
     * Требует однократной ADB-настройки:
     *   adb shell settings put global enable_freeform_support 1
     *   adb shell settings put global force_resizable_activities 1
     *   adb reboot
     *
     * Force-stop обязателен: если Яндекс уже запущен в fullscreen-задаче,
     * флаг --windowingMode 5 будет проигнорирован, задача останется fullscreen.
     */
    private suspend fun launchYandexInFreeform() {
        // Проверка настроек
        val freeformEnabled = adb.shell("settings get global enable_freeform_support")?.trim()
        val forceResizable = adb.shell("settings get global force_resizable_activities")?.trim()
        Log.i(TAG, "settings: enable_freeform_support=$freeformEnabled, force_resizable_activities=$forceResizable")

        if (freeformEnabled != "1" || forceResizable != "1") {
            Log.e(TAG, "Freeform не включён — нужны ADB-команды и reboot")
            withContext(Dispatchers.Main) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Freeform отключён.\nВключите через ADB и перезагрузите ГУ."
            }
            return
        }

        // Force-stop: без него freeform не применится
        adb.shell("am force-stop $YANDEX_PKG")
        delay(500)

        // Запуск в freeform-стеке
        val startCmd = "am start --windowingMode 5 -n $YANDEX_PKG/$YANDEX_ACTIVITY"
        Log.i(TAG, "am start: $startCmd")
        val startResult = adb.shell(startCmd)
        Log.i(TAG, "am start result: $startResult")

        if (startResult == null || startResult.contains("Error", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Не удалось запустить\nЯндекс"
            }
            return
        }

        // Дать системе создать задачу
        delay(1500)

        // Найти стек с Яндексом
        val stackList = adb.shell("am stack list")
        if (stackList == null) {
            Log.e(TAG, "am stack list вернул null")
            return
        }
        val stackId = findStackIdForPackage(stackList, YANDEX_PKG)
        if (stackId == null) {
            Log.e(TAG, "Стек с $YANDEX_PKG не найден")
            withContext(Dispatchers.Main) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Яндекс не найден\nв стеке"
            }
            return
        }
        Log.i(TAG, "Найден stackId=$stackId")

        // Ресайз: точные границы
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val availableH = dm.heightPixels   // уже без системного бара
        val bottomBarPx = (80 * dm.density).toInt()

        val left   = (w * SPLIT_RATIO).toInt()
        val top    = 0
        val right  = w
        val bottom = availableH - bottomBarPx

        val resizeCmd = "am stack resize $stackId $left $top $right $bottom"
        Log.i(TAG, "am stack resize: $resizeCmd")
        val resizeResult = adb.shell(resizeCmd)
        Log.i(TAG, "resize result: $resizeResult")

        withContext(Dispatchers.Main) {
            if (resizeResult == null || resizeResult.contains("Error", ignoreCase = true)) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Не удалось\nотресайзить"
            } else {
                navPlaceholder.visibility = View.GONE
            }
        }
    }

    /**
     * Ищет StackId, содержащий задачу указанного пакета.
     * Работает независимо от windowingMode стека.
     */
    private fun findStackIdForPackage(stackList: String, packageName: String): Int? {
        var currentStackId: Int? = null
        for (line in stackList.lines()) {
            val stackMatch = Regex("""Stack id=(\d+)""").find(line)
            if (stackMatch != null) {
                currentStackId = stackMatch.groupValues[1].toIntOrNull()
                continue
            }
            if (line.contains("$packageName/") && currentStackId != null) {
                Log.d(TAG, "Package $packageName найден в stackId=$currentStackId")
                return currentStackId
            }
        }
        return null
    }

    // ────────────────────────────────────────────────────────
    //  Нижний бар: Back и Home
    // ────────────────────────────────────────────────────────

    private fun setupBottomBar() {
        btnBack.setOnClickListener {
            Log.d(TAG, "Back pressed")
            scope.launch { adb.shell("input keyevent 4") }
        }

        btnHome.setOnClickListener {
            Log.d(TAG, "Home pressed")
            scope.launch {
                val ok = adb.shell("input keyevent 3")
                if (ok == null || ok.contains("Error", ignoreCase = true)) {
                    adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  Основной цикл опроса BMS
    // ────────────────────────────────────────────────────────

    private suspend fun CoroutineScope.runLoop() {
        while (isActive) {
            if (!connectWithRetry()) return

            var consecutiveFailures = 0
            pollLoop@ while (isActive) {
                val snap = try {
                    reader.read()
                } catch (t: Throwable) {
                    Log.e(TAG, "read() threw", t)
                    null
                }

                if (snap == null) {
                    consecutiveFailures++
                    setContent("Нет ответа от BMS ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)...")
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        setContent("Потеряна связь с ADB. Переподключение...")
                        adb.disconnect()
                        break@pollLoop
                    }
                } else {
                    consecutiveFailures = 0
                    sohCalc.onSnapshot(snap)
                    setContent(format(snap))
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun CoroutineScope.connectWithRetry(): Boolean {
        while (isActive) {
            setContent("Подключение к ADB...")
            if (adb.connect()) {
                setContent("Подключено. Читаю BMS...")
                return true
            }
            setContent(
                "Ошибка подключения ADB.\n" +
                        "Проверьте, что ADB включён в инженерном меню.\n" +
                        "Повтор через ${RECONNECT_DELAY_MS / 1000} с..."
            )
            delay(RECONNECT_DELAY_MS)
        }
        return false
    }

    private suspend fun setContent(text: CharSequence) = withContext(Dispatchers.Main) {
        contentText.text = text
    }

    // ────────────────────────────────────────────────────────
    //  Форматирование данных BMS
    // ────────────────────────────────────────────────────────

    private fun format(s: BatterySnapshot): CharSequence {
        val computedPowerKw: Double? =
            if (s.hvVoltageV != null && s.hvCurrentA != null) {
                s.hvVoltageV.toDouble() * s.hvCurrentA.toDouble() / 1000.0
            } else null

        fun fmt3(v: Double?): String = v?.let { "%.3f".format(it) } ?: "—"
        fun fmt2(v: Float?):  String = v?.let { "%.2f".format(it) } ?: "—"

        fun fmtPower(v: Double?): String = v?.let {
            if (Math.abs(it) >= 10.0) "%.1f".format(it)
            else "%.3f".format(it)
        } ?: "—"

        val sb = StringBuilder()

        sb.appendLine("SOC:            ${s.socPercent ?: "—"} %")
        sb.appendLine("SOH OEM:        ${s.sohPercent ?: "—"} %")

        val calcSoh = sohCalc.getCurrentSoh()
        val validCount = sohCalc.getValidMeasurements().size
        val calcSohStr = when {
            sohCalc.isChargeInProgress() -> "идёт зарядка..."
            calcSoh != null              -> "%.1f".format(calcSoh) + " %  ($validCount зам.)"
            else                         -> "нет замеров"
        }
        sb.appendLine("SOH расчётный:  $calcSohStr")

        sb.appendLine("Ячейка max:     ${fmt3(s.maxCellV)} V")
        sb.appendLine("Ячейка min:     ${fmt3(s.minCellV)} V")
        sb.appendLine("Δ ячеек:        ${s.cellDeltaMv ?: "—"} мВ")
        sb.appendLine("Батарея t max:  ${s.maxBatTempC ?: "—"} °C")
        sb.appendLine("Батарея t min:  ${s.minBatTempC ?: "—"} °C")
        sb.appendLine("HV напряжение:  ${s.hvVoltageV ?: "—"} В")

        if (computedPowerKw != null) {
            val absKw = Math.abs(computedPowerKw)
            val word = when {
                computedPowerKw < -0.1 -> "заряд"
                computedPowerKw >  0.1 -> "разряд"
                else -> "покой"
            }
            val nativeStr = s.powerKw?.let { "$it кВт" } ?: "—"
            sb.appendLine("Мощность:       $word ${fmtPower(absKw)} кВт  (родная: $nativeStr)")
        } else {
            sb.appendLine("Мощность:       —")
        }

        sb.appendLine("12V батарея:    ${fmt2(s.voltage12v)} В")
        sb.appendLine("Изоляция:       ${s.insulationKohm ?: "—"} кОм")
        sb.appendLine("Расход:         ${s.lifetimeKwh ?: "—"} кВт·ч")
        sb.appendLine("Пробег:         ${s.lifetimeKm ?: "—"} км")
        sb.appendLine("BMS state:      ${s.bmsState ?: "—"}")

        val text = sb.toString()
        val spannable = SpannableString(text)

        var idx = 0
        while (true) {
            idx = text.indexOf("разряд", idx)
            if (idx < 0) break
            spannable.setSpan(
                ForegroundColorSpan(COLOR_DISCHARGE),
                idx, idx + 6,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            idx += 6
        }
        idx = 0
        while (true) {
            idx = text.indexOf("заряд", idx)
            if (idx < 0) break
            val insideRazryad = idx >= 2 && text.substring(idx - 2, idx) == "ра"
            if (!insideRazryad) {
                spannable.setSpan(
                    ForegroundColorSpan(COLOR_CHARGE),
                    idx, idx + 5,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            idx += 5
        }

        return spannable
    }

    override fun onDestroy() {
        scope.cancel()
        adb.disconnect()
        super.onDestroy()
    }
}