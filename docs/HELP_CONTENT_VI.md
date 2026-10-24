# Nội dung Trung tâm hướng dẫn (tiếng Việt)

> Tệp này được SINH TỰ ĐỘNG từ `app/src/main/java/vn/nvxconnect/helpcenter/HelpContent.kt` (`tools/generate-help-md.sh`). Không sửa tay; sửa nguồn rồi sinh lại.

**An toàn: chỉ cấu hình, kết nối hoặc chẩn đoán khi xe đã dừng ở nơi an toàn và tắt máy/chống chân. Không thao tác điện thoại khi đang lái xe.**

## Mục lục
1. [Bắt đầu sử dụng NVX Connect](#bai-1) — Bắt đầu
2. [Chuẩn bị điện thoại và bật Bluetooth](#bai-2) — Bắt đầu
3. [Cấp quyền Android](#bai-3) — Bắt đầu
4. [Quét và chọn thiết bị](#bai-4) — Kết nối
5. [Kết nối Bluetooth và giải thích các trạng thái](#bai-5) — Kết nối
6. [Kiểm tra dịch vụ GATT](#bai-6) — Kết nối
7. [Đọc và xác minh dữ liệu xe](#bai-7) — Dữ liệu xe
8. [Sử dụng GPS và lưu hành trình](#bai-8) — GPS & dẫn đường
9. [Kết nối Y-Connect theo hướng dẫn chính thức](#bai-9) — Kết nối
10. [Sử dụng TFT và dẫn đường](#bai-10) — GPS & dẫn đường
11. [Xử lý lỗi không tìm thấy xe](#bai-11) — Xử lý sự cố
12. [Xử lý mất kết nối hoặc không có dữ liệu](#bai-12) — Xử lý sự cố
13. [Xuất báo cáo chẩn đoán để hỗ trợ kỹ thuật](#bai-13) — Xử lý sự cố
14. [Quản lý quyền riêng tư và xóa dữ liệu đã lưu](#bai-14) — Dữ liệu & riêng tư

<a id="bai-1"></a>
## 1. Bắt đầu sử dụng NVX Connect

*Nhóm: Bắt đầu · id: `bat-dau` · nút hành động: Mở mục Xe (hồ sơ, bảo dưỡng) · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt)*

**Mục đích.** Hiểu app làm được gì và chưa làm được gì trước khi kết nối với xe.

**Điều kiện trước khi thực hiện**
- Điện thoại Android 8.0 trở lên có Bluetooth Low Energy.
- Xe đã dừng ở nơi an toàn.

**Các bước**
1. Mở app, đọc màn hình chào và chọn Bắt đầu.
2. Vào tab Xe để nhập hồ sơ xe và ODO hiện tại (số này do bạn nhập, app không đọc từ xe).
3. Vào tab Kết nối để làm theo các bước: cấp quyền, quét, kết nối.
4. Xem mục Chẩn đoán (Phân tích giao thức) để biết app đã xác minh được gì.

**Kết quả mong đợi.** Bạn nắm được: bản đồ, hành trình, nhiên liệu, bảo dưỡng chạy độc lập bằng GPS và dữ liệu bạn nhập; số liệu từ xe CHỈ xuất hiện khi giao thức đã được xác minh.

**Lỗi thường gặp**
- Nghĩ rằng kết nối Bluetooth thành công nghĩa là app đọc được RPM/nhiệt độ.
- Nghĩ rằng app đã hiển thị chỉ đường lên màn hình TFT.

**Cách khắc phục**
- Xem nhãn trạng thái: "CONNECTED — RAW DATA ONLY" nghĩa là mới nhận dữ liệu thô, chưa giải mã.
- Dẫn đường hiện chỉ chạy trên điện thoại; xem bài "Sử dụng TFT và dẫn đường".

<a id="bai-2"></a>
## 2. Chuẩn bị điện thoại và bật Bluetooth

*Nhóm: Bắt đầu · id: `chuan-bi-dien-thoai` · nút hành động: Mở cài đặt Bluetooth của Android · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt) · hiển thị cảnh báo an toàn*

**Mục đích.** Bảo đảm điện thoại sẵn sàng quét và kết nối BLE.

**Điều kiện trước khi thực hiện**
- Điện thoại còn pin; Bluetooth hoạt động bình thường.

**Các bước**
1. Vuốt thanh trạng thái và bật Bluetooth.
2. Android 11 trở xuống: bật thêm Vị trí (GPS) của hệ thống, vì hệ điều hành yêu cầu để quét BLE.
3. Tắt chế độ tiết kiệm pin mạnh cho NVX Connect nếu bạn muốn ghi hành trình lâu.
4. Đưa điện thoại lại gần xe (vài mét), bật khóa điện nếu bạn đang quét thử xe.

**Kết quả mong đợi.** Bước "Bluetooth khả dụng" trong Quy trình kiểm tra chuyển sang đạt.

**Lỗi thường gặp**
- Bluetooth bật nhưng app vẫn báo tắt.
- Android 11 trở xuống: quét không ra gì dù đã cấp quyền.

**Cách khắc phục**
- Tắt rồi bật lại Bluetooth, sau đó quay lại app.
- Bật Vị trí của hệ thống (không chỉ cấp quyền cho app).

<a id="bai-3"></a>
## 3. Cấp quyền Android

*Nhóm: Bắt đầu · id: `cap-quyen` · nút hành động: Mở cài đặt quyền của ứng dụng · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt) · hiển thị cảnh báo an toàn*

**Mục đích.** Cấp đúng quyền tối thiểu để quét và kết nối Bluetooth, ghi hành trình.

**Điều kiện trước khi thực hiện**
- Đã bật Bluetooth.

**Các bước**
1. Android 12 trở lên: khi được hỏi, cho phép "Thiết bị ở gần" (BLUETOOTH_SCAN và BLUETOOTH_CONNECT). Không cần quyền vị trí để quét BLE.
2. Android 8–11: cho phép Vị trí khi dùng app (bắt buộc để quét BLE).
3. Muốn ghi hành trình bằng GPS: cho phép Vị trí chính xác khi dùng app; Android 14 có thể yêu cầu cấp trước khi bật dịch vụ nền.
4. Nếu đã từ chối hai lần: vào Cài đặt ứng dụng → Quyền để bật lại.

**Kết quả mong đợi.** Bước "Đã cấp quyền" đạt; nút Quét hoạt động.

**Lỗi thường gặp**
- Chọn "Không cho phép" nên không quét được.
- Hộp thoại quyền không hiện lại.

**Cách khắc phục**
- Dùng nút "Mở cài đặt quyền của ứng dụng" bên dưới rồi bật quyền thủ công.
- Quay lại app và bấm Quét lại.

<a id="bai-4"></a>
## 4. Quét và chọn thiết bị

*Nhóm: Kết nối · id: `quet-chon-thiet-bi` · nút hành động: Mở Tự tìm xe · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt) · hiển thị cảnh báo an toàn*

**Mục đích.** Tìm thiết bị Bluetooth của xe và chọn đúng thiết bị.

**Điều kiện trước khi thực hiện**
- Bluetooth bật, đã cấp quyền.
- Xe đã bật khóa điện và ở gần; xe dừng ở nơi an toàn.

**Các bước**
1. Vào tab Kết nối và bấm Quét (hoặc dùng Tự tìm xe).
2. Đợi vài giây; danh sách hiện tên, cường độ tín hiệu (RSSI) và thiết bị kết nối được.
3. Mẹo nhận diện: quét lúc xe TẮT rồi lúc xe BẬT; thiết bị chỉ xuất hiện khi xe bật là ứng viên.
4. Chọn thiết bị rồi bấm Kết nối. Có thể lưu thiết bị để lần sau tự nhận.

**Kết quả mong đợi.** Bước "Tìm thấy thiết bị" đạt. Lưu ý: app xếp hạng ứng viên nhưng không khẳng định đó là xe Yamaha.

**Lỗi thường gặp**
- Không thấy thiết bị nào.
- Có nhiều thiết bị không tên, không biết chọn cái nào.
- Địa chỉ thiết bị đổi sau mỗi lần quét.

**Cách khắc phục**
- Xem bài "Xử lý lỗi không tìm thấy xe".
- Dùng bộ lọc "Có tên" và so sánh quét lúc xe tắt/bật.
- Thiết bị đã lưu được nhận lại theo tên khi địa chỉ xoay.

<a id="bai-5"></a>
## 5. Kết nối Bluetooth và giải thích các trạng thái

*Nhóm: Kết nối · id: `ket-noi-trang-thai` · nút hành động: Mở màn hình Kết nối · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt) · hiển thị cảnh báo an toàn*

**Mục đích.** Biết mỗi trạng thái kết nối nghĩa là gì, để không hiểu nhầm.

**Điều kiện trước khi thực hiện**
- Đã chọn một thiết bị ở bước quét.

**Các bước**
1. Bấm Kết nối. App chờ tối đa 15 giây cho mỗi lần thử và tự thử lại có giới hạn khi mất kết nối.
2. Đang kết nối: ĐANG KẾT NỐI… (chưa phải đã kết nối).
3. ĐÃ NỐI GATT: đã nối và khám phá xong dịch vụ; chưa chứng minh đó là xe, chưa xác thực.
4. ĐÃ GHÉP ĐÔI (BONDED): Android báo đã ghép đôi; KHÔNG chứng minh xe đã xác thực app.
5. ĐÃ XÁC MINH GIAO THỨC / CÓ DỮ LIỆU XE: chỉ khi có adapter đã xác minh trên xe thật. Hiện chưa có adapter nào.

**Kết quả mong đợi.** Bạn đọc đúng trạng thái. "CONNECTED — RAW DATA ONLY" là kết quả bình thường ở bản này.

**Lỗi thường gặp**
- LỖI KẾT NỐI / hết thời gian chờ.
- XE ĐANG BẬN VỚI ỨNG DỤNG KHÁC.
- Thiết bị yêu cầu ghép đôi/xác thực.

**Cách khắc phục**
- Hết thời gian: lại gần xe, bật khóa điện, thử lại.
- Bận: đóng Y-Connect (và tắt Bluetooth của nó) rồi thử; app không chiếm kết nối.
- Cần xác thực: app không đoán PIN và không vượt xác thực; chỉ ghép đôi qua hộp thoại Android.

<a id="bai-6"></a>
## 6. Kiểm tra dịch vụ GATT

*Nhóm: Kết nối · id: `kiem-tra-gatt` · nút hành động: Mở màn hình Kết nối · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt) · hiển thị cảnh báo an toàn*

**Mục đích.** Xem các service/characteristic mà thiết bị công bố, để phục vụ khảo sát.

**Điều kiện trước khi thực hiện**
- Trạng thái đã nối GATT (READY).
- Xe dừng an toàn.

**Các bước**
1. Trong màn hình Kết nối, mở mục Nâng cao → cây GATT.
2. Đọc UUID, thuộc tính (Read/Write/Notify/Indicate) và descriptor của từng characteristic.
3. Chỉ bấm "Nghe" ở characteristic có Notify/Indicate khi bạn muốn quan sát; thao tác này chỉ ghi descriptor CCCD để bật thông báo.
4. App KHÔNG có nút ghi dữ liệu hoặc gửi lệnh vào xe.

**Kết quả mong đợi.** Thấy danh sách dịch vụ. UUID riêng của hãng KHÔNG được app gán nhãn nếu chưa có bằng chứng.

**Lỗi thường gặp**
- Danh sách dịch vụ trống.
- Nghe nhưng không có gói nào.

**Cách khắc phục**
- Ngắt và kết nối lại; kiểm tra xe có đang bị ứng dụng khác giữ kết nối không.
- Một số characteristic cần xác thực; app dừng và báo, không tìm cách vượt qua.

<a id="bai-7"></a>
## 7. Đọc và xác minh dữ liệu xe

*Nhóm: Dữ liệu xe · id: `doc-xac-minh-du-lieu` · nút hành động: Mở Danh mục chức năng · bài tham khảo, không có trạng thái hoàn thành · hiển thị cảnh báo an toàn*

**Mục đích.** Hiểu nhãn trạng thái của từng giá trị và vì sao nhiều giá trị là "Chưa có dữ liệu".

**Điều kiện trước khi thực hiện**
- Đã nối GATT.
- Muốn có số liệu xe: cần giao thức đã xác minh (hiện chưa có).

**Các bước**
1. Mở Phân tích giao thức → Danh mục chức năng để xem từng chức năng xe.
2. VERIFIED: giá trị đến từ adapter đã xác minh, còn mới (≤ 5 giây) và nằm trong khoảng hợp lệ.
3. STALE: từng nhận nhưng đã cũ; UNVERIFIED: nhận được nhưng chưa đủ điều kiện (ví dụ ngoài khoảng); UNAVAILABLE: chưa có; UNSUPPORTED: giao thức đã xác minh khai báo không cung cấp.
4. Mỗi giá trị hiển thị kèm nguồn: XE / GPS ước tính / Bạn nhập / Tính toán.

**Kết quả mong đợi.** Không có số liệu xe nào hiển thị nếu chưa xác minh. Tốc độ GPS không bao giờ được dùng thay tốc độ xe.

**Lỗi thường gặp**
- Tất cả đều "Chưa có dữ liệu".
- Số liệu có nhãn STALE.

**Cách khắc phục**
- Đây là hành vi đúng khi chưa có giao thức xe đã xác minh; xuất báo cáo khảo sát để phục vụ phân tích.
- STALE: kiểm tra kết nối còn không; giá trị cũ không được hiển thị như số hiện tại.

<a id="bai-8"></a>
## 8. Sử dụng GPS và lưu hành trình

*Nhóm: GPS & dẫn đường · id: `gps-hanh-trinh` · nút hành động: Mở Hành trình · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt) · hiển thị cảnh báo an toàn*

**Mục đích.** Ghi hành trình bằng GPS điện thoại và xem lại lịch sử.

**Điều kiện trước khi thực hiện**
- Đã cấp quyền vị trí.
- Bắt đầu/kết thúc ghi khi xe đã dừng; đặt điện thoại cố định trước khi chạy.

**Các bước**
1. Vào tab Hành trình và bấm "Bắt đầu hành trình" khi xe đang đứng yên.
2. Cất/cố định điện thoại; không thao tác khi đang lái.
3. Dừng xe ở nơi an toàn rồi mới tạm dừng/kết thúc.
4. Xem lại quãng đường, thời gian, tốc độ trung bình/tối đa trong lịch sử.

**Kết quả mong đợi.** Hành trình được lưu với nhãn "GPS ước tính" (không phải ODO hay tốc độ của xe).

**Lỗi thường gặp**
- Quãng đường lệch so với đồng hồ xe.
- Ghi dừng khi tắt màn hình.

**Cách khắc phục**
- Đây là khoảng cách GPS nên có sai lệch; ODO do bạn nhập mới là số của bạn.
- Cho phép chạy nền và thông báo dịch vụ vị trí; tắt tiết kiệm pin cho app.

<a id="bai-9"></a>
## 9. Kết nối Y-Connect theo hướng dẫn chính thức

*Nhóm: Kết nối · id: `y-connect` · nút hành động: Mở Danh mục chức năng · bài tham khảo, không có trạng thái hoàn thành · hiển thị cảnh báo an toàn*

**Mục đích.** Biết mối quan hệ giữa NVX Connect và ứng dụng Y-Connect của Yamaha.

**Điều kiện trước khi thực hiện**
- Xe có hỗ trợ Y-Connect theo tài liệu của Yamaha (đối chiếu sổ tay/đại lý).
- Xe dừng ở nơi an toàn.

**Các bước**
1. Cài và ghép đôi Y-Connect HOÀN TOÀN theo hướng dẫn trong sổ tay xe hoặc trang chính thức của Yamaha. NVX Connect không thay thế và không hướng dẫn thay quy trình đó.
2. Y-Connect thường giữ kết nối độc quyền với xe. Nếu muốn NVX Connect khảo sát, đóng Y-Connect trước.
3. NVX Connect không vượt qua xác thực của Y-Connect và không chen vào kết nối chính thức.
4. Khả năng tương thích của NVX 155 V3 SP 2025 với giao thức trong app: CHƯA xác minh.

**Kết quả mong đợi.** Y-Connect chính thức hoạt động bình thường; NVX Connect báo rõ khi xe đang bận.

**Lỗi thường gặp**
- Mở cả hai app cùng lúc và một trong hai không kết nối được.
- Kỳ vọng NVX Connect dùng chung phiên của Y-Connect.

**Cách khắc phục**
- Dùng lần lượt: đóng app kia trước khi mở app này.
- Cần tích hợp đầy đủ: phải có tài liệu/SDK được Yamaha cho phép.

<a id="bai-10"></a>
## 10. Sử dụng TFT và dẫn đường

*Nhóm: GPS & dẫn đường · id: `tft-dan-duong` · nút hành động: Mở Hiển thị trên màn hình xe · bài tham khảo, không có trạng thái hoàn thành · hiển thị cảnh báo an toàn*

**Mục đích.** Hiểu rõ dẫn đường đang chạy ở đâu và TFT có được hỗ trợ hay không.

**Điều kiện trước khi thực hiện**
- Đã chọn điểm đến trên Bản đồ.
- Chỉ đặt điểm đến khi xe đã dừng ở nơi an toàn.

**Các bước**
1. Vào Bản đồ, tìm địa điểm hoặc nhập tọa độ, chọn tuyến và bắt đầu trước khi xuất phát.
2. Chỉ dẫn từng chặng (kể cả giọng nói) CHẠY TRÊN ĐIỆN THOẠI.
3. Mở "Hiển thị trên màn hình xe" để xem trạng thái TFT: hiện là KHÔNG HỖ TRỢ vì chưa có giao thức/API được Yamaha cho phép và chưa xác minh trên NVX 155 V3 SP.
4. App không gửi gì lên TFT và không giả lập việc hiển thị.

**Kết quả mong đợi.** Dẫn đường hoạt động trên điện thoại; màn hình TFT báo rõ chưa hỗ trợ.

**Lỗi thường gặp**
- Chỉ dẫn không hiện trên TFT của xe.
- Không có giọng nói tiếng Việt.

**Cách khắc phục**
- Đây là giới hạn đã biết, không phải lỗi kết nối.
- Cài/bật giọng vi-VN trong cài đặt chuyển văn bản thành giọng nói của Android.

<a id="bai-11"></a>
## 11. Xử lý lỗi không tìm thấy xe

*Nhóm: Xử lý sự cố · id: `khong-tim-thay-xe` · nút hành động: Mở Tự tìm xe · bài tham khảo, không có trạng thái hoàn thành · hiển thị cảnh báo an toàn*

**Mục đích.** Khắc phục khi quét không thấy thiết bị của xe.

**Điều kiện trước khi thực hiện**
- Xe dừng ở nơi an toàn; điện thoại gần xe.

**Các bước**
1. Kiểm tra Bluetooth bật và đã cấp quyền (Android 11 trở xuống: bật cả Vị trí).
2. Bật khóa điện xe và quét lại; thử cả lúc xe tắt để so sánh.
3. Đóng Y-Connect và mọi ứng dụng Bluetooth khác đang kết nối với xe.
4. Tắt/bật Bluetooth điện thoại, rồi quét lại trong 30–60 giây.

**Kết quả mong đợi.** Thiết bị ứng viên xuất hiện trong danh sách quét.

**Lỗi thường gặp**
- Xe không phát quảng bá khi đang kết nối với máy khác.
- Thiết bị không tên bị ẩn bởi bộ lọc.

**Cách khắc phục**
- Bỏ bộ lọc "Có tên"; xem tổng số thiết bị đã thấy.
- Nếu vẫn không thấy: có thể xe không dùng BLE (có thể là Bluetooth Classic) hoặc không hỗ trợ — xuất báo cáo và liên hệ hỗ trợ.

<a id="bai-12"></a>
## 12. Xử lý mất kết nối hoặc không có dữ liệu

*Nhóm: Xử lý sự cố · id: `mat-ket-noi-khong-du-lieu` · nút hành động: Mở màn hình Kết nối · bài tham khảo, không có trạng thái hoàn thành · hiển thị cảnh báo an toàn*

**Mục đích.** Khắc phục khi đang nối thì mất kết nối, hoặc nối được nhưng không có số liệu.

**Điều kiện trước khi thực hiện**
- Xe dừng ở nơi an toàn.

**Các bước**
1. Xem trạng thái hiện tại ở màn hình Kết nối (ĐANG KẾT NỐI LẠI, LỖI KẾT NỐI, hay RAW DATA ONLY).
2. App tự thử lại có giới hạn; khi hết số lần sẽ báo lỗi, hãy bấm Kết nối lại.
3. Nếu nối được nhưng "Chưa có dữ liệu": kiểm tra bước "Đã xác minh giao thức" — thiếu adapter đã xác minh thì không có số liệu xe.
4. Kiểm tra pin điện thoại, khoảng cách và việc ứng dụng khác chiếm kết nối.

**Kết quả mong đợi.** Kết nối phục hồi, hoặc bạn biết chính xác bước nào chưa đạt.

**Lỗi thường gặp**
- Mất kết nối khi tắt màn hình.
- Giá trị chuyển STALE.

**Cách khắc phục**
- Tắt tối ưu hóa pin cho app; giữ điện thoại gần xe.
- STALE nghĩa là dữ liệu cũ; không dùng số đó như hiện tại.

<a id="bai-13"></a>
## 13. Xuất báo cáo chẩn đoán để hỗ trợ kỹ thuật

*Nhóm: Xử lý sự cố · id: `xuat-bao-cao-chan-doan` · nút hành động: Mở Phân tích giao thức / Chẩn đoán · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt) · hiển thị cảnh báo an toàn*

**Mục đích.** Tạo báo cáo JSON/CSV/văn bản để gửi cho người hỗ trợ.

**Điều kiện trước khi thực hiện**
- Đã chạy khảo sát hoặc gặp lỗi cần báo cáo.
- Thực hiện khi xe dừng an toàn.

**Các bước**
1. Vào Phân tích giao thức (Chẩn đoán).
2. Chọn xuất JSON, CSV hoặc Báo cáo văn bản.
3. Báo cáo đã che chuỗi có thể là mã định danh (VIN/ID) và địa chỉ thiết bị; vẫn nên đọc lại trước khi gửi.
4. Gửi qua ứng dụng bạn chọn (chia sẻ của Android).

**Kết quả mong đợi.** Có tệp báo cáo gồm thời gian, trạng thái, mã lỗi và dữ liệu cần thiết.

**Lỗi thường gặp**
- Không có nút chia sẻ.
- Báo cáo ít thông tin.

**Cách khắc phục**
- Chạy lại khảo sát có kết nối trước khi xuất.
- Ghi chú hành động (khởi động, tăng ga khi xe dựng chân chống) để báo cáo hữu ích hơn — chỉ khi an toàn.

<a id="bai-14"></a>
## 14. Quản lý quyền riêng tư và xóa dữ liệu đã lưu

*Nhóm: Dữ liệu & riêng tư · id: `quyen-rieng-tu-xoa-du-lieu` · nút hành động: Mở Cài đặt · có thể đánh dấu hoàn thành (bài có kiểm tra tự động chỉ được đánh dấu khi bước kiểm tra đạt)*

**Mục đích.** Biết app lưu gì trên máy và cách xóa.

**Điều kiện trước khi thực hiện**
- Không yêu cầu.

**Các bước**
1. Dữ liệu lưu cục bộ trên điện thoại: hành trình, nhiên liệu, bảo dưỡng, hồ sơ xe, thiết bị đã lưu, tiến độ hướng dẫn.
2. Mở Cài đặt → "Xóa toàn bộ dữ liệu" (có hộp thoại xác nhận).
3. Đặt lại tiến độ hướng dẫn ở Trung tâm hướng dẫn (nút Đặt lại).
4. Báo cáo xuất ra chỉ rời máy khi bạn chủ động chia sẻ.

**Kết quả mong đợi.** Dữ liệu bị xóa khỏi máy; không có dữ liệu nào tự gửi đi nơi khác.

**Lỗi thường gặp**
- Quên xóa tệp báo cáo đã xuất.

**Cách khắc phục**
- Xóa tệp trong ứng dụng đã nhận báo cáo/thư mục tải xuống.
- Gỡ cài đặt app cũng xóa dữ liệu cục bộ.

