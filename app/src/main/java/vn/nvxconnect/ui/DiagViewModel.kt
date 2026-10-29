package vn.nvxconnect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nvxconnect.AppInfo
import vn.nvxconnect.NvxApp
import vn.nvxconnect.telemetry.*
import java.io.File

/**
 * Điều khiển màn hình "Dữ liệu xe chuyên sâu". Mọi I/O (liệt kê, xuất, xóa) chạy trên Dispatchers.IO.
 * Không có thao tác nào gửi dữ liệu ra mạng: xuất = ghi tệp vào cache rồi mở Android Sharesheet do NGƯỜI DÙNG chọn nơi nhận.
 */
/** Gói ZIP vừa tạo (đã kiểm tra) để hiển thị và chia sẻ lại. */
data class BundleResult(val file: File, val sessions: Int, val entries: Int)

class DiagViewModel(app: Application) : AndroidViewModel(app) {
    private val nvx = getApplication<NvxApp>()
    val logger get() = nvx.diagLogger
    val collector get() = nvx.diagCollector
    private val store get() = nvx.diagStore

    val sessions = MutableStateFlow<List<DiagSessionInfo>>(emptyList())
    val usage = MutableStateFlow<DiagUsage?>(null)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun refresh() {
        viewModelScope.launch {
            val (list, use) = withContext(Dispatchers.IO) {
                val st = logger.status.value
                val live = if (st.sessionId != null && logger.activeSessionId != null)
                    mapOf(st.sessionId to (if (st.state == DiagState.PAUSED) DiagSessionState.PAUSED else DiagSessionState.RECORDING)) else emptyMap()
                store.list(live) to store.usage()
            }
            sessions.value = list; usage.value = use
        }
    }

    fun start(label: String, intervalMs: Long) {
        val ok = logger.start(label.ifBlank { "Phiên chẩn đoán" }, intervalMs, collector.startContext())
        message.value = if (ok) null else "Không bắt đầu được: đang có phiên mở hoặc không tạo được tệp (kiểm tra bộ nhớ trống)."
        refresh()
    }
    fun pause() { logger.pause(); refresh() }
    fun resume() { logger.resume(); refresh() }
    fun stop() { logger.stop("Người dùng kết thúc"); refresh() }
    fun note(text: String): Boolean = logger.note(text).also { if (!it) message.value = "Ghi chú không được lưu (cần phiên đang mở và nội dung không rỗng)." }
    fun dismissRecovered() { logger.dismissRecovered() }

    fun continueInterrupted(id: String) {
        message.value = if (logger.continueInterrupted(id, collector.startContext())) "Đã tiếp tục phiên. Khoảng thời gian không ghi KHÔNG được lấp." else "Không tiếp tục được phiên này."
        refresh()
    }

    /** Xóa phiên: CHỈ gọi sau khi người dùng đã xác nhận. Không xóa phiên đang ghi. */
    fun delete(id: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { if (logger.activeSessionId == id) false else store.delete(id) }
            message.value = if (ok) "Đã xóa phiên $id" else "Không xóa được (phiên đang ghi hoặc không còn tệp)."
            refresh()
        }
    }

    /** Ghi tệp xuất vào cacheDir/exports rồi gọi [onReady] trên luồng chính để mở Sharesheet. */
    fun export(id: String, format: DiagFormat, opts: DiagExportOptions, onReady: (File, String) -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val data = store.read(id) ?: throw IllegalStateException("Không đọc được phiên")
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
                    val out = File(dir, DiagExporter.fileName(data.meta, format))
                    val live = logger.status.value.takeIf { it.sessionId == id && logger.activeSessionId == id }?.let { if (it.state == DiagState.PAUSED) DiagSessionState.PAUSED else DiagSessionState.RECORDING }
                    out.bufferedWriter(Charsets.UTF_8).use { DiagExporter.export(format, data, opts, System.currentTimeMillis(), it, live) }
                    out
                }
                onReady(file, format.mime)
            } catch (e: Exception) {
                message.value = "Xuất thất bại: " + (e.message ?: e.javaClass.simpleName)
            } finally { busy.value = false }
        }
    }

    // ---------------------------------------------------------------- Nhật ký kỹ thuật: xem phiên + xuất MỘT ZIP (chỉ GỌI module sẵn có; không đụng bộ thu/ghi log)
    val viewed = MutableStateFlow<DiagSummary?>(null)
    val lastBundle = MutableStateFlow<BundleResult?>(null)

    private fun liveMap(): Map<String, DiagSessionState> {
        val st = logger.status.value
        return if (st.sessionId != null && logger.activeSessionId != null)
            mapOf(st.sessionId to (if (st.state == DiagState.PAUSED) DiagSessionState.PAUSED else DiagSessionState.RECORDING)) else emptyMap()
    }

    /** Đọc phiên để xem tóm tắt (chỉ đọc; không dừng/ghi gì). */
    fun view(id: String) {
        viewModelScope.launch {
            val live = liveMap()[id]
            val sum = withContext(Dispatchers.IO) { runCatching { store.read(id)?.let { DiagSummary.compute(it, live) } }.getOrNull() }
            if (sum == null) message.value = "Không đọc được phiên $id" else viewed.value = sum
        }
    }
    fun closeView() { viewed.value = null }

    /**
     * Tạo MỘT ZIP chứa mọi phiên (kể cả phiên đang ghi, chỉ đọc), ghi vào cacheDir/exports, KIỂM TRA lại, rồi mới gọi [onReady] trên luồng chính.
     * Lỗi ở bất kỳ bước nào: xóa tệp dở, báo lỗi, KHÔNG báo thành công. Nhật ký gốc không bị sửa/xóa; phiên đang ghi vẫn tiếp tục.
     */
    fun exportAll(opts: DiagExportOptions, onReady: (File) -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { buildBundle(opts) }
                lastBundle.value = result
                onReady(result.file)
                message.value = "Đã tạo và kiểm tra ${result.file.name} (${result.sessions} phiên, ${result.entries} tệp). Chọn nơi gửi ở khung chia sẻ."
            } catch (e: Exception) {
                message.value = "Xuất thất bại: " + (e.message ?: e.javaClass.simpleName)
            } finally { busy.value = false }
        }
    }

    private fun buildBundle(opts: DiagExportOptions): BundleResult {
        val now = System.currentTimeMillis(); val live = liveMap()
        val infos = store.list(live)
        if (infos.isEmpty()) throw IllegalStateException("Chưa có phiên nào để xuất (app không tạo dữ liệu giả).")
        val sessions = infos.map { i -> BundleSession(store.read(i.meta.id) ?: throw IllegalStateException("Không đọc được phiên ${i.meta.id}"), live[i.meta.id]) }
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { now - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val name = DiagBundle.uniqueName(dir, DiagBundle.fileName(AppInfo.VERSION, now))
        val part = File(dir, "$name.part")
        try {
            part.outputStream().buffered().use { DiagBundle.write(it, sessions, BundleInfo("NVX Connect", AppInfo.VERSION, AppInfo.VERSION_CODE, AppInfo.APPLICATION_ID), opts, now) }
            val chk = DiagBundle.verify(part, sessions.map { it.data.meta.id })
            if (!chk.ok) throw IllegalStateException("ZIP không đạt kiểm tra: " + chk.problems.take(3).joinToString("; "))
            val out = File(dir, name)
            if (!part.renameTo(out)) throw IllegalStateException("Không đặt được tên tệp ZIP")
            return BundleResult(out, sessions.size, chk.entryCount)
        } finally { part.delete() }   // chỉ còn tệp dở nếu có lỗi; sau rename thì không còn gì để xóa
    }
}
