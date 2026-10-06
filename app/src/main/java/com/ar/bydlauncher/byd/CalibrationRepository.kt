package com.ar.bydlauncher.byd

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Хранилище фактических замеров расхода поездок для подстройки
 * прогноза запаса хода.
 *
 * При завершении поездки сохраняем:
 *  - среднюю температуру, среднюю скорость, профиль
 *  - фактический расход (кВт·ч/100км)
 *  - расход, который модель предсказала бы для этих условий
 *
 * При каждом прогнозе берём медиану отношений actual/predicted
 * для похожих температурных условий и умножаем BASE_CONSUMPTION.
 */
class CalibrationRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "CalibrationRepository"
        private const val PREFS_NAME = "range_calibration"
        private const val KEY_SAMPLES = "samples"

        private const val MAX_SAMPLES = 60          // храним последние 60
        private const val MIN_SAMPLES = 3           // минимум для подстройки
        private const val TEMP_TOLERANCE = 5        // ±5°C — «похожие» условия
        private const val MIN_TRIP_KM = 5.0         // короткие не пишем
        private const val RATIO_MIN = 0.5           // отсекаем выбросы
        private const val RATIO_MAX = 2.0
        private const val FACTOR_MIN = 0.80         // границы подстройки
        private const val FACTOR_MAX = 1.30
    }

    data class Sample(
        val ts: Long,
        val tempC: Int,
        val avgSpeedKmh: Float,
        val profile: String,
        val actualKwh100: Double,
        val predictedKwh100: Double
    ) {
        val ratio: Double
            get() = if (predictedKwh100 > 0.5 && actualKwh100 > 0.5)
                actualKwh100 / predictedKwh100
            else 1.0

        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", ts)
            put("tempC", tempC)
            put("avgSpeedKmh", avgSpeedKmh.toDouble())
            put("profile", profile)
            put("actualKwh100", actualKwh100)
            put("predictedKwh100", predictedKwh100)
        }

        companion object {
            fun fromJson(o: JSONObject): Sample = Sample(
                ts = o.optLong("ts", 0L),
                tempC = o.optInt("tempC", 15),
                avgSpeedKmh = o.optDouble("avgSpeedKmh", 50.0).toFloat(),
                profile = o.optString("profile", RouteProfile.UNKNOWN.name),
                actualKwh100 = o.optDouble("actualKwh100", 0.0),
                predictedKwh100 = o.optDouble("predictedKwh100", 0.0)
            )
        }
    }

    // ==================================================
    //  Запись
    // ==================================================

    /**
     * Добавить замер. Вызывается при завершении поездки.
     * Короткие поездки (< MIN_TRIP_KM) игнорируются.
     */
    @Synchronized
    fun addSample(
        tripKm: Double,
        tempAvgC: Double?,
        avgSpeedKmh: Double?,
        profile: RouteProfile,
        actualKwh100: Double,
        predictedKwh100: Double
    ) {
        if (tripKm < MIN_TRIP_KM) {
            Log.d(TAG, "Skip sample: trip too short ($tripKm km)")
            return
        }
        if (actualKwh100 <= 0.5 || predictedKwh100 <= 0.5) {
            Log.d(TAG, "Skip sample: invalid consumption actual=$actualKwh100 pred=$predictedKwh100")
            return
        }
        if (avgSpeedKmh == null || avgSpeedKmh < 3.0) {
            Log.d(TAG, "Skip sample: avg speed too low")
            return
        }

        val sample = Sample(
            ts = System.currentTimeMillis(),
            tempC = tempAvgC?.toInt() ?: 15,
            avgSpeedKmh = avgSpeedKmh.toFloat(),
            profile = profile.name,
            actualKwh100 = actualKwh100,
            predictedKwh100 = predictedKwh100
        )

        val list = loadSamples().toMutableList()
        list.add(sample)
        while (list.size > MAX_SAMPLES) list.removeAt(0)
        saveSamples(list)

        Log.i(
            TAG,
            "Sample added: t=${sample.tempC}° v=${"%.0f".format(sample.avgSpeedKmh)} " +
                    "profile=${sample.profile} " +
                    "actual=${"%.2f".format(actualKwh100)} " +
                    "pred=${"%.2f".format(predictedKwh100)} " +
                    "ratio=${"%.3f".format(sample.ratio)} (total ${list.size})"
        )
    }

    // ==================================================
    //  Чтение
    // ==================================================

    /**
     * Коэффициент калибровки для текущей температуры.
     * Возвращает медиану ratios похожих условий.
     * Если данных мало — 1.0 (без подстройки).
     */
    @Synchronized
    fun getCalibrationFactor(tempC: Int): Double {
        val all = loadSamples()
        if (all.isEmpty()) return 1.0

        // Сначала пробуем похожие по температуре
        val similar = all.filter {
            abs(it.tempC - tempC) <= TEMP_TOLERANCE &&
                    it.ratio in RATIO_MIN..RATIO_MAX
        }

        if (similar.size >= MIN_SAMPLES) {
            val f = median(similar.map { it.ratio }).coerceIn(FACTOR_MIN, FACTOR_MAX)
            Log.d(TAG, "Calibration factor (t=$tempC°): ${"%.3f".format(f)} from ${similar.size} similar samples")
            return f
        }

        // Fallback: все samples (если похожих мало, но данных достаточно)
        val usable = all.filter { it.ratio in RATIO_MIN..RATIO_MAX }
        if (usable.size >= MIN_SAMPLES + 2) {
            val f = median(usable.map { it.ratio }).coerceIn(FACTOR_MIN, FACTOR_MAX)
            Log.d(TAG, "Calibration factor (global fallback): ${"%.3f".format(f)} from ${usable.size} samples")
            return f
        }

        // Мало данных — без подстройки
        return 1.0
    }

    /** Количество сохранённых замеров. */
    @Synchronized
    fun getSampleCount(): Int = loadSamples().size

    /** Очистить историю (для отладки). */
    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_SAMPLES).apply()
        Log.i(TAG, "Calibration history cleared")
    }

    // ==================================================
    //  Внутреннее
    // ==================================================

    private fun loadSamples(): List<Sample> {
        val raw = prefs.getString(KEY_SAMPLES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Sample.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "loadSamples failed", e)
            emptyList()
        }
    }

    private fun saveSamples(list: List<Sample>) {
        try {
            val arr = JSONArray()
            for (s in list) arr.put(s.toJson())
            prefs.edit().putString(KEY_SAMPLES, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveSamples failed", e)
        }
    }

    private fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 1.0
        val s = sorted.sorted()
        return if (s.size % 2 == 0)
            (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        else
            s[s.size / 2]
    }
}