package com.ar.bydlauncher.byd

import android.util.Log

class BmsReader(private val c: AutoserviceClient) {

    companion object {
        private const val TAG = "BmsReader"

        // ── Батарея / BMS ──────────────────────────────
        const val FID_SOH          = 1145045032  // STATISTIC_BATTERY_HEALTHY_INDEX
        const val FID_SOC          = 1246777400  // STATISTIC_ELEC_PERCENTAGE (float)
        const val FID_MAX_CELL_V   = 1147142192  // STATISTIC_HIGHEST_BATTERY_VOLTAGE (mV)
        const val FID_MIN_CELL_V   = 1147142160  // STATISTIC_LOWEST_BATTERY_VOLTAGE
        const val FID_MAX_BAT_TEMP = 1148190752  // STATISTIC_HIGHEST_BATTERY_TEMP (raw-40)
        const val FID_MIN_BAT_TEMP = 1148190736  // STATISTIC_LOWEST_BATTERY_TEMP
        const val FID_INSULATION   = 1134559256  // GB_BMC_INSULATION_VALUE (kΩ)
        const val FID_LIFETIME_KWH = 1032871984  // STATISTIC_TOTAL_ELEC_CONSUMPTION (float)
        const val FID_LIFETIME_KM  = 1246765072  // STATISTIC_TOTAL_MILEAGE (×10)
        const val FID_HV_VOLTAGE   = 1145045000  // CHARGING_CHARGE_BATTERY_VOLT
        const val FID_HV_CURRENT   = 1145045016  // CHARGING_CHARGE_CURRENT (float)
        const val FID_BMS_STATE    = 876609560   // CHARGING_BATTERY_DEVICE_STATE
        const val FID_VOLTAGE_12V  = 1128267816  // OTA_BATTERY_POWER_VOLTAGE (float)
        const val FID_POWER_KW     = 339738656   // ENGINE_POWER (kW)

        // ── Для TripDetector ───────────────────────────
        // GEARBOX_AUTO_MODE_TYPE: по javadoc P=1, R=2, N=3, D=4, M=5, S=6
        const val FID_GEAR_MODE    = 555745336   // Gearbox.GEARBOX_AUTO_MODE_TYPE
        const val FID_GEAR_ACTUAL  = 606076980   // Gearbox.GEARBOX_ACTUAL_GERA_STATE (диагностика)
        const val FID_SPEED        = 339738632   // Statistic.STATISTIC_SPEED_SIG_VDIS

        // ── Ремень водителя (TripDetector) ──
        // getSafetyBeltStatus(SAFETY_BELT_AREA_MAIN): по javadoc 1 = LOCK, 0 = UNLOCK, 2 = INVALID
        const val FID_BELT_MAIN    = -1455423464 // Safety.SAFETY_BELT_COMMAND_AREA_MAIN
        const val FID_BELT_INSTR   = 692060184   // Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE
        const val FID_BELT_LF      = 692060180   // Safety.SAFETY_BELT_LF_FLAG (левый перед)

        /** Источник ремня для TripDetector: 0 = Safety COMMAND_AREA_MAIN, 1 = приборка, 2 = Safety LF. */
        const val BELT_SOURCE = 0
        const val BELT_BUCKLED_VALUE = 1     // SAFETY_BELT_STATE_LOCK
        const val BELT_UNBUCKLED_VALUE = 0   // SAFETY_BELT_STATE_UNLOCK (2 = INVALID → null)

        // ── Температуры (AC) ───────────────────────────
        const val FID_TEMP_OUTSIDE = 1077936184  // Ac.AC_TEMP_OUT
        const val FID_TEMP_INSIDE  = 1031798832  // Ac.AC_TEMP_INSIDE
    }

    // ── Запросы создаются один раз (постоянные) ────────

    private val reqSoh = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_SOH, AutoserviceClient.ValueType.INT)

    private val reqSoc = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_SOC, AutoserviceClient.ValueType.FLOAT)

    private val reqMaxCellV = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_MAX_CELL_V, AutoserviceClient.ValueType.INT)

    private val reqMinCellV = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_MIN_CELL_V, AutoserviceClient.ValueType.INT)

    private val reqMaxBatTemp = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_MAX_BAT_TEMP, AutoserviceClient.ValueType.INT)

    private val reqMinBatTemp = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_MIN_BAT_TEMP, AutoserviceClient.ValueType.INT)

    private val reqInsulation = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_INSULATION, AutoserviceClient.ValueType.INT)

    private val reqLifetimeKwh = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_LIFETIME_KWH, AutoserviceClient.ValueType.FLOAT)

    private val reqLifetimeKm = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_LIFETIME_KM, AutoserviceClient.ValueType.INT)

    private val reqHvVoltage = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_CHARGING, FID_HV_VOLTAGE, AutoserviceClient.ValueType.INT)

    private val reqHvCurrent = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_CHARGING, FID_HV_CURRENT, AutoserviceClient.ValueType.FLOAT)

    private val reqBmsState = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_CHARGING, FID_BMS_STATE, AutoserviceClient.ValueType.INT)

    private val reqVoltage12v = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_BODYWORK, FID_VOLTAGE_12V, AutoserviceClient.ValueType.FLOAT)

    private val reqPowerKw = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_ENGINE, FID_POWER_KW, AutoserviceClient.ValueType.INT)

    private val reqGearMode = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_GEARBOX, FID_GEAR_MODE, AutoserviceClient.ValueType.INT)

    private val reqSpeed = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_STATISTIC, FID_SPEED, AutoserviceClient.ValueType.INT)

    private val reqGearActual = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_GEARBOX, FID_GEAR_ACTUAL, AutoserviceClient.ValueType.INT)

    private val reqBeltMain = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_SAFETY_BELT, FID_BELT_MAIN, AutoserviceClient.ValueType.INT)

    private val reqBeltInstr = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_INSTRUMENT, FID_BELT_INSTR, AutoserviceClient.ValueType.INT)

    private val reqBeltLf = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_SAFETY_BELT, FID_BELT_LF, AutoserviceClient.ValueType.INT)

    private val reqTempOutside = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_AC, FID_TEMP_OUTSIDE, AutoserviceClient.ValueType.INT)

    private val reqTempInside = AutoserviceClient.FieldRequest(
        AutoserviceClient.DEV_AC, FID_TEMP_INSIDE, AutoserviceClient.ValueType.INT)

    /** Все запросы одним списком — уходит одним ADB round-trip. */
    private val allRequests = listOf(
        reqSoh, reqSoc,
        reqMaxCellV, reqMinCellV,
        reqMaxBatTemp, reqMinBatTemp,
        reqInsulation,
        reqLifetimeKwh, reqLifetimeKm,
        reqHvVoltage, reqHvCurrent, reqBmsState,
        reqVoltage12v, reqPowerKw,
        reqGearMode, reqSpeed,
        reqGearActual, reqBeltMain, reqBeltInstr, reqBeltLf,
        reqTempOutside, reqTempInside
    )

    /**
     * Читает все поля батареи, температур и состояния авто **за один**
     * ADB round-trip (getBatch). Возвращает null, если shell не отработал
     * вовсе (ADB отвалился / autoservice недоступен).
     */
    fun read(): BatterySnapshot? {
        val result = c.getBatch(allRequests) ?: run {
            Log.w(TAG, "getBatch вернул null — ADB недоступен")
            return null
        }

        // ── Извлекаем сырые значения ──────────────────
        val soh           = result[reqSoh]         as? Int
        val soc           = result[reqSoc]         as? Float
        val maxRaw        = result[reqMaxCellV]    as? Int
        val minRaw        = result[reqMinCellV]    as? Int
        val maxT          = result[reqMaxBatTemp]  as? Int
        val minT          = result[reqMinBatTemp]  as? Int
        val insulation    = result[reqInsulation]  as? Int
        val lifetimeKwh   = result[reqLifetimeKwh] as? Float
        val lifetimeKmRaw = result[reqLifetimeKm]  as? Int
        Log.i(
            TAG,
            "LIFETIME_KM: raw=$lifetimeKmRaw -> km=${lifetimeKmRaw?.let { it / 10f }}"
        )
        val hvVoltage     = result[reqHvVoltage]   as? Int
        val hvCurrent     = result[reqHvCurrent]   as? Float
        val bmsState      = result[reqBmsState]    as? Int
        val v12           = result[reqVoltage12v]  as? Float
        val powerKw       = result[reqPowerKw]     as? Int
        val gearMode      = result[reqGearMode]    as? Int
        val speedRaw      = result[reqSpeed]       as? Int
        val gearActual    = result[reqGearActual]  as? Int
        val beltMain      = result[reqBeltMain]    as? Int
        val beltInstr     = result[reqBeltInstr]   as? Int
        val beltLf        = result[reqBeltLf]      as? Int
        val tempOutside   = result[reqTempOutside] as? Int
        val tempInside    = result[reqTempInside]  as? Int

        // ADB жив, но autoservice отвечает мусором/ошибками: все ключевые поля null.
        // Считаем это провалом чтения, чтобы сработал счётчик ошибок и переподключение.
        if (soc == null && maxRaw == null && hvVoltage == null &&
            lifetimeKmRaw == null && bmsState == null && gearMode == null
        ) {
            Log.w(TAG, "Снапшот пуст: все ключевые поля null — считаем чтение неудачным")
            return null
        }

        // ── Преобразования в единицы измерения ─────────
        return BatterySnapshot(
            // Батарея / BMS
            sohPercent     = soh,
            socPercent     = soc,
            maxCellV       = maxRaw?.let { it * 0.001 },
            minCellV       = minRaw?.let { it * 0.001 },
            cellDeltaMv    = if (maxRaw != null && minRaw != null) maxRaw - minRaw else null,
            maxBatTempC    = maxT?.minus(40),
            minBatTempC    = minT?.minus(40),
            hvVoltageV     = hvVoltage,
            hvCurrentA     = hvCurrent,
            insulationKohm = insulation,
            voltage12v     = v12,
            bmsState       = bmsState,
            powerKw        = powerKw,
            lifetimeKwh    = lifetimeKwh,
            lifetimeKm     = lifetimeKmRaw?.let { it / 10f },

            // TripDetector
            gearMode       = gearMode,
            gearActualRaw  = gearActual,
            speedKmh       = speedRaw?.toFloat(),

            // Ремень
            beltMainRaw       = beltMain,
            beltInstrRaw      = beltInstr,
            beltLfRaw         = beltLf,
            driverBeltBuckled = decodeBelt(when (BELT_SOURCE) {
                1 -> beltInstr
                2 -> beltLf
                else -> beltMain
            }),

            // Температуры (AC)
            tempOutsideC   = tempOutside,
            tempInsideC    = tempInside
        )
    }

    /** true — пристёгнут, false — отстёгнут, null — неизвестно / незнакомое значение. */
    private fun decodeBelt(raw: Int?): Boolean? = when (raw) {
        null -> null
        BELT_BUCKLED_VALUE -> true
        BELT_UNBUCKLED_VALUE -> false
        else -> null
    }
}
