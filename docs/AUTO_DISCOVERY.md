# Tự tìm và kết nối xe (0.4.0-auto)

Luồng: Quét (xe TẮT rồi BẬT) → xếp hạng ứng viên → bạn cho phép "Kết nối thử" → khám phá GATT → trạng thái xác thực → báo cáo giao thức.

## Nguyên tắc
- Không xác nhận Yamaha từ RSSI/tên/UUID. Mức cao nhất từ quảng bá là "bằng chứng gián tiếp"; "USER_CONFIRMED" nghĩa là BẠN đã lưu.
- Trạng thái chỉ lên khi có sự kiện: GATT_CONNECTED = GATT Ready; AUTHENTICATED = Android báo BONDED + GATT Ready (chỉ mức BLE);
  PROTOCOL_VERIFIED/DATA_AVAILABLE = có adapter đã đăng ký kèm bằng chứng (hiện KHÔNG có).
- Không ghi vào xe, không phát lại gói, không đoán PIN, không lưu bí mật. Ghép đôi chỉ khi bạn bấm nút.
- Nghi Y-Connect giữ kết nối (quảng bá không connectable lặp lại, hoặc kết nối lỗi lặp lại dù RSSI >= -80): dừng, báo, không chiếm kết nối.
- Tự kết nối: chỉ hồ sơ đã lưu + ưu tiên, cài đặt bật, tối đa 3 chu kỳ, một lần mỗi lần mở app.

## Cần tài liệu/cơ chế từ Yamaha
Giải mã dữ liệu xe, xác thực lớp giao thức, và đẩy chỉ đường lên TFT. `NavAdapterRegistry` và `AdapterRegistry` là điểm cắm.

## Kiểm thử trên máy thật (chưa làm)
Xe tắt/bật; thiết bị lân cận; địa chỉ xoay vòng; Y-Connect đang kết nối; mất tín hiệu; từ chối ghép đôi. Ghi kết quả vào docs/TEST_CHECKLIST.md.
