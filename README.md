# Xem Kênh TV (Android TV, điều khiển bằng remote)

App chỉ để xem kênh truyền hình (nguồn vtvgo.vn, danh sách ở `app/src/main/assets/channels.json`), tối ưu cho remote TV.

## Phím trên remote
| Phím | Khi đang xem | Khi mở bảng chọn kênh |
|---|---|---|
| ▲ / CH+ | kênh kế tiếp | di chuyển |
| ▼ / CH- | kênh trước | di chuyển |
| ◀ / ▶ (hoặc PG-/PG+) | nhảy sang đài trước / sau | ◀ về cột Đài, ▶ sang cột Kênh |
| OK / Menu / Guide | mở bảng chọn kênh | OK = xem kênh (đài chỉ có 1 kênh: OK trên đài là xem luôn) |
| Số 0-9 | gõ số kênh (vd 12) rồi chờ ~2 giây | |
| Vàng | tải lại kênh | |
| Info | hiện tên kênh + đài | |
| Back | mở thẳng cột Đài; bấm Back 2 lần nhanh để thoát | đóng bảng (2 lần nhanh: thoát) |

Mở app là tự vào kênh xem lần trước. Bấm nhanh ▲/▼ nhiều lần chỉ tải kênh cuối cùng (đỡ giật). Video đứng hình ~40 giây hoặc lỗi mạng thì tự tải lại.

## Có file APK
1. Tạo repo GitHub mới, tải toàn bộ thư mục này lên (giữ `.github/workflows/build.yml`).
2. Tab **Actions** → chờ "Build APK" chạy xong → **Artifacts** → tải `TVXemKenh-apk`, giải nén lấy `.apk`.
3. Cài lên TV: chép APK vào USB cắm TV rồi mở bằng trình quản lý tệp, hoặc dùng app *Send Files to TV* / *Downloader*, hoặc `adb install`.
