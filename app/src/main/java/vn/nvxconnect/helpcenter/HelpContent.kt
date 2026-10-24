package vn.nvxconnect.helpcenter

import vn.nvxconnect.helpcenter.HelpAction.*
import vn.nvxconnect.helpcenter.HelpCategory.*

/**
 * Nội dung hướng dẫn tiếng Việt. Nguyên tắc biên soạn:
 *  - Chỉ mô tả hành vi CÓ THẬT của app ở bản này. Không hứa dữ liệu xe/TFT khi chưa có giao thức đã xác minh.
 *  - Bước liên quan Y-Connect/TFT chỉ trỏ tới hướng dẫn chính thức của Yamaha, không tự bịa quy trình.
 *  - Bản Markdown HELP_CONTENT_VI.md được sinh từ chính danh sách này (tools/GenerateHelpMd.kt) để không lệch nhau.
 */
object HelpContent {
    object Id {
        const val GETTING_STARTED = "bat-dau"
        const val PREPARE_PHONE = "chuan-bi-dien-thoai"
        const val PERMISSIONS = "cap-quyen"
        const val SCAN_SELECT = "quet-chon-thiet-bi"
        const val CONNECT_STATES = "ket-noi-trang-thai"
        const val GATT_SERVICES = "kiem-tra-gatt"
        const val VEHICLE_DATA = "doc-xac-minh-du-lieu"
        const val GPS_TRIPS = "gps-hanh-trinh"
        const val YCONNECT = "y-connect"
        const val TFT_NAV = "tft-dan-duong"
        const val NOT_FOUND = "khong-tim-thay-xe"
        const val LOST_NO_DATA = "mat-ket-noi-khong-du-lieu"
        const val EXPORT_DIAG = "xuat-bao-cao-chan-doan"
        const val PRIVACY = "quyen-rieng-tu-xoa-du-lieu"
    }

    val guides: List<HelpGuide> = listOf(
        HelpGuide(
            Id.GETTING_STARTED, 1, "Bắt đầu sử dụng NVX Connect", START,
            purpose = "Hiểu app làm được gì và chưa làm được gì trước khi kết nối với xe.",
            prerequisites = listOf("Điện thoại Android 8.0 trở lên có Bluetooth Low Energy.", "Xe đã dừng ở nơi an toàn."),
            steps = listOf(
                "Mở app, đọc màn hình chào và chọn Bắt đầu.",
                "Vào tab Xe để nhập hồ sơ xe và ODO hiện tại (số này do bạn nhập, app không đọc từ xe).",
                "Vào tab Kết nối để làm theo các bước: cấp quyền, quét, kết nối.",
                "Xem mục Chẩn đoán (Phân tích giao thức) để biết app đã xác minh được gì."
            ),
            expected = "Bạn nắm được: bản đồ, hành trình, nhiên liệu, bảo dưỡng chạy độc lập bằng GPS và dữ liệu bạn nhập; số liệu từ xe CHỈ xuất hiện khi giao thức đã được xác minh.",
            commonErrors = listOf("Nghĩ rằng kết nối Bluetooth thành công nghĩa là app đọc được RPM/nhiệt độ.", "Nghĩ rằng app đã hiển thị chỉ đường lên màn hình TFT."),
            fixes = listOf("Xem nhãn trạng thái: \"CONNECTED — RAW DATA ONLY\" nghĩa là mới nhận dữ liệu thô, chưa giải mã.", "Dẫn đường hiện chỉ chạy trên điện thoại; xem bài \"Sử dụng TFT và dẫn đường\"."),
            keywords = listOf("gioi thieu", "tinh nang", "lam duoc gi"), action = OPEN_VEHICLE, requiresStationary = false
        ),
        HelpGuide(
            Id.PREPARE_PHONE, 2, "Chuẩn bị điện thoại và bật Bluetooth", START,
            purpose = "Bảo đảm điện thoại sẵn sàng quét và kết nối BLE.",
            prerequisites = listOf("Điện thoại còn pin; Bluetooth hoạt động bình thường."),
            steps = listOf(
                "Vuốt thanh trạng thái và bật Bluetooth.",
                "Android 11 trở xuống: bật thêm Vị trí (GPS) của hệ thống, vì hệ điều hành yêu cầu để quét BLE.",
                "Tắt chế độ tiết kiệm pin mạnh cho NVX Connect nếu bạn muốn ghi hành trình lâu.",
                "Đưa điện thoại lại gần xe (vài mét), bật khóa điện nếu bạn đang quét thử xe."
            ),
            expected = "Bước \"Bluetooth khả dụng\" trong Quy trình kiểm tra chuyển sang đạt.",
            commonErrors = listOf("Bluetooth bật nhưng app vẫn báo tắt.", "Android 11 trở xuống: quét không ra gì dù đã cấp quyền."),
            fixes = listOf("Tắt rồi bật lại Bluetooth, sau đó quay lại app.", "Bật Vị trí của hệ thống (không chỉ cấp quyền cho app)."),
            keywords = listOf("bluetooth tat", "bat bluetooth", "vi tri"), action = OPEN_SYSTEM_BLUETOOTH
        ),
        HelpGuide(
            Id.PERMISSIONS, 3, "Cấp quyền Android", START,
            purpose = "Cấp đúng quyền tối thiểu để quét và kết nối Bluetooth, ghi hành trình.",
            prerequisites = listOf("Đã bật Bluetooth."),
            steps = listOf(
                "Android 12 trở lên: khi được hỏi, cho phép \"Thiết bị ở gần\" (BLUETOOTH_SCAN và BLUETOOTH_CONNECT). Không cần quyền vị trí để quét BLE.",
                "Android 8–11: cho phép Vị trí khi dùng app (bắt buộc để quét BLE).",
                "Muốn ghi hành trình bằng GPS: cho phép Vị trí chính xác khi dùng app; Android 14 có thể yêu cầu cấp trước khi bật dịch vụ nền.",
                "Nếu đã từ chối hai lần: vào Cài đặt ứng dụng → Quyền để bật lại."
            ),
            expected = "Bước \"Đã cấp quyền\" đạt; nút Quét hoạt động.",
            commonErrors = listOf("Chọn \"Không cho phép\" nên không quét được.", "Hộp thoại quyền không hiện lại."),
            fixes = listOf("Dùng nút \"Mở cài đặt quyền của ứng dụng\" bên dưới rồi bật quyền thủ công.", "Quay lại app và bấm Quét lại."),
            keywords = listOf("quyen", "permission", "thiet bi o gan", "tu choi"), action = OPEN_APP_SETTINGS
        ),
        HelpGuide(
            Id.SCAN_SELECT, 4, "Quét và chọn thiết bị", CONNECT,
            purpose = "Tìm thiết bị Bluetooth của xe và chọn đúng thiết bị.",
            prerequisites = listOf("Bluetooth bật, đã cấp quyền.", "Xe đã bật khóa điện và ở gần; xe dừng ở nơi an toàn."),
            steps = listOf(
                "Vào tab Kết nối và bấm Quét (hoặc dùng Tự tìm xe).",
                "Đợi vài giây; danh sách hiện tên, cường độ tín hiệu (RSSI) và thiết bị kết nối được.",
                "Mẹo nhận diện: quét lúc xe TẮT rồi lúc xe BẬT; thiết bị chỉ xuất hiện khi xe bật là ứng viên.",
                "Chọn thiết bị rồi bấm Kết nối. Có thể lưu thiết bị để lần sau tự nhận."
            ),
            expected = "Bước \"Tìm thấy thiết bị\" đạt. Lưu ý: app xếp hạng ứng viên nhưng không khẳng định đó là xe Yamaha.",
            commonErrors = listOf("Không thấy thiết bị nào.", "Có nhiều thiết bị không tên, không biết chọn cái nào.", "Địa chỉ thiết bị đổi sau mỗi lần quét."),
            fixes = listOf("Xem bài \"Xử lý lỗi không tìm thấy xe\".", "Dùng bộ lọc \"Có tên\" và so sánh quét lúc xe tắt/bật.", "Thiết bị đã lưu được nhận lại theo tên khi địa chỉ xoay."),
            keywords = listOf("quet", "scan", "danh sach thiet bi", "rssi", "chon thiet bi"), action = OPEN_AUTO_CONNECT
        ),
        HelpGuide(
            Id.CONNECT_STATES, 5, "Kết nối Bluetooth và giải thích các trạng thái", CONNECT,
            purpose = "Biết mỗi trạng thái kết nối nghĩa là gì, để không hiểu nhầm.",
            prerequisites = listOf("Đã chọn một thiết bị ở bước quét."),
            steps = listOf(
                "Bấm Kết nối. App chờ tối đa 15 giây cho mỗi lần thử và tự thử lại có giới hạn khi mất kết nối.",
                "Đang kết nối: ĐANG KẾT NỐI… (chưa phải đã kết nối).",
                "ĐÃ NỐI GATT: đã nối và khám phá xong dịch vụ; chưa chứng minh đó là xe, chưa xác thực.",
                "ĐÃ GHÉP ĐÔI (BONDED): Android báo đã ghép đôi; KHÔNG chứng minh xe đã xác thực app.",
                "ĐÃ XÁC MINH GIAO THỨC / CÓ DỮ LIỆU XE: chỉ khi có adapter đã xác minh trên xe thật. Hiện chưa có adapter nào."
            ),
            expected = "Bạn đọc đúng trạng thái. \"CONNECTED — RAW DATA ONLY\" là kết quả bình thường ở bản này.",
            commonErrors = listOf("LỖI KẾT NỐI / hết thời gian chờ.", "XE ĐANG BẬN VỚI ỨNG DỤNG KHÁC.", "Thiết bị yêu cầu ghép đôi/xác thực."),
            fixes = listOf("Hết thời gian: lại gần xe, bật khóa điện, thử lại.", "Bận: đóng Y-Connect (và tắt Bluetooth của nó) rồi thử; app không chiếm kết nối.", "Cần xác thực: app không đoán PIN và không vượt xác thực; chỉ ghép đôi qua hộp thoại Android."),
            keywords = listOf("trang thai", "gatt", "bonded", "ghep doi", "bus", "ket noi"), action = OPEN_CONNECT
        ),
        HelpGuide(
            Id.GATT_SERVICES, 6, "Kiểm tra dịch vụ GATT", CONNECT,
            purpose = "Xem các service/characteristic mà thiết bị công bố, để phục vụ khảo sát.",
            prerequisites = listOf("Trạng thái đã nối GATT (READY).", "Xe dừng an toàn."),
            steps = listOf(
                "Trong màn hình Kết nối, mở mục Nâng cao → cây GATT.",
                "Đọc UUID, thuộc tính (Read/Write/Notify/Indicate) và descriptor của từng characteristic.",
                "Chỉ bấm \"Nghe\" ở characteristic có Notify/Indicate khi bạn muốn quan sát; thao tác này chỉ ghi descriptor CCCD để bật thông báo.",
                "App KHÔNG có nút ghi dữ liệu hoặc gửi lệnh vào xe."
            ),
            expected = "Thấy danh sách dịch vụ. UUID riêng của hãng KHÔNG được app gán nhãn nếu chưa có bằng chứng.",
            commonErrors = listOf("Danh sách dịch vụ trống.", "Nghe nhưng không có gói nào."),
            fixes = listOf("Ngắt và kết nối lại; kiểm tra xe có đang bị ứng dụng khác giữ kết nối không.", "Một số characteristic cần xác thực; app dừng và báo, không tìm cách vượt qua."),
            keywords = listOf("service", "characteristic", "uuid", "notify", "descriptor", "cccd"), action = OPEN_CONNECT
        ),
        HelpGuide(
            Id.VEHICLE_DATA, 7, "Đọc và xác minh dữ liệu xe", DATA,
            purpose = "Hiểu nhãn trạng thái của từng giá trị và vì sao nhiều giá trị là \"Chưa có dữ liệu\".",
            prerequisites = listOf("Đã nối GATT.", "Muốn có số liệu xe: cần giao thức đã xác minh (hiện chưa có)."),
            steps = listOf(
                "Mở Phân tích giao thức → Danh mục chức năng để xem từng chức năng xe.",
                "VERIFIED: giá trị đến từ adapter đã xác minh, còn mới (≤ 5 giây) và nằm trong khoảng hợp lệ.",
                "STALE: từng nhận nhưng đã cũ; UNVERIFIED: nhận được nhưng chưa đủ điều kiện (ví dụ ngoài khoảng); UNAVAILABLE: chưa có; UNSUPPORTED: giao thức đã xác minh khai báo không cung cấp.",
                "Mỗi giá trị hiển thị kèm nguồn: XE / GPS ước tính / Bạn nhập / Tính toán."
            ),
            expected = "Không có số liệu xe nào hiển thị nếu chưa xác minh. Tốc độ GPS không bao giờ được dùng thay tốc độ xe.",
            commonErrors = listOf("Tất cả đều \"Chưa có dữ liệu\".", "Số liệu có nhãn STALE."),
            fixes = listOf("Đây là hành vi đúng khi chưa có giao thức xe đã xác minh; xuất báo cáo khảo sát để phục vụ phân tích.", "STALE: kiểm tra kết nối còn không; giá trị cũ không được hiển thị như số hiện tại."),
            keywords = listOf("rpm", "nhiet do", "dien ap", "du lieu xe", "verified", "stale", "xac minh"), action = OPEN_CAPABILITIES, manualComplete = false
        ),
        HelpGuide(
            Id.GPS_TRIPS, 8, "Sử dụng GPS và lưu hành trình", NAVIGATION,
            purpose = "Ghi hành trình bằng GPS điện thoại và xem lại lịch sử.",
            prerequisites = listOf("Đã cấp quyền vị trí.", "Bắt đầu/kết thúc ghi khi xe đã dừng; đặt điện thoại cố định trước khi chạy."),
            steps = listOf(
                "Vào tab Hành trình và bấm \"Bắt đầu hành trình\" khi xe đang đứng yên.",
                "Cất/cố định điện thoại; không thao tác khi đang lái.",
                "Dừng xe ở nơi an toàn rồi mới tạm dừng/kết thúc.",
                "Xem lại quãng đường, thời gian, tốc độ trung bình/tối đa trong lịch sử."
            ),
            expected = "Hành trình được lưu với nhãn \"GPS ước tính\" (không phải ODO hay tốc độ của xe).",
            commonErrors = listOf("Quãng đường lệch so với đồng hồ xe.", "Ghi dừng khi tắt màn hình."),
            fixes = listOf("Đây là khoảng cách GPS nên có sai lệch; ODO do bạn nhập mới là số của bạn.", "Cho phép chạy nền và thông báo dịch vụ vị trí; tắt tiết kiệm pin cho app."),
            keywords = listOf("gps", "hanh trinh", "ghi chuyen di", "lich su", "quang duong"), action = OPEN_TRIPS
        ),
        HelpGuide(
            Id.YCONNECT, 9, "Kết nối Y-Connect theo hướng dẫn chính thức", CONNECT,
            purpose = "Biết mối quan hệ giữa NVX Connect và ứng dụng Y-Connect của Yamaha.",
            prerequisites = listOf("Xe có hỗ trợ Y-Connect theo tài liệu của Yamaha (đối chiếu sổ tay/đại lý).", "Xe dừng ở nơi an toàn."),
            steps = listOf(
                "Cài và ghép đôi Y-Connect HOÀN TOÀN theo hướng dẫn trong sổ tay xe hoặc trang chính thức của Yamaha. NVX Connect không thay thế và không hướng dẫn thay quy trình đó.",
                "Y-Connect thường giữ kết nối độc quyền với xe. Nếu muốn NVX Connect khảo sát, đóng Y-Connect trước.",
                "NVX Connect không vượt qua xác thực của Y-Connect và không chen vào kết nối chính thức.",
                "Khả năng tương thích của NVX 155 V3 SP 2025 với giao thức trong app: CHƯA xác minh."
            ),
            expected = "Y-Connect chính thức hoạt động bình thường; NVX Connect báo rõ khi xe đang bận.",
            commonErrors = listOf("Mở cả hai app cùng lúc và một trong hai không kết nối được.", "Kỳ vọng NVX Connect dùng chung phiên của Y-Connect."),
            fixes = listOf("Dùng lần lượt: đóng app kia trước khi mở app này.", "Cần tích hợp đầy đủ: phải có tài liệu/SDK được Yamaha cho phép."),
            keywords = listOf("yconnect", "y connect", "yamaha", "ghep doi chinh thuc", "doc quyen"), action = OPEN_CAPABILITIES, manualComplete = false
        ),
        HelpGuide(
            Id.TFT_NAV, 10, "Sử dụng TFT và dẫn đường", NAVIGATION,
            purpose = "Hiểu rõ dẫn đường đang chạy ở đâu và TFT có được hỗ trợ hay không.",
            prerequisites = listOf("Đã chọn điểm đến trên Bản đồ.", "Chỉ đặt điểm đến khi xe đã dừng ở nơi an toàn."),
            steps = listOf(
                "Vào Bản đồ, tìm địa điểm hoặc nhập tọa độ, chọn tuyến và bắt đầu trước khi xuất phát.",
                "Chỉ dẫn từng chặng (kể cả giọng nói) CHẠY TRÊN ĐIỆN THOẠI.",
                "Mở \"Hiển thị trên màn hình xe\" để xem trạng thái TFT: hiện là KHÔNG HỖ TRỢ vì chưa có giao thức/API được Yamaha cho phép và chưa xác minh trên NVX 155 V3 SP.",
                "App không gửi gì lên TFT và không giả lập việc hiển thị."
            ),
            expected = "Dẫn đường hoạt động trên điện thoại; màn hình TFT báo rõ chưa hỗ trợ.",
            commonErrors = listOf("Chỉ dẫn không hiện trên TFT của xe.", "Không có giọng nói tiếng Việt."),
            fixes = listOf("Đây là giới hạn đã biết, không phải lỗi kết nối.", "Cài/bật giọng vi-VN trong cài đặt chuyển văn bản thành giọng nói của Android."),
            keywords = listOf("tft", "man hinh xe", "dan duong", "chi duong", "navigation"), action = OPEN_TFT, manualComplete = false
        ),
        HelpGuide(
            Id.NOT_FOUND, 11, "Xử lý lỗi không tìm thấy xe", TROUBLE,
            purpose = "Khắc phục khi quét không thấy thiết bị của xe.",
            prerequisites = listOf("Xe dừng ở nơi an toàn; điện thoại gần xe."),
            steps = listOf(
                "Kiểm tra Bluetooth bật và đã cấp quyền (Android 11 trở xuống: bật cả Vị trí).",
                "Bật khóa điện xe và quét lại; thử cả lúc xe tắt để so sánh.",
                "Đóng Y-Connect và mọi ứng dụng Bluetooth khác đang kết nối với xe.",
                "Tắt/bật Bluetooth điện thoại, rồi quét lại trong 30–60 giây."
            ),
            expected = "Thiết bị ứng viên xuất hiện trong danh sách quét.",
            commonErrors = listOf("Xe không phát quảng bá khi đang kết nối với máy khác.", "Thiết bị không tên bị ẩn bởi bộ lọc."),
            fixes = listOf("Bỏ bộ lọc \"Có tên\"; xem tổng số thiết bị đã thấy.", "Nếu vẫn không thấy: có thể xe không dùng BLE (có thể là Bluetooth Classic) hoặc không hỗ trợ — xuất báo cáo và liên hệ hỗ trợ."),
            keywords = listOf("khong thay xe", "khong tim thay", "not found", "quet khong ra"), action = OPEN_AUTO_CONNECT, manualComplete = false
        ),
        HelpGuide(
            Id.LOST_NO_DATA, 12, "Xử lý mất kết nối hoặc không có dữ liệu", TROUBLE,
            purpose = "Khắc phục khi đang nối thì mất kết nối, hoặc nối được nhưng không có số liệu.",
            prerequisites = listOf("Xe dừng ở nơi an toàn."),
            steps = listOf(
                "Xem trạng thái hiện tại ở màn hình Kết nối (ĐANG KẾT NỐI LẠI, LỖI KẾT NỐI, hay RAW DATA ONLY).",
                "App tự thử lại có giới hạn; khi hết số lần sẽ báo lỗi, hãy bấm Kết nối lại.",
                "Nếu nối được nhưng \"Chưa có dữ liệu\": kiểm tra bước \"Đã xác minh giao thức\" — thiếu adapter đã xác minh thì không có số liệu xe.",
                "Kiểm tra pin điện thoại, khoảng cách và việc ứng dụng khác chiếm kết nối."
            ),
            expected = "Kết nối phục hồi, hoặc bạn biết chính xác bước nào chưa đạt.",
            commonErrors = listOf("Mất kết nối khi tắt màn hình.", "Giá trị chuyển STALE."),
            fixes = listOf("Tắt tối ưu hóa pin cho app; giữ điện thoại gần xe.", "STALE nghĩa là dữ liệu cũ; không dùng số đó như hiện tại."),
            keywords = listOf("mat ket noi", "ngat ket noi", "khong co du lieu", "timeout", "het thoi gian"), action = OPEN_CONNECT, manualComplete = false
        ),
        HelpGuide(
            Id.EXPORT_DIAG, 13, "Xuất báo cáo chẩn đoán để hỗ trợ kỹ thuật", TROUBLE,
            purpose = "Tạo báo cáo JSON/CSV/văn bản để gửi cho người hỗ trợ.",
            prerequisites = listOf("Đã chạy khảo sát hoặc gặp lỗi cần báo cáo.", "Thực hiện khi xe dừng an toàn."),
            steps = listOf(
                "Vào Phân tích giao thức (Chẩn đoán).",
                "Chọn xuất JSON, CSV hoặc Báo cáo văn bản.",
                "Báo cáo đã che chuỗi có thể là mã định danh (VIN/ID) và địa chỉ thiết bị; vẫn nên đọc lại trước khi gửi.",
                "Gửi qua ứng dụng bạn chọn (chia sẻ của Android)."
            ),
            expected = "Có tệp báo cáo gồm thời gian, trạng thái, mã lỗi và dữ liệu cần thiết.",
            commonErrors = listOf("Không có nút chia sẻ.", "Báo cáo ít thông tin."),
            fixes = listOf("Chạy lại khảo sát có kết nối trước khi xuất.", "Ghi chú hành động (khởi động, tăng ga khi xe dựng chân chống) để báo cáo hữu ích hơn — chỉ khi an toàn."),
            keywords = listOf("bao cao", "xuat", "json", "csv", "ho tro", "log"), action = OPEN_ANALYSIS
        ),
        HelpGuide(
            Id.PRIVACY, 14, "Quản lý quyền riêng tư và xóa dữ liệu đã lưu", PRIVACY,
            purpose = "Biết app lưu gì trên máy và cách xóa.",
            prerequisites = listOf("Không yêu cầu."),
            steps = listOf(
                "Dữ liệu lưu cục bộ trên điện thoại: hành trình, nhiên liệu, bảo dưỡng, hồ sơ xe, thiết bị đã lưu, tiến độ hướng dẫn.",
                "Mở Cài đặt → \"Xóa toàn bộ dữ liệu\" (có hộp thoại xác nhận).",
                "Đặt lại tiến độ hướng dẫn ở Trung tâm hướng dẫn (nút Đặt lại).",
                "Báo cáo xuất ra chỉ rời máy khi bạn chủ động chia sẻ."
            ),
            expected = "Dữ liệu bị xóa khỏi máy; không có dữ liệu nào tự gửi đi nơi khác.",
            commonErrors = listOf("Quên xóa tệp báo cáo đã xuất."),
            fixes = listOf("Xóa tệp trong ứng dụng đã nhận báo cáo/thư mục tải xuống.", "Gỡ cài đặt app cũng xóa dữ liệu cục bộ."),
            keywords = listOf("rieng tu", "xoa du lieu", "privacy", "du lieu luu"), action = OPEN_SETTINGS, requiresStationary = false
        )
    )

    private val byId = guides.associateBy { it.id }
    fun find(id: String): HelpGuide? = byId[id]
}
