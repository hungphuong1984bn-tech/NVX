# Chỉ dẫn từng chặng lên TFT của NVX 155 V3 SP — trạng thái: KHÔNG HỖ TRỢ (NOT_VERIFIED)

Tìm hiểu ngày 2026-10-22. Hai chức năng được tách riêng:
1. **Bản đồ/dẫn đường trên điện thoại**: đã triển khai độc lập (tab Bản đồ).
2. **Chỉ dẫn lên TFT của xe**: `TftNavigationBridge` hiện là `UnsupportedTftBridge` (status `UNSUPPORTED`), mọi lời gọi gửi đều bị từ chối và
   **không chạm tới BLE**. Giao diện ghi rõ "Không hỗ trợ"; không có hiệu ứng mô phỏng đã hiển thị lên xe.

## Điều tìm được
- Trang của Yamaha Motor mô tả "TBT navi" (dẫn đường từng chặng) là một tính năng của hệ Y-Connect chạy qua **ứng dụng Y-Connect chính thức**
  và đồng hồ trên các mẫu xe được hỗ trợ (https://global.yamaha-motor.com/business/mc/connectivity/yconnect/iceg/tbt_navi/).
- Báo chí xe máy Indonesia mô tả dẫn đường lên đồng hồ của một số mẫu khác (ví dụ Aerox Alpha) hoạt động qua ứng dụng Y-Connect
  (https://www.gridoto.com/read/224195723/yamaha-aerox-alpha-bisa-tampilkan-google-maps-begini-cara-kerjanya).
- FAQ Y-Connect (Malaysia) cho biết ứng dụng làm việc với bộ CCU trên xe (xem `PROTOCOL_RESEARCH.md`).
- KHÔNG tìm thấy: giao thức/SDK công bố, tài liệu cho nhà phát triển bên thứ ba, hay xác nhận rằng NVX 155 V3 SP (thị trường Việt Nam) có
  TBT trong Y-Connect. Những điều này nằm ngoài những gì có thể xác minh từ nguồn công khai.

## Vì sao không triển khai
CCU chỉ nhận dữ liệu qua phiên đã ghép đôi/xác thực của Y-Connect. Triển khai riêng nghĩa là gửi dữ liệu thử vào CCU/TFT hoặc vượt xác thực,
cả hai đều bị cấm trong yêu cầu của dự án (và không an toàn cho xe). Việc không có dữ liệu thì không có gì để "làm giả thành công".

## Điều kiện cần để viết triển khai thật
1. Tài liệu giao thức do Yamaha công bố, hoặc văn bản cho phép tích hợp (SDK/đối tác), áp dụng đúng NVX 155 V3 SP và đúng CCU/firmware.
2. Mô tả cơ chế xác thực được phép dùng; app không được sao chép thông tin xác thực của Y-Connect.
3. Bản ghi thử nghiệm lặp lại được trên xe thật (xe đứng yên, của chính bạn), cùng `ProtocolEvidence` và unit test bằng mẫu gói thật.
4. Cài `VehicleProtocolAdapter` + một `TftNavigationBridge` mới có `status = VERIFIED_READY`, chỉ gửi `TurnInstruction` đã định dạng, có điều kiện an toàn
   (chỉ khi đang dẫn đường, giới hạn tần suất, có nút tắt).

## Hướng dẫn kiểm thử (khi đã có tài liệu hợp lệ)
Dùng `docs/TEST_CHECKLIST.md` phần C. Trước đó, kiểm thử duy nhất cần làm: mở Cài đặt và tab Bản đồ, xác nhận có dòng "KHÔNG HỖ TRỢ" và
không thấy gói nào được ghi (nhật ký BLE không có thao tác ghi ngoài CCCD).
