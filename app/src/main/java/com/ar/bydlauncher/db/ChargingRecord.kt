package com.ar.bydlauncher.db

/**
 * Запись о зарядной сессии.
 *
 * Обязательные поля (собираются автоматически):
 *  - startedAt, endedAt, durationMin
 *  - energyVehicleKwh (приняла машина)
 *  - bmsCounterStart, bmsCounterEnd
 *  - cellTempStartC, cellTempEndC (средние)
 *  - tempOutsideStartC, tempOutsideEndC
 *  - startSoc, endSoc
 *  - odometerKm
 *
 * Вводит пользователь (диалог):
 *  - energyStationKwh (отдала станция)
 *  - costPerKwh, totalCost
 *  - location
 *  - acDc (AC / DC)
 *  - notes
 */
data class ChargingRecord(
    val id: Long = 0,

    // Время
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMin: Long? = null,

    // Тип зарядки
    val acDc: String? = null,          // "AC" / "DC"

    // Энергия
    val energyVehicleKwh: Double = 0.0,
    val energyStationKwh: Double? = null,

    // Счётчик BMS (lifetimeKwh)
    val bmsCounterStart: Double? = null,
    val bmsCounterEnd: Double? = null,

    // Температуры (средние по ячейкам)
    val cellTempStartC: Double? = null,
    val cellTempEndC: Double? = null,

    // Внешняя температура
    val tempOutsideStartC: Double? = null,
    val tempOutsideEndC: Double? = null,

    // Место и стоимость
    val location: String? = null,
    val costPerKwh: Double? = null,
    val totalCost: Double? = null,

    // Авто
    val odometerKm: Double? = null,

    // SOC
    val startSoc: Int? = null,
    val endSoc: Int? = null,

    // Заметки
    val notes: String? = null
) {
    /** BMS-дельта за зарядку. */
    val bmsDelta: Double?
        get() = if (bmsCounterStart != null && bmsCounterEnd != null)
            bmsCounterEnd - bmsCounterStart else null

    /** Эффективность: сколько машина приняла от того, что станция отдала. */
    val efficiency: Double?
        get() = if (energyStationKwh != null && energyStationKwh > 0.1)
            energyVehicleKwh / energyStationKwh else null

    /** Стоимость за кВт·ч, если введена только общая. */
    val effectiveCostPerKwh: Double?
        get() = when {
            costPerKwh != null -> costPerKwh
            totalCost != null && energyStationKwh != null && energyStationKwh > 0.1 ->
                totalCost / energyStationKwh
            else -> null
        }
}