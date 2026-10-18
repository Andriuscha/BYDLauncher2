package com.ar.bydlauncher.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class TripDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    @Synchronized
    override fun close() { /* no-op */ }

    companion object {
        private const val TAG = "TripDatabase"

        @Volatile
        private var instance: TripDatabase? = null

        fun get(context: Context): TripDatabase =
            instance ?: synchronized(this) {
                instance ?: TripDatabase(context.applicationContext).also { instance = it }
            }

        private const val DB_NAME = "trips.db"
        private const val DB_VERSION = 5     // v4 → track_points, v5 → track_points.speed_kmh

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
        const val COL_ROUTE_PROFILE = "route_profile"

        // ── Chargings ──
        const val TABLE_CHARGING = "chargings"
        const val CH_ID = "id"
        const val CH_STARTED_AT = "started_at"
        const val CH_ENDED_AT = "ended_at"
        const val CH_DURATION_MIN = "duration_min"
        const val CH_AC_DC = "ac_dc"
        const val CH_ENERGY_VEHICLE = "energy_vehicle_kwh"
        const val CH_ENERGY_STATION = "energy_station_kwh"
        const val CH_BMS_START = "bms_counter_start"
        const val CH_BMS_END = "bms_counter_end"
        const val CH_CELLTEMP_START = "cell_temp_start_c"
        const val CH_CELLTEMP_END = "cell_temp_end_c"
        const val CH_TEMP_OUTSIDE_START = "temp_outside_start_c"
        const val CH_TEMP_OUTSIDE_END = "temp_outside_end_c"
        const val CH_LOCATION = "location"
        const val CH_COST_PER_KWH = "cost_per_kwh"
        const val CH_TOTAL_COST = "total_cost"
        const val CH_ODOMETER_KM = "odometer_km"
        const val CH_START_SOC = "start_soc"
        const val CH_END_SOC = "end_soc"
        const val CH_NOTES = "notes"

        // ── KmPerPct (v3) ──
        const val TABLE_KMPCT = "km_per_pct"
        const val KP_ID = "id"
        const val KP_TRIP_ID = "trip_id"
        const val KP_ENDED_AT = "ended_at"
        const val KP_DISTANCE_KM = "distance_km"
        const val KP_KM_PER_PCT = "km_per_pct"
        const val KP_KM_PER_PCT_NORM = "km_per_pct_norm"
        const val KP_TEMP_C = "temp_c"
        const val KP_SPEED_KMH = "speed_kmh"
        const val KP_PROFILE = "profile"
        const val KP_SOC_AVG = "soc_avg"
        const val KP_SOC_DELTA = "soc_delta"

        // ── TrackPoints (v4, v5) ──
        const val TABLE_TRACK = "track_points"
        const val TR_ID = "id"
        const val TR_TRIP_ID = "trip_id"
        const val TR_LAT = "lat"
        const val TR_LON = "lon"
        const val TR_TS = "timestamp"
        const val TR_SPEED_KMH = "speed_kmh" // v5

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
                $COL_END_LON         REAL,
                $COL_ROUTE_PROFILE   TEXT
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

        private const val CREATE_KMPCT = """
            CREATE TABLE $TABLE_KMPCT (
                $KP_ID              INTEGER PRIMARY KEY AUTOINCREMENT,
                $KP_TRIP_ID         INTEGER NOT NULL,
                $KP_ENDED_AT        INTEGER NOT NULL,
                $KP_DISTANCE_KM     REAL NOT NULL,
                $KP_KM_PER_PCT      REAL NOT NULL,
                $KP_KM_PER_PCT_NORM REAL NOT NULL,
                $KP_TEMP_C          REAL NOT NULL,
                $KP_SPEED_KMH       REAL NOT NULL,
                $KP_PROFILE         TEXT NOT NULL DEFAULT 'UNKNOWN',
                $KP_SOC_AVG         REAL NOT NULL,
                $KP_SOC_DELTA       INTEGER NOT NULL
            )
        """

        private const val CREATE_TRACK = """
            CREATE TABLE $TABLE_TRACK (
                $TR_ID       INTEGER PRIMARY KEY AUTOINCREMENT,
                $TR_TRIP_ID  INTEGER NOT NULL,
                $TR_LAT      REAL NOT NULL,
                $TR_LON      REAL NOT NULL,
                $TR_TS       INTEGER NOT NULL,
                $TR_SPEED_KMH REAL
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

        private const val CREATE_INDEX_KMPCT_ENDED =
            "CREATE INDEX idx_kmpct_ended ON $TABLE_KMPCT($KP_ENDED_AT)"
        private const val CREATE_INDEX_KMPCT_PROFILE =
            "CREATE INDEX idx_kmpct_profile ON $TABLE_KMPCT($KP_PROFILE, $KP_ENDED_AT)"

        private const val CREATE_INDEX_TRACK =
            "CREATE INDEX idx_track_trip ON $TABLE_TRACK($TR_TRIP_ID, $TR_TS)"
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "onCreate: creating tables")
        db.execSQL(CREATE_TRIPS)
        db.execSQL(CREATE_INDEX_TRIPS_STARTED)
        db.execSQL(CREATE_INDEX_TRIPS_FINISHED)
        db.execSQL(CREATE_INDEX_TRIPS_FIN_START)

        db.execSQL(CREATE_CHARGINGS)
        db.execSQL(CREATE_INDEX_CHARGING_STARTED)

        db.execSQL(CREATE_KMPCT)
        db.execSQL(CREATE_INDEX_KMPCT_ENDED)
        db.execSQL(CREATE_INDEX_KMPCT_PROFILE)

        db.execSQL(CREATE_TRACK)
        db.execSQL(CREATE_INDEX_TRACK)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "onUpgrade: $oldVersion -> $newVersion")

        if (oldVersion < 2) {
            try {
                db.execSQL(CREATE_CHARGINGS)
                db.execSQL(CREATE_INDEX_CHARGING_STARTED)
                Log.i(TAG, "Added chargings table")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create chargings table", e)
                db.execSQL("DROP TABLE IF EXISTS $TABLE_CHARGING")
                db.execSQL(CREATE_CHARGINGS)
                db.execSQL(CREATE_INDEX_CHARGING_STARTED)
            }
        }

        if (oldVersion < 3) {
            try {
                db.execSQL(CREATE_KMPCT)
                db.execSQL(CREATE_INDEX_KMPCT_ENDED)
                db.execSQL(CREATE_INDEX_KMPCT_PROFILE)
                Log.i(TAG, "Added km_per_pct table")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create km_per_pct table", e)
                db.execSQL("DROP TABLE IF EXISTS $TABLE_KMPCT")
                db.execSQL(CREATE_KMPCT)
                db.execSQL(CREATE_INDEX_KMPCT_ENDED)
                db.execSQL(CREATE_INDEX_KMPCT_PROFILE)
            }

            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_ROUTE_PROFILE TEXT")
                Log.i(TAG, "Added trips.route_profile column")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add route_profile column", e)
            }
        }

        if (oldVersion < 4) {
            try {
                db.execSQL(CREATE_TRACK)
                db.execSQL(CREATE_INDEX_TRACK)
                Log.i(TAG, "Added track_points table")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create track_points table", e)
                db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACK")
                db.execSQL(CREATE_TRACK)
                db.execSQL(CREATE_INDEX_TRACK)
            }
        }

        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRACK ADD COLUMN $TR_SPEED_KMH REAL")
                Log.i(TAG, "Added track_points.speed_kmh column")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add speed_kmh column", e)
            }
        }
    }
}