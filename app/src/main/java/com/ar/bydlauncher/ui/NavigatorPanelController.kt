package com.ar.bydlauncher.ui

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.ar.bydlauncher.adb.AdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Панель Яндекс.Навигатора во freeform-окне поверх лаунчера.
 *
 * Код перенесён из MainActivity без изменения логики:
 *  - запуск Яндекса (am start --windowingMode 5) и поиск его stack id;
 *  - показ/скрытие панели (am stack resize), проверка реальной видимости;
 *  - повторный показ после тапов, возврат фокуса и возврат из внутренних экранов;
 *  - кнопка «Навигация ВКЛ/ВЫКЛ» и заглушка navPlaceholder.
 *
 * Все вызовы Activity → контроллер идут через публичные методы ниже.
 */
class NavigatorPanelController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val adb: AdbClient,
    private val captionMask: CaptionMaskOverlay,
    private val navPlaceholder: TextView,
    private val labelNav: TextView
) {

    companion object {
        private const val TAG = "NavigatorPanel"

        private const val NAV_RE_SHOW_DELAY_MS = 150L
        private const val NAV_RE_SHOW_DEBOUNCE_MS = 100L
        private const val NAV_TOGGLE_GRACE_MS = 600L

        private const val YANDEX_PKG = "ru.yandex.yandexnavi"
        private const val YANDEX_ACTIVITY =
            "ru.yandex.yandexnavi.core.NavigatorActivity"

        private const val SPLIT_RATIO = 0.45
        private const val CAPTION_HEIGHT_PX = 80

        private const val FIND_STACK_MAX_ATTEMPTS = 8
        private const val FIND_STACK_RETRY_DELAY_MS = 500L

        private const val OFFSCREEN_X = 1920
        private const val OFFSCREEN_Y = 0
        private const val OFFSCREEN_W = 100
        private const val OFFSCREEN_H = 100
    }

    private var navStackId: Int = -1
    private var navLeft = 0
    private var navTop = 0
    private var navRight = 0
    private var navBottom = 0

    private var externalAppActive = false
    private var navWasVisibleBeforeExternal = false

    private var reShowJob: Job? = null
    private var lastReShowAttemptTs = 0L
    private var lastToggleTs = 0L

    @Volatile
    private var navigatorReady = false

    /** Панель сейчас показана (по нашему состоянию). */
    @Volatile
    var isVisible = true
        private set

    /** Текст подписи в маске над freeform-заголовком (обновляет Activity). */
    @Volatile
    var captionText = "Поездка не начата"

    // ==================================================
    // Вызовы из Activity
    // ==================================================

    /** Запуск Яндекса во freeform. Вызывать один раз из onCreate. */
    fun start() {
        scope.launch { delay(1500); launchYandexInFreeform() }
    }

    /** Тап по кнопке «Навигация» в доке. */
    fun onNavDockClicked() {
        lastToggleTs = System.currentTimeMillis()
        scope.launch { toggleNavigator() }
    }

    /** Из dispatchTouchEvent при ACTION_UP. */
    fun onUserTouchUp() = scheduleNavigatorReShow("dispatchTouchEvent")

    /** Из onWindowFocusChanged(hasFocus = true). */
    fun onWindowFocusGained() = scheduleNavigatorReShow("onWindowFocusChanged")

    /** Перед запуском внутреннего экрана лаунчера (настройки, поездки и т.д.). */
    fun onBeforeOpenExternal() {
        externalAppActive = true
        navWasVisibleBeforeExternal = isVisible
        runCatching { captionMask.hide() }
    }

    /**
     * Навигаторская часть onResume.
     * @return true, если это возврат из внутреннего экрана: Activity должна выйти
     * из onResume (как и раньше, без обновления сводки дня).
     */
    fun onResume(): Boolean {
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
            return true
        }

        if (navStackId >= 0 && isVisible) {
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
                            text = captionText
                        )
                    }
                }
            }
        }
        return false
    }

    // ==================================================
    // Кнопка
    // ==================================================

    private fun updateNavButtonState(visible: Boolean) {
        labelNav.text = if (visible) "Навигация ВКЛ" else "Навигация ВЫКЛ"
    }

    // ==================================================
    // Повторный показ после тапов и фокуса
    // ==================================================

    private fun scheduleNavigatorReShow(source: String) {
        if (!navigatorReady || navStackId < 0) return
        if (!isVisible) return
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
            if (!isVisible) return@launch

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
        Log.i(TAG, "toggleNavigator: isNavigatorVisible=$isVisible, reallyVisible=$reallyVisible")

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
        isVisible = false
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
                    text = captionText
                )
                updateNavButtonState(visible = true)
                isVisible = true
                Log.i(TAG, "Navigator shown (verified)")
            } else {
                Log.w(TAG, "showNavigator: окно не поднялось, visible=false")
                labelNav.text = "Навигация ?"
                isVisible = false
            }
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
            if (isVisible) {
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
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            size.x to size.y
        } catch (e: Exception) {
            Log.e(TAG, "getRealScreenSize failed", e)
            val dm = activity.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun getNavigationBarHeight(): Int {
        val resId = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) activity.resources.getDimensionPixelSize(resId) else 0
    }

    private fun getStatusBarHeight(): Int {
        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) activity.resources.getDimensionPixelSize(resId) else 0
    }
}
