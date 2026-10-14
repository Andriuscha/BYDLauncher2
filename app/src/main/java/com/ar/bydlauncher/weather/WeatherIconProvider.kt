package com.ar.bydlauncher.weather

import android.util.Log
import android.widget.ImageView
import coil.load
import com.ar.bydlauncher.R

/**
 * Провайдер иконок погоды @meteocons/svg (jsDelivr).
 *
 * Маппинг по WMO-коду — надёжнее, чем по тексту описания.
 * Используется и в виджете (MainActivity), и в списке прогноза.
 */
object WeatherIconProvider {

    private const val TAG = "WeatherIconProvider"

    const val METEO_BASE =
        "https://cdn.jsdelivr.net/npm/@meteocons/svg@0.1.0/fill/"

    // ═══════════════════════════════════════════════════════════
    // WMO-код → имя SVG-файла
    // ═══════════════════════════════════════════════════════════
    fun fileNameForCode(code: Int, isDay: Boolean = true): String = when (code) {
        0  -> if (isDay) "clear-day.svg"            else "clear-night.svg"
        1  -> if (isDay) "mostly-clear-day.svg"     else "mostly-clear-night.svg"
        2  -> if (isDay) "partly-cloudy-day.svg"    else "partly-cloudy-night.svg"
        3  -> "overcast.svg"
        45, 48 -> if (isDay) "fog-day.svg"          else "fog-night.svg"

        51, 53, 55 -> "drizzle.svg"
        56, 57     -> "extreme-drizzle.svg"

        61, 63, 65 -> "rain.svg"
        66, 67     -> "extreme-rain.svg"

        71, 73, 75 -> "snow.svg"
        77         -> "snowflake.svg"

        80 -> if (isDay) "overcast-day-rain.svg"    else "overcast-night-rain.svg"
        81, 82 -> if (isDay) "extreme-day-rain.svg" else "extreme-night-rain.svg"

        85 -> if (isDay) "overcast-day-snow.svg"    else "overcast-night-snow.svg"
        86 -> if (isDay) "extreme-day-snow.svg"     else "extreme-night-snow.svg"

        95 -> if (isDay) "thunderstorms-day.svg"    else "thunderstorms-night.svg"
        96, 99 -> if (isDay) "thunderstorms-day-hail.svg" else "thunderstorms-night-hail.svg"

        else -> "not-available.svg"
    }

    fun urlForCode(code: Int, isDay: Boolean = true): String =
        METEO_BASE + fileNameForCode(code, isDay)

    // ═══════════════════════════════════════════════════════════
    // Загрузка иконки погоды по WMO-коду
    // ═══════════════════════════════════════════════════════════
    fun load(
        view: ImageView,
        code: Int,
        isDay: Boolean = true,
        onError: ((Throwable) -> Unit)? = null
    ) {
        val url = urlForCode(code, isDay)
        Log.d(TAG, "Loading weather icon: $url")

        view.load(url) {
            crossfade(200)
            placeholder(R.drawable.ic_weather_placeholder)
            error(R.drawable.ic_weather_placeholder)
            listener(
                onError = { _, result ->
                    Log.e(TAG, "Icon FAILED: $url — ${result.throwable.message}")
                    onError?.invoke(result.throwable)
                },
                onSuccess = { _, _ ->
                    Log.i(TAG, "Icon OK: $url")
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Иконка капли
    // ═══════════════════════════════════════════════════════════
    fun loadDrop(view: ImageView) {
        val url = "${METEO_BASE}raindrop.svg"
        Log.d(TAG, "Loading drop icon: $url")

        view.load(url) {
            crossfade(150)
            placeholder(R.drawable.ic_weather_placeholder)
            error(R.drawable.ic_weather_placeholder)
        }
    }
}