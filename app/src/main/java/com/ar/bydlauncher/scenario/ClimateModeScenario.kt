package com.ar.bydlauncher.scenario

import android.util.Log
import com.ar.bydlauncher.byd.AutoserviceClient
import com.ar.bydlauncher.byd.BatterySnapshot

/**
 * Сценарий 3: «Эко / комфорт климат».
 *
 * FID (проверено):
 *   Dev = 1000 (DEV_AC)
 *   READ  режим:   1301291032 (SET_AC_AUTO_AIR)     1=Эко, 2=Комфорт
 *   WRITE режим:   1311768588 (SET_AC_AUTO_AIR_SET) 1=Эко, 2=Комфорт
 *   READ  скорость: 1077936156 (AC_WIND_LEVEL)
 *   WRITE скорость: 501219340  (AC_WIND_LEVEL_SET)  1..7
 */
class ClimateModeScenario(
    private val settings: ScenarioSettingsRepository,
    private val client: AutoserviceClient,
    private val onUserIntervention: () -> Unit = {}
) {

    companion object {
        private const val TAG = "ClimateModeScenario"

        private const val DEV_AC = AutoserviceClient.DEV_AC

        private const val FID_MODE_READ = 1301291032
        private const val FID_MODE_SET  = 1311768588
        private const val FID_FAN_READ  = 1077936156
        private const val FID_FAN_SET   = 501219340

        private const val MODE_ECO     = 1
        private const val MODE_COMFORT = 2

        private const val START_OUTSIDE_BELOW = 8
        private const val START_INSIDE_BELOW  = 10

        private const val THRESHOLD_FAN4_HEAT = 14
        private const val THRESHOLD_ECO_HEAT  = 17
        private const val THRESHOLD_DEADZONE_HIGH = 17
        private const val THRESHOLD_DEADZONE_LOW  = 15
        private const val THRESHOLD_COMFORT_BELOW = 15
        private const val THRESHOLD_FAN4_BELOW = 13
        private const val THRESHOLD_FAN5_BELOW = 12

        private const val DELAY_MS = 30_000L
    }

    private var activated: Boolean = false
    private var initialized: Boolean = false
    private var accWasOff: Boolean = true

    private var expectedMode: Int? = null
    private var expectedFan: Int? = null

    private var lastInsideTemp: Int? = null

    private data class Target(val mode: Int, val fan: Int)

    private var pendingTarget: Target? = null
    private var pendingSinceMs: Long = 0L

    fun onSnapshot(s: BatterySnapshot) {
        // ── ACC ──
        val acc = s.accOn
        if (acc == false) {
            if (activated) Log.i(TAG, "ACC выключен — сбрасываю сценарий климата")
            reset()
            accWasOff = true
            return
        }
        if (accWasOff && acc == true) {
            accWasOff = false
        }

        // ── Сценарий выключен ──
        if (!settings.climateEcoEnabled) {
            reset()
            return
        }

        // ── Первый снапшот ──
        if (!initialized) {
            initialized = true
            val outside = s.tempOutsideC
            val inside  = s.tempInsideC

            if (outside == null || inside == null) {
                Log.w(TAG, "Нет данных о температуре: outside=$outside inside=$inside")
                return
            }

            if (outside < START_OUTSIDE_BELOW && inside < START_INSIDE_BELOW) {
                Log.i(TAG, "Старт: outside=$outside < $START_OUTSIDE_BELOW, " +
                        "inside=$inside < $START_INSIDE_BELOW → Комфорт + скорость 5")
                applyTarget(Target(MODE_COMFORT, 5), "start")
                activated = true
            } else {
                Log.i(TAG, "Старт: условия не выполнены (outside=$outside, inside=$inside)")
                activated = false
            }
            return
        }

        if (!activated) return

        val inside = s.tempInsideC ?: return
        val prevInside = lastInsideTemp
        lastInsideTemp = inside

        // ── Целевое состояние по температуре ──
        val target = computeTarget(inside, prevInside)
        if (target == null) {
            if (pendingTarget != null) {
                Log.i(TAG, "Температура вернулась — отменяю отложенное переключение")
                pendingTarget = null
            }
            return
        }

        // ── Уже в целевом состоянии ──
        if (isCurrentTarget(target)) {
            pendingTarget = null
            return
        }

        // ── Таймер ──
        val now = System.currentTimeMillis()
        if (pendingTarget == null || pendingTarget != target) {
            Log.i(TAG, "Запускаю таймер 30с: mode=${target.mode} fan=${target.fan}")
            pendingTarget = target
            pendingSinceMs = now
            return
        }

        if (now - pendingSinceMs >= DELAY_MS) {
            Log.i(TAG, "Таймер сработал — переключаю: mode=${target.mode} fan=${target.fan}")
            applyTarget(target, "threshold")
            pendingTarget = null
        }
    }

    private fun computeTarget(inside: Int, prev: Int?): Target? {
        // Нагрев (или стабильно)
        if (prev == null || inside >= prev) {
            return when {
                inside >= THRESHOLD_ECO_HEAT  -> Target(MODE_ECO, 3)
                inside >= THRESHOLD_FAN4_HEAT -> Target(MODE_COMFORT, 4)
                else -> null
            }
        }

        // Охлаждение
        return when {
            inside in THRESHOLD_DEADZONE_LOW..THRESHOLD_DEADZONE_HIGH -> null
            inside < THRESHOLD_FAN5_BELOW -> Target(MODE_COMFORT, 5)
            inside < THRESHOLD_FAN4_BELOW -> Target(MODE_COMFORT, 4)
            inside < THRESHOLD_COMFORT_BELOW -> Target(MODE_COMFORT, 3)
            else -> null
        }
    }

    private fun isCurrentTarget(target: Target): Boolean {
        val curMode = client.getInt(DEV_AC, FID_MODE_READ)
        val curFan  = client.getInt(DEV_AC, FID_FAN_READ)
        return curMode == target.mode && curFan == target.fan
    }

    private fun applyTarget(target: Target, reason: String) {
        val curMode = client.getInt(DEV_AC, FID_MODE_READ)
        val curFan  = client.getInt(DEV_AC, FID_FAN_READ)

        Log.i(TAG, "[$reason] текущее: mode=$curMode fan=$curFan; " +
                "целевое: mode=${target.mode} fan=${target.fan}; " +
                "expected: mode=$expectedMode fan=$expectedFan")

        // ── Проверка вмешательства пользователя ──
        if (expectedMode != null && curMode != expectedMode) {
            Log.w(TAG, "Пользователь изменил режим ($expectedMode → $curMode) — отключаю сценарий")
            settings.climateEcoEnabled = false
            onUserIntervention()
            reset()
            return
        }
        if (expectedFan != null && curFan != expectedFan) {
            Log.w(TAG, "Пользователь изменил скорость ($expectedFan → $curFan) — отключаю сценарий")
            settings.climateEcoEnabled = false
            onUserIntervention()
            reset()
            return
        }

        // ── SET ──
        val okMode = client.setInt(DEV_AC, FID_MODE_SET, target.mode)
        val okFan  = client.setInt(DEV_AC, FID_FAN_SET, target.fan)

        Log.i(TAG, "[$reason] SET mode=${target.mode} ok=$okMode; SET fan=${target.fan} ok=$okFan")

        expectedMode = target.mode
        expectedFan  = target.fan
    }

    fun reset() {
        activated = false
        initialized = false
        pendingTarget = null
        pendingSinceMs = 0L
        expectedMode = null
        expectedFan = null
        lastInsideTemp = null
    }
}