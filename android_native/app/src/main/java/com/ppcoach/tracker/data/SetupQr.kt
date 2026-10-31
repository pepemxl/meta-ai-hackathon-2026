package com.ppcoach.tracker.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject

/**
 * The setup QR of the analysis system: the server, the coach's email and,
 * optionally, the password. Scanning it fills Settings and logs in; the
 * password is only used for that login, never stored (only the session is).
 *
 *   {"pp_coach_tracker":"setup","v":1,"server":"https://…","email":"…","password":"…"}
 *
 * worker/tools/setup_qr.py makes one. A QR with a password is a key: whoever
 * has the picture can log in as that coach.
 */
data class SetupQr(val server: String, val email: String, val password: String = "") {
    fun toJson(): String = JSONObject().put(KEY, KIND).put("v", VERSION).put("server", server).put("email", email)
        .apply { if (password.isNotEmpty()) put("password", password) }.toString()

    class Invalid(val reason: Reason) : Exception(reason.name)

    enum class Reason { NOT_SETUP, VERSION, SERVER, EMAIL }

    companion object {
        const val KEY = "pp_coach_tracker"
        const val KIND = "setup"
        const val VERSION = 1
        private const val MAX_FIELD = 300

        /** The QR text → a setup, or Invalid with why (not ours, another version, a bad server or email). */
        fun parse(text: String): SetupQr {
            val o = try {
                JSONObject(text.trim())
            } catch (e: Exception) {
                throw Invalid(Reason.NOT_SETUP)
            }
            if (o.optString(KEY) != KIND) throw Invalid(Reason.NOT_SETUP)
            if (o.optInt("v", -1) != VERSION) throw Invalid(Reason.VERSION)
            val server = ApiClient.normalizeUrl(o.optString("server").take(MAX_FIELD))
            if (!Regex("^https?://[^\\s/?#]+(\\S*)$").matches(server)) throw Invalid(Reason.SERVER)
            val email = o.optString("email").trim().take(MAX_FIELD)
            if (!Regex("^[^@\\s]+@[^@\\s]+$").matches(email)) throw Invalid(Reason.EMAIL)
            return SetupQr(server, email, o.optString("password").take(MAX_FIELD))
        }
    }
}

/** Reads a QR code from a grayscale picture (camera frame or image), or null if there is none. */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
