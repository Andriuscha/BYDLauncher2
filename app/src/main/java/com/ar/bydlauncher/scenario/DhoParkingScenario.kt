package com.ar.bydlauncher.scenario

import android.util.Log
import com.ar.bydlauncher.byd.AutoserviceClient
import com.ar.bydlauncher.byd.BatterySnapshot

/**
 * Сценарий: выключать ДХО при переводе КПП в P.
 * Возвращать как было при выходе из P.
 *
 * FID (экспериментально подтверждено):
 *   - READ  : Dev=1001, FID=985661476  (LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE)
 *             1 = ДХО включены, 2 = ДХО выключены, 0 = неизвестно
 *   - WRITE : Dev=1001, FID=1125122118 (LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE_SET)
 *             1 = включить, 2 = выключить
 *
 * Логика:
 *   - Читаем GEARBOX_AUTO_MODE_TYPE из снапшота (P = 1).
 *   - При переходе НЕ-P → P: сохраняем состояние ДХО, запускаем таймер 2 сек.
 *   - Если через 2 сек всё ещё в P — пишем 2 (выключить).
 *   - При переходе P → НЕ-P: отменяем таймер, возвращаем сохранённое состояние.
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
    }

    private var savedDhoState: Int = DRL_STATE_ON
    private var weDisabledDho: Boolean = false
    private var lastInP: Boolean = false
    private var pEnteredAtMs: Long = 0L
    private var timerFired: Boolean = false

    fun onSnapshot(s: BatterySnapshot) {
        if (!settings.dhoParkingEnabled) {
            if (lastInP || weDisabledDho) reset()
            return
        }

        val gear = s.gearMode ?: return
        val inP = gear == GEAR_P
        val now = System.currentTimeMillis()

        when {
            !lastInP && inP -> {
                Log.i(TAG, "Переход в P: запускаю таймер ${PARK_DELAY_MS}мс")
                pEnteredAtMs = now
                timerFired = false
                savedDhoState = readDhoState() ?: DRL_STATE_ON
                Log.i(TAG, "Сохранено состояние ДХО = $savedDhoState")
            }

            lastInP && !inP -> {
                Log.i(TAG, "Выход из P: возвращаю ДХО = $savedDhoState")
                if (weDisabledDho) {
                    writeDhoState(savedDhoState)
                    weDisabledDho = false
                }
                timerFired = false
            }

            inP && !timerFired -> {
                val elapsed = now - pEnteredAtMs
                if (elapsed >= PARK_DELAY_MS) {
                    Log.i(TAG, "Таймер сработал (${elapsed}мс), выключаю ДХО")
                    writeDhoState(DRL_STATE_OFF)
                    weDisabledDho = true
                    timerFired = true
                }
            }
        }

        lastInP = inP
    }

    fun reset() {
        Log.i(TAG, "reset(): возвращаю ДХО = $savedDhoState (weDisabledDho=$weDisabledDho)")
        if (weDisabledDho) {
            writeDhoState(savedDhoState)
        }
        weDisabledDho = false
        lastInP = false
        timerFired = false
    }

    private fun readDhoState(): Int? {
        val v = client.getInt(DEV_DRL, FID_DRL_STATE) ?: return null
        return when (v) {
            1 -> DRL_STATE_ON
            2 -> DRL_STATE_OFF
            else -> null
        }
    }

    private fun writeDhoState(state: Int) {
        val ok = client.setInt(DEV_DRL, FID_DRL_SET, state)
        Log.i(TAG, "writeDhoState($state) → ok=$ok")
    }
}