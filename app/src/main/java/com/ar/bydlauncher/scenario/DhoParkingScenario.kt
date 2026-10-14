package com.ar.bydlauncher.scenario

import android.util.Log
import com.ar.bydlauncher.byd.AutoserviceClient
import com.ar.bydlauncher.byd.BatterySnapshot

/**
 * Сценарий: выключать ДХО при переводе КПП в P, возвращать при выходе из P.
 *
 * FID (экспериментально подтверждено):
 *   - READ  : Dev=1001, FID=985661476  (LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE)
 *             1 = ДХО включены, 2 = ДХО выключены, 0 = неизвестно
 *   - WRITE : Dev=1001, FID=1125122118 (LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE_SET)
 *             1 = включить, 2 = выключить
 *
 * Принципы надёжности:
 *   1. Действуем только если ДХО сейчас ВКЛЮЧЕНЫ (чужую настройку не трогаем).
 *      Если состояние не прочиталось — в эту стоянку ничего не делаем.
 *   2. Флаг «выключили мы» (settings.dhoWeDisabled) пишется на диск ДО записи
 *      в машину и переживает перезапуск процесса.
 *   3. Каждая запись проверяется обратным чтением, неудача повторяется.
 *   4. Сторожевое условие: если ДХО выключены нами, а скорость больше порога —
 *      возвращаем немедленно, независимо от сигнала КПП.
 *   5. При старте процесса сверяемся с флагом: выключено нами и мы не в P —
 *      возвращаем.
 *
 * Возврат всегда в состояние «включены»: по п.1 выключаем мы только из него.
 */
class DhoParkingScenario(
    private val settings: ScenarioSettingsRepository,
    private val client: AutoserviceClient
) {

    companion object {
        private const val TAG = "DhoParkingScenario"

        private const val DEV_DRL = 1001
        private const val FID_DRL_STATE = 985661476
        private const val FID_DRL_SET = 1125122118

        private const val DRL_STATE_ON = 1
        private const val DRL_STATE_OFF = 2

        private const val GEAR_P = 1

        private const val PARK_DELAY_MS = 2_000L

        /** Выше этой скорости ДХО должны быть включены в любом случае. */
        private const val SPEED_RESTORE_KMH = 2.0

        private const val MAX_DISABLE_ATTEMPTS = 3
        private const val MAX_RESTORE_ATTEMPTS = 10
        private const val SLOW_RESTORE_RETRY_MS = 30_000L
    }

    // Память процесса. Всё, что нельзя терять, лежит в settings.dhoWeDisabled.
    private var initialized = false
    private var lastInP = false
    private var pEnteredAtMs = 0L
    private var timerFired = false
    private var skipThisPark = false
    private var disableAttempts = 0
    private var restoreAttempts = 0
    private var lastRestoreAtMs = 0L

    @Synchronized
    fun onSnapshot(s: BatterySnapshot) {
        if (!settings.dhoParkingEnabled) {
            if (settings.dhoWeDisabled) restoreDho()
            resetMemory()
            return
        }

        val gear = s.gearMode ?: return
        val inP = gear == GEAR_P
        val speed = s.speedKmh?.toDouble()
        val now = System.currentTimeMillis()

        // ── Сторож: едем, а ДХО выключены нами ──
        if (settings.dhoWeDisabled && speed != null && speed > SPEED_RESTORE_KMH) {
            Log.w(TAG, "Скорость $speed км/ч при выключенных нами ДХО — возвращаю")
            restoreDho()
            skipThisPark = true     // не выключать повторно в этой стоянке
            timerFired = true
            lastInP = inP
            initialized = true
            return
        }

        // ── Первый снапшот после старта процесса ──
        if (!initialized) {
            initialized = true
            if (settings.dhoWeDisabled) {
                if (inP) {
                    // Всё ещё стоим, ДХО выключены нами ещё до перезапуска.
                    Log.i(TAG, "Старт: стоим в P, ДХО выключены нами ранее — продолжаю")
                    timerFired = true
                    lastInP = true
                } else {
                    Log.w(TAG, "Старт: не в P, а ДХО остались выключены нами — возвращаю")
                    restoreDho()
                    lastInP = false
                }
                return
            }
            lastInP = false  // если стартуем уже в P — отработает обычный вход в P
        }

        when {
            !lastInP && inP -> onEnterP(now)
            lastInP && !inP -> onLeaveP()
            !inP && settings.dhoWeDisabled -> restoreDho()   // догоняем неудавшийся возврат
            inP -> onParked(now)
        }

        lastInP = inP
    }

    /** Принудительный возврат (отключение сценария, закрытие приложения). */
    @Synchronized
    fun reset() {
        Log.i(TAG, "reset(): weDisabled=${settings.dhoWeDisabled}")
        restoreDho()
        resetMemory()
    }

    // ────────────────────────────────────────────────────────

    private fun onEnterP(now: Long) {
        pEnteredAtMs = now
        timerFired = false
        skipThisPark = false
        disableAttempts = 0

        if (settings.dhoWeDisabled) {
            timerFired = true   // уже выключено нами, повторно не трогаем
            return
        }

        when (readDhoState()) {
            null -> {
                skipThisPark = true
                Log.w(TAG, "Вход в P: состояние ДХО не прочиталось — в эту стоянку не трогаю")
            }
            DRL_STATE_OFF -> {
                skipThisPark = true
                Log.i(TAG, "Вход в P: ДХО уже выключены пользователем — не трогаю")
            }
            else -> Log.i(TAG, "Вход в P: ДХО включены, через ${PARK_DELAY_MS}мс выключу")
        }
    }

    private fun onLeaveP() {
        Log.i(TAG, "Выход из P")
        restoreDho()
        timerFired = false
        skipThisPark = false
    }

    private fun onParked(now: Long) {
        if (timerFired || skipThisPark) return
        if (now - pEnteredAtMs < PARK_DELAY_MS) return
        disableDho()
    }

    private fun disableDho() {
        // Флаг на диск ДО записи в машину.
        settings.dhoWeDisabled = true

        val ok = client.setInt(DEV_DRL, FID_DRL_SET, DRL_STATE_OFF) == true
        val after = readDhoState()
        Log.i(TAG, "disable: ok=$ok, после записи state=$after")

        if (after == DRL_STATE_OFF || (ok && after == null)) {
            timerFired = true
            return
        }

        disableAttempts++
        if (disableAttempts >= MAX_DISABLE_ATTEMPTS) {
            Log.e(TAG, "Не удалось выключить ДХО за $disableAttempts попыток — сдаюсь")
            timerFired = true
            // Если точно видим «включены» — менять нечего, флаг не нужен.
            // Если состояние неизвестно, флаг оставляем: возврат безвреден.
            if (after == DRL_STATE_ON) settings.dhoWeDisabled = false
        }
    }

    private fun restoreDho() {
        if (!settings.dhoWeDisabled) {
            restoreAttempts = 0
            return
        }

        val now = System.currentTimeMillis()
        if (restoreAttempts >= MAX_RESTORE_ATTEMPTS &&
            now - lastRestoreAtMs < SLOW_RESTORE_RETRY_MS) return
        lastRestoreAtMs = now

        val ok = client.setInt(DEV_DRL, FID_DRL_SET, DRL_STATE_ON) == true
        val after = readDhoState()
        Log.i(TAG, "restore: ok=$ok, после записи state=$after")

        if (after == DRL_STATE_ON || (ok && after == null)) {
            settings.dhoWeDisabled = false
            restoreAttempts = 0
        } else {
            restoreAttempts++
            Log.w(TAG, "Возврат ДХО не подтверждён (попытка $restoreAttempts)")
        }
    }

    private fun resetMemory() {
        initialized = false
        lastInP = false
        timerFired = false
        skipThisPark = false
        disableAttempts = 0
    }

    private fun readDhoState(): Int? {
        val v = client.getInt(DEV_DRL, FID_DRL_STATE) ?: return null
        return when (v) {
            1 -> DRL_STATE_ON
            2 -> DRL_STATE_OFF
            else -> null
        }
    }
}