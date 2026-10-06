package com.ar.bydlauncher.byd

import com.ar.bydlauncher.weather.Weather

class RangeEstimator {

    companion object {
        private const val NOMINAL_KWH = 44.9
        private const val BASE_CONSUMPTION = 11.89

        const val HEATER_SOC_THRESHOLD = 30f
        const val HEATER_CELLV_THRESHOLD = 3.0
        const val HEATER_TEMP_THRESHOLD = 0

        private val TEMP_POINTS = listOf(
            25   to 1.00, 20 to 1.00, 15 to 1.02, 10 to 1.06, 5 to 1.12,
            0    to 1.22, -5 to 1.35, -10 to 1.55, -15 to 1.80,
            -20  to 2.12, -25 to 2.50
        )

        private val SPEED_POINTS = listOf(
            20f  to 0.92f, 50f to 0.88f, 70f to 1.00f,
            90f  to 1.12f, 110f to 1.39f, 130f to 1.75f
        )
    }

    data class Factor(
        val label: String,
        val percent: Int,
        val severity: Severity
    )

    enum class Severity { OK, WARN, BAD }

    data class Estimate(
        val mainKm: Int,
        val maxKm: Int,
        val minKm: Int,
        val consumption: Double,
        val baseConsumption: Double,
        val heaterWarning: Boolean,
        val factors: List<Factor>,
        val favorable: Boolean,
        val routeProfile: RouteProfile,
        val calibrationFactor: Double
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
        calibrationFactor: Double = 1.0
    ): Estimate {

        val soh = (sohPercent ?: 100) / 100.0
        val soc = socPercent / 100.0
        val effectiveKwh = NOMINAL_KWH * soh * soc

        val calibratedBase = BASE_CONSUMPTION * calibrationFactor

        // ВАЖНО: profileK НЕ используем — он дублирует speedK.
        // routeProfile нужен только для подписи в UI.
        val tempK    = tempCoefficient(tempOutsideC ?: 22)
        val speedK   = speedCoefficient(avgSpeedKmh)
        val windRes  = windCoefficient(weather, carBearing)
        val windK    = windRes.first
        val precipK  = precipCoefficient(weather)

        val consumption = calibratedBase * tempK * speedK * windK * precipK
        val mainKm = (effectiveKwh / consumption * 100).toInt()

        val maxKm = (mainKm * 1.10).toInt()
        val minKm = (mainKm * 0.85).toInt()

        val heaterWarning =
            socPercent <= HEATER_SOC_THRESHOLD + 10f &&
                    (maxCellV ?: 5.0) < HEATER_CELLV_THRESHOLD + 0.10 &&
                    (battTempC ?: 20) <= HEATER_TEMP_THRESHOLD + 3

        val factors = buildFactors(
            tempK = tempK,
            speedK = speedK,
            profile = routeProfile,
            windK = windK,
            windLabel = windRes.second,
            precipK = precipK,
            weather = weather,
            calibrationFactor = calibrationFactor
        )

        val favorable = factors.none {
            it.severity != Severity.OK && Math.abs(it.percent) >= 5
        }

        return Estimate(
            mainKm = mainKm,
            maxKm = maxKm,
            minKm = minKm,
            consumption = consumption,
            baseConsumption = calibratedBase,
            heaterWarning = heaterWarning,
            factors = factors,
            favorable = favorable,
            routeProfile = routeProfile,
            calibrationFactor = calibrationFactor
        )
    }

    private fun tempCoefficient(tempOut: Int): Double {
        if (tempOut >= 25) return 1.00
        if (tempOut <= -25) return 2.50
        val sorted = TEMP_POINTS.sortedBy { it.first }
        for (i in 0 until sorted.size - 1) {
            val (t1, k1) = sorted[i]
            val (t2, k2) = sorted[i + 1]
            if (tempOut in t1..t2) {
                val ratio = (tempOut - t1).toDouble() / (t2 - t1)
                return k1 + ratio * (k2 - k1)
            }
        }
        return 1.00
    }

    private fun speedCoefficient(speedKmh: Float): Double {
        if (speedKmh <= SPEED_POINTS.first().first) return SPEED_POINTS.first().second.toDouble()
        if (speedKmh >= SPEED_POINTS.last().first)  return SPEED_POINTS.last().second.toDouble()
        for (i in 0 until SPEED_POINTS.size - 1) {
            val (v1, k1) = SPEED_POINTS[i]
            val (v2, k2) = SPEED_POINTS[i + 1]
            if (speedKmh in v1..v2) {
                val t = (speedKmh - v1) / (v2 - v1)
                return (k1 + t * (k2 - k1)).toDouble()
            }
        }
        return 1.0
    }

    private fun windCoefficient(
        weather: Weather?,
        carBearing: Float?
    ): Pair<Double, String> {
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

    private fun buildFactors(
        tempK: Double,
        speedK: Double,
        profile: RouteProfile,
        windK: Double,
        windLabel: String,
        precipK: Double,
        weather: Weather?,
        calibrationFactor: Double
    ): List<Factor> {
        val list = mutableListOf<Factor>()

        // Калибровка
        val calPct = ((calibrationFactor - 1.0) * 100).toInt()
        if (Math.abs(calPct) >= 3) {
            list.add(Factor(
                label = "КАЛИБРОВКА",
                percent = calPct,
                severity = Severity.OK
            ))
        }

        // Температура
        val tempPct = ((tempK - 1.0) * 100).toInt()
        if (Math.abs(tempPct) >= 2) {
            val label = if (tempK > 1.05) "ХОЛОД" else "ХОЛОД"
            list.add(Factor(
                label = label,
                percent = tempPct,
                severity = when {
                    tempPct <= -25 -> Severity.BAD
                    tempPct <= -10 -> Severity.WARN
                    else           -> Severity.OK
                }
            ))
        }

        // Скорость — показываем как профиль (ГОРОД / МКАД / ТРАССА)
        val speedPct = ((speedK - 1.0) * 100).toInt()
        if (Math.abs(speedPct) >= 3 && !profile.isDefault) {
            list.add(Factor(
                label = profile.label,
                percent = speedPct,
                severity = when {
                    profile == RouteProfile.HIGHWAY -> Severity.WARN
                    profile == RouteProfile.MKAD    -> Severity.WARN
                    else                             -> Severity.OK
                }
            ))
        }

        // Ветер
        val windPct = ((windK - 1.0) * 100).toInt()
        if (Math.abs(windPct) >= 3 && windLabel.isNotEmpty()) {
            list.add(Factor(
                label = windLabel,
                percent = windPct,
                severity = if (windPct > 0) Severity.WARN else Severity.OK
            ))
        }

        // Осадки
        val precipPct = ((precipK - 1.0) * 100).toInt()
        if (precipPct >= 3) {
            val label = if ((weather?.snowfall ?: 0f) > 0.1f) "СНЕГ" else "ДОЖДЬ"
            list.add(Factor(
                label = label,
                percent = precipPct,
                severity = if (precipPct >= 15) Severity.BAD else Severity.WARN
            ))
        }

        return list
    }
}