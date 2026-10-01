package com.ar.bydlauncher.weather

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Weather(
    val temperature: Double,
    val tempMax: Double,
    val tempMin: Double,
    val icon: String,
    val description: String
)

object WeatherClient {

    private const val TAG = "WeatherClient"

    private const val BYD_WEATHER_URI =
        "content://com.byd.weatherdata.utils.WeatherContentProvider/weather"

    suspend fun fetch(context: Context, lat: Double, lon: Double): Weather? {
        // 1. Сначала — BYD Provider (погода уже внутри машины)
        fetchFromByd(context)?.let {
            Log.i(TAG, "Weather from BYD provider: $it")
            return it
        }

        // 2. Fallback — Open-Meteo
        Log.i(TAG, "BYD provider failed, falling back to Open-Meteo")
        return fetchFromOpenMeteo(lat, lon)
    }

    // ─────────────────────────────────────────────────
    //  BYD ContentProvider
    // ─────────────────────────────────────────────────

    private fun fetchFromByd(context: Context): Weather? {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse(BYD_WEATHER_URI),
                null, null, null, null
            ) ?: return null

            cursor.use { c ->
                if (!c.moveToFirst()) {
                    Log.w(TAG, "BYD weather: empty cursor")
                    return null
                }

                Log.i(TAG, "BYD weather columns: ${c.columnNames.joinToString()}")

                val temp = getDouble(c,
                    "temperature", "temp", "temp_current", "temp_now",
                    "current_temp", "currentTemperature"
                ) ?: run {
                    Log.w(TAG, "BYD weather: no temperature column found")
                    return null
                }

                val max = getDouble(c,
                    "temp_max", "tempMax", "temp_max_today",
                    "max_temp", "maxTemperature"
                ) ?: temp

                val min = getDouble(c,
                    "temp_min", "tempMin", "temp_min_today",
                    "min_temp", "minTemperature"
                ) ?: temp

                val code = getInt(c,
                    "weather_code", "weatherCode", "code", "weather",
                    "current_weather_code"
                ) ?: 0

                Weather(
                    temperature = temp,
                    tempMax = max,
                    tempMin = min,
                    icon = iconFor(code),
                    description = descFor(code)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "BYD weather provider failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    private fun getDouble(c: Cursor, vararg names: String): Double? {
        for (name in names) {
            val idx = c.getColumnIndex(name)
            if (idx >= 0) {
                try {
                    if (c.isNull(idx)) continue
                    return c.getDouble(idx)
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun getInt(c: Cursor, vararg names: String): Int? {
        for (name in names) {
            val idx = c.getColumnIndex(name)
            if (idx >= 0) {
                try {
                    if (c.isNull(idx)) continue
                    return c.getInt(idx)
                } catch (_: Exception) {}
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────
    //  Open-Meteo (fallback)
    // ─────────────────────────────────────────────────

    private suspend fun fetchFromOpenMeteo(lat: Double, lon: Double): Weather? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                            "?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,weather_code" +
                            "&daily=temperature_2m_max,temperature_2m_min" +
                            "&timezone=auto&forecast_days=1"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode != 200) {
                    Log.e(TAG, "HTTP ${conn.responseCode}")
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                val cur = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")
                val code = cur.getInt("weather_code")

                Weather(
                    temperature = cur.getDouble("temperature_2m"),
                    tempMax = daily.getJSONArray("temperature_2m_max").getDouble(0),
                    tempMin = daily.getJSONArray("temperature_2m_min").getDouble(0),
                    icon = iconFor(code),
                    description = descFor(code)
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Open-Meteo fetch failed: ${e.javaClass.simpleName}: ${e.message}",
                    e
                )
                null
            }
        }

    private fun iconFor(code: Int): String = when (code) {
        0 -> "☀"
        1, 2 -> "🌤"
        3 -> "☁"
        45, 48 -> "🌫"
        51, 53, 55 -> "🌦"
        61, 63, 65 -> "🌧"
        71, 73, 75 -> "🌨"
        80, 81, 82 -> "🌧"
        95, 96, 99 -> "⛈"
        else -> "❓"
    }

    private fun descFor(code: Int): String = when (code) {
        0 -> "Ясно"
        1, 2 -> "Малооблачно"
        3 -> "Облачно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        61, 63, 65 -> "Дождь"
        71, 73, 75 -> "Снег"
        80, 81, 82 -> "Ливень"
        95, 96, 99 -> "Гроза"
        else -> "—"
    }
}