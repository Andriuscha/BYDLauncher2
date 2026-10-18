package com.ar.bydlauncher.db

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.util.Log
import com.ar.bydlauncher.byd.RouteProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import com.ar.bydlauncher.byd.RangeMath

/**
 * Хранит эмпирику "км на 1% SOC" для эмпирической модели прогноза пробега.
 *
 * Соглашения:
 *  • kmPerPct     — сырой км/% поездки (при её температуре);
 *  • kmPerPctNorm — км/%, приведённый к эталону 20°C: kmPerPct * tempK(t поездки).
 *    tempK — множитель РАСХОДА (>1 в холод), поэтому в холод км/% при нормализации
 *    растёт, а при применении (RangeEstimator) делится на tempK(текущая t);
 *  • усреднение — по ΔSOC (пул Σкм/ΣΔSOC): целочисленный SOC шумит,
 *    и пул усредняет округление; вес поездки = ΔSOC * exp(-возраст/τ);
 *  • поездки, начатые с SOC >= TOP_SOC_EXCLUDE, не используются: у верхней границы
 *    SOC после зарядки дельта занижена, км/% завышен.
 */
class KmPerPctRepository(context: Context) {

    private val db = TripDatabase.get(context)

    /** Стартовый SOC поездки из сохранённых полей: soc_avg + soc_delta / 2. */
    private val START_SOC_SQL =
        "(${TripDatabase.KP_SOC_AVG} + ${TripDatabase.KP_SOC_DELTA} / 2.0)"

    companion object {
        private const val TAG = "KmPerPctRepository"

        const val TAU_MS = 5L * 24 * 3600_000L       // 5 дней — для весов
        const val WINDOW_MS = 15L * 24 * 3600_000L   // окно выборки
        const val MIN_KM = 4.0                       // мин. длина поездки
        const val MIN_SOC_DELTA = 1                   // мин. дельта SOC
        const val TOP_SOC_EXCLUDE = 98                // старт с SOC >= этого значения — не в выборку
        const val MAX_SAMPLES = 30
        const val REF_TEMP_C = 20.0                   // эталон температуры
    }

    data class Sample(
        val tripId: Long,
        val endedAt: Long,
        val distanceKm: Double,
        val kmPerPct: Double,
        val kmPerPctNorm: Double,
        val tempC: Double,
        val speedKmh: Double,
        val profile: RouteProfile,
        val socAvg: Double,
        val socDelta: Int
    )

    /**
     * Записать сэмпл по завершённой поездке.
     * Вызывается из TripRepository.finish() или сразу после.
     */
    suspend fun recordTrip(trip: TripRecord) = withContext(Dispatchers.IO) {
        try {
            val startSoc = trip.startSoc ?: return@withContext
            val endSoc   = trip.endSoc   ?: return@withContext
            val socDelta = startSoc - endSoc

            if (startSoc >= TOP_SOC_EXCLUDE) return@withContext
            if (socDelta < MIN_SOC_DELTA) return@withContext
            if (trip.distanceKm < MIN_KM)  return@withContext
            val avgSpeed = trip.avgSpeedKmh ?: return@withContext
            if (avgSpeed < 15.0) return@withContext
            if (trip.consumption !in 5.0..30.0) return@withContext
            if (!trip.finished) return@withContext

            val endedAt = trip.endedAt ?: trip.startedAt
            val tempC = trip.outsideTempAvgC ?: return@withContext

            val kmPerPct = trip.distanceKm / socDelta
            val tempK = RangeMath.tempCoefficient(tempC.toInt())
            val kmPerPctNorm = kmPerPct * tempK   // приведение к 20°C

            val cv = ContentValues().apply {
                put(TripDatabase.KP_TRIP_ID, trip.id)
                put(TripDatabase.KP_ENDED_AT, endedAt)
                put(TripDatabase.KP_DISTANCE_KM, trip.distanceKm)
                put(TripDatabase.KP_KM_PER_PCT, kmPerPct)
                put(TripDatabase.KP_KM_PER_PCT_NORM, kmPerPctNorm)
                put(TripDatabase.KP_TEMP_C, tempC)
                put(TripDatabase.KP_SPEED_KMH, avgSpeed)
                put(TripDatabase.KP_PROFILE, (trip.routeProfile ?: RouteProfile.UNKNOWN).name)
                put(TripDatabase.KP_SOC_AVG, (startSoc + endSoc) / 2.0)
                put(TripDatabase.KP_SOC_DELTA, socDelta)
            }
            val id = db.writableDatabase.insert(TripDatabase.TABLE_KMPCT, null, cv)
            Log.i(TAG, "recordTrip: tripId=${trip.id}, km/%=${"%.2f".format(kmPerPct)}, id=$id")
        } catch (t: Throwable) {
            Log.e(TAG, "recordTrip failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    /**
     * Дозаписывает сэмплы по завершённым поездкам, которых ещё нет в km_per_pct.
     * Идемпотентно: для поездки, у которой сэмпл уже есть, второй не создаётся.
     * Берутся только поездки в пределах WINDOW_MS: более старые в расчёте не участвуют.
     * Пороги (км, дельта SOC, расход, скорость, стартовый SOC) применяет recordTrip().
     * Возвращает число добавленных сэмплов.
     */
    suspend fun backfillFromTrips(tripRepo: TripRepository): Int = withContext(Dispatchers.IO) {
        try {
            val from = System.currentTimeMillis() - WINDOW_MS

            val existing = HashSet<Long>()
            db.readableDatabase.query(
                TripDatabase.TABLE_KMPCT, arrayOf(TripDatabase.KP_TRIP_ID),
                null, null, null, null, null
            ).use { c ->
                while (c.moveToNext()) existing.add(c.getLong(0))
            }

            val before = DatabaseUtils.queryNumEntries(db.readableDatabase, TripDatabase.TABLE_KMPCT)

            tripRepo.getAllFinished()
                .filter { (it.endedAt ?: it.startedAt) >= from && it.id !in existing }
                .sortedBy { it.endedAt ?: it.startedAt }
                .forEach { recordTrip(it) }

            val after = DatabaseUtils.queryNumEntries(db.readableDatabase, TripDatabase.TABLE_KMPCT)
            val added = (after - before).toInt()
            Log.i(TAG, "backfill: +$added samples")
            added
        } catch (t: Throwable) {
            Log.e(TAG, "backfill failed: ${t.javaClass.simpleName}: ${t.message}", t)
            0
        }
    }

    /**
     * Нормализованный км/% (эталон при 20°C).
     * Приоритет: свежие + профиль совпадает. Если по профилю пусто — общий.
     */
    suspend fun getNormalized(profile: RouteProfile): Double? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val from = now - WINDOW_MS

        val byProfile = querySamples(profile.name, from)
        val samples = if (byProfile.isNotEmpty()) byProfile else querySamples(null, from)
        if (samples.isEmpty()) return@withContext null

        weightedAvgNorm(samples, now)
    }

    /**
     * Сырой км/% (без нормализации) — для UI.
     */
    suspend fun getSmoothedKmPerPct(): Double? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val samples = querySamples(null, now - WINDOW_MS)
        if (samples.isEmpty()) return@withContext null
        weightedAvgRaw(samples, now)
    }

    /**
     * Средний км/% за окно [fromMs, toMs] — для тренда.
     */
    suspend fun getAvgForWindow(fromMs: Long, toMs: Long): Double? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val samples = querySamplesInRange(fromMs, toMs)
        if (samples.isEmpty()) return@withContext null
        weightedAvgRaw(samples, now)
    }

    private fun querySamples(profileName: String?, fromMs: Long): List<Sample> {
        val list = mutableListOf<Sample>()
        val selection = buildString {
            append("${TripDatabase.KP_ENDED_AT} >= ?")
            append(" AND $START_SOC_SQL < $TOP_SOC_EXCLUDE")
            if (profileName != null) append(" AND ${TripDatabase.KP_PROFILE} = ?")
        }
        val args = buildList {
            add(fromMs.toString())
            if (profileName != null) add(profileName)
        }.toTypedArray()

        db.readableDatabase.query(
            TripDatabase.TABLE_KMPCT, null, selection, args,
            null, null, "${TripDatabase.KP_ENDED_AT} DESC",
            MAX_SAMPLES.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(cursorToSample(c))
        }
        return list
    }

    private fun querySamplesInRange(fromMs: Long, toMs: Long): List<Sample> {
        val list = mutableListOf<Sample>()
        db.readableDatabase.query(
            TripDatabase.TABLE_KMPCT, null,
            "${TripDatabase.KP_ENDED_AT} >= ? AND ${TripDatabase.KP_ENDED_AT} < ? " +
                    "AND $START_SOC_SQL < $TOP_SOC_EXCLUDE",
            arrayOf(fromMs.toString(), toMs.toString()),
            null, null, "${TripDatabase.KP_ENDED_AT} DESC"
        ).use { c ->
            while (c.moveToNext()) list.add(cursorToSample(c))
        }
        return list
    }

    private fun cursorToSample(c: android.database.Cursor): Sample {
        val kmPerPct = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.KP_KM_PER_PCT))
        val tempC = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.KP_TEMP_C))
        return Sample(
            tripId = c.getLong(c.getColumnIndexOrThrow(TripDatabase.KP_TRIP_ID)),
            endedAt = c.getLong(c.getColumnIndexOrThrow(TripDatabase.KP_ENDED_AT)),
            distanceKm = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.KP_DISTANCE_KM)),
            kmPerPct = kmPerPct,
            // Нормализацию считаем при чтении из kmPerPct и температуры, а не берём
            // сохранённое поле: в старых записях оно посчитано с обратной поправкой.
            kmPerPctNorm = kmPerPct * RangeMath.tempCoefficient(tempC.toInt()),
            tempC = tempC,
            speedKmh = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.KP_SPEED_KMH)),
            profile = runCatching {
                RouteProfile.valueOf(c.getString(c.getColumnIndexOrThrow(TripDatabase.KP_PROFILE)))
            }.getOrDefault(RouteProfile.UNKNOWN),
            socAvg = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.KP_SOC_AVG)),
            socDelta = c.getInt(c.getColumnIndexOrThrow(TripDatabase.KP_SOC_DELTA))
        )
    }

    private fun weightedAvgNorm(samples: List<Sample>, now: Long): Double {
        val w = samples.map {
            val age = now - it.endedAt
            val weight = it.socDelta * exp(-age.toDouble() / TAU_MS)
            weight to it.kmPerPctNorm
        }
        val sumW = w.sumOf { it.first }
        return if (sumW > 0) w.sumOf { it.first * it.second } / sumW else 0.0
    }

    private fun weightedAvgRaw(samples: List<Sample>, now: Long): Double {
        val w = samples.map {
            val age = now - it.endedAt
            val weight = it.socDelta * exp(-age.toDouble() / TAU_MS)
            weight to it.kmPerPct
        }
        val sumW = w.sumOf { it.first }
        return if (sumW > 0) w.sumOf { it.first * it.second } / sumW else 0.0
    }

    suspend fun purgeOld() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 60L * 24 * 3600_000L
        db.writableDatabase.delete(
            TripDatabase.TABLE_KMPCT,
            "${TripDatabase.KP_ENDED_AT} < ?",
            arrayOf(cutoff.toString())
        )
    }
}