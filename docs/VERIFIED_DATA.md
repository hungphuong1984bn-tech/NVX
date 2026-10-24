# Danh sách dữ liệu đã xác minh / chưa xác minh (0.9.0)

**Đã xác minh từ xe thật: KHÔNG CÓ GIÁ TRỊ NÀO.** Chưa có adapter giao thức xe nào đạt yêu cầu bằng chứng (`AdapterRegistry.register` từ chối nếu thiếu thử nghiệm trên xe).

| Dữ liệu | Trạng thái | Nguồn hiển thị |
|---|---|---|
| Tốc độ, RPM, nhiệt độ động cơ, nhiệt độ khí nạp*, điện áp ắc quy, nhiên liệu, ODO, quãng đường xe, khóa điện, mã lỗi, bảo dưỡng xe | UNAVAILABLE (chưa xác minh) | "Chưa có dữ liệu" |
| Dẫn đường lên TFT / điều khiển TFT | UNSUPPORTED | — |
| Tốc độ/quãng đường GPS | Có, nhưng là **GPS ước tính** của điện thoại, không phải của xe | GPS ước tính |
| ODO, hồ sơ xe, đổ xăng, bảo dưỡng | Do người dùng nhập | Bạn nhập |
| L/100km, tầm chạy, nhắc bảo dưỡng | Tính toán từ dữ liệu người dùng; không phải chẩn đoán ECU | Tính toán |
| Thông số kỹ thuật NVX 155 | Tham khảo tài liệu công bố; không đọc từ xe | Tham khảo |

\* Nhiệt độ khí nạp: đề bài yêu cầu hỗ trợ; `VehicleParam` hiện **chưa có** tham số này. Chưa thêm vì chưa có nguồn xác minh (thêm khi có adapter khai báo).
