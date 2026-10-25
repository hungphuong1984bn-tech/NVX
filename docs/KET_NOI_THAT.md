# KET_NOI_THAT — vì sao trước đây "không kết nối thật" và đã sửa gì (0.12.0-live)

Nguồn: `nvx-ble-log-20261025-190509_1.json` (2.153 sự kiện) và `nvx-ble-log-20261025-194402_1.json` (4.680 sự kiện), app 0.10.0, NVX 155 V3 SP, MTU 247, GATT: `1800`, `1801`, Nordic-UART `6e400001` (`6e400002` WRITE, `6e400003` NOTIFY).

## 1. Điều log chứng minh (OBSERVED)
| Quan sát | Log 1 | Log 2 |
|---|---|---|
| Xe tự ngắt, status 19, ~29,3 s sau READY, 0 gói | 22/23 lần ngắt (11:51 → 12:02); 2 lần sớm hơn 29 s là 20,3 s và 15,1 s | 1 lần (12:37:32) |
| Có gói → liên kết giữ | 133 s (đến 12:04:54) | 355 s (đến 12:43:29) |
| Gói đầu tiên xuất hiện | +0,4 s sau READY (12:02:41) | +43,5 s (sau khi người dùng bấm "Nghe") |
| Sau khi rpm về 0 (tắt máy) | rpm>0 lần cuối +118 s, ngắt sau 11,2 s | rpm>0 lần cuối +298 s, ngắt sau 14,0 s |
| Sau ngắt, nối lại | — | 15 s hết giờ (xe không quảng bá nữa) |

Tổng kiểm SUM8 khớp 100% gói 0x6B/0x6C. App **không ghi** gì vào xe trong cả hai log, vậy mà liên kết sống 355 s: **không cần bước "keep-alive" ghi vào xe** (bác giả thuyết trong YCC_PROTOCOL_FINDINGS §5; xe ngắt vì khóa điện tắt, không phải vì thiếu lệnh).

## 2. Suy luận (HYPOTHESIS, chưa kiểm bằng thao tác có đối chứng)
Khóa điện TẮT: xe vẫn cho nối GATT nhưng cắt liên kết rảnh sau ~29 s. Khóa điện BẬT: xe giữ liên kết và phát khung 0x6B (~12/s) + 0x6C (~6/s) ngay khi CCCD được ghi. Log chưa có nhãn thao tác trùng thời điểm (các nhãn "Chống nghiêng/Đèn pha/Nổ máy/Tắt máy" ở log 2 được gắn lúc 12:43:51+, SAU khi liên kết đã mất), nên chưa gán được byte nào cho chân chống/đèn pha.

## 3. Các lỗi của app khiến người dùng không có dữ liệu liên tục
1. Chỉ thử lại 5 lần rồi `Error` → với xe tắt khóa điện thì chắc chắn bỏ cuộc.
2. Không tự bật nghe → mỗi lần mở app mất 11–44 s đầu; sau mỗi lần nối lại thì có (do `desired`), nhưng lần đầu thì không.
3. Hết giờ 15 s rồi thử trực tiếp lại: có khoảng hở không quảng bá/không lắng nghe → bỏ lỡ lúc bật khóa.
4. Giao diện báo "LỖI KẾT NỐI"/"x/5" khi thực ra xe chỉ đang chờ bật khóa.

## 4. Đã sửa (xem CHANGELOG 0.12.0-live)
Nối lại không giới hạn · kết nối nền sau 3 lần · tự nghe `6e400003` · gợi ý lý do (`LinkHint`) · xác thực/mã hóa vẫn dừng · không ghi `6e400002`.

## 5. Chưa chứng minh được — cần bạn đo
- **RPM**: u16 BE @ byte 5–6 của 0x6B cho cầm chừng ~1.250–1.500 (hợp lý), nhưng trường `02 BA` của 0x6C chỉ bằng ≈0,75× giá trị đó (4210 ↔ 3143; 4349 ↔ 3290). Hai trường không thể cùng là rpm thật 1:1; chưa biết cái nào đúng (hoặc 0x6B là rpm×4/3). **Phải đối chiếu đồng hồ vòng tua** bằng thẻ "Hiệu chỉnh" ≥4 mức.
- **Điện áp**: byte 11 × 0,07 → tắt máy 12,7–12,9 V; khi nổ 13,6–13,9 V; lúc đề 10,9–11,8 V. Hợp lý nhưng chưa có số đo vạn năng.
- Tốc độ, nhiệt độ, xăng, ODO, mã lỗi: chưa có (log chưa chạy xe có GPS).

## 6. Những gì app vẫn KHÔNG làm
Không ghi `6e400002`, không đoán lệnh, không ghép/vượt xác thực. Muốn điều khiển hoặc đọc thêm trường cần log HCI của Y-Connect chính hãng trên xe của bạn.

## 7. Việc còn lại để dùng khi chạy xe
Kết nối hiện sống trong `BleViewModel`; khi tắt màn hình lâu Android có thể dừng tiến trình. Bật ghi Hành trình (TripService, foreground) để giữ tiến trình, hoặc thêm một foreground service loại `connectedDevice` (cần sửa Manifest + quyền, chưa làm vì chưa build được Compose ở đây).
