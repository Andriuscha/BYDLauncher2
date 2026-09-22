package com.ar.bydlauncher.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays

class AdbClient(private val context: Context) {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var keyPair: KeyPair? = null
    private var nextLocalId = 1

    /** Мьютекс: два одновременных shell() не пересекутся.
     *  Опрос BMS и команды кнопок встанут в очередь. */
    private val shellLock = Any()

    companion object {
        private const val TAG = "AdbClient"
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555

        // Команды протокола ADB (little-endian ASCII)
        const val A_CNXN = 0x4E584E43  // "CNXN"
        const val A_AUTH = 0x48545541  // "AUTH"
        const val A_OPEN = 0x4E45504F  // "OPEN"
        const val A_OKAY = 0x59414B4F  // "OKAY"
        const val A_CLSE = 0x45534C43  // "CLSE"
        const val A_WRTE = 0x45545257  // "WRTE"
        const val A_STLS = 0x534C5453  // "STLS"

        const val A_VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val USER_PROMPT_TIMEOUT_MS = 60_000

        private const val PRIVATE_KEY_FILE = "adbkey"
        private const val PUBLIC_KEY_FILE = "adbkey.pub"

        val ADB_PADDING = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
        )
    }

    fun isConnected(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed
    }

    fun connect(): Boolean {
        return try {
            keyPair = loadOrGenerateKeys()

            val s = Socket(HOST, PORT).apply {
                soTimeout = SOCKET_TIMEOUT_MS
                tcpNoDelay = true
            }
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()

            Log.i(TAG, "TCP connected to $HOST:$PORT, sending CNXN")
            writePacket(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())

            var pkt = readPacket()
            Log.i(
                TAG,
                "Got first packet: cmd=0x${pkt.command.toString(16)} " +
                        "arg0=${pkt.arg0} arg1=${pkt.arg1} payloadSize=${pkt.payload.size}"
            )

            if (pkt.command == A_STLS) {
                Log.i(TAG, "adbd offers STLS — ignoring, continuing in plain mode")
                pkt = readPacket()
                Log.i(
                    TAG,
                    "After STLS, next packet: cmd=0x${pkt.command.toString(16)} " +
                            "arg0=${pkt.arg0} arg1=${pkt.arg1}"
                )
            }

            when (pkt.command) {
                A_CNXN -> {
                    Log.i(TAG, "Connected without auth")
                    true
                }
                A_AUTH -> {
                    if (pkt.arg0 == AUTH_TOKEN) {
                        authenticate(pkt.payload)
                    } else {
                        Log.e(TAG, "Unexpected AUTH arg0=${pkt.arg0}")
                        false
                    }
                }
                else -> {
                    Log.e(TAG, "Unexpected handshake response cmd=0x${pkt.command.toString(16)}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed", e)
            false
        }
    }

    private fun authenticate(token: ByteArray): Boolean {
        return try {
            Log.i(TAG, "Auth step 1: signing token (${token.size}B)")
            val sig = signToken(token, keyPair!!)
            writePacket(A_AUTH, AUTH_SIGNATURE, 0, sig)

            var resp = readPacket()
            Log.i(TAG, "Auth response 1: cmd=0x${resp.command.toString(16)} arg0=${resp.arg0}")

            if (resp.command == A_CNXN) {
                Log.i(TAG, "Authenticated (cached key)")
                return true
            }

            if (resp.command == A_AUTH && resp.arg0 == AUTH_TOKEN) {
                Log.i(TAG, "Auth step 2: sending public key, waiting for user approval...")
                val pub = serializePublicKey(keyPair!!)
                writePacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, pub)

                socket?.soTimeout = USER_PROMPT_TIMEOUT_MS
                resp = readPacket()
                socket?.soTimeout = SOCKET_TIMEOUT_MS

                Log.i(TAG, "Auth response 2: cmd=0x${resp.command.toString(16)}")
                if (resp.command == A_CNXN) {
                    Log.i(TAG, "Authenticated (user approved)")
                    return true
                }
            }

            Log.e(TAG, "Auth failed. Last response: 0x${resp.command.toString(16)}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Auth error", e)
            false
        }
    }

    /**
     * Отправляет shell-команду и возвращает её stdout (trim'ленный).
     * Синхронизирован: два параллельных shell() не пересекутся.
     * null — если соединение оборвалось или произошла ошибка протокола.
     */
    fun shell(command: String): String? = synchronized(shellLock) {
        try {
            val localId = nextLocalId++
            writePacket(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())

            var remoteId = 0
            for (i in 0 until 20) {
                val pkt = readPacket()
                if (pkt.command == A_OKAY && pkt.arg1 == localId) {
                    remoteId = pkt.arg0
                    break
                }
            }
            if (remoteId == 0) {
                Log.e(TAG, "shell: never got OKAY for localId=$localId")
                return@synchronized null
            }

            val sb = StringBuilder()
            for (i in 0 until 500) {
                val pkt = readPacket()
                when {
                    pkt.command == A_WRTE && pkt.arg0 == remoteId && pkt.arg1 == localId -> {
                        sb.append(String(pkt.payload, Charsets.UTF_8))
                        writePacket(A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    pkt.command == A_CLSE && pkt.arg0 == remoteId -> {
                        writePacket(A_CLSE, localId, remoteId, ByteArray(0))
                        break
                    }
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "shell failed: $command", e)
            null
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    private fun writePacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd); buf.putInt(arg0); buf.putInt(arg1); buf.putInt(payload.size)
        var cs = 0
        for (b in payload) cs += (b.toInt() and 0xFF)
        buf.putInt(cs); buf.putInt(cmd.inv())
        output!!.write(buf.array())
        if (payload.isNotEmpty()) output!!.write(payload)
        output!!.flush()
    }

    private fun readPacket(): Packet {
        val h = ByteBuffer.wrap(readBytes(24)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val a0 = h.int
        val a1 = h.int
        val len = h.int
        val declaredChecksum = h.int
        h.int
        val payload = if (len > 0) readBytes(len) else ByteArray(0)

        var cs = 0
        for (b in payload) cs += (b.toInt() and 0xFF)
        if (cs != declaredChecksum) {
            Log.w(
                TAG,
                "Checksum mismatch on incoming packet (cmd=0x${cmd.toString(16)}): " +
                        "expected $declaredChecksum, got $cs"
            )
        }

        return Packet(cmd, a0, a1, payload)
    }

    private fun readBytes(n: Int): ByteArray {
        val buf = ByteArray(n)
        var r = 0
        while (r < n) {
            val x = input!!.read(buf, r, n - r)
            if (x < 0) throw IOException("EOF after $r of $n bytes")
            r += x
        }
        return buf
    }

    private fun loadOrGenerateKeys(): KeyPair {
        val privFile = File(context.filesDir, PRIVATE_KEY_FILE)
        val pubFile = File(context.filesDir, PUBLIC_KEY_FILE)

        if (privFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                Log.i(TAG, "Loaded existing ADB key pair")
                return KeyPair(pub, priv)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load saved ADB key pair, regenerating", e)
            }
        }

        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = gen.generateKeyPair()
        try {
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
            Log.i(TAG, "Generated and saved new ADB key pair")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist new ADB key pair — will need re-approval every run", e)
        }
        return kp
    }

    private fun signToken(token: ByteArray, kp: KeyPair): ByteArray {
        val padded = Arrays.copyOf(ADB_PADDING, ADB_PADDING.size + token.size)
        System.arraycopy(token, 0, padded, ADB_PADDING.size, token.size)
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(kp.private)
        sig.update(padded)
        return sig.sign()
    }

    private fun serializePublicKey(kp: KeyPair): ByteArray {
        val pub = kp.public as RSAPublicKey
        var modulus = pub.modulus
        val ONE = BigInteger.ONE
        val r32 = ONE.shiftLeft(32)
        val mask32 = r32.subtract(ONE)
        val n0inv = modulus.and(mask32).modInverse(r32).negate().mod(r32)
        var rr = ONE.shiftLeft(4096).mod(modulus)

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv.toInt())
        for (i in 0 until 64) {
            buf.putInt(modulus.and(mask32).toInt())
            modulus = modulus.shiftRight(32)
        }
        for (i in 0 until 64) {
            buf.putInt(rr.and(mask32).toInt())
            rr = rr.shiftRight(32)
        }
        buf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return (b64 + " bydlauncher@dilink\u0000").toByteArray(Charsets.UTF_8)
    }

    data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )
}