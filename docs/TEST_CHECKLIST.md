# Checklist thử nghiệm trên thiết bị Android thật

Quy tắc: chỉ tick khi đã tự thử; mục không thử được ghi "CHƯA THỬ". Ghi mỗi lần thử vào `test-templates/TEST_LOG.csv`.
Chỉ thử trên xe của chính bạn, xe đứng yên khi khảo sát kết nối. **Không thao tác điện thoại khi đang lái.**

## 0. Thông tin buổi thử
- [ ] Ngày, người thử, điện thoại (hãng/model), phiên bản Android, versionName + commit
- [ ] Xe (chỉ cho phần B): biến thể, năm, CCU/firmware nếu biết, có đang ghép Y-Connect không

# PHẦN A. BLE thông thường (không cần xe) — dùng tai nghe/đồng hồ/beacon BLE bất kỳ

## A1. Quyền và điều kiện
| Mục | Android 8-11 | Android 12-13 | Android 14+ |
|---|---|---|---|
| Quét khi chưa cấp quyền: hỏi đúng loại (8-11: Vị trí; 12+: Thiết bị ở gần) | | | |
| Từ chối quyền: báo rõ + nút Cấp quyền/Mở cài đặt, không crash | | | |
| Tắt Bluetooth rồi quét: báo "Bluetooth đang tắt" + nút mở cài đặt | | | |
| Android 8-11, tắt Vị trí hệ thống: báo cần bật Vị trí | | | |
| Thu hồi quyền khi đang chạy: không crash | | | |
## A2. Quét
- [ ] Thấy thiết bị, tên/RSSI/kết nối được/UUID hiển thị; thiết bị không tên hiển thị "(không tên)"
- [ ] Dừng quét; tự dừng sau 60 s; bấm Quét liên tục >5 lần/30 s: báo lỗi "quét quá dày", không crash
- [ ] Tìm kiếm theo tên/địa chỉ/UUID; từng bộ lọc hoạt động; Lưu thiết bị; lọc "Đã lưu"
- [ ] Thiết bị đổi địa chỉ riêng tư (điện thoại khác đang quảng bá): thiết bị đã lưu vẫn được nhận lại theo tên
## A3. Kết nối GATT
- [ ] Trình tự trạng thái hiện đúng: Connecting → Connected → Discovering → Ready (xem Nhật ký)
- [ ] Bấm Kết nối hai lần liên tiếp: chỉ một phiên; kết nối thiết bị thứ hai: phiên cũ đóng
- [ ] Đi xa/tắt thiết bị khi đang kết nối: "Đang kết nối lại n/5" rồi báo lỗi rõ, không treo
- [ ] Tắt Bluetooth khi đang kết nối: báo lỗi Bluetooth tắt
- [ ] Ngắt kết nối: về DISCONNECTED; mở lại app/xoay màn hình không tạo phiên thứ hai
- [ ] Cây GATT: service/characteristic/descriptor và thuộc tính READ/WRITE/WRITE_NO_RESP/NOTIFY/INDICATE đúng
- [ ] Nghe: trạng thái "Chờ xác nhận" rồi "Đang nghe (đã xác nhận)"; Ngừng nghe; có gói HEX hiện ra
- [ ] Đọc thông tin chuẩn (tên/hãng/firmware) nếu thiết bị có; characteristic khác KHÔNG có nút đọc
- [ ] Xuất JSON/CSV: mở được, địa chỉ bị băm, không còn tên thật, CSV không chạy công thức khi mở bằng bảng tính
- [ ] Xác nhận không có thao tác ghi nào ngoài CCCD (Nhật ký)

# PHẦN B. Yamaha NVX 155 V3 SP (cần xe thật, đứng yên, tắt máy)
- [ ] Quét 2 phút khi chìa tắt; bật chìa/khởi động; quét lại; ghi thiết bị xuất hiện/mất
- [ ] Thiết bị nghi của xe: tên, RSSI ở 0,5/2/5 m, connectable
- [ ] Thử kết nối: thành công hay lỗi (ghi status). Nếu AUTH_REQUIRED/ghép nối: DỪNG, ghi lại, không cố vượt qua
- [ ] Nếu Ready: ghi cây GATT, thông tin chuẩn (firmware), nghe notify khi bật/tắt khóa điện, xuất log đã che
- [ ] Màn hình hiển thị đúng "CONNECTED — RAW DATA ONLY" + "VEHICLE PROTOCOL NOT VERIFIED"; RPM/nhiệt độ/điện áp/DTC = "Chưa có dữ liệu"
- [ ] KHÔNG: gửi lệnh, ghi characteristic, can thiệp ghép nối Y-Connect, thử trên xe người khác
- [ ] Kết luận (nêu bằng chứng): thiết bị là xe / điện thoại khác / lạ / chưa kết luận được
- [ ] TFT: xác nhận app báo "KHÔNG HỖ TRỢ" và xe không có hiển thị lạ

# PHẦN C. Bản đồ, GPS, hành trình, dẫn đường (ngoài trời, KHÔNG lái khi thao tác)
- [ ] Mở tab Bản đồ: xin quyền vị trí; từ chối: báo rõ; cấp "gần đúng" Android 12+: báo cần "chính xác"
- [ ] GPS tắt: báo + nút mở cài đặt; trong nhà: "Đang tìm/ yếu" không crash
- [ ] Bản đồ hiện, vị trí + mũi tên hướng; tắt mạng: banner offline, ô đã xem vẫn hiện
- [ ] Tìm địa điểm; nhập "vĩ độ, kinh độ"; mất mạng khi tìm: báo lỗi; bấm Tìm dồn dập: không bị chặn 429 (có giãn cách)
- [ ] Tính tuyến: khoảng cách/thời gian ước tính/các bước; cảnh báo hồ sơ ô tô hiển thị
- [ ] Bắt đầu dẫn đường (đi bộ/xe đạp để thử): nhắc rẽ bằng giọng 300/100/30 m đúng một lần mỗi mức
- [ ] Cố tình đi lệch: báo lệch tuyến, tính lại; tắt mạng lúc lệch: báo lỗi, thử lại, không bịa tuyến
- [ ] Đến nơi: báo đến nơi
- [ ] Giọng nói: máy không có giọng vi-VN → hiện hướng dẫn cài, không đọc sai tiếng
- [ ] Tạm dừng/tiếp tục: quãng đường không nhảy qua đoạn tạm dừng
- [ ] Tắt màn hình 5 phút khi đang ghi: thông báo dịch vụ nền hiện, hành trình vẫn tiếp tục (Android 12/13/14)
- [ ] Vuốt tắt app khi đang ghi rồi mở lại: chuyến dở dang được khôi phục (không mất toàn bộ)
- [ ] Kết thúc: lưu, mở lại xem đường đi, xóa hành trình
- [ ] Ngưỡng tốc độ: vượt ngưỡng có âm cảnh báo (khi bật), không lặp dồn dập

# PHẦN D. Dữ liệu người dùng
- [ ] Hồ sơ xe: nhập sai ngày mua/dung tích bị báo lỗi; lưu và mở lại còn nguyên
- [ ] Xăng: nhập sai bị báo; 2 lần đổ đầy cho L/100km hợp lý; trùng ODO bị chặn
- [ ] Bảo dưỡng: còn hạn/sắp/quá hạn/hoàn thành (việc một lần) đúng; "Vừa làm xong" cập nhật
- [ ] DEMO bật/tắt: dữ liệu DEMO có nhãn, tắt thì biến mất, không lẫn vào số liệu thật
- [ ] Xuất dữ liệu JSON; Xóa toàn bộ dữ liệu: sạch cả hành trình/xăng/bảo dưỡng/cài đặt/thiết bị đã lưu
