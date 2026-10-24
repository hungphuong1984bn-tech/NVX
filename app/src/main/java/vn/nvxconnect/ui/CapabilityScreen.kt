package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.nvxconnect.capability.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun capTone(s: CapabilityStatus) = when (s) {
    CapabilityStatus.AVAILABLE -> Tone.Good
    CapabilityStatus.OBSERVED, CapabilityStatus.HYPOTHESIS -> Tone.Warn
    CapabilityStatus.UNSUPPORTED -> Tone.Bad
    CapabilityStatus.UNKNOWN -> Tone.Neutral
}

private fun integrationTone(i: Integration) = when (i) {
    Integration.CAN_INTEGRATE, Integration.PHONE_INDEPENDENT -> Tone.Good
    Integration.AWAITING_ADAPTER, Integration.NEEDS_APP_WORK -> Tone.Warn
    Integration.BLOCKED -> Tone.Bad
    Integration.UNKNOWN -> Tone.Neutral
}

/**
 * Danh mục chức năng xe/app, bảng đối chiếu, việc còn thiếu và hồ sơ giao thức. Không có phần trăm tin cậy:
 * mọi nhãn là trạng thái bằng chứng thật hoặc "chưa biết".
 */
@Composable
fun CapabilityScreen(ble: BleViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val report by ble.capabilityReport.collectAsState()
    val state by ble.connState.collectAsState()
    val now = rememberNow()
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var onlyOpen by remember { mutableStateOf(false) }
    // Dựng lại khi trạng thái kết nối đổi và mỗi 5 giây (để độ mới dữ liệu tự cập nhật).
    LaunchedEffect(state, now / 5000) { ble.refreshCapabilities() }

    Page(null) {
        AppTopBar("Danh mục chức năng", "Xe mục tiêu: ${ProtocolProfileCatalog.TARGET_VEHICLE}", onBack = onBack)
        val r = report
        if (r == null) { LoadingState("Đang dựng danh mục…"); return@Page }

        Notice("Mọi trạng thái dựa trên bằng chứng quan sát được. Chưa có bằng chứng thì là UNKNOWN; app không tạo giá trị xe giả.")
        Segmented(listOf("Xe", "App", "Đối chiếu", "Cần làm", "Hồ sơ"), tab) { tab = it }
        if (tab <= 3) OutlinedTextField(q, { q = it }, label = { Text("Tìm chức năng") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        fun hit(s: String) = q.isBlank() || s.contains(q, ignoreCase = true)

        when (tab) {
            0 -> {
                LabeledSwitch("Chỉ hiện mục chưa xác minh", onlyOpen) { onlyOpen = it }
                val rows = r.vehicle.filter { hit(it.name) && (!onlyOpen || !it.verified) }
                if (rows.isEmpty()) EmptyState("Không có mục khớp")
                rows.forEach { v ->
                    LogEntryCard(v.name, buildString {
                        append(v.evidence)
                        if (v.serviceRefs.isNotEmpty()) append("\nService: ").append(v.serviceRefs.joinToString())
                        v.freshnessMs?.let { append("\nĐộ mới: ").append(it / 1000).append(" giây") }
                        v.unit?.let { append("\nĐơn vị theo adapter: ").append(it) }
                        append("\nKiểm tra lúc ").append(v.checkedAtMs?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) } ?: "—")
                        append(" · hồ sơ ").append(v.profileVersion)
                        v.lastError?.let { append("\nLỗi gần nhất: ").append(it) }
                    }) { StatusChip(v.status.name, capTone(v.status)) }
                }
            }
            1 -> {
                val rows = r.app.filter { hit(it.name) }
                if (rows.isEmpty()) EmptyState("Không có mục khớp")
                rows.forEach { a ->
                    LogEntryCard(a.name, buildString {
                        append(a.kind.label).append(" · dữ liệu: ").append(a.dataKind.label)
                        append("\nKiểm thử: ").append(a.testLevel.name).append(" — ").append(a.testScope)
                        append("\nKhông cần xe: ").append(if (a.worksWithoutVehicle) "có" else "không")
                        val deps = buildList<String> {
                            addAll(a.permissions); if (a.needsNetwork) add("mạng"); if (a.needsGps) add("GPS"); if (a.needsExternalDevice) add("thiết bị ngoài")
                        }
                        if (deps.isNotEmpty()) append("\nPhụ thuộc: ").append(deps.joinToString("; "))
                        a.note?.let { append("\nGhi chú: ").append(it) }
                    }) { StatusChip(if (a.implemented) "ĐÃ TRIỂN KHAI" else "CHƯA TRIỂN KHAI", if (a.implemented) Tone.Good else Tone.Bad) }
                }
            }
            2 -> {
                val rows = r.matches.filter { hit(it.function) }
                if (rows.isEmpty()) EmptyState("Không có mục khớp")
                rows.forEach { m ->
                    LogEntryCard(m.function, buildString {
                        append("Nguồn: ").append(m.dataSource).append("\nGiao thức: ").append(m.protocolState)
                        if (m.missing.isNotEmpty()) append("\nCòn thiếu: ").append(m.missing.joinToString("; "))
                        append("\nBước tiếp: ").append(m.nextStep)
                    }) { StatusChip(m.integration.label, integrationTone(m.integration)) }
                }
            }
            3 -> {
                val rows = r.unsupported.filter { hit(it.function) }
                Panel { Text("${r.unsupported.size} mục chưa hỗ trợ hoặc cần làm tiếp", style = MaterialTheme.typography.titleSmall) }
                if (rows.isEmpty()) EmptyState("Không có mục khớp")
                rows.forEach { m ->
                    LogEntryCard(m.function, (if (m.missing.isNotEmpty()) "Còn thiếu: ${m.missing.joinToString("; ")}\n" else "") + "Bước tiếp: ${m.nextStep}") {
                        StatusChip(m.integration.label, integrationTone(m.integration))
                    }
                }
            }
            else -> {
                SectionHeader("Adapter giao thức đã xác minh")
                if (r.registeredAdapters.isEmpty()) EmptyState("Chưa có adapter nào", "Chưa có hồ sơ giao thức nào được xác minh trên xe thật. Xem docs/PROTOCOL_EVIDENCE_TEMPLATE.md.")
                else r.registeredAdapters.forEach { Panel { Text(it) } }
                SectionHeader("Nguồn tham chiếu công khai (không phải adapter)")
                r.profiles.forEach { p ->
                    LogEntryCard(p.title, buildString {
                        append("Nguồn: ").append(p.sourceUrl).append("\nLicense: ").append(p.sourceLicense)
                        append("\nKênh: ").append(p.transport)
                        append("\nXe do nguồn nêu: ").append(p.vehiclesStatedBySource.joinToString("; "))
                        p.controllerStatedBySource?.let { append("\nBộ điều khiển: ").append(it) }
                        p.softwareVersionStatedBySource?.let { append("\nPhiên bản: ").append(it) }
                        append("\nBằng chứng: ").append(p.compatibilityEvidence)
                        append("\nSử dụng: ").append(p.usageNote).append("\nTra cứu: ").append(p.retrievedOn)
                    }) { StatusChip(p.nvxCompatibility.label, if (p.verifiedForNvx) Tone.Good else Tone.Neutral) }
                }
            }
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-capabilities-$stamp.json", "application/json", ble.capabilityJson()) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("JSON") }
            OutlinedButton(onClick = { ShareFiles.share(ctx, "nvx-capability-matrix-$stamp.csv", "text/csv", ble.capabilityCsv()) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("CSV") }
            Button(onClick = { ShareFiles.share(ctx, "nvx-capabilities-$stamp.txt", "text/plain", ble.capabilityText()) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Báo cáo") }
        }
        Text("Báo cáo xuất ra không chứa địa chỉ thiết bị, tên thiết bị hay thông tin định danh xe.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
