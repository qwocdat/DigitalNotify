# DigitalNotify v0.2.0 (Pro Liquid Glass Edition)

Ứng dụng Android hiển thị thông báo nổi phong cách **Liquid Glass (AGSL Shader)** tốc độ cao, đã được **sửa lỗi build CI/Gradle** và **nâng cấp toàn diện**.

## Các điểm đã sửa lỗi Build (Fix Build)

1. **GitHub Actions CI (`.github/workflows/build-apk.yml`)**:
   - Loại bỏ phụ thuộc vào `android-actions/setup-android@v3` (thường gây lỗi `sdkmanager` / lệch biến môi trường `ANDROID_HOME` trên runner `ubuntu-latest`).
   - Sử dụng trực tiếp Android SDK có sẵn trên GitHub runner, tự động chấp nhận license (`sdkmanager --licenses`) và cài đặt `platforms;android-35`, `build-tools;35.0.0`.
   - Tự động nhận diện thư mục chứa `settings.gradle.kts` dù người dùng giải nén ở thư mục gốc hay thư mục con.
   - Tự động build cả **Debug APK** và **Release APK** (đã tối ưu R8/shrinkResources và ký sẵn để cài ngay trên điện thoại).
2. **Cấu hình Gradle & Android Manifest**:
   - Bổ sung `gradle/wrapper/gradle-wrapper.properties` và `.gitignore` chuẩn Android Studio.
   - Bổ sung `<queries>` và `tools:ignore="QueryAllPackagesPermission"` trong `AndroidManifest.xml`, cấu hình `lint` trong `app/build.gradle.kts` để không chặn quá trình build Release/Debug.
   - Sửa lỗi khai báo font `sans-serif` trong `styles.xml` và bổ sung icon vector `@drawable/ic_launcher`.

## Các tính năng nâng cấp mới (v0.2.0)

- **Sửa lỗi hiển thị góc bo & đổ bóng (`GlassBannerView`)**: Thêm `ViewOutlineProvider` chuẩn xác và viền kính inset không bị cắt lẹm; bổ sung 3 chủ đề AGSL Shader (**Frost Blue**, **Aurora Violet**, **Obsidian Gold**).
- **Cử chỉ cảm ứng thông minh trên Banner (`OverlayController`)**:
  - **Vuốt lên hoặc vuốt ngang** để đóng nhanh banner.
  - **Chạm và giữ** để tạm dừng bộ đếm tự đóng khi đang đọc nội dung dài.
  - **Nhấn vào banner** để mở trực tiếp màn hình/cuộc trò chuyện của thông báo (`PendingIntent` với hỗ trợ Android 14/15 `ActivityOptions`), tự động cập nhật nội dung tại chỗ nếu cùng ứng dụng gửi liên tiếp.
- **Hiển thị Avatar / Large Icon & Rung Haptic**: Tự động hiển thị ảnh đại diện người gửi (nếu có trong thông báo) và phản hồi rung nhẹ 18ms.
- **Bộ lọc nâng cao (`NotificationAccessService`)**:
  - Công tắc **Bật/Tắt nhanh** toàn bộ dịch vụ.
  - Tuỳ chọn **Theo dõi tất cả ứng dụng** hoặc chỉ các ứng dụng được chọn.
  - Tự động **bỏ qua thông báo đang chạy nền (Ongoing)** như trình phát nhạc, thanh tải xuống.
  - **Chặn theo từ khóa (Muted Keywords)**.
- **Giao diện `MainActivity` mới mượt mà**:
  - Tải danh sách ứng dụng **bất đồng bộ (Background Thread)** và lưu đệm icon/tên ứng dụng, loại bỏ hoàn toàn hiện tượng giật lag khi tìm kiếm.
  - Nút **Xem thử Banner Liquid Glass ngay** để kiểm tra hiệu ứng tức thì.
  - **Nhật ký thông báo gần đây** (cho phép nhấn để phát lại banner).
  - Hỗ trợ **Edge-to-Edge WindowInsets** chuẩn Android 15 (SDK 35) và lối tắt tắt tối ưu hoá pin chạy nền.

## Hướng dẫn Build trên GitHub Actions

1. Tạo repository mới trên GitHub và tải toàn bộ các file trong bản zip này lên repository.
2. Mở tab **Actions** -> chọn workflow **Build DigitalNotify APK** -> nhấn **Run workflow**.
3. Sau khi chạy xong (khoảng 1–2 phút), tải file `DigitalNotify-debug-apk` hoặc `DigitalNotify-release-apk` về điện thoại và cài đặt.
