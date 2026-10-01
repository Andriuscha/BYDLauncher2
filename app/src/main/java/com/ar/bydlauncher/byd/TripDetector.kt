package com.ar.bydlauncher.byd

import android.util.Log

/**
 * Определяет начало и конец поездки.
 *
 * НАЧАЛО:  powerLevel == POWER_ON  ИЛИ  gear ушёл из P.
 * КОНЕЦ:   gear == P И speed < 1 км/ч в течение STOP_HOLD_MS (5 минут).
 *
 * Дополнительно накапливает средние температуры, среднюю/макс скорость.
 */
class TripDetector {

    companion object {
        private const val TAG = "TripDetector"
        const val GEAR_P   = 1
        const val POWER_ON = 2
        const val MIN_SPEED = 1f
        const val STOP_HOLD_MS = 5 * 60_000L
    }

    data class TripState(
        val active: Boolean,
        val startedAt: Long = 0L,
        val endedAt: Long? = null,
        val distanceKm: Double = 0.0,
        val energyKwh: Double = 0.0,
        val consumptionPer100Km: Double = 0.0,
        val durationMin: Long = 0L,
        val avgSpeedKmh: Double = 0.0,
        val maxSpeedKmh: Double = 0.0,
        val outsideTempAvgC: Double? = null,
        val insideTempAvgC: Double? = null,
        val battTempAvgC: Double? = null,
        val startOdometerKm: Double? = null,
        val endOdometerKm: Double? = null,
        val startSoc: Int? = null,
        val endSoc: Int? = null
    )

    private var startTs = 0L
    private var startMileage = 0.0
    private var startLifetimeKwh = 0.0
    private var lastStopTs = 0L
    private var gearWasP = true

    private var sampleCount = 0
    private var speedSum = 0.0
    private var maxSpeed = 0.0
    private var outsideTempSum = 0.0
    private var outsideTempSamples = 0
    private var insideTempSum = 0.0
    private var insideTempSamples = 0
    private var battTempSum = 0.0
    private var battTempSamples = 0

    private var startSoc: Int? = null
    private var startOdometerKm: Double? = null

    private var lastSoc: Int? = null
    private var lastOdometerKm: Double? = null

    fun onSnapshot(
        ts: Long,
        gearMode: Int?,
        speedKmh: Float?,
        mileageKm: Float?,
        lifetimeKwh: Float?,
        powerLevel: Int?,
        socPercent: Float?,
        tempOutsideC: Int?,
        tempInsideC: Int?,
        battTempC: Int?
    ): TripState {
        val gear   = gearMode ?: GEAR_P
        val speed  = speedKmh ?: 0f
        val mileage = mileageKm?.toDouble() ?: 0.0
        val kwh    = lifetimeKwh?.toDouble() ?: 0.0

        socPercent?.let { lastSoc = it.toInt() }
        mileageKm?.let { lastOdometerKm = it.toDouble() }

        if (startTs == 0L) {
            val gearLeftP = gear != GEAR_P && gearWasP
            val engineOn  = powerLevel == POWER_ON
            if (gearLeftP || engineOn) {
                startTs = ts
                startMileage = mileage
                startLifetimeKwh = kwh
                startSoc = socPercent?.toInt()
                startOdometerKm = mileageKm?.toDouble()

                sampleCount = 0
                speedSum = 0.0
                maxSpeed = 0.0
                outsideTempSum = 0.0; outsideTempSamples = 0
                insideTempSum = 0.0; insideTempSamples = 0
                battTempSum = 0.0; battTempSamples = 0

                Log.i(TAG, "Trip started (gearLeftP=$gearLeftP engineOn=$engineOn) " +
                        "soc=$startSoc mileage=$startOdometerKm")
            }
        } else {
            val spd = speed.toDouble()
            if (spd > 0) {
                speedSum += spd
                if (spd > maxSpeed) maxSpeed = spd
                sampleCount++
            }
            tempOutsideC?.let { outsideTempSum += it.toDouble(); outsideTempSamples++ }
            tempInsideC?.let { insideTempSum += it.toDouble(); insideTempSamples++ }
            battTempC?.let { battTempSum += it.toDouble(); battTempSamples++ }

            val stoppedInP = gear == GEAR_P && speed < MIN_SPEED
            if (stoppedInP) {
                if (lastStopTs == 0L) lastStopTs = ts
                if (ts - lastStopTs >= STOP_HOLD_MS) {
                    val st = buildState(ts, mileage, kwh)
                    Log.i(TAG, "Trip ended: %.2f km, %.2f kWh, %d min".format(
                        st.distanceKm, st.energyKwh, st.durationMin
                    ))
                    startTs = 0L
                    lastStopTs = 0L
                    gearWasP = true
                    return st.copy(active = false, endedAt = ts)
                }
            } else {
                lastStopTs = 0L
            }
        }

        gearWasP = gear == GEAR_P
        return buildState(ts, mileage, kwh)
    }

    private fun buildState(ts: Long, mileage: Double, kwh: Double): TripState {
        if (startTs == 0L) return TripState(active = false)
        val distance = (mileage - startMileage).coerceAtLeast(0.0)
        val energy   = (kwh - startLifetimeKwh).coerceAtLeast(0.0)
        val consumption = if (distance > 0.5) energy / distance * 100.0 else 0.0
        val durationMin = (ts - startTs) / 60_000
        val avgSpeed = if (sampleCount > 0) speedSum / sampleCount else 0.0

        return TripState(
            active = true,
            startedAt = startTs,
            endedAt = null,
            distanceKm = distance,
            energyKwh = energy,
            consumptionPer100Km = consumption,
            durationMin = durationMin,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed,
            outsideTempAvgC = if (outsideTempSamples > 0)
                outsideTempSum / outsideTempSamples else null,
            insideTempAvgC = if (insideTempSamples > 0)
                insideTempSum / insideTempSamples else null,
            battTempAvgC = if (battTempSamples > 0)
                battTempSum / battTempSamples else null,
            startOdometerKm = startOdometerKm,
            endOdometerKm = mileage,
            startSoc = startSoc,
            endSoc = lastSoc
        )
    }
}