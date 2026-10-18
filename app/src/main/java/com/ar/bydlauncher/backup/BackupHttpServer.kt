package com.ar.bydlauncher.backup

import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Мини-HTTP-сервер для ручной передачи бэкапа на телефон через хотспот.
 * ГУ — клиент хотспота, телефон открывает в браузере http://<ip ГУ>:PORT/<PIN>/.
 *
 * Включается кнопкой в настройках, живёт пока открыт экран. Доступ — по PIN в пути.
 * Загруженный с телефона файл только кладётся в папку бэкапов;
 * восстановление — отдельным явным действием на экране ГУ.
 */
class BackupHttpServer(private val backups: BackupManager) {

    companion object {
        private const val TAG = "BackupHttp"
        const val PORT = 8765
        private const val MAX_UPLOAD = 64L * 1024 * 1024
    }

    val pin: String = (100_000 + SecureRandom().nextInt(900_000)).toString()

    @Volatile private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null

    fun start(): Boolean = try {
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        serverSocket = ss
        val p = Executors.newFixedThreadPool(4)
        pool = p
        Thread({
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    p.execute { runCatching { handle(s) }.onFailure { Log.w(TAG, "handle: ${it.message}") } }
                } catch (e: Exception) {
                    if (!ss.isClosed) Log.w(TAG, "accept: ${e.message}")
                }
            }
        }, "backup-http-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "Started on :$PORT")
        true
    } catch (e: Exception) {
        Log.e(TAG, "start failed: ${e.message}")
        stop()
        false
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
        Log.i(TAG, "Stopped")
    }

    /** Локальные IPv4 ГУ (в хотспоте телефона — обычно 192.168.x.x / 172.x.x.x). */
    fun localAddresses(): List<String> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    // ── обработка запроса ──

    private fun handle(sock: Socket) = sock.use {
        it.soTimeout = 20_000
        val input = BufferedInputStream(it.getInputStream())
        val out = BufferedOutputStream(it.getOutputStream())

        val reqLine = readLine(input) ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        var contentLength = 0L
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) {
                contentLength = h.substring(15).trim().toLongOrNull() ?: 0L
            }
        }

        val segs = path.split('/').filter { s -> s.isNotEmpty() }
        if (segs.isEmpty() || segs[0] != pin) {
            respond(out, 403, "text/plain", "Forbidden".toByteArray())
            return
        }

        when {
            method == "GET" && segs.size == 1 -> respond(out, 200, "text/html", page().toByteArray(Charsets.UTF_8))

            method == "GET" && segs.size == 2 && segs[1] == "new" -> {
                val f = runBlocking { backups.exportToDownloads() }
                if (f == null) respond(out, 500, "text/plain", "Backup failed".toByteArray())
                else redirect(out, "/$pin/")
            }

            method == "GET" && segs.size == 3 && segs[1] == "file" -> {
                val name = URLDecoder.decode(segs[2], "UTF-8")
                val f = backups.listBackups().firstOrNull { b -> b.name == name }
                if (f == null) respond(out, 404, "text/plain", "Not found".toByteArray())
                else sendFile(out, f)
            }

            method == "POST" && segs.size == 2 && segs[1] == "upload" -> {
                if (contentLength <= 0 || contentLength > MAX_UPLOAD) {
                    respond(out, 413, "text/plain", "Bad size".toByteArray())
                    return
                }
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val tmp = File(backups.backupDir(), "upload_$ts.tmp")
                tmp.outputStream().use { fos ->
                    var left = contentLength
                    val buf = ByteArray(16 * 1024)
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        left -= n
                    }
                    if (left > 0) { tmp.delete(); respond(out, 400, "text/plain", "Truncated".toByteArray()); return }
                }
                // принимаем только валидный бэкап нужной версии
                val meta = runBlocking { backups.readMeta(tmp) }
                if (meta == null) {
                    tmp.delete()
                    respond(out, 400, "text/plain", "Not a valid backup".toByteArray())
                    return
                }
                val dest = File(backups.backupDir(), "${BackupManager.FILE_PREFIX}uploaded_$ts.json")
                if (!tmp.renameTo(dest)) { tmp.delete(); respond(out, 500, "text/plain", "Save failed".toByteArray()); return }
                respond(out, 200, "text/plain", "OK ${dest.name}".toByteArray())
            }

            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun page(): String {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val rows = backups.listBackups().joinToString("") { f ->
            val n = esc(f.name)
            "<li><a href=\"/$pin/file/$n\">$n</a> <small>${fmt.format(Date(f.lastModified()))}, ${f.length() / 1024} КБ</small></li>"
        }.ifEmpty { "<li>Бэкапов пока нет</li>" }
        return """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>BYDLauncher — бэкапы</title>
<style>body{font-family:sans-serif;max-width:640px;margin:16px auto;padding:0 12px}
a.btn,button{display:inline-block;padding:10px 14px;margin:6px 0;background:#2563eb;color:#fff;border:0;border-radius:8px;text-decoration:none;font-size:16px}
li{margin:8px 0;word-break:break-all}</style></head><body>
<h2>Бэкапы BYDLauncher</h2>
<a class="btn" href="/$pin/new">Создать новый бэкап</a>
<h3>Скачать</h3><ul>$rows</ul>
<h3>Загрузить на ГУ</h3>
<input type="file" id="f" accept=".json,application/json"><br>
<button onclick="up()">Загрузить</button> <span id="st"></span>
<p><small>После загрузки восстановление выполняется на экране ГУ: Настройки → Бэкапы → Восстановить.</small></p>
<script>
async function up(){var f=document.getElementById('f').files[0],s=document.getElementById('st');
if(!f){s.textContent='Выберите файл';return}
s.textContent='Загрузка…';
try{var r=await fetch('/$pin/upload',{method:'POST',body:f});var t=await r.text();
s.textContent=r.ok?'Готово: '+t:'Ошибка: '+t;if(r.ok)setTimeout(function(){location.reload()},1200)}
catch(e){s.textContent='Ошибка сети'}}
</script></body></html>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 400 -> "Bad Request"; else -> "Error" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun redirect(out: OutputStream, to: String) {
        out.write("HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun sendFile(out: OutputStream, f: File) {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${f.length()}\r\n" +
                "Content-Disposition: attachment; filename=\"${f.name}\"\r\nConnection: close\r\n\r\n").toByteArray())
        f.inputStream().use { it.copyTo(out) }
        out.flush()
    }
}
