package com.ar.bydlauncher.scenario

import android.util.Log
import com.ar.bydlauncher.byd.AutoserviceClient
import com.ar.bydlauncher.byd.BatterySnapshot

/**
 * Центральный движок сценариев.
 * Прогоняет снапшот через все активные сценарии.
 */
class ScenarioEngine(
    private val settings: ScenarioSettingsRepository,
    private val client: AutoserviceClient
) {

    companion object {
        private const val TAG = "ScenarioEngine"
    }

    private val dhoScenario = DhoParkingScenario(settings, client)

    fun onSnapshot(s: BatterySnapshot) {
        runCatching { dhoScenario.onSnapshot(s) }
            .onFailure { Log.e(TAG, "dhoScenario failed", it) }
    }

    fun resetAll() {
        runCatching { dhoScenario.reset() }
            .onFailure { Log.e(TAG, "dhoScenario.reset failed", it) }
    }
}