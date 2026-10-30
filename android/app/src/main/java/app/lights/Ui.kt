package app.lights

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val presets = listOf(
    Preset("Warm", 255, 214, 160),
    Preset("Candle", 255, 120, 48),
    Preset("White", 255, 255, 255),
    Preset("Red", 255, 0, 0),
    Preset("Orange", 255, 90, 0),
    Preset("Yellow", 255, 190, 0),
    Preset("Green", 0, 255, 80),
    Preset("Cyan", 0, 210, 255),
    Preset("Blue", 40, 80, 255),
    Preset("Purple", 170, 50, 255),
    Preset("Pink", 255, 40, 140),
    Preset("Mint", 90, 255, 190),
)

private data class Preset(val name: String, val r: Int, val g: Int, val b: Int)

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    MessageScreen(
        title = "Bluetooth access",
        body = "Lights needs Bluetooth to talk to the lamps directly. Nothing is sent to KS Light or LED LAMP.",
        action = "Allow Bluetooth",
        onAction = onRequest,
    )
}

@Composable
fun BluetoothOffScreen(onEnable: () -> Unit) {
    MessageScreen(
        title = "Bluetooth is off",
        body = "Turn Bluetooth on. Leave KS Light and LED LAMP closed so this app can connect.",
        action = "Turn on Bluetooth",
        onAction = onEnable,
    )
}

@Composable
private fun MessageScreen(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("KS Light and LED LAMP", color = Amber, fontSize = 12.sp)
        Text(title, color = Paper, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Muted)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)) {
            Text(action)
        }
    }
}

@Composable
fun LightsScreen(binder: LightService.LocalBinder?) {
    val appState by LightService.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showOthers by remember { mutableStateOf(false) }
    LaunchedEffect(appState.notice?.id) {
        val text = appState.notice?.text ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
    }
    val selected = appState.lights.firstOrNull { it.address == appState.selected }
    BackHandler(enabled = selected != null) { binder?.select(null) }
    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text("KS Light and LED LAMP", color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Text("Lights", color = Paper, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            if (selected == null) {
                ListScreen(appState, binder, showOthers, onShowOthers = { showOthers = it })
            } else {
                ControlScreen(selected, appState.applyAll, binder)
            }
        }
    }
}

@Composable
private fun ListScreen(
    appState: AppState,
    binder: LightService.LocalBinder?,
    showOthers: Boolean,
    onShowOthers: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { binder?.scan() },
            enabled = binder != null && !appState.scanning,
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
        ) {
            Text(if (appState.scanning) "Scanning…" else "Scan")
        }
        TextButton(onClick = { onShowOthers(!showOthers) }) {
            Text(if (showOthers) "Hide other devices" else "Show other devices")
        }
    }
    Text(
        "Close the KS Light and LED LAMP apps first. A lamp accepts one connection at a time.",
        color = Muted,
        modifier = Modifier.padding(vertical = 10.dp),
    )
    val visible = appState.lights.filter { showOthers || it.profile != null || it.saved || it.connected }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (visible.isEmpty()) {
            Text(
                if (appState.scanning) "Looking for lamps…" else "No lamps yet. Power them on and scan.",
                color = Muted,
            )
        }
        visible.forEach { light ->
            LightCard(light, binder)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightCard(light: Light, binder: LightService.LocalBinder?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .clickable(enabled = light.connected) { binder?.select(light.address) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(light.r, light.g, light.b).copy(alpha = if (light.on) light.brightness / 100f else 0.15f)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(light.displayName(), color = Paper)
                Text(statusLine(light), color = Faint, fontSize = 12.sp)
            }
            if (light.connected) {
                TextButton(onClick = { binder?.select(light.address) }) { Text("Control") }
            } else if (light.profile != null) {
                TextButton(onClick = { binder?.connect(light.address) }, enabled = !light.connecting) {
                    Text(if (light.connecting) "Connecting" else "Connect")
                }
            }
        }
        if (light.profile == null) {
            Spacer(Modifier.height(8.dp))
            Text("Choose the app this lamp used", color = Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PROFILES.forEach { profile ->
                    FilterChip(
                        selected = false,
                        onClick = { binder?.setProfile(light.address, profile.id) },
                        label = { Text(profile.label) },
                    )
                }
            }
        }
        light.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun statusLine(light: Light): String {
    val kind = profileInfo(light.profile)?.label ?: "Unrecognized"
    val link = when {
        light.connecting -> "connecting"
        light.connected -> "connected"
        light.seen -> "in range"
        else -> "saved"
    }
    val rssi = light.rssi?.let { " · $it dBm" }.orEmpty()
    return "$kind · $link$rssi"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlScreen(light: Light, applyAll: Boolean, binder: LightService.LocalBinder?) {
    val profile = profileInfo(light.profile)
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val initial = rgbToHsv(light.r, light.g, light.b)
    var hue by remember(light.address) { mutableFloatStateOf(initial.first) }
    var sat by remember(light.address) { mutableFloatStateOf(initial.second) }
    var bright by remember(light.address) { mutableFloatStateOf(light.brightness.toFloat()) }
    var speed by remember(light.address) { mutableFloatStateOf(light.speed.toFloat()) }
    var pattern by remember(light.address) { mutableIntStateOf(light.effect ?: 0) }
    var sens by remember(light.address) { mutableFloatStateOf(light.sensitivity.toFloat()) }
    var nickname by remember(light.address) { mutableStateOf(light.displayName()) }

    LaunchedEffect(light.r, light.g, light.b, light.stateSource) {
        if (!dragging && light.stateSource == "lamp") {
            val hsv = rgbToHsv(light.r, light.g, light.b)
            hue = hsv.first
            sat = hsv.second
        }
    }
    LaunchedEffect(light.brightness) { if (!dragging) bright = light.brightness.toFloat() }

    fun sendColor(r: Int, g: Int, b: Int) {
        job?.cancel()
        job = scope.launch {
            delay(80)
            binder?.color(r, g, b)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { binder?.select(null) }) { Text("Back") }
            Text(light.displayName(), color = Paper, modifier = Modifier.weight(1f))
            Button(
                onClick = { binder?.power(!light.on) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (light.on) Amber else SurfaceHigh,
                    contentColor = if (light.on) Ink else Paper,
                ),
            ) { Text(if (light.on) "On" else "Off") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Apply to every connected lamp", color = Muted, modifier = Modifier.weight(1f))
            Switch(checked = applyAll, onCheckedChange = { binder?.setApplyAll(it) })
        }
        Text(
            when (light.stateSource) {
                "lamp" -> "Color was read from the lamp."
                "sent" -> "Showing the last command sent."
                else -> "Showing the color remembered from last time."
            },
            color = Faint,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        ColorPad(hue, sat) { nextHue, nextSat ->
            dragging = true
            hue = nextHue
            sat = nextSat
            val (r, g, b) = hsvToRgb(nextHue, nextSat, 1f)
            sendColor(r, g, b)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(preset.r, preset.g, preset.b))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable {
                            val hsv = rgbToHsv(preset.r, preset.g, preset.b)
                            hue = hsv.first
                            sat = hsv.second
                            binder?.color(preset.r, preset.g, preset.b)
                        },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Brightness ${bright.toInt()}", color = Muted)
        Slider(
            value = bright,
            onValueChange = {
                dragging = true
                bright = it
            },
            onValueChangeFinished = {
                dragging = false
                binder?.brightness(bright.toInt())
            },
            valueRange = 0f..100f,
        )
        if (profile?.supportsSpeed == true && (light.profile != KS_TILDE || light.mode == "effect")) {
            Text("Pattern speed ${speed.toInt()}", color = Muted)
            Slider(
                value = speed,
                onValueChange = { speed = it },
                onValueChangeFinished = { binder?.speed(speed.toInt()) },
                valueRange = 0f..100f,
            )
        }
        Text("Patterns", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = light.mode == "solid",
                onClick = {
                    val (r, g, b) = hsvToRgb(hue, sat, 1f)
                    binder?.color(r, g, b)
                },
                label = { Text("Solid color") },
            )
            profile?.effects?.forEach { effect ->
                FilterChip(
                    selected = light.effect == effect.id && light.mode == "effect",
                    onClick = { binder?.effect(effect.id) },
                    label = { Text(effect.name) },
                )
            }
        }
        if (profile?.supportsEffectIndex == true) {
            Text("LED LAMP pattern $pattern", color = Muted, modifier = Modifier.padding(top = 8.dp))
            Slider(
                value = pattern.toFloat(),
                onValueChange = { pattern = it.toInt() },
                onValueChangeFinished = { binder?.effect(pattern) },
                valueRange = 0f..210f,
            )
        }
        if (profile?.supportsRhythm == true) {
            Text("Microphone", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RHYTHM_MODES.forEach { mode ->
                    FilterChip(
                        selected = light.mode == "rhythm" && light.rhythm == mode.id,
                        onClick = { binder?.rhythm(mode.id, light.sensitivity) },
                        label = { Text(mode.name) },
                    )
                }
            }
            Text("Mic sensitivity ${sens.toInt()} · 1 hears quiet sounds", color = Muted, fontSize = 12.sp)
            Slider(
                value = sens,
                onValueChange = { sens = it },
                onValueChangeFinished = { binder?.rhythm(light.rhythm ?: 1, sens.toInt()) },
                valueRange = 1f..8f,
            )
        }
        warningFor(light.bluetoothName, light.profile)?.let {
            Text(it, color = Color(0xFFF0C7A0), modifier = Modifier.padding(top = 8.dp))
        }
        Text("Lamp settings", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TextButton(onClick = { binder?.setNickname(light.address, nickname) }) { Text("Save name") }
        Text("Type", color = Muted, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PROFILES.forEach { item ->
                FilterChip(
                    selected = light.profile == item.id,
                    onClick = { binder?.setProfile(light.address, item.id) },
                    label = { Text(item.label) },
                )
            }
        }
        Text("Color order", color = Muted, modifier = Modifier.padding(top = 8.dp))
        Text("If green and blue are swapped, choose RBG.", color = Faint, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COLOR_ORDERS.forEach { order ->
                FilterChip(
                    selected = light.colorOrder == order,
                    onClick = { binder?.setColorOrder(light.address, order) },
                    label = { Text(order.uppercase()) },
                )
            }
        }
        if (light.profile == LEDDMX) {
            Text("Power commands", color = Muted, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = light.powerStyle == "standard",
                    onClick = { binder?.setPowerStyle(light.address, "standard") },
                    label = { Text("LED LAMP standard") },
                )
                FilterChip(
                    selected = light.powerStyle == "alt",
                    onClick = { binder?.setPowerStyle(light.address, "alt") },
                    label = { Text("Alternate on/off") },
                )
            }
        }
        Text(light.address, color = Faint, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        if (light.lastCommand.isNotBlank()) {
            Text(light.lastCommand, color = Color(0xFFCFC08A), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Row {
            if (light.connected) {
                TextButton(onClick = { binder?.disconnect(light.address) }) { Text("Disconnect") }
            }
            TextButton(onClick = {
                binder?.forget(light.address)
                binder?.select(null)
            }) { Text("Forget lamp", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ColorPad(hue: Float, sat: Float, onChange: (Float, Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun emit(position: Offset) {
                        val nextHue = (position.x / size.width).coerceIn(0f, 1f) * 360f
                        val nextSat = 1f - (position.y / size.height).coerceIn(0f, 1f)
                        onChange(nextHue, nextSat)
                    }
                    emit(down.position)
                    drag(down.id) { change ->
                        emit(change.position)
                        if (change.positionChange() != Offset.Zero) change.consume()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bands = 48
            val band = size.width / bands
            for (index in 0 until bands) {
                val color = hsvToRgb(index * 360f / bands, 1f, 1f).let { Color(it.first, it.second, it.third) }
                drawRect(
                    brush = Brush.verticalGradient(listOf(color, Color.White)),
                    topLeft = Offset(index * band, 0f),
                    size = Size(band + 1f, size.height),
                )
            }
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = Offset((hue / 360f) * size.width, (1f - sat) * size.height),
            )
        }
    }
}

private fun hsvToRgb(hue: Float, sat: Float, value: Float): Triple<Int, Int, Int> {
    val c = value * sat
    val hp = ((hue % 360f) + 360f) % 360f / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r, g, b) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = value - c
    return Triple(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
}

private fun rgbToHsv(r: Int, g: Int, b: Int): Pair<Float, Float> {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val d = max - min
    val hue = when {
        d == 0f -> 0f
        max == rf -> 60f * (((gf - bf) / d) % 6f)
        max == gf -> 60f * ((bf - rf) / d + 2f)
        else -> 60f * ((rf - gf) / d + 4f)
    }
    val sat = if (max == 0f) 0f else d / max
    return (if (hue < 0f) hue + 360f else hue) to sat
}
