package com.ar.bydlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ar.bydlauncher.service.BydBackgroundService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        Log.i(
            TAG,
            "Boot completed: ${intent.action}"
        )

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {

                // 1. Поднимаем anchor-service — он держит процесс лаунчера живым
                //    в фоне и сам перезапускается при kill (START_STICKY).
                try {
                    BydBackgroundService.start(context)
                    Log.i(TAG, "BydBackgroundService start requested")
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to start BydBackgroundService", e)
                }

                // 2. Запускаем MainActivity — как было в исходной версии.
                //    На случай, если система не поднимет её сама как HOME-лаунчер.
                val launchIntent =
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }

                try {
                    context.startActivity(launchIntent)
                    Log.i(TAG, "MainActivity started automatically")
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to start MainActivity", e)
                }
            }
        }
    }
}