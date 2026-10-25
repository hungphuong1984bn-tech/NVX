# PROTOCOL_STATUS (cập nhật: 2026-10-21)

| Hạng mục | Trạng thái |
|---|---|
| UUID/service/characteristic của xe NVX | CHƯA BIẾT - chưa có bằng chứng |
| Định dạng gói tin, byte order, checksum | CHƯA BIẾT |
| RPM / nhiệt độ nước / điện áp / mã lỗi từ ECU | UNAVAILABLE |
| Kết nối xe thật | CHƯA THỬ trên xe thực |
| BLE scanner (quảng bá) | Đã viết mã, CHƯA build, CHƯA thử trên thiết bị |
| GATT discovery (chỉ liệt kê, không đọc/ghi) | Đã viết mã, CHƯA build, CHƯA thử |
| Xuất log JSON/CSV có che địa chỉ | Đã viết mã + unit test, CHƯA chạy test |

Chưa có VehicleProtocolAdapter nào vì chưa có bằng chứng giao thức.

## Cập nhật Giai đoạn C (2026-10-21)
- Đã viết khung: VehicleConnection, VehicleProtocolAdapter, AdapterRegistry (rỗng, từ chối adapter thiếu bằng chứng), RawCaptureAdapter (mọi gói = UNKNOWN), BleVehicleConnection (timeout 15s, kết nối lại lùi thời gian tối đa 5 lần, chỉ nghe notify thụ động, không ghi).
- KHÔNG có adapter thật nào: chưa có bằng chứng giao thức. RPM/nhiệt độ/điện áp/mã lỗi vẫn UNAVAILABLE.
- BleVehicleConnection chưa build, chưa thử trên xe. Chưa nối vào giao diện.

## Giai đoạn D (2026-10-21)
- Đã soạn checklist và mẫu log thử nghiệm. Chưa thử trên thiết bị hay xe thật, nên chưa có sai số, tần suất cập nhật hay kết luận tương thích nào.

## Ghi chú về ảnh chụp ứng dụng khác (2026-10-21)
- Ảnh chỉ cho thấy giao diện và tên tính năng của một ứng dụng bên thứ ba ("Tự xác thực với xe"). Không có thông tin nào về UUID, gói tin hay cơ chế xác thực. Không dùng để suy ra giao thức.
- Không triển khai ghép đôi/xác thực thay Y-Connect: cần giao thức có tài liệu hoặc được Yamaha cho phép.

## Kết nối thật - khảo sát thụ động (2026-10-21)
- Tab Khảo sát đã nối BleVehicleConnection vào giao diện. Kết nối tới xe có thể bị từ chối hoặc không liệt kê được dịch vụ nếu xe yêu cầu ghép nối/xác thực: khi đó app chỉ báo lỗi, không cố vượt qua.
- Gói nhận được luôn hiện RAW/UNKNOWN vì AdapterRegistry rỗng.

## Nâng cấp kết nối v2 (2026-10-21)
- Xem docs/BLE_UPGRADE.md. Log khảo sát đã gửi cho thấy một thiết bị "YCC" có service dạng Nordic UART; chưa có gói notify nào được bắt, giao thức vẫn CHƯA BIẾT, AdapterRegistry vẫn rỗng.

## Bản 0.3.0-nav (2026-10-22)
- Giao thức xe vẫn CHƯA BIẾT; AdapterRegistry vẫn rỗng; không có adapter Yamaha nào, không có dữ liệu xe nào được hiển thị.
- Khi GATT Ready mà chưa có adapter: giao diện ghi "CONNECTED — RAW DATA ONLY" và "VEHICLE PROTOCOL NOT VERIFIED".
- Giao diện đã nối BLE ở mức thụ động; máy trạng thái có unit test với cổng GATT giả, CHƯA thử trên điện thoại/xe thật.
- TFT: xem TFT_NAVIGATION.md (không hỗ trợ).


## Vì sao không thể "tự động" xác thực giao thức (2026-10-22)
App không có tài liệu giao thức của Yamaha và không có xe thật trong môi trường phát triển. Với một giao thức nhị phân không công bố,
không có "sự thật nền" (ground truth) để một thuật toán tự so sánh và tự kết luận đúng/sai — chỉ có thể quan sát BYTE nào đổi khi nào.
Quan sát đó luôn cần một người đối chiếu với thực tế (đồng hồ xe, hành động vừa làm) mới thành bằng chứng. Vì vậy bản 0.3.2 thêm công cụ
TƯƠNG QUAN (CorrelationEngine): gắn nhãn hành động, gợi ý vị trí byte khả nghi — nhưng việc "xác thực" vẫn luôn cần một người xác nhận,
không thể và sẽ không được tự động hóa trong app này. Mọi tuyên bố "đã kết nối thật" phải đi qua AdapterRegistry.register với hồ sơ bằng
chứng đầy đủ (docs/PROTOCOL_EVIDENCE_TEMPLATE.md), do người, không phải do app tự gắn cờ.

## 0.6.0-capabilities (2026-10-23)
- Giao thức xe vẫn CHƯA BIẾT; AdapterRegistry vẫn rỗng; không có adapter, không có dữ liệu xe, TFT vẫn UNSUPPORTED.
- Thêm nhãn ENCRYPTED_OR_AUTHENTICATED (dấu hiệu, không phải kết luận) và trạng thái BONDED tách khỏi AUTHENTICATED: bonding chỉ là ghép đôi BLE.
- Thêm danh mục chức năng xe/app và bảng đối chiếu (`capability/`); mặc định mọi chức năng xe là UNKNOWN.
- Nguồn công khai mới tra cứu: xem PROTOCOL_RESEARCH.md. Chưa có nguồn nào cho NVX.

## 0.10.0-telemetry-verify (2026-10-25)
Bảng trên ĐÃ LỖI THỜI từ 2026-10-23: kênh YCC (Nordic UART) đã bắt được khung thật; xem `YCC_PROTOCOL_FINDINGS.md`, `BLE_PROTOCOL_AUDIT.md`, `TELEMETRY_FIELD_EVIDENCE.md`. Không trường nào VERIFIED/CORRELATED.
