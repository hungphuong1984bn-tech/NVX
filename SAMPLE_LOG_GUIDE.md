# SAMPLE_LOG_GUIDE — tạo log thực tế để phân tích bước tiếp theo

An toàn: app chỉ **nghe** (notify/indicate) và đọc các thông tin chuẩn; **không ghi** vào xe. Đừng thử lệnh điều khiển. Làm ở nơi an toàn, xe đứng yên trên chân chống; chỉ chạy GPS khi điều kiện giao thông cho phép (nhờ người khác ghi hoặc để điện thoại tự ghi).

## A. Chuẩn bị
1. Cài bản 0.10.0-telemetry-verify; cấp quyền Bluetooth/vị trí. Tắt Y-Connect chính hãng (nó giữ kết nối độc quyền).
2. Kết nối → Tự tìm xe → kết nối thử → Phân tích giao thức → tab **Xác minh**. "ĐANG GHI" phải bật; đặt nhãn phiên bằng "Bắt đầu ghi" mỗi lần thử mới.
3. Chuẩn bị thiết bị đo: máy đo vòng tua rời, đồng hồ vạn năng, (tùy) nhiệt kế ngoài. Cần đồng hồ giờ điện thoại đúng; ghi timestamp tham chiếu bằng đồng hồ điện thoại (UTC, ms nếu được).

## B. Phiên 1 (xe tắt → bật khóa → nổ máy)
1. Bắt đầu ghi. Tắt máy 30 s → bật khóa 30 s → nổ cầm chừng 60 s.
2. Giữ ga ổn định ≥10 s ở ≥4 mức vòng tua cách xa nhau (phủ ≥1.500 rpm). Mỗi mức: ghi số đọc tham chiếu + giờ.
3. Đo điện áp ắc quy bằng vạn năng ở: tắt máy, bật khóa, cầm chừng, ga vừa (phủ ≥1 V). Ghi giờ.
4. Dừng ghi → **Xuất JSON** (và CSV nếu muốn). Giữ nguyên tên tệp.

## C. Phiên 2 (lặp lại ở lần khóa điện khác, tốt nhất khác ngày)
Làm lại B. Cần ≥2 phiên để đánh giá tái lập.

## D. Tốc độ (tùy chọn)
Bật Hành trình (GPS) rồi chạy với tốc độ đổi ≥18 km/h; xuất log. `SpeedCrossCheck` sẽ gợi ý trường nếu có.

## E. Tệp tham chiếu CSV
Tiêu đề bắt buộc: `time_ms|time_utc,field,value,unit,source,grade[,method,session]`
- `field`: `rpm`, `engine_temp`, `vehicle_speed`, `battery_voltage`, `charging_voltage`, `engine_status`.
- `unit`: phải đúng `rpm`, `°C`, `km/h`, `V`, `state` (không quy đổi ngầm).
- `source`: mô tả thiết bị đo (bắt buộc). `grade`: `official` | `independent` | `cluster` (đồng hồ xe) | `inference`.
- Dòng thiếu nguồn/đơn vị/cấp hợp lệ bị bỏ kèm cảnh báo. Ví dụ trong `docs/samples/SYNTHETIC_reference.csv` (**dữ liệu tổng hợp**, chỉ để thử công cụ).

## F. Gửi lại để phân tích
Cần: (1) file log JSON/CSV của ≥2 phiên; (2) CSV tham chiếu; (3) ghi chú: mẫu xe, ngày, thiết bị đo, thao tác từng đoạn. Chạy ngay trên máy tính: `tools/analyze-log.sh log1.json log2.json --ref tham_chieu.csv` (cần kotlinc). Kết quả: bảng thiết bị/characteristic, cấu trúc khung, bảng bằng chứng từng trường.

## G. Việc người dùng cần làm để có bất kỳ trạng thái nào cao hơn "giả thuyết"
- Log thật mới (các log cũ không có trong gói đã giao: `nvx-ble-log-20261024-071131_1.json` và 3 log 10-23 — nếu còn, hãy gửi lại).
- Dữ liệu tham chiếu độc lập như mục E.
Không có hai thứ này thì mọi trường sẽ dừng ở DECODED_HYPOTHESIS hoặc UNAVAILABLE.
