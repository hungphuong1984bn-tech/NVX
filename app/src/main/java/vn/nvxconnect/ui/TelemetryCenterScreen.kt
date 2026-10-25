package vn.nvxconnect.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.nvxconnect.NvxApp
import vn.nvxconnect.capability.VehicleCapabilityRegistry
import vn.nvxconnect.pro.*
import vn.nvxconnect.protocol.VehicleParam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SeriesMeta(val title: String, val unit: String, val sourceLabel: String, val tone: Tone, val live: Boolean)

private fun metaOf(key: String, unitOf: (String) -> String): SeriesMeta = when {
    key == "gps:speed" -> SeriesMeta("Tốc độ GPS", "km/h", "GPS điện thoại (không phải ECU)", Tone.Neutral, true)
    key.startsWith("veh:") -> {
        val p = runCatching { VehicleParam.valueOf(key.removePrefix("veh:")) }.getOrNull()
        SeriesMeta(VehicleCapabilityRegistry.defs.firstOrNull { it.param == p }?.name ?: key.removePrefix("veh:"), unitOf(key), "XE (đã xác minh)", Tone.Good, true)
    }
    key.startsWith("cand:") -> SeriesMeta(if ("rpm" in key) "RPM (giả thuyết)" else if ("batt" in key) "Điện áp (giả thuyết)" else key, unitOf(key), "GIẢ THUYẾT — chưa xác minh", Tone.Warn, true)
    key.startsWith("replay:") -> SeriesMeta("Phát lại: " + key.removePrefix("replay:"), "", "REPLAY — dữ liệu nạp từ tệp, không phải trực tiếp", Tone.Warn, false)
    else -> SeriesMeta(key, "", "Không rõ", Tone.Neutral, false)
}

private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
private fun num(v: Double?) = if (v == null) "—" else String.format(Locale.US, if (kotlin.math.abs(v) >= 100) "%.0f" else "%.2f", v)

/**
 * Telemetry Center: biểu đồ các chuỗi THẬT đã nhận. Chuỗi trống không được tạo; không nội suy: gói thiếu hiện là KHOẢNG TRỐNG (nền vàng nhạt),
 * mẫu lỗi cắt đường vẽ. Mọi chuỗi chia sẻ một trục thời gian, một con trỏ và một vùng chọn. Giả thuyết luôn gắn nhãn, không lẫn với số đã xác minh.
 */
@Composable
fun TelemetryCenterScreen(app: AppViewModel, onOpenConnect: () -> Unit) {
    val ctx = LocalContext.current
    val nvx = ctx.applicationContext as NvxApp
    val pro by nvx.proPrefs.state.collectAsState()
    val replay by app.replaySeries.collectAsState()
    val tele by nvx.telemetry.state.collectAsState()
    val cands by nvx.yccCandidates.state.collectAsState()
    val now = rememberNowEvery(pro.uiRefreshMs.toLong())

    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var windowIdx by rememberSaveable { mutableIntStateOf(1) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panBackMs by rememberSaveable { mutableLongStateOf(0L) }
    var cursor by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selA by rememberSaveable { mutableStateOf<Long?>(null) }
    var selB by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val allKeys = remember(now, replay) { (nvx.history.keys().filter { nvx.history.series(it).isNotEmpty() } + replay.keys.map { "replay:$it" }).distinct() }
    fun unitOf(k: String) = when {
        k.startsWith("veh:") -> runCatching { tele.values[VehicleParam.valueOf(k.removePrefix("veh:"))]?.unit }.getOrNull().orEmpty()
        k.startsWith("cand:") -> cands[k.removePrefix("cand:")]?.unit.orEmpty()
        else -> ""
    }
    // Chuỗi được đọc và giảm mẫu ngoài main thread; chỉ chạy lại theo chu kỳ vẽ cấu hình.
    val data by produceState(emptyMap<String, List<Sample>>(), selected, now, replay) {
        value = withContext(Dispatchers.Default) { selected.associateWith { k -> if (k.startsWith("replay:")) replay[k.removePrefix("replay:")].orEmpty() else nvx.history.series(k) } }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try { ctx.contentResolver.openInputStream(uri)?.use { ins -> ins.readBytes().takeIf { it.size <= 20 * 1024 * 1024 }?.toString(Charsets.UTF_8) } } catch (_: Exception) { null }
            if (text == null) message = "Không đọc được tệp (quá 20 MB hoặc lỗi đọc)."
            else { val r = SeriesCsv.decode(text); app.replaySeries.value = r.series
                message = if (r.series.isEmpty()) "Tệp không có chuỗi hợp lệ." else "Đã nạp ${r.series.size} chuỗi để phát lại" + if (r.warnings.isNotEmpty()) " (${r.warnings.size} cảnh báo)" else "" }
        }
    }

    Page(null) {
        AppTopBar("Telemetry Center", "Biểu đồ dữ liệu thật · không nội suy")
        message?.let { Notice(it, Tone.Neutral, "Đóng") { message = null } }
        if (allKeys.isEmpty()) {
            StateBanner(UiState.Empty, "Chưa có chuỗi dữ liệu nào",
                "Biểu đồ chỉ vẽ mẫu đã nhận thật: tốc độ GPS (khi đang nghe GPS), giá trị xe đã xác minh, hoặc giá trị giả thuyết từ kênh YCC. App không tạo dữ liệu mẫu.",
                "Kết nối xe", onOpenConnect)
        } else {
            Section("Chọn thông số")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                allKeys.forEach { k -> NvxChip(metaOf(k, ::unitOf).title, k in selected) { selected = if (k in selected) selected - k else selected + k } }
            }
            Text("Chạm để bật/tắt nhiều thông số (đang chọn ${selected.size}).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Section("Khoảng thời gian")
            ChipRow(ChartMath.WINDOWS_MS.map { ChartMath.windowLabel(it) }, windowIdx) { windowIdx = it; zoom = 1f; panBackMs = 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { selectMode = !selectMode; selA = null; selB = null }, Modifier.heightIn(min = 48.dp)) { Text(if (selectMode) "Đang chọn vùng" else "Chọn vùng") }
                OutlinedButton(onClick = { zoom = 1f; panBackMs = 0; cursor = null; selA = null; selB = null }, Modifier.heightIn(min = 48.dp)) { Text("Đặt lại") }
            }
            Text(if (selectMode) "Kéo trên biểu đồ để chọn vùng dữ liệu." else "Chạm: xem giá trị. Hai ngón: thu phóng. Kéo: cuộn theo thời gian.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (selected.isEmpty()) StateBanner(UiState.Empty, "Chưa chọn thông số", "Chọn ít nhất một thông số ở trên để xem biểu đồ.")
            val sel = selected.filter { data[it] != null }
            val baseEnd = sel.mapNotNull { data[it]?.lastOrNull()?.timeMs }.maxOrNull()
            if (baseEnd != null) {
                val w = ChartMath.WINDOWS_MS[windowIdx]
                val baseStart = if (w == 0L) sel.mapNotNull { data[it]?.firstOrNull()?.timeMs }.min() else baseEnd - w
                val fullSpan = (baseEnd - baseStart).coerceAtLeast(1000L)
                val span = (fullSpan / zoom).toLong().coerceAtLeast(500L)
                val back = panBackMs.coerceIn(0L, (fullSpan - span).coerceAtLeast(0L))
                val dEnd = baseEnd - back; val dStart = dEnd - span
                sel.forEach { key ->
                    val meta = metaOf(key, ::unitOf); val all = data[key].orEmpty()
                    val visible = ChartMath.window(all, dStart, dEnd)
                    val st = ChartMath.stats(visible); val last = all.lastOrNull()
                    val (gapCount, gapMs) = ChartMath.gaps(visible, 3000L)
                    val range = if (selA != null && selB != null) ChartMath.selectionStats(all, selA!!, selB!!) else null
                    Panel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(meta.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            StatusChip(meta.sourceLabel, meta.tone)
                        }
                        val age = last?.let { now - it.timeMs }
                        val isStale = meta.live && (age ?: 0) > 5000
                        Text(if (last == null) "Chưa có mẫu" else if (isStale) "Giá trị cuối ${num(last.value)} ${meta.unit} — cũ ${(age ?: 0) / 1000} giây, không phải số hiện tại" else "Hiện tại ${num(last.value)} ${meta.unit}",
                            style = MaterialTheme.typography.titleMedium, color = if (isStale) NvxColors.Warning else MaterialTheme.colorScheme.onSurface)
                        Text("Min ${num(st.min)} · Max ${num(st.max)} · TB ${num(st.mean)} ${meta.unit} · ${st.count} mẫu · cập nhật ${last?.let { clock(it.timeMs) } ?: "—"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TimeChart(visible, dStart, dEnd, cursor, selA, selB, selectMode, if (meta.tone == Tone.Good) NvxColors.Success else if (meta.tone == Tone.Warn) NvxColors.Warning else NvxColors.Primary, meta.title,
                            onCursor = { cursor = it }, onSelect = { a, b -> selA = a; selB = b },
                            onTransform = { z, panFrac -> val nz = (zoom * z).coerceIn(1f, 30f); zoom = nz; panBackMs = (back + (panFrac * span).toLong()).coerceAtLeast(0L) })
                        val c = cursor
                        if (c != null) { val n = ChartMath.nearest(visible, c, 1500L)
                            Text(if (n != null) "${clock(n.timeMs)} · ${num(n.value)} ${meta.unit}" else "${clock(c)} · không có mẫu tại thời điểm này (khoảng trống)", style = MaterialTheme.typography.bodySmall) }
                        if (gapCount > 0) Text("$gapCount khoảng trống, tổng ${gapMs / 1000} giây không có gói (không nội suy).", style = MaterialTheme.typography.labelSmall, color = NvxColors.Warning)
                        if (range != null && range.count > 0) Text("Vùng chọn ${clock(range.firstMs!!)}–${clock(range.lastMs!!)}: ${range.count} mẫu · min ${num(range.min)} · max ${num(range.max)} · TB ${num(range.mean)}", style = MaterialTheme.typography.bodySmall, color = NvxColors.Primary)
                        if (!key.startsWith("replay:")) { val d = nvx.history.dropped(key); val sk = nvx.history.skipped(key)
                            if (d > 0 || sk > 0) Text("Lịch sử giới hạn 3.600 mẫu/chuỗi: đã bỏ $d mẫu cũ; $sk mẫu đến quá dày bị bỏ để tiết kiệm.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        Panel {
            Section("Phiên và phát lại")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val m = nvx.history.keys().associateWith { nvx.history.series(it) }.filterValues { it.isNotEmpty() }
                    if (m.isEmpty()) message = "Chưa có dữ liệu để lưu."
                    else ShareFiles.share(ctx, "nvx-telemetry-series-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv", "text/csv", SeriesCsv.encode(m))
                }, Modifier.heightIn(min = 48.dp)) { Text("Lưu phiên (CSV)") }
                OutlinedButton(onClick = { pick.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }, Modifier.heightIn(min = 48.dp)) { Text("Phát lại từ CSV") }
            }
            if (replay.isNotEmpty()) TextButton(onClick = { app.replaySeries.value = emptyMap(); selected = selected.filterNot { it.startsWith("replay:") } }) { Text("Gỡ dữ liệu phát lại") }
            OutlinedButton(onClick = { confirmClear = true }, Modifier.heightIn(min = 48.dp)) { Text("Xóa lịch sử biểu đồ") }
            Text("Phát lại dùng tệp CSV do Telemetry Center xuất. Log payload thô (JSON) nạp ở Công cụ → Protocol Lab → Evidence & Verification.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmClear) ConfirmDialog("Xóa lịch sử biểu đồ?", "Toàn bộ mẫu đang giữ trong bộ nhớ sẽ bị xóa. Không ảnh hưởng hành trình hay log payload.", "Xóa", { confirmClear = false; nvx.history.clear(); selected = emptyList() }, { confirmClear = false })
}

/** Biểu đồ đường theo đoạn liên tục. Khoảng trống được tô nền, không nối. Con trỏ và vùng chọn dùng chung theo thời gian. */
@Composable
private fun TimeChart(samples: List<Sample>, startMs: Long, endMs: Long, cursor: Long?, selA: Long?, selB: Long?, selectMode: Boolean, line: Color, name: String,
                      onCursor: (Long?) -> Unit, onSelect: (Long?, Long?) -> Unit, onTransform: (Float, Float) -> Unit) {
    val grid = NvxColors.OutlineSoft; val gapColor = NvxColors.Warning.copy(alpha = 0.14f); val cur = NvxColors.TextPrimary; val selColor = NvxColors.Primary.copy(alpha = 0.18f)
    val span = (endMs - startMs).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = "Biểu đồ $name, ${samples.size} mẫu" }
        .pointerInput(startMs, endMs) { detectTapGestures { o -> onCursor(startMs + (o.x / size.width * span).toLong()) } }
        .pointerInput(selectMode, startMs, endMs) {
            if (selectMode) {
                var a = 0L
                detectDragGestures(onDragStart = { o -> a = startMs + (o.x / size.width * span).toLong(); onSelect(a, a) },
                    onDrag = { ch, _ -> onSelect(a, startMs + (ch.position.x.coerceIn(0f, size.width.toFloat()) / size.width * span).toLong()) })
            } else detectTransformGestures { _, pan, z, _ -> onTransform(z, pan.x / size.width) }
        }) {
        fun x(t: Long) = (t - startMs).toFloat() / span * size.width
        for (i in 0..3) drawLine(grid, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
        if (samples.isEmpty()) return@Canvas
        val lo0 = samples.minOf { it.value }; val hi0 = samples.maxOf { it.value }
        val pad = if (hi0 - lo0 < 1e-9) 1.0 else (hi0 - lo0) * 0.1
        val lo = lo0 - pad; val hi = hi0 + pad
        fun y(v: Double) = (size.height - ((v - lo) / (hi - lo)).toFloat() * size.height)
        if (selA != null && selB != null) { val l = x(minOf(selA, selB)).coerceIn(0f, size.width); val r = x(maxOf(selA, selB)).coerceIn(0f, size.width); drawRect(selColor, Offset(l, 0f), androidx.compose.ui.geometry.Size(r - l, size.height)) }
        val segs = ChartMath.segments(samples, 3000L)
        for (i in 1 until segs.size) { val l = x(segs[i - 1].samples.last().timeMs); val r = x(segs[i].samples.first().timeMs); drawRect(gapColor, Offset(l, 0f), androidx.compose.ui.geometry.Size((r - l).coerceAtLeast(1f), size.height)) }
        for (sg in segs) {
            val pts = ChartMath.downsample(sg.samples, (size.width / 2).toInt().coerceAtLeast(8))
            if (pts.size == 1) drawCircle(line, 3.dp.toPx(), Offset(x(pts[0].timeMs), y(pts[0].value)))
            else { val path = Path(); pts.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.timeMs), y(s.value)) else path.lineTo(x(s.timeMs), y(s.value)) }; drawPath(path, line, style = Stroke(2.dp.toPx())) }
        }
        if (cursor != null && cursor in startMs..endMs) drawLine(cur, Offset(x(cursor), 0f), Offset(x(cursor), size.height), 1.5f)
    }
}
