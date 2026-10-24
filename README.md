# DigitalNotify v0.5.0 (iOS 27 Liquid Glass Edition)

## Các cập nhật trong bản v0.5.0

1. **Sửa triệt để lỗi làm mờ màn hình xung quanh**:
   - Đã gỡ bỏ `FLAG_BLUR_BEHIND` trên cửa sổ `WindowManager.LayoutParams`. Màn hình chính và các biểu tượng xung quanh giữ nguyên độ sắc nét 100%, chỉ riêng bên trong tấm thẻ thông báo có hiệu ứng kính lỏng AGSL.
2. **Sửa triệt để lỗi tự lặp thông báo *"DigitalNotify đang hiện trên các ứng dụng khác"***:
   - Chặn hoàn toàn các gói hệ thống (`"android"`, `"com.android.systemui"`) và bất kỳ thông báo hệ thống nào nhắc tới `"DigitalNotify"`.
   - Xoá thông báo chạy ngầm của chính ứng dụng.
3. **Xoá bỏ tiện ích Lịch & Thời tiết và làm mới toàn bộ giao diện iOS 27 cho người dùng**:
   - Giao diện hoàn toàn dành cho người dùng cuối, không còn thuật ngữ lập trình viên.
   - Bổ sung **LiquidGlassButton** và **LiquidGlassSwitch**: khi nhấn hoặc giữ để kéo, nút biến thành dạng thủy tinh lỏng với ánh sáng khúc xạ chạy theo ngón tay và co giãn đàn hồi.
4. **Luồng Onboarding iOS 27 mới**:
   - Chọn ngôn ngữ (Tiếng Việt / English).
   - Hướng dẫn cấp quyền & nút xác nhận điều kiện tối ưu (tắt thông báo nổi mặc định của các app đã chọn).
   - Màn hình ủng hộ Admin: nút mở TikTok **`https://www.tiktok.com/@digitalxiaoiu5`** để Follow & Tim video. Khi chuyển sang TikTok rồi quay lại, ứng dụng tự động mở khoá vào màn hình chính.
5. **Chế độ thay thế thông báo gốc (`Auto-Suppress`)**:
   - Tự động thu hồi thông báo nổi mặc định của ứng dụng khác ngay khi DigitalNotify bắt được tin nhắn, giúp chỉ hiển thị duy nhất thanh thông báo kính lỏng iOS 27.
