# PROTOCOL_RESEARCH - Giai đoạn A

## Đã xác minh từ nguồn công khai (tìm ngày 2026-10-21)
- Yamaha Việt Nam ra mắt NVX 2025 (155cc Blue Core, VVA); bản SP và GP có YECVT, đồng hồ TFT 4,2 inch, kết nối Y-Connect (VOH, 26/6/2025).
- Có tin bán xe "NVX 2026 155cc" tại TP.HCM (muaban.net, 07/2026). CHƯA tìm thấy thông số chính thức Yamaha cho đời 2026; chưa xác định biến thể (tiêu chuẩn/SP/GP) nào có CCU.
- FAQ Y-Connect (Yamaha Malaysia): ứng dụng dùng thiết bị CCU (Communication Control Unit) lắp trên xe; cần dữ liệu di động; lưu vị trí đỗ khi ngắt kết nối; CCU chỉ lắp trên xe thiết kế sẵn CCU. FAQ này của thị trường Malaysia, chưa chắc áp dụng y nguyên cho Việt Nam.

## Chưa biết / không kết luận được
- Kênh liên lạc xe-điện thoại (BLE hay khác), UUID, định dạng dữ liệu, cơ chế ghép nối/xác thực.
- Tài liệu kỹ thuật công khai về giao thức: chưa tìm thấy. Không suy đoán.

## Cách thu thập bằng chứng an toàn (cần xe thật + sự đồng ý của chủ xe)
1. Dùng app NVX Connect (chế độ quét), bật/tắt chìa khóa thông minh, ghi lại thiết bị xuất hiện/mất theo thời gian; xuất log đã che.
2. Nếu thiết bị kết nối được, dùng GATT discovery (chỉ liệt kê) và xuất danh sách service/characteristic.
3. Không gửi lệnh, không đọc characteristic chưa rõ, không can thiệp ghép nối Y-Connect.
4. Ghi biến thể xe, số hiệu CCU (nếu có), phiên bản firmware/app, điều kiện thử.

---
## Bổ sung 2026-10-23 — nguồn công khai về CCU/TFT/Y-Connect

Danh mục có cấu trúc nằm trong mã: `capability/ProtocolProfiles.kt` (hiển thị ở màn hình "Danh mục chức năng → Hồ sơ").
**Không nguồn nào nêu NVX 155 V3 SP 2025. Không hồ sơ nào được coi là tương thích.**

| Nguồn | License | Kênh | Xe do nguồn nêu | Liên quan NVX |
|---|---|---|---|---|
| Pillion (github.com/alexandrevega/pillion), NaviLite — đẩy ảnh dẫn đường lên TFT của CCU Garmin | PolyForm Noncommercial 1.0.0 | **Bluetooth Classic RFCOMM (SPP)**, không phải BLE GATT (tài liệu PROTOCOL.md của nguồn) | MT-07, MT-09, MT-09 SP, R9, XSR900, XMAX; NMAX "rất có thể" | Không có bằng chứng |
| YammiC (github.com/C0Newb/YammiC) — kết nối Yamaha CCU qua BLE, dữ liệu CAN | GPL-3.0 | BLE | README chỉ nói "xe thể thao" của tác giả | Không có bằng chứng; tự ghi chưa tìm thấy tín hiệu mức xăng; lấy passkey ghép đôi qua API Yamaha là việc chưa làm |
| Yamaha toàn cầu — trang Y-Connect | Của Yamaha | BLE 4.1+ (yêu cầu phía điện thoại) | "Tương thích thay đổi theo mẫu xe"; cần CCU và tài khoản Yamaha Motor ID | Không có đặc tả giao thức |
| VOH 2025-06-26 (ra mắt NVX 2025 VN) | — | — | NVX 2025 (Standard/SP/GP): Google Maps qua Y-Connect, nhiên liệu, lịch bảo dưỡng | Mô tả tính năng, không phải giao thức |

### Hệ quả cho dự án
- Nếu CCU của NVX VN cùng nền tảng Garmin, kênh TFT có thể là RFCOMM và **không xuất hiện** trong khảo sát BLE. Chưa biết; cần đối chiếu trên xe thật (xem thiết bị Bluetooth Classic trong cài đặt điện thoại — không ghép đôi, không gửi dữ liệu).
- Bản ghi khảo sát của người dùng (CHANGELOG 0.4.1) thấy thiết bị "YCC" có khung kiểu UART của TI; điều này chưa khớp/không khớp với nguồn nào ở trên.
- Cả hai dự án cộng đồng đều có license hạn chế (phi thương mại/GPL): **không sao chép mã**; chỉ dùng như tài liệu đối chiếu cấu trúc, rồi kiểm chứng độc lập.
- Dự án không phát lại gói, không gửi khung lên xe, không lấy passkey qua tài khoản Yamaha.
