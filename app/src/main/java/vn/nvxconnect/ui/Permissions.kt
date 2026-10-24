package vn.nvxconnect.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import vn.nvxconnect.bluetooth.BlePermissions

object Perms {
    fun hasPreciseLocation(c: Context) = BlePermissions.granted(c, BlePermissions.FINE_LOCATION)
    fun openAppSettings(c: Context) = c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun openBluetoothSettings(c: Context) = c.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun openLocationSettings(c: Context) = c.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Trả về hàm "yêu cầu": nếu đã có quyền vị trí chính xác thì gọi onGranted ngay, nếu chưa thì hỏi.
 * Android 13+ hỏi thêm quyền thông báo (cho thông báo dịch vụ nền); từ chối thông báo không chặn việc ghi hành trình.
 */
@Composable
fun rememberLocationGate(onGranted: () -> Unit, onDenied: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val ok by rememberUpdatedState(onGranted); val no by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[BlePermissions.FINE_LOCATION] == true || Perms.hasPreciseLocation(ctx)) ok()
        else no(if (r[BlePermissions.COARSE_LOCATION] == true) "Cần quyền vị trí CHÍNH XÁC để ghi hành trình và dẫn đường. Hãy chọn \"Chính xác\" trong Cài đặt ứng dụng."
                else "Chưa cấp quyền vị trí. Bản đồ, ghi hành trình và dẫn đường cần quyền này.")
    }
    return {
        if (Perms.hasPreciseLocation(ctx)) ok()
        else launcher.launch(buildList {
            add(BlePermissions.FINE_LOCATION); add(BlePermissions.COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
        }.toTypedArray())
    }
}

/** Yêu cầu quyền BLE đúng theo phiên bản Android (xem BlePermissions). */
@Composable
fun rememberBleGate(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val ok by rememberUpdatedState(onGranted); val no by rememberUpdatedState(onDenied)
    val need = BlePermissions.forScan(Build.VERSION.SDK_INT)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (need.all { r[it] == true || BlePermissions.granted(ctx, it) }) ok() else no()
    }
    return { if (BlePermissions.missingForScan(ctx).isEmpty()) ok() else launcher.launch(need.toTypedArray()) }
}
