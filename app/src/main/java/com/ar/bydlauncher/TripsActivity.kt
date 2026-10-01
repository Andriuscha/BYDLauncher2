package com.ar.bydlauncher

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ar.bydlauncher.db.TripRecord
import com.ar.bydlauncher.db.TripRepository
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripsActivity : Activity() {

    companion object {
        private const val TAG = "TripsActivity"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var tripRepository: TripRepository
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tripRepository = TripRepository(this)

        // Собираем layout программно — не плодим xml ради одного экрана.
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0D1B2A.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        val title = TextView(this).apply {
            text = getString(R.string.trips_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        header.addView(title)

        val back = TextView(this).apply {
            text = getString(R.string.trips_back)
            setTextColor(0xFF5BE05B.toInt())
            textSize = 16f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        }
        header.addView(back)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val scroll = ScrollView(this).apply {
            addView(container)
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        root.addView(rootLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)

        loadTrips()
    }

    private fun loadTrips() {
        scope.launch {
            val trips = tripRepository.getAllFinished()
            Log.i(TAG, "Loaded ${trips.size} finished trips")
            renderTrips(trips)
        }
    }

    private fun renderTrips(trips: List<TripRecord>) {
        container.removeAllViews()

        if (trips.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.trips_empty)
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
            }
            container.addView(empty)
            return
        }

        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        for (t in trips) {
            val card = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(0xFF1A2A3A.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp

                text = buildString {
                    append(sdf.format(Date(t.startedAt)))
                    if (t.endedAt != null) {
                        append("  →  ")
                        append(sdf.format(Date(t.endedAt)))
                    }
                    append('\n')
                    append("%.1f км · %.1f кВт·ч · %.1f кВт·ч/100км · %d мин"
                        .format(t.distanceKm, t.energyKwh, t.consumption, t.durationMin))
                    append('\n')
                    if (t.startSoc != null || t.endSoc != null) {
                        append("SOC: ${t.startSoc ?: "—"}% → ${t.endSoc ?: "—"}%  ")
                    }
                    if (t.avgSpeedKmh != null || t.maxSpeedKmh != null) {
                        append("Скорость: ср %.1f / макс %.1f км/ч"
                            .format(t.avgSpeedKmh ?: 0.0, t.maxSpeedKmh ?: 0.0))
                    }
                    append('\n')
                    if (t.outsideTempAvgC != null || t.battTempAvgC != null) {
                        append("t снаружи: %.1f°C · t батареи: %.1f°C"
                            .format(t.outsideTempAvgC ?: 0.0, t.battTempAvgC ?: 0.0))
                    }
                }
            }
            container.addView(card)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        tripRepository.close()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}