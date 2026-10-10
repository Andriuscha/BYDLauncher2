package com.ar.bydlauncher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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

    // ── Сценарии ──
    private lateinit var scenarioSettings: ScenarioSettingsRepository
    private lateinit var scenarioEngine: ScenarioEngine

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

    private var navStackId: Int = -1
    private var navLeft = 0
    private var navTop = 0
    private var navRight = 0
    private var navBottom = 0
    private var isNavigatorVisible = true

    private var externalAppActive = false
    private var navWasVisibleBeforeExternal = false
    private var lastCaptionText = "Поездка не начата"

    private var reShowJob: Job? = null
    private var lastReShowAttemptTs = 0L
    private var lastToggleTs = 0L

    @Volatile
    private var navigatorReady = false

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

        private const val NAV_RE_SHOW_DELAY_MS = 150L
        private const val NAV_RE_SHOW_DEBOUNCE_MS = 100L
        private const val NAV_TOGGLE_GRACE_MS = 600L

        private const val YANDEX_PKG = "ru.yandex.yandexnavi"
        private const val YANDEX_ACTIVITY =
            "ru.yandex.yandexnavi.core.NavigatorActivity"

        private const val NOMINAL_KWH = 44.9
        private const val SPLIT_RATIO = 0.45
        private const val CAPTION_HEIGHT_PX = 80

        private const val FIND_STACK_MAX_ATTEMPTS = 8
        private const val FIND_STACK_RETRY_DELAY_MS = 500L

        private const val GPS_REFRESH_MS = 5 * 60_000L
        private const val GPS_RETRY_MS = 15_000L
        private const val GPS_MAX_RETRY_MS = 60_000L

        private const val WEATHER_REFRESH_MS = 30 * 60_000L
        private const val WEATHER_RETRY_MS = 15_000L
        private const val WEATHER_MAX_RETRY_MS = 60_000L

        private const val LOCATION_PERMISSION_REQUEST = 1001

        private const val OFFSCREEN_X = 1920
        private const val OFFSCREEN_Y = 0
        private const val OFFSCREEN_W = 100
        private const val OFFSCREEN_H = 100

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
        carLocationProvider = CarLocationProvider(this)
        tripRepository = TripRepository(this)
        calibrationRepo = CalibrationRepository(this)
        chargingRepository = ChargingRepository(this)

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

                        tripRepository.finish(o.id, TripRecord(
                            startedAt = o.startedAt,
                            endedAt = fallbackEnd,
                            finished = true,
                            distanceKm = o.distanceKm,
                            durationMin = o.durationMin,
                            energyKwh = o.energyKwh,
                            consumption = o.consumption,
                            startSoc = o.startSoc,
                            endSoc = o.endSoc,
                            startOdometerKm = o.startOdometerKm,
                            endOdometerKm = o.endOdometerKm
                        ))
                        Log.w(TAG, "Closed orphan id=${o.id}, endedAt=$fallbackEnd")
                    }
                }

                val summary = tripRepository.getTodaySummary()
                withContext(Dispatchers.Main) { updateDaySummaryUi(summary) }
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

        scope.launch { delay(1500); launchYandexInFreeform() }
        scope.launch { gpsLoop() }
        scope.launch { weatherLoop() }
        scope.launch { runLoop() }
        scope.launch { daySummaryLoop() }
    }

    override fun onResume() {
        super.onResume()

        if (externalAppActive) {
            externalAppActive = false

            if (navWasVisibleBeforeExternal && navStackId >= 0) {
                scope.launch {
                    delay(300)
                    showNavigatorInternal()
                }
            } else if (navStackId >= 0) {
                updateNavButtonState(visible = false)
            }
            navWasVisibleBeforeExternal = false
            return
        }

        if (navStackId >= 0 && isNavigatorVisible) {
            scope.launch {
                delay(200)
                val reallyVisible = isNavigatorReallyVisible()
                if (!reallyVisible) {
                    showNavigatorInternal()
                } else {
                    withContext(Dispatchers.Main) {
                        captionMask.show(
                            top = 0,
                            captionHeightPx = CAPTION_HEIGHT_PX,
                            text = lastCaptionText
                        )
                    }
                }
            }
        }

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
        externalAppActive = true
        navWasVisibleBeforeExternal = isNavigatorVisible
        runCatching { captionMask.hide() }
        startActivity(intent)
    }

    // ==================================================
    // Нижние кнопки
    // ==================================================

    private fun setupBottomButtons() {
        btnNav.setOnClickListener {
            lastToggleTs = System.currentTimeMillis()
            selectDockButton(btnNav)
            scope.launch { toggleNavigator() }
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

    private fun updateNavButtonState(visible: Boolean) {
        labelNav.text = if (visible) "Навигация ВКЛ" else "Навигация ВЫКЛ"
    }

    // ==================================================
    // Перехват тапов и фокуса
    // ==================================================

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            scheduleNavigatorReShow("dispatchTouchEvent")
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            scheduleNavigatorReShow("onWindowFocusChanged")
        }
    }

    private fun scheduleNavigatorReShow(source: String) {
        if (!navigatorReady || navStackId < 0) return
        if (!isNavigatorVisible) return
        if (externalAppActive) return

        val now = System.currentTimeMillis()

        if (now - lastToggleTs < NAV_TOGGLE_GRACE_MS) {
            Log.d(TAG, "scheduleNavigatorReShow($source): skip — recent toggle")
            return
        }

        if (now - lastReShowAttemptTs < NAV_RE_SHOW_DEBOUNCE_MS) {
            Log.d(TAG, "scheduleNavigatorReShow($source): skip — debounce")
            return
        }
        lastReShowAttemptTs = now

        reShowJob?.cancel()
        reShowJob = scope.launch {
            delay(NAV_RE_SHOW_DELAY_MS)

            if (externalAppActive) return@launch
            if (!navigatorReady || navStackId < 0) return@launch
            if (!isNavigatorVisible) return@launch

            Log.i(TAG, "scheduleNavigatorReShow($source): re-show navigator")
            showNavigatorInternal()
        }
    }

    // ==================================================
    // Проверка видимости навигатора
    // ==================================================

    private suspend fun isNavigatorReallyVisible(): Boolean {
        if (navStackId < 0) return false
        val list = adb.shell("am stack list") ?: return false
        val rx = Regex(
            """Stack id=$navStackId\s+bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""
        )
        val m = rx.find(list) ?: return false
        val left = m.groupValues[1].toIntOrNull() ?: return false
        return left < OFFSCREEN_X
    }

    private suspend fun toggleNavigator() {
        if (navStackId < 0) {
            Log.w(TAG, "toggleNavigator: navStackId неизвестен")
            return
        }
        val reallyVisible = isNavigatorReallyVisible()
        Log.i(TAG, "toggleNavigator: isNavigatorVisible=$isNavigatorVisible, reallyVisible=$reallyVisible")

        if (reallyVisible) hideNavigatorInternal() else showNavigatorInternal()
    }

    private suspend fun hideNavigatorInternal() {
        val cmd = "am stack resize $navStackId " +
                "$OFFSCREEN_X $OFFSCREEN_Y " +
                "${OFFSCREEN_X + OFFSCREEN_W} ${OFFSCREEN_Y + OFFSCREEN_H}"
        Log.i(TAG, "hideNavigator cmd: $cmd")
        val r = adb.shell(cmd)
        Log.i(TAG, "hideNavigator result: $r")
        delay(200)

        withContext(Dispatchers.Main) {
            captionMask.hide()
            updateNavButtonState(visible = false)
        }
        isNavigatorVisible = false
        Log.i(TAG, "Navigator hidden")
    }

    private suspend fun showNavigatorInternal() {
        if (navStackId < 0) {
            Log.w(TAG, "showNavigatorInternal: navStackId неизвестен")
            return
        }

        val resizeCmd = "am stack resize $navStackId " +
                "$navLeft $navTop $navRight $navBottom"
        Log.i(TAG, "showNavigator resize cmd: $resizeCmd")
        val r = adb.shell(resizeCmd)
        Log.i(TAG, "showNavigator resize result: $r")
        delay(150)

        val reShowCmd = "am start --windowingMode 5 " +
                "-n $YANDEX_PKG/$YANDEX_ACTIVITY"
        Log.i(TAG, "showNavigator reShow cmd: $reShowCmd")
        val reShowResult = adb.shell(reShowCmd)
        Log.i(TAG, "showNavigator reShow result: $reShowResult")
        delay(300)

        val jiggle1 = adb.shell(
            "am stack resize $navStackId " +
                    "$navLeft ${navTop + 1} $navRight $navBottom"
        )
        Log.i(TAG, "showNavigator jiggle1: $jiggle1")
        delay(80)
        val jiggle2 = adb.shell(
            "am stack resize $navStackId " +
                    "$navLeft $navTop $navRight $navBottom"
        )
        Log.i(TAG, "showNavigator jiggle2: $jiggle2")
        delay(200)

        val reallyVisible = isNavigatorReallyVisible()

        withContext(Dispatchers.Main) {
            if (reallyVisible) {
                captionMask.show(
                    top = 0,
                    captionHeightPx = CAPTION_HEIGHT_PX,
                    text = lastCaptionText
                )
                updateNavButtonState(visible = true)
                isNavigatorVisible = true
                Log.i(TAG, "Navigator shown (verified)")
            } else {
                Log.w(TAG, "showNavigator: окно не поднялось, visible=false")
                labelNav.text = "Навигация ?"
                isNavigatorVisible = false
            }
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
    // Freeform-запуск Яндекса
    // ==================================================

    private suspend fun launchYandexInFreeform() {
        val overlayGrant =
            adb.shell("appops set com.ar.bydlauncher SYSTEM_ALERT_WINDOW allow")
        Log.i(TAG, "SYSTEM_ALERT_WINDOW grant result: $overlayGrant")

        val freeformEnabled =
            adb.shell("settings get global enable_freeform_support")?.trim()
        val forceResizable =
            adb.shell("settings get global force_resizable_activities")?.trim()
        Log.i(TAG, "settings: enable_freeform_support=$freeformEnabled, force_resizable=$forceResizable")

        if (freeformEnabled != "1" || forceResizable != "1") {
            withContext(Dispatchers.Main) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Freeform отключён.\nВключите через ADB и перезагрузите ГУ."
            }
            return
        }

        adb.shell("am force-stop $YANDEX_PKG")
        delay(500)

        val startCmd = "am start --windowingMode 5 -n $YANDEX_PKG/$YANDEX_ACTIVITY"
        val startResult = adb.shell(startCmd)
        Log.i(TAG, "am start: $startResult")

        if (startResult == null || startResult.contains("Error", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Не удалось запустить\nЯндекс"
            }
            return
        }

        val stackId = findStackIdWithRetry(
            YANDEX_PKG, FIND_STACK_MAX_ATTEMPTS, FIND_STACK_RETRY_DELAY_MS
        )
        if (stackId == null) {
            withContext(Dispatchers.Main) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Яндекс не найден\nв стеке"
            }
            return
        }

        val (realW, realH) = getRealScreenSize()
        val navBarHeight = getNavigationBarHeight()
        val statusBarHeight = getStatusBarHeight()

        navStackId = stackId
        navLeft = (realW * SPLIT_RATIO).toInt()
        navTop = statusBarHeight
        navRight = realW
        navBottom = realH - navBarHeight

        navigatorReady = true

        showNavigatorInternal()

        withContext(Dispatchers.Main) {
            if (isNavigatorVisible) {
                navPlaceholder.visibility = View.GONE
            } else {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Не удалось\nпоказать Яндекс"
            }
        }
    }

    private suspend fun findStackIdWithRetry(pkg: String, max: Int, delayMs: Long): Int? {
        repeat(max) {
            delay(delayMs)
            val list = adb.shell("am stack list") ?: return@repeat
            findStackIdForPackage(list, pkg)?.let { return it }
        }
        return null
    }

    private fun findStackIdForPackage(stackList: String, pkg: String): Int? {
        var cur: Int? = null
        for (line in stackList.lines()) {
            val match = Regex("""Stack id=(\d+)""").find(line)
            if (match != null) {
                cur = match.groupValues[1].toIntOrNull()
                continue
            }
            if (line.contains("$pkg/") && cur != null) return cur
        }
        return null
    }

    // ==================================================
    // Размеры экрана
    // ==================================================

    private fun getRealScreenSize(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            size.x to size.y
        } catch (e: Exception) {
            Log.e(TAG, "getRealScreenSize failed", e)
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun getNavigationBarHeight(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
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

                    // ── Порядок важен: сначала updateTrip, потом updateBmsDashboard,
                    //    затем сценарии. ──
                    updateTrip(snap)
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
            calibrationFactor = calibrationFactor
        )

        lastEstimatedConsumption = est.consumption

        bmsRange.text    = est.mainKm.toString()
        bmsRangeMax.text = est.maxKm.toString()
        bmsRangeMin.text = est.minKm.toString()

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

        if (est.factors.isNotEmpty()) {
            val factorStr = est.factors.joinToString(" · ") {
                "${it.percent}% ${it.label}"
            }
            Log.i(TAG, "Range factors: $factorStr (main=${est.mainKm}, cons=${String.format(Locale.US, "%.2f", est.consumption)})")
        }

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
            tripWasActive = false
            endMarkerWritten = false
            val id = currentTripId
            currentTripId = null
            if (id != null) {
                val loc = lastKnownLocation
                val record = TripRecord(
                    startedAt = trip.startedAt,
                    endedAt = trip.endedAt,
                    finished = true,
                    distanceKm = trip.distanceKm,
                    durationMin = trip.durationMin,
                    energyKwh = trip.energyKwh,
                    consumption = trip.consumptionPer100Km,
                    avgSpeedKmh = trip.avgSpeedKmh,
                    maxSpeedKmh = trip.maxSpeedKmh,
                    outsideTempAvgC = trip.outsideTempAvgC,
                    insideTempAvgC = trip.insideTempAvgC,
                    battTempAvgC = trip.battTempAvgC,
                    endSoc = trip.endSoc,
                    endOdometerKm = trip.endOdometerKm,
                    endLat = loc?.latitude,
                    endLon = loc?.longitude
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

                    val summary = tripRepository.getTodaySummary()
                    withContext(Dispatchers.Main) { updateDaySummaryUi(summary) }
                }
            }
        }

        val txt = if (trip.active) "В поездке" else "Поездка не начата"
        lastCaptionText = txt

        if (isNavigatorVisible) {
            captionMask.updateTrip(
                active = trip.active,
                distanceKm = trip.distanceKm,
                energyKwh = trip.energyKwh,
                consumption = trip.consumptionPer100Km,
                durationMin = trip.durationMin.toInt()
            )
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