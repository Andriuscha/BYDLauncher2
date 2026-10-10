package com.ar.bydlauncher.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class TripDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "TripDatabase"
        private const val DB_NAME = "trips.db"
        private const val DB_VERSION = 2     // ← было 1, стало 2 (добавили chargings)

        // ── Trips ──
        const val TABLE = "trips"
        const val COL_ID = "id"
        const val COL_STARTED_AT = "started_at"
        const val COL_ENDED_AT = "ended_at"
        const val COL_FINISHED = "finished"
        const val COL_START_SOC = "start_soc"
        const val COL_END_SOC = "end_soc"
        const val COL_START_ODO = "start_odometer_km"
        const val COL_END_ODO = "end_odometer_km"
        const val COL_DISTANCE = "distance_km"
        const val COL_DURATION = "duration_min"
        const val COL_AVG_SPEED = "avg_speed_kmh"
        const val COL_MAX_SPEED = "max_speed_kmh"
        const val COL_OUTSIDE_TEMP = "outside_temp_avg_c"
        const val COL_INSIDE_TEMP = "inside_temp_avg_c"
        const val COL_BATT_TEMP = "batt_temp_avg_c"
        const val COL_ENERGY = "energy_kwh"
        const val COL_CONSUMPTION = "consumption"
        const val COL_START_LAT = "start_lat"
        const val COL_START_LON = "start_lon"
        const val COL_END_LAT = "end_lat"
        const val COL_END_LON = "end_lon"

        // ── Chargings ──
        const val TABLE_CHARGING = "chargings"
        const val CH_ID = "id"
        const val CH_STARTED_AT = "started_at"
        const val CH_ENDED_AT = "ended_at"
        const val CH_DURATION_MIN = "duration_min"
        const val CH_AC_DC = "ac_dc"                       // "AC" / "DC"
        const val CH_ENERGY_VEHICLE = "energy_vehicle_kwh"  // приняла машина
        const val CH_ENERGY_STATION = "energy_station_kwh"  // отдала станция
        const val CH_BMS_START = "bms_counter_start"        // lifetimeKwh при старте
        const val CH_BMS_END = "bms_counter_end"            // lifetimeKwh при финише
        const val CH_CELLTEMP_START = "cell_temp_start_c"   // средняя по ячейкам в начале
        const val CH_CELLTEMP_END = "cell_temp_end_c"       // средняя по ячейкам в конце
        const val CH_TEMP_OUTSIDE_START = "temp_outside_start_c"
        const val CH_TEMP_OUTSIDE_END = "temp_outside_end_c"
        const val CH_LOCATION = "location"
        const val CH_COST_PER_KWH = "cost_per_kwh"
        const val CH_TOTAL_COST = "total_cost"
        const val CH_ODOMETER_KM = "odometer_km"
        const val CH_START_SOC = "start_soc"
        const val CH_END_SOC = "end_soc"
        const val CH_NOTES = "notes"

        private const val CREATE_TRIPS = """
            CREATE TABLE $TABLE (
                $COL_ID              INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_STARTED_AT      INTEGER NOT NULL,
                $COL_ENDED_AT        INTEGER,
                $COL_FINISHED        INTEGER NOT NULL DEFAULT 0,
                $COL_START_SOC       INTEGER,
                $COL_END_SOC         INTEGER,
                $COL_START_ODO       REAL,
                $COL_END_ODO         REAL,
                $COL_DISTANCE        REAL NOT NULL DEFAULT 0,
                $COL_DURATION        INTEGER NOT NULL DEFAULT 0,
                $COL_AVG_SPEED       REAL,
                $COL_MAX_SPEED       REAL,
                $COL_OUTSIDE_TEMP    REAL,
                $COL_INSIDE_TEMP     REAL,
                $COL_BATT_TEMP       REAL,
                $COL_ENERGY          REAL NOT NULL DEFAULT 0,
                $COL_CONSUMPTION     REAL NOT NULL DEFAULT 0,
                $COL_START_LAT       REAL,
                $COL_START_LON       REAL,
                $COL_END_LAT         REAL,
                $COL_END_LON         REAL
            )
        """

        private const val CREATE_CHARGINGS = """
            CREATE TABLE $TABLE_CHARGING (
                $CH_ID                  INTEGER PRIMARY KEY AUTOINCREMENT,
                $CH_STARTED_AT          INTEGER NOT NULL,
                $CH_ENDED_AT            INTEGER,
                $CH_DURATION_MIN        INTEGER,
                $CH_AC_DC               TEXT,
                $CH_ENERGY_VEHICLE      REAL NOT NULL DEFAULT 0,
                $CH_ENERGY_STATION      REAL,
                $CH_BMS_START           REAL,
                $CH_BMS_END             REAL,
                $CH_CELLTEMP_START      REAL,
                $CH_CELLTEMP_END        REAL,
                $CH_TEMP_OUTSIDE_START  REAL,
                $CH_TEMP_OUTSIDE_END    REAL,
                $CH_LOCATION            TEXT,
                $CH_COST_PER_KWH        REAL,
                $CH_TOTAL_COST          REAL,
                $CH_ODOMETER_KM         REAL,
                $CH_START_SOC           INTEGER,
                $CH_END_SOC             INTEGER,
                $CH_NOTES               TEXT
            )
        """

        private const val CREATE_INDEX_TRIPS_STARTED =
            "CREATE INDEX idx_trips_started ON $TABLE($COL_STARTED_AT)"
        private const val CREATE_INDEX_TRIPS_FINISHED =
            "CREATE INDEX idx_trips_finished ON $TABLE($COL_FINISHED)"
        private const val CREATE_INDEX_TRIPS_FIN_START =
            "CREATE INDEX idx_trips_finished_started ON $TABLE($COL_FINISHED, $COL_STARTED_AT)"

        private const val CREATE_INDEX_CHARGING_STARTED =
            "CREATE INDEX idx_charging_started ON $TABLE_CHARGING($CH_STARTED_AT)"
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "onCreate: creating tables")
        db.execSQL(CREATE_TRIPS)
        db.execSQL(CREATE_INDEX_TRIPS_STARTED)
        db.execSQL(CREATE_INDEX_TRIPS_FINISHED)
        db.execSQL(CREATE_INDEX_TRIPS_FIN_START)

        db.execSQL(CREATE_CHARGINGS)
        db.execSQL(CREATE_INDEX_CHARGING_STARTED)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "onUpgrade: $oldVersion -> $newVersion")

        // v1 → v2: добавляем таблицу chargings, не трогаем trips
        if (oldVersion < 2) {
            try {
                db.execSQL(CREATE_CHARGINGS)
                db.execSQL(CREATE_INDEX_CHARGING_STARTED)
                Log.i(TAG, "Added chargings table")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create chargings table", e)
                // На всякий случай пересоздаём только chargings
                db.execSQL("DROP TABLE IF EXISTS $TABLE_CHARGING")
                db.execSQL(CREATE_CHARGINGS)
                db.execSQL(CREATE_INDEX_CHARGING_STARTED)
            }
        }
    }
}