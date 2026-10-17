package com.ar.bydlauncher.db

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

/**
 * Возвращает эмпирический "базовый" расход (кВт·ч/100км) из недавних
 * длинных поездок. Используется как fallback, если нет km/%.
 *
 * Приоритет: km/% > baseRecent > BASE_DEFAULT.
 *
 * Окно: 7 дней, поездки длиннее 20 км, взвешивание по (distance × exp(-age/τ)),
 * τ = 3 дня.
 */
class BaseConsumptionRepository(context: Context) {

    private val tripRepo = TripRepository(context)

    companion object {
        private const val TAG = "BaseConsumptionRepo"

        const val TAU_MS = 3L * 24 * 3600_000L       // 3 дня
        const val MAX_AGE_MS = 7L * 24 * 3600_000L   // не старше 7 дней
        const val MIN_KM = 10.0                       // мин. длина поездки
        const val MAX_SAMPLES = 10
        const val MIN_CONSUMPTION = 5.0
        const val MAX_CONSUMPTION = 30.0
    }

    suspend fun getRecentBase(): Double? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val from = now - MAX_AGE_MS

            val candidates = tripRepo.getRecentFinished(
                fromMs = from,
                minKm = MIN_KM,
                limit = MAX_SAMPLES
            ).filter {
                it.consumption in MIN_CONSUMPTION..MAX_CONSUMPTION
            }

            if (candidates.isEmpty()) return@withContext null

            val weighted = candidates.map { t ->
                val age = now - (t.endedAt ?: t.startedAt)
                val w = t.distanceKm * exp(-age.toDouble() / TAU_MS)
                w to t.consumption
            }
            val sumW = weighted.sumOf { it.first }
            if (sumW <= 0.0) return@withContext null

            val base = weighted.sumOf { it.first * it.second } / sumW
            Log.i(TAG, "getRecentBase: ${"%.2f".format(base)} кВт·ч/100км, " +
                    "samples=${candidates.size}")
            base
        } catch (t: Throwable) {
            Log.e(TAG, "getRecentBase failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }
}