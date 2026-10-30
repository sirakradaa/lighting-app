package app.lights

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Calendar
import java.util.UUID

data class Notice(val id: Long, val text: String)

data class Light(
    val address: String,
    val bluetoothName: String = "",
    val nickname: String = "",
    val profile: String? = null,
    val profileConfident: Boolean = false,
    val colorOrder: String = "rgb",
    val powerStyle: String = "standard",
    val rssi: Int? = null,
    val seen: Boolean = false,
    val saved: Boolean = false,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val on: Boolean = true,
    val r: Int = 255,
    val g: Int = 180,
    val b: Int = 90,
    val brightness: Int = 80,
    val mode: String = "solid",
    val effect: Int? = null,
    val speed: Int = 50,
    val rhythm: Int? = null,
    val sensitivity: Int = 4,
    val lastCommand: String = "",
    val error: String? = null,
    val stateSource: String = "remembered",
) {
    fun displayName(): String = nickname.ifBlank { bluetoothName.ifBlank { address } }
}

data class AppState(
    val scanning: Boolean = false,
    val lights: List<Light> = emptyList(),
    val selected: String? = null,
    val applyAll: Boolean = false,
    val notice: Notice? = null,
)

class LightService : Service() {
    inner class LocalBinder : Binder() {
        fun scan() = this@LightService.scan()
        fun connect(address: String) = this@LightService.connect(address)
        fun disconnect(address: String) = this@LightService.disconnect(address)
        fun forget(address: String) = this@LightService.forget(address)
        fun select(address: String?) = this@LightService.select(address)
        fun setApplyAll(value: Boolean) = this@LightService.setApplyAll(value)
        fun setNickname(address: String, nickname: String) = this@LightService.setNickname(address, nickname)
        fun setProfile(address: String, profile: String) = this@LightService.setProfile(address, profile)
        fun setColorOrder(address: String, order: String) = this@LightService.setColorOrder(address, order)
        fun setPowerStyle(address: String, style: String) = this@LightService.setPowerStyle(address, style)
        fun power(on: Boolean) = this@LightService.power(on)
        fun color(r: Int, g: Int, b: Int) = this@LightService.color(r, g, b)
        fun brightness(level: Int) = this@LightService.brightness(level)
        fun effect(id: Int) = this@LightService.effect(id)
        fun speed(level: Int) = this@LightService.speed(level)
        fun rhythm(mode: Int, sensitivity: Int) = this@LightService.rhythm(mode, sensitivity)
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val lights = linkedMapOf<String, Light>()
    private val sessions = linkedMapOf<String, Session>()
    private var scanning = false
    private var binds = 0
    private var foreground = false
    private var noticeId = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        load()
        publish()
    }

    override fun onBind(intent: Intent?): IBinder {
        binds += 1
        return binder
    }

    override fun onRebind(intent: Intent?) {
        binds += 1
    }

    override fun onUnbind(intent: Intent?): Boolean {
        binds -= 1
        if (binds <= 0 && lights.values.none { it.connected || it.connecting }) {
            stopSelf()
        }
        return true
    }

    override fun onDestroy() {
        stopScan()
        sessions.values.toList().forEach { it.closeNow() }
        sessions.clear()
        super.onDestroy()
    }

    fun select(address: String?) {
        state.value = state.value.copy(selected = address)
    }

    fun setApplyAll(value: Boolean) {
        state.value = state.value.copy(applyAll = value)
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            notice("Turn Bluetooth on, then scan again.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            notice("This phone cannot scan for Bluetooth lights.")
            return
        }
        if (scanning) return
        scanning = true
        lights.keys.toList().forEach { address ->
            val light = lights.getValue(address)
            if (!light.connected) lights[address] = light.copy(seen = false)
        }
        publish()
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, scanCallback)
        } catch (_: SecurityException) {
            scanning = false
            notice("Allow Bluetooth permission, then scan again.")
            publish()
            return
        }
        main.postDelayed({ stopScan() }, 8_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        val drop = lights.filterValues { !it.saved && !it.seen && !it.connected }.keys
        drop.forEach { lights.remove(it) }
        publish()
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val light = lights[address] ?: return
        if (light.profile !in PROFILE_IDS) {
            notice("Choose a lamp type before connecting.")
            return
        }
        if (light.connected && sessions[address]?.ready == true) {
            lights[address] = light.copy(saved = true)
            save()
            publish()
            return
        }
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            notice("Turn Bluetooth on, then connect.")
            return
        }
        ensureForeground("Connecting to ${light.displayName()}")
        lights[address] = light.copy(connecting = true, error = null, saved = true)
        publish()
        val previous = sessions.remove(address)
        if (previous != null) {
            previous.release { openGatt(address) }
        } else {
            openGatt(address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(address: String) {
        val light = lights[address] ?: return
        if (!light.connecting) return
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            failConnect(address, "Turn Bluetooth on, then connect.")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            failConnect(address, "That Bluetooth address is not valid.")
            return
        }
        val session = Session(address)
        sessions[address] = session
        try {
            session.gatt = device.connectGatt(this, false, session, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            failConnect(address, "Allow Bluetooth permission, then connect again.")
            return
        }
        main.postDelayed({
            val current = lights[address]
            if (current?.connecting == true && !current.connected && sessions[address] === session) {
                failConnect(address, "The lamp did not connect. Close KS Light, LED LAMP, and iStrip+, then try again.")
            }
        }, 20_000)
    }

    fun disconnect(address: String, after: (() -> Unit)? = null) {
        val session = sessions.remove(address)
        lights[address]?.let { lights[address] = it.copy(connected = false, connecting = false) }
        publish()
        maybeStopForeground()
        if (session == null) {
            if (after != null) main.postDelayed(after, 400)
            return
        }
        session.release {
            if (after != null) main.postDelayed(after, 300)
        }
    }

    fun forget(address: String) {
        disconnect(address)
        val light = lights[address] ?: return
        if (light.seen) {
            lights[address] = light.copy(saved = false, connected = false, connecting = false)
        } else {
            lights.remove(address)
        }
        if (state.value.selected == address) {
            state.value = state.value.copy(selected = null)
        }
        save()
        publish()
    }

    fun setNickname(address: String, nickname: String) {
        val clean = nickname.trim().take(40)
        if (clean.isEmpty()) return
        lights[address]?.let { lights[address] = it.copy(nickname = clean, saved = true) }
        save()
        publish()
    }

    fun setProfile(address: String, profile: String) {
        if (profile !in PROFILE_IDS) return
        val light = lights[address] ?: return
        val changed = light.profile != profile
        lights[address] = light.copy(profile = profile, profileConfident = true, saved = true)
        save()
        publish()
        if (changed && light.connected) {
            disconnect(address) { connect(address) }
        }
    }

    fun setColorOrder(address: String, order: String) {
        if (order !in COLOR_ORDERS) return
        lights[address]?.let { lights[address] = it.copy(colorOrder = order, saved = true) }
        save()
        publish()
    }

    fun setPowerStyle(address: String, style: String) {
        if (style != "standard" && style != "alt") return
        lights[address]?.let { lights[address] = it.copy(powerStyle = style, saved = true) }
        save()
        publish()
    }

    fun power(on: Boolean) = command { light ->
        var next = if (on && light.profile == ISTRIP) {
            light
        } else {
            transmit(light, Frame(kind = "power", on = on, powerStyle = light.powerStyle))
        }
        next = next.copy(on = on)
        if (on) next = resendLook(next)
        next
    }

    fun color(r: Int, g: Int, b: Int) = command { light ->
        var next = light.copy(
            r = r.coerceIn(0, 255),
            g = g.coerceIn(0, 255),
            b = b.coerceIn(0, 255),
            mode = "solid",
            effect = null,
            rhythm = null,
        )
        if (!next.on) next = turnOn(next)
        sendColor(next)
    }

    fun brightness(level: Int) = command { light ->
        val next = light.copy(brightness = level.coerceIn(0, 100))
        when {
            next.mode == "rhythm" -> next
            next.mode == "effect" && next.effect != null && (next.profile == KS_TILDE || next.profile == ISTRIP) -> sendEffect(next)
            next.mode == "solid" || next.profile == KS_TILDE || next.profile == ISTRIP -> sendColor(next)
            else -> transmit(next, visual(next, "brightness"))
        }
    }

    fun effect(id: Int) = command { light ->
        val profile = light.profile ?: error("Choose a lamp type")
        val allowed = knownEffectIds(profile)
        val effectId = if (profile == LEDDMX) id.coerceIn(0, 210) else id
        if (allowed != null && effectId !in allowed) error("That pattern is not in this lamp's list")
        var next = light.copy(mode = "effect", effect = effectId, rhythm = null)
        if (!next.on) next = turnOn(next)
        sendEffect(next)
    }

    fun speed(level: Int) = command { light ->
        val next = light.copy(speed = level.coerceIn(0, 100))
        when (next.profile) {
            KS_TILDE -> if (next.mode == "effect" && next.effect != null) sendEffect(next) else next
            ISTRIP -> if (next.mode == "effect" && next.effect != null) sendEffect(next) else sendColor(next)
            else -> transmit(next, visual(next, "speed"))
        }
    }

    fun rhythm(mode: Int, sensitivity: Int) = command { light ->
        if (light.profile != KS_TILDE) error("Microphone patterns are only on newer KS Light lamps")
        var next = light.copy(
            rhythm = mode.coerceIn(1, 4),
            sensitivity = sensitivity.coerceIn(1, 8),
            mode = "rhythm",
            effect = null,
        )
        if (!next.on) next = turnOn(next)
        transmit(next, Frame(kind = "rhythm", rhythm = next.rhythm ?: 1, sensitivity = next.sensitivity))
    }

    private fun command(block: (Light) -> Light) {
        val chosen = targets()
        if (chosen.isEmpty()) {
            notice("Connect a lamp first.")
            return
        }
        for (item in chosen) {
            try {
                val latest = lights[item.address] ?: item
                lights[item.address] = block(latest).copy(saved = true)
            } catch (error: Exception) {
                val latest = lights[item.address] ?: item
                lights[item.address] = latest.copy(error = error.message ?: "Command failed")
                notice("${latest.displayName()}: ${error.message}")
            }
        }
        save()
        publish()
    }

    private fun targets(): List<Light> {
        val connected = lights.values.filter { it.connected }
        if (state.value.applyAll) return connected
        val selected = state.value.selected?.let { lights[it] }
        return listOfNotNull(selected).filter { it.connected }
    }

    private fun turnOn(light: Light): Light {
        if (light.profile == ISTRIP) return light.copy(on = true)
        return transmit(light, Frame(kind = "power", on = true, powerStyle = light.powerStyle)).copy(on = true)
    }

    private fun resendLook(light: Light): Light {
        return when {
            light.mode == "effect" && light.effect != null -> sendEffect(light)
            light.mode == "rhythm" && light.rhythm != null -> transmit(
                light,
                Frame(kind = "rhythm", rhythm = light.rhythm, sensitivity = light.sensitivity),
            )
            else -> sendColor(light.copy(mode = "solid"))
        }
    }

    private fun sendColor(light: Light): Light {
        var next = transmit(light, visual(light, "color"))
        if (next.profile != KS_TILDE && next.profile != ISTRIP) next = transmit(next, visual(next, "brightness"))
        return next
    }

    private fun sendEffect(light: Light): Light {
        var next = transmit(light, visual(light, "effect"))
        if (next.profile == KS_CLASSIC || next.profile == LEDBLE) {
            next = transmit(next, visual(next, "speed"))
        }
        return next
    }

    private fun visual(light: Light, kind: String) = Frame(
        kind = kind,
        on = light.on,
        r = light.r,
        g = light.g,
        b = light.b,
        brightness = light.brightness,
        effect = light.effect ?: 0,
        speed = light.speed,
        colorOrder = light.colorOrder,
        powerStyle = light.powerStyle,
    )

    private fun transmit(light: Light, frame: Frame): Light {
        val session = sessions[light.address] ?: error("Lamp is not connected")
        val profile = light.profile ?: error("Choose a lamp type")
        val payload = encode(profile, frame)
        session.enqueue(payload)
        return light.copy(
            lastCommand = hexBytes(payload),
            error = null,
            stateSource = if (frame.kind == "query") light.stateSource else "sent",
        )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { mergeScan(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                notice("Bluetooth scan failed ($errorCode).")
                publish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun mergeScan(result: ScanResult) {
        val advertised = result.scanRecord?.deviceName ?: result.device.name.orEmpty()
        val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        val manufacturerIds = buildList {
            val data = result.scanRecord?.manufacturerSpecificData ?: return@buildList
            for (index in 0 until data.size()) add(data.keyAt(index))
        }
        val (profile, confident) = classify(advertised, uuids, manufacturerIds)
        val name = advertised.ifBlank { if (profile == ISTRIP) "YouOKLight" else "" }
        if (name.isBlank() && profile == null) return
        val address = result.device.address ?: return
        val existing = lights[address]
        if (existing == null) {
            lights[address] = Light(
                address = address,
                bluetoothName = name,
                nickname = name,
                profile = profile,
                profileConfident = confident,
                colorOrder = if (profile == LEDDMX) "rbg" else "rgb",
                rssi = result.rssi,
                seen = true,
            )
        } else {
            var next = existing.copy(seen = true, rssi = result.rssi)
            if (name.isNotBlank()) {
                next = next.copy(bluetoothName = name, nickname = next.nickname.ifBlank { name })
            }
            if (next.profile == null && profile != null) {
                next = next.copy(
                    profile = profile,
                    profileConfident = confident,
                    colorOrder = if (profile == LEDDMX && next.colorOrder == "rgb") "rbg" else next.colorOrder,
                )
            }
            if (confident && profile != null && next.profile != profile && !next.connected && !next.connecting) {
                next = next.copy(profile = profile, profileConfident = true)
            }
            lights[address] = next
            if (next.saved && next.profile != existing.profile) save()
        }
        publish()
    }

    private fun onReady(address: String) {
        val light = lights[address] ?: return
        lights[address] = light.copy(connected = true, connecting = false, saved = true, error = null)
        ensureForeground(light.displayName())
        save()
        publish()
        if (light.profile == KS_TILDE) {
            val session = sessions[address] ?: return
            val ready = lights.getValue(address)
            var next = transmit(ready, Frame(kind = "time", whenCalendar = Calendar.getInstance()))
            next = transmit(next, Frame(kind = "query"))
            lights[address] = next
            publish()
        }
    }

    private fun onNotify(address: String, payload: ByteArray) {
        val reading = parseKs03Notification(payload) ?: return
        val light = lights[address] ?: return
        lights[address] = light.copy(
            on = reading.on ?: light.on,
            r = reading.r,
            g = reading.g,
            b = reading.b,
            brightness = reading.brightness,
            speed = reading.speed,
            stateSource = "lamp",
            error = null,
        )
        save()
        publish()
    }

    private fun failConnect(address: String, message: String) {
        sessions.remove(address)?.release {}
        lights[address]?.let {
            lights[address] = it.copy(connected = false, connecting = false, error = message, saved = true)
        }
        notice(message)
        save()
        publish()
        maybeStopForeground()
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = getSystemService(BluetoothManager::class.java)
        return manager?.adapter
    }

    private fun notice(text: String) {
        noticeId += 1
        state.value = state.value.copy(notice = Notice(noticeId, text))
    }

    private fun publish() {
        val current = state.value
        val sorted = lights.values.sortedWith(
            compareBy<Light> { !it.connected }.thenBy { !it.seen }.thenBy { it.displayName().lowercase() },
        )
        state.value = current.copy(scanning = scanning, lights = sorted)
    }

    private fun ensureForeground(text: String) {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_light)
            .setContentTitle("Lights")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
        foreground = true
    }

    private fun maybeStopForeground() {
        if (lights.values.any { it.connected || it.connecting }) return
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Light control", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while a lamp is connected"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun prefs() = getSharedPreferences("lights", MODE_PRIVATE)

    private fun load() {
        val raw = prefs().getString("saved", null) ?: return
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return
        }
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val address = item.optString("address")
            if (address.isBlank()) continue
            val profile = if (item.isNull("profile")) null else item.optString("profile").takeIf { it in PROFILE_IDS }
            val order = item.optString("colorOrder", "rgb").takeIf { it in COLOR_ORDERS } ?: "rgb"
            val style = item.optString("powerStyle", "standard").takeIf { it == "standard" || it == "alt" } ?: "standard"
            val mode = item.optString("mode", "solid").takeIf { it == "solid" || it == "effect" || it == "rhythm" } ?: "solid"
            lights[address] = Light(
                address = address,
                bluetoothName = item.optString("bluetoothName"),
                nickname = item.optString("nickname"),
                profile = profile,
                profileConfident = item.optBoolean("profileConfident"),
                colorOrder = order,
                powerStyle = style,
                saved = true,
                on = item.optBoolean("on", true),
                r = item.optInt("r", 255),
                g = item.optInt("g", 180),
                b = item.optInt("b", 90),
                brightness = item.optInt("brightness", 80),
                mode = mode,
                effect = if (item.isNull("effect")) null else item.optInt("effect"),
                speed = item.optInt("speed", 50),
                rhythm = if (item.isNull("rhythm")) null else item.optInt("rhythm"),
                sensitivity = item.optInt("sensitivity", 4),
                stateSource = "remembered",
            )
        }
    }

    private fun save() {
        val array = JSONArray()
        lights.values.filter { it.saved }.forEach { light ->
            array.put(
                JSONObject()
                    .put("address", light.address)
                    .put("bluetoothName", light.bluetoothName)
                    .put("nickname", light.nickname)
                    .put("profile", light.profile ?: JSONObject.NULL)
                    .put("profileConfident", light.profileConfident)
                    .put("colorOrder", light.colorOrder)
                    .put("powerStyle", light.powerStyle)
                    .put("on", light.on)
                    .put("r", light.r)
                    .put("g", light.g)
                    .put("b", light.b)
                    .put("brightness", light.brightness)
                    .put("mode", light.mode)
                    .put("effect", light.effect ?: JSONObject.NULL)
                    .put("speed", light.speed)
                    .put("rhythm", light.rhythm ?: JSONObject.NULL)
                    .put("sensitivity", light.sensitivity),
            )
        }
        prefs().edit().putString("saved", array.toString()).apply()
    }

    private sealed interface Op {
        class Char(val data: ByteArray) : Op
        class Desc(val descriptor: BluetoothGattDescriptor, val data: ByteArray) : Op
    }

    private inner class Session(val address: String) : BluetoothGattCallback() {
        var gatt: BluetoothGatt? = null
        var ready: Boolean = false
            private set
        private var writeChar: BluetoothGattCharacteristic? = null
        private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        private val queue: ArrayDeque<Op> = ArrayDeque()
        private var busy = false
        private var ticket = 0
        private var closed = false
        private var released = false
        private var releaseCallback: (() -> Unit)? = null
        private var discoveryGen = 0
        private var discoveryTries = 0

        fun enqueue(data: ByteArray) {
            if (closed) return
            queue.add(Op.Char(data))
            pump()
        }

        fun closeNow() {
            closed = true
            ready = false
            queue.clear()
            val current = gatt
            gatt = null
            try {
                current?.disconnect()
            } catch (_: SecurityException) {
            }
            try {
                current?.close()
            } catch (_: SecurityException) {
            }
            finishRelease()
        }

        fun release(after: () -> Unit) {
            if (released) {
                after()
                return
            }
            releaseCallback = after
            closed = true
            ready = false
            queue.clear()
            val current = gatt
            if (current == null) {
                finishRelease()
                return
            }
            try {
                current.disconnect()
            } catch (_: SecurityException) {
                try {
                    current.close()
                } catch (_: SecurityException) {
                }
                gatt = null
                finishRelease()
                return
            }
            main.postDelayed({
                if (released) return@postDelayed
                try {
                    current.close()
                } catch (_: SecurityException) {
                }
                if (gatt === current) gatt = null
                finishRelease()
            }, 800)
        }

        private fun finishRelease() {
            if (released) return
            released = true
            val callback = releaseCallback
            releaseCallback = null
            callback?.invoke()
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    try {
                        gatt.close()
                    } catch (_: SecurityException) {
                    }
                    if (this.gatt === gatt) this.gatt = null
                    if (closed) {
                        finishRelease()
                        return@post
                    }
                    if (sessions[address] !== this@Session) return@post
                    val message = if (ready) {
                        "The lamp disconnected."
                    } else {
                        "Could not connect. Close KS Light, LED LAMP, and iStrip+, then try again."
                    }
                    ready = false
                    failConnect(address, message)
                    return@post
                }
                if (closed || sessions[address] !== this@Session) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status != BluetoothGatt.GATT_SUCCESS) {
                    failConnect(address, "Could not connect. Close KS Light, LED LAMP, and iStrip+, then try again.")
                    return@post
                }
                if (newState != BluetoothProfile.STATE_CONNECTED) return@post
                discoveryTries = 0
                scheduleDiscovery(gatt)
            }
        }

        private fun scheduleDiscovery(gatt: BluetoothGatt) {
            val gen = ++discoveryGen
            main.postDelayed({
                if (closed || gen != discoveryGen || sessions[address] !== this@Session) return@postDelayed
                try {
                    if (!gatt.discoverServices()) {
                        rediscover(gatt, "Could not read the lamp's Bluetooth services.")
                    }
                } catch (_: SecurityException) {
                    failConnect(address, "Allow Bluetooth permission, then connect again.")
                }
            }, 600)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val gen = discoveryGen
            val light = lights[address]
            val chars = if (status == BluetoothGatt.GATT_SUCCESS) gatt.services.flatMap { it.characteristics } else emptyList()
            val uuids = chars.map { it.uuid.toString() }
            val resolved = if (status == BluetoothGatt.GATT_SUCCESS) {
                resolveProfile(light?.bluetoothName, light?.profile, uuids)
            } else {
                null
            }
            val wanted = if (resolved != null) findWrite(gatt, resolved) else null
            val notify = if (wanted != null && resolved != null) {
                notifyShort(resolved)?.let { findChar(gatt, it) }
            } else {
                null
            }
            val described = if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.services.joinToString { service ->
                    service.uuid.toString() + "[" + service.characteristics.joinToString { it.uuid.toString() } + "]"
                }
            } else {
                "status=$status"
            }
            main.post {
                if (closed || gen != discoveryGen || sessions[address] !== this@Session) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "service discovery failed for $address: $described")
                    rediscover(gatt, "Could not read the lamp's Bluetooth services.")
                    return@post
                }
                if (wanted == null || resolved == null) {
                    Log.i(TAG, "light characteristic missing for $address profile=${light?.profile} resolved=$resolved: $described")
                    val fallback = fallbackProfile(light?.profile, chars)
                    if (fallback != null && light != null && discoveryTries >= 2) {
                        lights[address] = light.copy(profile = fallback, profileConfident = false, saved = true)
                        save()
                        failConnect(address, "This lamp looks like ${profileInfo(fallback)?.label}. Connect again.")
                        return@post
                    }
                    rediscover(gatt, "The lamp connected, but its controls were not ready. Connect again.")
                    return@post
                }
                if (light != null && light.profile != resolved) {
                    lights[address] = light.copy(profile = resolved, profileConfident = true, saved = true)
                    save()
                }
                writeChar = wanted
                writeType = if (wanted.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                ready = true
                if (notify != null) enableNotify(gatt, notify)
                onReady(address)
            }
        }

        private fun rediscover(gatt: BluetoothGatt, message: String) {
            if (discoveryTries >= 2) {
                failConnect(address, message)
                return
            }
            discoveryTries += 1
            refreshGatt(gatt)
            scheduleDiscovery(gatt)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val current = ticket
            main.post { finish(current) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val current = ticket
            main.post { finish(current) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { onNotify(address, value) }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            main.post { onNotify(address, value) }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD)
            if (descriptor != null) {
                queue.add(Op.Desc(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
            }
        }

        private fun pump() {
            if (busy || closed) return
            val op = queue.pollFirst() ?: return
            val gatt = gatt ?: return
            val char = writeChar
            busy = true
            val mine = ++ticket
            val started = try {
                when (op) {
                    is Op.Char -> char != null && writeChar(gatt, char, op.data, writeType)
                    is Op.Desc -> writeDesc(gatt, op.descriptor, op.data)
                }
            } catch (_: SecurityException) {
                false
            }
            if (!started) {
                busy = false
                pump()
                return
            }
            main.postDelayed({
                if (busy && ticket == mine) {
                    busy = false
                    pump()
                }
            }, 200)
        }

        private fun finish(id: Int) {
            if (!busy || id != ticket) return
            busy = false
            main.postDelayed({ pump() }, 50)
        }

        private fun writeChar(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray, type: Int): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = type
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }

        private fun writeDesc(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, data: ByteArray): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, data) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = data
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun refreshGatt(gatt: BluetoothGatt) {
        try {
            val refresh = gatt.javaClass.getMethod("refresh")
            refresh.isAccessible = true
            refresh.invoke(gatt)
        } catch (_: Exception) {
        }
    }

    private fun findWrite(gatt: BluetoothGatt, profile: String): BluetoothGattCharacteristic? {
        for (candidate in writeCandidates(profile)) {
            findChar(gatt, candidate)?.let { return it }
        }
        if (profile != KS_TILDE) return null
        val service = gatt.services.firstOrNull { uuidMatches(it.uuid.toString(), "afd0") } ?: return null
        return service.characteristics.firstOrNull { characteristic ->
            val props = characteristic.properties
            props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        }
    }

    private fun findChar(gatt: BluetoothGatt, token: String): BluetoothGattCharacteristic? {
        val uuid = bluetoothUuid(token)
        for (service in gatt.services) {
            service.getCharacteristic(uuid)?.let { return it }
            service.characteristics.firstOrNull { uuidMatches(it.uuid.toString(), token) }?.let { return it }
        }
        return null
    }

    private fun fallbackProfile(current: String?, chars: List<BluetoothGattCharacteristic>): String? {
        val present = PROFILE_IDS.filter { profile ->
            chars.any { uuidMatches(it.uuid.toString(), writeShort(profile)) }
        }
        if (current == KS_TILDE && KS_CLASSIC in present) return KS_CLASSIC
        if (current == KS_CLASSIC && KS_TILDE in present) return KS_TILDE
        return null
    }

    companion object {
        val state = MutableStateFlow(AppState())
        private const val CHANNEL = "lights"
        private const val TAG = "Lights"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
