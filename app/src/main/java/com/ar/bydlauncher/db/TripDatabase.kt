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
        private const val DB_VERSION = 1

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

        private const val CREATE_TABLE = """
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

        private const val CREATE_INDEX_STARTED =
            "CREATE INDEX idx_trips_started ON $TABLE($COL_STARTED_AT)"
        private const val CREATE_INDEX_FINISHED =
            "CREATE INDEX idx_trips_finished ON $TABLE($COL_FINISHED)"
        private const val CREATE_INDEX_FINISHED_STARTED =
            "CREATE INDEX idx_trips_finished_started ON $TABLE($COL_FINISHED, $COL_STARTED_AT)"
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "onCreate: creating trips table")
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_INDEX_STARTED)
        db.execSQL(CREATE_INDEX_FINISHED)
        db.execSQL(CREATE_INDEX_FINISHED_STARTED)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "onUpgrade: $oldVersion -> $newVersion, recreating table")
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }
}