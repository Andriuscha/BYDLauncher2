package com.ar.bydlauncher

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.ar.bydlauncher.adb.AdbClient
import com.ar.bydlauncher.byd.AutoserviceClient
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tech-тестер FID: позволяет прочитать/записать произвольный FID
 * через service call autoservice.
 *
 * Полезно для отладки и калибровки.
 */
class TechActivity : Activity() {

    companion object {
        private const val TAG = "TechActivity"
        private const val MAX_LOG_LINES = 200
    }

    private lateinit var adb: AdbClient
    private lateinit var ac: AutoserviceClient

    private lateinit var etDevice: EditText
    private lateinit var etFid: EditText
    private lateinit var etValue: EditText
    private lateinit var tvResult: TextView
    private lateinit var tvLog: TextView

    private val logLines = ArrayDeque<String>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tech)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        adb = AdbClient.ensureConnected(this)
        ac = AutoserviceClient(adb)

        etDevice = findViewById(R.id.techDevice)
        etFid = findViewById(R.id.techFid)
        etValue = findViewById(R.id.techValue)
        tvResult = findViewById(R.id.techResult)
        tvLog = findViewById(R.id.techLog)

        findViewById<View>(R.id.techClose).setOnClickListener { finish() }

        findViewById<View>(R.id.techReadInt).setOnClickListener { doReadInt() }
        findViewById<View>(R.id.techReadFloat).setOnClickListener { doReadFloat() }
        findViewById<View>(R.id.techWrite).setOnClickListener { doWrite() }

        appendLog("TechActivity запущен")
    }

    // ═══════════════════════════════════════════════
    //  ДЕЙСТВИЯ
    // ═══════════════════════════════════════════════

    private fun doReadInt() {
        val device = etDevice.text.toString().toIntOrNull() ?: run {
            showError("Некорректный DEVICE"); return
        }
        val fid = etFid.text.toString().toLongOrNull()?.toInt() ?: run {
            showError("Некорректный FID"); return
        }
        appendLog("READ INT  dev=$device  fid=$fid")

        ioScope.launch {
            val raw = ac.getRaw(device, fid, AutoserviceClient.ValueType.INT)
            val parsed = ac.getInt(device, fid)
            withContext(Dispatchers.Main) {
                showResult(raw, parsed?.toString(), "INT")
                appendLog("  → $raw")
                if (parsed != null) appendLog("  value = $parsed")
            }
        }
    }

    private fun doReadFloat() {
        val device = etDevice.text.toString().toIntOrNull() ?: run {
            showError("Некорректный DEVICE"); return
        }
        val fid = etFid.text.toString().toLongOrNull()?.toInt() ?: run {
            showError("Некорректный FID"); return
        }
        appendLog("READ FLOAT  dev=$device  fid=$fid")

        ioScope.launch {
            val raw = ac.getRaw(device, fid, AutoserviceClient.ValueType.FLOAT)
            val parsed = ac.getFloat(device, fid)
            withContext(Dispatchers.Main) {
                showResult(raw, parsed?.toString(), "FLOAT")
                appendLog("  → $raw")
                if (parsed != null) appendLog("  value = $parsed")
            }
        }
    }

    private fun doWrite() {
        val device = etDevice.text.toString().toIntOrNull() ?: run {
            showError("Некорректный DEVICE"); return
        }
        val fid = etFid.text.toString().toLongOrNull()?.toInt() ?: run {
            showError("Некорректный FID"); return
        }
        val value = etValue.text.toString().toIntOrNull() ?: run {
            showError("Некорректное VALUE"); return
        }
        appendLog("WRITE  dev=$device  fid=$fid  value=$value")

        ioScope.launch {
            val raw = ac.callRaw(AutoserviceClient.TX_SET_INT, device, fid, value)
            val ok = ac.setInt(device, fid, value)
            withContext(Dispatchers.Main) {
                showResult(raw, if (ok) "OK (команда принята)" else "FAILED", "WRITE")
                appendLog("  → $raw")
                appendLog("  result = ${if (ok) "OK" else "FAIL"}")
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  UI-помощники
    // ═══════════════════════════════════════════════

    private fun showResult(raw: String?, parsed: String?, kind: String) {
        val rawBlock = raw ?: "(нет ответа)"
        val parsedBlock = parsed ?: "—"
        tvResult.text = "── $kind ──\n\nСЫРОЙ ОТВЕТ:\n$rawBlock\n\nЗНАЧЕНИЕ:\n$parsedBlock"
    }

    private fun showError(msg: String) {
        tvResult.text = "ОШИБКА: $msg"
        appendLog("ERR: $msg")
    }

    private fun appendLog(line: String) {
        val ts = timeFmt.format(Date())
        logLines.addLast("[$ts] $line")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()

        tvLog.text = logLines.joinToString("\n")
    }

    override fun onDestroy() {
        ioScope.cancel()
        scope.cancel()
        super.onDestroy()
    }
}