package com.ar.bydlauncher.byd

data class BatterySnapshot(
    // Батарея / BMS
    val sohPercent: Int?,
    val socPercent: Float?,

    val maxCellV: Double?,
    val minCellV: Double?,
    val cellDeltaMv: Int?,

    val maxBatTempC: Int?,
    val minBatTempC: Int?,

    val hvVoltageV: Int?,
    val hvCurrentA: Float?,

    val insulationKohm: Int?,
    val voltage12v: Float?,
    val bmsState: Int?,

    val powerKw: Int?,

    val lifetimeKwh: Float?,
    val lifetimeKm: Float?,

    // TripDetector
    val gearMode: Int?,
    val speedKmh: Float?,
    val powerLevel: Int?,

    // Температуры
    val tempOutsideC: Int?,
    val tempInsideC: Int?
)