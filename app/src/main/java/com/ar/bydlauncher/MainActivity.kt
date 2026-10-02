package com.ar.bydlauncher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
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
import com.ar.bydlauncher.location.CarLocationProvider
import com.ar.bydlauncher.ui.CaptionMaskOverlay
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
import com.ar.bydlauncher.service.BydBackgroundService

class MainActivity : Activity() {

    private lateinit var adb: AdbClient
    private lateinit var ac: AutoserviceClient
    private lateinit var reader: BmsReader

    private lateinit var contentText: TextView

    // ── Погодный виджет (native) ──
    private lateinit var wIcon: ImageView
    private lateinit var wLocName: TextView
    private lateinit var wTemp: TextView
    private lateinit var wCond: TextView
    private lateinit var wDay: TextView
    private lateinit var wNight: TextView
    private lateinit var wOut: TextView
    private lateinit var wIn: TextView

    private lateinit var coilLoader: ImageLoader

    private lateinit var navContainer: FrameLayout
    private lateinit var navPlaceholder: TextView

    // ── Кнопки дока: LinearLayout ──
    private lateinit var btnNav: LinearLayout
    private lateinit var btnCar: LinearLayout
    private lateinit var btnClimate: LinearLayout
    private lateinit var btnTrip: LinearLayout

    // ── Текстовые лейблы внутри кнопок ──
    private lateinit var labelNav: TextView
    private lateinit var labelCar: TextView
    private lateinit var labelClimate: TextView
    private lateinit var labelTrip: TextView

    private lateinit var sohCalc: SohCalculator
    private lateinit var captionMask: CaptionMaskOverlay

    private lateinit var carLocationProvider: CarLocationProvider
    private lateinit var tripRepository: TripRepository
    private var currentTripId: Long? = null
    private var tripWasActive = false
    private var lastLiveUpdateTs = 0L
    private var endMarkerWritten = false
    private val tripDetector = TripDetector()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Состояние навигатора ─────────────────────────
    private var navStackId: Int = -1
    private var navLeft = 0
    private var navTop = 0
    private var navRight = 0
    private var navBottom = 0
    private var isNavigatorVisible = true

    // ── Флаг: мы ушли в BYD-приложение и должны вернуться ──
    private var externalAppActive = false

    // ── Была ли навигация видима в момент ухода во внешнее приложение ──
    private var navWasVisibleBeforeExternal = false

    // ── Последний текст плашки, чтобы восстановить после возврата ──
    private var lastCaptionText = "Поездка не начата"

    // ── Кеш последней удачной GPS-локации ──
    @Volatile
    private var lastKnownLocation: Location? = null

    // ── Сигнал «GPS обновился» ──
    private val gpsReadySignal = Channel<Unit>(Channel.CONFLATED)

    companion object {
        private const val TAG = "MainActivity"

        private const val POLL_INTERVAL_MS = 1000L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val LIVE_UPDATE_INTERVAL_MS = 30_000L

        private val COLOR_CHARGE = Color.parseColor("#5BE05B")
        private val COLOR_DISCHARGE = Color.parseColor("#FF5A5A")

        private const val YANDEX_PKG = "ru.yandex.yandexnavi"
        private const val YANDEX_ACTIVITY =
            "ru.yandex.yandexnavi.core.NavigatorActivity"

        private const val NOMINAL_KWH = 44.9
        private const val SPLIT_RATIO = 0.45
        private const val CAPTION_HEIGHT_PX = 60

        private const val FIND_STACK_MAX_ATTEMPTS = 8
        private const val FIND_STACK_RETRY_DELAY_MS = 500L

        private const val GPS_REFRESH_MS = 5 * 60_000L
        private const val GPS_RETRY_MS = 15_000L
        private const val GPS_MAX_RETRY_MS = 60_000L

        private const val WEATHER_REFRESH_MS = 30 * 60_000L
        private const val WEATHER_RETRY_MS = 15_000L
        private const val WEATHER_MAX_RETRY_MS = 60_000L

        private const val LOCATION_PERMISSION_REQUEST = 1001

        private const val BYD_CARSETTINGS_PKG = "com.byd.carsettings"
        private const val BYD_CARSETTINGS_ACT = "com.byd.carsettings.MainActivity"

        private const val BYD_CLIMATE_PKG = "com.byd.airconditioning"
        private const val BYD_CLIMATE_ACT =
            "com.byd.airconditioning.mainactivity.FullScreenMainActivity"

        private const val BYD_TRAVEL_PKG = "com.byd.smarttravel"
        private const val BYD_TRAVEL_ACT = "com.byd.smarttravel.MainActivity"

        private const val OFFSCREEN_X = 1920
        private const val OFFSCREEN_Y = 0
        private const val OFFSCREEN_W = 100
        private const val OFFSCREEN_H = 100

        // CDN Meteocons — те же иконки, что и в HTML-виджете
        private const val METEO_BASE =
            "https://bmcdn.nl/assets/weather-icons/v3.0/fill/svg/"
    }

    // ==================================================
    // Lifecycle
    // ==================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        contentText = findViewById(R.id.contentText)

        // ── Погодный виджет ──
        wIcon    = findViewById(R.id.wIcon)
        wLocName = findViewById(R.id.wLocName)
        wTemp    = findViewById(R.id.wTemp)
        wCond    = findViewById(R.id.wCond)
        wDay     = findViewById(R.id.wDay)
        wNight   = findViewById(R.id.wNight)
        wOut     = findViewById(R.id.wOut)
        wIn      = findViewById(R.id.wIn)

        // ── Coil-лоадер для SVG-иконок погоды ──
        coilLoader = ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()

        navContainer = findViewById(R.id.navContainer)
        navPlaceholder = findViewById(R.id.navPlaceholder)

        // ── Кнопки дока ──
        btnNav = findViewById(R.id.btnNav)
        btnCar = findViewById(R.id.btnCar)
        btnClimate = findViewById(R.id.btnClimate)
        btnTrip = findViewById(R.id.btnTrip)

        labelNav = btnNav.findViewById(R.id.labelNav)
        labelCar = btnCar.findViewById(R.id.labelCar)
        labelClimate = btnClimate.findViewById(R.id.labelClimate)
        labelTrip = btnTrip.findViewById(R.id.labelTrip)

        adb = AdbClient(this)
        ac = AutoserviceClient(adb)
        reader = BmsReader(ac)
        sohCalc = SohCalculator(File(filesDir, "soh_data.json"), nominalKwh = NOMINAL_KWH)
        captionMask = CaptionMaskOverlay(this)
        carLocationProvider = CarLocationProvider(this)
        tripRepository = TripRepository(this)
        scope.launch {
            val orphans = tripRepository.getUnfinished()
            if (orphans.isNotEmpty()) {
                Log.w(TAG, "Found ${orphans.size} orphan unfinished trips, closing them")
                for (o in orphans) {
                    tripRepository.finish(o.id, TripRecord(
                        startedAt = o.startedAt,
                        endedAt = o.endedAt ?: (o.startedAt + o.durationMin * 60_000L),
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
                    Log.w(TAG, "Closed orphan id=${o.id}")
                }
            }
        }
        BydBackgroundService.start(this)

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
        }
    }

    // ==================================================
    // Нижние кнопки
    // ==================================================

    private fun setupBottomButtons() {
        btnNav.setOnClickListener {
            selectDockButton(btnNav)
            scope.launch { toggleNavigator() }
        }
        btnCar.setOnClickListener {
            selectDockButton(btnCar)
            scope.launch { launchBydApp(BYD_CARSETTINGS_PKG, BYD_CARSETTINGS_ACT) }
        }
        btnClimate.setOnClickListener {
            selectDockButton(btnClimate)
            scope.launch { launchBydApp(BYD_CLIMATE_PKG, BYD_CLIMATE_ACT) }
        }
        btnTrip.setOnClickListener {
            externalAppActive = true
            navWasVisibleBeforeExternal = isNavigatorVisible
            captionMask.hide()
            startActivity(Intent(this@MainActivity, TripsActivity::class.java))
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
    // Проверка реальной видимости окна навигации
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

        if (reallyVisible) {
            hideNavigatorInternal()
        } else {
            showNavigatorInternal()
        }
    }

    // ==================================================
    // Скрытие навигатора
    // ==================================================

    private suspend fun hideNavigatorInternal() {
        val cmd = "am stack resize $navStackId " +
                "$OFFSCREEN_X $OFFSCREEN_Y " +
                "${OFFSCREEN_X + OFFSCREEN_W} ${OFFSCREEN_Y + OFFSCREEN_H}"
        Log.i(TAG, "hideNavigator cmd: $cmd")
        val r = adb.shell(cmd)
        Log.i(TAG, "hideNavigator result: $r")
        delay(200)
        Log.i(TAG, "hideNavigator stack list:\n${adb.shell("am stack list")}")

        withContext(Dispatchers.Main) {
            captionMask.hide()
            updateNavButtonState(visible = false)
        }
        isNavigatorVisible = false
        Log.i(TAG, "Navigator hidden")
    }

    // ==================================================
    // Показ навигатора
    // ==================================================

    private suspend fun showNavigatorInternal() {
        if (navStackId < 0) {
            Log.w(TAG, "showNavigatorInternal: navStackId неизвестен")
            return
        }

        val cmd = "am stack resize $navStackId " +
                "$navLeft $navTop $navRight $navBottom"
        Log.i(TAG, "showNavigator resize cmd: $cmd")
        val r = adb.shell(cmd)
        Log.i(TAG, "showNavigator resize result: $r")
        delay(150)

        val reorderCmd = "am start --activity-reorder-to-front " +
                "-n $YANDEX_PKG/$YANDEX_ACTIVITY"
        val reorderResult = adb.shell(reorderCmd)
        Log.i(TAG, "showNavigator reorderToFront: $reorderResult")
        delay(200)

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

        val stackList = adb.shell("am stack list") ?: ""
        Log.i(TAG, "showNavigator stack list (after jiggle):\n$stackList")

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
    // Запуск BYD-приложения
    // ==================================================

    private suspend fun launchBydApp(pkg: String, activity: String) {
        externalAppActive = true
        navWasVisibleBeforeExternal = isNavigatorVisible

        withContext(Dispatchers.Main) {
            captionMask.hide()
        }

        val cmd = "am start -n $pkg/$activity"
        Log.i(TAG, "launchBydApp: $cmd")
        val result = adb.shell(cmd)
        Log.i(TAG, "result: $result")

        if (result == null || result.contains("Error", ignoreCase = true)) {
            externalAppActive = false
            withContext(Dispatchers.Main) {
                if (navWasVisibleBeforeExternal && isNavigatorVisible) {
                    captionMask.show(
                        top = 0,
                        captionHeightPx = CAPTION_HEIGHT_PX,
                        text = lastCaptionText
                    )
                }
            }
            navWasVisibleBeforeExternal = false
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

        Log.i(
            TAG,
            "screen: realW=$realW realH=$realH navBar=$navBarHeight statusBar=$statusBarHeight"
        )

        navStackId = stackId
        navLeft = (realW * SPLIT_RATIO).toInt()
        navTop = statusBarHeight
        navRight = realW
        navBottom = realH - navBarHeight

        Log.i(
            TAG,
            "nav bounds: L=$navLeft T=$navTop R=$navRight B=$navBottom"
        )

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
    // Размеры экрана и системных баров
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
            Log.e(TAG, "getRealScreenSize failed, falling back to displayMetrics", e)
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun getNavigationBarHeight(): Int {
        val resId = resources.getIdentifier(
            "navigation_bar_height", "dimen", "android"
        )
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
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
                Log.i(
                    TAG,
                    "GPS OK (attempt #$attempt): lat=${location.latitude}, " +
                            "lon=${location.longitude}, acc=${location.accuracy}m"
                )
                withContext(Dispatchers.Main) {
                    wLocName.text = "%.4f, %.4f".format(
                        location.latitude, location.longitude
                    )
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
                withTimeoutOrNull(WEATHER_REFRESH_MS) {
                    gpsReadySignal.receive()
                }
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
                withContext(Dispatchers.Main) {
                    wLocName.text = "Ожидание GPS..."
                }
                delay(
                    minOf(
                        WEATHER_RETRY_MS * (1L shl minOf(attempt - 1, 4)),
                        WEATHER_MAX_RETRY_MS
                    )
                )
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

    /**
     * Тянет погоду по локации и заполняет виджет + грузит иконку с CDN Meteocons.
     */
    private suspend fun loadWeatherFor(location: Location): Boolean {
        val locationName = getLocationName(location)

        withContext(Dispatchers.Main) {
            wLocName.text = locationName
            wCond.text = "Загрузка…"
        }

        val w = WeatherClient.fetch(this@MainActivity, location.latitude, location.longitude)
        if (w == null) {
            withContext(Dispatchers.Main) {
                wCond.text = "Нет данных"
            }
            return false
        }

        withContext(Dispatchers.Main) {
            wLocName.text = locationName
            wTemp.text    = "%.0f°".format(w.temperature)
            wCond.text    = w.description.uppercase()
            wDay.text     = "%.0f°".format(w.tempMax)
            wNight.text   = "%.0f°".format(w.tempMin)
            wOut.text     = "%.0f°".format(w.temperature)
            // wIn (Салон) обновляется из BMS в updateTemperatures()

            val iconFile = weatherIconName(w.icon, w.description)
            val url = METEO_BASE + iconFile
            wIcon.load(url, coilLoader) {
                placeholder(R.drawable.ic_weather_placeholder)
                error(R.drawable.ic_weather_placeholder)
                crossfade(true)
            }
        }
        return true
    }

    /**
     * Маппинг описания/иконки погоды на имя SVG-файла Meteocons.
     */
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
    // Название города по координатам
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
                    Log.w(TAG, "Nominatim HTTP ${conn.responseCode}")
                    conn.disconnect()
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                val address = json.optJSONObject("address")
                    ?: return@withContext null

                val city = address.optString("city").takeIf { it.isNotBlank() }
                    ?: address.optString("town").takeIf { it.isNotBlank() }
                    ?: address.optString("village").takeIf { it.isNotBlank() }
                    ?: address.optString("municipality").takeIf { it.isNotBlank() }
                    ?: address.optString("county").takeIf { it.isNotBlank() }
                    ?: address.optString("state").takeIf { it.isNotBlank() }
                    ?: address.optString("country").takeIf { it.isNotBlank() }

                Log.i(TAG, "Nominatim city: $city  (lat=$lat lon=$lon)")
                city
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Nominatim failed: ${e.javaClass.simpleName}: ${e.message}",
                    e
                )
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
                    setContent("Нет ответа от BMS ($fails/$MAX_CONSECUTIVE_FAILURES)...")
                    if (fails >= MAX_CONSECUTIVE_FAILURES) {
                        setContent("Потеряна связь с ADB. Переподключение...")
                        adb.disconnect()
                        break@pollLoop
                    }
                } else {
                    fails = 0
                    sohCalc.onSnapshot(snap)
                    setContent(format(snap))
                    updateTemperatures(snap)
                    updateTrip(snap)
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Обновляет чипы «Улица» и «Салон» из BMS.
     * Иконку «Улица» погода тоже пишет — кто последний, тот и прав.
     */
    private suspend fun updateTemperatures(s: BatterySnapshot) = withContext(Dispatchers.Main) {
        s.tempOutsideC?.let { wOut.text = "$it°" }
        s.tempInsideC?.let  { wIn.text  = "$it°" }
    }

    private suspend fun updateTrip(s: BatterySnapshot) = withContext(Dispatchers.Main) {
        val trip = tripDetector.onSnapshot(
            ts = System.currentTimeMillis(),
            gearMode = s.gearMode,
            speedKmh = s.speedKmh,
            mileageKm = s.lifetimeKm,
            lifetimeKwh = s.lifetimeKwh,
            powerLevel = s.powerLevel,
            socPercent = s.socPercent,
            tempOutsideC = s.tempOutsideC,
            tempInsideC = s.tempInsideC,
            battTempC = s.maxBatTempC,
            driverBeltBuckled = s.driverBeltBuckled,
            accelPercent = s.accelPercent
        )

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
                currentTripId = tripRepository.insertStart(record)
                Log.i(TAG, "Trip started in DB, id=$currentTripId")
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
                scope.launch { tripRepository.updateLive(id, record) }
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
                scope.launch {
                    tripRepository.finish(id, record)
                    Log.i(TAG, "Trip finished in DB, id=$id")
                }
            }
        }

        val txt = if (trip.active) {
            "🚗  %.1f км  ·  %.2f кВт·ч  ·  %.1f кВт·ч/100км  ·  %d мин"
                .format(
                    trip.distanceKm,
                    trip.energyKwh,
                    trip.consumptionPer100Km,
                    trip.durationMin
                )
        } else {
            "Поездка не начата"
        }

        lastCaptionText = txt
        if (isNavigatorVisible) {
            captionMask.updateText(txt)
        }
    }

    private suspend fun CoroutineScope.connectWithRetry(): Boolean {
        while (isActive) {
            setContent("Подключение к ADB...")
            if (adb.connect()) {
                setContent("Подключено. Читаю BMS...")
                return true
            }
            setContent(
                "Ошибка подключения ADB.\n" +
                        "Повтор через ${RECONNECT_DELAY_MS / 1000} с..."
            )
            delay(RECONNECT_DELAY_MS)
        }
        return false
    }

    private suspend fun setContent(text: CharSequence) = withContext(Dispatchers.Main) {
        contentText.text = text
    }

    // ==================================================
    // Форматирование BMS
    // ==================================================

    private fun format(s: BatterySnapshot): CharSequence {
        val computedPowerKw: Double? =
            if (s.hvVoltageV != null && s.hvCurrentA != null)
                s.hvVoltageV.toDouble() * s.hvCurrentA.toDouble() / 1000.0
            else null

        fun fmt3(v: Double?): String = v?.let { "%.3f".format(it) } ?: "—"
        fun fmt2(v: Float?): String = v?.let { "%.2f".format(it) } ?: "—"
        fun fmtPower(v: Double?): String = v?.let {
            if (Math.abs(it) >= 10.0) "%.1f".format(it) else "%.3f".format(it)
        } ?: "—"

        val sb = StringBuilder()
        sb.appendLine("SOC:            ${s.socPercent ?: "—"} %")
        sb.appendLine("SOH OEM:        ${s.sohPercent ?: "—"} %")

        val calcSoh = sohCalc.getCurrentSoh()
        val validCount = sohCalc.getValidMeasurements().size
        val calcSohStr = when {
            sohCalc.isChargeInProgress() -> "идёт зарядка..."
            calcSoh != null -> "%.1f".format(calcSoh) + " %  ($validCount зам.)"
            else -> "нет замеров"
        }
        sb.appendLine("SOH расчётный:  $calcSohStr")
        sb.appendLine("Ячейка max:     ${fmt3(s.maxCellV)} V")
        sb.appendLine("Ячейка min:     ${fmt3(s.minCellV)} V")
        sb.appendLine("Δ ячеек:        ${s.cellDeltaMv ?: "—"} мВ")
        sb.appendLine("Батарея t max:  ${s.maxBatTempC ?: "—"} °C")
        sb.appendLine("Батарея t min:  ${s.minBatTempC ?: "—"} °C")
        sb.appendLine("HV напряжение:  ${s.hvVoltageV ?: "—"} В")

        if (computedPowerKw != null) {
            val absKw = Math.abs(computedPowerKw)
            val word = when {
                computedPowerKw < -0.1 -> "заряд"
                computedPowerKw > 0.1 -> "разряд"
                else -> "покой"
            }
            val nativeStr = s.powerKw?.let { "$it кВт" } ?: "—"
            sb.appendLine("Мощность:       $word ${fmtPower(absKw)} кВт  (родная: $nativeStr)")
        } else {
            sb.appendLine("Мощность:       —")
        }

        sb.appendLine("12V батарея:    ${fmt2(s.voltage12v)} В")
        sb.appendLine("Изоляция:       ${s.insulationKohm ?: "—"} кОм")
        sb.appendLine("Расход:         ${s.lifetimeKwh ?: "—"} кВт·ч")
        sb.appendLine("Пробег:         ${s.lifetimeKm ?: "—"} км")
        sb.appendLine("BMS state:      ${s.bmsState ?: "—"}")
        sb.appendLine("КПП:            ${s.gearMode ?: "—"}   (actual: ${s.gearActualRaw ?: "—"})  [P=1 R=2 N=3 D=4]")
        val beltStr = when (s.driverBeltBuckled) {
            true -> "пристёгнут"
            false -> "отстёгнут"
            null -> "?"
        }
        sb.appendLine("Ремень: main=${s.beltMainRaw ?: "—"} приб.=${s.beltInstrRaw ?: "—"} LF=${s.beltLfRaw ?: "—"}  → $beltStr")
        sb.appendLine("Педаль газа:    ${s.accelRaw ?: "—"} raw  →  ${s.accelPercent?.let { "%.0f".format(it) } ?: "—"} %")

        val text = sb.toString()
        val spannable = SpannableString(text)

        var idx = 0
        while (true) {
            idx = text.indexOf("разряд", idx); if (idx < 0) break
            spannable.setSpan(
                ForegroundColorSpan(COLOR_DISCHARGE), idx, idx + 6,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            idx += 6
        }
        idx = 0
        while (true) {
            idx = text.indexOf("заряд", idx); if (idx < 0) break
            val insideRazryad = idx >= 2 && text.substring(idx - 2, idx) == "ра"
            if (!insideRazryad) {
                spannable.setSpan(
                    ForegroundColorSpan(COLOR_CHARGE), idx, idx + 5,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            idx += 5
        }
        return spannable
    }

    // ==================================================
    // Завершение
    // ==================================================

    override fun onDestroy() {
        captionMask.hide()
        gpsReadySignal.close()
        tripRepository.close()
        scope.cancel()
        adb.disconnect()
        super.onDestroy()
    }
}