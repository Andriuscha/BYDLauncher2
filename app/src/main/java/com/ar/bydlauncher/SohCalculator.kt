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
 * bmsState == BMS_CHARGING. Счётчик STATISTIC_TOTAL_ELEC_CONSUMPTION для этого
 * не годится: он копит расход при движении и при зарядке почти не растёт.
 *
 * Отличия от первой версии:
 *  - SOC хранится как Double (раньше усекался до Int → до ~10% ошибки при ΔSOC=20);
 *  - незавершённая сессия сохраняется на диск и переживает перезапуск процесса;
 *  - конец зарядки с задержкой END_DEBOUNCE_MS (BMS-state иногда мигает);
 *  - пропуски в данных (ADB отвалился, V/I = null) учитываются, и сессия с
 *    большими пропусками отбраковывается — иначе энергия занижена, а SOH тоже;
 *  - запись файла атомарная (tmp + rename): питание в машине может пропасть
 *    в любой момент.
 */
class SohCalculator(
    private val storageFile: File,
    var nominalKwh: Double = 44.9
) {
    companion object {
        private const val TAG = "SohCalculator"

        const val FORMAT_VERSION = 2

        const val MIN_DELTA_SOC = 20.0
        const val MIN_KWH_ADDED = 0.5
        const val MIN_TEMP_C = 15
        const val MAX_TEMP_C = 35
        const val SOH_MIN_VALID = 60.0
        const val SOH_MAX_VALID = 105.0
        const val MAX_MEASUREMENTS = 20
        const val BMS_CHARGING = 1

        /** Интервал между снапшотами длиннее этого считается пропуском. */
        const val MAX_SAMPLE_GAP_MS = 10_000L
        /** Если пропуски > 5% длительности зарядки — замер ненадёжен. */
        const val MAX_GAP_FRACTION = 0.05
        /** Сколько секунд bmsState должен быть «не зарядка», чтобы закрыть сессию. */
        const val END_DEBOUNCE_MS = 30_000L
        /** Как часто сбрасывать активную сессию на диск. */
        const val SAVE_INTERVAL_MS = 60_000L
        /** Сессия старше этого при загрузке закрывается сразу, а не продолжается. */
        const val RESUME_WINDOW_MS = 10 * 60_000L
        /** Всё, что выше, считаем мусором из HAL (Dolphin на DC максимум ~90 кВт). */
        const val MAX_PLAUSIBLE_KW = 150.0
    }

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
        /** Время последнего снапшота В СОСТОЯНИИ ЗАРЯДКИ. */
        var lastTs: Long,
        var lastPowerKw: Double?,
        var energyKwh: Double = 0.0,
        var gapMs: Long = 0L,
        var minTempC: Int?,
        var maxTempC: Int?,
        /** 0 — зарядка идёт; иначе момент, когда bmsState перестал быть «зарядка». */
        var notChargingSince: Long = 0L,
        var lastSavedTs: Long = 0L
    ) {
        fun averageTemp(): Int? {
            val lo = minTempC ?: return maxTempC
            val hi = maxTempC ?: return minTempC
            return (lo + hi) / 2
        }

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
                lastSavedTs = ts
            )
            save()
            Log.i(TAG, "→ Charge started: soc=%.1f, temp=%s, power=%s kW"
                .format(soc, tempC, powerKw?.let { "%.1f".format(it) } ?: "—"))
            return
        }

        // ── Идёт зарядка ─────────────────────────────────
        if (isCharging) {
            s.notChargingSince = 0L
            accumulate(s, ts, powerKw)
            if (soc != null) s.lastSoc = soc
            if (tempC != null) {
                s.minTempC = minOf(s.minTempC ?: tempC, tempC)
                s.maxTempC = maxOf(s.maxTempC ?: tempC, tempC)
            }
            if (ts - s.lastSavedTs >= SAVE_INTERVAL_MS) {
                s.lastSavedTs = ts
                save()
            }
            return
        }

        // ── Не зарядка, но сессия открыта: ждём END_DEBOUNCE_MS ──
        if (s.notChargingSince == 0L) {
            s.notChargingSince = ts
            return
        }
        if (ts - s.notChargingSince >= END_DEBOUNCE_MS) {
            closeSession(s)
        }
    }

    /** |U×I| в кВт. null — если V или I недоступны либо значение неправдоподобно. */
    private fun chargePowerKw(snap: BatterySnapshot): Double? {
        val v = snap.hvVoltageV ?: return null
        val i = snap.hvCurrentA ?: return null
        val kw = Math.abs(v * i.toDouble()) / 1000.0
        return if (kw <= MAX_PLAUSIBLE_KW) kw else null
    }

    /** Трапецеидальное интегрирование мощности между двумя снапшотами зарядки. */
    private fun accumulate(s: ActiveSession, ts: Long, powerKw: Double?) {
        val dt = ts - s.lastTs
        if (dt > MAX_SAMPLE_GAP_MS) {
            // Большая дыра (в том числе перезапуск процесса / мигание BMS-state)
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
    }

    // ══════════════════════════════════════════════════════
    // Завершение сессии
    // ══════════════════════════════════════════════════════

    private fun closeSession(s: ActiveSession) {
        val durationMs = (s.lastTs - s.startTs).coerceAtLeast(0L)

        val m = finalize(
            startTs = s.startTs,
            socStart = s.socStart,
            socEnd = s.lastSoc,
            kwhAdded = s.energyKwh,
            durationMs = durationMs,
            gapMs = s.gapMs,
            batTempC = s.averageTemp()
        )

        measurements.add(m)
        while (measurements.size > MAX_MEASUREMENTS) measurements.removeAt(0)
        session = null
        save()

        val avgKw = if (durationMs > 0) s.energyKwh / (durationMs / 3_600_000.0) else 0.0
        if (m.rejected) {
            Log.w(TAG, "✗ Session rejected: ${m.rejectReason}")
        } else {
            Log.i(TAG, "✓ Session complete: soc=%.1f→%.1f%%, kwh=%.2f (avg %.1f kW, %d min), usable=%.2f kWh, SOH=%.1f%%"
                .format(m.socStart, m.socEnd, m.kwhAdded, avgKw,
                    durationMs / 60_000, m.usableCapacityKwh, m.sohPercent))
        }
    }

    private fun finalize(
        startTs: Long,
        socStart: Double,
        socEnd: Double,
        kwhAdded: Double,
        durationMs: Long,
        gapMs: Long,
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

    /** Обновить номинал ёмкости. Переименовано из setNominalKwh,
     *  чтобы не конфликтовать с автогенерируемым сеттером поля var nominalKwh. */
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
            // Атомарная запись: при потере питания остаётся либо старый, либо новый файл.
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

            // v1 считал энергию по счётчику расхода — эти замеры несопоставимы с новыми.
            if (root.optInt("version", 1) < FORMAT_VERSION) {
                try {
                    storageFile.copyTo(File(storageFile.parentFile, storageFile.name + ".v1.bak"), overwrite = true)
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
                val restored = ActiveSession(
                    startTs = so.getLong("startTs"),
                    socStart = so.getDouble("socStart"),
                    lastSoc = so.getDouble("lastSoc"),
                    lastTs = so.getLong("lastTs"),
                    lastPowerKw = if (so.isNull("lastPowerKw")) null else so.getDouble("lastPowerKw"),
                    energyKwh = so.getDouble("energyKwh"),
                    gapMs = so.getLong("gapMs"),
                    minTempC = if (so.isNull("minTempC")) null else so.getInt("minTempC"),
                    maxTempC = if (so.isNull("maxTempC")) null else so.getInt("maxTempC"),
                    lastSavedTs = System.currentTimeMillis()
                )
                // Сессия давно оборвалась — закрываем её как есть (энергия и ΔSOC
                // согласованы по lastTs), а не клеим к новой зарядке через дыру.
                if (System.currentTimeMillis() - restored.lastTs > RESUME_WINDOW_MS) {
                    Log.i(TAG, "Найдена старая незакрытая сессия — закрываю")
                    closeSession(restored)
                } else {
                    session = restored
                    Log.i(TAG, "Сессия зарядки восстановлена: %.1f→%.1f%%, %.2f kWh"
                        .format(restored.socStart, restored.lastSoc, restored.energyKwh))
                }
            }
            Log.i(TAG, "Loaded ${measurements.size} measurements, nominal=$nominalKwh")
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }
}
