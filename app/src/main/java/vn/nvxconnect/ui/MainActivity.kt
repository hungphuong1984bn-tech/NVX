package vn.nvxconnect.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.nvxconnect.helpcenter.HelpAction

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

private val tabs = listOf("Trang chủ", "Bản đồ", "Hành trình", "Xe", "Kết nối")

/** 5 mục ở thanh dưới; Cài đặt mở từ biểu tượng ở Trang chủ. */
@Composable
fun App(app: AppViewModel = viewModel(), ble: BleViewModel = viewModel(), nav: NavViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showAuto by remember { mutableStateOf(false) }
    var showTft by remember { mutableStateOf(false) }
    var showAnalysis by remember { mutableStateOf(false) }
    var showCaps by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var helpTopic by remember { mutableStateOf<String?>(null) }
    /** Mở Trung tâm hướng dẫn; topic = id bài cần mở thẳng (theo ngữ cảnh/lỗi), null = danh sách. */
    val openHelp: (String?) -> Unit = { t -> helpTopic = t; showHelp = true }
    /** Nút hành động trong bài hướng dẫn: chỉ tới các màn hình CÓ THẬT trong app. */
    val goTo: (HelpAction) -> Unit = { a ->
        showHelp = false
        when (a) {
            HelpAction.OPEN_CONNECT -> { showSettings = false; showAuto = false; showTft = false; showAnalysis = false; showCaps = false; tab = 4 }
            HelpAction.OPEN_AUTO_CONNECT -> { showSettings = false; showTft = false; showAnalysis = false; showCaps = false; tab = 4; showAuto = true }
            HelpAction.OPEN_ANALYSIS -> { showCaps = false; showAnalysis = true }
            HelpAction.OPEN_CAPABILITIES -> showCaps = true
            HelpAction.OPEN_TFT -> showTft = true
            HelpAction.OPEN_SETTINGS -> { showTft = false; showAnalysis = false; showCaps = false; showSettings = true }
            HelpAction.OPEN_MAP -> { showSettings = false; showAuto = false; showTft = false; showAnalysis = false; showCaps = false; tab = 1 }
            HelpAction.OPEN_TRIPS -> { showSettings = false; showAuto = false; showTft = false; showAnalysis = false; showCaps = false; tab = 2 }
            HelpAction.OPEN_VEHICLE -> { showSettings = false; showAuto = false; showTft = false; showAnalysis = false; showCaps = false; tab = 3 }
            // Hai hành động hệ thống được HelpCenterScreen tự xử lý, không đi qua đây.
            HelpAction.OPEN_SYSTEM_BLUETOOTH, HelpAction.OPEN_APP_SETTINGS -> {}
        }
    }
    val ctx = LocalContext.current
    var welcome by remember { mutableStateOf(!ctx.getSharedPreferences("ui", 0).getBoolean("welcome_seen", false)) }
    val icons = listOf(Icons.Filled.Home, Icons.Filled.Place, Icons.Filled.DateRange, Icons.Filled.Build, Icons.Filled.Info)
    val settings by app.settings.collectAsState()
    LaunchedEffect(settings.autoConnect, ble.saved.collectAsState().value.size) { ble.autoConnectOnce(settings.autoConnect) }
    if (welcome) WelcomeScreen { ctx.getSharedPreferences("ui", 0).edit().putBoolean("welcome_seen", true).apply(); welcome = false }
    else Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NvxBottomBar(tabs, icons, if (showSettings) -1 else tab) { i -> tab = i; showSettings = false; showAuto = false; showTft = false; showAnalysis = false; showCaps = false; showHelp = false }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (showHelp) { BackHandler { showHelp = false }; HelpCenterScreen(ble, helpTopic, onBack = { showHelp = false }, onNavigate = goTo) }
            else if (showTft) { BackHandler { showTft = false }; TftDisplayScreen(app, nav, ble, onBack = { showTft = false }, onOpenHelp = openHelp) }
            else if (showCaps) { BackHandler { showCaps = false }; CapabilityScreen(ble, onBack = { showCaps = false }) }
            else if (showAnalysis) { BackHandler { showAnalysis = false }; ProtocolAnalysisScreen(ble, onBack = { showAnalysis = false }, onOpenCapabilities = { showCaps = true }, onOpenHelp = openHelp) }
            else if (showSettings) SettingsScreen(app, ble, nav, onBack = { showSettings = false }, onOpenTft = { showTft = true }, onOpenHelp = openHelp)
            else when (tab) {
                0 -> HomeScreen(app, ble, onOpenTab = { tab = it }, onSettings = { showSettings = true }, onOpenAuto = { tab = 4; showAuto = true }, onOpenAnalysis = { showAnalysis = true })
                1 -> MapScreen(app, nav, onOpenTft = { showTft = true })
                2 -> TripsScreen(app)
                3 -> VehicleScreen(app, ble, onOpenHelp = openHelp)
                else -> if (showAuto) AutoConnectScreen(app, ble, onBack = { showAuto = false }, onOpenAnalysis = { showAnalysis = true }, onOpenHelp = openHelp)
                else ConnectScreen(ble, onOpenAuto = { showAuto = true }, onOpenHelp = openHelp)
            }
        }
    }
}
