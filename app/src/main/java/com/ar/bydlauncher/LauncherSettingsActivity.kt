package com.ar.bydlauncher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.ar.bydlauncher.adb.AdbClient
import com.ar.bydlauncher.backup.BackupManager
import kotlinx.coroutines.*

/**
 * Настройки лаунчера.
 * Заводские экраны BYD + бэкап и восстановление данных.
 */
class LauncherSettingsActivity : Activity() {

    companion object {
        private const val TAG = "LauncherSettings"

        private const val BYD_CARSETTINGS_PKG = "com.byd.carsettings"
        private const val BYD_CARSETTINGS_ACT = "com.byd.carsettings.MainActivity"

        private const val BYD_DIAG_PKG = "com.byd.factorytest"
        private const val BYD_DIAG_ACT = "com.byd.factorytest.MainActivity"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var adb: AdbClient
    private lateinit var backupManager: BackupManager
    private lateinit var tvBackupStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher_settings)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        adb = AdbClient.ensureConnected(this)
        backupManager = BackupManager(this)

        tvBackupStatus = findViewById(R.id.tvBackupStatus)

        findViewById<View>(R.id.settingsClose).setOnClickListener { finish() }

        findViewById<View>(R.id.btnFactorySettings).setOnClickListener {
            launchBydActivity(BYD_CARSETTINGS_PKG, BYD_CARSETTINGS_ACT)
        }

        findViewById<View>(R.id.btnFactoryDiagnostics).setOnClickListener {
            launchBydActivity(BYD_DIAG_PKG, BYD_DIAG_ACT)
        }

        findViewById<View>(R.id.btnBackupExport).setOnClickListener {
            exportBackup()
        }

        findViewById<View>(R.id.btnBackupImport).setOnClickListener {
            showImportDialog()
        }

        // Показываем «последний бэкап» при открытии
        updateLastBackupInfo()
    }

    private fun updateLastBackupInfo() {
        val text = backupManager.getLastBackupText()
        tvBackupStatus.text = text
        tvBackupStatus.setTextColor(0xFFB0B4BD.toInt())
    }

    private fun launchBydActivity(pkg: String, act: String) {
        scope.launch {
            val cmd = "am start -n $pkg/$act"
            Log.i(TAG, "launchBydActivity: $cmd")
            val result = adb.shell(cmd)
            Log.i(TAG, "result: $result")
        }
    }

    // ==================================================
    //  БЭКАП — ЭКСПОРТ
    // ==================================================

    private fun exportBackup() {
        setBackupStatus("Создание бэкапа…", 0xFFB0B4BD.toInt())
        scope.launch {
            val file = backupManager.exportToDownloads()
            withContext(Dispatchers.Main) {
                if (file != null) {
                    setBackupStatus("✓ Сохранено: ${file.name}", 0xFF28C76F.toInt())
                    Toast.makeText(
                        this@LauncherSettingsActivity,
                        "Бэкап сохранён",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    setBackupStatus("✗ Ошибка создания бэкапа", 0xFFEA5455.toInt())
                }
            }
        }
    }

    // ==================================================
    //  БЭКАП — ИМПОРТ
    // ==================================================

    private fun showImportDialog() {
        val backups = backupManager.listBackups()
        if (backups.isEmpty()) {
            Toast.makeText(this, "Нет сохранённых бэкапов", Toast.LENGTH_SHORT).show()
            return
        }

        val names = backups
            .map { it.name.removePrefix(BackupManager.FILE_PREFIX) }
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Восстановить из бэкапа")
            .setItems(names) { _, which ->
                scope.launch {
                    val file = backups[which]
                    val meta = backupManager.readMeta(file)
                    val current = backupManager.readCurrentCounters()
                    withContext(Dispatchers.Main) {
                        if (meta != null) {
                            showCompareDialog(meta, current)
                        } else {
                            Toast.makeText(
                                this@LauncherSettingsActivity,
                                "Не удалось прочитать бэкап",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showCompareDialog(
        meta: BackupManager.BackupMeta,
        current: BackupManager.CurrentCounters
    ) {
        val message = buildString {
            appendLine("Бэкап от: ${meta.displayName}")
            appendLine("Версия: ${meta.appVersion}")
            appendLine()
            appendLine("                    БЭКАП   ТЕКУЩИЕ")
            appendLine("Поездок:            ${meta.tripsCount}       ${current.tripsCount}")
            appendLine("SOH замеров:        ${meta.sohMeasurementsCount}       ${current.sohMeasurementsCount}")
            appendLine("Калибровка:         ${meta.calibrationSamplesCount}       ${current.calibrationSamplesCount}")
            appendLine()
            appendLine("Все текущие данные будут заменены.")
            appendLine("Перед заменой создаётся резервная копия в Download/.")
        }

        AlertDialog.Builder(this)
            .setTitle("Восстановить из бэкапа?")
            .setMessage(message)
            .setPositiveButton("Восстановить") { _, _ ->
                importBackup(meta.file)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun importBackup(file: java.io.File) {
        setBackupStatus("Восстановление…", 0xFFB0B4BD.toInt())
        scope.launch {
            val result = backupManager.importFromFile(file)
            withContext(Dispatchers.Main) {
                when (result) {
                    is BackupManager.BackupResult.Success -> {
                        val rollback = result.rollbackPath
                            ?.substringAfterLast("/")
                            ?.let { "\nРезерв: $it" }
                            ?: ""
                        setBackupStatus(
                            "✓ Восстановлено:\n" +
                                    "Поездок: ${result.tripsCount}\n" +
                                    "SOH замеров: ${result.sohMeasurementsCount}\n" +
                                    "Калибровка: ${result.calibrationSamplesCount}$rollback",
                            0xFF28C76F.toInt()
                        )
                        Toast.makeText(
                            this@LauncherSettingsActivity,
                            "Восстановлено: ${result.tripsCount} поездок",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is BackupManager.BackupResult.Error -> {
                        setBackupStatus("✗ ${result.message}", 0xFFEA5455.toInt())
                    }
                }
            }
        }
    }

    private fun setBackupStatus(text: String, color: Int) {
        tvBackupStatus.text = text
        tvBackupStatus.setTextColor(color)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}