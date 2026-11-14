# HouseNail Android V10 ↔ IPA V21.7: kiểm tra đồng bộ (2026-11-14)

Nguồn chuẩn: AnhKenDy1991/HouseNailManager-V3, nhánh v21-7-gop-day-du-ipa, thư mục App/. Android: repository này, nhánh main. Đây là kiểm tra mã nguồn, KHÔNG phải chứng nhận đã kiểm thử thực tế trên thiết bị.

## Nguyên tắc
- Giữ IPA hiện tại; dùng IPA V21.7 làm chuẩn giao diện, cấu trúc 5 tab, quyền và nghiệp vụ.
- Hai ứng dụng dùng cùng website/API, không sao chép trạng thái cài đặt cục bộ để giả làm đồng bộ.
- Mọi thay đổi cấu hình từ Android phải đọc lại máy chủ để xác nhận; lỗi đọc không được hiển thị trạng thái cũ như đã xác minh.
- Không bật thao tác nguy hiểm nếu không có quyền quản trị và trạng thái máy chủ xác thực.

## Kết quả đối chiếu hiện tại
| Hạng mục | iOS chuẩn | Android hiện tại | Kết quả |
| --- | --- | --- | --- |
| 5 tab Trang chủ/Lịch/Quét QR/Khách/Cài đặt | Có | Có, vừa đổi nhãn và kiểu tab | Một phần: chưa đối chiếu chi tiết mọi màn |
| Header, màu xanh/trắng, thanh tab bo góc | Có | Đã thay đổi mã nguồn | Chưa kiểm thử hiển thị trên thiết bị |
| Lịch: lọc, Timeline/Danh sách | Có | Có trong Appointments | Khác bố cục và vị trí thanh tìm kiếm |
| Lịch: hoàn thành, không đến, tích điểm | Có | Có các thành phần trong BookingCard | Cần kiểm thử quyền, API và kết quả sau ghi |
| Trạng thái lịch | status/attendanceOutcome | status/attendanceOutcome | Logic tương đồng; ảnh thực tế khác trạng thái, cần so sánh cùng ngày + thời điểm tải dữ liệu |
| Cài đặt BulkGate | Số dư, giá, ngưỡng, lưu adminSetBulkGateMonitorPrefs | Đọc số dư, hiển thị giá/ngưỡng; CHƯA có lưu giá/ngưỡng | Thiếu |
| SMS OTP | Đọc trạng thái, chỉ cho bật khi configured và provider=BulkGate, ghi rồi đọc xác minh | Đọc và ghi; chưa kiểm tra đủ điều kiện cấu hình nhà cung cấp, chưa xác minh giá trị sau ghi | Thiếu kiểm soát |
| Website công khai / kiểm soát lịch | iOS có giao diện và API | Android có giao diện và API | Ảnh thực tế báo lỗi; cần log HTTP cụ thể |
| Phân quyền nhân viên | iOS có HouseNailStaffShell | Android có kiểm tra isStaff ở vài phần | Chưa chứng minh tương đương trên mọi tab |
| Báo cáo, lịch sử, chờ, dịch vụ, đồng bộ, QR | Có các module iOS | Có màn tương ứng Android | Chưa kiểm thử đối chiếu đầu-cuối |
| Thông báo nền | APNs iOS | WorkManager/FCM Android | Khác nền tảng; cần kiểm thử trên thiết bị |
| Ổn định khởi động | Đã dùng thực tế | Đã sửa crash WorkManager | Chưa chứng nhận không còn crash khác |

## Việc phải hoàn tất trước khi gọi là 'đồng bộ hoàn toàn'
1. Đối chiếu từng màn hình và quyền admin/nhân viên trên cả 5 tab; dùng ảnh IPA V21.7 làm chuẩn.
2. Hoàn thiện BulkGate edit/save, OTP provider guard + re-read after write, trạng thái website/booking-control và quyền quản trị.
3. Test lịch: cùng tài khoản, cùng ngày, cùng thời điểm tải lại; xác minh đặt/hủy/hoàn thành/không đến/tích điểm xuất hiện giống nhau.
4. Test QR, khách, điểm, thẻ, báo cáo, lịch sử, chờ, dịch vụ, khóa thiết bị và thông báo.
5. Kiểm thử build, mở app, đăng nhập, chuyển từng tab, thao tác CRUD, mạng mất/khôi phục và đăng nhập lại trên Android thực.
6. Chỉ phát hành APK sau khi GitHub Actions thành công và có file artifact tải được.

## Ghi chú
Đừng tuyên bố 'giống IPA 100%' hoặc 'đã đồng bộ toàn bộ' khi các mục trên chưa được xác minh.
