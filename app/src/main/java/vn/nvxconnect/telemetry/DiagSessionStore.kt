package vn.nvxconnect.telemetry

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Kết quả ghi một bản ghi. Mọi bản ghi bị loại đều có bộ đếm: không có đường bỏ im lặng. */
enum class DiagAppend { OK, DROPPED_LIMIT, DROPPED_LOW_STORAGE, DROPPED_ERROR }

enum class DiagHealth(val title: String) {
    OK("Bình thường"), LIMIT_REACHED("Đã chạm giới hạn dung lượng — mẫu mới bị loại"),
    LOW_STORAGE("Bộ nhớ trống quá thấp — mẫu mới bị loại"), WRITE_ERROR("Lỗi ghi tệp")
}

data class DiagCounters(val written: Long = 0, val droppedLimit: Long = 0, val droppedLowStorage: Long = 0, val droppedError: Long = 0, val lastError: String? = null) {
    val dropped: Long get() = droppedLimit + droppedLowStorage + droppedError
}

data class DiagSessionInfo(
    val meta: DiagMeta, val file: File, val state: DiagSessionState, val sizeBytes: Long,
    /** Thời điểm bản ghi cuối cùng (giờ hệ thống); null nếu tệp không có bản ghi nào. */
    val lastRecordWallMs: Long?
) { val id: String get() = meta.id }

data class DiagRecovery(val sessionId: String, val records: Long, val corruptLines: Int, val tornTailBytes: Int, val lastRecordWallMs: Long?)

data class DiagUsage(val sessions: Int, val totalBytes: Long, val maxTotalBytes: Long, val freeBytes: Long?)

/**
 * Kho nhật ký phiên chẩn đoán: MỖI PHIÊN MỘT TỆP JSON Lines (`diag-<id>.nvxdiag`), mỗi dòng có CRC32.
 *  - Ghi nối tiếp (append) thẳng xuống tệp → tiến trình bị tắt đột ngột chỉ có thể làm hỏng DÒNG CUỐI; mọi dòng trước đó còn nguyên.
 *  - Giới hạn dung lượng theo phiên và theo tổng; khi chạm giới hạn thì LOẠI MẪU MỚI (có đếm) chứ KHÔNG BAO GIỜ tự xóa phiên cũ.
 *    Phần dự trữ [reserveBytes] chỉ dành cho bản ghi quan trọng (kết thúc phiên, lỗi, ghi chú) để phiên luôn đóng được đúng cách.
 *  - Chỉ [delete] (do người dùng xác nhận ở giao diện) mới xóa dữ liệu.
 */
class DiagSessionStore(
    val dir: File,
    val maxSessionBytes: Long = 16L * 1024 * 1024,
    val maxTotalBytes: Long = 128L * 1024 * 1024,
    private val minFreeBytes: Long = 32L * 1024 * 1024,
    private val reserveBytes: Long = 64L * 1024,
    private val freeSpace: () -> Long = { dir.usableSpace },
    private val syncEveryMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init { dir.mkdirs() }

    private fun fileOf(id: String) = File(dir, "diag-$id.nvxdiag")
    private fun files(): List<File> = (dir.listFiles { f -> f.isFile && f.name.matches(NAME) } ?: emptyArray()).sortedBy { it.name }

    /** Mã phiên hợp lệ làm tên tệp: chỉ chữ/số/gạch ngang. */
    private fun safeId(id: String) = require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Mã phiên không hợp lệ" }

    /** Tạo tệp phiên mới và ghi dòng meta. Ném lỗi nếu mã đã tồn tại (không ghi đè phiên cũ). */
    @Synchronized fun create(meta: DiagMeta): DiagWriter {
        safeId(meta.id)
        val f = fileOf(meta.id)
        require(!f.exists()) { "Phiên ${meta.id} đã tồn tại" }
        return DiagWriter(f, meta, nextSeq = 1L, initialBytes = 0L, store = this, writeMeta = true)
    }

    /** Mở lại một phiên đã khôi phục để GHI TIẾP (chỉ khi người dùng chọn Tiếp tục). Trả null nếu tệp không tồn tại/hỏng. */
    @Synchronized fun reopen(id: String): DiagWriter? {
        safeId(id)
        val f = fileOf(id); if (!f.exists()) return null
        val data = readFile(f) ?: return null
        repairTail(f)
        return DiagWriter(f, data.meta, nextSeq = (data.records.maxOfOrNull { it.seq } ?: 0L) + 1, initialBytes = f.length(), store = this, writeMeta = false)
    }

    /** Dung lượng đang dùng bởi MỌI phiên khác (dùng để áp giới hạn tổng). */
    internal fun othersBytes(except: File): Long = files().filter { it != except }.sumOf { it.length() }
    internal fun freeNow(): Long = freeSpace()
    internal val minFree get() = minFreeBytes
    internal val reserve get() = reserveBytes
    internal val syncEvery get() = syncEveryMs
    internal fun now() = clock()

    // ------------------------------------------------------------------------------------------ đọc
    /** Đọc cả phiên. null nếu tệp không có dòng meta hợp lệ. Dòng hỏng được đếm, không bị bỏ qua âm thầm. */
    fun read(id: String): DiagSessionData? { safeId(id); val f = fileOf(id); return if (f.exists()) readFile(f) else null }

    private fun readFile(f: File): DiagSessionData? {
        val bytes = f.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        val torn = bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()
        var meta: DiagMeta? = null; val recs = ArrayList<DiagRecord>(); var bad = 0
        for (l in text.split('\n')) {
            if (l.isBlank()) continue
            when (val d = DiagCodec.decode(l)) {
                is DiagCodec.Line.Meta -> if (meta == null) meta = d.meta else bad++
                is DiagCodec.Line.Rec -> recs += d.rec
                null -> bad++
            }
        }
        val m = meta ?: return null
        return DiagSessionData(m, recs, bad, torn)
    }

    /**
     * Danh sách phiên, mới nhất trước. [activeIds] = phiên đang được CHÍNH tiến trình này ghi (trạng thái lấy từ [liveState]);
     * phiên khác mà chưa có bản ghi kết thúc/khôi phục được báo UNFINISHED (cần [recoverInterrupted]).
     */
    fun list(activeIds: Map<String, DiagSessionState> = emptyMap()): List<DiagSessionInfo> = files().mapNotNull { f ->
        val head = readMeta(f) ?: return@mapNotNull null
        val last = lastControl(f)
        val state = activeIds[head.id] ?: when (last?.name) {
            DiagControl.STOP -> DiagSessionState.STOPPED
            DiagControl.RECOVERED -> DiagSessionState.INTERRUPTED
            else -> DiagSessionState.UNFINISHED
        }
        DiagSessionInfo(head, f, state, f.length(), lastRecordWall(f))
    }.sortedByDescending { it.meta.startedWallMs }

    private fun readMeta(f: File): DiagMeta? = try {
        f.bufferedReader(Charsets.UTF_8).use { r -> (DiagCodec.decode(r.readLine() ?: return null) as? DiagCodec.Line.Meta)?.meta }
    } catch (_: Exception) { null }

    /** Đọc ngược từng khối từ cuối tệp tới khi gặp bản ghi SESSION cuối cùng (không đọc cả tệp lớn). */
    private fun lastControl(f: File): DiagRecord? = scanBackwards(f) { it.kind == DiagKind.SESSION }
    private fun lastRecordWall(f: File): Long? = scanBackwards(f) { true }?.wallMs

    private fun scanBackwards(f: File, pick: (DiagRecord) -> Boolean): DiagRecord? = try {
        RandomAccessFile(f, "r").use { raf ->
            var end = raf.length(); val chunk = 64 * 1024L; var carry = ByteArray(0); var found: DiagRecord? = null
            while (end > 0 && found == null) {
                val start = maxOf(0L, end - chunk); val buf = ByteArray((end - start).toInt()); raf.seek(start); raf.readFully(buf)
                val data = buf + carry
                // Làm việc trên BYTE (không giải mã cả khối) để một ký tự UTF-8 bị cắt ở ranh giới khối không làm hỏng dòng.
                val nl = ArrayList<Int>(); for (i in data.indices) if (data[i] == NL) nl += i
                // các dòng đầy đủ: nằm giữa hai '\n' liên tiếp, cộng đoạn cuối (sau '\n' cuối cùng) và — nếu tới đầu tệp — đoạn đầu
                val bounds = ArrayList<IntRange>()
                var lineStart = 0
                for (i in nl) { bounds += lineStart until i; lineStart = i + 1 }
                bounds += lineStart until data.size
                val firstComplete = if (start > 0) 1 else 0           // đoạn đầu của khối có thể là dòng bị cắt: chuyển sang khối trước
                carry = if (start > 0 && bounds.isNotEmpty()) data.copyOfRange(bounds[0].first, bounds[0].last + 1) else ByteArray(0)
                for (bi in bounds.indices.reversed()) {
                    if (bi < firstComplete) break
                    val r = bounds[bi]; if (r.isEmpty()) continue
                    val d = DiagCodec.decode(String(data, r.first, r.last - r.first + 1, Charsets.UTF_8))
                    if (d is DiagCodec.Line.Rec && pick(d.rec)) { found = d.rec; break }
                }
                end = start
            }
            found
        }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------------------------------ khôi phục
    /** Cắt dòng ghi dở ở cuối tệp (byte cuối không phải '\n'). Trả số byte đã cắt. */
    private fun repairTail(f: File): Int {
        val data = f.readBytes()
        if (data.isEmpty() || data.last() == '\n'.code.toByte()) return 0
        val cut = data.lastIndexOf('\n'.code.toByte()) + 1
        f.writeBytes(data.copyOfRange(0, cut)); return data.size - cut
    }

    /**
     * Gọi MỘT lần khi khởi động (trước khi bắt đầu phiên mới). Với mọi phiên không do tiến trình này đang ghi và chưa có STOP/RECOVERED:
     * cắt dòng ghi dở, rồi ghi bản ghi SESSION/RECOVERED. Mọi bản ghi tới thời điểm cuối cùng vẫn còn nguyên, không bị xóa/sửa.
     */
    @Synchronized fun recoverInterrupted(skipIds: Set<String> = emptySet()): List<DiagRecovery> {
        val out = ArrayList<DiagRecovery>()
        for (f in files()) {
            val meta = readMeta(f) ?: continue
            if (meta.id in skipIds) continue
            val last = lastControl(f)
            if (last?.name == DiagControl.STOP || last?.name == DiagControl.RECOVERED) continue
            val torn = repairTail(f)
            val data = readFile(f) ?: continue
            val lastRec = data.records.lastOrNull()
            val rec = DiagRecord(
                seq = (lastRec?.seq ?: 0L) + 1, wallMs = clock(), monoMs = lastRec?.monoMs ?: 0L, kind = DiagKind.SESSION, name = DiagControl.RECOVERED,
                text = "Ứng dụng bị đóng đột ngột hoặc tiến trình bị hệ thống thu hồi. Dữ liệu tới bản ghi cuối vẫn còn.",
                attrs = mapOf("last_record_wall_ms" to (lastRec?.wallMs?.toString() ?: ""), "records" to data.records.size.toString(),
                    "corrupt_lines" to data.corruptLines.toString(), "torn_tail_bytes" to torn.toString(), "previous_state" to (last?.name ?: "NONE")))
            FileOutputStream(f, true).use { it.write((DiagCodec.encode(rec) + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync() }
            out += DiagRecovery(meta.id, data.records.size.toLong(), data.corruptLines, torn, lastRec?.wallMs)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ xóa/thống kê
    /** CHỈ gọi khi người dùng đã xác nhận. Không có đường xóa tự động nào trong kho này. */
    @Synchronized fun delete(id: String): Boolean { safeId(id); return fileOf(id).delete() }

    fun usage(): DiagUsage { val fs = files(); return DiagUsage(fs.size, fs.sumOf { it.length() }, maxTotalBytes, runCatching { freeSpace() }.getOrNull()) }

    fun fileFor(id: String): File { safeId(id); return fileOf(id) }

    companion object { private val NAME = Regex("diag-[A-Za-z0-9-]{1,64}\\.nvxdiag"); private const val NL = '\n'.code.toByte() }
}

/** Ghi một phiên. Mọi phương thức an toàn đa luồng. Không chặn luồng giao diện nếu gọi ngoài main (không có I/O mạng, chỉ append tệp cục bộ). */
class DiagWriter internal constructor(
    private val file: File, val meta: DiagMeta, nextSeq: Long, initialBytes: Long, private val store: DiagSessionStore, writeMeta: Boolean
) {
    private var out: FileOutputStream? = null
    private var seq = nextSeq
    private var sessionBytes = initialBytes
    private var lastSyncAt = Long.MIN_VALUE
    private var limitAnnounced = false
    private var lastMono = 0L
    @Volatile var health: DiagHealth = DiagHealth.OK; private set
    private var written = 0L; private var dLimit = 0L; private var dLow = 0L; private var dErr = 0L; private var lastError: String? = null
    private val baseOthers: Long = store.othersBytes(file)

    init {
        try { out = FileOutputStream(file, true) } catch (e: Exception) { fail(e) }
        if (writeMeta) {
            val line = (DiagCodec.encodeMeta(meta) + "\n").toByteArray(Charsets.UTF_8)
            rawWrite(line, sync = true)
        }
    }

    val counters: DiagCounters @Synchronized get() = DiagCounters(written, dLimit, dLow, dErr, lastError)
    @Synchronized fun nextSeq(): Long = seq
    val bytes: Long @Synchronized get() = sessionBytes

    private fun fail(e: Exception) { health = DiagHealth.WRITE_ERROR; lastError = e.message ?: e.javaClass.simpleName; try { out?.close() } catch (_: Exception) {}; out = null }

    private fun rawWrite(b: ByteArray, sync: Boolean): Boolean {
        val o = out ?: run { try { out = FileOutputStream(file, true) } catch (e: Exception) { fail(e); return false }; out!! }
        return try {
            o.write(b); sessionBytes += b.size
            val now = store.now()
            if (sync || lastSyncAt == Long.MIN_VALUE || now - lastSyncAt >= store.syncEvery || now < lastSyncAt) { o.fd.sync(); lastSyncAt = now }
            true
        } catch (e: Exception) { fail(e); false }
    }

    /**
     * Ghi một bản ghi; [DiagRecord.seq] do writer cấp. Quy tắc:
     *  - thiếu bộ nhớ trống (< minFree): loại mẫu thường; bản ghi quan trọng vẫn ghi nếu còn ≥ 1 MB.
     *  - chạm giới hạn phiên/tổng: loại mẫu thường; bản ghi quan trọng dùng phần dự trữ.
     */
    @Synchronized fun append(r: DiagRecord): DiagAppend {
        val stamped = r.copy(seq = seq)
        val line = (DiagCodec.encode(stamped) + "\n").toByteArray(Charsets.UTF_8)
        val critical = r.critical
        val free = runCatching { store.freeNow() }.getOrDefault(Long.MAX_VALUE)
        val lowBar = if (critical) 1L * 1024 * 1024 else store.minFree
        if (free < lowBar) { dLow++; health = DiagHealth.LOW_STORAGE; announceLimit("Bộ nhớ trống thấp"); return DiagAppend.DROPPED_LOW_STORAGE }
        // Bản ghi thường chỉ được dùng tới (giới hạn − dự trữ); bản ghi quan trọng được dùng cả phần dự trữ.
        val limS = if (critical) store.maxSessionBytes else store.maxSessionBytes - store.reserve
        val limT = if (critical) store.maxTotalBytes else store.maxTotalBytes - store.reserve
        val overSession = sessionBytes + line.size > limS
        if (overSession || baseOthers + sessionBytes + line.size > limT) {
            dLimit++; health = DiagHealth.LIMIT_REACHED
            announceLimit(if (overSession) "Phiên đạt ${store.maxSessionBytes / 1024} KB" else "Tổng dung lượng nhật ký đạt ${store.maxTotalBytes / 1024} KB")
            return DiagAppend.DROPPED_LIMIT
        }
        if (!rawWrite(line, sync = critical)) { dErr++; return DiagAppend.DROPPED_ERROR }
        seq++; written++; if (r.monoMs != 0L) lastMono = r.monoMs
        if (health == DiagHealth.WRITE_ERROR) health = DiagHealth.OK
        return DiagAppend.OK
    }

    private fun announceLimit(why: String) {
        if (limitAnnounced) return
        limitAnnounced = true
        val rec = DiagRecord(seq, store.now(), lastMono, DiagKind.SESSION, DiagControl.LIMIT, text = why + ". Từ đây mẫu thường bị loại (có đếm); nhật ký cũ KHÔNG bị xóa. Hãy xuất và xóa thủ công phiên cũ.")
        val line = (DiagCodec.encode(rec) + "\n").toByteArray(Charsets.UTF_8)
        if (rawWrite(line, sync = true)) { seq++; written++ }
    }

    @Synchronized fun flush() { try { out?.fd?.sync() } catch (e: Exception) { fail(e) } }

    @Synchronized fun close() { try { out?.fd?.sync() } catch (_: Exception) {}; try { out?.close() } catch (_: Exception) {}; out = null }
}
