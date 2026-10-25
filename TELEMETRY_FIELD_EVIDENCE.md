# TELEMETRY_FIELD_EVIDENCE — bảng bằng chứng theo từng trường

Trạng thái tại 2026-10-25, bản 0.10.0-telemetry-verify. **Không có trường nào đã VERIFIED hay CORRELATED.** Lý do: gói được giao không chứa log thật nào và không có dữ liệu tham chiếu độc lập (xem `BLE_PROTOCOL_AUDIT.md` §1).

## 1. Tổng hợp
| Trường | Trạng thái | Phân loại bằng chứng | Nguồn dữ liệu / UUID | Công thức (giả thuyết) | Đã có | Còn thiếu |
|---|---|---|---|---|---|---|
| RPM | DECODED_HYPOTHESIS | Chỉ là suy luận từ mẫu dữ liệu | Khung 0x6B, `6e400003-b5a3-f393-e0a9-e50e24dcca9e` (NOTIFY) | u16 big-endian @ byte 5–6, hệ số 1, đơn vị rpm | Tài liệu cũ: 5.988 gói, =0 khi chưa nổ, 676–3043 khi nổ, r=0,97 với mục `02 BA` của 0x6C (số liệu không kiểm lại được). Fixture: 0 và 1692 | Tham chiếu độc lập (máy đo vòng tua), ≥4 mức, ≥2 phiên |
| Nhiệt độ động cơ | UNAVAILABLE | Chưa có dữ liệu | Chưa xác định | — | Không | Nguồn dữ liệu + giả thuyết + tham chiếu |
| Tốc độ xe | UNAVAILABLE | Chưa có dữ liệu | Chưa xác định (byte 27/31/34 đổi khi nổ máy, chưa gán nghĩa) | — | Không | Chạy có GPS, tìm trường, tham chiếu |
| Điện áp ắc quy | DECODED_HYPOTHESIS | Chỉ là suy luận từ mẫu dữ liệu | Khung 0x6B byte 11 | 0,07 × byte 11 (V) — hệ số là ước lượng | Tài liệu cũ: giảm chậm khi tắt, tụt khi đề. Fixture: 0xB2 (12,46 V) lúc raw rpm = 0; 0xAA (11,90 V) lúc raw rpm = 1692 | Vạn năng ≥3 trạng thái; làm rõ mâu thuẫn (xem dưới) |
| Điện áp sạc | UNAVAILABLE | Chưa có dữ liệu | Chưa tách khỏi điện áp ắc quy | — | Không | Nguồn riêng hoặc xác nhận cùng trường |
| Trạng thái động cơ | UNAVAILABLE | Chưa có dữ liệu | Chưa xác định | — | Không | Giả thuyết + mốc nổ/tắt có nhãn |
| DTC | UNAVAILABLE | Không thể truy cập bằng phương thức hiện tại | Khung 0x6A có bảng kiểu DID nhưng chứa trường định danh và chưa được giải mã; không đoán lệnh vào ECU | — | Không | Phương thức chẩn đoán được hỗ trợ và có tài liệu |

Năm loại bằng chứng bắt buộc: *đã xác minh bằng nguồn chính thức* — 0 trường; *đã đối chiếu bằng phép đo độc lập* — 0; *chỉ suy luận từ mẫu* — RPM, điện áp ắc quy; *chưa có dữ liệu* — nhiệt độ, tốc độ, điện áp sạc, trạng thái; *không thể truy cập* — DTC.

## 2. Điều cần lưu ý
- Mâu thuẫn tiềm ẩn: nếu cả hai giả thuyết đều đúng thì khung "running" (rpm 1692) cho 11,90 V, thấp hơn 12,46 V lúc tắt máy, trái với mô tả "cao khi nổ" (~0xC3 ≈ 13,7 V). Hai khung không đủ để kết luận gì; có thể do đang đề/ngay sau nổ, hoặc hệ số/byte sai. **Không nâng cấp trạng thái.**
- Khung fixture là khung thật (đã che) từ `YccFrameTest`; thời gian trong fixture CSV chỉ là số thứ tự, không phải thời gian bắt thật.
- Test tổng hợp cho thấy engine có thể ra VERIFIED khi dữ liệu thỏa tiêu chí; đó là bằng chứng về **logic của công cụ**, không về xe.

## Phụ lục: kết quả sinh tự động (`tools/analyze-log.sh docs/samples/REAL_fixture_frames.csv`)

Trạng thái chỉ do bộ xác minh cấp theo tiêu chí đã khóa. Parser chạy được hoặc tổng kiểm hợp lệ KHÔNG đủ để VERIFIED.

## Vòng tua động cơ (RPM) (`rpm`, rpm)
- Trạng thái: **DECODED_HYPOTHESIS** — Giả thuyết giải mã
- Cấp bằng chứng: Chỉ là suy luận từ mẫu dữ liệu
- Lý do: Chỉ là giả thuyết giải mã: Dữ liệu tham chiếu độc lập (rpm) có timestamp, nguồn và cấp bằng chứng
- Giả thuyết `ycc.rpm.u16be@5`: giá trị = 1.0 × raw(2 byte big-endian không dấu @ byte 5) + 0.0 [rpm]; UUID `6e400003-b5a3-f393-e0a9-e50e24dcca9e`; khung 0x6B dài 59B; tổng kiểm SUM8(toàn gói) ≡ 0
  - Cơ sở: Giả thuyết từ mẫu: =0 khi chưa nổ, 676–3043 khi nổ, tương quan 0,97 với một trường trong khung 0x6C. Chưa đối chiếu với tham chiếu độc lập.
- Mẫu gần nhất: raw=1692 → 1692.000 rpm lúc 1970-01-01T00:00:06.000Z (phiên s1); payload `6B 05 00 01 21 06 9C 00 06 23 35 AA 23 00 00 00 10 00 00 74 58 61 00 CB 80 80 00 81 00 00 00 01 50 00 80 80 80 80 00 48 04 00 00 16 17 01 1D 01 FF 01 17 01 FF 01 19 01 FF 60 9E`
- Số mẫu giải mã: 3
- Còn thiếu bằng chứng:
  - Dữ liệu tham chiếu độc lập (rpm) có timestamp, nguồn và cấp bằng chứng
- Vân tay tiêu chí: 06c264171be85f0b

## Nhiệt độ động cơ (`engine_temp`, °C)
- Trạng thái: **UNAVAILABLE** — Chưa tìm thấy nguồn dữ liệu
- Cấp bằng chứng: Chưa có dữ liệu
- Lý do: Chưa tìm thấy nguồn dữ liệu cho trường này trong dữ liệu đã có.
- Số mẫu giải mã: 0
- Còn thiếu bằng chứng:
  - Nguồn dữ liệu truy cập được
  - Giả thuyết giải mã
- Vân tay tiêu chí: 7b11e5ec5a55eed9

## Tốc độ xe (`vehicle_speed`, km/h)
- Trạng thái: **UNAVAILABLE** — Chưa tìm thấy nguồn dữ liệu
- Cấp bằng chứng: Chưa có dữ liệu
- Lý do: Chưa tìm thấy nguồn dữ liệu cho trường này trong dữ liệu đã có.
- Số mẫu giải mã: 0
- Còn thiếu bằng chứng:
  - Nguồn dữ liệu truy cập được
  - Giả thuyết giải mã
- Vân tay tiêu chí: 11f2fac45225593f

## Điện áp ắc quy (`battery_voltage`, V)
- Trạng thái: **DECODED_HYPOTHESIS** — Giả thuyết giải mã
- Cấp bằng chứng: Chỉ là suy luận từ mẫu dữ liệu
- Lý do: Chỉ là giả thuyết giải mã: Dữ liệu tham chiếu độc lập (V) có timestamp, nguồn và cấp bằng chứng
- Giả thuyết `ycc.batt.u8@11x0.07`: giá trị = 0.07 × raw(1 byte big-endian không dấu @ byte 11) + 0.0 [V]; UUID `6e400003-b5a3-f393-e0a9-e50e24dcca9e`; khung 0x6B dài 59B; tổng kiểm SUM8(toàn gói) ≡ 0
  - Cơ sở: Giả thuyết từ mẫu: byte 11 giảm khi tắt máy, tụt khi đề, cao khi nổ. Hệ số 0,07 là ước lượng.
- Mẫu gần nhất: raw=170 → 11.900 V lúc 1970-01-01T00:00:06.000Z (phiên s1); payload `6B 05 00 01 21 06 9C 00 06 23 35 AA 23 00 00 00 10 00 00 74 58 61 00 CB 80 80 00 81 00 00 00 01 50 00 80 80 80 80 00 48 04 00 00 16 17 01 1D 01 FF 01 17 01 FF 01 19 01 FF 60 9E`
- Số mẫu giải mã: 3
- Còn thiếu bằng chứng:
  - Dữ liệu tham chiếu độc lập (V) có timestamp, nguồn và cấp bằng chứng
- Vân tay tiêu chí: d18a70bf9523af99

## Điện áp sạc (`charging_voltage`, V)
- Trạng thái: **UNAVAILABLE** — Chưa tìm thấy nguồn dữ liệu
- Cấp bằng chứng: Chưa có dữ liệu
- Lý do: Chưa tìm thấy nguồn dữ liệu cho trường này trong dữ liệu đã có.
- Số mẫu giải mã: 0
- Còn thiếu bằng chứng:
  - Nguồn dữ liệu truy cập được
  - Giả thuyết giải mã
- Vân tay tiêu chí: 18a763781537b489

## Trạng thái động cơ (`engine_status`, state)
- Trạng thái: **UNAVAILABLE** — Chưa tìm thấy nguồn dữ liệu
- Cấp bằng chứng: Chưa có dữ liệu
- Lý do: Chưa tìm thấy nguồn dữ liệu cho trường này trong dữ liệu đã có.
- Số mẫu giải mã: 0
- Còn thiếu bằng chứng:
  - Nguồn dữ liệu truy cập được
  - Giả thuyết giải mã
- Vân tay tiêu chí: ce93031d256b2bd2

## Mã lỗi chẩn đoán (DTC) (`dtc`, mã)
- Trạng thái: **UNAVAILABLE** — Chưa tìm thấy nguồn dữ liệu
- Cấp bằng chứng: Không thể truy cập bằng phương thức hiện tại
- Lý do: Chưa có phương thức chẩn đoán được hỗ trợ và có tài liệu cho xe này; app không đoán lệnh vào ECU.
- Số mẫu giải mã: 0
- Còn thiếu bằng chứng:
  - Phương thức xác minh được hỗ trợ cho trường này
- Vân tay tiêu chí: (không có tiêu chí)


