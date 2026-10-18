package com.ar.bydlauncher.byd

import com.ar.bydlauncher.weather.Weather

class RangeEstimator {

    companion object {
        const val NOMINAL_KWH = 44.9

        // Пороги для heaterWarning
        const val HEATER_SOC_THRESHOLD = 30f
        const val HEATER_CELLV_THRESHOLD = 3.0
        const val HEATER_TEMP_THRESHOLD = 0

        // Порог для "нет условий" в favorable
        private const val FAVORABLE_MAX_PCT = 5
    }

    data class Factor(
        val label: String,
        val percent: Int,
        val severity: Severity
    )

    enum class Severity { OK, WARN, BAD }

    enum class Trend { UP, DOWN, FLAT }

    enum class EstimateSource { KM_PER_PCT, BASE_RECENT, FALLBACK }

    data class Estimate(
        val mainKm: Int,
        val consumption: Double,
        val baseConsumption: Double,
        val heaterWarning: Boolean,
        val factors: List<Factor>,
        val favorable: Boolean,
        val routeProfile: RouteProfile,
        val calibrationFactor: Double,

        // ── НОВОЕ ──
        val kmPerPctCurrent: Double?,      // на месте "МАКС"
        val kmPerPctWeekAvg: Double?,      // на месте "МИН"
        val kmPerPctTrend: Trend,
        val source: EstimateSource
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
        calibrationFactor: Double = 1.0,

        // ── НОВЫЕ ВХОДЫ ──
        kmPerPctNorm: Double? = null,      // из KmPerPctRepository.getNormalized(profile)
        kmPerPctWeekAvg: Double? = null,   // из KmPerPctRepository.getSmoothedKmPerPct()
        kmPerPctPrevWeekAvg: Double? = null,
        baseRecent: Double? = null         // из BaseConsumptionRepository.getRecentBase()
    ): Estimate {

        val soc = socPercent
        val tempC = tempOutsideC ?: 22
        val tempK = RangeMath.tempCoefficient(tempC)
        val speedK = RangeMath.speedCoefficient(avgSpeedKmh)
        val windRes = windCoefficient(weather, carBearing)
        val windK = windRes.first
        val precipK = precipCoefficient(weather)

        // ═════════════════════════════════════════════════════
        // РАСЧЕТ ФИЗИЧЕСКОЙ МОДЕЛИ (нужен как "якорь")
        // ═════════════════════════════════════════════════════
        val base = baseRecent ?: RangeMath.BASE_DEFAULT_KWH100
        val weatherRaw = tempK * speedK * windK * precipK
        val weatherWeighted = 1.0 + (weatherRaw - 1.0) * routeProfile.weatherWeight
        val physConsumption = base * routeProfile.extraK * weatherWeighted
        val soh = (sohPercent ?: 100) / 100.0
        val effectiveKwh = NOMINAL_KWH * soh * (soc / 100.0)

        val physKm = if (physConsumption > 0.5) {
            (effectiveKwh / physConsumption * 100).toInt().coerceAtLeast(0)
        } else 0

        // ═════════════════════════════════════════════════════
        // ВЕТКА 1: эмпирика км/% (с защитой от аномалий)
        // ═════════════════════════════════════════════════════
        if (kmPerPctNorm != null && kmPerPctNorm > 0.5) {
            val kmPerPctEff = kmPerPctNorm / tempK
            val empiricalKm = (kmPerPctEff * soc).toInt().coerceAtLeast(0)

            // Защита: если эмпирика дает меньше 75% от физики — она "сломана" (пробки/трасса/мороз)
            // Берем среднее между физикой и эмпирикой, либо доверяем физике
            val mainKm = if (empiricalKm < physKm * 0.75) {
                // Эмпирика слишком пессимистична — смешиваем 50/50
                ((empiricalKm + physKm) / 2.0).toInt()
            } else {
                empiricalKm
            }

            val heaterWarning = computeHeaterWarning(socPercent, maxCellV, battTempC)
            val favorable = mainKm > 0 && !heaterWarning

            // Для consumption показываем "эквивалент" из км/%
            val equivalentConsumption = if (kmPerPctEff > 0.5) {
                NOMINAL_KWH / (kmPerPctEff * 100) * 100
            } else 0.0

            val factors = buildFactorsEmpirical(
                tempK = tempK,
                profile = routeProfile,
                windK = windK,
                windLabel = windRes.second,
                precipK = precipK
            )

            return Estimate(
                mainKm = mainKm,
                consumption = equivalentConsumption,
                baseConsumption = baseRecent ?: RangeMath.BASE_DEFAULT_KWH100,
                heaterWarning = heaterWarning,
                factors = factors,
                favorable = favorable,
                routeProfile = routeProfile,
                calibrationFactor = calibrationFactor,
                kmPerPctCurrent = kmPerPctEff,
                kmPerPctWeekAvg = kmPerPctWeekAvg,
                kmPerPctTrend = computeTrend(kmPerPctWeekAvg, kmPerPctPrevWeekAvg),
                source = EstimateSource.KM_PER_PCT
            )
        }

        // ═════════════════════════════════════════════════════
        // ВЕТКА 2: baseRecent или FALLBACK
        // ═════════════════════════════════════════════════════
        val source = if (baseRecent != null) EstimateSource.BASE_RECENT else EstimateSource.FALLBACK

        val heaterWarning = computeHeaterWarning(socPercent, maxCellV, battTempC)
        val favorable = physKm > 0 && !heaterWarning && factorsAreFavorable(
            tempK, speedK, routeProfile, windK, precipK, windRes.second, calibrationFactor
        )

        val factors = buildFactorsPhysical(
            tempK = tempK,
            speedK = speedK,
            profile = routeProfile,
            windK = windK,
            windLabel = windRes.second,
            precipK = precipK,
            weather = weather,
            calibrationFactor = calibrationFactor
        )

        return Estimate(
            mainKm = physKm,
            consumption = physConsumption,
            baseConsumption = base,
            heaterWarning = heaterWarning,
            factors = factors,
            favorable = favorable,
            routeProfile = routeProfile,
            calibrationFactor = calibrationFactor,
            kmPerPctCurrent = null,
            kmPerPctWeekAvg = kmPerPctWeekAvg,
            kmPerPctTrend = computeTrend(kmPerPctWeekAvg, kmPerPctPrevWeekAvg),
            source = source
        )
    }

    // ────────────────────────────────────────────────────────
    // Вспомогательные
    // ────────────────────────────────────────────────────────

    private fun computeHeaterWarning(
        socPercent: Float,
        maxCellV: Double?,
        battTempC: Int?
    ): Boolean =
        socPercent <= HEATER_SOC_THRESHOLD + 10f &&
                (maxCellV ?: 5.0) < HEATER_CELLV_THRESHOLD + 0.10 &&
                (battTempC ?: 20) <= HEATER_TEMP_THRESHOLD + 3

    private fun computeTrend(
        current: Double?,
        previous: Double?
    ): Trend {
        if (current == null || previous == null || previous < 0.1) return Trend.FLAT
        val ratio = current / previous
        return when {
            ratio > 1.02 -> Trend.UP
            ratio < 0.98 -> Trend.DOWN
            else         -> Trend.FLAT
        }
    }

    private fun factorsAreFavorable(
        tempK: Double,
        speedK: Double,
        profile: RouteProfile,
        windK: Double,
        precipK: Double,
        windLabel: String,
        calibrationFactor: Double
    ): Boolean {
        val tempPct = ((tempK - 1.0) * 100).toInt()
        val speedPct = ((speedK - 1.0) * 100).toInt()
        val windPct = ((windK - 1.0) * 100).toInt()
        val precipPct = ((precipK - 1.0) * 100).toInt()
        val calPct = ((calibrationFactor - 1.0) * 100).toInt()

        return Math.abs(tempPct) < FAVORABLE_MAX_PCT &&
                Math.abs(speedPct) < FAVORABLE_MAX_PCT &&
                Math.abs(windPct) < FAVORABLE_MAX_PCT &&
                precipPct < FAVORABLE_MAX_PCT &&
                Math.abs(calPct) < FAVORABLE_MAX_PCT
    }

    // ────────────────────────────────────────────────────────
    // Коэффициенты — делегаты в RangeMath
    // ────────────────────────────────────────────────────────

    private fun tempCoefficient(tempOut: Int): Double = RangeMath.tempCoefficient(tempOut)
    private fun speedCoefficient(speedKmh: Float): Double = RangeMath.speedCoefficient(speedKmh)

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

    // ────────────────────────────────────────────────────────
    // Факторы (для UI — подпись под прогнозом)
    // ────────────────────────────────────────────────────────

    private fun buildFactorsEmpirical(
        tempK: Double,
        profile: RouteProfile,
        windK: Double,
        windLabel: String,
        precipK: Double
    ): List<Factor> {
        val list = mutableListOf<Factor>()

        val tempPct = ((tempK - 1.0) * 100).toInt()
        if (Math.abs(tempPct) >= 2) {
            list.add(Factor(
                label = "ХОЛОД",
                percent = tempPct,
                severity = when {
                    tempPct >= 25 -> Severity.BAD
                    tempPct >= 10 -> Severity.WARN
                    else          -> Severity.OK
                }
            ))
        }

        val windPct = ((windK - 1.0) * 100).toInt()
        if (Math.abs(windPct) >= 3 && windLabel.isNotEmpty()) {
            list.add(Factor(
                label = windLabel,
                percent = windPct,
                severity = if (windPct > 0) Severity.WARN else Severity.OK
            ))
        }

        val precipPct = ((precipK - 1.0) * 100).toInt()
        if (precipPct >= 3) {
            list.add(Factor(
                label = "ОСАДКИ",
                percent = precipPct,
                severity = if (precipPct >= 15) Severity.BAD else Severity.WARN
            ))
        }

        return list
    }

    private fun buildFactorsPhysical(
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

        val calPct = ((calibrationFactor - 1.0) * 100).toInt()
        if (Math.abs(calPct) >= 3) {
            list.add(Factor(
                label = "КАЛИБРОВКА",
                percent = calPct,
                severity = Severity.OK
            ))
        }

        val tempPct = ((tempK - 1.0) * 100).toInt()
        if (Math.abs(tempPct) >= 2) {
            list.add(Factor(
                label = "ХОЛОД",
                percent = tempPct,
                severity = when {
                    tempPct >= 25 -> Severity.BAD
                    tempPct >= 10 -> Severity.WARN
                    else          -> Severity.OK
                }
            ))
        }

        val speedPct = ((speedK - 1.0) * 100).toInt()
        if (Math.abs(speedPct) >= 3 && !profile.isDefault) {
            list.add(Factor(
                label = profile.label,
                percent = speedPct,
                severity = when (profile) {
                    RouteProfile.HIGHWAY -> Severity.WARN
                    RouteProfile.MKAD    -> Severity.WARN
                    else                 -> Severity.OK
                }
            ))
        }

        val windPct = ((windK - 1.0) * 100).toInt()
        if (Math.abs(windPct) >= 3 && windLabel.isNotEmpty()) {
            list.add(Factor(
                label = windLabel,
                percent = windPct,
                severity = if (windPct > 0) Severity.WARN else Severity.OK
            ))
        }

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