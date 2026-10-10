package com.ar.bydlauncher.scenario

import android.content.Context

/**
 * Хранит флаги сценариев в SharedPreferences.
 */
class ScenarioSettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Сценарий «Выключать ДХО при переводе КПП в P». */
    var dhoParkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_DHO_PARKING, false)
        set(v) = prefs.edit().putBoolean(KEY_DHO_PARKING, v).apply()

    /** Сценарий «Подогрев сидений — авто-выкл после отстёгивания ремня». */
    var seatHeatOffEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEAT_HEAT_OFF, false)
        set(v) = prefs.edit().putBoolean(KEY_SEAT_HEAT_OFF, v).apply()

    /** Сценарий «Эко / комфорт климат». */
    var climateEcoEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIMATE_ECO, false)
        set(v) = prefs.edit().putBoolean(KEY_CLIMATE_ECO, v).apply()

    companion object {
        private const val PREFS_NAME = "scenarios"

        private const val KEY_DHO_PARKING = "dho_parking_enabled"
        private const val KEY_SEAT_HEAT_OFF = "seat_heat_off_enabled"
        private const val KEY_CLIMATE_ECO = "climate_eco_enabled"
    }
}