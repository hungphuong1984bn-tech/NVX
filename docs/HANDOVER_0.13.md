# HANDOVER 0.13.0-live — kết nối xe thật, thu thập không âm thầm mất gói, chạy nền

> **Trạng thái thật:** chưa thử trên xe và điện thoại thật. Chưa chạy `./gradlew` (môi trường soạn không có Android SDK/Maven). Xem mục 3.

## 1. Lỗi phát hiện (theo mức độ) và cách khắc phục
| # | Mức | Lỗi (đã đọc mã để xác nhận) | Khắc phục |
|---|---|---|---|
| 1 | NGHIÊM TRỌNG | Phiên GATT thuộc `BleViewModel` (`viewModelScope`) và `onCleared()` gọi `conn.dispose()`: Activity bị hủy → đóng GATT, timer nối lại chết. Không có dịch vụ nền cho BLE (chỉ `TripService` loại *location*, chỉ chạy khi ghi hành trình). Tắt màn hình lâu → tiến trình có thể bị đóng băng/tắt. | Phiên GATT chuyển sang `NvxApp.vehicleConnection` (một đối tượng cho cả tiến trình). ViewModel chỉ quan sát, **không đóng** phiên. Thêm `VehicleConnectionService` (foreground, `connectedDevice`) do `VehicleServiceHost` bật/tắt theo trạng thái kết nối. |
| 2 | NGHIÊM TRỌNG | Gói notify đi qua `MutableSharedFlow(extraBufferCapacity=256).tryEmit(...)`: khi bộ thu chậm gói **bị bỏ không đếm**; khi không có bộ thu (Activity đã hủy) gói **biến mất**. Toàn bộ xử lý (hex, giải mã, cập nhật UI) chạy trên luồng chính. | `FrameSink` được gọi ngay trên callback → `FrameIngress` (hàng đợi giới hạn 4096, không chặn, mỗi gói được cấp `seq` trước khi vào hàng đợi, gói bị bỏ được đếm theo nguyên nhân/thời điểm/khoảng seq) → `FrameWriter` ghi lô xuống đĩa. Gói giao diện không theo kịp cũng được đếm (`uiFramesDropped`). |
| 3 | NGHIÊM TRỌNG | `RawFrameRecorder` chỉ trong RAM (vòng 20.000): tiến trình bị dừng là mất hết; không có seq phiên bền vững, không có đồng hồ đơn điệu, không có xoay vòng/kiểm tra dung lượng. | `DurableFrameLog`: tệp nối thêm chia đoạn, mỗi dòng có CRC32, `fsync` mỗi lô, sửa đuôi ghi dở khi mở lại, kiểm tra dung lượng trống, xoay vòng theo tổng dung lượng (số đoạn bị xóa được báo), phục hồi sau lỗi ghi. |
| 4 | CAO | Nếu Bluetooth **đang tắt** lúc bấm kết nối, `AndroidGattPort.open()` trả lỗi **trước khi** đăng ký bộ nhận sự kiện bật/tắt Bluetooth → bật lại không bao giờ được nhận biết. Và ngay cả khi nhận được, `onBluetoothState(true)` chỉ ghi log, không nối lại. | Đăng ký bộ nhận trước mọi kiểm tra; Bluetooth bật lại + người dùng chưa ngắt + lỗi trước đó là `BLUETOOTH_OFF` → nối lại **đúng một lần**. Bluetooth tắt/thiếu quyền/xác thực không bao giờ lặp vô hạn. |
| 5 | CAO | `onServices`/`onDescriptorWrite`/`onMtu` muộn của phiên cũ chưa bị loại ở tầng máy trạng thái: `onServices(status=0)` muộn khi đang `Reconnecting` đẩy trạng thái sang `Ready` với dịch vụ cũ; `onDescriptorWrite` muộn xóa trạng thái đăng ký và có thể nhả nhầm hàng đợi (tìm ra bằng test mới). | Mỗi callback chỉ được nhận khi đang chờ nó (đúng trạng thái; CCCD chỉ khi có thao tác đang chờ). Phần còn lại bị loại và đếm (`lateEventsDropped`). Cổng Android vẫn lọc theo token phiên. |
| 6 | TRUNG BÌNH | `AndroidGattPort` (API < 33) trả `characteristic.value` — bộ đệm dùng chung — không sao chép; gói kế tiếp có thể ghi đè trước khi xử lý. | Sao chép ngay tại callback (cả notify và read). |
| 7 | TRUNG BÌNH | `onNotification` giữ khóa chính của máy trạng thái (cùng khóa với connect/timer/`port.open`) → callback có thể bị chặn. | Đường nhận gói không giữ khóa (đếm gói bằng `AtomicLong`). |
| 8 | TRUNG BÌNH | Nối lại theo nhịp cố định 1→4 s, không rung: nhiều lần thử cùng nhịp. Không có lịch sử nguyên nhân mất kết nối. | `ReconnectPolicy.jitter` (±20% cho chế độ xe); `linkHistory` (≤100 mốc: mã GATT, thời gian sống, số gói, phiên GATT). |
| 9 | THẤP | Không biết "đã kết nối" khác "đang nhận" khác "đang ghi" khác "đã xác minh". | `LinkMonitorModel` + `LinkMonitorPanel` hiển thị 4 mức tách bạch; thiếu dữ liệu → "Chưa có dữ liệu". |

Không đổi (đã đúng từ trước, giữ nguyên): không ghi vào `6e400002`, không keep-alive, xác thực/mã hóa (5/15) thì dừng, tự nghe `6e400003` (có bằng chứng log thật, `docs/KET_NOI_THAT.md`).

## 2. File đã sửa / file mới
Mới: `protocol/FrameSink.kt`; `telemetry/{CaptureModels,FrameIngress,DurableFrameLog,FrameWriter,CapturePipeline,CaptureExport,LinkMonitorModel}.kt`; `service/{VehicleServicePolicy,VehicleConnectionService,VehicleServiceHost}.kt`; `ui/LinkMonitorPanel.kt`; test `CapturePipelineTest.kt`, `LinkResilienceTest.kt`; tài liệu này.
Sửa: `protocol/GattSessionController.kt`, `protocol/VehicleConnection.kt`, `protocol/BleVehicleConnection.kt` (AndroidGattPort), `NvxApp.kt`, `ui/BleViewModel.kt`, `ui/ConnectScreen.kt` (1 dòng gọi panel), `AndroidManifest.xml`, `AppInfo.kt`, `app/build.gradle.kts` (phiên bản), `tools/verify-jvm.sh` (thêm `service/`), `ui/CockpitScreen.kt` (import `rememberSaveable` — lỗi biên dịch CI trước đó).

**Lưu trữ bằng tệp, không dùng Room:** Room cần KSP và schema/migration khớp tuyệt đối; không thể kiểm ở đây, và migration sai có thể làm hỏng dữ liệu hành trình/nhiên liệu đang có. Tệp append-only kiểm được trên JVM bằng tệp thật. Nếu muốn Room sau này, `CapturedFrame` đã đủ trường.

## 3. Kết quả build/test THỰC TẾ
| | Kết quả |
|---|---|
| `./gradlew assembleDebug`, `testDebugUnitTest` | **CHƯA chạy** (không có Android SDK/Gradle/Maven trong môi trường soạn). Cần chạy trên GitHub Actions/Android Studio. Dự kiến lỗi có thể còn ở Compose. |
| `tools/verify-jvm.sh` (kotlinc 2.0.21, android.jar API 34, JUnit 4.13.2) | Type-check lớp Android không-Compose: **sạch**. **485 test, 0 lỗi** (mốc đầu 446 + 39 mới). |
| Kiểm đột biến (cố ý phá mã) | 3/3 bị test bắt: bỏ đếm gói mất → 3 test đỏ; nhận gói ngoài phiên → 1 đỏ; không sửa dòng ghi dở → 1 đỏ. Mã đã khôi phục, chạy lại 485 đạt. |
| Compose (`LinkMonitorPanel.kt`, `ConnectScreen.kt`), Manifest, Room/KSP | **Chưa được trình biên dịch kiểm.** `LinkMonitorPanel` chỉ dùng thành phần đã có trong dự án và import tường minh. |
| Xe thật / điện thoại thật / tắt màn hình 30 phút | **Chưa thử.** Thủ tục ở mục 5. |

## 4. Cài APK, quyền, kết nối
1. Build: đẩy lên GitHub (workflow `.github/workflows/android.yml`) hoặc `./gradlew assembleDebug`; APK ở `app/build/outputs/apk/debug/`.
2. Cài APK, mở app → cấp **Thiết bị ở gần** (Android 12+) hoặc Vị trí chính xác (Android 8–11, chỉ để quét BLE), và **Thông báo** (Android 13+: cần để thấy thông báo dịch vụ nền).
3. Tab **Kết nối xe** → quét → chọn xe → Kết nối. Dịch vụ nền tự bật; thanh thông báo "Kết nối xe" xuất hiện.
4. Tắt tối ưu pin cho NVX Connect (Cài đặt → Ứng dụng → Pin → Không hạn chế): nhiều hãng máy (Xiaomi, Oppo, Vivo, Samsung) vẫn có thể tắt tiến trình nền dù có dịch vụ foreground.
5. Bấm **Ngắt kết nối** (trong app hoặc ở thông báo) → dừng hẳn, không tự nối lại.

## 5. Quy trình thử trên xe thật (điền kết quả; đừng suy luận)
Chuẩn bị: bật `Giám sát kết nối`; mở **Xuất** sau mỗi kịch bản. Ghi giờ thật của từng thao tác (đồng hồ điện thoại).
| Bước | Thao tác | Kỳ vọng cần QUAN SÁT | Ghi lại |
|---|---|---|---|
| A | Khóa điện **TẮT**, bấm kết nối | Nối được GATT; ~29 s xe ngắt (status 19); app "đang chờ xe", **không báo lỗi cuối**; Nhận dữ liệu = "xe chưa gửi gói nào" | số lần nối lại, mã GATT trong Lịch sử |
| B | Bật khóa điện khi app đang chờ | Tự nối ≤ vài giây; "Đăng ký nghe" = đã xác nhận; ~18 gói/giây | thời gian từ bật khóa → gói đầu |
| C | Nổ máy / tắt máy | Gói liên tục; sau tắt máy xe ngắt sau chục giây | gói cuối, thời điểm ngắt |
| D | **Tắt màn hình ≥ 30 phút** (khóa điện bật) | Thông báo còn; khi bật lại: số gói tăng đều, `Gói app làm mất` = 0, `Khoảng cách lớn nhất giữa 2 gói` nhỏ | đối chiếu `received` trước/sau |
| E | Đi ra ngoài tầm (hoặc che điện thoại) rồi quay lại | Lịch sử có "Mất liên kết"; tự nối lại; phiên GATT thứ tăng | |
| F | Tắt Bluetooth rồi bật | "Bluetooth đang tắt" (không lặp); bật lại → tự nối **một lần** | |
| G | Vuốt tắt app khỏi đa nhiệm | Quan sát: dịch vụ có sống tiếp không (tùy hãng). Mở lại app: báo "Phiên trước bị dừng đột ngột" nếu bị tắt; dữ liệu đã ghi vẫn còn | **Ghi rõ có/không** |
| H | Bấm Ngắt kết nối | Thông báo biến mất; không nối lại | |
| I | Đối chiếu RPM với đồng hồ vòng tua độc lập, điện áp với vạn năng, tốc độ với GPS (≥4 mức, ≥2 phiên) | Dùng thẻ Hiệu chỉnh/nhập tham chiếu CSV (xem `SAMPLE_LOG_GUIDE.md`) | file tham chiếu |

## 6. Định dạng log cần gửi để phân tích lỗi
1. **Giám sát kết nối → "Xuất toàn bộ gói (CSV)"** — cột: `seq,session,epoch,wall_ms,wall_iso_utc,mono_ns,gap_ms_same_epoch_char,char_uuid,service_uuid,source,rssi,conn_state,length,hex`. `epoch` = số thứ tự phiên GATT (tăng khi nối lại). `seq` có khoảng trống = app làm mất gói (đã đếm). Dữ liệu thô giữ nguyên.
2. **JSON Lines**: dòng đầu = metadata + thống kê (`lostByApp`, `droppedIngress`, `droppedStorage`, `maxGapMs`, `wallClockJumps`, các đợt mất gói), tiếp theo mỗi dòng một gói, dòng cuối `{"end":true,...}`.
3. Kèm chụp màn hình bảng **Lịch sử kết nối** và ghi giờ từng thao tác thực.
Nhật ký chứa UUID, byte thô, thời gian, RSSI; **không** chứa MAC hay tên thiết bị. Xóa bằng nút "Xóa dữ liệu thu thập".
Định dạng đĩa (`filesDir/capture/seg-*.nvxlog`): `V1,<crc32>,<seq>,<session>,<epoch>,<wallMs>,<monoNs>,<char>,<service|->,<source>,<rssi|->,<state>,<HEX>`.

## 7. Trường dữ liệu CHƯA xác minh (không đổi so với bằng chứng hiện có)
Không trường nào ở trạng thái VERIFIED. Theo `docs/KET_NOI_THAT.md`, `TELEMETRY_FIELD_EVIDENCE.md`:
- **RPM**: giả thuyết u16 BE @ byte 5–6 của khung `0x6B`; trường `0x6C` lệch ≈0,75× → chưa biết cái nào đúng. Cần đồng hồ vòng tua độc lập, ≥4 mức, ≥2 phiên.
- **Điện áp ắc quy**: giả thuyết byte 11 × 0,07 V; chưa có số đo vạn năng.
- **Tốc độ, nhiệt độ động cơ, mức nhiên liệu, ODO, trạng thái động cơ, điện áp sạc, mã lỗi (DTC)**: chưa có nguồn dữ liệu đã xác minh (UNAVAILABLE).
- Checksum SUM8 của `0x6B/0x6C` khớp 100% trong log thật (tính toàn vẹn khung, **không** chứng minh ý nghĩa trường).
- Thiếu: trạng thái `INVALID` theo từng trường và metadata nguồn (UUID/byte/endianness/hệ số) dạng bảng thống nhất — hiện nằm rải ở `TelemetryVerification` (có `unmet`/`missingEvidence`) và `YccCandidates`. Chưa làm trong bản này.

## 8. Việc chưa làm / giới hạn đã biết
- Giải mã (adapter/`YccMonitor`/`TelemetryHub`) vẫn chạy trong `BleViewModel`: khi Activity bị hủy, **gói vẫn được ghi xuống đĩa** nhưng số liệu giải mã trực tiếp tạm dừng tới khi mở lại app. Chưa chuyển sang phạm vi tiến trình.
- Sự kiện kết nối (không phải gói) chỉ nằm trong RAM; sau khi tiến trình chết chỉ còn gói + cờ "phiên trước bị dừng".
- Không khẳng định Bluetooth chạy mãi: force-stop, tắt Bluetooth, thu hồi quyền (Android tự tắt tiến trình), hoặc hãng máy tắt tiến trình nền đều dừng kết nối. Dịch vụ dùng `START_NOT_STICKY` có chủ đích (Android 12+ có thể từ chối bật dịch vụ foreground từ nền).
- Test "tắt màn hình 30 phút" và "xe thật" không thể chạy bằng JUnit → thủ tục thủ công ở mục 5.
