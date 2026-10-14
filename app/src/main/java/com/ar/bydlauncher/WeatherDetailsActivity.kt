package com.ar.bydlauncher

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ar.bydlauncher.weather.DailyForecast
import com.ar.bydlauncher.weather.WeatherClient
import com.ar.bydlauncher.weather.WeatherIconProvider
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class WeatherDetailsActivity : Activity() {

    companion object {
        private const val TAG = "WeatherDetailsActivity"

        @Volatile var cachedLat: Double = Double.NaN
        @Volatile var cachedLon: Double = Double.NaN
        @Volatile var cachedLocationName: String? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var list: RecyclerView
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

        list = findViewById(R.id.weatherList)
        tvLocation = findViewById(R.id.weatherLocation)

        list.layoutManager = LinearLayoutManager(this)

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
        list.adapter = ForecastAdapter(days)
    }

    private fun renderError(msg: String) {
        list.adapter = ErrorAdapter(msg)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    // АДАПТЕР СПИСКА ДНЕЙ
    // ═══════════════════════════════════════════════════════════════
    private inner class ForecastAdapter(
        private val items: List<DailyForecast>
    ) : RecyclerView.Adapter<ForecastAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dayName: TextView     = v.findViewById(R.id.dayName)
            val icon: ImageView       = v.findViewById(R.id.dayIcon)
            val cond: TextView        = v.findViewById(R.id.dayCond)
            val precipIcon: ImageView = v.findViewById(R.id.dayPrecipIcon)
            val precip: TextView      = v.findViewById(R.id.dayPrecip)
            val wind: TextView        = v.findViewById(R.id.dayWind)
            val max: TextView         = v.findViewById(R.id.dayMax)
            val min: TextView         = v.findViewById(R.id.dayMin)
            val divider: View         = v.findViewById(R.id.dayDivider)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_weather_day, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val d = items[pos]
            val isToday = pos == 0
            val isTomorrow = pos == 1

            // ── День недели ──
            val label = when {
                isToday    -> "СЕГОДНЯ"
                isTomorrow -> "ЗАВТРА"
                else       -> d.date.format(dateFmt).uppercase()
            }
            h.dayName.text = label
            h.dayName.setTextColor(
                if (isToday) 0xFFFFFFFF.toInt() else 0xFFB0B4BD.toInt()
            )

            // ── Иконка погоды по WMO-коду ──
            WeatherIconProvider.load(
                view  = h.icon,
                code  = d.weatherCode,
                isDay = true
            )

            // ── Описание ──
            h.cond.text = d.description.uppercase()

            // ── Осадки: показываем только значимые ──
            if (d.precipitationMm >= 0.5f) {
                h.precipIcon.visibility = View.VISIBLE
                h.precip.visibility     = View.VISIBLE
                h.precip.text = String.format(Locale.US, "%.1f", d.precipitationMm)
                WeatherIconProvider.loadDrop(h.precipIcon)
            } else {
                h.precipIcon.visibility = View.GONE
                h.precip.visibility     = View.GONE
                h.precip.text = ""
            }

            // ── Ветер ──
            h.wind.text = if (d.windMaxMs > 0.1f)
                "%.0f м/с".format(d.windMaxMs)
            else "—"

            // ── Температуры ──
            h.max.text = "%.0f°".format(d.tempMax)
            h.min.text = "%.0f°".format(d.tempMin)

            // ── Разделитель: скрываем под последним элементом ──
            h.divider.visibility =
                if (pos == items.size - 1) View.GONE else View.VISIBLE
        }

        override fun getItemCount() = items.size
    }

    // ═══════════════════════════════════════════════════════════════
    // АДАПТЕР ОШИБКИ
    // ═══════════════════════════════════════════════════════════════
    private inner class ErrorAdapter(private val msg: String) :
        RecyclerView.Adapter<ErrorAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.errorText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_weather_error, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            h.tv.text = msg
        }

        override fun getItemCount() = 1
    }
}