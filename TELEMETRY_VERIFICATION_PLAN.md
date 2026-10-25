# TELEMETRY_VERIFICATION_PLAN — kế hoạch xác minh từng thông số

Tiêu chí dưới đây **đặt trong mã** (`CriteriaRegistry.defaultFor`) và được ghim bằng test (`builtInCriteriaArePinnedAgainstPostHocTuning`) trước khi có bất kỳ dữ liệu tham chiếu nào. Tiêu chí người dùng tự đặt phải được khóa **trước** khi nhập tham chiếu (`ProtocolEvidenceRepository.lockCriteria` từ chối sau đó); tiêu chí bị sửa sau khóa bị phát hiện qua vân tay SHA-256.

## 1. Quy tắc chung
- Điều kiện NỀN (cần, không đủ): đúng UUID, đúng loại khung và độ dài, tổng kiểm đạt ≥ 99,9% khung của loại đó.
- Điều kiện VERIFIED = nền + **toàn bộ** bảng ngưỡng dưới đây + tham chiếu thuộc cấp `official` hoặc `independent`.
- Đồng hồ xe (`cluster`) cùng nguồn ECU và làm tròn → tối đa **CORRELATED**.
- Tham chiếu `inference` (suy luận từ mẫu) không bao giờ vượt DECODED_HYPOTHESIS.
- Không quy đổi đơn vị ngầm: mẫu tham chiếu khác đơn vị bị loại và ghi vào "còn thiếu bằng chứng".
- Độ trễ: tìm độ lệch thời gian tốt nhất trong ±`maxLag`; báo cáo độ trễ ước lượng; mỗi phiên phải tự đạt sai số trung bình.
- STALE: mẫu gần nhất cũ hơn `stale` → trạng thái hiển thị là STALE (giữ `baseStatus`). Log ngoại tuyến xét độ cũ tại gói cuối.

## 2. Bảng tiêu chí mặc định
| Trường | Đơn vị | Cặp tối thiểu | Phiên ≥3 cặp | Phủ tham chiếu | Số mức (cách nhau) | Pearson r ≥ | Sai số TB ≤ | Sai số max ≤ | Độ lệch chuẩn ≤ | Độ trễ ± | Cửa sổ ghép | Quá hạn |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RPM | rpm | 30 | 2 | 1500 | 4 (300) | 0,997 | 50 | 100 | 60 | 1 s | 0,5 s | 3 s |
| Nhiệt độ động cơ | °C | 20 | 2 | 20 | 3 (5) | 0,98 | 2,0 | 4,0 | 2,5 | 5 s | 2 s | 10 s |
| Tốc độ xe | km/h | 40 | 2 | 18 | 4 (4) | 0,98 | 2,0 | 5,0 | 3,0 | 3 s | 1,5 s | 3 s |
| Điện áp ắc quy | V | 20 | 2 | 1,0 | 3 (0,3) | 0,99 | 0,10 | 0,20 | 0,12 | 5 s | 2 s | 10 s |
| Điện áp sạc | V | 20 | 2 | 0,8 | 3 (0,2) | 0,99 | 0,10 | 0,20 | 0,12 | 5 s | 2 s | 10 s |
| Trạng thái động cơ (0/1) | state | 30 | 2 | 1 | 2 (0,5) | 0,95 | 0,05 | 1,0 | 0,25 | 2 s | 1 s | 5 s |
| DTC | — | — | — | — | — | — | — | — | — | — | — | — |
Phủ ≥ 80% số mẫu tham chiếu phải ghép được với mẫu giải mã (thiếu dữ liệu). Ngưỡng RPM/điện áp khớp `CalSpecs` của bộ hiệu chỉnh cũ (RMSE 50 rpm / 0,10 V).
Các ngưỡng nhiệt độ, tốc độ, trạng thái, điện áp sạc là **lựa chọn kỹ thuật của dự án**, không phải thông số Yamaha; hãy sửa (và khóa) trước khi thu tham chiếu nếu thiết bị đo của bạn kém hơn.

## 3. Phương pháp theo trường
| Trường | Nguồn tham chiếu chấp nhận để VERIFIED | Cách đo | Tình trạng |
|---|---|---|---|
| RPM | Máy đo vòng tua độc lập (kẹp bugi/cảm biến rời); tài liệu/ công cụ chẩn đoán chính thức | ≥4 mức giữ ổn định, ≥2 phiên khác ngày/khóa điện | Có giả thuyết `u16be@5` của 0x6B; chưa có tham chiếu |
| Nhiệt độ | Nhiệt kế/cảm biến ngoài có hiệu chuẩn; công cụ chẩn đoán chính thức | Từ nguội đến nóng, ≥3 mức cách ≥5 °C | **Chưa có giả thuyết/nguồn** |
| Tốc độ | GPS điện thoại (độ chính xác ≤30 m) hoặc máy đo tốc độ độc lập | Chạy có đổi tốc độ ≥18 km/h; `SpeedCrossCheck` đã có để gợi ý trường | **Chưa có giả thuyết** (byte 27/31/34 đổi khi nổ máy, chưa gán nghĩa) |
| Điện áp ắc quy | Đồng hồ vạn năng ở cực ắc quy | ≥3 trạng thái: tắt máy, bật khóa, nổ cầm chừng, ga vừa | Có giả thuyết byte 11 × 0,07; chưa có tham chiếu |
| Điện áp sạc | Vạn năng khi nổ máy (cùng phép đo, khác điều kiện) | Cầm chừng/ga vừa/ga cao | Chưa có nguồn phân biệt với điện áp ắc quy |
| Trạng thái động cơ | Quan sát có ghi nhãn thời điểm (nổ/tắt) | ≥30 mốc, ≥2 phiên | Chưa có giả thuyết |
| DTC | Công cụ chẩn đoán được hỗ trợ có tài liệu | Không đoán lệnh vào ECU | `UNAVAILABLE` (không có đường truy cập hợp lệ) |
| Khác | Chỉ khi có bằng chứng thực tế | — | Không đưa vào |

## 4. Quy trình
1. (Tùy chọn) khóa tiêu chí riêng. 2. Ghi log theo `SAMPLE_LOG_GUIDE.md`. 3. Ghi dữ liệu tham chiếu CSV có timestamp/nguồn/cấp. 4. Nhập vào tab "Xác minh" hoặc `tools/analyze-log.sh log --ref tham_chieu.csv`. 5. Đọc `TELEMETRY_FIELD_EVIDENCE` sinh ra: danh sách tiêu chí chưa đạt nói rõ cần thêm gì. 6. Lặp ở phiên khác.

## 5. Giới hạn đã biết của phương pháp
- Hồi quy tuyến tính/hệ số chưa được ước lượng tự động ở engine mới: engine chỉ **kiểm** giả thuyết đã nêu (scale/offset), không tự tinh chỉnh. Bộ hiệu chỉnh cũ (`YccCalibration`) vẫn dùng để tìm hệ số; kết quả của nó là ĐỀ NGHỊ, rồi mới đăng ký giả thuyết mới và thu tham chiếu **mới** để kiểm.
- DTC và trạng thái không có phép kiểm danh mục mã; chưa cài.
- Các ngưỡng chưa được hiệu chuẩn với thiết bị đo thực tế.
