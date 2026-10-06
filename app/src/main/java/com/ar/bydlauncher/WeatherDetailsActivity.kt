package com.ar.bydlauncher

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ar.bydlauncher.db.TripRepository
import com.ar.bydlauncher.weather.DailyForecast
import com.ar.bydlauncher.weather.WeatherClient
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Расширенный прогноз погоды на 7 дней.
 *
 * Использует lastKnownLocation, переданную через companion (MainActivity
 * кладёт координаты перед startActivity).
 */
class WeatherDetailsActivity : Activity() {

    companion object {
        private const val TAG = "WeatherDetailsActivity"

        /** Заполняется MainActivity перед запуском. */
        @Volatile
        var cachedLat: Double = Double.NaN

        @Volatile
        var cachedLon: Double = Double.NaN

        @Volatile
        var cachedLocationName: String? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var container: LinearLayout
    private lateinit var tvLocation: TextView

    private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale("ru"))
    private val today = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather_details)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        container = findViewById(R.id.weatherList)
        tvLocation = findViewById(R.id.weatherLocation)

        findViewById<View>(R.id.weatherClose).setOnClickListener { finish() }

        tvLocation.text = cachedLocationName ?: "—"

        if (cachedLat.isNaN() || cachedLon.isNaN()) {
            renderError("Нет координат для запроса прогноза")
            return
        }

        loadDaily()
    }

    private fun loadDaily() {
        scope.launch {
            val days = WeatherClient.fetchDaily(cachedLat, cachedLon, 7)
            if (days == null || days.isEmpty()) {
                renderError("Не удалось загрузить прогноз")
                return@launch
            }
            renderDays(days)
        }
    }

    private fun renderDays(days: List<DailyForecast>) {
        container.removeAllViews()

        days.forEachIndexed { idx, d ->
            container.addView(buildDayCard(d, isToday = (idx == 0)))
        }
    }

    private fun renderError(msg: String) {
        container.removeAllViews()
        val tv = TextView(this).apply {
            text = msg
            setTextColor(0xFFEA5455.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(32), dp(16), dp(32))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        container.addView(tv)
    }

    private fun buildDayCard(d: DailyForecast, isToday: Boolean): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(R.drawable.bg_chip)
            gravity = Gravity.CENTER_VERTICAL

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        // ── Левая часть: дата + иконка + описание ──
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        val tvDate = TextView(this).apply {
            val label = when {
                isToday -> "СЕГОДНЯ"
                d.date == today.plusDays(1) -> "ЗАВТРА"
                else -> d.date.format(dateFmt).uppercase()
            }
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        left.addView(tvDate)

        val tvDesc = TextView(this).apply {
            text = "${d.icon}  ${d.description}"
            setTextColor(0xFFB0B4BD.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        }
        left.addView(tvDesc)

        root.addView(left)

        // ── Центр: осадки + ветер ──
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvPrecip = TextView(this).apply {
            text = if (d.precipitationMm > 0.1f)
                "💧 %.1f мм".format(d.precipitationMm)
            else "—"
            setTextColor(0xFF2E93FA.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        center.addView(tvPrecip)

        val tvWind = TextView(this).apply {
            text = if (d.windMaxMs > 0.1f)
                "💨 %.0f м/с".format(d.windMaxMs)
            else "—"
            setTextColor(0xFFB0B4BD.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        }
        center.addView(tvWind)

        root.addView(center)

        // ── Правая часть: макс/мин ──
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvMax = TextView(this).apply {
            text = "%.0f°".format(d.tempMax)
            setTextColor(0xFFFF9F43.toInt())
            textSize = 20f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        right.addView(tvMax)

        val tvSep = TextView(this).apply {
            text = " / "
            setTextColor(0xFFB0B4BD.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        right.addView(tvSep)

        val tvMin = TextView(this).apply {
            text = "%.0f°".format(d.tempMin)
            setTextColor(0xFF2E93FA.toInt())
            textSize = 20f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        right.addView(tvMin)

        root.addView(right)

        return root
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}