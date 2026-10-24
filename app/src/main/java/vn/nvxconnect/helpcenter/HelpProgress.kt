package vn.nvxconnect.helpcenter

/** Lưu tiến độ hướng dẫn. Cài đặt thật dùng SharedPreferences (data/PrefsHelpProgressStore); test dùng bản trong bộ nhớ. */
interface HelpProgressStore {
    fun load(): Set<String>
    fun save(completed: Set<String>)
}

class InMemoryHelpProgressStore(initial: Set<String> = emptySet()) : HelpProgressStore {
    private var data: Set<String> = initial
    override fun load() = data
    override fun save(completed: Set<String>) { data = completed }
}

object HelpProgressCodec {
    fun encode(ids: Set<String>): String = ids.sorted().joinToString(",")
    fun decode(s: String?): Set<String> = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
}

sealed interface CompleteResult {
    data object Done : CompleteResult
    data class NotAllowed(val reason: String) : CompleteResult
}

/**
 * Tiến độ hướng dẫn. Quy tắc "không tự đánh dấu hoàn thành khi kiểm tra chưa đạt":
 *  - Bài có kiểm tra tự động liên kết (xem [ConnectionChecklist.stepForGuide]) chỉ được đánh dấu khi bước kiểm tra tương ứng ĐẠT tại thời điểm bấm.
 *  - Bài thuộc loại tham khảo ([HelpGuide.manualComplete] = false) không có trạng thái hoàn thành.
 *  - id lạ (bài đã bị xóa ở phiên bản sau) bị loại khi tải.
 */
class HelpProgressTracker(private val store: HelpProgressStore, private val guides: List<HelpGuide> = HelpContent.guides) {
    private val known = guides.map { it.id }.toSet()
    private var completed: Set<String> = store.load().filter { it in known }.toSet()

    fun completedIds(): Set<String> = completed
    fun isCompleted(id: String) = id in completed
    fun completedCount() = completed.size
    /** Chỉ tính các bài có thể hoàn thành (loại bài tham khảo) để tỉ lệ không bao giờ đạt 100% một cách giả tạo. */
    fun trackableCount() = guides.count { it.manualComplete }
    fun ratio(): Double = trackableCount().let { if (it == 0) 0.0 else completed.count { id -> guides.first { g -> g.id == id }.manualComplete }.toDouble() / it }

    fun markComplete(id: String, checklist: List<StepResult>? = null): CompleteResult {
        val g = guides.firstOrNull { it.id == id } ?: return CompleteResult.NotAllowed("Không có bài hướng dẫn này.")
        if (!g.manualComplete) return CompleteResult.NotAllowed("Bài này chỉ để tham khảo, không có trạng thái hoàn thành.")
        val step = ConnectionChecklist.stepForGuide[id]
        if (step != null) {
            val r = checklist?.firstOrNull { it.step == step }
            if (r == null || r.status != CheckStatus.PASSED)
                return CompleteResult.NotAllowed("Chưa đạt bước kiểm tra \"${step.title}\": ${r?.detail ?: "chưa kiểm tra"}.")
        }
        completed = completed + id
        store.save(completed)
        return CompleteResult.Done
    }

    fun unmark(id: String) { completed = completed - id; store.save(completed) }
    fun reset() { completed = emptySet(); store.save(completed) }
}
