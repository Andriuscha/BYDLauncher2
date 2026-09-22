package com.ar.bydlauncher.byd

import android.util.Log

class AutoserviceClient(private val adb: com.ar.bydlauncher.adb.AdbClient) {

    companion object {
        private const val TAG = "AutoserviceClient"

        const val TX_GET_INT = 5
        const val TX_SET_INT = 6
        const val TX_GET_FLOAT = 7

        const val DEV_BODYWORK = 1001
        const val DEV_CHARGING = 1009
        const val DEV_ENGINE = 1012
        const val DEV_STATISTIC = 1014
        const val DEV_GB = 1039

        private val PARCEL = Regex("""Parcel\(00000000\s+([0-9a-fA-F]{8})""")
        private const val DELIMITER = "@@BYD@@"

        // Коды возврата BYD HAL
        private const val RESULT_SUCCESS         = 0
        private const val RESULT_BUSY            = -2147482647
        private const val RESULT_FAILED          = -2147482648
        private const val RESULT_INVALID_VALUE   = -2147482645
        private const val RESULT_TIMEOUT         = -2147482646
    }

    enum class ValueType { INT, FLOAT }

    data class FieldRequest(val device: Int, val fid: Int, val type: ValueType)

    fun getInt(device: Int, fid: Int): Int? {
        val out = adb.shell(intCommand(device, fid)) ?: return null
        return parseInt(out)
    }

    fun getFloat(device: Int, fid: Int): Float? {
        val out = adb.shell(floatCommand(device, fid)) ?: return null
        return parseFloat(out)
    }

    /**
     * Записать значение. Возвращает true ТОЛЬКО если HAL вернул SUCCESS (код 0).
     *
     * Раньше мы считали успехом любой ответ без слова "Error". Это давало
     * ложные срабатывания: `service call` мог вернуть успешный Parcel,
     * внутри которого был код ошибки HAL (FAILED/BUSY/TIMEOUT). Мы
     * переключали UI, а BMS оставался в прежнем состоянии.
     */
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

        val hex = PARCEL.find(out)?.groupValues?.get(1) ?: run {
            Log.w(TAG, "setInt no Parcel in output: $out")
            return false
        }
        val code = hex.toLong(16).toInt()

        return when (code) {
            RESULT_SUCCESS -> true
            RESULT_FAILED -> {
                Log.w(TAG, "setInt FAILED (dev=$device fid=$fid val=$value)")
                false
            }
            RESULT_BUSY -> {
                Log.w(TAG, "setInt BUSY (dev=$device fid=$fid val=$value)")
                false
            }
            RESULT_TIMEOUT -> {
                Log.w(TAG, "setInt TIMEOUT (dev=$device fid=$fid val=$value)")
                false
            }
            RESULT_INVALID_VALUE -> {
                Log.w(TAG, "setInt INVALID_VALUE (dev=$device fid=$fid val=$value)")
                false
            }
            else -> {
                Log.d(TAG, "setInt unknown code=$code (dev=$device fid=$fid val=$value)")
                code >= 0
            }
        }
    }

    /**
     * Батч-чтение: все FID за один ADB round-trip.
     * null — если сам shell-вызов провалился (ADB отвалился).
     */
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

    private fun intCommand(device: Int, fid: Int) =
        "service call autoservice $TX_GET_INT i32 $device i32 $fid"

    private fun floatCommand(device: Int, fid: Int) =
        "service call autoservice $TX_GET_FLOAT i32 $device i32 $fid"

    private fun parseInt(out: String): Int? {
        val hex = PARCEL.find(out)?.groupValues?.get(1) ?: return null
        val raw = hex.toLong(16).toInt()
        return if (raw == 0x0000FFFF || raw == 0x000FFFFF ||
            raw == -10013 || raw == -10011) null else raw
    }

    private fun parseFloat(out: String): Float? {
        val hex = PARCEL.find(out)?.groupValues?.get(1) ?: return null
        val bits = hex.toLong(16).toInt()
        val f = Float.fromBits(bits)
        return if (f.isNaN() || f.isInfinite() || f == -1.0f) null else f
    }
}