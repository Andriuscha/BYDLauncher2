package com.ar.bydlauncher.byd

import android.util.Log

class TripDetector {

    companion object {
        private const val TAG = "TripDetector"
        const val GEAR_P = 1
        const val START_SPEED_KMH = 3f
        const val STOP_HOLD_MS = 5 * 60_000L
    }

    data class TripState(
        val active: Boolean,
        val startedAt: Long = 0L,
        val endedAt: Long? = null,
        val endPendingSince: Long? = null,
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
        val endSoc: Int? = null,
        val routeProfile: RouteProfile = RouteProfile.UNKNOWN   // ← НОВОЕ
    )

    private var startTs = 0L
    private var startMileage = 0.0
    private var startKwh: Double? = null
    private var startSoc: Int? = null

    private var pendingSince = 0L
    private var pendMileage = 0.0
    private var pendKwh: Double? = null
    private var pendSoc: Int? = null

    private var lastMileage: Double? = null
    private var lastKwh: Double? = null
    private var lastSoc: Int? = null

    @Volatile
    var lastDiag: String = ""
        private set

    private var sampleCount = 0
    private var speedSum = 0.0
    private var maxSpeed = 0.0
    private var outsideTempSum = 0.0
    private var outsideTempSamples = 0
    private var insideTempSum = 0.0
    private var insideTempSamples = 0
    private var battTempSum = 0.0
    private var battTempSamples = 0

    fun onSnapshot(
        ts: Long,
        gearMode: Int?,
        speedKmh: Float?,
        mileageKm: Float?,
        lifetimeKwh: Float?,
        socPercent: Float?,
        tempOutsideC: Int?,
        tempInsideC: Int?,
        battTempC: Int?,
        driverBeltBuckled: Boolean?,
        routeProfile: RouteProfile = RouteProfile.UNKNOWN   // ← НОВОЕ
    ): TripState {
        mileageKm?.let { lastMileage = it.toDouble() }
        lifetimeKwh?.let { lastKwh = it.toDouble() }
        socPercent?.let { lastSoc = Math.round(it) }

        val inP = gearMode == GEAR_P
        val notP = gearMode != null && gearMode != GEAR_P

        if (startTs == 0L) {
            val mileage = lastMileage
            val kwh = lastKwh
            val startCond = notP &&
                    driverBeltBuckled == true &&
                    (speedKmh ?: 0f) >= START_SPEED_KMH

            lastDiag = "старт: КПП≠P=%s ремень=%s скорость=%s(≥%.0f:%s) пробег=%s кВт·ч=%s".format(
                notP, driverBeltBuckled, speedKmh, START_SPEED_KMH,
                (speedKmh ?: 0f) >= START_SPEED_KMH, mileage != null, kwh != null)
            if (startCond && mileage != null) {
                startTs = ts
                startMileage = mileage
                startKwh = kwh
                startSoc = lastSoc
                pendingSince = 0L
                sampleCount = 0
                speedSum = 0.0
                maxSpeed = 0.0
                outsideTempSum = 0.0; outsideTempSamples = 0
                insideTempSum = 0.0; insideTempSamples = 0
                battTempSum = 0.0; battTempSamples = 0
                Log.i(TAG, "Trip started: gear=$gearMode belt=$driverBeltBuckled " +
                        "speed=$speedKmh soc=$startSoc odo=$startMileage")
            }
            return buildState(ts, routeProfile)
        }

        lastDiag = if (pendingSince == 0L) "идёт" else
            "идёт, таймер конца %d с".format((ts - pendingSince) / 1000)
        if (pendingSince == 0L) {
            val spd = (speedKmh ?: 0f).toDouble()
            if (spd > 0) {
                speedSum += spd
                if (spd > maxSpeed) maxSpeed = spd
                sampleCount++
            }
            tempOutsideC?.let { outsideTempSum += it.toDouble(); outsideTempSamples++ }
            tempInsideC?.let { insideTempSum += it.toDouble(); insideTempSamples++ }
            battTempC?.let { battTempSum += it.toDouble(); battTempSamples++ }
        }

        val endCond = inP && driverBeltBuckled == false
        val resumeCond = notP || driverBeltBuckled == true

        if (pendingSince == 0L) {
            if (endCond) {
                pendingSince = ts
                pendMileage = lastMileage ?: startMileage
                pendKwh = lastKwh
                pendSoc = lastSoc
                Log.i(TAG, "End timer started (P + belt off): soc=$pendSoc odo=$pendMileage")
            }
        } else {
            if (resumeCond) {
                Log.i(TAG, "End timer cancelled: gear=$gearMode belt=$driverBeltBuckled")
                pendingSince = 0L
            } else if (ts - pendingSince >= STOP_HOLD_MS) {
                val closing = buildState(ts, routeProfile).copy(
                    active = false,
                    endedAt = pendingSince,
                    endPendingSince = null
                )
                lastDiag = "завершена"
                Log.i(TAG, "Trip ended: %.2f km, %.2f kWh, %d min".format(
                    closing.distanceKm, closing.energyKwh, closing.durationMin
                ))
                reset()
                return closing
            }
        }

        return buildState(ts, routeProfile)
    }

    private fun reset() {
        startTs = 0L
        pendingSince = 0L
    }

    private fun buildState(ts: Long, routeProfile: RouteProfile): TripState {
        if (startTs == 0L) return TripState(active = false, routeProfile = routeProfile)

        val frozen = pendingSince != 0L
        val endTs = if (frozen) pendingSince else ts
        val endMileage = if (frozen) pendMileage else (lastMileage ?: startMileage)
        val endKwh = if (frozen) pendKwh else lastKwh
        val endSoc = if (frozen) pendSoc else lastSoc

        val distance = (endMileage - startMileage).coerceAtLeast(0.0)
        val sk = startKwh
        val energy = if (sk != null && endKwh != null) (endKwh - sk).coerceAtLeast(0.0) else 0.0
        val consumption = if (distance > 0.5) energy / distance * 100.0 else 0.0
        val durationMin = ((endTs - startTs) / 60_000).coerceAtLeast(0L)
        val avgSpeed = if (sampleCount > 0) speedSum / sampleCount else 0.0

        return TripState(
            active = true,
            startedAt = startTs,
            endedAt = null,
            endPendingSince = if (frozen) pendingSince else null,
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
            startOdometerKm = startMileage,
            endOdometerKm = endMileage,
            startSoc = startSoc,
            endSoc = endSoc,
            routeProfile = routeProfile
        )
    }
}