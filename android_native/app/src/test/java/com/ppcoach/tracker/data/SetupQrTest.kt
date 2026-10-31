package com.ppcoach.tracker.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SetupQrTest {
    @Test
    fun aSetupQrReadsBackAndTheServerIsNormalized() {
        val qr = SetupQr("https://ppcoachtracker.example.com", "coach@example.com", "s3cret pass")
        assertEquals(qr, SetupQr.parse(qr.toJson()))
        // Without a password: the coach types it.
        assertEquals("", SetupQr.parse("""{"pp_coach_tracker":"setup","v":1,"server":"https://a.example","email":"c@d.e"}""").password)
        // A bare host gets https and loses the trailing slash, as in Settings.
        assertEquals("https://a.example", SetupQr.parse("""{"pp_coach_tracker":"setup","v":1,"server":"a.example/","email":"c@d.e"}""").server)
        assertEquals("http://10.0.2.2:8097/pp-coach-tracker", SetupQr.parse("""{"pp_coach_tracker":"setup","v":1,"server":"http://10.0.2.2:8097/pp-coach-tracker","email":"c@d.e"}""").server)
    }

    @Test
    fun whatIsNotASetupQrSaysWhy() {
        fun reason(text: String) = assertThrows(SetupQr.Invalid::class.java) { SetupQr.parse(text) }.reason
        assertEquals(SetupQr.Reason.NOT_SETUP, reason("https://example.com"))
        assertEquals(SetupQr.Reason.NOT_SETUP, reason("""{"server":"https://a.example","email":"c@d.e"}"""))
        assertEquals(SetupQr.Reason.VERSION, reason("""{"pp_coach_tracker":"setup","v":2,"server":"https://a.example","email":"c@d.e"}"""))
        assertEquals(SetupQr.Reason.SERVER, reason("""{"pp_coach_tracker":"setup","v":1,"server":"","email":"c@d.e"}"""))
        assertEquals(SetupQr.Reason.SERVER, reason("""{"pp_coach_tracker":"setup","v":1,"server":"https://a b","email":"c@d.e"}"""))
        assertEquals(SetupQr.Reason.EMAIL, reason("""{"pp_coach_tracker":"setup","v":1,"server":"https://a.example","email":"nobody"}"""))
    }

    @Test
    fun theDecoderReadsAQrPictureAndNothingElse() {
        val text = SetupQr("https://ppcoachtracker.example.com", "coach@example.com", "pw").toJson()
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
        val pixels = IntArray(300 * 300) { i -> if (m.get(i % 300, i / 300)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(text, QrDecoder.decode(RGBLuminanceSource(300, 300, pixels)))
        assertNull(QrDecoder.decode(RGBLuminanceSource(300, 300, IntArray(300 * 300) { 0xFFFFFFFF.toInt() })))
    }
}
