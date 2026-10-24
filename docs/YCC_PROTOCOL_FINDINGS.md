# YCC (Nordic UART) — kết quả phân tích 3 lần bắt, 2026-10-23

Nguồn: `nvx-ble-log-20261023-181445/184119/184153` (gộp, bỏ trùng = **5.988 gói**, 4 phiên GATT, MTU 247). Thiết bị đã BONDED, app chỉ nghe notify (`6e400003`), **không ghi** vào `6e400002`.

## 1. Mức bằng chứng
| Nội dung | Mức | Căn cứ |
|---|---|---|
| Khung & tổng kiểm | **OBSERVED, chắc** | SUM8(toàn gói)=0 ở 5.988/5.988 gói, cả 5 loại khung |
| Độ dài cố định theo loại | OBSERVED | 0x6B=59, 0x6C=143, 0x6A=74, 0x59=77, 0x5A=8 |
| Bộ đếm (byte n-2 của 0x6B/0x6C) | OBSERVED | +1 ở 99,9% cặp liên tiếp; mỗi loại một bộ đếm riêng |
| Cấu trúc TLV của 0x6C | OBSERVED | 24 mục `(nhóm,id,len,data)`, parse đúng 1.973/1.973 gói |
| RPM = u16 big-endian @ byte 5–6 của 0x6B | **HYPOTHESIS (trung bình)** | =0 ở 99,9% gói chưa nổ; 676–3043 khi nổ; ~1.5–1.7k lúc cầm chừng; tương quan 0,97 với mục `02 BA` của 0x6C |
| Điện áp ≈ byte 11 × 0,07 V | **HYPOTHESIS (thấp)** | Giảm chậm khi tắt máy (0xB2→0xB0), tụt khi đề, ~0xC3 khi nổ. Hệ số chưa kiểm |
| Tốc độ, nhiệt độ, xăng, ODO, mã lỗi | **UNKNOWN** | Chưa có đoạn chạy xe có GPS; byte 27/31/34 đổi khi vừa nổ máy nhưng chưa gán nghĩa |

Không trường nào là VERIFIED. `AdapterRegistry` vẫn rỗng; giá trị đi kênh riêng `YccCandidateHub` (không vào `TelemetryHub`, không lên TFT).

## 2. Bố cục khung 0x6B (59 byte, ~12 gói/s)
`6B 05 00 01 21` (cố định) · byte 5–6 (hyp. RPM) · 8, 9, 10, 11 (hyp. điện áp), 12, 13, 16, 19–21, 27, 31, 32, 34 thay đổi · byte 57 = bộ đếm · byte 58 = tổng kiểm. Các byte còn lại không đổi trong 3 lần bắt.

## 3. Khung 0x6C (143 byte, ~6 gói/s)
`6C 18` + 24 mục TLV + bộ đếm + tổng kiểm. Giá trị `FE` lặp lại có vẻ là "không có dữ liệu". Mục đổi theo thời gian: `02 A3`, `02 09`, `02 0A`, `02 3E`, `02 AA`, `02 AB`, `02 BA`, `03 BA`.

## 4. Khung đầu phiên
`5A`(8) → `6A`(74, bảng DID kiểu chẩn đoán, có trường định danh: **không lưu/không xuất**) → 30 gói `59`(77, bộ đếm 0x60–0x7F). Chỉ thấy ở lần nối đầu của phiên app; các lần nối lại không có.

## 5. Phát hiện quan trọng về kết nối
Xe **chủ động ngắt (status 19) đúng ~29,3 s sau khi READY** ở 31/36 lần nối (một số phiên dài hơn: 70–215 s). App tự nối lại sau ~1,9 s và bộ đếm gói tiếp tục, nên dữ liệu chỉ đứt ~2 s mỗi 30 s (giá trị >3 s bị coi là cũ).
Giả thuyết (chưa kiểm): xe chờ một bước ở lớp ứng dụng (xác nhận/keep-alive) mà app cố ý chưa làm. **App không ghi vào `6e400002`** vì chưa có tài liệu; đoán lệnh vào ECU có thể gây hành vi ngoài ý muốn. Nếu muốn tìm hiểu, cách an toàn là so sánh với log HCI của chính ứng dụng chính hãng trên xe của bạn, và chỉ đánh giá thao tác KHÔNG điều khiển xe.

## 6. Quy trình nâng lên VERIFIED (cần bạn, trên xe thật)
1. Điện áp: đo ắc quy bằng vạn năng lúc tắt máy và lúc nổ (≥3 điểm), so với `byte11 × 0,07`; chỉnh hệ số.
2. RPM: giữ ga ở ≥4 mức vòng tua quan sát được trên đồng hồ xe, ghi lại, so với u16@5; tính sai số.
3. Tốc độ: bật GPS, chạy xe có tốc độ đổi ≥18 km/h, ≥40 gói, để `SpeedCrossCheck` tự đối chiếu.
4. Điền `PROTOCOL_EVIDENCE_TEMPLATE.md`, lặp lại ở lần khác, rồi mới `AdapterRegistry.register` thủ công.

## 7. Hiệu chỉnh trong app (0.8.0)
Phân tích giao thức → thẻ "Hiệu chỉnh bằng số đo thật":
- **Vòng tua**: nổ máy, giữ ga ổn định ở ≥4 mức cách xa nhau (tổng phủ ≥1.500 rpm), mỗi mức nhập số trên đồng hồ xe rồi "Ghi điểm". App từ chối nếu tín hiệu đang dao động >6%.
- **Điện áp**: đo ắc quy bằng vạn năng ở ≥4 trạng thái (tắt máy, bật khóa, nổ cầm chừng, ga vừa), phủ ≥1 V.
- Khi chất lượng "Đạt": nút "Kiểm chứng" mở; lấy thêm ≥3 điểm ở mức KHÁC các điểm trước. Đủ → "ĐỦ ĐIỀU KIỆN đề nghị xác minh".
- Sai số của đồng hồ xe (làm tròn 50–100 rpm) là giới hạn thực: nên dùng thêm một nguồn độc lập (ví dụ máy đo vòng tua rời) nếu cần sai số nhỏ hơn.
- Tốc độ: chạy có GPS; kết quả `SpeedCrossCheck` giờ kèm độ trễ GPS và sai số km/h.
