package com.ar.bydlauncher

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ar.bydlauncher.byd.SohCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Детальный экран состояния батареи.
 * Показывает все успешные замеры SOH, статистику, ёмкость.
 */
class BatteryDetailsActivity : Activity() {

    companion object {
        /** Заполняется MainActivity перед запуском. */
        @Volatile
        var cachedMeasurements: List<SohCalculator.Measurement> = emptyList()

        @Volatile
        var cachedCurrentSoh: Double? = null

        @Volatile
        var cachedNominalKwh: Double = 44.9

        @Volatile
        var cachedSohOem: Int? = null
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var tvSummarySoh: TextView
    private lateinit var tvSummaryOem: TextView
    private lateinit var tvSummaryCapacity: TextView
    private lateinit var tvSummaryCount: TextView
    private lateinit var tvSummaryLastDate: TextView
    private lateinit var tvSummaryDiff: TextView

    private val dateFmt = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery_details)

        // Полноэкранный режим
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        listContainer     = findViewById(R.id.detailsList)
        tvSummarySoh      = findViewById(R.id.detailsSummarySoh)
        tvSummaryOem      = findViewById(R.id.detailsSummaryOem)
        tvSummaryCapacity = findViewById(R.id.detailsSummaryCapacity)
        tvSummaryCount    = findViewById(R.id.detailsSummaryCount)
        tvSummaryLastDate = findViewById(R.id.detailsSummaryLastDate)
        tvSummaryDiff     = findViewById(R.id.detailsSummaryDiff)

        // Кнопка «назад» — стрелка или крестик
        findViewById<View>(R.id.detailsClose).setOnClickListener { finish() }

        render()
    }

    private fun render() {
        val valid = cachedMeasurements.filter { !it.rejected }
        val all   = cachedMeasurements

        // ── Сводка ──
        tvSummarySoh.text = cachedCurrentSoh?.let { "%.1f%%".format(it) } ?: "—"
        tvSummaryOem.text = cachedSohOem?.let { "$it%" } ?: "—"
        tvSummaryCount.text = "${valid.size} / ${all.size}"

        // Ёмкость — из последнего успешного замера
        val lastValid = valid.lastOrNull()
        tvSummaryCapacity.text = lastValid?.let {
            "%.2f / %.1f кВт·ч".format(it.usableCapacityKwh, cachedNominalKwh)
        } ?: "—"

        tvSummaryLastDate.text = lastValid?.let {
            dateFmt.format(Date(it.ts))
        } ?: "нет замеров"

        // Разница OEM vs расчётный
        if (cachedCurrentSoh != null && cachedSohOem != null) {
            val diff = cachedCurrentSoh!! - cachedSohOem!!
            val sign = if (diff >= 0) "+" else ""
            tvSummaryDiff.text = "%s%.1f%%".format(sign, diff)
            tvSummaryDiff.setTextColor(
                when {
                    Math.abs(diff) < 2.0 -> 0xFF28C76F.toInt()   // зелёный
                    Math.abs(diff) < 5.0 -> 0xFFFF9F43.toInt()   // янтарный
                    else                 -> 0xFFEA5455.toInt()   // красный
                }
            )
        } else {
            tvSummaryDiff.text = "—"
        }

        // ── Список замеров ──
        listContainer.removeAllViews()

        if (all.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Замеров пока нет.\n\nSOH рассчитывается автоматически\nпосле каждой зарядки:\n\n• ΔSOC ≥ 20%\n• добавлено ≥ 0.5 кВт·ч\n• средняя температура 15–35°C"
                setTextColor(0xFFB0B4BD.toInt())
                textSize = 12f
                setPadding(24, 24, 24, 24)
                gravity = android.view.Gravity.CENTER
            }
            listContainer.addView(empty)
            return
        }

        // Заголовок списка
        val header = TextView(this).apply {
            text = "ЖУРНАЛ ЗАМЕРОВ"
            setTextColor(0xFFB0B4BD.toInt())
            textSize = 11f
            letterSpacing = 0.08f
            setPadding(0, 8, 0, 12)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        listContainer.addView(header)

        // Идём от свежих к старым
        all.sortedByDescending { it.ts }.forEach { m ->
            listContainer.addView(buildRow(m))
        }
    }

    private fun buildRow(m: SohCalculator.Measurement): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            background = getDrawable(R.drawable.bg_chip)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (6 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }

        // Заголовок строки: дата + SOH (цветной) или «ОТКЛОНЁН»
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val tvDate = TextView(this).apply {
            text = dateFmt.format(Date(m.ts))
            setTextColor(0xFFB0B4BD.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(tvDate)

        val tvSoh = TextView(this).apply {
            if (m.rejected) {
                text = "ОТКЛОНЁН"
                setTextColor(0xFFEA5455.toInt())
                textSize = 12f
            } else {
                text = "%.1f%%".format(m.sohPercent)
                setTextColor(
                    when {
                        m.sohPercent >= 95 -> 0xFF28C76F.toInt()
                        m.sohPercent >= 85 -> 0xFF2E93FA.toInt()
                        m.sohPercent >= 75 -> 0xFFFF9F43.toInt()
                        else               -> 0xFFEA5455.toInt()
                    }
                )
                textSize = 16f
            }
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        topRow.addView(tvSoh)

        root.addView(topRow)

        // Вторая строка: SOC / kWh / ёмкость
        val tvDetails = TextView(this).apply {
            if (m.rejected) {
                text = "Причина: ${m.rejectReason ?: "—"}"
                setTextColor(0xFFEA5455.toInt())
            } else {
                val tempStr = m.batTempC?.let { " · ${it}°C" } ?: ""
                text = "SOC: %.0f→%.0f%%   ·   +%.2f кВт·ч   ·   ёмкость: %.2f кВт·ч%s"
                    .format(m.socStart, m.socEnd, m.kwhAdded, m.usableCapacityKwh, tempStr)
                setTextColor(0xFF8E939D.toInt())
            }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 6, 0, 0)
        }
        root.addView(tvDetails)

        return root
    }
}