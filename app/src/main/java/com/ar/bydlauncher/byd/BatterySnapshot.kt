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
    val gearActualRaw: Int?,         // GEARBOX_ACTUAL_GERA_STATE (только для сравнения на экране)
    val speedKmh: Float?,
    val powerLevel: Int?,

    // Ремень водителя и педаль газа (для TripDetector)
    val beltMainRaw: Int?,           // Safety.SAFETY_BELT_COMMAND_AREA_MAIN (основной источник)
    val beltInstrRaw: Int?,          // Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE (диагностика)
    val beltLfRaw: Int?,             // Safety.SAFETY_BELT_LF_FLAG
    val driverBeltBuckled: Boolean?, // расшифровка выбранного источника; null — неизвестно
    val accelRaw: Int?,              // Speed.SPEED_ACCELERATOR_DEPTH_10D
    val accelPercent: Float?,        // accelRaw в процентах (см. ACCEL_FULL_SCALE)

    // Температуры
    val tempOutsideC: Int?,
    val tempInsideC: Int?
)