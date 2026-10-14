package com.ar.bydlauncher.byd

/**
 * Единая точка физических коэффициентов для модели прогноза пробега.
 *
 * Используется:
 *  • RangeEstimator — для fallback-ветки (когда нет эмпирики).
 *  • KmPerPctRepository — для нормализации км/% к эталонной температуре.
 *
 * Константы откалиброваны по 48 реальным поездкам (бэкап 13.10.2026):
 * средний расход ~13.5 кВт·ч/100км при +11°C и 50 км/ч — модель занижала
 * на 11–18%, поэтому BASE_DEFAULT поднят с 11.89 до 13.5, speedK смягчён,
 * tempK усилен на холоде.
 */
object RangeMath {

    /** Базовый расход на 100 км при эталонных условиях (+20°C, 50 км/ч). */
    const val BASE_DEFAULT_KWH100 = 13.5

    private val TEMP_POINTS = listOf(
        25   to 1.00,
        20   to 1.00,
        15   to 1.04,
        10   to 1.10,
        5    to 1.18,
        0    to 1.30,
        -5   to 1.48,
        -10  to 1.70,
        -15  to 2.00,
        -20  to 2.35,
        -25  to 2.80
    )

    private val SPEED_POINTS = listOf(
        20f  to 0.95f,
        50f  to 0.95f,
        70f  to 1.00f,
        90f  to 1.15f,
        110f to 1.45f,
        130f to 1.85f
    )

    fun tempCoefficient(tempOut: Int): Double {
        if (tempOut >= 25) return 1.00
        if (tempOut <= -25) return 2.80
        val sorted = TEMP_POINTS.sortedBy { it.first }
        for (i in 0 until sorted.size - 1) {
            val (t1, k1) = sorted[i]
            val (t2, k2) = sorted[i + 1]
            if (tempOut in t1..t2) {
                val ratio = (tempOut - t1).toDouble() / (t2 - t1)
                return k1 + ratio * (k2 - k1)
            }
        }
        return 1.00
    }

    fun speedCoefficient(speedKmh: Float): Double {
        if (speedKmh <= SPEED_POINTS.first().first) return SPEED_POINTS.first().second.toDouble()
        if (speedKmh >= SPEED_POINTS.last().first)  return SPEED_POINTS.last().second.toDouble()
        for (i in 0 until SPEED_POINTS.size - 1) {
            val (v1, k1) = SPEED_POINTS[i]
            val (v2, k2) = SPEED_POINTS[i + 1]
            if (speedKmh in v1..v2) {
                val t = (speedKmh - v1) / (v2 - v1)
                return (k1 + t * (k2 - k1)).toDouble()
            }
        }
        return 1.0
    }
}
