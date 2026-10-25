package vn.nvxconnect.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.nvxconnect.NvxApp
import vn.nvxconnect.helpcenter.HelpAction
import vn.nvxconnect.pro.ProNavState
import vn.nvxconnect.pro.ProPage
import vn.nvxconnect.pro.ProTab
import vn.nvxconnect.pro.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nvx = application as NvxApp
        setContent {
            val pro by nvx.proPrefs.state.collectAsState()
            val dark = when (pro.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
            // Biểu tượng thanh trạng thái/điều hướng đúng độ tương phản theo chủ đề của ỨNG DỤNG (không phải của hệ thống).
            DisposableEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(statusBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t))
                onDispose { }
            }
            AppTheme(pro.theme) { App() }
        }
    }
}

private val navSaver = Saver<ProNavState, Any>(save = { it.encode() }, restore = { ProNavState.decode(it as? String) })
private val tabIcons = listOf(Icons.Filled.Home, Icons.Filled.Info, Icons.Filled.DateRange, Icons.Filled.Place, Icons.Filled.Build)

/**
 * 5 tab: Trang chủ, Cockpit, Telemetry, Hành trình, Công cụ. Trang con (Protocol Lab, Chẩn đoán, Cài đặt...) nằm trong tab sở hữu.
 * Trạng thái điều hướng lưu qua xoay màn hình/tiến trình bị thu hồi; trạng thái cuộn/lọc của từng màn hình giữ khi đổi tab.
 * Chỉ MỘT BleViewModel/AppViewModel/NavViewModel (phạm vi Activity) nên không có bản sao phiên BLE.
 */
@Composable
fun App(app: AppViewModel = viewModel(), ble: BleViewModel = viewModel(), navVm: NavViewModel = viewModel()) {
    var nav by rememberSaveable(stateSaver = navSaver) { mutableStateOf(ProNavState()) }
    val ctx = LocalContext.current
    var welcome by remember { mutableStateOf(!ctx.getSharedPreferences("ui", 0).getBoolean("welcome_seen", false)) }
    val settings by app.settings.collectAsState()
    val replay by app.replay.collectAsState()
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(settings.autoConnect, ble.saved.collectAsState().value.size) { ble.autoConnectOnce(settings.autoConnect) }

    val open: (ProPage) -> Unit = { nav = nav.open(it) }
    val openHelp: (String?) -> Unit = { nav = nav.openHelp(it) }
    val back: () -> Unit = { nav = nav.back() }
    val goTo: (HelpAction) -> Unit = { a ->
        when (a) {
            HelpAction.OPEN_CONNECT -> open(ProPage.CONNECT)
            HelpAction.OPEN_AUTO_CONNECT -> open(ProPage.AUTO)
            HelpAction.OPEN_ANALYSIS -> open(ProPage.LAB)
            HelpAction.OPEN_CAPABILITIES -> open(ProPage.CAPABILITIES)
            HelpAction.OPEN_TFT -> open(ProPage.TFT)
            HelpAction.OPEN_SETTINGS -> open(ProPage.SETTINGS)
            HelpAction.OPEN_MAP -> open(ProPage.MAP)
            HelpAction.OPEN_TRIPS -> nav = nav.selectTab(ProTab.RIDES)
            HelpAction.OPEN_VEHICLE -> open(ProPage.VEHICLE)
            // Hai hành động hệ thống được HelpCenterScreen tự xử lý, không đi qua đây.
            HelpAction.OPEN_SYSTEM_BLUETOOTH, HelpAction.OPEN_APP_SETTINGS -> {}
        }
    }
    // Back: đóng chi tiết hành trình → đóng cockpit toàn màn hình → bỏ trang con → về Trang chủ → (hệ thống) thoát.
    BackHandler(enabled = !welcome && replay != null) { app.closeReplay() }
    BackHandler(enabled = !welcome && replay == null && nav.canGoBack) { back() }

    if (welcome) { WelcomeScreen { ctx.getSharedPreferences("ui", 0).edit().putBoolean("welcome_seen", true).apply(); welcome = false }; return }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (!nav.fullscreenCockpit) NvxBottomBar(ProTab.values().map { it.title }, tabIcons, nav.tab.ordinal) { i -> nav = nav.selectTab(ProTab.values()[i]); app.closeReplay() }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            holder.SaveableStateProvider("${nav.tab.name}:${nav.page?.name ?: "root"}") {
                when (val p = nav.page) {
                    ProPage.LAB -> ProtocolLabScreen(ble, onBack = back, onOpenCapabilities = { open(ProPage.CAPABILITIES) }, onOpenHelp = openHelp, onOpenConnect = { open(ProPage.CONNECT) })
                    ProPage.DIAGNOSTICS -> DiagnosticsScreen(ble, onBack = back, onOpenHelp = openHelp, onOpenLab = { open(ProPage.LAB) })
                    ProPage.SETTINGS -> SettingsScreen(app, ble, navVm, onBack = back, onOpenTft = { open(ProPage.TFT) }, onOpenHelp = openHelp, onOpenLab = { open(ProPage.LAB) })
                    ProPage.CONNECT -> ConnectScreen(ble, onOpenAuto = { open(ProPage.AUTO) }, onOpenHelp = openHelp)
                    ProPage.AUTO -> AutoConnectScreen(app, ble, onBack = back, onOpenAnalysis = { open(ProPage.LAB) }, onOpenHelp = openHelp)
                    ProPage.CAPABILITIES -> CapabilityScreen(ble, onBack = back)
                    ProPage.TFT -> TftDisplayScreen(app, navVm, ble, onBack = back, onOpenHelp = openHelp)
                    ProPage.HELP -> HelpCenterScreen(ble, nav.helpTopic, onBack = back, onNavigate = goTo)
                    ProPage.MAP -> MapScreen(app, navVm, onOpenTft = { open(ProPage.TFT) })
                    ProPage.VEHICLE -> VehicleScreen(app, ble, onOpenHelp = openHelp)
                    null -> when (nav.tab) {
                        ProTab.HOME -> HomeScreen(app, ble, onOpenTab = { nav = nav.selectTab(it) }, onOpenPage = open, onOpenHelp = openHelp)
                        ProTab.COCKPIT -> CockpitScreen(app, ble, fullscreen = nav.fullscreenCockpit, onFullscreen = { nav = if (it) nav.enterCockpit() else nav.exitCockpit() },
                            onOpenConnect = { open(ProPage.CONNECT) }, onOpenSettings = { open(ProPage.SETTINGS) })
                        ProTab.TELEMETRY -> TelemetryCenterScreen(app, onOpenConnect = { open(ProPage.CONNECT) })
                        ProTab.RIDES -> TripsScreen(app, onOpenMap = { open(ProPage.MAP) })
                        ProTab.TOOLS -> ToolsScreen(onOpen = open)
                    }
                }
            }
        }
    }
}
