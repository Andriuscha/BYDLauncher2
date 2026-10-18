package com.ar.bydlauncher.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Диагностический этап: только записывает доступные системе события кнопок.
 *
 * ВАЖНО: onKeyEvent всегда возвращает false, поэтому сервис не поглощает
 * нажатия и не мешает штатному поведению автомобиля.
 */
class SteeringWheelAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SteeringWheelKeys"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected; waiting for key events")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> event.action.toString()
        }

        Log.i(
            TAG,
            "action=$action keyCode=${event.keyCode} " +
                "key=${KeyEvent.keyCodeToString(event.keyCode)} " +
                "repeat=${event.repeatCount} deviceId=${event.deviceId} " +
                "scanCode=${event.scanCode} flags=${event.flags}"
        )

        // Не перехватываем кнопку на этом диагностическом этапе.
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События интерфейса пока не нужны: нас интересуют только KeyEvent.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }
}
