package vn.nvxconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.nvxconnect.pro.ProPage

private data class Tool(val page: ProPage, val title: String, val subtitle: String)

/** Menu Công cụ: mọi mục mở một màn hình có thật. */
@Composable
fun ToolsScreen(onOpen: (ProPage) -> Unit) {
    val layout = rememberLayoutInfo()
    val cols = layout.columns.coerceAtLeast(2)
    val main = listOf(
        Tool(ProPage.LAB, "Protocol Lab", "Quét BLE, GATT, gói dữ liệu, HEX, bằng chứng"),
        Tool(ProPage.DIAGNOSTICS, "Chẩn đoán", "Kết nối, quyền, nhật ký lỗi, gói hỗ trợ"),
        Tool(ProPage.SETTINGS, "Cài đặt", "Giao diện, cockpit, dữ liệu, thiết bị"))
    val more = listOf(
        Tool(ProPage.CONNECT, "Kết nối thủ công", "Quét, kết nối, nghe characteristic"),
        Tool(ProPage.AUTO, "Tự tìm và kết nối xe", "Khảo sát tự động, xếp hạng ứng viên"),
        Tool(ProPage.VEHICLE, "Xe và bảo dưỡng", "Hồ sơ xe, nhiên liệu, bảo dưỡng"),
        Tool(ProPage.CAPABILITIES, "Danh mục chức năng", "Đối chiếu chức năng xe/app"),
        Tool(ProPage.TFT, "Hiển thị TFT", "Trạng thái tích hợp màn hình xe"),
        Tool(ProPage.HELP, "Hướng dẫn", "Bài viết tiếng Việt, kiểm tra từng bước"))
    @Composable fun grid(items: List<Tool>) = items.chunked(cols).forEach { rowItems ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems.forEach { t -> ShortcutCard(t.title, t.subtitle, { onOpen(t.page) }, Modifier.weight(1f)) }
            repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Page("Công cụ", "Khảo sát, chẩn đoán và cài đặt") {
        SectionHeader("Chính"); grid(main)
        SectionHeader("Khác"); grid(more)
    }
}
