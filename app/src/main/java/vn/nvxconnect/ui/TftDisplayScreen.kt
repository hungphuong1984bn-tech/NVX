package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nvxconnect.NvxApp
import vn.nvxconnect.domain.DataSource
import vn.nvxconnect.navigation.Fmt
import vn.nvxconnect.navigation.TurnText
import vn.nvxconnect.helpcenter.HelpContent
import vn.nvxconnect.protocol.*
import vn.nvxconnect.tftintegration.TftIntegrationInputs
import vn.nvxconnect.tftintegration.TftIntegrationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Hiển thị trên màn hình xe". Khung bên dưới được dựng bởi CHÍNH bộ dựng sẽ gửi lên TFT (TftComposer) từ sở thích + dữ liệu hiện có,
 * nên khi có giao thức TFT/dữ liệu xe đã xác minh, các trường sẽ tự xuất hiện. Hiện chưa gửi gì lên xe.
 * Tốc độ/xăng xe chỉ lấy từ xe (LIVE_VERIFIED); không dùng GPS thay thế trong khung gửi đi.
 */
@Composable
fun TftDisplayScreen(app: AppViewModel, nav: NavViewModel, ble: BleViewModel, onBack: () -> Unit, onOpenHelp: (String?) -> Unit = {}) {
    val nvx = LocalContext.current.applicationContext as NvxApp
    val s by app.settings.collectAsState()
    val fix by app.engine.lastFix.collectAsState()
    val snap by app.engine.nav.collectAsState()
    val tele by nvx.telemetry.state.collectAsState()
    val outcome by nvx.tftPipeline.last.collectAsState()
    val conn by ble.connState.collectAsState()
    val link by ble.linkStatus.collectAsState()
    val seen by ble.verifiedValueSeen.collectAsState()
    val now = rememberNow()
    val supported = nav.tft.status == TftBridgeStatus.VERIFIED_READY

    val p = snap?.progress; val ns = p?.nextStep
    val frame = TftComposer.compose(TftPrefs(s.tftMapOnly, s.tftSpeed, s.tftRoute, s.tftTrip, s.tftVehicle, s.tftClock), tele,
        maneuver = if (p != null && ns != null) TurnText.instruction(ns) + " · sau " + Fmt.distance(p.distanceToNextM) else null,
        remaining = p?.let { Fmt.distance(it.remainingM) + " · " + Fmt.duration(it.remainingS) },
        clockText = SimpleDateFormat("HH:mm", Locale.US).format(Date(now)), nowMs = now)

    Page(null) {
        AppTopBar("Hiển thị trên màn hình xe", onBack = onBack, actions = { HelpButton({ onOpenHelp(HelpContent.Id.TFT_NAV) }) })
        Segmented(listOf("Đầy đủ", "Chỉ bản đồ"), if (s.tftMapOnly) 1 else 0) { app.setTftMapOnly(it == 1) }

        Panel {
            Section("Khung sẽ gửi lên TFT")
            if (frame.isEmpty) Text("Chưa có dữ liệu nào đủ điều kiện để hiển thị (cần chỉ đường đang chạy hoặc dữ liệu xe đã xác minh).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            frame.fields.forEach { f ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(f.text, style = MaterialTheme.typography.titleMedium)
                    }
                    SourceTag(f.source)
                }
            }
            Text("Xem trước trên điện thoại, không phải hình ảnh TFT. Tốc độ và xăng xe chỉ hiện khi đến từ xe đã xác minh.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!s.tftMapOnly && s.tftSpeed && frame.field("speed") == null) fix?.let {
                Text("Tốc độ GPS điện thoại: %.0f km/h (không gửi lên TFT)".format(it.speedMps * 3.6), style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("Dữ liệu hiển thị")
        Panel {
            LabeledSwitch("Tốc độ", s.tftSpeed) { app.setTftFlag("tft_speed", it) }
            LabeledSwitch("Bản đồ chỉ đường", s.tftRoute) { app.setTftFlag("tft_route", it) }
            LabeledSwitch("Thông tin hành trình", s.tftTrip) { app.setTftFlag("tft_trip", it) }
            LabeledSwitch("Tình trạng xe (xăng xe)", s.tftVehicle) { app.setTftFlag("tft_vehicle", it) }
            LabeledSwitch("Thời gian", s.tftClock) { app.setTftFlag("tft_clock", it) }
        }
        Panel {
            StatusChip(if (supported) "SẴN SÀNG" else "CHƯA HỖ TRỢ", if (supported) Tone.Good else Tone.Warn)
            Text("Tính năng này yêu cầu đã kết nối và được xác thực giao thức Yamaha.", style = MaterialTheme.typography.bodyMedium)
            Text(nav.tft.limitation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val o = outcome) {
                // "Đã gửi" KHÔNG phải "đã hiển thị": xác nhận hiển thị chỉ có khi cầu nối TFT đã xác minh báo tích cực (xem mức tích hợp bên dưới).
                is TftDisplayPipeline.Outcome.Sent -> StatusChip("Đã gửi khung gần nhất (chưa chắc đã hiển thị)", Tone.Warn)
                is TftDisplayPipeline.Outcome.Rejected -> Text("Xe từ chối: ${o.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is TftDisplayPipeline.Outcome.Idle -> if (supported) Text(o.reason, style = MaterialTheme.typography.bodySmall)
            }
            Text("Lựa chọn của bạn được lưu và có hiệu lực ngay khi hiển thị TFT được hỗ trợ.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Sáu mức tích hợp TÁCH BIẠCH: đạt mức này không suy ra đạt mức khác.
        val report = TftIntegrationManager.evaluate(TftIntegrationInputs(conn, link, seen, nav.tft, navRunningOnPhone = snap != null, lastOutcome = outcome))
        Section("Mức tích hợp TFT")
        Panel {
            Text(report.headline, style = MaterialTheme.typography.titleSmall)
            report.levels.forEach { l ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(l.level.title, style = MaterialTheme.typography.bodyMedium)
                        Text(l.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(if (l.reached) "ĐẠT" else "CHƯA", if (l.reached) Tone.Good else Tone.Neutral)
                }
            }
        }
    }
}
