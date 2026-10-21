package com.ar.bydlauncher.byd

import com.ar.bydlauncher.db.DayStats
import com.ar.bydlauncher.weather.Weather
import java.util.Calendar

class RangeEstimator {

    companion object {
        const val NOMINAL_KWH = 44.9
        const val DEFAULT_CONSUMPTION = 13.5 // Если вообще нет данных

        // Пороги для heaterWarning
        const val HEATER_SOC_THRESHOLD = 30f
        const val HEATER_CELLV_THRESHOLD = 3.0
        const val HEATER_TEMP_THRESHOLD = 0
    }

    data class Factor(
        val label: String,
        val percent: Int,
        val severity: Severity
    )

    enum class Severity { OK, WARN, BAD }
    enum class Trend { UP, DOWN, FLAT }

    data class Estimate(
        val mainKm: Int,
        val consumption: Double,
        val heaterWarning: Boolean,
        val factors: List<Factor>,
        val favorable: Boolean,
        val routeProfile: RouteProfile,
        val kmPerPctCurrent: Double?, // Оставляем для совместимости с UI
        val kmPerPctWeekAvg: Double?,
        val kmPerPctTrend: Trend
    )

    fun estimate(
        socPercent: Float,
        sohPercent: Int?,
        tempOutsideC: Int?,
        avgSpeedKmh: Float,
        maxCellV: Double? = null,
        battTempC: Int? = null,
        weather: Weather? = null,
        carBearing: Float? = null,
        routeProfile: RouteProfile = RouteProfile.UNKNOWN,
        dailyStats: List<DayStats>
    ): Estimate {

        val soc = socPercent
        val soh = sohPercent ?: 100

        // 1. Вычисляем доступную энергию
        // Реальная емкость = 44.9 * (SOH / 100)
        // Остаток = Реальная емкость * (SOC / 100)
        val realCapacityKwh = NOMINAL_KWH * (soh / 100.0)
        val availableKwh = realCapacityKwh * (soc / 100.0)

        // 2. Находим статистику за сегодня, вчера и позавчера
        val todayStart = getTodayStart()
        val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
        val dayBeforeStart = yesterdayStart - 24 * 60 * 60 * 1000L

        val today = dailyStats.find { it.startOfDayMs == todayStart }
        val yesterday = dailyStats.find { it.startOfDayMs == yesterdayStart }
        val dayBefore = dailyStats.find { it.startOfDayMs == dayBeforeStart }

        fun getCons(stats: DayStats?): Double? {
            if (stats == null || stats.totalDistanceKm < 0.1) return null
            return (stats.totalEnergyKwh / stats.totalDistanceKm) * 100.0
        }

        val cToday = getCons(today)
        val cYesterday = getCons(yesterday)
        val cDayBefore = getCons(dayBefore)

        // 3. Считаем базовый расход по весам
        var baseConsumption = DEFAULT_CONSUMPTION

        if (today != null && today.totalDistanceKm > 10.0 && cToday != null) {
            // Есть статистика за сегодня (>10 км): 50% сегодня, 35% вчера, 15% позавчера
            var sumWeights = 0.5
            var sumCons = cToday * 0.5

            if (cYesterday != null) {
                sumCons += cYesterday * 0.35
                sumWeights += 0.35
            }
            if (cDayBefore != null) {
                sumCons += cDayBefore * 0.15
                sumWeights += 0.15
            }
            baseConsumption = sumCons / sumWeights
        } else {
            // Нет статистики за сегодня: 70% вчера, 30% позавчера
            var sumWeights = 0.0
            var sumCons = 0.0

            if (cYesterday != null) {
                sumCons += cYesterday * 0.7
                sumWeights += 0.7
            }
            if (cDayBefore != null) {
                sumCons += cDayBefore * 0.3
                sumWeights += 0.3
            }

            if (sumWeights > 0) {
                baseConsumption = sumCons / sumWeights
            }
        }

        // 4. Накладываем погодные штрафы (для города, МКАД, трассы)
        val windRes = windCoefficient(weather, carBearing)
        val precipK = precipCoefficient(weather)

        // Вес погоды зависит от профиля (в городе ветер/дождь меньше влияют, на трассе - больше)
        val weatherRaw = windRes.first * precipK
        val weatherWeighted = 1.0 + (weatherRaw - 1.0) * routeProfile.weatherWeight

        val finalConsumption = baseConsumption * weatherWeighted

        // 5. Итоговый прогноз
        val mainKm = if (finalConsumption > 0.1) {
            (availableKwh / finalConsumption * 100).toInt().coerceAtLeast(0)
        } else 0

        // 6. Факторы для UI
        val factors = mutableListOf<Factor>()
        if (windRes.first > 1.02) {
            factors.add(Factor("ВСТРЕЧНЫЙ ВЕТЕР", ((windRes.first - 1) * 100).toInt(), Severity.WARN))
        } else if (windRes.first < 0.98) {
            factors.add(Factor("ПОПУТНЫЙ ВЕТЕР", ((windRes.first - 1) * 100).toInt(), Severity.OK))
        }
        if (precipK > 1.0) {
            factors.add(Factor("ОСАДКИ", ((precipK - 1) * 100).toInt(), Severity.WARN))
        }
        if (tempOutsideC != null && tempOutsideC < 10) {
            factors.add(Factor("ХОЛОД", ((10 - tempOutsideC) * 2), Severity.WARN))
        }

        val heaterWarning = computeHeaterWarning(socPercent, maxCellV, battTempC)
        val favorable = mainKm > 0 && !heaterWarning && factors.isEmpty()

        // 7. Эквивалент км/% для UI (чтобы не ломать старый интерфейс)
        val kmPerPctCurrent = if (finalConsumption > 0.1) (NOMINAL_KWH / finalConsumption) else null

        // Для недельного среднего берем вчерашний расход
        val kmPerPctWeekAvg = cYesterday?.let { NOMINAL_KWH / it }

        return Estimate(
            mainKm = mainKm,
            consumption = finalConsumption,
            heaterWarning = heaterWarning,
            factors = factors,
            favorable = favorable,
            routeProfile = routeProfile,
            kmPerPctCurrent = kmPerPctCurrent,
            kmPerPctWeekAvg = kmPerPctWeekAvg,
            kmPerPctTrend = Trend.FLAT
        )
    }

    private fun getTodayStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 6)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (System.currentTimeMillis() < cal.timeInMillis) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return cal.timeInMillis
    }

    private fun computeHeaterWarning(
        socPercent: Float,
        maxCellV: Double?,
        battTempC: Int?
    ): Boolean =
        socPercent <= HEATER_SOC_THRESHOLD + 10f &&
                (maxCellV ?: 5.0) < HEATER_CELLV_THRESHOLD + 0.10 &&
                (battTempC ?: 20) <= HEATER_TEMP_THRESHOLD + 3

    private fun windCoefficient(weather: Weather?, carBearing: Float?): Pair<Double, String> {
        val speed = weather?.windSpeedMs ?: return 1.0 to ""
        val windDir = weather.windDirection
        if (speed < 3f) return 1.0 to ""

        val basePenalty = when {
            speed < 7f  -> speed * 0.8f
            speed < 12f -> 5.6f + (speed - 7f) * 1.0f
            speed < 17f -> 10.6f + (speed - 12f) * 1.5f
            else        -> 18.1f + (speed - 17f) * 2.0f
        }

        val headwindFactor = if (windDir != null && carBearing != null) {
            val angleDeg = ((windDir - carBearing + 360f) % 360f).toDouble()
            Math.cos(Math.toRadians(angleDeg)).toFloat()
        } else 0f

        val signedPercent = if (headwindFactor > 0.1f) {
            basePenalty * headwindFactor
        } else if (headwindFactor < -0.1f) {
            basePenalty * headwindFactor * 0.6f
        } else 0f

        val k = 1.0 + signedPercent / 100.0
        val label = when {
            signedPercent > 2f  -> "ВСТРЕЧНЫЙ ВЕТЕР"
            signedPercent < -2f -> "ПОПУТНЫЙ ВЕТЕР"
            else                -> ""
        }
        return k to label
    }

    private fun precipCoefficient(weather: Weather?): Double {
        if (weather == null) return 1.0
        val snow = weather.snowfall ?: 0f
        val rain = weather.precipitation ?: 0f
        return when {
            snow > 0.1f -> 1.20
            rain < 0.5f -> 1.00
            rain < 2.0f -> 1.05
            rain < 7.0f -> 1.10
            else        -> 1.18
        }
    }
}