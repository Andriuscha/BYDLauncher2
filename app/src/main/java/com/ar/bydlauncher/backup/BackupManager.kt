package com.ar.bydlauncher.backup

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ar.bydlauncher.db.TripDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Бэкап и восстановление всех данных приложения.
 *
 * Экспорт: один JSON в Download/BYDLauncher/.
 * Rollback-бэкап: тоже в Download/BYDLauncher/ с префиксом rollback_.
 * Импорт: атомарный, с предварительным rollback.
 */
class BackupManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupManager"

        const val FORMAT_VERSION = 1
        const val FILE_PREFIX = "BYDLauncher_backup_"
        const val ROLLBACK_PREFIX = "rollback_"

        const val PREFS_CALIBRATION = "range_calibration"
        const val PREFS_FULL_CHARGE = "full_charge_tracker"
        const val PREFS_BACKUP_META = "backup_meta"
        const val KEY_LAST_BACKUP_TS = "last_backup_ts"

        const val FOLDER_NAME = "BYDLauncher"
    }

    // ==================================================
    //  ПУТИ
    // ==================================================

    /** Папка для бэкапов. Сначала пробуем публичный Downloads, fallback — приватный. */
    private fun getBackupDir(): File {
        // Приватная папка приложения — доступна всегда, не требует разрешений
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ==================================================
    //  ЭКСПОРТ
    // ==================================================

    /**
     * Собирает все данные и пишет JSON.
     * @return File — путь к созданному бэкапу, или null при ошибке.
     */
    suspend fun exportToDownloads(): File? = withContext(Dispatchers.IO) {
        try {
            val json = collectAll()
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(getBackupDir(), "$FILE_PREFIX$ts.json")

            file.writeText(json.toString(2))
            Log.i(TAG, "Backup exported: ${file.absolutePath} (${file.length()} bytes)")

            // Запоминаем время последнего бэкапа
            saveLastBackupTs(System.currentTimeMillis())

            file
        } catch (e: Exception) {
            Log.e(TAG, "exportToDownloads failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Собирает все данные в один JSON.
     */
    private fun collectAll(): JSONObject {
        val root = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("appVersion", getAppVersion())
        }

        // ── 1. Trips ──
        val trips = collectTrips()
        root.put("trips", trips)
        root.put("tripsCount", trips.length())

        // ── 2. SOH file ──
        val sohFile = File(context.filesDir, "soh_data.json")
        if (sohFile.exists()) {
            try {
                val sohJson = JSONObject(sohFile.readText())
                root.put("soh", sohJson)
                // Считаем замеры для metadata
                val measurements = sohJson.optJSONArray("measurements")
                root.put("sohMeasurementsCount", measurements?.length() ?: 0)
            } catch (e: Exception) {
                Log.w(TAG, "soh read failed", e)
                root.put("soh", JSONObject.NULL)
                root.put("sohMeasurementsCount", 0)
            }
        } else {
            root.put("soh", JSONObject.NULL)
            root.put("sohMeasurementsCount", 0)
        }

        // ── 3. SharedPreferences ──
        val calibration = prefsToJson(PREFS_CALIBRATION)
        root.put("prefsCalibration", calibration)
        // Считаем samples для metadata
        val samplesStr = calibration.optString("samples", "")
        val samplesCount = try {
            if (samplesStr.isNotEmpty()) JSONArray(samplesStr).length() else 0
        } catch (_: Exception) { 0 }
        root.put("calibrationSamplesCount", samplesCount)

        root.put("prefsFullCharge", prefsToJson(PREFS_FULL_CHARGE))

        return root
    }

    private fun collectTrips(): JSONArray {
        val arr = JSONArray()
        val db = TripDatabase(context.applicationContext)
        try {
            db.readableDatabase.query(
                TripDatabase.TABLE,
                null, null, null, null, null,
                "${TripDatabase.COL_STARTED_AT} ASC"
            ).use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject()
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        val v: Any? = when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        }
                        o.put(name, v ?: JSONObject.NULL)
                    }
                    arr.put(o)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "collectTrips failed", e)
        } finally {
            db.close()
        }
        return arr
    }

    private fun prefsToJson(name: String): JSONObject {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val o = JSONObject()
        for ((k, v) in prefs.all) {
            when (v) {
                is String -> o.put(k, v)
                is Int    -> o.put(k, v)
                is Long   -> o.put(k, v)
                is Float  -> o.put(k, v.toDouble())
                is Boolean -> o.put(k, v)
                is Set<*> -> o.put(k, JSONArray(v.toList()))
                else -> o.put(k, v.toString())
            }
        }
        return o
    }

    private fun getAppVersion(): String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: "?"
    } catch (_: Exception) { "?" }

    // ==================================================
    //  ИМПОРТ
    // ==================================================

    /**
     * Метаданные бэкапа — для диалога подтверждения.
     */
    data class BackupMeta(
        val file: File,
        val createdAt: Long,
        val tripsCount: Int,
        val sohMeasurementsCount: Int,
        val calibrationSamplesCount: Int,
        val appVersion: String
    ) {
        val displayName: String
            get() = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(Date(createdAt))
    }

    /**
     * Читает метаданные бэкапа без импорта.
     */
    suspend fun readMeta(file: File): BackupMeta? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val root = JSONObject(file.readText())
            BackupMeta(
                file = file,
                createdAt = root.optLong("createdAt", file.lastModified()),
                tripsCount = root.optInt("tripsCount", root.optJSONArray("trips")?.length() ?: 0),
                sohMeasurementsCount = root.optInt("sohMeasurementsCount", 0),
                calibrationSamplesCount = root.optInt("calibrationSamplesCount", 0),
                appVersion = root.optString("appVersion", "?")
            )
        } catch (e: Exception) {
            Log.e(TAG, "readMeta failed: ${e.message}", e)
            null
        }
    }

    /**
     * Текущие счётчики — для диалога «до/после».
     */
    data class CurrentCounters(
        val tripsCount: Int,
        val sohMeasurementsCount: Int,
        val calibrationSamplesCount: Int
    )

    suspend fun readCurrentCounters(): CurrentCounters = withContext(Dispatchers.IO) {
        val tripsCount = try {
            val db = TripDatabase(context.applicationContext)
            val c = db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ${TripDatabase.TABLE}", null
            )
            val n = if (c.moveToFirst()) c.getInt(0) else 0
            c.close()
            db.close()
            n
        } catch (_: Exception) { 0 }

        val sohCount = try {
            val sohFile = File(context.filesDir, "soh_data.json")
            if (sohFile.exists()) {
                val arr = JSONObject(sohFile.readText()).optJSONArray("measurements")
                arr?.length() ?: 0
            } else 0
        } catch (_: Exception) { 0 }

        val calCount = try {
            val prefs = context.getSharedPreferences(PREFS_CALIBRATION, Context.MODE_PRIVATE)
            val s = prefs.getString("samples", "")
            if (s.isNullOrEmpty()) 0 else JSONArray(s).length()
        } catch (_: Exception) { 0 }

        CurrentCounters(tripsCount, sohCount, calCount)
    }

    /**
     * Восстанавливает данные из бэкапа.
     */
    suspend fun importFromFile(file: File): BackupResult = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext BackupResult.Error("Файл не найден")
            }

            val root = JSONObject(file.readText())
            val version = root.optInt("version", 0)
            if (version != FORMAT_VERSION) {
                return@withContext BackupResult.Error(
                    "Несовместимая версия бэкапа: $version (ожидается $FORMAT_VERSION)"
                )
            }

            // ── Сохраняем текущее состояние в rollback (в Download/) ──
            val rollbackFile = createRollbackBackup()
            Log.i(TAG, "Rollback created: ${rollbackFile?.absolutePath}")

            // ── Применяем ──
            val tripsCount = applyTrips(root.optJSONArray("trips"))
            val sohCount = applySoh(root.optJSONObject("soh"))
            val calCount = applyPrefs(PREFS_CALIBRATION, root.optJSONObject("prefsCalibration"))
            applyPrefs(PREFS_FULL_CHARGE, root.optJSONObject("prefsFullCharge"))

            Log.i(
                TAG,
                "Backup imported: trips=$tripsCount, soh=$sohCount, calibration=$calCount"
            )

            BackupResult.Success(
                tripsCount = tripsCount,
                sohMeasurementsCount = sohCount,
                calibrationSamplesCount = calCount,
                rollbackPath = rollbackFile?.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "importFromFile failed: ${e.javaClass.simpleName}: ${e.message}", e)
            BackupResult.Error("Ошибка импорта: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Rollback-бэкап перед импортом.
     * Пишется в ту же папку, что и обычные бэкапы — видно через файловый менеджер.
     */
    private fun createRollbackBackup(): File? {
        return try {
            val json = collectAll()
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(getBackupDir(), "$ROLLBACK_PREFIX$ts.json")
            file.writeText(json.toString(2))
            file
        } catch (e: Exception) {
            Log.w(TAG, "createRollbackBackup failed", e)
            null
        }
    }

    private fun applyTrips(arr: JSONArray?): Int {
        if (arr == null) return 0
        val db = TripDatabase(context.applicationContext)
        var count = 0
        try {
            val writable = db.writableDatabase
            writable.beginTransaction()
            try {
                writable.delete(TripDatabase.TABLE, null, null)

                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cv = android.content.ContentValues()
                    for (key in o.keys()) {
                        val v = o.get(key)
                        when (v) {
                            is Int    -> cv.put(key, v)
                            is Long   -> cv.put(key, v)
                            is Double -> cv.put(key, v)
                            is Boolean -> cv.put(key, if (v) 1 else 0)
                            is String -> cv.put(key, v)
                            JSONObject.NULL -> cv.putNull(key)
                        }
                    }
                    writable.insert(TripDatabase.TABLE, null, cv)
                    count++
                }
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyTrips failed", e)
        } finally {
            db.close()
        }
        return count
    }

    private fun applySoh(json: JSONObject?): Int {
        var count = 0
        try {
            val file = File(context.filesDir, "soh_data.json")
            if (json == null || json == JSONObject.NULL) {
                if (file.exists()) file.delete()
                Log.i(TAG, "SOH file deleted (backup had no SOH)")
                return 0
            }
            count = json.optJSONArray("measurements")?.length() ?: 0
            // Атомарная запись
            val tmp = File(context.filesDir, "soh_data.json.tmp")
            tmp.writeText(json.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            Log.i(TAG, "SOH file restored: $count measurements")
        } catch (e: Exception) {
            Log.e(TAG, "applySoh failed", e)
        }
        return count
    }

    private fun applyPrefs(name: String, json: JSONObject?): Int {
        var samplesCount = 0
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.clear()
            if (json != null && json != JSONObject.NULL) {
                for (key in json.keys()) {
                    val v = json.get(key)
                    when (v) {
                        is Int    -> editor.putInt(key, v)
                        is Long   -> editor.putLong(key, v)
                        is Double -> editor.putFloat(key, v.toFloat())
                        is Boolean -> editor.putBoolean(key, v)
                        is String -> {
                            editor.putString(key, v)
                            // Считаем calibration samples
                            if (name == PREFS_CALIBRATION && key == "samples") {
                                samplesCount = try { JSONArray(v).length() } catch (_: Exception) { 0 }
                            }
                        }
                        is JSONArray -> {
                            val set = mutableSetOf<String>()
                            for (i in 0 until v.length()) set.add(v.getString(i))
                            editor.putStringSet(key, set)
                        }
                    }
                }
            }
            editor.apply()
            Log.i(TAG, "Prefs $name restored (samples=$samplesCount)")
        } catch (e: Exception) {
            Log.e(TAG, "applyPrefs($name) failed", e)
        }
        return samplesCount
    }

    // ==================================================
    //  СПИСОК БЭКАПОВ
    // ==================================================

    /** Все обычные бэкапы (без rollback), новые сверху. */
    fun listBackups(): List<File> {
        val dir = getBackupDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter {
                it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".json")
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Все rollback-бэкапы, новые сверху. */
    fun listRollbacks(): List<File> {
        val dir = getBackupDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter {
                it.name.startsWith(ROLLBACK_PREFIX) && it.name.endsWith(".json")
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Удалить бэкап. */
    fun deleteBackup(file: File): Boolean = try {
        file.delete()
    } catch (e: Exception) {
        Log.e(TAG, "deleteBackup failed", e)
        false
    }

    // ==================================================
    //  МЕТАДАННЫЕ ПОСЛЕДНЕГО БЭКАПА
    // ==================================================

    private fun saveLastBackupTs(ts: Long) {
        context.getSharedPreferences(PREFS_BACKUP_META, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_BACKUP_TS, ts)
            .apply()
    }

    /** Время последнего успешного экспорта. 0 — ещё не было. */
    fun getLastBackupTs(): Long {
        return context.getSharedPreferences(PREFS_BACKUP_META, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_BACKUP_TS, 0L)
    }

    /** Текст «последний бэкап: …». */
    fun getLastBackupText(): String {
        val ts = getLastBackupTs()
        if (ts == 0L) return "Последний бэкап: ещё не создавался"
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return "Последний бэкап: ${fmt.format(Date(ts))}"
    }

    // ==================================================
    //  РЕЗУЛЬТАТ
    // ==================================================

    sealed class BackupResult {
        data class Success(
            val tripsCount: Int,
            val sohMeasurementsCount: Int,
            val calibrationSamplesCount: Int,
            val rollbackPath: String?
        ) : BackupResult()
        data class Error(val message: String) : BackupResult()
    }
}