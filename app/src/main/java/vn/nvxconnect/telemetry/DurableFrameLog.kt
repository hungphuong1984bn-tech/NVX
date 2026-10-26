package vn.nvxconnect.telemetry

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.zip.CRC32

class StorageLowException(message: String) : IOException(message)

/**
 * Định dạng một dòng (ASCII, kết thúc '\n'):
 *   V1,<crc32 hex 8>,<seq>,<session>,<epoch>,<wallMs>,<monoNs>,<charUuid>,<serviceUuid|->,<source>,<rssi|->,<connState>,<HEX liền nhau>
 * CRC32 tính trên phần sau "V1,<crc>," → dòng ghi dở do tiến trình bị tắt giữa chừng sẽ bị phát hiện, không bị đọc nhầm thành dữ liệu.
 */
object CaptureCodec {
    private val HEXCHARS = "0123456789ABCDEF".toCharArray()
    fun hexCompact(b: ByteArray): String { val c = CharArray(b.size * 2); for (i in b.indices) { val v = b[i].toInt() and 0xFF; c[i * 2] = HEXCHARS[v ushr 4]; c[i * 2 + 1] = HEXCHARS[v and 15] }; return String(c) }
    fun fromHexCompact(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) { val h = Character.digit(s[i * 2], 16); val l = Character.digit(s[i * 2 + 1], 16); if (h < 0 || l < 0) return null; out[i] = ((h shl 4) or l).toByte() }
        return out
    }
    private fun crc(body: String): String = "%08x".format(CRC32().also { it.update(body.toByteArray(Charsets.US_ASCII)) }.value)

    fun encode(f: CapturedFrame): String {
        val body = "${f.seq},${f.sessionId},${f.epoch},${f.wallMs},${f.monoNs},${f.charUuid.lowercase()},${f.serviceUuid?.lowercase() ?: "-"},${f.source.name},${f.rssi?.toString() ?: "-"},${f.connState},${f.hex}"
        return "V1,${crc(body)},$body"
    }

    /** null nếu dòng hỏng (sai CRC, thiếu trường, số không hợp lệ). */
    fun decode(line: String): CapturedFrame? {
        if (!line.startsWith("V1,")) return null
        val second = line.indexOf(',', 3); if (second < 0) return null
        val body = line.substring(second + 1)
        if (crc(body) != line.substring(3, second)) return null
        val p = body.split(',', limit = 11); if (p.size != 11) return null
        return try {
            CapturedFrame(
                seq = p[0].toLong(), sessionId = p[1], epoch = p[2].toInt(), wallMs = p[3].toLong(), monoNs = p[4].toLong(),
                charUuid = p[5], serviceUuid = p[6].takeIf { it != "-" }, source = PayloadSource.valueOf(p[7]),
                rssi = p[8].takeIf { it != "-" }?.toInt(), connState = p[9], bytes = fromHexCompact(p[10]) ?: return null)
        } catch (e: IllegalArgumentException) { null }
    }
}

/**
 * Nhật ký gói bền vững: tệp nối thêm (append-only) chia đoạn, có xoay vòng + giới hạn tổng dung lượng.
 *  - Mỗi lô được flush + fsync: tiến trình/ứng dụng bị tắt không làm mất các lô đã ghi xong. Gói CHƯA nhận từ xe thì tất nhiên không khôi phục được.
 *  - Khi mở lại, dòng cuối ghi dở được cắt bỏ để các lần ghi sau không dính vào dòng hỏng.
 *  - Kiểm tra dung lượng trống trước mỗi lô; lỗi I/O → đóng tệp, lần ghi sau mở lại và sửa đuôi (phục hồi sau lỗi ghi).
 *  - Đọc lại tự loại bản trùng (cùng phiên, số thứ tự không tăng) nhưng KHÔNG loại các gói có nội dung giống nhau mà số thứ tự khác nhau.
 *  - Xoay vòng: xóa đoạn cũ nhất khi vượt [maxTotalBytes]; số đoạn bị xóa được báo ([evictedSegments]), không xóa im lặng.
 */
class DurableFrameLog(
    private val dir: File,
    private val segmentMaxBytes: Long = 4L * 1024 * 1024,
    private val maxTotalBytes: Long = 256L * 1024 * 1024,
    private val minFreeBytes: Long = 64L * 1024 * 1024,
    private val freeSpace: () -> Long = { dir.usableSpace },
    private val syncEachBatch: Boolean = true
) {
    data class ReadReport(val frames: Int, val duplicates: Int, val corruptLines: Int, val segments: Int)

    private var out: FileOutputStream? = null
    private var current: File? = null
    private var curSize = 0L
    private var needRepair = true
    @Volatile var evictedSegments = 0L; private set
    @Volatile var evictedBytes = 0L; private set
    @Volatile var repairedTails = 0L; private set
    @Volatile var lastError: String? = null; private set

    init { dir.mkdirs() }

    private fun segments(): List<File> = (dir.listFiles { f -> f.isFile && f.name.startsWith("seg-") && f.name.endsWith(".nvxlog") } ?: emptyArray()).sortedBy { it.name }
    private fun indexOf(f: File) = f.name.removePrefix("seg-").removeSuffix(".nvxlog").toIntOrNull() ?: 0
    private fun segFile(i: Int) = File(dir, "seg-%08d.nvxlog".format(i))

    @Synchronized fun totalBytes(): Long = segments().sumOf { it.length() }
    @Synchronized fun segmentCount(): Int = segments().size

    /** Ghi một lô. Ném IOException (hoặc [StorageLowException]) nếu thất bại; gọi lại sau là an toàn. */
    @Synchronized fun append(batch: List<CapturedFrame>) {
        if (batch.isEmpty()) return
        val free = freeSpace()
        if (free < minFreeBytes) { val m = "Bộ nhớ trống còn ${free / 1_048_576} MB (cần ≥ ${minFreeBytes / 1_048_576} MB)"; lastError = m; throw StorageLowException(m) }
        val sb = StringBuilder(batch.size * 140)
        for (f in batch) sb.append(CaptureCodec.encode(f)).append('\n')
        val bytes = sb.toString().toByteArray(Charsets.US_ASCII)
        try {
            ensureOpen()
            if (curSize > 0 && curSize + bytes.size > segmentMaxBytes) rotate()
            val o = out!!
            o.write(bytes); o.flush(); if (syncEachBatch) o.fd.sync()
            curSize += bytes.size
            lastError = null
        } catch (e: IOException) {
            lastError = e.message ?: e.javaClass.simpleName
            closeQuietly(); needRepair = true
            throw e
        }
    }

    private fun ensureOpen() {
        if (out != null) return
        val segs = segments()
        val f = segs.lastOrNull() ?: segFile(1)
        if (needRepair && f.exists() && repairTail(f)) repairedTails++
        needRepair = false
        out = FileOutputStream(f, true); current = f; curSize = f.length()
    }

    private fun rotate() {
        val idx = current?.let { indexOf(it) } ?: 0
        closeQuietly()
        val f = segFile(idx + 1)
        out = FileOutputStream(f, true); current = f; curSize = f.length()
        enforceRetention()
    }

    private fun enforceRetention() {
        val segs = segments().toMutableList()
        var total = segs.sumOf { it.length() }
        while (total > maxTotalBytes && segs.size > 1) {
            val old = segs.removeAt(0)
            if (old == current) break
            val len = old.length()
            if (old.delete()) { total -= len; evictedSegments++; evictedBytes += len } else break
        }
    }

    /** Cắt phần đuôi sau '\n' cuối cùng nếu tệp không kết thúc bằng '\n'. true nếu đã cắt. */
    private fun repairTail(f: File): Boolean {
        RandomAccessFile(f, "rw").use { raf ->
            val len = raf.length(); if (len == 0L) return false
            raf.seek(len - 1); if (raf.read() == '\n'.code) return false
            var pos = len - 1; val buf = ByteArray(8192)
            while (pos > 0) {
                val n = minOf(buf.size.toLong(), pos).toInt(); val start = pos - n
                raf.seek(start); raf.readFully(buf, 0, n)
                for (i in n - 1 downTo 0) if (buf[i] == '\n'.code.toByte()) { raf.setLength(start + i + 1); return true }
                pos = start
            }
            raf.setLength(0); return true
        }
    }

    private fun closeQuietly() { try { out?.close() } catch (_: IOException) {}; out = null }
    @Synchronized fun close() { closeQuietly() }

    /** Đọc tuần tự toàn bộ (không nạp hết vào bộ nhớ). Không ném lỗi khi gặp dòng hỏng: chỉ đếm. */
    @Synchronized fun readAll(visit: (CapturedFrame) -> Unit): ReadReport {
        try { out?.flush() } catch (_: IOException) {}
        var frames = 0; var dup = 0; var bad = 0
        val lastSeq = HashMap<String, Long>()
        val segs = segments()
        for (s in segs) {
            BufferedReader(InputStreamReader(FileInputStream(s), Charsets.US_ASCII), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty()) continue
                    val f = CaptureCodec.decode(line)
                    if (f == null) { bad++; continue }
                    val prev = lastSeq[f.sessionId]
                    if (prev != null && f.seq <= prev) { dup++; continue }
                    lastSeq[f.sessionId] = f.seq
                    frames++; visit(f)
                }
            }
        }
        return ReadReport(frames, dup, bad, segs.size)
    }

    fun readList(limit: Int = Int.MAX_VALUE): Pair<List<CapturedFrame>, ReadReport> {
        val l = ArrayList<CapturedFrame>(); val r = readAll { if (l.size < limit) l += it }; return l to r
    }

    /** Xóa toàn bộ dữ liệu thu thập (người dùng chủ động). */
    @Synchronized fun clear() { closeQuietly(); segments().forEach { it.delete() }; needRepair = true; curSize = 0; current = null }
}
