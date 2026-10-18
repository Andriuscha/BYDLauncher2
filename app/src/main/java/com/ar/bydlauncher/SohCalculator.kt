package com.ar.bydlauncher.byd

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Расчёт State of Health (SOH) аккумулятора по зарядным сессиям.
 *
 * SOH = (реальная ёмкость / номинал) × 100%
 * Реальная ёмкость = добавленная энергия / ΔSOC × 100
 *
 * Энергия считается интегрированием мощности |U_hv × I_hv| по времени, пока
 * bmsState == BMS_CHARGING.
 *
 * Автоопределение AC/DC:
 *   если пиковая мощность за сессию > AC_DC_THRESHOLD_KW — DC, иначе AC.
 *
 * Если машину выключили во время зарядки и включили через какое-то время,
 * сессия восстанавливается из файла, и «дозаряд» после выключения оценивается
 * по приросту SOC с использованием номинала батареи. Такая энергия — оценка, а не
 * измерение (по ней SOH вышел бы 100% по построению), поэтому сессия помечается
 * estimated и в расчёт SOH не берётся (но в историю зарядок записывается).
 *
 * Что считается в SOH:
 *  • только AC-сессии: на быстрой зарядке заметная часть энергии через клеммы уходит
 *    в тепло внутри батареи, и ёмкость получается завышенной;
 *  • ΔSOC не меньше MIN_DELTA_SOC, батарея MIN_TEMP_C..MAX_TEMP_C, без больших пропусков.
 * DC-сессии и сессии с оценённой энергией сохраняются как отклонённые замеры
 * (с причиной) — они видны в деталях батареи и пишутся в историю зарядок.
 */
class SohCalculator(
    private val storageFile: File,
    var nominalKwh: Double = 44.9
) {
    companion object {
        private const val TAG = "SohCalculator"

        const val FORMAT_VERSION = 3

        const val MIN_DELTA_SOC = 30.0
        const val MIN_KWH_ADDED = 0.5
        const val MIN_TEMP_C = -10
        const val MAX_TEMP_C = 38
        const val SOH_MIN_VALID = 60.0
        const val SOH_MAX_VALID = 105.0
        const val MAX_MEASUREMENTS = 20
        const val BMS_CHARGING = 1

        const val MAX_SAMPLE_GAP_MS = 10_000L
        const val MAX_GAP_FRACTION = 0.05
        const val END_DEBOUNCE_MS = 30_000L
        const val SAVE_INTERVAL_MS = 60_000L
        const val RESUME_WINDOW_MS = 10 * 60_000L
        const val MAX_PLAUSIBLE_KW = 150.0

        /** Порог пиковой мощности: > 9 кВт → DC, ≤ 9 кВт → AC. */
        const val AC_DC_THRESHOLD_KW = 9.0

        interface ChargingListener {
            /**
             * Вызывается при завершении зарядной сессии.
             * @param acDc "AC" / "DC" — автоопределение по пиковой мощности
             */
            fun onChargingFinished(
                startTs: Long,
                endTs: Long,
                socStart: Double,
                socEnd: Double,
                energyKwh: Double,
                bmsStart: Double?,
                bmsEnd: Double?,
                cellTempStart: Double?,
                cellTempEnd: Double?,
                tempOutsideStart: Double?,
                tempOutsideEnd: Double?,
                odometerKm: Double?,
                acDc: String?,
                accepted: Boolean,
                rejectReason: String?
            )
        }
    }

    /** Слушатель, устанавливается извне (например, из MainActivity). */
    var listener: ChargingListener? = null

    data class Measurement(
        val ts: Long,
        val socStart: Double,
        val socEnd: Double,
        val kwhAdded: Double,
        val usableCapacityKwh: Double,
        val sohPercent: Double,
        val batTempC: Int?,
        val rejected: Boolean,
        val rejectReason: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", ts)
            put("socStart", socStart)
            put("socEnd", socEnd)
            put("kwhAdded", kwhAdded)
            put("usableCapacityKwh", usableCapacityKwh)
            put("sohPercent", sohPercent)
            put("batTempC", batTempC ?: JSONObject.NULL)
            put("rejected", rejected)
            put("rejectReason", rejectReason ?: JSONObject.NULL)
        }
    }

    private class ActiveSession(
        val startTs: Long,
        val socStart: Double,
        var lastSoc: Double,
        var lastTs: Long,
        var lastPowerKw: Double?,
        var energyKwh: Double = 0.0,
        var gapMs: Long = 0L,
        var minTempC: Int?,
        var maxTempC: Int?,
        var notChargingSince: Long = 0L,
        var lastSavedTs: Long = 0L,
        /** true — сессия восстановлена из файла после выключения машины. */
        var resumed: Boolean = false,

        /** true — часть энергии добавлена по номиналу (SOC × номинал), а не измерена. */
        var estimated: Boolean = false,

        // ── Данные для истории зарядок ──
        var bmsCounterStart: Double? = null,
        var bmsCounterEnd: Double? = null,
        var cellTempStart: Double? = null,
        var cellTempEnd: Double? = null,
        var tempOutsideStart: Double? = null,
        var tempOutsideEnd: Double? = null,
        var odometerKm: Double? = null,

        // ── Пиковая мощность для авто-AC/DC ──
        var peakPowerKw: Double = 0.0
    ) {
        fun averageTemp(): Int? {
            val lo = minTempC ?: return maxTempC
            val hi = maxTempC ?: return minTempC
            return (lo + hi) / 2
        }

        /** AC / DC по пиковой мощности. */
        fun acDc(): String =
            if (peakPowerKw > AC_DC_THRESHOLD_KW) "DC" else "AC"

        fun toJson(): JSONObject = JSONObject().apply {
            put("startTs", startTs)
            put("socStart", socStart)
            put("lastSoc", lastSoc)
            put("lastTs", lastTs)
            put("lastPowerKw", lastPowerKw ?: JSONObject.NULL)
            put("energyKwh", energyKwh)
            put("gapMs", gapMs)
            put("minTempC", minTempC ?: JSONObject.NULL)
            put("maxTempC", maxTempC ?: JSONObject.NULL)
            put("bmsCounterStart", bmsCounterStart ?: JSONObject.NULL)
            put("bmsCounterEnd", bmsCounterEnd ?: JSONObject.NULL)
            put("cellTempStart", cellTempStart ?: JSONObject.NULL)
            put("cellTempEnd", cellTempEnd ?: JSONObject.NULL)
            put("tempOutsideStart", tempOutsideStart ?: JSONObject.NULL)
            put("tempOutsideEnd", tempOutsideEnd ?: JSONObject.NULL)
            put("odometerKm", odometerKm ?: JSONObject.NULL)
            put("peakPowerKw", peakPowerKw)
            put("estimated", estimated)
        }
    }

    private var session: ActiveSession? = null
    private val measurements = mutableListOf<Measurement>()

    init {
        load()
    }

    // ══════════════════════════════════════════════════════
    // Основной вход: вызывается на каждый снапшот BMS
    // ══════════════════════════════════════════════════════

    @Synchronized
    fun onSnapshot(snap: BatterySnapshot, ts: Long = System.currentTimeMillis()) {
        val bmsState = snap.bmsState ?: return
        val isCharging = bmsState == BMS_CHARGING
        val soc = snap.socPercent?.toDouble()
        val powerKw = chargePowerKw(snap)
        val tempC = snap.maxBatTempC

        val s = session

        // ── Сессии нет ───────────────────────────────────
        if (s == null) {
            if (!isCharging) return
            if (soc == null) {
                Log.d(TAG, "Charge started but SOC not yet available")
                return
            }
            session = ActiveSession(
                startTs = ts,
                socStart = soc,
                lastSoc = soc,
                lastTs = ts,
                lastPowerKw = powerKw,
                minTempC = tempC,
                maxTempC = tempC,
                lastSavedTs = ts,
                bmsCounterStart = snap.lifetimeKwh?.toDouble(),
                cellTempStart = averageCellTemp(snap),
                tempOutsideStart = snap.tempOutsideC?.toDouble(),
                odometerKm = snap.lifetimeKm?.toDouble(),
                peakPowerKw = powerKw ?: 0.0
            )
            save()
            Log.i(TAG, "→ Charge started: soc=%.1f, temp=%s, power=%s kW, bms=%s, odo=%s"
                .format(
                    soc, tempC,
                    powerKw?.let { "%.1f".format(it) } ?: "—",
                    snap.lifetimeKwh?.let { "%.1f".format(it) } ?: "—",
                    snap.lifetimeKm?.let { "%.1f".format(it) } ?: "—"
                ))
            return
        }

        // ── Идёт зарядка ─────────────────────────────────
        if (isCharging) {
            s.notChargingSince = 0L

            // Первый снапшот после восстановления сессии
            if (s.resumed) {
                s.resumed = false
                if (soc != null && soc > s.lastSoc) {
                    val missingSoc = soc - s.lastSoc
                    val missingKwh = nominalKwh * missingSoc / 100.0
                    s.energyKwh += missingKwh
                    s.estimated = true
                    s.gapMs = 0L
                    Log.i(TAG,
                        "Resume (charging): дозаряд %.1f%% (%.2f кВт·ч), энергия → %.2f кВт·ч"
                            .format(missingSoc, missingKwh, s.energyKwh))
                }
            }

            accumulate(s, ts, powerKw)
            if (soc != null) s.lastSoc = soc
            if (tempC != null) {
                s.minTempC = minOf(s.minTempC ?: tempC, tempC)
                s.maxTempC = maxOf(s.maxTempC ?: tempC, tempC)
            }

            // Обновляем конечные значения
            s.bmsCounterEnd = snap.lifetimeKwh?.toDouble()
            s.cellTempEnd = averageCellTemp(snap)
            s.tempOutsideEnd = snap.tempOutsideC?.toDouble()
            if (snap.lifetimeKm != null) s.odometerKm = snap.lifetimeKm.toDouble()

            if (ts - s.lastSavedTs >= SAVE_INTERVAL_MS) {
                s.lastSavedTs = ts
                save()
            }
            return
        }

        // ── Не зарядка, но сессия открыта: ждём END_DEBOUNCE_MS ──
        if (s.notChargingSince == 0L) {
            s.notChargingSince = ts

            // Первый снапшот после восстановления — зарядка уже закончилась
            if (s.resumed) {
                s.resumed = false
                if (soc != null && soc > s.lastSoc) {
                    val missingSoc = soc - s.lastSoc
                    val missingKwh = nominalKwh * missingSoc / 100.0
                    s.energyKwh += missingKwh
                    s.estimated = true
                    s.lastSoc = soc
                    s.gapMs = 0L
                    Log.i(TAG,
                        "Resume (не зарядка): дозаряд %.1f%% (%.2f кВт·ч), энергия → %.2f кВт·ч, lastSoc=%.1f"
                            .format(missingSoc, missingKwh, s.energyKwh, soc))
                }
            }
            return
        }
        if (ts - s.notChargingSince >= END_DEBOUNCE_MS) {
            closeSession(s, wasResumed = s.resumed)
        }
    }

    /** |U×I| в кВт. null — если V или I недоступны либо значение неправдоподобно. */
    private fun chargePowerKw(snap: BatterySnapshot): Double? {
        val v = snap.hvVoltageV ?: return null
        val i = snap.hvCurrentA ?: return null
        val kw = Math.abs(v * i.toDouble()) / 1000.0
        return if (kw <= MAX_PLAUSIBLE_KW) kw else null
    }

    /** Средняя температура по ячейкам (между max и min). */
    private fun averageCellTemp(snap: BatterySnapshot): Double? {
        val max = snap.maxBatTempC ?: return null
        val min = snap.minBatTempC ?: return max.toDouble()
        return (max + min) / 2.0
    }

    /** Трапецеидальное интегрирование мощности между двумя снапшотами зарядки. */
    private fun accumulate(s: ActiveSession, ts: Long, powerKw: Double?) {
        val dt = ts - s.lastTs
        if (dt > MAX_SAMPLE_GAP_MS) {
            s.gapMs += dt
        } else if (dt > 0) {
            val prev = s.lastPowerKw
            val p: Double? = when {
                prev != null && powerKw != null -> (prev + powerKw) / 2.0
                else -> powerKw ?: prev
            }
            if (p != null) {
                s.energyKwh += p * dt / 3_600_000.0
            } else {
                s.gapMs += dt
            }
        }
        s.lastTs = ts
        s.lastPowerKw = powerKw

        // Обновляем пиковую мощность для авто-AC/DC
        if (powerKw != null && powerKw > s.peakPowerKw) {
            s.peakPowerKw = powerKw
        }
    }

    // ══════════════════════════════════════════════════════
    // Завершение сессии
    // ══════════════════════════════════════════════════════

    private fun closeSession(s: ActiveSession, wasResumed: Boolean = false) {
        val durationMs = (s.lastTs - s.startTs).coerceAtLeast(0L)

        val effectiveGapMs = if (wasResumed) 0L else s.gapMs

        val m = finalize(
            startTs = s.startTs,
            socStart = s.socStart,
            socEnd = s.lastSoc,
            kwhAdded = s.energyKwh,
            durationMs = durationMs,
            gapMs = effectiveGapMs,
            batTempC = s.averageTemp(),
            acDc = s.acDc(),
            estimated = s.estimated
        )

        measurements.add(m)
        while (measurements.size > MAX_MEASUREMENTS) measurements.removeAt(0)
        session = null
        save()

        val avgKw = if (durationMs > 0) s.energyKwh / (durationMs / 3_600_000.0) else 0.0
        val acDc = s.acDc()

        if (m.rejected) {
            Log.w(TAG, "✗ Session rejected (resumed=$wasResumed, acDc=$acDc, peak=${"%.1f".format(s.peakPowerKw)} kW): ${m.rejectReason}")
        } else {
            Log.i(TAG, "✓ Session complete (resumed=$wasResumed, acDc=$acDc, peak=${"%.1f".format(s.peakPowerKw)} kW): soc=%.1f→%.1f%%, kwh=%.2f (avg %.1f kW, %d min), usable=%.2f kWh, SOH=%.1f%%"
                .format(m.socStart, m.socEnd, m.kwhAdded, avgKw,
                    durationMs / 60_000, m.usableCapacityKwh, m.sohPercent))
        }

        // ── Уведомляем слушателя (ChargingRepository) ──
        listener?.onChargingFinished(
            startTs = s.startTs,
            endTs = s.lastTs,
            socStart = s.socStart,
            socEnd = s.lastSoc,
            energyKwh = s.energyKwh,
            bmsStart = s.bmsCounterStart,
            bmsEnd = s.bmsCounterEnd,
            cellTempStart = s.cellTempStart,
            cellTempEnd = s.cellTempEnd,
            tempOutsideStart = s.tempOutsideStart,
            tempOutsideEnd = s.tempOutsideEnd,
            odometerKm = s.odometerKm,
            acDc = acDc,
            accepted = !m.rejected,
            rejectReason = m.rejectReason
        )
    }

    private fun finalize(
        startTs: Long,
        socStart: Double,
        socEnd: Double,
        kwhAdded: Double,
        durationMs: Long,
        gapMs: Long,
        batTempC: Int?,
        acDc: String,
        estimated: Boolean
    ): Measurement {
        val deltaSoc = socEnd - socStart

        fun reject(reason: String, usable: Double = 0.0, soh: Double = 0.0) =
            Measurement(
                ts = startTs, socStart = socStart, socEnd = socEnd,
                kwhAdded = kwhAdded, usableCapacityKwh = usable,
                sohPercent = soh, batTempC = batTempC,
                rejected = true, rejectReason = reason
            )

        // Оценённая энергия: SOH = 100% по построению, в расчёт не берём.
        if (estimated)
            return reject("Энергия частично оценена по номиналу (опрос BMS не шёл во время зарядки)")

        // DC: потери внутри батареи искажают ёмкость. Для справки считаем, но не берём в SOH.
        if (acDc != "AC") {
            val u = if (deltaSoc > 0 && kwhAdded > 0) kwhAdded * 100.0 / deltaSoc else 0.0
            return reject(
                "DC-сессия: в расчёт SOH не берётся (потери в батарее на быстрой зарядке)",
                u, u / nominalKwh * 100.0
            )
        }

        if (deltaSoc < MIN_DELTA_SOC)
            return reject("ΔSOC = %.1f%% (< %.0f%%)".format(deltaSoc, MIN_DELTA_SOC))

        if (kwhAdded <= MIN_KWH_ADDED)
            return reject("ΔkWh = %.2f (≤ %.1f)".format(kwhAdded, MIN_KWH_ADDED))

        if (durationMs > 0 && gapMs.toDouble() / durationMs > MAX_GAP_FRACTION)
            return reject("Пропуски данных: %d с из %d с".format(gapMs / 1000, durationMs / 1000))

        if (batTempC != null && (batTempC < MIN_TEMP_C || batTempC > MAX_TEMP_C))
            return reject("Temp = $batTempC°C (out of $MIN_TEMP_C..$MAX_TEMP_C)")

        val usableKwh = kwhAdded * 100.0 / deltaSoc
        val soh = usableKwh / nominalKwh * 100.0

        if (soh < SOH_MIN_VALID || soh > SOH_MAX_VALID)
            return reject("SOH out of range: %.1f%%".format(soh), usableKwh, soh)

        return Measurement(
            ts = startTs, socStart = socStart, socEnd = socEnd,
            kwhAdded = kwhAdded, usableCapacityKwh = usableKwh,
            sohPercent = soh, batTempC = batTempC,
            rejected = false, rejectReason = null
        )
    }

    // ══════════════════════════════════════════════════════
    // Публичное API
    // ══════════════════════════════════════════════════════

    @Synchronized
    fun getCurrentSoh(): Double? {
        val valid = measurements.filter { !it.rejected }
        if (valid.isEmpty()) return null

        var sumW = 0.0
        var sumWSoh = 0.0
        for (m in valid) {
            val deltaSoc = m.socEnd - m.socStart
            val tempW = m.batTempC?.let {
                val dist = Math.abs(it - 25)
                1.0 / (1.0 + dist / 10.0)
            } ?: 0.5
            val w = deltaSoc * tempW
            sumW += w
            sumWSoh += m.sohPercent * w
        }
        return if (sumW > 0) sumWSoh / sumW else null
    }

    @Synchronized
    fun getValidMeasurements(): List<Measurement> = measurements.filter { !it.rejected }

    @Synchronized
    fun getAllMeasurements(): List<Measurement> = measurements.toList()

    @Synchronized
    fun isChargeInProgress(): Boolean = session != null

    @Synchronized
    fun clear() {
        measurements.clear()
        session = null
        save()
    }

    @Synchronized
    fun updateNominalKwh(v: Double) {
        if (v > 0) {
            nominalKwh = v
            save()
        }
    }

    // ══════════════════════════════════════════════════════
    // Хранение
    // ══════════════════════════════════════════════════════

    private fun save() {
        try {
            val root = JSONObject().apply {
                put("version", FORMAT_VERSION)
                put("nominalKwh", nominalKwh)
                put("measurements", JSONArray().apply {
                    measurements.forEach { put(it.toJson()) }
                })
                session?.let { put("session", it.toJson()) }
            }
            val tmp = File(storageFile.parentFile, storageFile.name + ".tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(storageFile)) {
                storageFile.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    private fun load() {
        if (!storageFile.exists()) return
        try {
            val root = JSONObject(storageFile.readText())
            nominalKwh = root.optDouble("nominalKwh", nominalKwh)

            if (root.optInt("version", 1) < FORMAT_VERSION) {
                try {
                    storageFile.copyTo(
                        File(storageFile.parentFile, storageFile.name + ".v1.bak"),
                        overwrite = true
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "v1 backup failed", e)
                }
                Log.i(TAG, "Старый формат (v1): замеры сброшены, номинал=$nominalKwh сохранён")
                save()
                return
            }

            measurements.clear()
            val arr = root.optJSONArray("measurements")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    measurements.add(Measurement(
                        ts = o.getLong("ts"),
                        socStart = o.getDouble("socStart"),
                        socEnd = o.getDouble("socEnd"),
                        kwhAdded = o.getDouble("kwhAdded"),
                        usableCapacityKwh = o.getDouble("usableCapacityKwh"),
                        sohPercent = o.getDouble("sohPercent"),
                        batTempC = if (o.isNull("batTempC")) null else o.getInt("batTempC"),
                        rejected = o.optBoolean("rejected", false),
                        rejectReason = if (o.isNull("rejectReason")) null else o.getString("rejectReason")
                    ))
                }
            }

            val so = root.optJSONObject("session")
            if (so != null) {
                fun getDoubleOrNull(key: String): Double? =
                    if (so.isNull(key)) null else so.optDouble(key, Double.NaN).takeIf { !it.isNaN() }

                val restored = ActiveSession(
                    startTs = so.getLong("startTs"),
                    socStart = so.getDouble("socStart"),
                    lastSoc = so.getDouble("lastSoc"),
                    lastTs = so.getLong("lastTs"),
                    lastPowerKw = getDoubleOrNull("lastPowerKw"),
                    energyKwh = so.getDouble("energyKwh"),
                    gapMs = so.getLong("gapMs"),
                    minTempC = if (so.isNull("minTempC")) null else so.getInt("minTempC"),
                    maxTempC = if (so.isNull("maxTempC")) null else so.getInt("maxTempC"),
                    lastSavedTs = System.currentTimeMillis(),
                    resumed = true,
                    bmsCounterStart = getDoubleOrNull("bmsCounterStart"),
                    bmsCounterEnd = getDoubleOrNull("bmsCounterEnd"),
                    cellTempStart = getDoubleOrNull("cellTempStart"),
                    cellTempEnd = getDoubleOrNull("cellTempEnd"),
                    tempOutsideStart = getDoubleOrNull("tempOutsideStart"),
                    tempOutsideEnd = getDoubleOrNull("tempOutsideEnd"),
                    odometerKm = getDoubleOrNull("odometerKm"),
                    peakPowerKw = so.optDouble("peakPowerKw", 0.0),
                    estimated = so.optBoolean("estimated", false)
                )

                val elapsed = System.currentTimeMillis() - restored.lastTs

                session = restored

                Log.i(TAG,
                    "Сессия восстановлена (resumed): socStart=%.1f, lastSoc=%.1f, kwh=%.2f, простой %d мин"
                        .format(restored.socStart, restored.lastSoc, restored.energyKwh, elapsed / 60_000))
            }
            Log.i(TAG, "Loaded ${measurements.size} measurements, nominal=$nominalKwh")
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }
}