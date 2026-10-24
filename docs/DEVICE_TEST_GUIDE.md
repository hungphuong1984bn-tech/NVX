# Hướng dẫn kiểm thử trên thiết bị thật (0.9.0)

An toàn: mọi bước thực hiện khi xe **đã dừng ở nơi an toàn**, chống chân, không thao tác khi lái.

1. **Build**: đẩy mã lên GitHub → Actions → Android CI → tải `nvx-connect-debug-apk`; hoặc Android Studio (JDK 17). Nếu có lỗi biên dịch Compose, gửi log.
2. **Trung tâm hướng dẫn** (Cài đặt → Trung tâm hướng dẫn): tìm "ket noi" (không dấu), mở từng bài, thử nút hành động, đánh dấu một bài thủ tục, đặt lại tiến độ.
3. **Quy trình kiểm tra**: tắt Bluetooth → bước 1 "CHƯA ĐẠT", các bước sau "CHƯA KIỂM TRA". Bật lại → đạt. Từ chối quyền → bước 2 chưa đạt, nút "Tìm cách khắc phục" mở bài "Cấp quyền Android".
4. **Quét/kết nối** (Kết nối → Tự tìm xe): quét xe TẮT rồi BẬT; kết nối thử; ghi Y-Connect có đang chạy không. Mong đợi: "CONNECTED — RAW DATA ONLY", bước "Đã xác minh giao thức" = CHƯA ĐẠT.
5. **Lỗi**: đưa điện thoại ra xa/đóng xe để gây timeout; kiểm tra "Tìm cách khắc phục" mở bài đúng.
6. **Dữ liệu xe** (tab Xe → Hồ sơ): mọi dòng UNAVAILABLE, không có số nào.
7. **TFT**: trạng thái "TFT chưa được hỗ trợ"; dẫn đường chạy trên điện thoại nhưng mức "xác nhận hiển thị trên TFT" = CHƯA.
8. **Xuất báo cáo**: Phân tích giao thức → xuất; kiểm tra MAC đã bị che.
9. **Xóa dữ liệu**: Cài đặt → Xóa toàn bộ dữ liệu; tiến độ hướng dẫn cũng về 0.
10. Điền `docs/TEST_CHECKLIST.md` và `docs/test-templates/TEST_LOG.csv`.
