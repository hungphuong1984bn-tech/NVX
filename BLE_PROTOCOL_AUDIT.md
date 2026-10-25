# BLE_PROTOCOL_AUDIT — kiểm toán giao thức và nguồn dữ liệu (0.10.0-telemetry-verify)

Ngày: 2026-10-25. Phạm vi: mã nguồn trong `NVX-0_9_0-help-center.zip`. Mọi kết luận ghi rõ nguồn; điều không chứng minh được ghi là **chưa chứng minh**.

## 1. Dữ liệu thực sự có trong gói được giao
| Mục | Kết quả |
|---|---|
| `nvx-ble-log-20261024-071131_1.json` (nêu trong đề) | **KHÔNG có trong zip** (đã tìm toàn bộ cây thư mục và hệ thống tệp). Chưa phân tích được. |
| "Log telemetry mới trong thư mục dự án" | **KHÔNG có** tệp log nào (không `.json`/`.csv` log). |
| 3 log 2026-10-23 (`nvx-ble-log-20261023-181445/184119/184153`) mà `docs/YCC_PROTOCOL_FINDINGS.md` dựa vào | **KHÔNG có**; chỉ còn các con số tổng hợp trong tài liệu (5.988 gói, SUM8 5.988/5.988…). Không thể kiểm lại độc lập. |
| Khung thật còn lại | 7 khung hex đã che trong `YccFrameTest.kt` (0x5A, 0x59, 0x6A, 0x6B×3, 0x6C×2). Dùng làm fixture cấu trúc. |
| Dữ liệu tham chiếu (vạn năng, máy đo vòng tua, GPS…) | **KHÔNG có**. |

→ Hệ quả: **không trường nào có thể lên CORRELATED/VERIFIED** từ những gì đang có. Xem `TELEMETRY_FIELD_EVIDENCE.md`.

## 2. Lỗi, giới hạn trước khi sửa (đọc mã 0.9.0)
1. `BleViewModel` chỉ giữ 5.000 khung đầu (`rawFrames.size < 5000`) rồi **bỏ âm thầm** gói mới; ở ~18 gói/s (0x6B ~12/s + 0x6C ~6/s) đầy sau ~4,6 phút. `SessionLogBuffer` cũng là vòng 5.000 dòng gồm cả khung.
2. Nguồn payload không được ghi: khung notify chỉ là dòng "notify N byte"; đọc characteristic chỉ là dòng văn bản; indicate không phân biệt với notify.
3. RSSI, MTU, kết quả đăng ký nghe, timeout, reconnect chỉ tồn tại ở dạng chuỗi tiếng Việt trong log; log JSON `/2` không gắn thiết bị cho từng gói.
4. Muối ẩn danh (`salt`) ngẫu nhiên mỗi lần mở app → ID thiết bị khác nhau giữa các lần xuất; không ghép thiết bị giữa nhiều file được (đã bù bằng so sánh theo UUID).
5. Không có bộ nhập/phân tích log ngoại tuyến; `FrameAnalyzer` gộp theo characteristic nên trộn 5 loại khung vào một thống kê.
6. Đăng ký notify thành công và "có dữ liệu" chưa được tách thành hai sự kiện cho người dùng.
7. `docs/PROTOCOL_STATUS.md` (2026-10-21) lỗi thời so với `YCC_PROTOCOL_FINDINGS.md` (10-23) — đã thêm ghi chú trỏ sang tài liệu mới.
8. Toàn bộ Compose/Room/osmdroid chưa từng được biên dịch (xem `docs/BUILD_LOG.md`); tab mới cũng vậy.
9. Xe chủ động ngắt ~29,3 s sau READY (status 19) ở 31/36 lần nối (theo tài liệu cũ); chưa biết nguyên nhân.

## 3. Đã sửa/thêm (xem `IMPLEMENTATION_REPORT.md`)
`RawFrameRecorder` (nguồn payload, cờ bất thường, bộ đếm, giữ đầu phiên + vòng đệm, báo số bản ghi bị cắt), sự kiện kết nối có cấu trúc, xuất JSON/CSV `/3`, nhập `/2` và `/3`, `OfflineLogAnalyzer`, `TelemetryValidationEngine`, kho bằng chứng, tab "Xác minh".

## 4. Thiết bị / characteristic đã biết (từ tài liệu, chưa kiểm lại)
| Thiết bị | Service | Characteristic | Thuộc tính | Có payload | Cấu trúc | Bằng chứng | Liên hệ chứng minh với xe | Tin cậy | Bước tiếp |
|---|---|---|---|---|---|---|---|---|---|
| "YCC" (ẩn danh) | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (dạng Nordic UART) | `6e400003-…` | NOTIFY | Có (theo `YCC_PROTOCOL_FINDINGS.md`) | 5 loại khung 0x5A/59/6A/6B/6C, SUM8 toàn gói ≡ 0 | Tài liệu nội bộ + 7 khung fixture | **CHƯA CHỨNG MINH** (UUID kiểu NUS không chứng minh là ECU Yamaha) | Cấu trúc: trung bình; ý nghĩa: không có | Ghi log mới + tham chiếu (SAMPLE_LOG_GUIDE) |
| "YCC" | cùng service | `6e400002-…` | WRITE/WRITE_NO_RESP | Không | — | — | — | — | **Không ghi**: chưa có tài liệu giao thức |

Phân biệt mức: *phát hiện* (quảng bá) → *kết nối* (GATT Ready) → *truyền dữ liệu* (có payload) → *xác định chức năng* → *chứng minh liên quan động cơ*. Hiện đạt tối đa mức "truyền dữ liệu"; bộ phân tích chỉ cho phép `ENGINE_LINK_PROVEN` khi một trường trên UUID đó đã VERIFIED.

## 5. Cấu trúc khung (đã quan sát, chỉ về định dạng)
| Loại | Dài | Ghi chú đã quan sát | Tổng kiểm |
|---|---|---|---|
| 0x5A | 8 | một lần đầu phiên | SUM8 ≡ 0 |
| 0x59 | 77 | 30 gói đầu phiên, bộ đếm riêng | SUM8 ≡ 0 |
| 0x6A | 74 | bảng kiểu DID, **có trường định danh: không lưu/xuất** | SUM8 ≡ 0 |
| 0x6B | 59 | ~12 gói/s, byte 57 = bộ đếm, byte 58 = tổng kiểm | SUM8 ≡ 0 |
| 0x6C | 143 | ~6 gói/s, 24 mục TLV (nhóm,id,len,dữ liệu) | SUM8 ≡ 0 |
Thứ tự byte, có dấu/không dấu, đơn vị: **chưa biết** cho mọi trường (chỉ có hai giả thuyết ở `TELEMETRY_FIELD_EVIDENCE.md`). 7 khung fixture đều qua kiểm tra SUM8 ≡ 0 và độ dài trong test.

## 6. Về các giả thuyết hiện có
- RPM = u16 big-endian @ byte 5–6 của 0x6B; điện áp = byte 11 × 0,07: **giả thuyết**, chưa phải công thức chính thức.
- Điểm cần xem lại (từ 2 khung fixture 0x6B): khung "idle" byte 11 = 0xB2 (→ 12,46 V theo hệ số giả định) với RPM raw = 0; khung "running" RPM raw = 1692 nhưng byte 11 = 0xAA (→ 11,90 V), **thấp hơn** khi tắt máy, trong khi tài liệu cũ nói ~0xC3 khi nổ máy. Có thể là thoáng qua lúc vừa nổ, hoặc hệ số/vị trí sai. Hai khung không đủ để kết luận; cần log mới + vạn năng.

## 7. Kênh khác
Không giả định Y-Connect có API công khai; `PROTOCOL_RESEARCH.md` chưa tìm thấy nguồn nào nêu NVX. Chẩn đoán/DTC: chưa có phương thức hỗ trợ có tài liệu → `UNAVAILABLE`, không đoán lệnh vào ECU.
