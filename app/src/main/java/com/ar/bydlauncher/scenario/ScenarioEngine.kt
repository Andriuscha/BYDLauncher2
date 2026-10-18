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
    private val client: AutoserviceClient,
    private val onClimateUserIntervention: () -> Unit = {}
) {

    companion object {
        private const val TAG = "ScenarioEngine"
    }

    private val dhoScenario = DhoParkingScenario(settings, client)
    private val climateScenario = ClimateModeScenario(settings, client, onClimateUserIntervention)

    fun onSnapshot(s: BatterySnapshot) {
        runCatching { dhoScenario.onSnapshot(s) }
            .onFailure { Log.e(TAG, "dhoScenario failed", it) }

        runCatching { climateScenario.onSnapshot(s) }
            .onFailure { Log.e(TAG, "climateScenario failed", it) }
    }

    fun resetAll() {
        runCatching { dhoScenario.reset() }
            .onFailure { Log.e(TAG, "dhoScenario.reset failed", it) }

        runCatching { climateScenario.reset() }
            .onFailure { Log.e(TAG, "climateScenario.reset failed", it) }
    }
}