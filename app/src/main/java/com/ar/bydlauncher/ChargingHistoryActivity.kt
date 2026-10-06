package com.ar.bydlauncher

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Заглушка — история зарядок появится позже.
 *
 * TODO: таблица `charges` в TripDatabase + ChargeRepository + рендер списка.
 */
class ChargingHistoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_charging_history)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        findViewById<View>(R.id.chargingClose).setOnClickListener { finish() }
    }
}