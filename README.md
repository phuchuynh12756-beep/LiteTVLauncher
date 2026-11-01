# Lite Launcher – launcher siêu nhẹ cho TV box cấu hình yếu

- Kotlin thuần, **không thư viện ngoài** (không AndroidX, Glide, Compose) → APK chỉ vài trăm KB, ít tốn RAM.
- Android 5.0+ (API 21), điều khiển hoàn toàn bằng remote (D-pad), có chuột/cảm ứng vẫn dùng được.
- **Animation mượt** (chỉ dùng alpha / translation / scale chạy bằng GPU, không relayout):
  - icon lần lượt hiện lên khi mở launcher
  - icon đang chọn phóng to nhẹ, có viền sáng
  - bảng cài đặt trượt từ phải sang
  - chuyển cảnh zoom khi mở app và hiệu ứng hiện lại khi quay về
  - có thể **tắt toàn bộ hiệu ứng** trong Cài đặt cho box yếu nhất
- **Nền tùy chỉnh**: chọn ảnh từ bộ nhớ/USB (tự giảm về độ phân giải màn hình, lưu RGB_565 để tiết kiệm RAM) hoặc 6 màu gradient, chỉnh độ tối nền.
- **Dọn RAM**: nút dọn ngay, hoặc tự dọn khi mở/thoát app.

## Cách dùng phím
| Phím | Tác dụng |
|---|---|
| D-pad | Di chuyển giữa các app |
| OK | Mở app |
| Giữ OK | Thông tin app (gỡ cài đặt ở đây) |
| MENU hoặc nút "Cài đặt" | Mở bảng cài đặt |
| BACK | Đóng bảng cài đặt (launcher không thoát) |

## Build ra APK

**Cách 1 – Android Studio:** mở thư mục này → *Build > Build APK(s)*.

**Cách 2 – Dòng lệnh** (cần JDK 17 và Android SDK):
```
./gradlew assembleRelease
```
APK ở `app/build/outputs/apk/release/app-release.apk` (đã ký bằng khóa debug nên cài được ngay).

**Cách 3 – Không cần cài gì:** đưa project lên GitHub, vào tab *Actions* → *Build APK* → *Run workflow*, tải APK ở mục Artifacts.

## Cài lên TV box
```
adb connect <IP_của_box>
adb install -r app-release.apk
```
Nhấn nút HOME, chọn **Lite Launcher** → *Luôn dùng*. Nếu box không hỏi, vào Cài đặt > Ứng dụng > Ứng dụng mặc định > Màn hình chính.

## Ghi chú
- Chọn ảnh nền cần có app quản lý file trên box (loại hỗ trợ `ACTION_GET_CONTENT`). Nếu không có, dùng màu gradient.
- Hạn chế của Android: app nền hệ thống không thể bị "diệt" hoàn toàn; nút dọn RAM chỉ kết thúc được các tiến trình nền của app người dùng.
