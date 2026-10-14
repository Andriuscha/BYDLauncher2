package com.ar.bydlauncher.db

import com.ar.bydlauncher.byd.RouteProfile

/**
 * Суммарные данные за "текущий день".
 * День = с 06:00 текущего дня до 05:59 следующего дня.
 */
data class TripDaySummary(
    val startOfDayMs: Long,
    val endOfDayMs: Long,
    val distanceKm: Double,
    val energyKwh: Double,
    val durationMin: Int,
    val consumptionKwh100: Double,
    val tripsCount: Int,
    val routeProfile: RouteProfile? = null   // задел на будущее
) {
    companion object {
        fun empty(startOfDayMs: Long): TripDaySummary = TripDaySummary(
            startOfDayMs = startOfDayMs,
            endOfDayMs = startOfDayMs + 24 * 60 * 60 * 1000L,
            distanceKm = 0.0,
            energyKwh = 0.0,
            durationMin = 0,
            consumptionKwh100 = 0.0,
            tripsCount = 0
        )
    }
}