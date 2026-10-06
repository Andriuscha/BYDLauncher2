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
import java.time.LocalDate

data class Weather(
    val temperature: Double,
    val tempMax: Double,
    val tempMin: Double,
    val icon: String,
    val description: String,

    val windSpeedMs: Float? = null,
    val windDirection: Float? = null,
    val precipitation: Float? = null,
    val snowfall: Float? = null,
    val weatherCode: Int? = null,
    val isDay: Boolean = true,
    val fetchedAtMs: Long = System.currentTimeMillis()
) {
    val hasPrecipitation: Boolean
        get() = (precipitation ?: 0f) > 0.1f || (snowfall ?: 0f) > 0.1f

    val isSnowing: Boolean
        get() = (snowfall ?: 0f) > 0.1f

    val isWindy: Boolean
        get() = (windSpeedMs ?: 0f) > 7f

    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - fetchedAtMs > 30 * 60_000L
}

/**
 * Одна дневная запись прогноза.
 */
data class DailyForecast(
    val date: LocalDate,
    val tempMax: Double,
    val tempMin: Double,
    val weatherCode: Int,
    val precipitationMm: Float,     // сумма за день
    val windMaxMs: Float,           // макс. ветер за день
    val sunriseIso: String? = null, // ISO time, опционально
    val sunsetIso: String? = null
) {
    val icon: String get() = WeatherClient.iconFor(weatherCode, isDay = true)
    val description: String get() = WeatherClient.descFor(weatherCode)
}

object WeatherClient {

    private const val TAG = "WeatherClient"

    private const val BYD_WEATHER_URI =
        "content://com.byd.weatherdata.utils.WeatherContentProvider/weather"

    // ─────────────────────────────────────────────────
    //  Текущая погода
    // ─────────────────────────────────────────────────

    suspend fun fetch(context: Context, lat: Double, lon: Double): Weather? {
        fetchFromByd(context)?.let {
            Log.i(TAG, "Weather from BYD provider: $it")
            return it
        }
        Log.i(TAG, "BYD provider failed, falling back to Open-Meteo")
        return fetchFromOpenMeteo(lat, lon)
    }

    // ─────────────────────────────────────────────────
    //  Прогноз на несколько дней
    // ─────────────────────────────────────────────────

    /**
     * Запрос дневного прогноза Open-Meteo.
     * Возвращает список дней (до [days]), либо null при ошибке.
     */
    suspend fun fetchDaily(
        lat: Double,
        lon: Double,
        days: Int = 7
    ): List<DailyForecast>? = withContext(Dispatchers.IO) {
        try {
            val safeDays = days.coerceIn(1, 14)
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&daily=" +
                        "weather_code," +
                        "temperature_2m_max," +
                        "temperature_2m_min," +
                        "precipitation_sum," +
                        "wind_speed_10m_max," +
                        "sunrise," +
                        "sunset" +
                        "&timezone=auto" +
                        "&forecast_days=$safeDays"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) {
                Log.e(TAG, "fetchDaily HTTP ${conn.responseCode}")
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(body)
            val daily = json.getJSONObject("daily")

            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val maxT = daily.getJSONArray("temperature_2m_max")
            val minT = daily.getJSONArray("temperature_2m_min")
            val precip = daily.optJSONArray("precipitation_sum")
            val windMax = daily.optJSONArray("wind_speed_10m_max")
            val sunrise = daily.optJSONArray("sunrise")
            val sunset = daily.optJSONArray("sunset")

            val out = ArrayList<DailyForecast>(dates.length())
            for (i in 0 until dates.length()) {
                out.add(
                    DailyForecast(
                        date = LocalDate.parse(dates.getString(i)),
                        tempMax = maxT.getDouble(i),
                        tempMin = minT.getDouble(i),
                        weatherCode = codes.getInt(i),
                        precipitationMm = precip?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                        windMaxMs = windMax?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                        sunriseIso = sunrise?.optString(i),
                        sunsetIso = sunset?.optString(i)
                    )
                )
            }

            Log.i(TAG, "fetchDaily OK: ${out.size} days")
            out
        } catch (e: Exception) {
            Log.e(TAG, "fetchDaily failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
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

                val windSpeed = getDouble(c,
                    "wind_speed", "windSpeed", "wind_speed_10m"
                )?.toFloat()

                val windDir = getDouble(c,
                    "wind_direction", "windDirection", "wind_dir", "wind_direction_10m"
                )?.toFloat()

                val precip = getDouble(c,
                    "precipitation", "precip", "rain"
                )?.toFloat()

                val snowfall = getDouble(c,
                    "snowfall", "snow"
                )?.toFloat()

                Weather(
                    temperature = temp,
                    tempMax = max,
                    tempMin = min,
                    icon = iconFor(code),
                    description = descFor(code),
                    windSpeedMs = windSpeed,
                    windDirection = windDir,
                    precipitation = precip,
                    snowfall = snowfall,
                    weatherCode = code,
                    isDay = true,
                    fetchedAtMs = System.currentTimeMillis()
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
    //  Open-Meteo — текущая
    // ─────────────────────────────────────────────────

    private suspend fun fetchFromOpenMeteo(lat: Double, lon: Double): Weather? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                            "?latitude=$lat&longitude=$lon" +
                            "&current=" +
                            "temperature_2m," +
                            "weather_code," +
                            "is_day," +
                            "wind_speed_10m," +
                            "wind_direction_10m," +
                            "precipitation," +
                            "snowfall" +
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
                val temp = cur.getDouble("temperature_2m")

                val windSpeed = if (cur.has("wind_speed_10m"))
                    cur.getDouble("wind_speed_10m").toFloat() else null
                val windDir = if (cur.has("wind_direction_10m"))
                    cur.getDouble("wind_direction_10m").toFloat() else null
                val precip = if (cur.has("precipitation"))
                    cur.getDouble("precipitation").toFloat() else null
                val snowfall = if (cur.has("snowfall"))
                    cur.getDouble("snowfall").toFloat() else null
                val isDay = if (cur.has("is_day"))
                    cur.getInt("is_day") == 1 else true

                Log.i(
                    TAG,
                    "Open-Meteo OK: t=$temp code=$code " +
                            "wind=${windSpeed}m/s @ ${windDir}° " +
                            "precip=${precip}mm snowfall=${snowfall}cm isDay=$isDay"
                )

                Weather(
                    temperature = temp,
                    tempMax = daily.getJSONArray("temperature_2m_max").getDouble(0),
                    tempMin = daily.getJSONArray("temperature_2m_min").getDouble(0),
                    icon = iconFor(code, isDay),
                    description = descFor(code),
                    windSpeedMs = windSpeed,
                    windDirection = windDir,
                    precipitation = precip,
                    snowfall = snowfall,
                    weatherCode = code,
                    isDay = isDay,
                    fetchedAtMs = System.currentTimeMillis()
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

    // ─────────────────────────────────────────────────
    //  WMO code → icon/desc
    // ─────────────────────────────────────────────────

    fun iconFor(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "☀" else "🌙"
        1, 2 -> if (isDay) "🌤" else "🌙"
        3 -> "☁"
        45, 48 -> "🌫"
        51, 53, 55 -> "🌦"
        56, 57 -> "🌧"
        61, 63, 65 -> "🌧"
        66, 67 -> "🌧"
        71, 73, 75 -> "🌨"
        77 -> "🌨"
        80, 81, 82 -> "🌧"
        85, 86 -> "🌨"
        95 -> "⛈"
        96, 99 -> "⛈"
        else -> "❓"
    }

    fun descFor(code: Int): String = when (code) {
        0 -> "Ясно"
        1 -> "Преимущ. ясно"
        2 -> "Переменно"
        3 -> "Облачно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        56, 57 -> "Лед. морось"
        61, 63, 65 -> "Дождь"
        66, 67 -> "Лед. дождь"
        71, 73, 75 -> "Снег"
        77 -> "Крупа"
        80, 81, 82 -> "Ливень"
        85, 86 -> "Снегопад"
        95 -> "Гроза"
        96, 99 -> "Гроза с градом"
        else -> "—"
    }
}