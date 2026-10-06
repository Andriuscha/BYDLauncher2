package com.ar.bydlauncher.byd

import android.content.Context
import android.util.Log

/**
 * Отслеживает момент, когда SOC достиг 100% (или близко к тому),
 * и сохраняет одометр в этот момент. Позволяет считать
 * "пробег после полной зарядки" = текущий одометр − одометр на 100%.
 */
class FullChargeTracker(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "FullChargeTracker"
        private const val PREFS_NAME = "full_charge_tracker"

        private const val KEY_ODO_AT_FULL = "odometer_at_full_charge_km"
        private const val KEY_TIME_AT_FULL = "timestamp_at_full_charge_ms"
        private const val KEY_SOC_AT_FULL = "soc_at_full_charge"

        // SOC, при котором считаем "полный заряд" — 99%, чтобы не пропустить момент,
        // если машина недозарядилась ровно до 100.
        private const val FULL_SOC_THRESHOLD = 99
    }

    /**
     * Вызывать при каждом получении snapshot BMS.
     * Если SOC достиг порога и текущий одометр известен — сохраняем.
     */
    fun onSnapshot(socPercent: Float?, odometerKm: Float?) {
        if (socPercent == null || odometerKm == null) return
        val soc = socPercent.toInt()

        if (soc < FULL_SOC_THRESHOLD) return

        val savedOdo = prefs.getFloat(KEY_ODO_AT_FULL, -1f)
        val savedTime = prefs.getLong(KEY_TIME_AT_FULL, 0L)

        // Если уже сохраняли и одометр не изменился — не перезаписываем
        if (savedOdo > 0f && Math.abs(savedOdo - odometerKm) < 0.1f) {
            return
        }

        // Если уже сохраняли сегодня и SOC снова 100% — обновляем только если прошло время
        // (пользователь мог отъехать, снова зарядиться — это новый цикл)
        val now = System.currentTimeMillis()

        // Первое сохранение — сразу
        if (savedOdo < 0f) {
            saveFullCharge(odometerKm, now, soc)
            Log.i(TAG, "First full charge saved: odo=$odometerKm km, soc=$soc%")
            return
        }

        // Если прошло больше часа с последнего "100%" и одометр отличается —
        // значит это новый цикл зарядки
        if (now - savedTime > 60 * 60 * 1000L &&
            Math.abs(savedOdo - odometerKm) > 0.5f) {
            saveFullCharge(odometerKm, now, soc)
            Log.i(TAG, "New full charge cycle saved: odo=$odometerKm km, soc=$soc%")
        }
    }

    private fun saveFullCharge(odoKm: Float, timestampMs: Long, soc: Int) {
        prefs.edit()
            .putFloat(KEY_ODO_AT_FULL, odoKm)
            .putLong(KEY_TIME_AT_FULL, timestampMs)
            .putInt(KEY_SOC_AT_FULL, soc)
            .apply()
    }

    /**
     * Считает "пробег после полной зарядки".
     * Возвращает null, если ещё ни разу не заряжались до 100% в текущей сессии данных.
     */
    fun getDistanceSinceFullCharge(currentOdometerKm: Float?): Double? {
        if (currentOdometerKm == null) return null
        val savedOdo = prefs.getFloat(KEY_ODO_AT_FULL, -1f)
        if (savedOdo < 0f) return null

        val delta = currentOdometerKm - savedOdo
        return if (delta >= 0) delta.toDouble() else 0.0
    }

    /**
     * Возвращает, сколько км было на одометре в момент 100%.
     */
    fun getOdometerAtFullCharge(): Float? {
        val savedOdo = prefs.getFloat(KEY_ODO_AT_FULL, -1f)
        return if (savedOdo >= 0f) savedOdo else null
    }

    /**
     * Сбросить трекер (например, если данные испортились).
     */
    fun reset() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Tracker reset")
    }
}