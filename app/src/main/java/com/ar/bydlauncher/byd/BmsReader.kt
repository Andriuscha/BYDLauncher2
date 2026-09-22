package com.ar.bydlauncher.byd


data class BatterySnapshot(
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
    val lifetimeKm: Float?
)

class BmsReader(private val c: AutoserviceClient) {

    companion object {
        const val FID_SOH          = 1145045032  // STATISTIC_BATTERY_HEALTHY_INDEX
        const val FID_SOC          = 1246777400  // STATISTIC_ELEC_PERCENTAGE
        const val FID_MAX_CELL_V   = 1147142192  // STATISTIC_HIGHEST_BATTERY_VOLTAGE (mV)
        const val FID_MIN_CELL_V   = 1147142160  // STATISTIC_LOWEST_BATTERY_VOLTAGE
        const val FID_MAX_BAT_TEMP = 1148190752  // STATISTIC_HIGHEST_BATTERY_TEMP (raw-40)
        const val FID_MIN_BAT_TEMP = 1148190736  // STATISTIC_LOWEST_BATTERY_TEMP
        const val FID_INSULATION   = 1134559256  // GB_BMC_INSULATION_VALUE (kΩ)
        const val FID_LIFETIME_KWH = 1032871984  // STATISTIC_TOTAL_ELEC_CONSUMPTION
        const val FID_LIFETIME_KM  = 1246765072  // STATISTIC_TOTAL_MILEAGE (×10)
        const val FID_HV_VOLTAGE   = 1145045000  // CHARGING_CHARGE_BATTERY_VOLT
        const val FID_HV_CURRENT   = 1145045016  // CHARGING_CHARGE_CURRENT (float)
        const val FID_BMS_STATE    = 876609560   // CHARGING_BATTERY_DEVICE_STATE
        const val FID_VOLTAGE_12V  = 1128267816  // OTA_BATTERY_POWER_VOLTAGE
        const val FID_POWER_KW     = 339738656   // ENGINE_POWER (kW)
    }

    fun read(): BatterySnapshot {
        val maxRaw = c.getInt(AutoserviceClient.DEV_STATISTIC, FID_MAX_CELL_V)
        val minRaw = c.getInt(AutoserviceClient.DEV_STATISTIC, FID_MIN_CELL_V)
        val maxT   = c.getInt(AutoserviceClient.DEV_STATISTIC, FID_MAX_BAT_TEMP)
        val minT   = c.getInt(AutoserviceClient.DEV_STATISTIC, FID_MIN_BAT_TEMP)
        val lkm    = c.getInt(AutoserviceClient.DEV_STATISTIC, FID_LIFETIME_KM)

        return BatterySnapshot(
            sohPercent     = c.getInt(AutoserviceClient.DEV_STATISTIC, FID_SOH),
            socPercent     = c.getFloat(AutoserviceClient.DEV_STATISTIC, FID_SOC),
            maxCellV       = maxRaw?.let { it * 0.001 },
            minCellV       = minRaw?.let { it * 0.001 },
            cellDeltaMv    = if (maxRaw != null && minRaw != null) maxRaw - minRaw else null,
            maxBatTempC    = maxT?.minus(40),
            minBatTempC    = minT?.minus(40),
            hvVoltageV     = c.getInt(AutoserviceClient.DEV_CHARGING, FID_HV_VOLTAGE),
            hvCurrentA     = c.getFloat(AutoserviceClient.DEV_CHARGING, FID_HV_CURRENT),
            insulationKohm = c.getInt(AutoserviceClient.DEV_STATISTIC, FID_INSULATION),
            voltage12v     = c.getFloat(AutoserviceClient.DEV_BODYWORK, FID_VOLTAGE_12V),
            bmsState       = c.getInt(AutoserviceClient.DEV_CHARGING, FID_BMS_STATE),
            powerKw        = c.getInt(AutoserviceClient.DEV_ENGINE, FID_POWER_KW),
            lifetimeKwh    = c.getFloat(AutoserviceClient.DEV_STATISTIC, FID_LIFETIME_KWH),
            lifetimeKm     = lkm?.let { it / 10f }
        )
    }
}