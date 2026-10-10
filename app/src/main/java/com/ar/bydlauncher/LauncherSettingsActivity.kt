package com.ar.bydlauncher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ar.bydlauncher.adb.AdbClient
import com.ar.bydlauncher.backup.BackupManager
import com.ar.bydlauncher.byd.AutoserviceClient
import com.ar.bydlauncher.scenario.ScenarioEngine
import com.ar.bydlauncher.scenario.ScenarioSettingsRepository
import kotlinx.coroutines.*

class LauncherSettingsActivity : Activity() {

    companion object {
        private const val TAG = "LauncherSettings"

        private const val BYD_CARSETTINGS_PKG = "com.byd.carsettings"
        private const val BYD_CARSETTINGS_ACT = "com.byd.carsettings.MainActivity"

        private const val BYD_DIAG_PKG = "com.byd.factorytest"
        private const val BYD_DIAG_ACT = "com.byd.factorytest.MainActivity"

        private const val COLOR_ACTIVE_TEXT   = 0xFFFFFFFF.toInt()
        private const val COLOR_INACTIVE_TEXT = 0xFFB0B4BD.toInt()
        private const val COLOR_ACTIVE_TINT   = 0xFFFFFFFF.toInt()
        private const val COLOR_INACTIVE_TINT = 0xFFB0B4BD.toInt()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var adb: AdbClient
    private lateinit var backupManager: BackupManager
    private lateinit var tvBackupStatus: TextView

    // ── Сценарии ──
    private lateinit var scenarioRepo: ScenarioSettingsRepository
    private lateinit var scenarioEngine: ScenarioEngine

    // ── навигация ──
    private lateinit var navFactory: LinearLayout
    private lateinit var navBackup: LinearLayout
    private lateinit var navScenarios: LinearLayout
    private lateinit var navVisual: LinearLayout

    private lateinit var navFactoryLabel: TextView
    private lateinit var navBackupLabel: TextView
    private lateinit var navScenariosLabel: TextView
    private lateinit var navVisualLabel: TextView

    // ── разделы ──
    private lateinit var sectionFactory: View
    private lateinit var sectionBackup: View
    private lateinit var sectionScenarios: View
    private lateinit var sectionVisual: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher_settings)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        adb = AdbClient.ensureConnected(this)
        backupManager = BackupManager(this)

        // ── Сценарии ──
        scenarioRepo = ScenarioSettingsRepository(this)
        scenarioEngine = ScenarioEngine(scenarioRepo, AutoserviceClient(adb))

        // ── разделы ──
        sectionFactory   = findViewById(R.id.sectionFactory)
        sectionBackup    = findViewById(R.id.sectionBackup)
        sectionScenarios = findViewById(R.id.sectionScenarios)
        sectionVisual    = findViewById(R.id.sectionVisual)

        // ── навигация ──
        navFactory   = findViewById(R.id.navFactory)
        navBackup    = findViewById(R.id.navBackup)
        navScenarios = findViewById(R.id.navScenarios)
        navVisual    = findViewById(R.id.navVisual)

        navFactoryLabel   = findViewById(R.id.navFactoryLabel)
        navBackupLabel    = findViewById(R.id.navBackupLabel)
        navScenariosLabel = findViewById(R.id.navScenariosLabel)
        navVisualLabel    = findViewById(R.id.navVisualLabel)

        // ── header ──
        findViewById<View>(R.id.settingsClose).setOnClickListener { finish() }

        // ── клики по навигации ──
        navFactory.setOnClickListener   { showSection(Section.FACTORY) }
        navBackup.setOnClickListener    { showSection(Section.BACKUP) }
        navScenarios.setOnClickListener { showSection(Section.SCENARIOS) }
        navVisual.setOnClickListener    { showSection(Section.VISUAL) }

        // ── заводские ──
        findViewById<View>(R.id.btnFactorySettings).setOnClickListener {
            launchBydActivity(BYD_CARSETTINGS_PKG, BYD_CARSETTINGS_ACT)
        }
        findViewById<View>(R.id.btnFactoryDiagnostics).setOnClickListener {
            launchBydActivity(BYD_DIAG_PKG, BYD_DIAG_ACT)
        }

        // ── бэкапы ──
        tvBackupStatus = findViewById(R.id.tvBackupStatus)
        findViewById<View>(R.id.btnBackupExport).setOnClickListener { exportBackup() }
        findViewById<View>(R.id.btnBackupImport).setOnClickListener { showImportDialog() }

        // ── сценарии ──
        setupScenarios()

        // ── визуал (заглушки) ──
        findViewById<View>(R.id.visualTheme).setOnClickListener {
            Toast.makeText(this, "Выбор темы в разработке", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.visualAutoTime).setOnClickListener {
            Toast.makeText(this, "Авто-переключение в разработке", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.visualAmbient).setOnClickListener {
            Toast.makeText(this, "Реакция на освещённость в разработке", Toast.LENGTH_SHORT).show()
        }

        // ── начальный раздел ──
        showSection(Section.FACTORY)

        // ── обновить статус бэкапа ──
        updateLastBackupInfo()
    }

    // ==================================================
    //  Сценарии
    // ==================================================

    private fun setupScenarios() {
        // ── ДХО при P ──
        val rowDho = findViewById<View>(R.id.scenarioDho)
        val cbDho = findViewById<View>(R.id.cbDho)
        var dhoEnabled = scenarioRepo.dhoParkingEnabled
        renderCheckbox(cbDho, dhoEnabled)

        rowDho.setOnClickListener {
            dhoEnabled = !dhoEnabled
            scenarioRepo.dhoParkingEnabled = dhoEnabled
            renderCheckbox(cbDho, dhoEnabled)
            Toast.makeText(
                this,
                if (dhoEnabled) "Сценарий «ДХО при P» включён" else "Сценарий «ДХО при P» выключен",
                Toast.LENGTH_SHORT
            ).show()

            // При выключении — сброс, чтобы ДХО вернулись в исходное
            if (!dhoEnabled) {
                scope.launch {
                    runCatching { scenarioEngine.resetAll() }
                        .onFailure { Log.e(TAG, "resetAll failed", it) }
                }
            }
        }

        // ── Подогрев сидений — заглушка ──
        val rowSeat = findViewById<View>(R.id.scenarioSeatHeat)
        val cbSeat = findViewById<View>(R.id.cbSeatHeat)
        renderCheckbox(cbSeat, scenarioRepo.seatHeatOffEnabled)

        rowSeat.setOnClickListener {
            Toast.makeText(this, "Сценарий в разработке", Toast.LENGTH_SHORT).show()
        }

        // ── Климат — заглушка ──
        val rowClimate = findViewById<View>(R.id.scenarioClimate)
        val cbClimate = findViewById<View>(R.id.cbClimate)
        renderCheckbox(cbClimate, scenarioRepo.climateEcoEnabled)

        rowClimate.setOnClickListener {
            Toast.makeText(this, "Сценарий в разработке", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderCheckbox(cb: View, checked: Boolean) {
        cb.setBackgroundResource(
            if (checked) R.drawable.bg_settings_checkbox_checked
            else R.drawable.bg_settings_checkbox
        )
    }

    // ==================================================
    //  Переключение разделов
    // ==================================================

    private enum class Section { FACTORY, BACKUP, SCENARIOS, VISUAL }

    private fun showSection(section: Section) {
        sectionFactory.visibility   = View.GONE
        sectionBackup.visibility    = View.GONE
        sectionScenarios.visibility = View.GONE
        sectionVisual.visibility    = View.GONE

        when (section) {
            Section.FACTORY   -> sectionFactory.visibility   = View.VISIBLE
            Section.BACKUP    -> sectionBackup.visibility    = View.VISIBLE
            Section.SCENARIOS -> sectionScenarios.visibility = View.VISIBLE
            Section.VISUAL    -> sectionVisual.visibility    = View.VISIBLE
        }

        setNavActive(navFactory,   navFactoryLabel,   section == Section.FACTORY)
        setNavActive(navBackup,    navBackupLabel,    section == Section.BACKUP)
        setNavActive(navScenarios, navScenariosLabel, section == Section.SCENARIOS)
        setNavActive(navVisual,    navVisualLabel,    section == Section.VISUAL)
    }

    private fun setNavActive(item: LinearLayout, label: TextView, active: Boolean) {
        if (active) {
            item.setBackgroundResource(R.drawable.bg_settings_nav_active)
            label.setTextColor(COLOR_ACTIVE_TEXT)
        } else {
            item.setBackgroundResource(R.drawable.bg_settings_nav_inactive)
            label.setTextColor(COLOR_INACTIVE_TEXT)
        }

        (item.getChildAt(0) as? ImageView)?.setColorFilter(
            if (active) COLOR_ACTIVE_TINT else COLOR_INACTIVE_TINT
        )
    }

    // ==================================================
    //  Заводские BYD
    // ==================================================

    private fun launchBydActivity(pkg: String, act: String) {
        scope.launch {
            val cmd = "am start -n $pkg/$act"
            Log.i(TAG, "launchBydActivity: $cmd")
            val result = adb.shell(cmd)
            Log.i(TAG, "result: $result")
        }
    }

    // ==================================================
    //  Бэкап
    // ==================================================

    private fun updateLastBackupInfo() {
        val text = backupManager.getLastBackupText()
        tvBackupStatus.text = text
        tvBackupStatus.setTextColor(0xFF6E7689.toInt())
    }

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
            appendLine("Зарядок:            ${meta.chargingsCount}       ${current.chargingsCount}")
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
                                    "Зарядок: ${result.chargingsCount}\n" +
                                    "SOH замеров: ${result.sohMeasurementsCount}\n" +
                                    "Калибровка: ${result.calibrationSamplesCount}$rollback",
                            0xFF28C76F.toInt()
                        )
                        Toast.makeText(
                            this@LauncherSettingsActivity,
                            "Восстановлено: ${result.tripsCount} поездок, ${result.chargingsCount} зарядок",
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