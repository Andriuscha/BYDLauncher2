package com.ar.bydlauncher.byd

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Модуль расчёта State of Health (SOH) аккумулятора.
 *
 * SOH = (реальная ёмкость / номинал) × 100%
 * Реальная ёмкость = добавленная энергия / ΔSOC × 100
 *
 * Энергия берётся из накопительного счётчика lifetimeKwh
 * (FID STATISTIC_TOTAL_ELEC_CONSUMPTION, 1032871984).
 */
class SohCalculator(
    private val storageFile: File,
    var nominalKwh: Double = 44.9
) {
    companion object {
        private const val TAG = "SohCalculator"

        const val MIN_DELTA_SOC = 20
        const val MIN_KWH_ADDED = 0.5
        const val MIN_TEMP_C = 15
        const val MAX_TEMP_C = 35
        const val SOH_MIN_VALID = 60.0
        const val SOH_MAX_VALID = 105.0
        const val MAX_MEASUREMENTS = 20
        const val BMS_CHARGING = 1
    }

    data class Measurement(
        val ts: Long,
        val socStart: Int,
        val socEnd: Int,
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

    private data class ActiveSession(
        val startTs: Long,
        val socStart: Int,
        val kwhStart: Double,
        var lastSoc: Int,
        var lastKwh: Double,
        var minTempC: Int?,
        var maxTempC: Int?
    ) {
        fun averageTemp(): Int? {
            val lo = minTempC ?: return maxTempC
            val hi = maxTempC ?: return minTempC
            return (lo + hi) / 2
        }
    }

    private var session: ActiveSession? = null
    private val measurements = mutableListOf<Measurement>()

    init {
        load()
    }

    fun onSnapshot(snap: BatterySnapshot, ts: Long = System.currentTimeMillis()) {
        val bmsState = snap.bmsState ?: return
        val soc = snap.socPercent?.toInt()
        val kwh = snap.lifetimeKwh?.toDouble()
        val tempC = snap.maxBatTempC

        val isCharging = bmsState == BMS_CHARGING

        // ── Старт зарядки ────────────────────────────
        if (isCharging && session == null) {
            if (soc == null || kwh == null) {
                Log.d(TAG, "Charge started but soc/kwh not yet available")
                return
            }
            session = ActiveSession(
                startTs = ts,
                socStart = soc,
                kwhStart = kwh,
                lastSoc = soc,
                lastKwh = kwh,
                minTempC = tempC,
                maxTempC = tempC
            )
            Log.i(TAG, "→ Charge started: soc=$soc, kwh=$kwh, temp=$tempC")
            return
        }

        // ── Идёт зарядка ────────────────────────────
        if (isCharging && session != null) {
            val s = session!!
            if (soc != null) s.lastSoc = soc
            if (kwh != null) s.lastKwh = kwh
            if (tempC != null) {
                s.minTempC = listOfNotNull(s.minTempC, tempC).min()
                s.maxTempC = listOfNotNull(s.maxTempC, tempC).max()
            }
            return
        }

        // ── Зарядка закончилась ─────────────────────
        if (!isCharging && session != null) {
            val s = session!!
            val socEnd = soc ?: s.lastSoc
            val kwhEnd = kwh ?: s.lastKwh
            val deltaKwh = kwhEnd - s.kwhStart
            val avgTemp = s.averageTemp()

            val m = finalize(
                startTs = s.startTs,
                socStart = s.socStart,
                socEnd = socEnd,
                kwhAdded = deltaKwh,
                batTempC = avgTemp
            )

            measurements.add(m)
            while (measurements.size > MAX_MEASUREMENTS) measurements.removeAt(0)
            session = null
            save()

            if (m.rejected) {
                Log.w(TAG, "✗ Session rejected: ${m.rejectReason}")
            } else {
                Log.i(TAG, "✓ Session complete: soc=${m.socStart}→${m.socEnd}%, " +
                        "kwh=${"%.2f".format(m.kwhAdded)}, " +
                        "usable=${"%.2f".format(m.usableCapacityKwh)} kWh, " +
                        "SOH=${"%.1f".format(m.sohPercent)}%")
            }
        }
    }

    private fun finalize(
        startTs: Long,
        socStart: Int,
        socEnd: Int,
        kwhAdded: Double,
        batTempC: Int?
    ): Measurement {
        val deltaSoc = socEnd - socStart

        fun reject(reason: String, usable: Double = 0.0, soh: Double = 0.0) =
            Measurement(
                ts = startTs, socStart = socStart, socEnd = socEnd,
                kwhAdded = kwhAdded, usableCapacityKwh = usable,
                sohPercent = soh, batTempC = batTempC,
                rejected = true, rejectReason = reason
            )

        if (deltaSoc < MIN_DELTA_SOC)
            return reject("ΔSOC = $deltaSoc% (< $MIN_DELTA_SOC%)")

        if (kwhAdded <= MIN_KWH_ADDED)
            return reject("ΔkWh = ${"%.2f".format(kwhAdded)} (≤ $MIN_KWH_ADDED)")

        if (batTempC != null && (batTempC < MIN_TEMP_C || batTempC > MAX_TEMP_C))
            return reject("Temp = $batTempC°C (out of $MIN_TEMP_C..$MAX_TEMP_C)")

        val usableKwh = kwhAdded * 100.0 / deltaSoc
        val soh = usableKwh / nominalKwh * 100.0

        if (soh < SOH_MIN_VALID || soh > SOH_MAX_VALID)
            return reject("SOH out of range: ${"%.1f".format(soh)}%", usableKwh, soh)

        return Measurement(
            ts = startTs, socStart = socStart, socEnd = socEnd,
            kwhAdded = kwhAdded, usableCapacityKwh = usableKwh,
            sohPercent = soh, batTempC = batTempC,
            rejected = false, rejectReason = null
        )
    }

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

    fun getValidMeasurements(): List<Measurement> = measurements.filter { !it.rejected }

    fun getAllMeasurements(): List<Measurement> = measurements.toList()

    fun isChargeInProgress(): Boolean = session != null

    fun clear() {
        measurements.clear()
        session = null
        save()
    }

    /** Обновить номинал ёмкости. Переименовано из setNominalKwh,
     *  чтобы не конфликтовать с автогенерируемым сеттером поля var nominalKwh. */
    fun updateNominalKwh(v: Double) {
        if (v > 0) {
            nominalKwh = v
            save()
        }
    }

    private fun save() {
        try {
            val root = JSONObject().apply {
                put("nominalKwh", nominalKwh)
                put("measurements", JSONArray().apply {
                    measurements.forEach { put(it.toJson()) }
                })
            }
            storageFile.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    private fun load() {
        if (!storageFile.exists()) return
        try {
            val root = JSONObject(storageFile.readText())
            nominalKwh = root.optDouble("nominalKwh", nominalKwh)
            val arr = root.optJSONArray("measurements") ?: return
            measurements.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                measurements.add(Measurement(
                    ts = o.getLong("ts"),
                    socStart = o.getInt("socStart"),
                    socEnd = o.getInt("socEnd"),
                    kwhAdded = o.getDouble("kwhAdded"),
                    usableCapacityKwh = o.getDouble("usableCapacityKwh"),
                    sohPercent = o.getDouble("sohPercent"),
                    batTempC = if (o.isNull("batTempC")) null else o.getInt("batTempC"),
                    rejected = o.optBoolean("rejected", false),
                    rejectReason = if (o.isNull("rejectReason")) null else o.getString("rejectReason")
                ))
            }
            Log.i(TAG, "Loaded ${measurements.size} measurements, nominal=$nominalKwh")
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }
}