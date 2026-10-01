package com.ar.bydlauncher.byd

import android.util.Log

/**
 * Определяет начало и конец поездки.
 *
 * СТАРТ  (все три условия в одном снапшоте):
 *   • селектор КПП не в P;
 *   • ремень водителя пристёгнут;
 *   • педаль газа > ACCEL_START_PERCENT %.
 *
 * КОНЕЦ:
 *   • селектор КПП в P и ремень водителя отстёгнут → запускается таймер STOP_HOLD_MS (5 мин)
 *     от момента отстёгивания;
 *   • если за это время ремень снова пристегнули или селектор ушёл из P — таймер сбрасывается,
 *     поездка продолжается;
 *   • по истечении таймера поездка закрывается. В итоговые значения (пробег, кВт·ч, SOC,
 *     длительность, endedAt) попадает состояние на МОМЕНТ ОТСТЁГИВАНИЯ, а не +5 минут —
 *     простой с работающим климатом в расход поездки не входит.
 *
 * Значение null («неизвестно», например ремень не читается при выключенном зажигании)
 * таймер не сбрасывает и конец поездки не запускает.
 *
 * Пока идёт таймер, TripState.endPendingSince != null, а distance/energy/duration заморожены —
 * вызывающий код может сразу записать их в БД: если ГУ уснёт раньше 5 минут, поездка закроется
 * по этим данным как «осиротевшая».
 */
class TripDetector {

    companion object {
        private const val TAG = "TripDetector"
        const val GEAR_P = 1
        const val ACCEL_START_PERCENT = 5f
        const val STOP_HOLD_MS = 5 * 60_000L
    }

    data class TripState(
        val active: Boolean,
        val startedAt: Long = 0L,
        /** Заполняется только в состоянии, закрывающем поездку (active = false). */
        val endedAt: Long? = null,
        /** Момент отстёгивания ремня, если идёт 5-минутный таймер конца; иначе null. */
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
        val endSoc: Int? = null
    )

    // ── Состояние активной поездки (startTs == 0 → поездки нет) ──
    private var startTs = 0L
    private var startMileage = 0.0
    private var startKwh = 0.0
    private var startSoc: Int? = null

    // ── Таймер конца (pendingSince == 0 → не запущен) + замороженный снимок ──
    private var pendingSince = 0L
    private var pendMileage = 0.0
    private var pendKwh = 0.0
    private var pendSoc: Int? = null

    // ── Последние валидные значения (null в снапшоте не превращается в 0) ──
    private var lastMileage: Double? = null
    private var lastKwh: Double? = null
    private var lastSoc: Int? = null

    // ── Накопители статистики ──
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
        accelPercent: Float?
    ): TripState {
        mileageKm?.let { lastMileage = it.toDouble() }
        lifetimeKwh?.let { lastKwh = it.toDouble() }
        socPercent?.let { lastSoc = Math.round(it) }

        val inP = gearMode == GEAR_P
        val notP = gearMode != null && gearMode != GEAR_P

        // ── Поездки нет: ждём старта ─────────────────────────
        if (startTs == 0L) {
            val mileage = lastMileage
            val kwh = lastKwh
            val startCond = notP &&
                    driverBeltBuckled == true &&
                    (accelPercent ?: 0f) > ACCEL_START_PERCENT

            // Без валидных пробега и счётчика кВт·ч не стартуем — иначе база = 0
            // и первая нормальная выборка даст «поездку» в тысячи километров.
            if (startCond && mileage != null && kwh != null) {
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
                        "accel=$accelPercent soc=$startSoc odo=$startMileage")
            }
            return buildState(ts)
        }

        // ── Поездка идёт ─────────────────────────────────────
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
        // Явные признаки того, что поездка продолжается. null таймер не сбрасывает.
        val resumeCond = notP || driverBeltBuckled == true

        if (pendingSince == 0L) {
            if (endCond) {
                pendingSince = ts
                pendMileage = lastMileage ?: startMileage
                pendKwh = lastKwh ?: startKwh
                pendSoc = lastSoc
                Log.i(TAG, "End timer started (P + belt off): soc=$pendSoc odo=$pendMileage")
            }
        } else {
            if (resumeCond) {
                Log.i(TAG, "End timer cancelled: gear=$gearMode belt=$driverBeltBuckled")
                pendingSince = 0L
            } else if (ts - pendingSince >= STOP_HOLD_MS) {
                val closing = buildState(ts).copy(
                    active = false,
                    endedAt = pendingSince,
                    endPendingSince = null
                )
                Log.i(TAG, "Trip ended: %.2f km, %.2f kWh, %d min".format(
                    closing.distanceKm, closing.energyKwh, closing.durationMin
                ))
                reset()
                return closing
            }
        }

        return buildState(ts)
    }

    private fun reset() {
        startTs = 0L
        pendingSince = 0L
    }

    private fun buildState(ts: Long): TripState {
        if (startTs == 0L) return TripState(active = false)

        val frozen = pendingSince != 0L
        val endTs = if (frozen) pendingSince else ts
        val endMileage = if (frozen) pendMileage else (lastMileage ?: startMileage)
        val endKwh = if (frozen) pendKwh else (lastKwh ?: startKwh)
        val endSoc = if (frozen) pendSoc else lastSoc

        val distance = (endMileage - startMileage).coerceAtLeast(0.0)
        val energy = (endKwh - startKwh).coerceAtLeast(0.0)
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
            endSoc = endSoc
        )
    }
}
