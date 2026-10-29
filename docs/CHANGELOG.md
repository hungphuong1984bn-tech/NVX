# 0.13.0-live — Kết nối nền + thu thập không âm thầm mất gói (2026-10-26)
Chi tiết, danh sách lỗi và quy trình thử xe thật: `docs/HANDOVER_0.13.md`. Chưa thử xe thật; Gradle/Compose chưa chạy.
- Phiên GATT chuyển sang `NvxApp.vehicleConnection` (ViewModel không còn đóng phiên); thêm `VehicleConnectionService` (foreground `connectedDevice`) + quyền `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- Đường thu thập: `FrameSink` → `FrameIngress` (hàng đợi giới hạn, đếm mọi gói bị bỏ) → `FrameWriter` → `DurableFrameLog` (CRC, fsync, sửa đuôi ghi dở, xoay vòng, kiểm tra dung lượng). Xuất CSV/JSON Lines.
- Sửa: Bluetooth tắt lúc bấm kết nối không bao giờ tự nối lại; callback muộn (`onServices`, `onDescriptorWrite`, `onMtu`) làm sai trạng thái; `characteristic.value` không sao chép (API<33); `onNotification` giữ khóa chính.
- Nối lại có rung ±20%; lịch sử mất kết nối; bảng "Giám sát kết nối" (4 mức tách bạch).
- Sửa lỗi biên dịch CI: thiếu import `rememberSaveable` trong `CockpitScreen.kt`.
- Test 446 → 485 (`CapturePipelineTest`, `LinkResilienceTest`). Kiểm đột biến: 3/3 bị test bắt.

# 0.12.0-live — Kết nối thật (2026-10-25)

Dựa trên 2 log thật của NVX 155 V3 SP (`nvx-ble-log-20261025-190509`, `-194402`; 6.616 gói, 0 gói sai tổng kiểm 0x6B/0x6C). Chi tiết bằng chứng: `docs/KET_NOI_THAT.md`.
- **Nguyên nhân "không kết nối thật"** (từ log, không phải giả định): xe tự ngắt (status 19) ~29,3 s sau READY ở 23/25 lần ngắt (0 gói) khi khóa điện TẮT, và app (a) chỉ thử lại 5 lần rồi báo lỗi, (b) không tự bật nghe, người dùng phải bấm "Nghe" (log 2: mất 43,5 s đầu), (c) hết giờ 15 s rồi bỏ khi xe không quảng bá. Khi bật khóa điện, liên kết giữ 133 s/355 s và phát ~18 gói/s mà app KHÔNG cần ghi gì vào xe.
- `ReconnectPolicy.vehicle()`: nối lại KHÔNG giới hạn (lùi tối đa 4 s). Xe tự ngắt (19) → thử lại sau 0,5 s, không tính là thất bại. Xác thực/mã hóa (5/15) vẫn DỪNG, không vượt qua.
- Sau 3 lần không thấy xe → **kết nối nền** (Android autoConnect, không hết giờ): bật khóa điện là tự nối.
- `YccAutoListen`: tự bật nghe `6e400003` (chỉ NOTIFY trong service Nordic-UART) ngay khi READY và sau mỗi lần nối lại; người dùng tắt nghe thì không tự bật lại. KHÔNG ghi vào `6e400002`.
- `LinkHint` (STREAMING / WAITING_IGNITION / NOT_IN_RANGE) + thông điệp rõ: "CHỜ BẬT KHÓA ĐIỆN", "ĐANG TÌM XE", "ĐANG NHẬN DỮ LIỆU TỪ XE (ƯỚC TÍNH)" thay vì lỗi. Vẫn `protocolVerified = false`: số liệu chưa được xác minh bằng đồng hồ xe/vạn năng.
- Test 436 → 446 (`LiveLinkTest` tái hiện hành vi trong log). Type-check lớp Android với android.jar API 34: sạch. Compose/Gradle/điện thoại thật: CHƯA chạy.

# 0.11.0-pro — NVX Connect Pro (UI/UX)

- Hệ thống thiết kế mới (Design.kt): dark than chì + cyan (mặc định), light thiết kế riêng, chọn Tối/Sáng/Theo hệ thống; token spacing/shape; bố cục responsive (giới hạn bề rộng, chia cột trên màn hình rộng/xoay ngang).
- Điều hướng 5 tab: Trang chủ, Cockpit, Telemetry, Hành trình, Công cụ (Protocol Lab, Chẩn đoán, Cài đặt + các màn hình cũ). Back xác định, trạng thái điều hướng lưu qua xoay màn hình, mỗi tab giữ trạng thái riêng. Một ViewModel/phiên BLE duy nhất.
- Màn hình mới: Cockpit (tốc độ GPS, vòng đo, toàn màn hình, tương phản cao, tùy chỉnh ô), Telemetry Center (biểu đồ thật, thu phóng, vùng chọn, khoảng trống, CSV lưu/phát lại), Diagnostics Center, Protocol Lab 9 tab, Ride Center, Trang chủ Pro.
- Logic thuần mới (package `pro`) có test: ProNav, ChartMath/SeriesStore, HexDiff, PacketFilter, CockpitModel, RideTools, LabStatus, ProSettings, DiagnosticsModel, SeriesCsv.
- Không đổi giao thức, không thêm quyền, không thêm thư viện. Xem docs/PRO_UPGRADE_REPORT.md.

# CHANGELOG

## Chưa phát hành (sau 0.13.0-live)
Thêm lớp ghép khung, 5 mức liên kết, vòng đời trường, hồ sơ tương thích adapter, nhật ký sự kiện bền vững; 545 test. Xem docs/UPGRADE_AUDIT_AND_HANDOVER.md.

## 0.8.0-ycc-calibration (2026-10-24)
Nâng độ chính xác của các kênh lấy dữ liệu. Vẫn KHÔNG có trường nào VERIFIED và vẫn không ghi vào xe.
- **Mới** `YccCalibration.kt` / `YccCalibrationController.kt`: hiệu chỉnh bằng điểm tham chiếu thật (đồng hồ xe/vạn năng): hồi quy bình phương tối thiểu, R², RMSE, sai số leave-one-out, ngưỡng chất lượng theo kênh (≥4 điểm, độ phủ tối thiểu, hệ số hợp lý), phát hiện điểm gõ sai, chỉ nhận điểm khi tín hiệu ỔN ĐỊNH (trung vị, biên độ giới hạn).
- **Kiểm chứng độc lập**: ≥3 điểm lấy SAU khi chốt hệ số; đổi hệ số thì kiểm chứng cũ mất hiệu lực. Đạt = "đủ điều kiện đề nghị xác minh", chưa phải VERIFIED.
- **Mới** `YccSignalFilter`: loại giá trị ngoài miền/nhảy bất khả thi, trung vị 5 mẫu; reset khi mất kết nối. Trên 3.919 gói thật: 0 gói bị loại nhầm (bước nhảy lớn nhất 1.596 rpm < ngưỡng 4.000).
- `SpeedCrossCheck`: nội suy tuyến tính GPS (thay vì mẫu gần nhất), dò độ trễ GPS↔BLE ±2 s, báo sai số km/h, quét mọi độ dài gói (cả 0x6C 143 byte).
- Lưu điểm hiệu chỉnh (`PrefsCalibrationStore`), nạp lại khi mở app. UI: bảng "Hiệu chỉnh bằng số đo thật" trong Phân tích giao thức (chưa được trình biên dịch Compose kiểm tra).
- Test: 217 → 233, `verify-jvm.sh` OK.

## 0.7.0-ycc-framing (2026-10-23)
Tích hợp kết quả 3 lần bắt thật (5.988 gói) của kênh NUS thiết bị "YCC". Chi tiết và mức bằng chứng: `docs/YCC_PROTOCOL_FINDINGS.md`.
- **Mới** `protocol/YccFrame.kt`: bộ phân tích khung (5 loại), tổng kiểm SUM8, độ dài, TLV 0x6C, `YccLinkMonitor` (đếm gói hợp lệ/lỗi/đứt bộ đếm). Chỉ nghe, không ghi.
- **Mới** `protocol/YccCandidates.kt`: giá trị ỨNG VIÊN (RPM, điện áp) gắn nhãn "GIẢ THUYẾT — chưa xác minh", kênh riêng, không vào `TelemetryHub`/TFT.
- `BleViewModel` giải mã khi có service NUS; `ProtocolAnalysisScreen` thêm thẻ "Kênh YCC". Chưa được trình biên dịch Compose kiểm tra.
- Test: 207 → 217 (`YccFrameTest`, dùng gói thật). `verify-jvm.sh`: OK.
- KHÔNG đổi: không ghi vào xe, `AdapterRegistry` vẫn rỗng, mọi trường vẫn chưa VERIFIED.

## 0.6.0-capabilities (2026-10-23)
Nâng cấp theo đề "Auto Vehicle Protocol Discovery & Capability Engine". Dự án nhận vào đã có sẵn tự khảo sát/GATT/phân tích giao thức; bản này bổ sung phần còn thiếu.
- **Mới** `capability/`: Vehicle Capability Registry (11 nhóm, mặc định UNKNOWN; AVAILABLE/OBSERVED chỉ từ adapter đã xác minh + giá trị còn mới; HYPOTHESIS từ SpeedCrossCheck; UNSUPPORTED chỉ khi có căn cứ),
  App Capability Registry (đã triển khai? kiểm thử mức nào? phụ thuộc gì? dữ liệu thật/tính toán/mẫu? chạy khi không có xe?), Capability Matching Engine (4 trường hợp của đề + BLOCKED + PHONE_INDEPENDENT),
  hồ sơ giao thức tham chiếu có nguồn/license, xuất báo cáo text/JSON/CSV.
- **Mới** màn hình `CapabilityScreen` (Xe / App / Đối chiếu / Cần làm / Hồ sơ, tìm kiếm, lọc, xuất), mở từ màn Phân tích giao thức. Chưa được trình biên dịch Compose kiểm tra.
- **Mới** `ByteViews`: HEX/thập phân/nhị phân, little/big-endian, có dấu/không dấu, bit field.
- **Đổi hành vi (có chủ đích, theo §8 đề bài)**: ghép đôi Android (BONDED) không còn hiển thị là AUTHENTICATED. `AutoState` thêm `BONDED` và `CONNECTION_BUSY` (nghi Y-Connect giữ kết nối; trước đây là FAILED);
  `AUTHENTICATED` chỉ đạt khi adapter đã xác minh báo xác thực lớp giao thức (hiện không thể xảy ra).
- **Đổi hành vi**: `EvidenceLevel.ENCRYPTED_OR_AUTHENTICATED` thay cho UNKNOWN/HYPOTHESIS ở hai chỗ: thiết bị đòi xác thực (GATT 5/15) và entropy rất cao. UNKNOWN giữ nguyên tên (tương ứng "UNSUPPORTED = chưa có bằng chứng" của đặc tả).
- `VehicleParam` thêm TRIP_KM, IGNITION_ON, MAINT_STATE; `VehicleProtocolAdapter.supportedParams` (mặc định null); `AdapterRegistry.evidences()`.
- Test: 182 → 207 (CapabilityTest, ByteViewsTest, sửa AuthAndStateTest/ProtocolReportTest). Phiên bản 0.6.0-capabilities (versionCode 8).
- Tài liệu: `LIBRARY_EVALUATION.md`, bổ sung `PROTOCOL_RESEARCH.md`, `PROTOCOL_STATUS.md`, `FEATURE_STATUS.md`, `HANDOVER.md`, `BUILD_LOG.md`.

# 0.5.3-live-data-ready (chưa build)
- Xăng xe thay Pin xe (xe xăng): "Chưa khả dụng" cho tới khi có dữ liệu xe đã xác minh. Thêm VehicleParam SPEED_KMH/FUEL_LEVEL/ODOMETER_KM, TelemetryHub, TftComposer, TftDisplayPipeline, TftNavigationBridge.sendFrame (mặc định từ chối), registry adapter dùng chung ở NvxApp, test TelemetryTftTest, docs/LIVE_DATA_PLUGIN.md. KHÔNG có adapter nào, KHÔNG gửi gì lên xe.

# 0.5.1-cockpit-screens (chưa build)
- Mới: WelcomeScreen (hiện lần đầu, lưu cờ), ProtocolAnalysisScreen (Tổng quan/Log/Chi tiết, xuất log, lưu báo cáo), TftDisplayScreen (Đầy đủ/Chỉ bản đồ + 5 công tắc; chỉ lưu sở thích, TFT vẫn CHƯA HỖ TRỢ).
- Trang chủ: ảnh xe, Pin xe = "Chưa khả dụng" (không có nguồn), RSSI thật, 3 dòng Trạng thái kết nối. Tự tìm xe: 3 ô số (tìm thấy/ứng viên/đã kết nối), nút mở Phân tích. Cài đặt: thẻ xe + dòng menu. Bản đồ: ETA + chuyển Chỉ bản đồ/Đầy đủ.
- Settings: thêm tftMapOnly, tftSpeed/Route/Trip/Vehicle/Clock (mặc định như cũ, không phá dữ liệu cũ).
- Ảnh xe thật (nền trong suốt) ở `res/drawable-nodpi/bike_hero.webp`; biểu tượng ứng dụng thích ứng (adaptive + monochrome) ở `res/mipmap-anydpi-v26`, `drawable-nodpi/ic_launcher_*`, khai báo trong Manifest.

# 0.5.0-cockpit-ui (chưa build)
- UI: theme navy "Premium Smart Cockpit" + design tokens (`ui/Design.kt`: NvxColors/Spacing/Radius/Elevation/Motion); `Components.kt` dùng token (giữ nguyên chữ ký hàm nên mọi màn hình tự đổi diện mạo).
- Mới `ui/Cockpit.kt`: AppTopBar, SectionHeader, PrimaryActionButton, ConnectionStatusCard, VehicleSummaryCard, DeviceCandidateCard, ProtocolStatusBadge, ScanRadarAnimation, EmptyState, ErrorState, LoadingState, LogEntryCard, NvxBottomBar.
- Trang chủ: wordmark, thẻ trạng thái kết nối + nút "Tự tìm và kết nối xe", lối tắt Bản đồ/Hành trình/Xe/Giao thức. Tự tìm xe: radar chỉ chạy khi đang quét thật, thẻ ứng viên và báo cáo dùng component mới.
- KHÔNG đổi: logic BLE, ViewModel, giao thức, trạng thái kết nối/xác thực/xác minh, dữ liệu.
- Chưa làm: ảnh xe, hiển thị TFT, màn hình mới (Phân tích giao thức riêng, Trình xem HEX), lọc/sắp xếp danh sách quét, làm lại bố cục Bản đồ/Hành trình/Cài đặt (các màn này mới chỉ đổi màu/thẻ theo token).

# CHANGELOG

## 0.4.2-autoanalyze (2026-10-23)
- Bấm "Xuất JSON" → app TỰ chạy phân tích và đính kèm mục `analysis.findings` trong tệp (không cần bấm thêm).
- `SpeedCrossCheck`: tự đối chiếu mọi trường (1 byte, 2 byte LE/BE) của gói notify với TỐC ĐỘ GPS điện thoại (sự thật nền độc lập). Cần ≥ 40 cặp mẫu, tốc độ đổi ≥ ~18 km/h, r ≥ 0.95, đạt ở cả hai nửa dữ liệu, loại bộ đếm/ODO. Kết quả = HYPOTHESIS ("giá trị thô ≈ a × km/h + b"), KHÔNG gán đơn vị, KHÔNG thành VERIFIED, không tạo adapter, không đổi LinkLevel.
- Vẫn không tự "xác thực/kết nối thật": VERIFIED chỉ qua AdapterRegistry.register do người đăng ký kèm hồ sơ bằng chứng (xem PROTOCOL_STATUS.md). Thêm SpeedCrossCheckTest (5 test).

## 0.4.1-specs (2026-10-23)
Dữ liệu xe THAM KHẢO (không phải dữ liệu đọc từ xe):
- Tab Xe có mục "Thông số": thông số NVX 155 từ tài liệu công bố (động cơ, công suất, dầu máy, bình xăng 5,5 L đời mới / 4,6 L đời đầu, khối lượng), kèm nhãn "Tham khảo" và cảnh báo đối chiếu sổ tay.
- "Tầm chạy ước tính" = dung tích bình ÷ L/100km (tính từ số đổ xăng bạn nhập), nguồn CALCULATED. Không đọc mức xăng thật.
- KnownUuids: thêm nhãn CHUNG cho dịch vụ HID, Device Information, khung serial TI thấy trong log 20261023. UUID riêng e7810b92/e7810bd2/bef8e8xx KHÔNG gán nhãn (chưa có bằng chứng).
- DataSource vẫn 6 giá trị; RPM/nhiệt độ/điện áp/DTC vẫn "Chưa có dữ liệu". Thêm VehicleSpecsTest.

## 0.4.0-auto (2026-10-23)
Tự tìm và kết nối xe (gói `auto/`, màn hình `AutoConnectScreen`, mở từ tab Kết nối). Xem `docs/AUTO_DISCOVERY.md`.
- Auto Discovery: phiên quét gắn nhãn xe TẮT/BẬT, `CandidateRanker` xếp hạng bằng chênh lệch bật/tắt, gom địa chỉ xoay vòng/tên đổi, hiển thị lý do + "cần thêm bằng chứng". Không mức nào nói "đã xác nhận Yamaha".
- Hồ sơ xe: `SavedDevice.manufacturerIds` (tương thích ngược), `ProfileMatcher` nhiều tín hiệu.
- Auto Connection: `AutoConnectController` (một vòng, backoff, giới hạn chu kỳ, dừng khi nghi Y-Connect giữ kết nối, dừng khẩn cấp). `AutoStatusResolver`: SCANNING/CANDIDATE_FOUND/GATT_CONNECTED/AUTHENTICATED/PROTOCOL_VERIFIED/DATA_AVAILABLE/FAILED, chỉ lên khi có sự kiện xác nhận.
- Authentication: `AndroidBondObserver` (quan sát BOND_STATE_CHANGED; ghép đôi CHỈ khi người dùng bấm nút, hộp thoại hệ thống), `AuthAssessor`. Không PIN/khóa/token nào được lưu.
  LƯU Ý: đây là thay đổi so với các bản trước (không `createBond`); chỉ gọi từ nút người dùng.
- Protocol Analyzer: `ProtocolReport` (OBSERVED/HYPOTHESIS/VERIFIED/UNKNOWN), so sánh phiên thử nghiệm, phát hiện bộ đếm/độ dài/checksum/entropy. VERIFIED chỉ qua `AdapterRegistry`.
- GATT Inspector: `GattInspector` (bảng + cờ ghi). TFT: `NavAdapterRegistry` (rỗng, từ chối thiếu tài liệu/thử xe thật).
- 59 test mới (tổng 162).

## 0.3.2-correlate (2026-10-22)
Thêm "Khảo sát có hướng dẫn" (mục Nâng cao, Kết nối): gắn nhãn hành động vừa làm trên xe thật (ga, phanh, xi-nhan, còi, đèn, nổ máy...),
CorrelationEngine so khung trước/sau mỗi nhãn và gợi ý vị trí byte đổi trùng lặp. Đây là GỢI Ý cho người khảo sát, KHÔNG phải giải mã
hay xác minh tự động: không trường nào được gán tên/đơn vị, không tạo VehicleProtocolAdapter, không đổi LinkLevel. Kết quả chỉ có ý nghĩa
sau khi người khảo sát tự đối chiếu với đồng hồ xe ở nhiều mức độ thao tác và điền docs/PROTOCOL_EVIDENCE_TEMPLATE.md.
Lý do không thể "tự động xác thực" hoàn toàn: xem docs/PROTOCOL_STATUS.md mục mới.

## 0.3.1-ui (2026-10-22)
Kết nối xe: màn hình Kết nối viết lại thành luồng từng bước (Bluetooth → Quét → Kết nối → Dữ liệu) với thẻ trạng thái lớn, một nút hành động chính theo ngữ cảnh
(Quét / Dừng quét / Ngắt kết nối / Thử lại / Kết nối lại thiết bị ưu tiên), vạch sóng RSSI, nhãn "tên giống thiết bị Yamaha" (chỉ đoán theo tên, không xác minh),
bộ lọc thu gọn. Công cụ khảo sát (cây GATT, gói RAW, nhật ký, xuất log) gom vào mục "Nâng cao". Bước "Dữ liệu" không hoàn tất khi chưa có giao thức xe được xác minh.
Không đổi hành vi BLE, không thêm lệnh ghi, không thêm giao thức giả.
UI: thành phần dùng chung mới (StatusDot, SignalBars, StepRow, DisclosureHeader), Page có phụ đề, thanh điều hướng đồng màu bề mặt.

## 0.3.0-nav (2026-10-22)
BLE: máy trạng thái GATT tách khỏi Android (GattSessionController + GattPort) với trạng thái CONNECTING/CONNECTED/DISCOVERING_SERVICES/READY/
RECONNECTING/ERROR; kiểm tra Bluetooth/quyền/Vị trí theo từng phiên bản Android; nhận diện lỗi theo loại; nghe notify có xác nhận CCCD;
đọc thông tin chuẩn theo danh sách cho phép; nhật ký có cấu trúc (thời gian, UUID, trạng thái, HEX) xuất JSON/CSV đã che; thiết bị đã lưu, tìm kiếm, bộ lọc,
gợi ý loại địa chỉ; GattSurveyor không còn mở phiên GATT thứ hai; JSON xuất có escape đầy đủ ký tự điều khiển và được kiểm tra bằng bộ đọc JSON; chia sẻ log bằng tệp (bản cũ chia sẻ chuỗi, cắt ở 100.000 ký tự).
Giao thức: VehicleLinkStatus (RAW DATA ONLY / NOT VERIFIED), AdapterRegistry.find, TftNavigationBridge (UNSUPPORTED), DataSource khớp 4 nguồn yêu cầu.
Bản đồ/dẫn đường: tìm địa điểm, định tuyến OSRM, theo dõi tuyến, nhắc rẽ bằng giọng nói tiếng Việt, lệch tuyến + tính lại, đến nơi.
Hành trình: TripEngine ở phạm vi ứng dụng, dịch vụ foreground, tạm dừng/tiếp tục, lưu theo đợt, khôi phục chuyến dở dang, xem lại; tách đoạn khi GPS đứt >30 s,
lọc GPS trôi và tốc độ nhiễu.
Dữ liệu: hồ sơ xe, kiểm tra nhập liệu xăng, bảo dưỡng việc một lần, xuất/xóa dữ liệu.
UI: 5 tab (Trang chủ, Bản đồ, Hành trình, Xe, Kết nối) + Cài đặt; thẻ BLE và GPS tách biệt; nhãn nguồn dữ liệu.
Manifest: thêm COARSE_LOCATION, FOREGROUND_SERVICE(+LOCATION), POST_NOTIFICATIONS, FileProvider, Application; BLE không còn bắt buộc để cài.
Xóa: ScanScreen dư thừa trong MainActivity cũ.
Schema Room giữ version 1 (chỉ thêm truy vấn).

## 0.9.0-help-center (2026-10-24)
- Mới: Trung tâm hướng dẫn (14 bài, tìm kiếm, tiến độ, kiểm tra từng bước), VehicleDataRepository (trạng thái/khoảng hợp lệ/nhật ký nguồn), TftIntegrationManager + NavigationIntegrationAdapter, DiagnosticsReport.
- Sửa: cờ "đã có dữ liệu" chỉ đặt khi kho dữ liệu thật sự nhận giá trị; chip TFT "Đã gửi" không còn tông xanh; xóa dữ liệu gồm tiến độ hướng dẫn.
- 296 test JVM đạt. Gradle/APK chưa chạy được (xem IMPLEMENTATION_REPORT.md).

## 0.10.0-telemetry-verify (2026-10-25)
- `telemetry/`: RawFrameRecorder, log JSON/CSV `/3` (xuất + nhập, cả `/2`), OfflineLogAnalyzer, TelemetryValidationEngine, ProtocolEvidenceRepository, báo cáo bằng chứng. 55 test mới.
- Tab "Xác minh" trong Phân tích giao thức (chưa biên dịch Compose). `tools/analyze-log.sh` phân tích ngoại tuyến.
- Không đổi giao thức, không ghi xuống xe, không trường nào VERIFIED.
