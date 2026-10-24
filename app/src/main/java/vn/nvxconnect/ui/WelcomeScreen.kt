package vn.nvxconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Màn chào: giới thiệu ngắn, không cần quyền, không quét BLE. */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NvxColors.HeroTop, NvxColors.Background, NvxColors.Background)))
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp))
        NvxWordmark()
        Text("Kết nối thông minh – Lái xe trọn vẹn", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        BikeHero()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature(Icons.Filled.Info, "Kết nối xe tự động", Modifier.weight(1f))
            Feature(Icons.Filled.Settings, "Hiển thị trên màn hình xe", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature(Icons.Filled.Place, "Bản đồ & Dẫn đường", Modifier.weight(1f))
            Feature(Icons.Filled.Build, "Quản lý thông minh", Modifier.weight(1f))
        }
        PrimaryActionButton("Bắt đầu", onStart)
        TextButton(onClick = { more = !more }) { Text(if (more) "Thu gọn" else "Tìm hiểu thêm") }
        if (more) Panel {
            Text("NVX Connect là ứng dụng độc lập, không liên kết với Yamaha.", style = MaterialTheme.typography.bodyMedium)
            Text("Bản đồ, dẫn đường, hành trình, nhiên liệu và bảo dưỡng chạy được ngay trên điện thoại. Kết nối BLE chỉ quét, kết nối và nghe; app không ghi dữ liệu vào xe. " +
                "Dữ liệu từ xe và hiển thị lên TFT chỉ có khi giao thức được xác minh trên xe thật.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun Feature(icon: ImageVector, text: String, modifier: Modifier) = Surface(modifier, shape = MaterialTheme.shapes.medium, color = NvxColors.Surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, NvxColors.Outline)) {
    Row(Modifier.padding(12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = NvxColors.Primary, modifier = Modifier.size(24.dp))
        Text(text, fontSize = 13.sp, maxLines = 2)
    }
}
