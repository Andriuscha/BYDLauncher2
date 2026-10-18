package com.ar.bydlauncher.byd

import android.util.Log

class AutoserviceClient(private val adb: com.ar.bydlauncher.adb.AdbClient) {

    companion object {
        private const val TAG = "AutoserviceClient"

        const val TX_GET_INT = 5
        const val TX_SET_INT = 6
        const val TX_GET_FLOAT = 7
        const val TX_SET_FLOAT = 8       // гипотеза; см. setFloat ниже

        // Устройства (BYDAutoConstants)
        const val DEV_AC = 1000
        const val DEV_BODYWORK = 1001
        const val DEV_INSTRUMENT = 1007
        const val DEV_CHARGING = 1009
        const val DEV_GEARBOX = 1011

        const val DEV_POWER = 1005
        const val DEV_ENGINE = 1012
        const val DEV_SPEED = 1013
        const val DEV_STATISTIC = 1014
        const val DEV_SETTING = 1023
        const val DEV_GB = 1039
        const val DEV_SAFETY_BELT = 1042

        private const val DELIMITER = "@@BYD@@"

        // Коды возврата BYD HAL
        private const val RESULT_SUCCESS = 0
        private const val RESULT_ASYNC_ACCEPTED = 1   // DiLink: команда принята в очередь
        private const val RESULT_BUSY = -2147482647
        private const val RESULT_FAILED = -2147482648
        private const val RESULT_INVALID_VALUE = -2147482645
        private const val RESULT_TIMEOUT = -2147482646

        // Формат ответа BYD:
        //   GET:  Parcel(00000000 00000017)  → [code][value]
        //   SET:  Parcel(00000001)           → [code]
        //   ERR:  Parcel(ffffd8e2)           → [code<0]
        //
        // Захватываем всё, что между Parcel( и ).
        private val PARCEL_HEX =
            Regex("""Parcel\(([0-9a-fA-F]+)(?:\s+([0-9a-fA-F]+))?""")
    }

    enum class ValueType { INT, FLOAT }

    data class FieldRequest(
        val device: Int,
        val fid: Int,
        val type: ValueType
    )

    // ─────────────────────────────────────────────
    //  Чтение
    // ─────────────────────────────────────────────

    fun getInt(device: Int, fid: Int): Int? {
        val out = adb.shell(intCommand(device, fid)) ?: return null
        return parseInt(out)
    }

    fun getFloat(device: Int, fid: Int): Float? {
        val out = adb.shell(floatCommand(device, fid)) ?: return null
        return parseFloat(out)
    }

    /**
     * Сырой вывод для тестера FID.
     */
    fun getRaw(device: Int, fid: Int, type: ValueType): String? {
        val cmd = when (type) {
            ValueType.INT -> intCommand(device, fid)
            ValueType.FLOAT -> floatCommand(device, fid)
        }
        return adb.shell(cmd)
    }

    // ─────────────────────────────────────────────
    //  Запись
    // ─────────────────────────────────────────────

    fun setInt(device: Int, fid: Int, value: Int): Boolean {
        val cmd = "service call autoservice $TX_SET_INT i32 $device i32 $fid i32 $value"
        val out = adb.shell(cmd) ?: run {
            Log.w(TAG, "setInt shell null (dev=$device fid=$fid val=$value)")
            return false
        }
        if (out.contains("Error", ignoreCase = true)) {
            Log.w(TAG, "setInt shell error: $out")
            return false
        }
        val code = parseCode(out) ?: run {
            Log.w(TAG, "setInt no code in: $out")
            return false
        }
        Log.i(TAG, "setInt dev=$device fid=$fid val=$value code=$code")
        return code >= 0
    }

    fun setFloat(device: Int, fid: Int, value: Float): Boolean {
        val cmd = "service call autoservice $TX_SET_FLOAT i32 $device i32 $fid f32 $value"
        val out = adb.shell(cmd) ?: run {
            Log.w(TAG, "setFloat shell null (dev=$device fid=$fid val=$value)")
            return false
        }
        if (out.contains("Error", ignoreCase = true)) {
            Log.w(TAG, "setFloat shell error: $out")
            return false
        }
        val code = parseCode(out) ?: run {
            Log.w(TAG, "setFloat no code in: $out")
            return false
        }
        Log.i(TAG, "setFloat dev=$device fid=$fid val=$value code=$code")
        return code >= 0
    }

    /**
     * Сырой вызов — для тестера FID.
     */
    fun callRaw(tx: Int, device: Int, fid: Int, value: Int): String? {
        val cmd = "service call autoservice $tx i32 $device i32 $fid i32 $value"
        return adb.shell(cmd)
    }

    // ─────────────────────────────────────────────
    //  Батч
    // ─────────────────────────────────────────────

    fun getBatch(requests: List<FieldRequest>): Map<FieldRequest, Any?>? {
        if (requests.isEmpty()) return emptyMap()

        val script = requests.joinToString(separator = " ; echo $DELIMITER ; ") { req ->
            when (req.type) {
                ValueType.INT -> intCommand(req.device, req.fid)
                ValueType.FLOAT -> floatCommand(req.device, req.fid)
            }
        }

        val out = adb.shell(script) ?: return null
        val blocks = out.split(DELIMITER)

        return requests.mapIndexed { i, req ->
            val block = blocks.getOrNull(i)
            val value: Any? = when {
                block == null -> null
                req.type == ValueType.INT -> parseInt(block)
                else -> parseFloat(block)
            }
            req to value
        }.toMap()
    }

    // ─────────────────────────────────────────────
    //  Внутренние парсеры
    // ─────────────────────────────────────────────

    private fun intCommand(device: Int, fid: Int): String =
        "service call autoservice $TX_GET_INT i32 $device i32 $fid"

    private fun floatCommand(device: Int, fid: Int): String =
        "service call autoservice $TX_GET_FLOAT i32 $device i32 $fid"

    /** Возвращает code (первый dword). */
    private fun parseCode(out: String): Int? {
        val m = PARCEL_HEX.find(out) ?: return null
        val codeHex = m.groupValues[1].takeIf { it.isNotBlank() } ?: return null
        return codeHex.toLong(16).toInt()
    }

    /**
     * GET-int: Parcel(0000000C 000000VV)
     *  code = первый dword (должен быть >= 0)
     *  value = второй dword
     *
     * Особые «мусорные» значения, означающие "нет данных":
     *   0x0000FFFF, 0x000FFFFF, -10013, -10011
     */
    private fun parseInt(out: String): Int? {
        val m = PARCEL_HEX.find(out) ?: return null
        val codeHex = m.groupValues[1]
        val valueHex = m.groupValues.getOrNull(2) ?: return null

        if (codeHex.isBlank() || valueHex.isBlank()) return null

        val code = codeHex.toLong(16).toInt()
        if (code < 0) return null    // HAL вернул ошибку, value недействительно

        val raw = valueHex.toLong(16).toInt()

        return if (
            raw == 0x0000FFFF || raw == 0x000FFFFF ||
            raw == -10013 || raw == -10011
        ) null else raw
    }

    /**
     * GET-float: Parcel(0000000C XXXXXXXX)
     *  code = первый dword
     *  value = второй dword как Float.fromBits
     *
     * Фильтруем NaN, ±Inf, -1.0 (это «нет данных»).
     */
    private fun parseFloat(out: String): Float? {
        val m = PARCEL_HEX.find(out) ?: return null
        val codeHex = m.groupValues[1]
        val valueHex = m.groupValues.getOrNull(2) ?: return null

        if (codeHex.isBlank() || valueHex.isBlank()) return null

        val code = codeHex.toLong(16).toInt()
        if (code < 0) return null

        val bits = valueHex.toLong(16).toInt()
        val f = Float.fromBits(bits)

        return if (f.isNaN() || f.isInfinite() || f == -1.0f) null else f
    }
}