package com.ar.bydlauncher.byd

/**
 * Профиль маршрута — три категории по средней скорости за 10 минут.
 *
 * Коэффициенты:
 *  • extraK       — множитель расхода (эмпирика владельца).
 *  • weatherWeight — насколько сильно погода влияет на расход в этом профиле:
 *                    город — 1.0 (климат, стёкла, прогрев),
 *                    МКАД  — 0.7 (скорость сглаживает),
 *                    трасса — 0.4 (аэродинамика доминирует, погода второстепенна).
 *  • speedRef     — эталонная скорость профиля для относительного speedK.
 */
enum class RouteProfile(
    val label: String,
    val extraK: Double,
    val weatherWeight: Double,
    val speedRef: Double
) {
    UNKNOWN("—",       1.00, 0.7,  60.0),
    CITY("ГОРОД",     0.99, 1.0,  30.0),
    MKAD("МКАД",      1.10, 0.7,  85.0),
    HIGHWAY("ТРАССА", 1.25, 0.4, 110.0);

    val isDefault: Boolean
        get() = this == UNKNOWN
}

/**
 * Детектор профиля маршрута.
 * Хранит окно последних скоростей (по умолчанию 10 минут)
 * и по средней скорости определяет профиль.
 */
class RouteProfileDetector(
    private val windowMs: Long = 10 * 60_000L
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var lastProfile: RouteProfile = RouteProfile.UNKNOWN

    /** Добавить точку скорости. Вызывается каждую секунду. */
    @Synchronized
    fun addSpeed(ts: Long, speedKmh: Float) {
        samples.addLast(ts to speedKmh)
        while (samples.isNotEmpty() && samples.first().first < ts - windowMs) {
            samples.removeFirst()
        }

        if (samples.size < 3) return
        lastProfile = computeProfile()
    }

    @Synchronized
    fun getProfile(): RouteProfile = lastProfile

    @Synchronized
    fun getAverageSpeed(): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0f
        for ((_, s) in samples) sum += s
        return sum / samples.size
    }

    /**
     * Профиль по средней скорости за окно.
     *  - < 30 км/ч   → ГОРОД
     *  - 30..60      → МКАД
     *  - > 60        → ТРАССА
     */
    private fun computeProfile(): RouteProfile {
        val avg = getAverageSpeed()

        return when {
            avg < 30f -> RouteProfile.CITY
            avg < 60f -> RouteProfile.MKAD
            else      -> RouteProfile.HIGHWAY
        }
    }

    @Synchronized
    fun reset() {
        samples.clear()
        lastProfile = RouteProfile.UNKNOWN
    }
}