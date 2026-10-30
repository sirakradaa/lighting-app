package app.lights

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightsTheme {
                LightsApp()
            }
        }
    }
}

fun neededPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

fun bluetoothGranted(context: Context): Boolean {
    val required = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

fun bluetoothEnabled(context: Context): Boolean {
    val manager = context.getSystemService(BluetoothManager::class.java)
    return manager?.adapter?.isEnabled == true
}

@Composable
fun LightsApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(bluetoothGranted(context)) }
    var binder by remember { mutableStateOf<LightService.LocalBinder?>(null) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = bluetoothGranted(context)
    }
    val enableBluetooth = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(neededPermissions())
    }

    DisposableEffect(granted) {
        if (!granted) {
            return@DisposableEffect onDispose { }
        }
        val intent = Intent(context, LightService::class.java)
        context.startService(intent)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? LightService.LocalBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            binder = null
            runCatching { context.unbindService(connection) }
        }
    }

    when {
        !granted -> PermissionScreen(onRequest = { permissionLauncher.launch(neededPermissions()) })
        !bluetoothEnabled(context) -> BluetoothOffScreen(
            onEnable = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        )
        else -> LightsScreen(binder)
    }
}
