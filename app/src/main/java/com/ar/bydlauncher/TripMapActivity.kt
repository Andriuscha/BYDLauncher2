package com.ar.bydlauncher

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ar.bydlauncher.db.TripRepository
import kotlinx.coroutines.*

class TripMapActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var webView: WebView
    private lateinit var repo: TripRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = TripRepository(this)
        webView = WebView(this)
        webView.setBackgroundColor(0xFF0A0E18.toInt())
        setContentView(webView)

        val tripId = intent.getLongExtra("TRIP_ID", -1L)
        if (tripId == -1L) {
            finish()
            return
        }

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()

        loadTrack(tripId)
    }

    private fun loadTrack(tripId: Long) {
        scope.launch {
            val points = repo.getTrackPoints(tripId)
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
                        <h2>Нет GPS-данных для этой поездки</h2>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                return@launch
            }

            // Формируем массив объектов: [{lat, lon, speed}, ...]
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
                        var map = L.map('map', { zoomControl: false });
                        
                        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19,
    opacity: 0.6 // Делаем карту полупрозрачной, чтобы она сливалась с черным фоном
}).addTo(map);

                        var points = [$coordsJson];
                        if (points.length > 0) {
                            var latlngs = [];
                            
                            // Отрисовка цветных сегментов
                            for (var i = 0; i < points.length; i++) {
                                var p1 = points[i];
                                latlngs.push([p1.lat, p1.lon]);
                                
                                if (i > 0) {
                                    var p0 = points[i - 1];
                                    // Берем среднюю скорость между двумя точками
                                    var avgSpeed = (p0.speed + p1.speed) / 2;
                                    
                                    // Градиент: 0 км/ч -> зеленый (0, 255, 0), 100+ км/ч -> красный (255, 0, 0)
                                    var ratio = Math.min(avgSpeed / 100.0, 1.0);
                                    var r = Math.round(255 * ratio);
                                    var g = Math.round(255 * (1 - ratio));
                                    var color = 'rgb(' + r + ',' + g + ',0)';
                                    
                                    var segment = L.polyline([[p0.lat, p0.lon], [p1.lat, p1.lon]], {
                                        color: color,
                                        weight: 5,
                                        opacity: 0.8
                                    }).addTo(map);
                                }
                            }
                            
                            // Автоматический зум под весь маршрут
                            var polyline = L.polyline(latlngs, {opacity: 0}); // невидимая для bounds
                            map.fitBounds(polyline.getBounds(), {padding: [30, 30]});
                            
                            // Метка старта
                            L.circleMarker(latlngs[0], {radius: 8, fillColor: '#28C76F', color: '#fff', weight: 2, fillOpacity: 1}).addTo(map).bindPopup('Старт');
                            
                            // Метка финиша
                            if (latlngs.length > 1) {
                                L.circleMarker(latlngs[latlngs.length - 1], {radius: 8, fillColor: '#EA5455', color: '#fff', weight: 2, fillOpacity: 1}).addTo(map).bindPopup('Финиш');
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
        repo.close()
        webView.destroy()
        super.onDestroy()
    }
}