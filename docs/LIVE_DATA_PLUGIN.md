# Cắm dữ liệu xe thật và hiển thị TFT (điểm cắm đã sẵn, hiện KHÔNG có adapter nào)

Luồng: GATT notify → `AdapterRegistry.decode` (adapter đã xác minh) → `TelemetryHub.ingest` → giao diện (Trang chủ "Xăng xe", "Dữ liệu từ xe") và `TftComposer` → `TftDisplayPipeline` → `TftNavigationBridge.sendFrame`.

Quy tắc đã khóa bằng mã và test (`TelemetryTftTest`):
- `TelemetryHub` chỉ nhận khi liên kết đã `protocolVerified`, mục là `DecodedItem.Value`, nguồn `LIVE_VERIFIED`, số hữu hạn; mất xác minh/ngắt kết nối thì xóa sạch.
- Giá trị quá 5 giây coi là "Chưa khả dụng".
- Khung TFT: tốc độ và xăng xe chỉ từ xe; không có thì bỏ trường, không thay bằng GPS. Chỉ đường là dữ liệu điện thoại, gắn nhãn đúng nguồn.
- `TftDisplayPipeline` chỉ gửi khi bridge `VERIFIED_READY` + liên kết đã xác minh + khung không rỗng. Mặc định `UnsupportedTftBridge` luôn từ chối.

Khi có bằng chứng giao thức trên xe thật:
1. Viết `VehicleProtocolAdapter` trả `DecodedItem.Value(VehicleParam.FUEL_LEVEL | SPEED_KMH | ODOMETER_KM | RPM ..., value, unit, ...)` với `ProtocolEvidence` đầy đủ, rồi `NvxApp.adapters.register(adapter)`. Đơn vị (%, vạch, lít) lấy từ bằng chứng, không đoán.
2. Với TFT: viết `NavigationProtocolAdapter` (có tài liệu/giấy phép + thử trên xe), override `sendFrame`/`sendManeuver`, `NvxApp.navAdapters.register(adapter)`.
3. Chạy `tools/verify-jvm.sh` rồi thử trên xe và điền `docs/TEST_CHECKLIST.md`.
