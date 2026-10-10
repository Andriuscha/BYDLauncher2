package com.ar.bydlauncher.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChargingRepository(context: Context) {

    private val db = TripDatabase(context.applicationContext)

    companion object {
        private const val TAG = "ChargingRepository"
    }

    // ==================================================
    //  ВСТАВКА / ОБНОВЛЕНИЕ
    // ==================================================

    suspend fun insert(record: ChargingRecord): Long = withContext(Dispatchers.IO) {
        val cv = toContentValues(record)
        cv.remove(TripDatabase.CH_ID)
        val id = db.writableDatabase.insert(TripDatabase.TABLE_CHARGING, null, cv)
        Log.i(TAG, "insert: id=$id, startedAt=${record.startedAt}, " +
                "vehicle=${record.energyVehicleKwh} kWh")
        id
    }

    suspend fun update(id: Long, record: ChargingRecord) = withContext(Dispatchers.IO) {
        val cv = toContentValues(record)
        cv.remove(TripDatabase.CH_ID)
        val rows = db.writableDatabase.update(
            TripDatabase.TABLE_CHARGING, cv,
            "${TripDatabase.CH_ID} = ?", arrayOf(id.toString())
        )
        Log.i(TAG, "update: id=$id, rows=$rows")
    }

    suspend fun updateUserFields(
        id: Long,
        energyStationKwh: Double?,
        costPerKwh: Double?,
        totalCost: Double?,
        location: String?,
        acDc: String?,
        notes: String?
    ) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            if (energyStationKwh != null) put(TripDatabase.CH_ENERGY_STATION, energyStationKwh)
            if (costPerKwh != null) put(TripDatabase.CH_COST_PER_KWH, costPerKwh)
            if (totalCost != null) put(TripDatabase.CH_TOTAL_COST, totalCost)
            if (location != null) put(TripDatabase.CH_LOCATION, location)
            if (acDc != null) put(TripDatabase.CH_AC_DC, acDc)
            if (notes != null) put(TripDatabase.CH_NOTES, notes)
        }
        val rows = db.writableDatabase.update(
            TripDatabase.TABLE_CHARGING, cv,
            "${TripDatabase.CH_ID} = ?", arrayOf(id.toString())
        )
        Log.i(TAG, "updateUserFields: id=$id, rows=$rows")
    }

    // ==================================================
    //  ВЫБОРКИ
    // ==================================================

    suspend fun getAll(): List<ChargingRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ChargingRecord>()
        db.readableDatabase.query(
            TripDatabase.TABLE_CHARGING, null,
            null, null, null, null,
            "${TripDatabase.CH_STARTED_AT} DESC"
        ).use { c ->
            while (c.moveToNext()) list.add(cursorToRecord(c))
        }
        list
    }

    suspend fun getById(id: Long): ChargingRecord? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            TripDatabase.TABLE_CHARGING, null,
            "${TripDatabase.CH_ID} = ?", arrayOf(id.toString()),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) cursorToRecord(c) else null
        }
    }

    /**
     * Суммарная статистика за период (для отчётов).
     */
    suspend fun getTotalStats(fromTs: Long, toTs: Long): ChargingStats = withContext(Dispatchers.IO) {
        val sql = """
            SELECT
                COUNT(*),
                COALESCE(SUM(${TripDatabase.CH_ENERGY_VEHICLE}), 0),
                COALESCE(SUM(${TripDatabase.CH_ENERGY_STATION}), 0),
                COALESCE(SUM(${TripDatabase.CH_TOTAL_COST}), 0),
                COALESCE(SUM(${TripDatabase.CH_DURATION_MIN}), 0)
            FROM ${TripDatabase.TABLE_CHARGING}
            WHERE ${TripDatabase.CH_STARTED_AT} >= ?
              AND ${TripDatabase.CH_STARTED_AT} < ?
        """.trimIndent()

        db.readableDatabase.rawQuery(
            sql, arrayOf(fromTs.toString(), toTs.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                ChargingStats(
                    count = c.getInt(0),
                    totalVehicleKwh = c.getDouble(1),
                    totalStationKwh = c.getDouble(2),
                    totalCost = c.getDouble(3),
                    totalDurationMin = c.getLong(4)
                )
            } else {
                ChargingStats(0, 0.0, 0.0, 0.0, 0)
            }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val rows = db.writableDatabase.delete(
            TripDatabase.TABLE_CHARGING,
            "${TripDatabase.CH_ID} = ?", arrayOf(id.toString())
        )
        Log.i(TAG, "delete: id=$id, rows=$rows")
    }

    // ==================================================
    //  ВНУТРЕННЕЕ
    // ==================================================

    private fun toContentValues(r: ChargingRecord): ContentValues = ContentValues().apply {
        if (r.id > 0) put(TripDatabase.CH_ID, r.id)
        put(TripDatabase.CH_STARTED_AT, r.startedAt)
        r.endedAt?.let { put(TripDatabase.CH_ENDED_AT, it) } ?: putNull(TripDatabase.CH_ENDED_AT)
        r.durationMin?.let { put(TripDatabase.CH_DURATION_MIN, it) } ?: putNull(TripDatabase.CH_DURATION_MIN)
        r.acDc?.let { put(TripDatabase.CH_AC_DC, it) } ?: putNull(TripDatabase.CH_AC_DC)
        put(TripDatabase.CH_ENERGY_VEHICLE, r.energyVehicleKwh)
        r.energyStationKwh?.let { put(TripDatabase.CH_ENERGY_STATION, it) } ?: putNull(TripDatabase.CH_ENERGY_STATION)
        r.bmsCounterStart?.let { put(TripDatabase.CH_BMS_START, it) } ?: putNull(TripDatabase.CH_BMS_START)
        r.bmsCounterEnd?.let { put(TripDatabase.CH_BMS_END, it) } ?: putNull(TripDatabase.CH_BMS_END)
        r.cellTempStartC?.let { put(TripDatabase.CH_CELLTEMP_START, it) } ?: putNull(TripDatabase.CH_CELLTEMP_START)
        r.cellTempEndC?.let { put(TripDatabase.CH_CELLTEMP_END, it) } ?: putNull(TripDatabase.CH_CELLTEMP_END)
        r.tempOutsideStartC?.let { put(TripDatabase.CH_TEMP_OUTSIDE_START, it) } ?: putNull(TripDatabase.CH_TEMP_OUTSIDE_START)
        r.tempOutsideEndC?.let { put(TripDatabase.CH_TEMP_OUTSIDE_END, it) } ?: putNull(TripDatabase.CH_TEMP_OUTSIDE_END)
        r.location?.let { put(TripDatabase.CH_LOCATION, it) } ?: putNull(TripDatabase.CH_LOCATION)
        r.costPerKwh?.let { put(TripDatabase.CH_COST_PER_KWH, it) } ?: putNull(TripDatabase.CH_COST_PER_KWH)
        r.totalCost?.let { put(TripDatabase.CH_TOTAL_COST, it) } ?: putNull(TripDatabase.CH_TOTAL_COST)
        r.odometerKm?.let { put(TripDatabase.CH_ODOMETER_KM, it) } ?: putNull(TripDatabase.CH_ODOMETER_KM)
        r.startSoc?.let { put(TripDatabase.CH_START_SOC, it) } ?: putNull(TripDatabase.CH_START_SOC)
        r.endSoc?.let { put(TripDatabase.CH_END_SOC, it) } ?: putNull(TripDatabase.CH_END_SOC)
        r.notes?.let { put(TripDatabase.CH_NOTES, it) } ?: putNull(TripDatabase.CH_NOTES)
    }

    private fun cursorToRecord(c: Cursor): ChargingRecord {
        fun getLongOrNull(col: String): Long? {
            val i = c.getColumnIndex(col); return if (i >= 0 && !c.isNull(i)) c.getLong(i) else null
        }
        fun getIntOrNull(col: String): Int? {
            val i = c.getColumnIndex(col); return if (i >= 0 && !c.isNull(i)) c.getInt(i) else null
        }
        fun getDoubleOrNull(col: String): Double? {
            val i = c.getColumnIndex(col); return if (i >= 0 && !c.isNull(i)) c.getDouble(i) else null
        }
        fun getStringOrNull(col: String): String? {
            val i = c.getColumnIndex(col); return if (i >= 0 && !c.isNull(i)) c.getString(i) else null
        }

        return ChargingRecord(
            id = c.getLong(c.getColumnIndexOrThrow(TripDatabase.CH_ID)),
            startedAt = c.getLong(c.getColumnIndexOrThrow(TripDatabase.CH_STARTED_AT)),
            endedAt = getLongOrNull(TripDatabase.CH_ENDED_AT),
            durationMin = getLongOrNull(TripDatabase.CH_DURATION_MIN),
            acDc = getStringOrNull(TripDatabase.CH_AC_DC),
            energyVehicleKwh = c.getDouble(c.getColumnIndexOrThrow(TripDatabase.CH_ENERGY_VEHICLE)),
            energyStationKwh = getDoubleOrNull(TripDatabase.CH_ENERGY_STATION),
            bmsCounterStart = getDoubleOrNull(TripDatabase.CH_BMS_START),
            bmsCounterEnd = getDoubleOrNull(TripDatabase.CH_BMS_END),
            cellTempStartC = getDoubleOrNull(TripDatabase.CH_CELLTEMP_START),
            cellTempEndC = getDoubleOrNull(TripDatabase.CH_CELLTEMP_END),
            tempOutsideStartC = getDoubleOrNull(TripDatabase.CH_TEMP_OUTSIDE_START),
            tempOutsideEndC = getDoubleOrNull(TripDatabase.CH_TEMP_OUTSIDE_END),
            location = getStringOrNull(TripDatabase.CH_LOCATION),
            costPerKwh = getDoubleOrNull(TripDatabase.CH_COST_PER_KWH),
            totalCost = getDoubleOrNull(TripDatabase.CH_TOTAL_COST),
            odometerKm = getDoubleOrNull(TripDatabase.CH_ODOMETER_KM),
            startSoc = getIntOrNull(TripDatabase.CH_START_SOC),
            endSoc = getIntOrNull(TripDatabase.CH_END_SOC),
            notes = getStringOrNull(TripDatabase.CH_NOTES)
        )
    }

    fun close() {
        db.close()
    }
}

data class ChargingStats(
    val count: Int,
    val totalVehicleKwh: Double,
    val totalStationKwh: Double,
    val totalCost: Double,
    val totalDurationMin: Long
)