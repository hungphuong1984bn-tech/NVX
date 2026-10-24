# Mẫu hồ sơ bằng chứng giao thức (điền trước khi viết adapter)
Adapter chỉ được đăng ký (`AdapterRegistry.register`) khi đủ mọi mục dưới đây, nếu thiếu thì app ném lỗi.
- id, phiên bản giao thức:
- Biến thể xe, số hiệu CCU/firmware, phiên bản app Y-Connect (nếu có), ngày thử:
- Service UUID và characteristic UUID (lấy từ GATT discovery thật):
- Định dạng gói, độ dài, byte order, đơn vị, hệ số, checksum:
- Mẫu gói gốc ĐÃ ẨN (FrameRedactor.hexRedacted), ít nhất vài mẫu ở các trạng thái khác nhau:
- So sánh với đồng hồ xe/phép đo độc lập: giá trị đọc được vs thực tế, sai số, tần suất cập nhật:
- Xe đã thử (testedOnVehicle) và kết quả (testResult):
- Chưa giải mã được trường nào thì để RAW/UNKNOWN, không đặt tên.
