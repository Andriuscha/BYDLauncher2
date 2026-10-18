package com.ar.bydlauncher.backup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Отправка бэкапа в Telegram (Bot API, sendDocument).
 * Сеть на ГУ — это хотспот телефона, поэтому «нет сети» — штатная ситуация:
 * при ошибке lastOkTs не обновляется, и следующая поездка попробует снова.
 */
object TelegramBackupUploader {

    private const val TAG = "TgBackup"
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val running = AtomicBoolean(false)

    /** Вызывать после завершения поездки. Сам решает, нужно ли отправлять. */
    suspend fun maybeAutoBackup(context: Context) {
        val s = BackupSettings(context)
        if (!s.tgAutoEnabled || !s.tgConfigured) return
        if (System.currentTimeMillis() - s.lastOkTs < MIN_INTERVAL_MS) return
        sendNow(context)
    }

    /** Отправить сейчас, без проверки интервала. */
    suspend fun sendNow(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val s = BackupSettings(app)
        if (!s.tgConfigured) return@withContext Result.failure(IllegalStateException("Не заданы токен и chat id"))
        if (!running.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Отправка уже идёт"))
        }
        var file: File? = null
        try {
            file = BackupManager(app).exportForUpload(s.tgStripCoords)
                ?: throw IOException("Не удалось собрать бэкап")
            val caption = "BYDLauncher backup " +
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
            post(s.tgToken, s.tgChatId, file, caption)
            s.lastOkTs = System.currentTimeMillis()
            s.lastError = ""
            Log.i(TAG, "Backup sent (${file.length()} bytes)")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // URL с токеном не логируем и в сообщение не кладём
            val msg = "${t.javaClass.simpleName}: ${t.message?.take(160)}"
            s.lastError = msg
            Log.w(TAG, "Send failed: $msg")
            Result.failure(t)
        } finally {
            file?.delete()
            running.set(false)
        }
    }

    private fun post(token: String, chatId: String, file: File, caption: String) {
        val boundary = "----bydlauncher${System.currentTimeMillis()}"
        val conn = URL("https://api.telegram.org/bot$token/sendDocument")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(0)

            DataOutputStream(conn.outputStream.buffered()).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("chat_id", chatId)
                field("caption", caption)
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: application/json\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Telegram HTTP $code: ${body.take(160)}")
        } finally {
            conn.disconnect()
        }
    }
}
