package com.ar.bydlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ar.bydlauncher.db.TripDayGroup
import com.ar.bydlauncher.db.TripRecord
import com.ar.bydlauncher.db.TripRepository
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class TripsActivity : Activity() {

    companion object {
        private const val TAG = "TripsActivity"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var tripRepository: TripRepository
    private lateinit var listContainer: LinearLayout

    private val expandedDays = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trips)

        tripRepository = TripRepository(this)
        listContainer = findViewById(R.id.tripsList)

        findViewById<View>(R.id.tripsClose).setOnClickListener { finish() }

        loadTrips()
    }

    private fun loadTrips() {
        scope.launch {
            val groups = tripRepository.getGroupedByDays()
            Log.i(TAG, "Loaded ${groups.size} day groups")
            renderTrips(groups)
        }
    }

    private fun renderTrips(groups: List<TripDayGroup>) {
        listContainer.removeAllViews()

        if (groups.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.trips_empty)
                setTextColor(0xFF6B7180.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
                typeface = android.graphics.Typeface.MONOSPACE
            }
            listContainer.addView(empty)
            return
        }

        for (group in groups) {
            listContainer.addView(buildDayGroupRow(group))
        }
    }

    private fun buildDayGroupRow(group: TripDayGroup): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF1A1D24.toInt())
            isClickable = true
            isFocusable = true
        }

        val dayFmt = SimpleDateFormat("dd.MM.yyyy (EEEE)", Locale("ru"))
        val dateStr = dayFmt.format(Date(group.dayStartMs)).uppercase()

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = dateStr
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleView)

        val arrow = TextView(this).apply {
            text = if (expandedDays.contains(group.dayStartMs)) "▲" else "▼"
            setTextColor(0xFF2E93FA.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        titleRow.addView(arrow)

        headerCard.addView(titleRow)

        val summary = TextView(this).apply {
            text = "🚗 %.1f км  ·  ⏱ %d ч %02d мин  ·  ⚡ %.1f кВт·ч (%.1f кВт·ч/100км)"
                .format(group.totalDistanceKm, group.totalDurationMin / 60, group.totalDurationMin % 60, group.totalEnergyKwh, group.avgConsumption)
            setTextColor(0xFF4FD1C5.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        }
        headerCard.addView(summary)

        container.addView(headerCard)

        val detailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expandedDays.contains(group.dayStartMs)) View.VISIBLE else View.GONE
            setPadding(0, dp(4), 0, 0)
        }

        for (trip in group.trips) {
            detailsContainer.addView(buildTripDetailRow(trip))
        }
        container.addView(detailsContainer)

        headerCard.setOnClickListener {
            val isNowVisible = detailsContainer.visibility == View.VISIBLE
            detailsContainer.visibility = if (isNowVisible) View.GONE else View.VISIBLE
            arrow.text = if (isNowVisible) "▼" else "▲"

            if (isNowVisible) expandedDays.remove(group.dayStartMs)
            else expandedDays.add(group.dayStartMs)
        }

        return container
    }

    private fun buildTripDetailRow(t: TripRecord): View {
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val startTime = sdfTime.format(Date(t.startedAt))
        val endTime = t.endedAt?.let { sdfTime.format(Date(it)) } ?: "—"

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0xFF14171E.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2) }
            isClickable = true
            isFocusable = true
        }

        val timeRow = TextView(this).apply {
            text = "$startTime → $endTime"
            setTextColor(0xFFB0B4BD.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.05f
        }
        card.addView(timeRow)

        val metrics = TextView(this).apply {
            text = "%.1f км · %.1f кВт·ч · %.1f кВт·ч/100км · %d мин"
                .format(t.distanceKm, t.energyKwh, t.consumption, t.durationMin)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
        }
        card.addView(metrics)

        val sub1 = StringBuilder()
        if (t.startSoc != null || t.endSoc != null) {
            sub1.append("SOC: ${t.startSoc ?: "—"}% → ${t.endSoc ?: "—"}%  ")
        }
        if (t.avgSpeedKmh != null || t.maxSpeedKmh != null) {
            sub1.append("Скорость: ср %.1f / макс %.1f км/ч".format(t.avgSpeedKmh ?: 0.0, t.maxSpeedKmh ?: 0.0))
        }
        if (sub1.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = sub1.toString()
                setTextColor(0xFFB0B4BD.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }

        val sub2 = StringBuilder()
        if (t.outsideTempAvgC != null) sub2.append("t снаружи: %.1f°C".format(t.outsideTempAvgC))
        if (t.battTempAvgC != null) {
            if (sub2.isNotEmpty()) sub2.append(" · ")
            sub2.append("t батареи: %.1f°C".format(t.battTempAvgC))
        }
        if (sub2.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = sub2.toString()
                setTextColor(0xFFB0B4BD.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, dp(2), 0, 0)
            })
        }

        val mapContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(250).toInt()
            ).apply { topMargin = dp(10) }
            visibility = View.GONE
            setBackgroundColor(0xFF0A0E18.toInt())
        }

        var isMapLoaded = false

        card.setOnClickListener {
            if (mapContainer.visibility == View.GONE) {
                mapContainer.visibility = View.VISIBLE
                if (!isMapLoaded) {
                    isMapLoaded = true
                    val wv = WebView(this@TripsActivity)
                    wv.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    wv.setBackgroundColor(0xFF0A0E18.toInt())
                    wv.settings.javaScriptEnabled = true
                    wv.webViewClient = WebViewClient()

                    mapContainer.addView(wv)
                    loadMapIntoWebView(wv, t.id)
                }
            } else {
                mapContainer.visibility = View.GONE
            }
        }

        val overlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                val intent = Intent(this@TripsActivity, TripMapActivity::class.java)
                intent.putExtra("TRIP_ID", t.id)
                startActivity(intent)
            }
        }
        mapContainer.addView(overlay)

        card.addView(mapContainer)
        return card
    }

    private fun loadMapIntoWebView(webView: WebView, tripId: Long) {
        scope.launch {
            val points = tripRepository.getTrackPoints(tripId)
            if (points.isEmpty()) {
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                        <style>
                            body { background-color: #0A0E18; color: #B0B4BD; font-family: monospace; 
                                   display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
                        </style>
                    </head>
                    <body>
                        <h3>Нет GPS-данных</h3>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                return@launch
            }

            val coordsJson = points.joinToString(",") {
                val speed = it.speedKmh ?: 0f
                "{lat: ${it.lat}, lon: ${it.lon}, speed: $speed}"
            }

            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
                    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                    <style>
                        body { margin: 0; padding: 0; background-color: #0A0E18; }
                        #map { width: 100vw; height: 100vh; }
                    </style>
                </head>
                <body>
                    <div id="map"></div>
                    <script>
                        var map = L.map('map', { 
                            zoomControl: false,
                            dragging: false,
                            touchZoom: false,
                            scrollWheelZoom: false,
                            doubleClickZoom: false,
                            boxZoom: false,
                            keyboard: false 
                        });
                        
                        L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
                            attribution: '&copy; OpenStreetMap &copy; CARTO',
                            subdomains: 'abcd',
                            maxZoom: 19
                        }).addTo(map);

                        var points = [$coordsJson];
                        if (points.length > 0) {
                            var latlngs = [];
                            
                            for (var i = 0; i < points.length; i++) {
                                var p1 = points[i];
                                latlngs.push([p1.lat, p1.lon]);
                                
                                if (i > 0) {
                                    var p0 = points[i - 1];
                                    var avgSpeed = (p0.speed + p1.speed) / 2;
                                    
                                    var ratio = Math.min(avgSpeed / 100.0, 1.0);
                                    var r = Math.round(255 * ratio);
                                    var g = Math.round(255 * (1 - ratio));
                                    var color = 'rgb(' + r + ',' + g + ',0)';
                                    
                                    L.polyline([[p0.lat, p0.lon], [p1.lat, p1.lon]], {
                                        color: color,
                                        weight: 5,
                                        opacity: 0.8
                                    }).addTo(map);
                                }
                            }
                            
                            var polyline = L.polyline(latlngs, {opacity: 0});
                            map.fitBounds(polyline.getBounds(), {padding: [10, 10]});
                            
                            L.circleMarker(latlngs[0], {radius: 6, fillColor: '#28C76F', color: '#fff', weight: 1.5, fillOpacity: 1}).addTo(map);
                            if (latlngs.length > 1) {
                                L.circleMarker(latlngs[latlngs.length - 1], {radius: 6, fillColor: '#EA5455', color: '#fff', weight: 1.5, fillOpacity: 1}).addTo(map);
                            }
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()

            webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        tripRepository.close()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}