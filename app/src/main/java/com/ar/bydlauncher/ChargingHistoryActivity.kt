package com.ar.bydlauncher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.ar.bydlauncher.db.ChargingRecord
import com.ar.bydlauncher.db.ChargingRepository
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * История зарядок.
 *
 * Список — базовая информация:
 *   дата · время · кВт·ч станция/машина · тип (AC/DC)
 *
 * Детали — при тапе на строку:
 *   полная информация + редактирование пользовательских полей.
 */
class ChargingHistoryActivity : Activity() {

    companion object {
        private const val TAG = "ChargingHistory"
        private const val COLOR_DIALOG_BG = 0xFF15233A.toInt() // Тёмно-синий, как в настройках
        private const val COLOR_DIALOG_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_DIALOG_MUTED = 0xFFB0B4BD.toInt()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: ChargingRepository

    private lateinit var listContainer: LinearLayout
    private lateinit var statsView: TextView

    private val dateFmt = SimpleDateFormat("dd.MM.yy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_charging_history)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        repository = ChargingRepository(this)

        findViewById<View>(R.id.chargingClose).setOnClickListener { finish() }

        listContainer = findViewById(R.id.chargingList) ?: run {
            val scroll = findViewById<ScrollView>(R.id.chargingScroll)
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                scroll.addView(this)
            }
        }

        statsView = findViewById(R.id.chargingStats) ?: TextView(this)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ==================================================
    //  СПИСОК
    // ==================================================

    private fun refreshList() {
        scope.launch {
            val records = repository.getAll()
            val stats = repository.getTotalStats(0L, Long.MAX_VALUE)

            withContext(Dispatchers.Main) {
                renderStats(stats, records)
                renderList(records)
            }
        }
    }

    private fun renderStats(
        stats: com.ar.bydlauncher.db.ChargingStats,
        records: List<ChargingRecord>
    ) {
        if (!::statsView.isInitialized) return
        if (stats.count == 0) {
            statsView.visibility = View.GONE
            return
        }
        statsView.visibility = View.VISIBLE
        val sb = StringBuilder(
            "ВСЕГО: ${stats.count}  ·  " +
                    "🚗 %.1f кВт·ч  ·  🔌 %.1f кВт·ч  ·  %.2f р"
                        .format(stats.totalVehicleKwh, stats.totalStationKwh, stats.totalCost)
        )
        // Среднее «батарея/станция» отдельно для AC и DC — по сессиям, где введена энергия станции
        // и энергия батареи измерена (не оценена по номиналу).
        for (type in listOf("AC", "DC")) {
            val e = records
                .filter { it.acDc == type && it.notes?.contains("оцен") != true }
                .mapNotNull { it.efficiency }
            if (e.isNotEmpty()) {
                sb.append("\nБатарея/станция $type: %.0f%% (сессий: %d)".format(e.average() * 100, e.size))
            }
        }
        statsView.text = sb.toString()
    }

    private fun renderList(records: List<ChargingRecord>) {
        listContainer.removeAllViews()

        if (records.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Пока нет зарядок.\n\n" +
                        "Данные появятся автоматически\n" +
                        "после первой зарядки."
                setTextColor(0xFF6B7180.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
                typeface = android.graphics.Typeface.MONOSPACE
            }
            listContainer.addView(empty)
            return
        }

        for (r in records) {
            listContainer.addView(buildRow(r))
        }
    }

    private fun buildRow(r: ChargingRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF1A1D24.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { showDetails(r) }
        }

        // ── Верхняя строка: дата · время — AC/DC ──
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dateTime = TextView(this).apply {
            val start = Date(r.startedAt)
            val startStr = "${dateFmt.format(start)} · ${timeFmt.format(start)}"
            val endStr = r.endedAt?.let { " – ${timeFmt.format(Date(it))}" } ?: ""
            text = startStr + endStr
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(dateTime)

        // Бейдж AC/DC — «DC (быстрая)» / «AC (медленная)»
        val badge = TextView(this).apply {
            text = typeLabel(r.acDc)
            setTextColor(
                when (r.acDc) {
                    "DC" -> 0xFFFF9F43.toInt()
                    "AC" -> 0xFF2E93FA.toInt()
                    else -> 0xFF6B7180.toInt()
                }
            )
            textSize = 10f
            letterSpacing = 0.05f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setBackgroundColor(0x20FFFFFF)
        }
        topRow.addView(badge)

        card.addView(topRow)

        // ── Нижняя строка: кВт·ч станция/машина ──
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val vehicleKwh = TextView(this).apply {
            text = "🚗 %.1f кВт·ч".format(r.energyVehicleKwh)
            setTextColor(0xFF4FD1C5.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoRow.addView(vehicleKwh)

        val stationKwh = TextView(this).apply {
            text = if (r.energyStationKwh != null)
                "🔌 %.1f кВт·ч".format(r.energyStationKwh)
            else "🔌 —"
            setTextColor(if (r.energyStationKwh != null) 0xFFFFB84D.toInt() else 0xFF6B7180.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoRow.addView(stationKwh)

        val cost = TextView(this).apply {
            text = r.totalCost?.let { "%.2f р".format(it) } ?: ""
            setTextColor(0xFFB0B4BD.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        infoRow.addView(cost)

        card.addView(infoRow)
        return card
    }

    // ==================================================
    //  ДЕТАЛИ
    // ==================================================

    private fun showDetails(r: ChargingRecord) {
        val sb = StringBuilder()

        sb.appendLine("📅 ${dateFmt.format(Date(r.startedAt))}")
        sb.appendLine("🕐 Начало: ${timeFmt.format(Date(r.startedAt))}")
        r.endedAt?.let { sb.appendLine("🕐 Конец: ${timeFmt.format(Date(it))}") }
        r.durationMin?.let {
            val h = it / 60
            val m = it % 60
            sb.appendLine("⏱ Длительность: ${if (h > 0) "${h} ч " else ""}${m} мин")
        }

        // Тип зарядки — с пометкой «быстрая» / «медленная»
        r.acDc?.let {
            sb.appendLine("⚡ Тип зарядки: ${typeLabel(it)}")
        }

        sb.appendLine()
        sb.appendLine("⚡ ЭНЕРГИЯ")
        sb.appendLine("  Принято батареей: %.2f кВт·ч (по датчику BMS)%s".format(
            r.energyVehicleKwh,
            if (r.notes?.contains("оцен") == true) ", оценка" else ""
        ))
        r.energyStationKwh?.let { sb.appendLine("  Станция отдала:  %.2f кВт·ч".format(it)) }
        r.efficiency?.let { sb.appendLine("  Батарея/станция: %.1f%%".format(it * 100)) }
        r.bmsDelta?.let { sb.appendLine("  BMS counter Δ:   %.2f кВт·ч".format(it)) }

        if (r.bmsCounterStart != null || r.bmsCounterEnd != null) {
            sb.appendLine()
            sb.appendLine("📊 СЧЁТЧИК BMS (lifetimeKwh)")
            r.bmsCounterStart?.let { sb.appendLine("  Начало: %.1f".format(it)) }
            r.bmsCounterEnd?.let { sb.appendLine("  Конец:  %.1f".format(it)) }
        }

        sb.appendLine()
        sb.appendLine("🔋 SOC")
        r.startSoc?.let { sb.appendLine("  Начало: $it%") }
        r.endSoc?.let { sb.appendLine("  Конец:  $it%") }

        if (r.cellTempStartC != null || r.cellTempEndC != null) {
            sb.appendLine()
            sb.appendLine("🌡 ТЕМПЕРАТУРА ЯЧЕЕК (средняя)")
            r.cellTempStartC?.let { sb.appendLine("  Начало: %.1f°C".format(it)) }
            r.cellTempEndC?.let { sb.appendLine("  Конец:  %.1f°C".format(it)) }
        }

        if (r.tempOutsideStartC != null || r.tempOutsideEndC != null) {
            sb.appendLine()
            sb.appendLine("🌤 ВНЕШНЯЯ ТЕМПЕРАТУРА")
            r.tempOutsideStartC?.let { sb.appendLine("  Начало: %.0f°C".format(it)) }
            r.tempOutsideEndC?.let { sb.appendLine("  Конец:  %.0f°C".format(it)) }
        }

        if (r.odometerKm != null) {
            sb.appendLine()
            sb.appendLine("🚗 Пробег: %.1f км".format(r.odometerKm))
        }

        if (r.location != null || r.totalCost != null || r.costPerKwh != null) {
            sb.appendLine()
            sb.appendLine("💳 СТОИМОСТЬ")
            r.location?.let { sb.appendLine("  Станция: $it") }
            r.costPerKwh?.let { sb.appendLine("  Тариф: %.3f р/кВт·ч".format(it)) }
            r.totalCost?.let { sb.appendLine("  Всего: %.2f р".format(it)) }
        }

        if (r.notes != null) {
            sb.appendLine()
            sb.appendLine("📝 ${r.notes}")
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_DIALOG_BG)
            val tv = TextView(this@ChargingHistoryActivity).apply {
                text = sb.toString()
                setTextColor(COLOR_DIALOG_TEXT)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            addView(tv)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Детали зарядки")
            .setView(scroll)
            .setPositiveButton("ОК", null)
            .setNeutralButton("Редактировать") { _, _ ->
                showUserFieldsDialog(r)
            }
            .setNegativeButton("Удалить") { _, _ ->
                confirmDelete(r)
            }
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(COLOR_DIALOG_BG))
        dialog.show()

        // Перекрашиваем заголовок и кнопки
        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.setTextColor(COLOR_DIALOG_TEXT)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF2E93FA.toInt())
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFF2E93FA.toInt())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFFEA5455.toInt())
    }

    // ==================================================
    //  РЕДАКТИРОВАНИЕ
    // ==================================================

    private fun showUserFieldsDialog(r: ChargingRecord) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(COLOR_DIALOG_BG)
        }

        fun addField(label: String, value: String, numeric: Boolean = false): EditText {
            val tv = TextView(this).apply {
                text = label
                setTextColor(COLOR_DIALOG_MUTED)
                textSize = 11f
                letterSpacing = 0.1f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, dp(8), 0, dp(2))
            }
            container.addView(tv)

            val et = EditText(this).apply {
                setText(value)
                setTextColor(COLOR_DIALOG_TEXT)
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                inputType = if (numeric)
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else
                    InputType.TYPE_CLASS_TEXT
            }
            container.addView(et)
            return et
        }

        val etStation = addField(
            "Станция отдала, кВт·ч",
            r.energyStationKwh?.let { "%.2f".format(it) } ?: "",
            numeric = true
        )
        val etCostPerKwh = addField(
            "Тариф, р/кВт·ч",
            r.costPerKwh?.let { "%.3f".format(it) } ?: "",
            numeric = true
        )
        val etTotalCost = addField(
            "Итого, р",
            r.totalCost?.let { "%.2f".format(it) } ?: "",
            numeric = true
        )
        val etLocation = addField("Станция", r.location ?: "")
        val etNotes = addField("Заметки", r.notes ?: "")

        // AC / DC — с пометками
        val tvType = TextView(this).apply {
            text = "Тип зарядки"
            setTextColor(COLOR_DIALOG_MUTED)
            textSize = 11f
            letterSpacing = 0.1f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(4))
        }
        container.addView(tvType)

        val rgType = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbAc = RadioButton(this).apply {
            text = "AC (медленная)"
            setTextColor(COLOR_DIALOG_TEXT)
            isChecked = r.acDc != "DC"
        }
        val rbDc = RadioButton(this).apply {
            text = "DC (быстрая)"
            setTextColor(COLOR_DIALOG_TEXT)
            isChecked = r.acDc == "DC"
        }
        rgType.addView(rbAc)
        rgType.addView(rbDc)
        container.addView(rgType)

        val scroll = ScrollView(this).apply {
            addView(container)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Редактировать зарядку")
            .setView(scroll)
            .setPositiveButton("Сохранить") { _, _ ->
                val station = etStation.text.toString().replace(",", ".").toDoubleOrNull()
                val costKwh = etCostPerKwh.text.toString().replace(",", ".").toDoubleOrNull()
                var totalCost = etTotalCost.text.toString().replace(",", ".").toDoubleOrNull()

                if (totalCost == null && station != null && costKwh != null) {
                    totalCost = station * costKwh
                }

                val location = etLocation.text.toString().ifBlank { null }
                val notes = etNotes.text.toString().ifBlank { null }
                val acDc = if (rbDc.isChecked) "DC" else "AC"

                scope.launch {
                    repository.updateUserFields(
                        id = r.id,
                        energyStationKwh = station,
                        costPerKwh = costKwh,
                        totalCost = totalCost,
                        location = location,
                        acDc = acDc,
                        notes = notes
                    )
                    withContext(Dispatchers.Main) { refreshList() }
                }
            }
            .setNegativeButton("Отмена", null)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(COLOR_DIALOG_BG))
        dialog.show()

        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.setTextColor(COLOR_DIALOG_TEXT)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF2E93FA.toInt())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFFEA5455.toInt())
    }

    private fun confirmDelete(r: ChargingRecord) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Удалить зарядку?")
            .setMessage(
                "Запись от ${dateFmt.format(Date(r.startedAt))} " +
                        "${timeFmt.format(Date(r.startedAt))} будет удалена безвозвратно."
            )
            .setPositiveButton("Удалить") { _, _ ->
                scope.launch {
                    repository.delete(r.id)
                    withContext(Dispatchers.Main) { refreshList() }
                }
            }
            .setNegativeButton("Отмена", null)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(COLOR_DIALOG_BG))
        dialog.show()

        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        val messageId = resources.getIdentifier("message", "id", "android")
        dialog.findViewById<TextView>(titleId)?.setTextColor(COLOR_DIALOG_TEXT)
        dialog.findViewById<TextView>(messageId)?.setTextColor(COLOR_DIALOG_MUTED)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFEA5455.toInt())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF2E93FA.toInt())
    }

    // ==================================================
    //  ХЕЛПЕРЫ
    // ==================================================

    /**
     * Текстовая метка типа зарядки.
     * AC → «AC (медленная)», DC → «DC (быстрая)», иначе — «—».
     */
    private fun typeLabel(acDc: String?): String = when (acDc) {
        "AC" -> "AC (медленная)"
        "DC" -> "DC (быстрая)"
        else -> "—"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        repository.close()
        super.onDestroy()
    }
}