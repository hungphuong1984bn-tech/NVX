# Đánh giá thư viện BLE (tra cứu 2026-10-23)

Chỉ ghi điều đã đọc được từ nguồn; mục nào chưa kiểm được thì nói rõ là chưa kiểm.

| Thư viện | License | Phiên bản/bảo trì (theo nguồn) | Ghi chú kỹ thuật | Quyết định |
|---|---|---|---|---|
| Nordic **Android-BLE-Library** (`no.nordicsemi.android:ble`, `ble-common`, `ble-ktx`) | BSD 3-Clause (POM trên Maven Central) | `ble-common` 2.11.0 và `ble` 2.10.x trên Maven Central; 71 bản phát hành, ~2,4K sao (releasealert) | Quản lý hàng đợi thao tác, thử lại, MTU; KHÔNG gồm quét (Nordic có thư viện scanner riêng); phụ thuộc AndroidX; bài đánh giá bên thứ ba nói thư viện khá lớn | **Chưa tích hợp** |
| Nordic **Kotlin-BLE-Library** | Chưa đọc được tệp license trong lần tra cứu này (chưa kiểm) | Nhánh 1.x (client/scanner 1.0.7 theo README, ghi "Beta"); bản viết lại v2 đang ở `2.0.0-alpha16` | Coroutines/Flow, có module scanner/client/mock | **Chưa tích hợp** (v2 còn alpha) |
| Kable, RxAndroidBle, Android `BluetoothGatt` thuần | — | **Chưa tra cứu** | — | Không đánh giá |

## Vì sao chưa thay lớp BLE hiện có
1. Lớp hiện tại (`GattSessionController` + `GattPort`, hàng đợi `GattOpQueue`, `BleScanner`) đã có 19 test máy trạng thái và đã được thiết kế **không có đường ghi dữ liệu vào xe**. Thư viện ngoài thêm bề mặt API ghi/ghép đôi cần chặn lại và kiểm thử lại.
2. Môi trường soạn mã không truy cập được Maven/Google (xem `BUILD_LOG.md`): không thể biên dịch hay kiểm thử phụ thuộc mới. Thêm một thư viện không thể build là rủi ro không có kế hoạch kiểm thử.
3. Chưa có bằng chứng từ điện thoại thật rằng lớp hiện tại thất bại (status 133, MTU…). Khi có log lỗi thật, kế hoạch là đưa Android-BLE-Library sau giao diện `GattPort` (chỉ cần một lớp mới, không đụng parser/giao thức), rồi chạy lại `GattSessionTest`.
4. Chưa kiểm: `minSdk` của các thư viện trên so với `minSdk = 26` của app.

## Thư viện cho parser/JSON/lưu trữ
Không thêm. Giao thức xe chưa có bằng chứng dùng JSON/CBOR; đã có `ByteViews` (endianness, bit field) và `FrameAnalyzer`/`ProtocolReport`. Room và DataStore đã có sẵn cho dữ liệu người dùng.
