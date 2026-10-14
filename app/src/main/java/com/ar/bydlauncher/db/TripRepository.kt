package com.ar.bydlauncher.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import com.ar.bydlauncher.byd.RouteProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

data class TripRecord(
    val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val finished: Boolean = false,
    val startSoc: Int? = null,
    val endSoc: Int? = null,
    val startOdometerKm: Double? = null,
    val endOdometerKm: Double? = null,
    val distanceKm: Double = 0.0,
    val durationMin: Long = 0L,
    val avgSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    val outsideTempAvgC: Double? = null,
    val insideTempAvgC: Double? = null,
    val battTempAvgC: Double? = null,
    val energyKwh: Double = 0.0,
    val consumption: Double = 0.0,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val routeProfile: RouteProfile? = null
)

class TripRepository(context: Context) {

    private val db = TripDatabase.get(context)

    companion object {
        private const val TAG = "TripRepository"
    }

    suspend fun insertStart(record: TripRecord): Long = withContext(Dispatchers.IO) {
        try {
            val cv = ContentValues().apply {
                put(TripDatabase.COL_STARTED_AT, record.startedAt)
                put(TripDatabase.COL_FINISHED, if (record.finished) 1 else 0)
                record.startSoc?.let { put(TripDatabase.COL_START_SOC, it) }
                record.startOdometerKm?.let { put(TripDatabase.COL_START_ODO, it) }
                record.startLat?.let { put(TripDatabase.COL_START_LAT, it) }
                record.startLon?.let { put(TripDatabase.COL_START_LON, it) }
            }
            val id = db.writableDatabase.insert(TripDatabase.TABLE, null, cv)
            Log.i(TAG, "insertStart: id=$id, startedAt=${record.startedAt}")
            id
        } catch (t: Throwable) {
            Log.e(TAG, "insertStart failed: ${t.javaClass.simpleName}: ${t.message}", t)
            -1L
        }
    }

    suspend fun updateLive(id: Long, record: TripRecord) = withContext(Dispatchers.IO) {
        try {
            val cv = ContentValues().apply {
                put(TripDatabase.COL_DISTANCE, record.distanceKm)
                put(TripDatabase.COL_DURATION, record.durationMin)
                put(TripDatabase.COL_ENERGY, record.energyKwh)
                put(TripDatabase.COL_CONSUMPTION, record.consumption)
                record.avgSpeedKmh?.let { put(TripDatabase.COL_AVG_SPEED, it) }
                record.maxSpeedKmh?.let { put(TripDatabase.COL_MAX_SPEED, it) }
                record.outsideTempAvgC?.let { put(TripDatabase.COL_OUTSIDE_TEMP, it) }
                record.insideTempAvgC?.let { put(TripDatabase.COL_INSIDE_TEMP, it) }
                record.battTempAvgC?.let { put(TripDatabase.COL_BATT_TEMP, it) }
                record.endLat?.let { put(TripDatabase.COL_END_LAT, it) }
                record.endLon?.let { put(TripDatabase.COL_END_LON, it) }
                record.endSoc?.let { put(TripDatabase.COL_END_SOC, it) }
                record.endOdometerKm?.let { put(TripDatabase.COL_END_ODO, it) }
                record.routeProfile?.let { put(TripDatabase.COL_ROUTE_PROFILE, it.name) }
                val pendingEnd = record.endedAt
                if (pendingEnd != null) put(TripDatabase.COL_ENDED_AT, pendingEnd)
                else putNull(TripDatabase.COL_ENDED_AT)
            }
            val rows = db.writableDatabase.update(
                TripDatabase.TABLE, cv,
                "${TripDatabase.COL_ID} = ? AND ${TripDatabase.COL_FINISHED} = 0",
                arrayOf(id.toString())
            )
            Log.i(TAG, "updateLive: id=$id, rows=$rows")
        } catch (t: Throwable) {
            Log.e(TAG, "updateLive failed id=$id: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    suspend fun finish(id: Long, record: TripRecord) = withContext(Dispatchers.IO) {
        try {
            val cv = ContentValues().apply {
                put(TripDatabase.COL_ENDED_AT, record.endedAt ?: System.currentTimeMillis())
                put(TripDatabase.COL_FINISHED, 1)
                record.endSoc?.let { put(TripDatabase.COL_END_SOC, it) }
                record.endOdometerKm?.let { put(TripDatabase.COL_END_ODO, it) }
                put(TripDatabase.COL_DISTANCE, record.distanceKm)
                put(TripDatabase.COL_DURATION, record.durationMin)
                put(TripDatabase.COL_ENERGY, record.energyKwh)
                put(TripDatabase.COL_CONSUMPTION, record.consumption)
                record.avgSpeedKmh?.let { put(TripDatabase.COL_AVG_SPEED, it) }
                record.maxSpeedKmh?.let { put(TripDatabase.COL_MAX_SPEED, it) }
                record.outsideTempAvgC?.let { put(TripDatabase.COL_OUTSIDE_TEMP, it) }
                record.insideTempAvgC?.let { put(TripDatabase.COL_INSIDE_TEMP, it) }
                record.battTempAvgC?.let { put(TripDatabase.COL_BATT_TEMP, it) }
                record.endLat?.let { put(TripDatabase.COL_END_LAT, it) }
                record.endLon?.let { put(TripDatabase.COL_END_LON, it) }
                record.routeProfile?.let { put(TripDatabase.COL_ROUTE_PROFILE, it.name) }
            }
            val rows = db.writableDatabase.update(
                TripDatabase.TABLE, cv,
                "${TripDatabase.COL_ID} = ?", arrayOf(id.toString())
            )
            Log.i(TAG, "finish: id=$id, rows=$rows, endedAt=${record.endedAt}")
        } catch (t: Throwable) {
            Log.e(TAG, "finish failed id=$id: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    suspend fun getAllFinished(): List<TripRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TripRecord>()
        try {
            db.readableDatabase.query(
                TripDatabase.TABLE, null,
                "${TripDatabase.COL_FINISHED} = 1", null,
                null, null,
                "${TripDatabase.COL_STARTED_AT} DESC"
            ).use { c ->
                while (c.moveToNext()) list.add(cursorToRecord(c))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getAllFinished failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
        list
    }

    suspend fun getById(id: Long): TripRecord? = withContext(Dispatchers.IO) {
        try {
            db.readableDatabase.query(
                TripDatabase.TABLE, null,
                "${TripDatabase.COL_ID} = ?", arrayOf(id.toString()),
                null, null, null
            ).use { c ->
                if (c.moveToFirst()) cursorToRecord(c) else null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getById($id) failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    suspend fun getUnfinished(): List<TripRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TripRecord>()
        try {
            db.readableDatabase.query(
                TripDatabase.TABLE, null,
                "${TripDatabase.COL_FINISHED} = 0", null,
                null, null,
                "${TripDatabase.COL_STARTED_AT} DESC"
            ).use { c ->
                while (c.moveToNext()) list.add(cursorToRecord(c))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getUnfinished failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
        list
    }

    /**
     * Последние N завершённых поездок с минимальной длиной — для BaseConsumptionRepository.
     */
    suspend fun getRecentFinished(
        fromMs: Long,
        minKm: Double,
        limit: Int
    ): List<TripRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TripRecord>()
        try {
            db.readableDatabase.query(
                TripDatabase.TABLE, null,
                "${TripDatabase.COL_FINISHED} = 1 " +
                        "AND ${TripDatabase.COL_ENDED_AT} >= ? " +
                        "AND ${TripDatabase.COL_DISTANCE} >= ?",
                arrayOf(fromMs.toString(), minKm.toString()),
                null, null,
                "${TripDatabase.COL_ENDED_AT} DESC",
                limit.toString()
            ).use { c ->
                while (c.moveToNext()) list.add(cursorToRecord(c))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getRecentFinished failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
        list
    }

    suspend fun getTodaySummary(now: Long = System.currentTimeMillis()): TripDaySummary =
        withContext(Dispatchers.IO) {
            try {
                val startOfDay = computeDayStart(now)
                val endOfDay = startOfDay + 24 * 60 * 60 * 1000L

                val sql = """
                SELECT
                COALESCE(SUM(${TripDatabase.COL_DISTANCE}), 0),
                COALESCE(SUM(${TripDatabase.COL_ENERGY}), 0),
                COALESCE(SUM(${TripDatabase.COL_DURATION}), 0),
                COUNT(*)
                FROM ${TripDatabase.TABLE}
                WHERE ${TripDatabase.COL_STARTED_AT} >= ?
                AND ${TripDatabase.COL_STARTED_AT} < ?
                AND ${TripDatabase.COL_DISTANCE} > 0.5
                """.trimIndent()

                val args = arrayOf(startOfDay.toString(), endOfDay.toString())

                db.readableDatabase.rawQuery(sql, args).use { c ->
                    if (c.moveToFirst()) {
                        val totalDistance = c.getDouble(0)
                        val totalEnergy = c.getDouble(1)
                        val totalDuration = c.getLong(2).toInt()
                        val tripsCount = c.getInt(3)

                        val avgConsumption = if (totalDistance > 0.1)
                            totalEnergy / totalDistance * 100.0
                        else 0.0

                        Log.i(
                            TAG,
                            "getTodaySummary: dist=$totalDistance km, " +
                                    "energy=$totalEnergy kWh, " +
                                    "avg=$avgConsumption kWh/100km, " +
                                    "duration=$totalDuration min, " +
                                    "trips=$tripsCount"
                        )

                        TripDaySummary(
                            startOfDayMs = startOfDay,
                            endOfDayMs = endOfDay,
                            distanceKm = totalDistance,
                            energyKwh = totalEnergy,
                            durationMin = totalDuration,
                            consumptionKwh100 = avgConsumption,
                            tripsCount = tripsCount
                        )
                    } else {
                        TripDaySummary.empty(startOfDay)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "getTodaySummary failed: ${t.javaClass.simpleName}: ${t.message}", t)
                TripDaySummary.empty(runCatching { computeDayStart(now) }.getOrDefault(now))
            }
        }

    private fun computeDayStart(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 6)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        if (now < cal.timeInMillis) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return cal.timeInMillis
    }

    private fun cursorToRecord(c: Cursor): TripRecord {
        fun getDoubleOrNull(col: String): Double? {
            val idx = c.getColumnIndex(col)
            return if (idx >= 0 && !c.isNull(idx)) c.getDouble(idx) else null
        }
        fun getLongOrNull(col: String): Long? {
            val idx = c.getColumnIndex(col)
            return if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null
        }
        fun getIntOrNull(col: String): Int? {
            val idx = c.getColumnIndex(col)
            return if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else null
        }
        fun getStringOrNull(col: String): String? {
            val idx = c.getColumnIndex(col)
            return if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
        }

        val profileRaw = getStringOrNull(TripDatabase.COL_ROUTE_PROFILE)
        val profile = profileRaw?.let {
            runCatching { RouteProfile.valueOf(it) }.getOrNull()
        }

        return TripRecord(
            id = c.getLong(c.getColumnIndexOrThrow(TripDatabase.COL_ID)),
            startedAt = c.getLong(c.getColumnIndexOrThrow(TripDatabase.COL_STARTED_AT)),
            endedAt = getLongOrNull(TripDatabase.COL_ENDED_AT),
            finished = c.getInt(c.getColumnIndexOrThrow(TripDatabase.COL_FINISHED)) == 1,
            startSoc = getIntOrNull(TripDatabase.COL_START_SOC),
            endSoc = getIntOrNull(TripDatabase.COL_END_SOC),
            startOdometerKm = getDoubleOrNull(TripDatabase.COL_START_ODO),
            endOdometerKm = getDoubleOrNull(TripDatabase.COL_END_ODO),
            distanceKm = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.COL_DISTANCE)),
            durationMin = c.getLong(c.getColumnIndexOrThrow(TripDatabase.COL_DURATION)),
            avgSpeedKmh = getDoubleOrNull(TripDatabase.COL_AVG_SPEED),
            maxSpeedKmh = getDoubleOrNull(TripDatabase.COL_MAX_SPEED),
            outsideTempAvgC = getDoubleOrNull(TripDatabase.COL_OUTSIDE_TEMP),
            insideTempAvgC = getDoubleOrNull(TripDatabase.COL_INSIDE_TEMP),
            battTempAvgC = getDoubleOrNull(TripDatabase.COL_BATT_TEMP),
            energyKwh = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.COL_ENERGY)),
            consumption = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.COL_CONSUMPTION)),
            startLat = getDoubleOrNull(TripDatabase.COL_START_LAT),
            startLon = getDoubleOrNull(TripDatabase.COL_START_LON),
            endLat = getDoubleOrNull(TripDatabase.COL_END_LAT),
            endLon = getDoubleOrNull(TripDatabase.COL_END_LON),
            routeProfile = profile
        )
    }

    fun close() {
        runCatching { db.close() }
            .onFailure { Log.w(TAG, "close failed: ${it.message}") }
    }
}