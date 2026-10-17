package com.ar.bydlauncher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load
import com.ar.bydlauncher.adb.AdbClient
import com.ar.bydlauncher.byd.AutoserviceClient
import com.ar.bydlauncher.byd.BatterySnapshot
import com.ar.bydlauncher.byd.BmsReader
import com.ar.bydlauncher.byd.SohCalculator
import com.ar.bydlauncher.byd.TripDetector
import com.ar.bydlauncher.byd.FullChargeTracker
import com.ar.bydlauncher.byd.RangeEstimator
import com.ar.bydlauncher.location.CarLocationProvider
import com.ar.bydlauncher.ui.CaptionMaskOverlay
import com.ar.bydlauncher.ui.NavigatorPanelController
import com.ar.bydlauncher.weather.Weather
import com.ar.bydlauncher.weather.WeatherClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.ar.bydlauncher.db.TripRecord
import com.ar.bydlauncher.db.TripRepository
import com.ar.bydlauncher.db.TripDaySummary
import com.ar.bydlauncher.db.ChargingRecord
import com.ar.bydlauncher.db.ChargingRepository
import com.ar.bydlauncher.service.BydBackgroundService
import com.ar.bydlauncher.byd.RangeEstimator.Factor
import com.ar.bydlauncher.byd.RouteProfile
import com.ar.bydlauncher.byd.RouteProfileDetector
import com.ar.bydlauncher.byd.CalibrationRepository
import com.ar.bydlauncher.scenario.ScenarioEngine
import com.ar.bydlauncher.scenario.ScenarioSettingsRepository
import com.ar.bydlauncher.db.KmPerPctRepository
import com.ar.bydlauncher.db.BaseConsumptionRepository
import com.ar.bydlauncher.backup.TelegramBackupUploader

class MainActivity : Activity() {

    private lateinit var adb: AdbClient
    private lateinit var ac: AutoserviceClient
    private lateinit var reader: BmsReader

    // ── BMS Dashboard ──
    private lateinit var bmsSoc: TextView
    private lateinit var bmsPower: TextView
    private lateinit var bmsPowerState: TextView
    private lateinit var bmsRange: TextView
    private lateinit var bmsRangeMax: TextView
    private lateinit var bmsRangeMin: TextView
    private lateinit var bmsRangeFactors: TextView
    private lateinit var bmsCellMax: TextView
    private lateinit var bmsCellMin: TextView
    private lateinit var bmsTempMax: TextView
    private lateinit var bmsTempMin: TextView
    private lateinit var bmsSoh: TextView
    private lateinit var bmsDelta: TextView
    private lateinit var bmsHv: TextView
    private lateinit var bmsCurrent: TextView
    private lateinit var bmsV12: TextView
    private lateinit var bmsTripDistance: TextView
    private lateinit var bmsTripConsumption: TextView
    private lateinit var bmsTripEnergy: TextView
    private lateinit var bmsFullRange: TextView
    private lateinit var bmsOdometer: TextView
    private lateinit var bmsLifetimeKwh: TextView
    private lateinit var bmsRouteProfile: TextView
    private lateinit var bmsRouteProfileBadge: TextView

    // ── Кликабельные контейнеры виджетов ──
    private lateinit var weatherCard: View
    private lateinit var bmsChipTop: View
    private lateinit var bmsChipMiddle: View
    private lateinit var bmsChipBottom: View
    private lateinit var sohBlock: View

    // ── Погодный виджет ──
    private lateinit var wIcon: ImageView
    private lateinit var wLocName: TextView
    private lateinit var wTemp: TextView
    private lateinit var wCond: TextView
    private lateinit var wDay: TextView
    private lateinit var wNight: TextView
    private lateinit var wOut: TextView
    private lateinit var wIn: TextView
    private lateinit var wExtra: TextView

    private lateinit var coilLoader: ImageLoader

    private lateinit var navContainer: FrameLayout
    private lateinit var navPlaceholder: TextView

    private lateinit var btnNav: LinearLayout
    private lateinit var btnCar: LinearLayout
    private lateinit var btnClimate: LinearLayout
    private lateinit var btnTrip: LinearLayout

    private lateinit var labelNav: TextView
    private lateinit var labelCar: TextView
    private lateinit var labelClimate: TextView
    private lateinit var labelTrip: TextView

    private lateinit var sohCalc: SohCalculator
    private lateinit var fullChargeTracker: FullChargeTracker
    private lateinit var captionMask: CaptionMaskOverlay
    private lateinit var chargingRepository: ChargingRepository
    private val rangeEstimator = RangeEstimator()
    private val routeProfileDetector = RouteProfileDetector()
    private lateinit var calibrationRepo: CalibrationRepository
    private lateinit var bmsKmPerPctTrendIcon: ImageView

    // ── Сценарии ──
    private lateinit var scenarioSettings: ScenarioSettingsRepository
    private lateinit var scenarioEngine: ScenarioEngine
    private lateinit var kmPerPctRepo: KmPerPctRepository
    private lateinit var baseConsumptionRepo: BaseConsumptionRepository

    @Volatile
    private var lastEstimatedConsumption: Double = 0.0

    @Volatile
    private var smoothedSpeedKmh: Float = 50f

    @Volatile
    private var lastTripState: TripDetector.TripState? = null

    @Volatile
    private var lastWeather: Weather? = null

    private lateinit var carLocationProvider: CarLocationProvider
    private lateinit var tripRepository: TripRepository
    private var currentTripId: Long? = null
    private var tripWasActive = false
    private var lastLiveUpdateTs = 0L
    private var endMarkerWritten = false
    private var lastDaySummary: TripDaySummary? = null
    private val tripDetector = TripDetector()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastSohOem: Int? = null

    private lateinit var navigator: NavigatorPanelController

    @Volatile
    private var lastKnownLocation: Location? = null

    private val gpsReadySignal = Channel<Unit>(Channel.CONFLATED)

    companion object {
        private const val TAG = "MainActivity"

        private const val POLL_INTERVAL_MS = 1000L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val LIVE_UPDATE_INTERVAL_MS = 30_000L
        private const val DAY_SUMMARY_REFRESH_MS = 30_000L

        private const val NOMINAL_KWH = 44.9

        private const val GPS_REFRESH_MS = 5 * 60_000L
        private const val GPS_RETRY_MS = 15_000L
        private const val GPS_MAX_RETRY_MS = 60_000L

        private const val WEATHER_REFRESH_MS = 30 * 60_000L
        private const val WEATHER_RETRY_MS = 15_000L
        private const val WEATHER_MAX_RETRY_MS = 60_000L

        private const val LOCATION_PERMISSION_REQUEST = 1001

        private const val METEO_BASE =
            "https://cdn.jsdelivr.net/npm/@meteocons/svg@0.1.0/fill/"

        private const val COLOR_OK      = 0xFF28C76F.toInt()
        private const val COLOR_WARN    = 0xFFFF9F43.toInt()
        private const val COLOR_BAD     = 0xFFEA5455.toInt()
        private const val COLOR_BLUE    = 0xFF2E93FA.toInt()
        private const val COLOR_NEUTRAL = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED   = 0xFFB0B4BD.toInt()

        private const val BMS_STATE_CHARGING = 1
    }

    // ==================================================
    // Lifecycle
    // ==================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ── BMS Dashboard ──
        bmsSoc             = findViewById(R.id.bmsSoc)
        bmsOdometer        = findViewById(R.id.bmsOdometer)
        bmsLifetimeKwh     = findViewById(R.id.bmsLifetimeKwh)
        bmsRouteProfile    = findViewById(R.id.bmsRouteProfile)
        bmsRouteProfileBadge = findViewById(R.id.bmsRouteProfileBadge)
        bmsPower           = findViewById(R.id.bmsPower)
        bmsPowerState      = findViewById(R.id.bmsPowerState)
        bmsRange           = findViewById(R.id.bmsRange)
        bmsRangeMax        = findViewById(R.id.bmsRangeMax)
        bmsRangeMin        = findViewById(R.id.bmsRangeMin)
        bmsRangeFactors    = findViewById(R.id.bmsRangeFactors)
        bmsCellMax         = findViewById(R.id.bmsCellMax)
        bmsCellMin         = findViewById(R.id.bmsCellMin)
        bmsTempMax         = findViewById(R.id.bmsTempMax)
        bmsTempMin         = findViewById(R.id.bmsTempMin)
        bmsSoh             = findViewById(R.id.bmsSoh)
        bmsDelta           = findViewById(R.id.bmsDelta)
        bmsHv              = findViewById(R.id.bmsHv)
        bmsCurrent         = findViewById(R.id.bmsCurrent)
        bmsV12             = findViewById(R.id.bmsV12)
        bmsTripDistance    = findViewById(R.id.bmsTripDistance)
        bmsTripConsumption = findViewById(R.id.bmsTripConsumption)
        bmsTripEnergy      = findViewById(R.id.bmsTripEnergy)
        bmsFullRange       = findViewById(R.id.bmsFullRange)
        bmsKmPerPctTrendIcon = findViewById(R.id.bmsKmPerPctTrendIcon)

        // ── Кликабельные контейнеры ──
        weatherCard    = findViewById(R.id.weatherCard)
        sohBlock       = findViewById(R.id.sohBlock)
        bmsChipTop     = findViewById(R.id.bmsChipTop)
        bmsChipMiddle  = findViewById(R.id.bmsChipMiddle)
        bmsChipBottom  = findViewById(R.id.bmsChipBottom)

        // ── Погодный виджет ──
        wIcon    = findViewById(R.id.wIcon)
        wLocName = findViewById(R.id.wLocName)
        wTemp    = findViewById(R.id.wTemp)
        wCond    = findViewById(R.id.wCond)
        wDay     = findViewById(R.id.wDay)
        wNight   = findViewById(R.id.wNight)
        wOut     = findViewById(R.id.wOut)
        wIn      = findViewById(R.id.wIn)
        wExtra   = findViewById(R.id.wExtra)

        coilLoader = ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()

        navContainer = findViewById(R.id.navContainer)
        navPlaceholder = findViewById(R.id.navPlaceholder)

        btnNav = findViewById(R.id.btnNav)
        btnCar = findViewById(R.id.btnCar)
        btnClimate = findViewById(R.id.btnClimate)
        btnTrip = findViewById(R.id.btnTrip)

        labelNav = btnNav.findViewById(R.id.labelNav)
        labelCar = btnCar.findViewById(R.id.labelCar)
        labelClimate = btnClimate.findViewById(R.id.labelClimate)
        labelTrip = btnTrip.findViewById(R.id.labelTrip)

        adb = AdbClient.ensureConnected(this)
        ac = AutoserviceClient(adb)
        reader = BmsReader(ac)
        sohCalc = SohCalculator(File(filesDir, "soh_data.json"), nominalKwh = NOMINAL_KWH)

        // ── Подключаем запись зарядок в БД ──
        sohCalc.listener = object : SohCalculator.Companion.ChargingListener {
            override fun onChargingFinished(
                startTs: Long,
                endTs: Long,
                socStart: Double,
                socEnd: Double,
                energyKwh: Double,
                bmsStart: Double?,
                bmsEnd: Double?,
                cellTempStart: Double?,
                cellTempEnd: Double?,
                tempOutsideStart: Double?,
                tempOutsideEnd: Double?,
                odometerKm: Double?,
                acDc: String?,
                accepted: Boolean,
                rejectReason: String?
            ) {
                scope.launch {
                    try {
                        val record = ChargingRecord(
                            startedAt = startTs,
                            endedAt = endTs,
                            durationMin = (endTs - startTs) / 60_000L,
                            acDc = acDc,
                            energyVehicleKwh = energyKwh,
                            bmsCounterStart = bmsStart,
                            bmsCounterEnd = bmsEnd,
                            cellTempStartC = cellTempStart,
                            cellTempEndC = cellTempEnd,
                            tempOutsideStartC = tempOutsideStart,
                            tempOutsideEndC = tempOutsideEnd,
                            odometerKm = odometerKm,
                            startSoc = socStart.toInt(),
                            endSoc = socEnd.toInt(),
                            notes = if (!accepted) "Отклонена SohCalculator: $rejectReason" else null
                        )
                        val id = chargingRepository.insert(record)
                        Log.i(TAG, "Charging saved to DB, id=$id, acDc=$acDc, accepted=$accepted")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save charging", e)
                    }
                }
            }
        }

        fullChargeTracker = FullChargeTracker(this)
        captionMask = CaptionMaskOverlay(this)
        navigator = NavigatorPanelController(this, scope, adb, captionMask, navPlaceholder, labelNav)
        carLocationProvider = CarLocationProvider(this)
        tripRepository = TripRepository(this)
        calibrationRepo = CalibrationRepository(this)
        chargingRepository = ChargingRepository(this)
        kmPerPctRepo = KmPerPctRepository(this)
        baseConsumptionRepo = BaseConsumptionRepository(this)

        // ── Сценарии ──
        scenarioSettings = ScenarioSettingsRepository(this)
        scenarioEngine = ScenarioEngine(scenarioSettings, ac)

        scope.launch {
            try {
                val orphans = tripRepository.getUnfinished()
                if (orphans.isNotEmpty()) {
                    Log.w(TAG, "Found ${orphans.size} orphan unfinished trips, closing them")
                    for (o in orphans) {
                        val fallbackEnd = o.endedAt
                            ?: (o.startedAt + maxOf(o.durationMin * 60_000L, 60_000L))

                        val rec = TripRecord(
                            id = o.id,
                            startedAt = o.startedAt,
                            endedAt = fallbackEnd,
                            finished = true,
                            startSoc = o.startSoc,
                            endSoc = o.endSoc,
                            startOdometerKm = o.startOdometerKm,
                            endOdometerKm = o.endOdometerKm,
                            distanceKm = o.distanceKm,
                            durationMin = o.durationMin,
                            energyKwh = o.energyKwh,
                            consumption = o.consumption,
                            routeProfile = o.routeProfile
                        )
                        tripRepository.finish(o.id, rec)
                        kmPerPctRepo.recordTrip(rec)
                        Log.w(TAG, "Closed orphan id=${o.id}, endedAt=$fallbackEnd")
                    }
                }

                val summary = tripRepository.getTodaySummary()
                withContext(Dispatchers.Main) { updateDaySummaryUi(summary) }

                // ── Дозапись эмпирики км/% по уже закрытым поездкам (идемпотентно) ──
                val added = kmPerPctRepo.backfillFromTrips(tripRepository)
                Log.i(TAG, "km/% backfill at start: +$added")
            } catch (t: Throwable) {
                Log.e(TAG, "onCreate init DB failed: ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }

        try {
            BydBackgroundService.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "BydBackgroundService.start failed", t)
        }

        setupWidgetClicks()
        setupBottomButtons()

        requestLocationPermissionIfNeeded()

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        navigator.start()
        scope.launch { gpsLoop() }
        scope.launch { weatherLoop() }
        scope.launch { runLoop() }
        scope.launch { daySummaryLoop() }
    }

    override fun onResume() {
        super.onResume()

        if (navigator.onResume()) return

        scope.launch {
            try {
                val summary = tripRepository.getTodaySummary()
                withContext(Dispatchers.Main) { updateDaySummaryUi(summary) }
            } catch (t: Throwable) {
                Log.e(TAG, "onResume: getTodaySummary failed", t)
            }
        }
    }
    override fun onPause() {
        super.onPause()
        runCatching { captionMask.hide() }
    }

    // ==================================================
    // Клики по виджетам
    // ==================================================

    private fun setupWidgetClicks() {
        weatherCard.setOnClickListener {
            val loc = lastKnownLocation
            WeatherDetailsActivity.cachedLat = loc?.latitude ?: Double.NaN
            WeatherDetailsActivity.cachedLon = loc?.longitude ?: Double.NaN
            WeatherDetailsActivity.cachedLocationName = wLocName.text?.toString()
            openInternalActivity(Intent(this@MainActivity, WeatherDetailsActivity::class.java))
        }

        bmsChipTop.setOnClickListener {
            openInternalActivity(Intent(this@MainActivity, ChargingHistoryActivity::class.java))
        }

        bmsChipMiddle.setOnClickListener {
            openSohDetails()
        }

        bmsChipBottom.setOnClickListener {
            openInternalActivity(Intent(this@MainActivity, TripsActivity::class.java))
        }

        sohBlock.setOnClickListener {
            openSohDetails()
        }
    }

    private fun openSohDetails() {
        BatteryDetailsActivity.cachedMeasurements = sohCalc.getAllMeasurements()
        BatteryDetailsActivity.cachedCurrentSoh   = sohCalc.getCurrentSoh()
        BatteryDetailsActivity.cachedNominalKwh   = NOMINAL_KWH
        BatteryDetailsActivity.cachedSohOem       = lastSohOem

        openInternalActivity(Intent(this@MainActivity, BatteryDetailsActivity::class.java))
    }

    private fun openInternalActivity(intent: Intent) {
        navigator.onBeforeOpenExternal()
        startActivity(intent)
    }

    // ==================================================
    // Нижние кнопки
    // ==================================================

    private fun setupBottomButtons() {
        btnNav.setOnClickListener {
            selectDockButton(btnNav)
            navigator.onNavDockClicked()
        }
        btnCar.setOnClickListener {
            selectDockButton(btnCar)
            openInternalActivity(Intent(this@MainActivity, LauncherSettingsActivity::class.java))
        }
        btnClimate.setOnClickListener {
            selectDockButton(btnClimate)
            openInternalActivity(Intent(this@MainActivity, ComfortActivity::class.java))
        }
        btnTrip.setOnClickListener {
            selectDockButton(btnTrip)
            openInternalActivity(Intent(this@MainActivity, TechActivity::class.java))
        }

        btnNav.isSelected = true
    }

    private fun selectDockButton(active: View) {
        listOf(btnNav, btnCar, btnClimate, btnTrip).forEach { it.isSelected = false }
        active.isSelected = true
    }

    // ==================================================
    // Перехват тапов и фокуса
    // ==================================================

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            navigator.onUserTouchUp()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            navigator.onWindowFocusGained()
        }
    }

    // ==================================================
    // GPS-разрешение
    // ==================================================

    private fun requestLocationPermissionIfNeeded() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            wLocName.text = "Ожидание разрешения GPS..."
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return

        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            wLocName.text = "Получаю GPS..."
            scope.launch { gpsTickWithRetry() }
        } else {
            wLocName.text = "Нет разрешения на GPS"
        }
    }

    // ==================================================
    // GPS-цикл
    // ==================================================

    private suspend fun gpsLoop() {
        while (currentCoroutineContext().isActive) {
            val ok = gpsTickWithRetry()
            delay(if (ok) GPS_REFRESH_MS else GPS_RETRY_MS)
        }
    }

    private suspend fun gpsTickWithRetry(): Boolean {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            val location = carLocationProvider.getCurrentLocation()

            if (location != null) {
                lastKnownLocation = location
                Log.i(TAG, "GPS OK: lat=${location.latitude}, lon=${location.longitude}")
                withContext(Dispatchers.Main) {
                    wLocName.text = "%.4f, %.4f".format(location.latitude, location.longitude)
                }
                gpsReadySignal.trySend(Unit)
                return true
            }

            val backoff = minOf(
                GPS_RETRY_MS * (1L shl minOf(attempt - 1, 4)),
                GPS_MAX_RETRY_MS
            )
            Log.w(TAG, "GPS attempt #$attempt failed, retry in ${backoff}ms")
            withContext(Dispatchers.Main) {
                wLocName.text = "GPS ГУ недоступен, повтор через ${backoff / 1000} с..."
            }
            delay(backoff)
        }
        return false
    }

    // ==================================================
    // Погода-цикл
    // ==================================================

    private suspend fun weatherLoop() {
        while (currentCoroutineContext().isActive) {
            val ok = weatherTickWithRetry()
            if (ok) {
                withTimeoutOrNull(WEATHER_REFRESH_MS) { gpsReadySignal.receive() }
            } else {
                delay(WEATHER_RETRY_MS)
            }
        }
    }

    private suspend fun weatherTickWithRetry(): Boolean {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++

            val location = lastKnownLocation
            if (location == null) {
                Log.w(TAG, "Weather attempt #$attempt: no cached location yet")
                withContext(Dispatchers.Main) { wLocName.text = "Ожидание GPS..." }
                delay(minOf(WEATHER_RETRY_MS * (1L shl minOf(attempt - 1, 4)), WEATHER_MAX_RETRY_MS))
                continue
            }

            val ok = loadWeatherFor(location)
            if (ok) {
                Log.i(TAG, "Weather OK after $attempt attempt(s)")
                return true
            }

            val backoff = minOf(
                WEATHER_RETRY_MS * (1L shl minOf(attempt - 1, 4)),
                WEATHER_MAX_RETRY_MS
            )
            Log.w(TAG, "Weather attempt #$attempt failed, retry in ${backoff}ms")
            withContext(Dispatchers.Main) {
                wLocName.text = "Погода недоступна, повтор через ${backoff / 1000} с..."
            }
            delay(backoff)
        }
        return false
    }

    private suspend fun loadWeatherFor(location: Location): Boolean {
        val locationName = getLocationName(location)

        withContext(Dispatchers.Main) {
            wLocName.text = locationName
            wCond.text = "Загрузка…"
        }

        val w = WeatherClient.fetch(this@MainActivity, location.latitude, location.longitude)
        if (w == null) {
            withContext(Dispatchers.Main) { wCond.text = "Нет данных" }
            return false
        }

        lastWeather = w
        Log.i(
            TAG,
            "Weather cached: t=${w.temperature}° " +
                    "wind=${w.windSpeedMs}m/s @${w.windDirection}° " +
                    "precip=${w.precipitation} snow=${w.snowfall} " +
                    "isDay=${w.isDay}"
        )

        withContext(Dispatchers.Main) {
            wLocName.text = locationName
            wTemp.text    = "%.0f°".format(w.temperature)
            wCond.text    = w.description.uppercase()
            wDay.text     = "%.0f°".format(w.tempMax)
            wNight.text   = "%.0f°".format(w.tempMin)
            wOut.text     = "%.0f°".format(w.temperature)

            val windStr = w.windSpeedMs?.let { "💨 %.0f м/с".format(it) }
            val precipStr = when {
                (w.snowfall ?: 0f) > 0.1f -> "❄ %.1f мм".format(w.snowfall)
                (w.precipitation ?: 0f) > 0.1f -> "💧 %.1f мм".format(w.precipitation)
                else -> "💧 0 мм"
            }
            wExtra.text = listOfNotNull(windStr, precipStr).joinToString(" · ")

            val iconFile = weatherIconName(w.icon, w.description)
            val url = METEO_BASE + iconFile
            Log.i(TAG, "Loading weather icon: $url")

            wIcon.load(url, coilLoader) {
                placeholder(R.drawable.ic_weather_placeholder)
                error(R.drawable.ic_weather_placeholder)
                crossfade(true)
                listener(
                    onError = { _, result ->
                        Log.e(TAG, "Icon FAILED: ${result.throwable.javaClass.simpleName}: ${result.throwable.message}")
                    },
                    onSuccess = { _, _ ->
                        Log.i(TAG, "Icon OK: $url")
                    }
                )
            }
        }
        return true
    }

    private fun weatherIconName(iconRaw: String?, description: String): String {
        val d = (description + " " + (iconRaw ?: "")).lowercase()

        return when {
            d.contains("гроза") || d.contains("thunder")   -> "thunderstorms.svg"
            d.contains("ливень") || d.contains("shower")   -> "rain.svg"
            d.contains("дождь") || d.contains("rain")      -> "rain.svg"
            d.contains("морось") || d.contains("drizzle")  -> "drizzle.svg"
            d.contains("снег") || d.contains("snow")       -> "snow.svg"
            d.contains("иней") || d.contains("sleet")      -> "sleet.svg"
            d.contains("туман") || d.contains("fog")       -> "fog.svg"
            d.contains("облач") || d.contains("cloud")     -> "overcast.svg"
            d.contains("перемен") || d.contains("partly")  -> "partly-cloudy-day.svg"
            else -> "clear-day.svg"
        }
    }

    // ==================================================
    // Название города
    // ==================================================

    private suspend fun getLocationName(location: Location): String {
        fetchCityFromNominatim(location.latitude, location.longitude)
            ?.let { return it }
        tryGeocoder(location)?.let { return it }
        return coordinatesString(location)
    }

    private suspend fun fetchCityFromNominatim(lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://nominatim.openstreetmap.org/reverse" +
                            "?format=json&lat=$lat&lon=$lon" +
                            "&zoom=10&accept-language=ru"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "BYDLauncher/1.0 (android)")
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                val address = json.optJSONObject("address") ?: return@withContext null

                val city = address.optString("city").takeIf { it.isNotBlank() }
                    ?: address.optString("town").takeIf { it.isNotBlank() }
                    ?: address.optString("village").takeIf { it.isNotBlank() }
                    ?: address.optString("municipality").takeIf { it.isNotBlank() }
                    ?: address.optString("county").takeIf { it.isNotBlank() }
                    ?: address.optString("state").takeIf { it.isNotBlank() }
                    ?: address.optString("country").takeIf { it.isNotBlank() }

                city
            } catch (e: Exception) {
                Log.e(TAG, "Nominatim failed: ${e.javaClass.simpleName}: ${e.message}", e)
                null
            }
        }

    private suspend fun tryGeocoder(location: Location): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext null
                @Suppress("DEPRECATION")
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(
                    location.latitude, location.longitude, 1
                )
                val address = addresses?.firstOrNull()
                address?.locality
                    ?: address?.subAdminArea
                    ?: address?.adminArea
                    ?: address?.countryName
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    private fun coordinatesString(location: Location): String =
        String.format(Locale.US, "%.4f, %.4f", location.latitude, location.longitude)

    // ==================================================
    // Основной цикл BMS
    // ==================================================

    private suspend fun CoroutineScope.runLoop() {
        while (isActive) {
            if (!connectWithRetry()) return

            var fails = 0
            pollLoop@ while (isActive) {
                val snap = try { reader.read() } catch (t: Throwable) {
                    Log.e(TAG, "read() threw", t); null
                }

                if (snap == null) {
                    fails++
                    Log.w(TAG, "BMS read failed ($fails/$MAX_CONSECUTIVE_FAILURES)")
                    if (fails >= MAX_CONSECUTIVE_FAILURES) {
                        Log.w(TAG, "Потеряна связь с ADB. Переподключение...")
                        if (::scenarioEngine.isInitialized) {
                            // Вернуть ДХО, если выключили мы. ADB ещё жив. Поток нужен, чтобы
                            // не делать блокирующий ADB-вызов на главном потоке.
                            val t = Thread { runCatching { scenarioEngine.resetAll() } }
                            t.start()
                            t.join(1500)
                        }
                        adb.disconnect()
                        break@pollLoop
                    }
                } else {
                    fails = 0
                    sohCalc.onSnapshot(snap)
                    fullChargeTracker.onSnapshot(snap.socPercent, snap.lifetimeKm)

                    snap.speedKmh?.let {
                        routeProfileDetector.addSpeed(System.currentTimeMillis(), it)
                    }

                    // ── НОВОЕ: Отлов выключения зажигания ──
                    if (snap.gearMode == null && lastTripState?.active == true) {
                        Log.i(TAG, "Ignition OFF detected (gearMode null) -> force close trip")
                        val closedTrip = tripDetector.onIgnitionOff(
                            ts = System.currentTimeMillis(),
                            routeProfile = routeProfileDetector.getProfile()
                        )
                        lastTripState = closedTrip
                        handleClosedTrip(closedTrip)
                    } else {
                        updateTrip(snap)
                    }

                    updateBmsDashboard(snap)
                    updateTemperatures(snap)

                    // ── Сценарии ──
                    runCatching { scenarioEngine.onSnapshot(snap) }
                        .onFailure { Log.e(TAG, "scenarioEngine failed", it) }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun updateTemperatures(s: BatterySnapshot) = withContext(Dispatchers.Main) {
        s.tempOutsideC?.let { wOut.text = "$it°" }
        s.tempInsideC?.let  { wIn.text  = "$it°" }
    }

    // ==================================================
    // Обновление BMS Dashboard
    // ==================================================

    private suspend fun updateBmsDashboard(s: BatterySnapshot) = withContext(Dispatchers.Main) {

        val rawSpeed = s.speedKmh ?: 0f
        smoothedSpeedKmh = smoothedSpeedKmh * 0.985f + rawSpeed * 0.015f

        val carBearing = carLocationProvider.getCurrentBearing()
        val routeProfile = routeProfileDetector.getProfile()
        val calibrationFactor = calibrationRepo.getCalibrationFactor(s.tempOutsideC ?: 15)

        val soc: Int = (s.socPercent ?: 0f).toInt()
        bmsSoc.text = soc.toString()
        bmsSoc.setTextColor(
            when {
                soc > 60 -> COLOR_OK
                soc > 30 -> COLOR_WARN
                else     -> COLOR_BAD
            }
        )

        val powerKw: Double? = if (s.hvVoltageV != null && s.hvCurrentA != null) {
            s.hvVoltageV.toDouble() * s.hvCurrentA.toDouble() / 1000.0
        } else null

        val isChargingFromStation = (s.bmsState == BMS_STATE_CHARGING)

        if (powerKw != null) {
            val absKw = Math.abs(powerKw)

            bmsPower.text = when {
                absKw >= 10.0 -> String.format(Locale.US, "%+.1f", powerKw)
                else          -> String.format(Locale.US, "%+.2f", powerKw)
            }

            when {
                isChargingFromStation -> {
                    bmsPower.setTextColor(COLOR_OK)
                    bmsPowerState.text = "ЗАРЯДКА"
                    bmsPowerState.setTextColor(COLOR_OK)
                }
                powerKw > 0.2 -> {
                    bmsPower.setTextColor(COLOR_WARN)
                    bmsPowerState.text = "РАЗРЯД"
                    bmsPowerState.setTextColor(COLOR_WARN)
                }
                powerKw < -0.2 -> {
                    bmsPower.setTextColor(COLOR_BLUE)
                    bmsPowerState.text = "РЕКУПЕРАЦИЯ"
                    bmsPowerState.setTextColor(COLOR_BLUE)
                }
                else -> {
                    bmsPower.setTextColor(COLOR_NEUTRAL)
                    bmsPowerState.text = "ПОКОЙ"
                    bmsPowerState.setTextColor(COLOR_MUTED)
                }
            }
        } else {
            bmsPower.text = "—"
            bmsPower.setTextColor(COLOR_MUTED)
            if (isChargingFromStation) {
                bmsPowerState.text = "ЗАРЯДКА"
                bmsPowerState.setTextColor(COLOR_OK)
            } else {
                bmsPowerState.text = "нет данных"
                bmsPowerState.setTextColor(COLOR_MUTED)
            }
        }

        val soh = s.sohPercent ?: 0
        bmsSoh.text = "$soh%"
        lastSohOem = s.sohPercent

        s.lifetimeKm?.let {
            bmsOdometer.text = String.format(Locale.US, "%,.1f", it)
        } ?: run {
            bmsOdometer.text = "—"
        }

        s.lifetimeKwh?.let {
            bmsLifetimeKwh.text = String.format(Locale.US, "%.1f", it)
        } ?: run {
            bmsLifetimeKwh.text = "—"
        }

        val tripAvgSpeed = lastTripState?.avgSpeedKmh ?: 0.0
        bmsRouteProfile.text = if (tripAvgSpeed > 0.5)
            String.format(Locale.US, "%.0f", tripAvgSpeed)
        else "—"

        val profileLabel = when (routeProfile) {
            RouteProfile.CITY    -> "ГОРОД"
            RouteProfile.MKAD    -> "МКАД"
            RouteProfile.HIGHWAY -> "ТРАССА"
            else                 -> "—"
        }
        bmsRouteProfileBadge.text = profileLabel

        val (bgRes, badgeColor) = when (routeProfile) {
            RouteProfile.CITY    -> R.drawable.bg_profile_city    to 0xFF4FD1C5.toInt()
            RouteProfile.MKAD    -> R.drawable.bg_profile_mkad    to 0xFF5B9CFF.toInt()
            RouteProfile.HIGHWAY -> R.drawable.bg_profile_highway to 0xFFA88BFF.toInt()
            else                 -> R.drawable.bg_profile_unknown to 0xFFB0B4BD.toInt()
        }
        bmsRouteProfileBadge.setBackgroundResource(bgRes)
        bmsRouteProfileBadge.setTextColor(badgeColor)

        // ═══ Эмпирика км/% и базовый расход ═══
        val kmPerPctNorm = kmPerPctRepo.getNormalized(routeProfile)
        val kmPerPctWeekAvg = kmPerPctRepo.getSmoothedKmPerPct()
        val now = System.currentTimeMillis()
        val kmPerPctPrevWeekAvg = kmPerPctRepo.getAvgForWindow(
            now - 14L * 24 * 3600_000L,
            now -  7L * 24 * 3600_000L
        )
        val baseRecent = baseConsumptionRepo.getRecentBase()

        val est = rangeEstimator.estimate(
            socPercent        = s.socPercent ?: 0f,
            sohPercent        = s.sohPercent,
            tempOutsideC      = s.tempOutsideC,
            avgSpeedKmh       = smoothedSpeedKmh,
            maxCellV          = s.maxCellV,
            battTempC         = s.maxBatTempC,
            weather           = lastWeather,
            carBearing        = carBearing,
            routeProfile      = routeProfile,
            calibrationFactor = calibrationFactor,
            kmPerPctNorm        = kmPerPctNorm,
            kmPerPctWeekAvg     = kmPerPctWeekAvg,
            kmPerPctPrevWeekAvg = kmPerPctPrevWeekAvg,
            baseRecent          = baseRecent
        )

        lastEstimatedConsumption = est.consumption

        bmsRange.text = est.mainKm.toString()

        // ── км/% (текущий) ──
        bmsRangeMax.text = est.kmPerPctCurrent?.let {
            String.format(Locale.US, "%.1f", it)
        } ?: "—"

        // ── среднее за неделю (без стрелки в тексте) ──
        bmsRangeMin.text = est.kmPerPctWeekAvg?.let {
            String.format(Locale.US, "%.1f", it)
        } ?: "—"

        // ── иконка тренда ──
        val trendColor = when (est.kmPerPctTrend) {
            RangeEstimator.Trend.UP   -> 0xFF28C76F.toInt()
            RangeEstimator.Trend.DOWN -> 0xFFEA5455.toInt()
            RangeEstimator.Trend.FLAT -> 0xFFB0B4BD.toInt()
        }
        bmsRangeMin.setTextColor(trendColor)

        val trendIconRes = when (est.kmPerPctTrend) {
            RangeEstimator.Trend.UP   -> R.drawable.ic_trend_up
            RangeEstimator.Trend.DOWN -> R.drawable.ic_trend_down
            RangeEstimator.Trend.FLAT -> R.drawable.ic_trend_flat
        }
        bmsKmPerPctTrendIcon.setImageResource(trendIconRes)

        bmsRange.setTextColor(
            if (est.heaterWarning) COLOR_WARN else COLOR_NEUTRAL
        )

        if (est.favorable) {
            bmsRangeFactors.visibility = View.VISIBLE
            bmsRangeFactors.text = "✓ БЛАГОПРИЯТНЫЕ УСЛОВИЯ"
            bmsRangeFactors.setTextColor(COLOR_OK)
        } else if (est.factors.isEmpty()) {
            bmsRangeFactors.text = ""
            bmsRangeFactors.visibility = View.GONE
        } else {
            bmsRangeFactors.visibility = View.VISIBLE
            bmsRangeFactors.text = est.factors.joinToString(" · ") {
                "${if (it.percent >= 0) "+" else ""}${it.percent}% ${it.label}"
            }
            val worst = est.factors.maxByOrNull { Math.abs(it.percent) }
            bmsRangeFactors.setTextColor(
                when (worst?.severity) {
                    RangeEstimator.Severity.BAD  -> COLOR_BAD
                    RangeEstimator.Severity.WARN -> COLOR_WARN
                    else                         -> COLOR_MUTED
                }
            )
        }

        Log.i(TAG, "Range: main=${est.mainKm} км/%=${est.kmPerPctCurrent} " +
                "week=${est.kmPerPctWeekAvg} trend=${est.kmPerPctTrend} " +
                "source=${est.source} cons=${String.format(Locale.US, "%.2f", est.consumption)}")

        bmsCellMax.text = s.maxCellV?.let { String.format(Locale.US, "%.3f", it) } ?: "—"
        bmsCellMin.text = s.minCellV?.let { String.format(Locale.US, "%.3f", it) } ?: "—"

        val deltaMv = s.cellDeltaMv ?: 0
        bmsDelta.text = "$deltaMv мВ"
        bmsDelta.setTextColor(
            when {
                deltaMv < 10 -> COLOR_OK
                deltaMv < 30 -> COLOR_WARN
                else         -> COLOR_BAD
            }
        )

        bmsTempMax.text = s.maxBatTempC?.toString() ?: "—"
        bmsTempMin.text = s.minBatTempC?.toString() ?: "—"

        bmsHv.text = s.hvVoltageV?.let { "$it В" } ?: "— В"
        bmsCurrent.text = s.hvCurrentA?.let { String.format(Locale.US, "%.0f А", Math.abs(it)) } ?: "— А"
        bmsV12.text = s.voltage12v?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

        val sinceFull = fullChargeTracker.getDistanceSinceFullCharge(s.lifetimeKm)
        bmsFullRange.text = when {
            sinceFull == null -> "— км"
            else -> String.format(Locale.US, "%.0f км", sinceFull)
        }
    }

    // ==================================================
    // Обновление «Поездка за день» из БД
    // ==================================================

    private suspend fun daySummaryLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val summary = tripRepository.getTodaySummary()
                lastDaySummary = summary

                withContext(Dispatchers.Main) {
                    updateDaySummaryUi(summary)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "daySummaryLoop failed", t)
            }
            delay(DAY_SUMMARY_REFRESH_MS)
        }
    }

    private fun updateDaySummaryUi(s: TripDaySummary) {
        bmsTripDistance.text    = String.format(Locale.US, "%.1f км", s.distanceKm)
        bmsTripConsumption.text = String.format(Locale.US, "%.1f кВт·ч", s.consumptionKwh100)
        bmsTripEnergy.text      = String.format(Locale.US, "%.1f кВт·ч", s.energyKwh)

        bmsTripConsumption.setTextColor(
            when {
                s.consumptionKwh100 <= 0.1 -> COLOR_NEUTRAL
                s.consumptionKwh100 < 15.0 -> COLOR_OK
                s.consumptionKwh100 < 25.0 -> COLOR_WARN
                else                       -> COLOR_BAD
            }
        )
    }

    // ==================================================
    // Trip-детектор
    // ==================================================

    private suspend fun updateTrip(s: BatterySnapshot) = withContext(Dispatchers.Main) {
        val trip = tripDetector.onSnapshot(
            ts = System.currentTimeMillis(),
            gearMode = s.gearMode,
            speedKmh = s.speedKmh,
            mileageKm = s.lifetimeKm,
            lifetimeKwh = s.lifetimeKwh,
            socPercent = s.socPercent,
            tempOutsideC = s.tempOutsideC,
            tempInsideC = s.tempInsideC,
            battTempC = s.maxBatTempC,
            driverBeltBuckled = s.driverBeltBuckled,
            routeProfile = routeProfileDetector.getProfile()
        )

        lastTripState = trip

        if (trip.active && !tripWasActive) {
            tripWasActive = true
            endMarkerWritten = false
            val loc = lastKnownLocation
            val record = TripRecord(
                startedAt = trip.startedAt,
                finished = false,
                startSoc = trip.startSoc,
                startOdometerKm = trip.startOdometerKm,
                startLat = loc?.latitude,
                startLon = loc?.longitude
            )
            scope.launch {
                try {
                    currentTripId = tripRepository.insertStart(record)
                    Log.i(TAG, "Trip started in DB, id=$currentTripId")

                    val summary = tripRepository.getTodaySummary()
                    withContext(Dispatchers.Main) { updateDaySummaryUi(summary) }
                } catch (t: Throwable) {
                    Log.e(TAG, "insertStart failed", t)
                }
            }
        }

        if (trip.active && currentTripId != null) {
            val now = System.currentTimeMillis()
            val endPending = trip.endPendingSince != null
            val endMarkerChanged = endPending != endMarkerWritten
            if (endMarkerChanged || now - lastLiveUpdateTs >= LIVE_UPDATE_INTERVAL_MS) {
                lastLiveUpdateTs = now
                endMarkerWritten = endPending
                val loc = lastKnownLocation
                val record = TripRecord(
                    startedAt = trip.startedAt,
                    distanceKm = trip.distanceKm,
                    durationMin = trip.durationMin,
                    energyKwh = trip.energyKwh,
                    consumption = trip.consumptionPer100Km,
                    avgSpeedKmh = trip.avgSpeedKmh,
                    maxSpeedKmh = trip.maxSpeedKmh,
                    outsideTempAvgC = trip.outsideTempAvgC,
                    insideTempAvgC = trip.insideTempAvgC,
                    battTempAvgC = trip.battTempAvgC,
                    endedAt = trip.endPendingSince,
                    endSoc = trip.endSoc,
                    endOdometerKm = trip.endOdometerKm,
                    endLat = loc?.latitude,
                    endLon = loc?.longitude
                )
                val id = currentTripId!!
                scope.launch {
                    try { tripRepository.updateLive(id, record) }
                    catch (t: Throwable) { Log.e(TAG, "updateLive failed", t) }
                }
            }
        }

        if (!trip.active && tripWasActive) {
            handleClosedTrip(trip)
        }

        val txt = if (trip.active) "В поездке" else "Поездка не начата"
        navigator.captionText = txt

        if (navigator.isVisible) {
            captionMask.updateTrip(
                active = trip.active,
                distanceKm = trip.distanceKm,
                energyKwh = trip.energyKwh,
                consumption = trip.consumptionPer100Km,
                durationMin = trip.durationMin.toInt()
            )
        }
    }

    private fun handleClosedTrip(trip: TripDetector.TripState) {
        if (!tripWasActive) return
        tripWasActive = false
        endMarkerWritten = false
        val id = currentTripId
        currentTripId = null

        if (id != null) {
            val loc = lastKnownLocation
            val record = TripRecord(
                id = id,
                startedAt = trip.startedAt,
                endedAt = trip.endedAt,
                finished = true,
                startSoc = trip.startSoc,
                endSoc = trip.endSoc,
                startOdometerKm = trip.startOdometerKm,
                endOdometerKm = trip.endOdometerKm,
                distanceKm = trip.distanceKm,
                durationMin = trip.durationMin,
                energyKwh = trip.energyKwh,
                consumption = trip.consumptionPer100Km,
                avgSpeedKmh = trip.avgSpeedKmh,
                maxSpeedKmh = trip.maxSpeedKmh,
                outsideTempAvgC = trip.outsideTempAvgC,
                insideTempAvgC = trip.insideTempAvgC,
                battTempAvgC = trip.battTempAvgC,
                endLat = loc?.latitude,
                endLon = loc?.longitude,
                routeProfile = trip.routeProfile
            )

            val predictedForCalibration = lastEstimatedConsumption
            val profileAtEnd = routeProfileDetector.getProfile()
            val tripKm = trip.distanceKm
            val tAvg = trip.outsideTempAvgC
            val vAvg = trip.avgSpeedKmh
            val actual = trip.consumptionPer100Km

            if (predictedForCalibration > 0.5 && actual > 0.5) {
                calibrationRepo.addSample(
                    tripKm = tripKm,
                    tempAvgC = tAvg,
                    avgSpeedKmh = vAvg,
                    profile = profileAtEnd,
                    actualKwh100 = actual,
                    predictedKwh100 = predictedForCalibration
                )
            }

            scope.launch {
                tripRepository.finish(id, record)
                Log.i(TAG, "Trip finished in DB, id=$id")
                // бэкап стартует только после того, как поездка закрыта в БД
                scope.launch { runCatching { TelegramBackupUploader.maybeAutoBackup(applicationContext) } }

                // ── Записываем эмпирику км/% ──
                try {
                    kmPerPctRepo.recordTrip(record)
                } catch (t: Throwable) {
                    Log.e(TAG, "kmPerPctRepo.recordTrip failed", t)
                }

                val summary = tripRepository.getTodaySummary()
                withContext(Dispatchers.Main) { updateDaySummaryUi(summary) }
            }
        }
    }

    private suspend fun CoroutineScope.connectWithRetry(): Boolean {
        while (isActive) {
            Log.i(TAG, "Подключение к ADB...")
            if (adb.connect()) {
                Log.i(TAG, "Подключено. Читаю BMS...")
                return true
            }
            Log.w(TAG, "Ошибка подключения ADB. Повтор через ${RECONNECT_DELAY_MS / 1000} с...")
            delay(RECONNECT_DELAY_MS)
        }
        return false
    }

    // ==================================================
    // Завершение
    // ==================================================

    override fun onDestroy() {
        runCatching { captionMask.hide() }
            .onFailure { Log.w(TAG, "captionMask.hide failed: ${it.message}") }
        if (::scenarioEngine.isInitialized) {
            // Вернуть ДХО, если выключили мы. ADB ещё жив. Поток нужен, чтобы
            // не делать блокирующий ADB-вызов на главном потоке.
            val t = Thread { runCatching { scenarioEngine.resetAll() } }
            t.start()
            t.join(1500)
        }
        scope.cancel()

        runCatching { gpsReadySignal.close() }
            .onFailure { Log.w(TAG, "gpsReadySignal.close failed: ${it.message}") }

        runCatching { tripRepository.close() }
            .onFailure { Log.w(TAG, "tripRepository.close failed: ${it.message}") }

        runCatching { chargingRepository.close() }
            .onFailure { Log.w(TAG, "chargingRepository.close failed: ${it.message}") }

        super.onDestroy()
    }
}