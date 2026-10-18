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
    private var navTaskId: Int = -1

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

    @Volatile
    var isVisible = true
        private set

    @Volatile
    var captionText = "Поездка не начата"

    // ==================================================
    // Вызовы из Activity
    // ==================================================

    fun start() {
        scope.launch { delay(1500); launchYandexInFreeform() }
    }

    fun onNavDockClicked() {
        lastToggleTs = System.currentTimeMillis()
        scope.launch { toggleNavigator() }
    }

    fun onUserTouchUp() = scheduleNavigatorReShow("dispatchTouchEvent")

    fun onWindowFocusGained() = scheduleNavigatorReShow("onWindowFocusChanged")

    /** Открытие Activity (Настройки, Поездки и т.д.) */
    fun onBeforeOpenExternal() {
        externalAppActive = true
        navWasVisibleBeforeExternal = isVisible
        runCatching { captionMask.hide() }
    }

    /** Открытие локального Popup (Комфорт) */
    fun onOverlayShown() {
        // Просто скрываем плашку на время, чтобы она не висела поверх попапа
        runCatching { captionMask.hide() }
    }

    /** Закрытие локального Popup (Комфорт) */
    fun onOverlayDismissed() {
        if (navTaskId >= 0 && isVisible) {
            scope.launch {
                delay(100) // Даем попапу закрыться и фокусу вернуться
                Log.i(TAG, "Overlay dismissed, checking navigator state")
                val reallyVisible = isNavigatorReallyVisible()

                // Восстанавливаем плашку
                withContext(Dispatchers.Main) {
                    captionMask.show(
                        top = 0,
                        captionHeightPx = CAPTION_HEIGHT_PX,
                        text = captionText
                    )
                }

                // Если навигатор физически съехал (bounds сломались), возвращаем его
                if (!reallyVisible) {
                    Log.i(TAG, "Navigator moved after overlay, resizing back")
                    val resizeCmd = "am task resize $navTaskId $navLeft $navTop $navRight $navBottom"
                    adb.shell(resizeCmd)
                    delay(100)
                    adb.shell("am task moveToFront $navTaskId")
                }
            }
        }
    }

    fun onResume(): Boolean {
        if (externalAppActive) {
            externalAppActive = false

            if (navWasVisibleBeforeExternal && navTaskId >= 0) {
                scope.launch {
                    delay(150)
                    Log.i(TAG, "Returning from external app: forcing navigator restart")
                    isVisible = false
                    withContext(Dispatchers.Main) {
                        updateNavButtonState(visible = false)
                        labelNav.text = "Навигация ?"
                        captionMask.hide()
                    }
                    adb.shell("am force-stop $YANDEX_PKG")
                    delay(200)
                    launchYandexInFreeform()
                }
            } else if (navTaskId >= 0) {
                updateNavButtonState(visible = false)
            }
            navWasVisibleBeforeExternal = false
            return true
        }

        if (navTaskId >= 0 && isVisible) {
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
    // Внутренняя логика
    // ==================================================

    private fun updateNavButtonState(visible: Boolean) {
        labelNav.text = if (visible) "Навигация ВКЛ" else "Навигация ВЫКЛ"
    }

    private fun scheduleNavigatorReShow(source: String) {
        if (!navigatorReady || navTaskId < 0) return
        if (!isVisible) return
        if (externalAppActive) return

        val now = System.currentTimeMillis()

        if (now - lastToggleTs < NAV_TOGGLE_GRACE_MS) return
        if (now - lastReShowAttemptTs < NAV_RE_SHOW_DEBOUNCE_MS) return
        lastReShowAttemptTs = now

        reShowJob?.cancel()
        reShowJob = scope.launch {
            delay(NAV_RE_SHOW_DELAY_MS)
            if (externalAppActive) return@launch
            if (!navigatorReady || navTaskId < 0) return@launch
            if (!isVisible) return@launch

            Log.i(TAG, "scheduleNavigatorReShow($source): re-show navigator")
            showNavigatorInternal()
        }
    }

    private suspend fun isNavigatorReallyVisible(): Boolean {
        if (navTaskId < 0) return false
        val list = adb.shell("am stack list") ?: return false
        val rx = Regex(
            """taskId=$navTaskId:\s+\S+.*?bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""
        )
        val m = rx.find(list) ?: return false
        val left = m.groupValues[1].toIntOrNull() ?: return false
        return left < OFFSCREEN_X
    }

    private suspend fun toggleNavigator() {
        if (navTaskId < 0) return
        val reallyVisible = isNavigatorReallyVisible()
        if (reallyVisible) hideNavigatorInternal() else showNavigatorInternal()
    }

    private suspend fun hideNavigatorInternal() {
        if (navTaskId < 0) return
        val cmd = "am task resize $navTaskId " +
                "$OFFSCREEN_X $OFFSCREEN_Y " +
                "${OFFSCREEN_X + OFFSCREEN_W} ${OFFSCREEN_Y + OFFSCREEN_H}"
        adb.shell(cmd)
        delay(200)

        withContext(Dispatchers.Main) {
            captionMask.hide()
            updateNavButtonState(visible = false)
        }
        isVisible = false
    }

    private suspend fun showNavigatorInternal() {
        if (navTaskId < 0) return

        val resizeCmd = "am task resize $navTaskId " +
                "$navLeft $navTop $navRight $navBottom"
        adb.shell(resizeCmd)
        delay(150)

        val reShowCmd = "am start --windowingMode 5 " +
                "-n $YANDEX_PKG/$YANDEX_ACTIVITY"
        adb.shell(reShowCmd)
        delay(300)

        adb.shell(resizeCmd)
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
            } else {
                labelNav.text = "Навигация ?"
                isVisible = false
                navTaskId = -1
            }
        }
    }

    private suspend fun launchYandexInFreeform() {
        val overlayGrant = adb.shell("appops set com.ar.bydlauncher SYSTEM_ALERT_WINDOW allow")
        val freeformEnabled = adb.shell("settings get global enable_freeform_support")?.trim()
        val forceResizable = adb.shell("settings get global force_resizable_activities")?.trim()

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

        if (startResult == null || startResult.contains("Error", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Не удалось запустить\nЯндекс"
            }
            return
        }

        val ids = findTaskAndStackIdsWithRetry(
            YANDEX_PKG, FIND_STACK_MAX_ATTEMPTS, FIND_STACK_RETRY_DELAY_MS
        )
        if (ids == null) {
            withContext(Dispatchers.Main) {
                navPlaceholder.visibility = View.VISIBLE
                navPlaceholder.text = "Яндекс не найден\nв стеке"
            }
            return
        }

        val (taskId, stackId) = ids
        navTaskId = taskId
        navStackId = stackId

        val (realW, realH) = getRealScreenSize()
        val navBarHeight = getNavigationBarHeight()
        val statusBarHeight = getStatusBarHeight()

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

    private suspend fun findTaskAndStackIdsWithRetry(
        pkg: String,
        max: Int,
        delayMs: Long
    ): Pair<Int, Int>? {
        repeat(max) {
            delay(delayMs)
            val list = adb.shell("am stack list") ?: return@repeat
            findTaskAndStackIdsForPackage(list, pkg)?.let { return it }
        }
        return null
    }

    private fun findTaskAndStackIdsForPackage(stackList: String, pkg: String): Pair<Int, Int>? {
        var currentStackId: Int? = null
        for (line in stackList.lines()) {
            val stackMatch = Regex("""Stack id=(\d+)""").find(line)
            if (stackMatch != null) {
                currentStackId = stackMatch.groupValues[1].toIntOrNull()
                continue
            }
            if (line.contains("$pkg/") && currentStackId != null) {
                val taskMatch = Regex("""taskId=(\d+)""").find(line)
                val taskId = taskMatch?.groupValues?.get(1)?.toIntOrNull()
                if (taskId != null) {
                    return Pair(taskId, currentStackId)
                }
            }
        }
        return null
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        return try {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            size.x to size.y
        } catch (e: Exception) {
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