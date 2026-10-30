package app.lights

import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

const val KS_TILDE = "ks03_tilde"
const val KS_CLASSIC = "ks_classic"
const val LEDDMX = "leddmx"
const val LEDBLE = "ledble"
const val ISTRIP = "istrip"

val PROFILE_IDS = listOf(KS_TILDE, KS_CLASSIC, LEDDMX, LEDBLE, ISTRIP)
val COLOR_ORDERS = listOf("rgb", "rbg", "grb", "gbr", "brg", "bgr")

data class EffectOption(val id: Int, val name: String)

data class ProfileInfo(
    val id: String,
    val label: String,
    val effects: List<EffectOption>,
    val supportsSpeed: Boolean,
    val supportsRhythm: Boolean,
    val supportsEffectIndex: Boolean,
    val effectMin: Int,
    val effectMax: Int,
)

val KS_EFFECTS = listOf(
    EffectOption(0x80, "Seven-color jump"),
    EffectOption(0x81, "RGB jump"),
    EffectOption(0x82, "Seven-color pulse"),
    EffectOption(0x83, "RGB pulse"),
    EffectOption(0x84, "Red pulse"),
    EffectOption(0x85, "Green pulse"),
    EffectOption(0x86, "Blue pulse"),
    EffectOption(0x87, "Yellow pulse"),
    EffectOption(0x88, "Cyan pulse"),
    EffectOption(0x89, "Purple pulse"),
    EffectOption(0x8A, "White pulse"),
    EffectOption(0x8B, "Red and green pulse"),
    EffectOption(0x8C, "Red and blue pulse"),
    EffectOption(0x8D, "Green and blue pulse"),
    EffectOption(0x8E, "Seven-color strobe"),
    EffectOption(0x8F, "RGB strobe"),
    EffectOption(0x90, "Red strobe"),
    EffectOption(0x91, "Green strobe"),
    EffectOption(0x92, "Blue strobe"),
    EffectOption(0x93, "Yellow strobe"),
    EffectOption(0x94, "Cyan strobe"),
    EffectOption(0x95, "Purple strobe"),
    EffectOption(0x96, "White strobe"),
)

val LEDBLE_EFFECTS = (0 until 23).map { EffectOption(0x87 + it, "Pattern ${it + 1}") }

val RHYTHM_MODES = listOf(
    EffectOption(1, "Fade"),
    EffectOption(2, "Pulse"),
    EffectOption(3, "Jump"),
    EffectOption(4, "Flash"),
)

val PROFILES = listOf(
    ProfileInfo(KS_TILDE, "KS Light · newer KS03~", KS_EFFECTS, supportsSpeed = true, supportsRhythm = true, supportsEffectIndex = false, effectMin = 0, effectMax = 0),
    ProfileInfo(KS_CLASSIC, "KS Light · older KS models", KS_EFFECTS, supportsSpeed = true, supportsRhythm = false, supportsEffectIndex = false, effectMin = 0, effectMax = 0),
    ProfileInfo(LEDDMX, "LED LAMP · LEDDMX strip", emptyList(), supportsSpeed = false, supportsRhythm = false, supportsEffectIndex = true, effectMin = 0, effectMax = 210),
    ProfileInfo(LEDBLE, "LED LAMP · LEDBLE controller", LEDBLE_EFFECTS, supportsSpeed = true, supportsRhythm = false, supportsEffectIndex = false, effectMin = 0, effectMax = 0),
    ProfileInfo(ISTRIP, "YouOKLight sunset · iStrip+", emptyList(), supportsSpeed = false, supportsRhythm = false, supportsEffectIndex = false, effectMin = 0, effectMax = 0),
)

fun profileInfo(id: String?): ProfileInfo? = PROFILES.firstOrNull { it.id == id }

private val WRITE_SHORT = mapOf(
    KS_TILDE to "afd1",
    KS_CLASSIC to "fff3",
    LEDDMX to "ffe1",
    LEDBLE to "ffe1",
    ISTRIP to "0000ac52-1212-efde-1523-785fedbeda25",
)

private const val ISTRIP_NOTIFY = "0000ac51-1212-efde-1523-785fedbeda25"
private const val ISTRIP_ALT_WRITE = "0000ae01-0000-1000-8000-00805f9b34fb"

fun writeShort(profile: String): String = WRITE_SHORT.getValue(profile)

fun writeCandidates(profile: String): List<String> =
    if (profile == ISTRIP) listOf(writeShort(profile), ISTRIP_ALT_WRITE) else listOf(writeShort(profile))

fun notifyShort(profile: String): String? = when (profile) {
    KS_TILDE -> "afd2"
    ISTRIP -> ISTRIP_NOTIFY
    else -> null
}

data class Frame(
    val kind: String,
    val on: Boolean = true,
    val r: Int = 255,
    val g: Int = 255,
    val b: Int = 255,
    val brightness: Int = 100,
    val effect: Int = 0,
    val speed: Int = 50,
    val rhythm: Int = 1,
    val sensitivity: Int = 4,
    val colorOrder: String = "rgb",
    val powerStyle: String = "standard",
    val whenCalendar: Calendar? = null,
)

fun classify(
    name: String?,
    serviceUuids: List<String> = emptyList(),
    manufacturerIds: List<Int> = emptyList(),
): Pair<String?, Boolean> {
    val label = name?.trim()?.uppercase().orEmpty()
    val services = serviceUuids.joinToString(" ").lowercase().replace("-", "")
    if (label.startsWith("KS03~")) return KS_TILDE to true
    if (label.startsWith("KS")) return KS_CLASSIC to true
    if (label.startsWith("LEDDMX") || label.startsWith("LEDCAR")) return LEDDMX to true
    if (label.startsWith("LEDBLE") || label.startsWith("LEDSTAGE") || label.startsWith("LEDLIGHT")) {
        return LEDBLE to true
    }
    if ("ISTRIP" in label || "YOUOK" in label || label.startsWith("YH-") || label.startsWith("YH_")) {
        return ISTRIP to true
    }
    fun has(short: String): Boolean {
        val token = "0000${short}00001000800000805f9b34fb"
        return token in services || short in services.split(" ")
    }
    if (has("afd0") || has("afd1")) return KS_TILDE to false
    if (has("ffe0") || has("ffe1")) return LEDDMX to false
    if (has("fff0") || has("fff3")) return KS_CLASSIC to false
    if ("0000ac501212efde1523785fedbeda25" in services || "0000ac521212efde1523785fedbeda25" in services) {
        return ISTRIP to false
    }
    if (0x5254 in manufacturerIds) return ISTRIP to true
    return null to false
}

fun warningFor(name: String?, profile: String?): String? {
    val label = name?.uppercase().orEmpty()
    if (profile == LEDDMX && listOf("LEDDMX-02", "LEDDMX-04", "LEDCAR-02").any { it in label }) {
        return "This model sometimes uses a shifted LED LAMP frame. If color and power do nothing, it is a variant this app does not speak yet."
    }
    if (profile == KS_CLASSIC) {
        return "Older KS lamps share one scene list with KS03~. A few of them ignore scene commands and still accept power, color, and brightness."
    }
    if (profile == ISTRIP) {
        return "Close iStrip+ before connecting. If the hue is wrong, set Color order to GRB."
    }
    return null
}

fun knownEffectIds(profile: String): Set<Int>? {
    val info = profileInfo(profile) ?: return emptySet()
    if (info.supportsEffectIndex) return null
    return info.effects.map { it.id }.toSet()
}

fun permuteColor(r: Int, g: Int, b: Int, order: String): Triple<Int, Int, Int> {
    require(order in COLOR_ORDERS) { "Unknown color order: $order" }
    val channels = mapOf('r' to r.coerceIn(0, 255), 'g' to g.coerceIn(0, 255), 'b' to b.coerceIn(0, 255))
    val values = order.map { channels.getValue(it) }
    return Triple(values[0], values[1], values[2])
}

fun sundayOne(calendar: Calendar): Int = calendar.get(Calendar.DAY_OF_WEEK)

fun uuidMatches(uuid: String, short: String): Boolean {
    val compact = uuid.lowercase().replace("-", "")
    val needle = short.lowercase()
    if (needle.length == 4) {
        return compact == "0000${needle}00001000800000805f9b34fb" || compact == needle || compact.startsWith("0000$needle")
    }
    return compact == needle.replace("-", "")
}

fun bluetoothUuid(token: String): UUID {
    val clean = token.lowercase()
    val full = if (clean.length == 4) "0000$clean-0000-1000-8000-00805f9b34fb" else clean
    return UUID.fromString(full)
}

fun resolveProfile(name: String?, saved: String?, characteristicUuids: List<String>): String? {
    fun has(token: String) = characteristicUuids.any { uuidMatches(it, token) }
    fun supports(profile: String) = writeCandidates(profile).any { has(it) }
    val (named, confident) = classify(name)
    if (confident && named != null && supports(named)) return named
    if (saved != null && supports(saved)) return saved
    if (supports(KS_TILDE)) return KS_TILDE
    if (supports(KS_CLASSIC)) return KS_CLASSIC
    if (supports(ISTRIP)) return ISTRIP
    if (supports(LEDDMX)) return if (named == LEDBLE) LEDBLE else LEDDMX
    return if (confident) named else saved
}

private val ISTRIP_KEY = byteArrayOf(
    0x34, 0x52, 0x2A, 0x5B, 0x7A, 0x6E, 0x49, 0x2C,
    0x08, 0x09, 0x0A, 0x9D.toByte(), 0x8D.toByte(), 0x2A, 0x23, 0xF8.toByte(),
)

fun encode(profile: String, frame: Frame): ByteArray {
    require(profile in PROFILE_IDS) { "Unknown profile: $profile" }
    return when (profile) {
        KS_TILDE -> encodeKsTilde(frame)
        KS_CLASSIC -> encodeKsClassic(frame)
        LEDDMX -> encodeLeddmx(frame)
        ISTRIP -> encryptIstrip(istripPlain(frame))
        else -> encodeLedble(frame)
    }
}

internal fun istripPlain(frame: Frame): ByteArray {
    val bright = level(frame.brightness)
    val pace = level(frame.speed)
    return when (frame.kind) {
        "power" -> if (frame.on) {
            istripColor(frame, bright, pace)
        } else {
            bytes(0x54, 0x52, 0x00, 0x57, 2, 1, 0, 0, 0, 0, 0, pace, 0, 0, 0, 0)
        }
        "color", "brightness" -> istripColor(frame, bright, pace)
        "effect" -> {
            val music = frame.effect >= 100
            val type = if (music) 3 else 5
            val mode = if (music) (frame.effect - 100).coerceIn(1, 4) else frame.effect.coerceIn(0, 255)
            bytes(0x54, 0x52, 0x00, 0x57, type, 1, mode, 0, 0, 0, bright, pace, 0, 0, 0, 0)
        }
        else -> error("iStrip does not use a separate ${frame.kind} frame")
    }
}

private fun istripColor(frame: Frame, bright: Int, pace: Int): ByteArray {
    val (r, g, b) = permuteColor(frame.r, frame.g, frame.b, frame.colorOrder)
    return bytes(0x54, 0x52, 0x00, 0x57, 2, 1, 0, r, g, b, bright, pace, 0, 0, 0, 0)
}

private fun encryptIstrip(plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ISTRIP_KEY, "AES"))
    return cipher.doFinal(plain)
}

private fun encodeKsTilde(frame: Frame): ByteArray = when (frame.kind) {
    "power" -> bytes(0x5B, if (frame.on) 0xF0 else 0x0F, 0x00, 0xB5)
    "color" -> {
        val (r, g, b) = permuteColor(frame.r, frame.g, frame.b, frame.colorOrder)
        bytes(0x5A, 0x00, 0x01, r, g, b, 0x00, level(frame.brightness), 0x00, 0xA5)
    }
    "effect" -> bytes(0x5C, 0x00, frame.effect.coerceIn(0, 255), level(frame.speed), level(frame.brightness), 0x00, 0xC5)
    "rhythm" -> bytes(0x5A, 0x0A, 0xF0, frame.rhythm.coerceIn(1, 4), frame.sensitivity.coerceIn(1, 8), 0xA5)
    "query" -> bytes(0x5F, 0x01, 0x00, 0xF5)
    "time" -> {
        val whenCalendar = frame.whenCalendar ?: Calendar.getInstance()
        bytes(
            0x7E,
            0x07,
            0x83,
            whenCalendar.get(Calendar.HOUR_OF_DAY),
            whenCalendar.get(Calendar.MINUTE),
            whenCalendar.get(Calendar.SECOND),
            sundayOne(whenCalendar),
            0xFF,
            0xEF,
        )
    }
    else -> error("KS03~ does not use a separate ${frame.kind} frame")
}

private fun encodeKsClassic(frame: Frame): ByteArray = when (frame.kind) {
    "power" -> if (frame.on) {
        bytes(0x7E, 0x04, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF)
    } else {
        bytes(0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF)
    }
    "color" -> {
        val (r, g, b) = permuteColor(frame.r, frame.g, frame.b, frame.colorOrder)
        bytes(0x7E, 0x07, 0x05, 0x03, r, g, b, 0x00, 0xEF)
    }
    "brightness" -> bytes(0x7E, 0x07, 0x01, level(frame.brightness), 0xFF, 0xFF, 0xFF, 0x00, 0xEF)
    "speed" -> bytes(0x7E, 0x04, 0x02, level(frame.speed), 0xFF, 0xFF, 0xFF, 0x00, 0xEF)
    "effect" -> bytes(0x7E, 0x07, 0x03, frame.effect.coerceIn(0, 255), 0x03, 0xFF, 0xFF, 0x00, 0xEF)
    else -> error("Older KS lamps do not use a separate ${frame.kind} frame")
}

private fun encodeLeddmx(frame: Frame): ByteArray = when (frame.kind) {
    "power" -> {
        val state = if (frame.powerStyle == "alt") {
            if (frame.on) 0x01 else 0x00
        } else {
            if (frame.on) 0x03 else 0x02
        }
        bytes(0x7B, 0xFF, 0x04, state, 0xFF, 0xFF, 0xFF, 0xFF, 0xBF)
    }
    "color" -> {
        val (c1, c2, c3) = permuteColor(frame.r, frame.g, frame.b, frame.colorOrder)
        bytes(0x7B, 0xFF, 0x07, c1, c2, c3, 0x00, 0xFF, 0xBF)
    }
    "brightness" -> {
        val amount = level(frame.brightness)
        bytes(0x7B, 0xFF, 0x01, (amount * 32) / 100, amount, 0x00, 0xFF, 0xFF, 0xBF)
    }
    "effect" -> bytes(0x7B, 0xFF, 0x03, frame.effect.coerceIn(0, 210), 0xFF, 0xFF, 0xFF, 0xFF, 0xBF)
    else -> error("LEDDMX does not use a separate ${frame.kind} frame")
}

private fun encodeLedble(frame: Frame): ByteArray = when (frame.kind) {
    "power" -> bytes(0x7E, 0xFF, 0x04, if (frame.on) 0x01 else 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF)
    "color" -> {
        val (r, g, b) = permuteColor(frame.r, frame.g, frame.b, frame.colorOrder)
        bytes(0x7E, 0xFF, 0x05, 0x03, r, g, b, 0xFF, 0xEF)
    }
    "brightness" -> bytes(0x7E, 0xFF, 0x01, level(frame.brightness), 0x00, 0xFF, 0xFF, 0xFF, 0xEF)
    "speed" -> bytes(0x7E, 0xFF, 0x02, level(frame.speed), 0x00, 0xFF, 0xFF, 0xFF, 0xEF)
    "effect" -> bytes(0x7E, 0xFF, 0x03, frame.effect.coerceIn(0, 255), 0x03, 0xFF, 0xFF, 0xFF, 0xEF)
    else -> error("LEDBLE does not use a separate ${frame.kind} frame")
}

data class LampReading(
    val on: Boolean?,
    val r: Int,
    val g: Int,
    val b: Int,
    val brightness: Int,
    val speed: Int,
)

fun parseKs03Notification(payload: ByteArray): LampReading? {
    if (payload.size < 12 || payload[0] != 0x5F.toByte() || payload[1] != 0x06.toByte() || payload[2] != 0x01.toByte()) {
        return null
    }
    val power = payload[3].toInt() and 0xFF
    val on = when (power) {
        0xF0 -> true
        0x0F -> false
        else -> null
    }
    return LampReading(
        on = on,
        r = payload[4].toInt() and 0xFF,
        g = payload[5].toInt() and 0xFF,
        b = payload[6].toInt() and 0xFF,
        brightness = (payload[8].toInt() and 0xFF).coerceIn(0, 100),
        speed = (100 - (payload[9].toInt() and 0xFF)).coerceIn(0, 100),
    )
}

fun hexBytes(payload: ByteArray): String = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

private fun level(value: Int) = value.coerceIn(0, 100)

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
