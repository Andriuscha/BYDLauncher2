package com.ar.bydlauncher.backup

import android.content.Context

/**
 * Настройки удалённого бэкапа (Telegram). Отдельный prefs-файл:
 * в сам бэкап (BackupManager) он НЕ попадает, чтобы токен не уезжал в JSON.
 */
class BackupSettings(context: Context) {

    private val p = context.applicationContext
        .getSharedPreferences("backup_remote", Context.MODE_PRIVATE)

    var tgToken: String
        get() = p.getString("tg_token", "") ?: ""
        set(v) = p.edit().putString("tg_token", v.trim()).apply()

    var tgChatId: String
        get() = p.getString("tg_chat_id", "") ?: ""
        set(v) = p.edit().putString("tg_chat_id", v.trim()).apply()

    /** Автобэкап после завершённой поездки. */
    var tgAutoEnabled: Boolean
        get() = p.getBoolean("tg_auto", false)
        set(v) = p.edit().putBoolean("tg_auto", v).apply()

    /** Не отправлять координаты поездок и название места зарядки. */
    var tgStripCoords: Boolean
        get() = p.getBoolean("tg_strip", true)
        set(v) = p.edit().putBoolean("tg_strip", v).apply()

    var lastOkTs: Long
        get() = p.getLong("tg_last_ok", 0L)
        set(v) = p.edit().putLong("tg_last_ok", v).apply()

    /** Пустая строка — последняя отправка прошла успешно. */
    var lastError: String
        get() = p.getString("tg_last_err", "") ?: ""
        set(v) = p.edit().putString("tg_last_err", v).apply()

    val tgConfigured: Boolean
        get() = tgToken.isNotEmpty() && tgChatId.isNotEmpty()
}
