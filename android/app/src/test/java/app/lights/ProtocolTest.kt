package app.lights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ProtocolTest {
    @Test
    fun ks03PowerColorAndScene() {
        assertEquals("5B F0 00 B5", hexBytes(encode(KS_TILDE, Frame(kind = "power", on = true))))
        assertEquals("5B 0F 00 B5", hexBytes(encode(KS_TILDE, Frame(kind = "power", on = false))))
        assertEquals(
            "5A 00 01 FF 00 00 00 50 00 A5",
            hexBytes(encode(KS_TILDE, Frame(kind = "color", r = 255, g = 0, b = 0, brightness = 80))),
        )
        assertEquals(
            "5C 00 95 28 50 00 C5",
            hexBytes(encode(KS_TILDE, Frame(kind = "effect", effect = 0x95, speed = 40, brightness = 80))),
        )
    }

    @Test
    fun ksClassicColor() {
        assertEquals(
            "7E 07 05 03 FF 80 00 00 EF",
            hexBytes(encode(KS_CLASSIC, Frame(kind = "color", r = 255, g = 128, b = 0))),
        )
        assertEquals(
            "7E 04 04 F0 00 01 FF 00 EF",
            hexBytes(encode(KS_CLASSIC, Frame(kind = "power", on = true))),
        )
    }

    @Test
    fun leddmxOrderAndBrightness() {
        assertEquals(Triple(0, 0, 255), permuteColor(0, 255, 0, "rbg"))
        assertEquals(
            "7B FF 07 00 00 FF 00 FF BF",
            hexBytes(encode(LEDDMX, Frame(kind = "color", r = 0, g = 255, b = 0, colorOrder = "rbg"))),
        )
        assertEquals("7B FF 01 20 64 00 FF FF BF", hexBytes(encode(LEDDMX, Frame(kind = "brightness", brightness = 100))))
        assertEquals("7B FF 04 03 FF FF FF FF BF", hexBytes(encode(LEDDMX, Frame(kind = "power", on = true))))
        assertEquals(
            "7B FF 04 01 FF FF FF FF BF",
            hexBytes(encode(LEDDMX, Frame(kind = "power", on = true, powerStyle = "alt"))),
        )
    }

    @Test
    fun ledbleFrames() {
        assertEquals(
            "7E FF 05 03 00 00 FF FF EF",
            hexBytes(encode(LEDBLE, Frame(kind = "color", r = 0, g = 0, b = 255))),
        )
        assertEquals("7E FF 04 00 FF FF FF FF EF", hexBytes(encode(LEDBLE, Frame(kind = "power", on = false))))
        assertEquals("7E FF 03 87 03 FF FF FF EF", hexBytes(encode(LEDBLE, Frame(kind = "effect", effect = 0x87))))
    }

    @Test
    fun classifyNames() {
        assertEquals(KS_TILDE to true, classify("KS03~A1B2"))
        assertEquals(KS_CLASSIC to true, classify("KS03-4410"))
        assertEquals(LEDDMX to true, classify("LEDDMX-03-0042"))
        assertEquals(LEDBLE to true, classify("LEDBLE-01-9201"))
        assertEquals(LEDDMX to false, classify(null, listOf("0000ffe0-0000-1000-8000-00805f9b34fb")))
        assertEquals(null to false, classify("Headphones"))
    }

    @Test
    fun weekdayAndNotification() {
        val sunday = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.OCTOBER, 25, 21, 5, 6)
            set(Calendar.MILLISECOND, 0)
        }
        val monday = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.OCTOBER, 26, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(1, sundayOne(sunday))
        assertEquals(2, sundayOne(monday))
        assertEquals("7E 07 83 15 05 06 01 FF EF", hexBytes(encode(KS_TILDE, Frame(kind = "time", whenCalendar = sunday))))
        val parsed = parseKs03Notification(hexToBytes("5f0601f0ff00000064640000"))
        assertEquals(true, parsed?.on)
        assertEquals(255, parsed?.r)
        assertEquals(100, parsed?.brightness)
        assertEquals(0, parsed?.speed)
    }

    @Test
    fun ks03CharacteristicUuidMatches() {
        assertTrue(uuidMatches("0000afd1-0000-1000-8000-00805f9b34fb", "afd1"))
        assertTrue(uuidMatches("0000AFD1-ABCD-1000-8000-00805F9B34FB", "afd1"))
        assertEquals(
            "0000afd1-0000-1000-8000-00805f9b34fb",
            bluetoothUuid("afd1").toString(),
        )
        assertEquals(
            KS_TILDE,
            resolveProfile(
                "KS03~0021b0",
                LEDDMX,
                listOf(
                    "0000afd1-0000-1000-8000-00805f9b34fb",
                    "0000afd2-0000-1000-8000-00805f9b34fb",
                ),
            ),
        )
    }

    @Test
    fun youOkLightUsesEncryptedIstripColor() {
        assertEquals(ISTRIP to true, classify("YouOKLight Sunset"))
        assertEquals(ISTRIP to true, classify("YH-2356"))
        assertEquals(ISTRIP to true, classify(null, manufacturerIds = listOf(0x5254)))
        assertEquals(
            ISTRIP to false,
            classify(null, listOf("0000ac50-1212-efde-1523-785fedbeda25")),
        )
        val packet = encode(ISTRIP, Frame(kind = "color", r = 255, g = 0, b = 0, brightness = 80, speed = 40))
        assertEquals(16, packet.size)
        val plain = istripPlain(Frame(kind = "color", r = 255, g = 0, b = 0, brightness = 80, speed = 40))
        assertEquals("54 52 00 57 02 01 00 FF 00 00 50 28 00 00 00 00", hexBytes(plain))
        assertEquals("54 52 00 57 02 01 00 00 00 00 00 32 00 00 00 00", hexBytes(istripPlain(Frame(kind = "power", on = false, speed = 50))))
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(
                byteArrayOf(
                    0x34, 0x52, 0x2A, 0x5B, 0x7A, 0x6E, 0x49, 0x2C,
                    0x08, 0x09, 0x0A, 0x9D.toByte(), 0x8D.toByte(), 0x2A, 0x23, 0xF8.toByte(),
                ),
                "AES",
            ),
        )
        assertEquals(hexBytes(plain), hexBytes(cipher.doFinal(packet)))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
