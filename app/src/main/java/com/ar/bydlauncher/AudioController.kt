package com.ar.bydlauncher.byd

import android.util.Log

/**
 * Обёртка над аудио-FID'ами BYD.
 *
 * Все FID'ы читаются/пишутся через device=1002 (BYDAUTO_DEVICE_AUDIO).
 * Transaction codes: 5=GET_INT, 6=SET_INT, 7=GET_FLOAT.
 *
 * ВНИМАНИЕ: значения FID'ов (0/1/2/3...) НЕ задокументированы.
 * Используйте экран AudioSettingsActivity для эмпирического определения.
 */
class AudioController(private val c: AutoserviceClient) {

    companion object {
        private const val TAG = "AudioController"
        private const val DEV_AUDIO = 1002
    }

    /** Один аудио-параметр: пара FID'ов (read + write) + человекочитаемое имя. */
    data class AudioParam(
        val name: String,
        val readFid: Int,
        val writeFid: Int?
    )

    /** Все контролы, которые мы хотим показать. */
    val params: List<AudioParam> = listOf(
        AudioParam(
            "Arkamys Soundstage Mode",
            1118830613,  // AUDIO_CMD_ARKAMYS_SOUNDSTAGE_MODE
            1309061136   // AUDIO_CMD_ARKAMYS_SOUNDSTAGE_MODE_SET
        ),
        AudioParam(
            "Arkamys Soundstage Woofer",
            1118830606,  // AUDIO_CMD_ARKAMYS_SOUNDSTAGE_WOOFER
            1309061152   // AUDIO_CMD_ARKAMYS_SOUNDSTAGE_WOOFER_SET
        ),
        AudioParam(
            "Beam Form",
            1118830621,  // AUDIO_CMD_BEAM_FORM
            1309073424   // AUDIO_CMD_BEAM_FORM_SET
        ),
        AudioParam(
            "iFlytek Control",
            0,           // нет read-FID, только write
            1309147152   // AUDIO_CONTROL_IFLYTEK_SET
        ),
        AudioParam(
            "Dirac Live",
            1108344862,  // AUDIO_DIRAC_LIVE
            1309057040   // AUDIO_DIRAC_LIVE_SET
        ),
        AudioParam(
            "Dirac Live Stage",
            1108344860,  // AUDIO_DIRAC_LIVE_STAGE
            1309343760   // AUDIO_DIRAC_LIVE_STAGE_SET
        ),
        AudioParam(
            "DMS Alert",
            0,           // нет read-FID
            -1442840232  // AUDIO_DMS_ALERT_SET
        ),
        AudioParam(
            "Dynaudio Sound Features",
            1281359880,  // AUDIO_DYNAUDIO_SOUND_FEATURES
            454033432    // AUDIO_DYNAUDIO_SOUND_FEATURES_SET
        ),
        AudioParam(
            "Dynaudio Soundfield Focus Reset",
            0,           // только write
            470810637    // AUDIO_DYNAUDIO_SOUNDFIELD_FOCUS_RESET_SET
        ),
        AudioParam(
            "Dynaudio Sound Reset",
            0,           // только write
            454033476    // AUDIO_DYNAUDIO_SOUND_RESET_SET
        )
    )

    /** Прочитать текущее значение FID. null = ошибка или sentinel. */
    fun read(fid: Int): Int? {
        if (fid == 0) return null
        val result = c.getInt(DEV_AUDIO, fid)
        Log.d(TAG, "read fid=$fid -> $result")
        return result
    }

    /** Записать значение. true = shell вернул не-Error. */
    fun write(fid: Int, value: Int): Boolean {
        val ok = c.setInt(DEV_AUDIO, fid, value)
        Log.d(TAG, "write fid=$fid value=$value -> $ok")
        return ok
    }

    /** Диагностика: тип DSP (read-only). */
    fun dspType(): Int? = read(-1728052715)  // AUDIO_DSP_TYPE
}