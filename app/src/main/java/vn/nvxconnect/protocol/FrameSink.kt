package vn.nvxconnect.protocol

/**
 * Đích nhận gói notify/indicate NGAY TRÊN luồng callback của Bluetooth.
 * Cài đặt PHẢI không chặn: không I/O tệp, không database, không khóa dài. Chỉ chép vào hàng đợi có giới hạn rồi trả về.
 * GattSessionController gọi sink này trước khi phát dữ liệu cho giao diện, nên dữ liệu vẫn được ghi khi không còn màn hình nào quan sát.
 */
interface FrameSink {
    /** Người dùng yêu cầu một lần kết nối mới (kể cả đổi thiết bị): mở một phiên thu thập mới. Địa chỉ KHÔNG được ghi xuống đĩa. */
    fun onSessionStart() {}

    /**
     * @param epoch số thứ tự phiên GATT (tăng mỗi lần mở lại GATT) — để phân biệt gói trước/sau khi nối lại.
     * @param wallMs thời gian epoch (đối chiếu với thiết bị tham chiếu); @param monoNs thời gian đơn điệu (đo khoảng cách giữa các gói).
     */
    fun onFrame(
        epoch: Int, charUuid: String, serviceUuid: String?, bytes: ByteArray,
        wallMs: Long, monoNs: Long, rssi: Int?, connState: String, indication: Boolean
    )
}
