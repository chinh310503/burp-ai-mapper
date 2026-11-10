# 05 — Khôi phục dữ liệu sau khi crawl

Mục tiêu: sau khi AI crawl (có thể đã tạo/sửa dữ liệu — xem `mutations.json`),
**đưa dữ liệu về trạng thái ban đầu**, phòng phá hoại. Tham khảo Strix và các pattern
test-data cleanup.

## 1. Bài học từ các tool (Strix, test frameworks)

- **Strix** (AI pentest agent, 36k★): chạy mọi thứ trong **Docker sandbox** riêng (có
  Caido proxy + Playwright), validate exploit trong môi trường cô lập. ⇒ Bài học #1:
  **PHÒNG hơn CHỮA — test trên môi trường disposable/sandbox, đừng mutate production.**
- **Test frameworks** (integration/E2E): phổ biến nhất là
  1. **Reset endpoint** do app cung cấp (gated bằng test-mode) → gọi để trả về seed.
  2. **DB snapshot → revert** (nhanh, tin cậy, nhưng cần quyền DB/môi trường).
  3. **Transaction rollback / savepoint** (chỉ trong 1 kết nối, không áp dụng cross-request).
  4. **Delete-and-reseed / clean trước khi chạy** (chống rác khi run bị hủy giữa chừng).
- **Compensating transaction / Saga** (undo create→delete, edit→restore-cũ): là **forward
  action ngược nghĩa**, **không phải** rollback ACID; **không đảm bảo nghịch đảo hoàn hảo**
  (có thể để lại audit/event), phải **idempotent**, cần **id của bản ghi đã tạo** + **giá trị
  trước khi sửa**. Black-box (như crawler) rất khó làm chuẩn.

## 2. Xếp hạng phương án cho extension (black-box qua Burp)

| Phương án | Độ tin cậy | Điều kiện | Ghi chú |
| --- | --- | --- | --- |
| **A. Reset endpoint của app** (vd `POST /admin/reset`) | ★★★★★ | App có sẵn endpoint + quyền admin | Nexora có đúng cái này; chuẩn công nghiệp |
| **B. Snapshot/restore môi trường** (DB dump, `docker compose down -v && up`) | ★★★★★ | Có quyền shell/DB | Ngoài tầm Burp; chạy bằng lệnh người dùng cấu hình |
| **C. Compensating (undo từ mutations)** | ★★☆☆☆ | Bắt được id tạo + giá trị-trước-khi-sửa | Fragile; dễ sót side-effect; delete bị guard chặn |
| **D. Phòng ngừa** (đã có): guard chặn delete/logout/pay + `mutations.json` audit | ★★★★☆ | — | Giảm thiệt hại; không "khôi phục" |
| **E. Dùng môi trường throwaway** (bài học Strix) | ★★★★★ | Có bản sao bỏ đi | Tốt nhất khi có thể |

**Kết luận**: ưu tiên **A (reset hook)** vì tin cậy cao + Nexora/đa số app test có sẵn.
B để người dùng tự cấu hình lệnh. C chỉ làm **bản kế hoạch để review**, không auto-execute
(vì thiếu before-state ⇒ không khôi phục update chuẩn được). D đã có. Luôn khuyến nghị E.

## 3. Thiết kế chức năng "Restore" cho extension

1. **Restore hook (phương án A)** — cấu hình `Restore request` = `POST /admin/reset`
   (hoặc URL tuyệt đối, kèm method). Hai cách kích hoạt:
   - Checkbox **"Auto-restore when crawl finishes"** → sau khi crawl xong, extension tự
     gọi reset.
   - Nút **"Restore now"** → gọi thủ công bất cứ lúc nào.
   - Reset endpoint thường **admin-only** ⇒ bước restore tự **đăng nhập admin bằng code**
     (bước BẢO TRÌ tất định, **tách khỏi** luồng login AI khi crawl) rồi `fetch(url, {method,
     credentials:'include'})` để cookie phiên áp dụng. Log `RESTORE: POST … -> 200`.
2. **`mutations.json`** (đã có) = audit "đã thêm/sửa gì" để đối chiếu sau khi restore.
3. **Snapshot/command (phương án B)** — *tùy chọn tương lai*: trường "Restore command"
   chạy lệnh shell người dùng cấp (ví dụ khôi phục DB snapshot) sau crawl.
4. **Rollback plan (phương án C)** — *tùy chọn tương lai*: cần bắt **giá trị-trước-khi-sửa**
   (đọc form edit trước khi submit) để sinh `rollback-plan.json` (update→re-submit giá trị cũ,
   create→delete). Chưa làm vì fragile; ghi nhận ở đây.

## 4. Nguyên tắc an toàn khi restore
- Restore là hành động **có chủ đích của người dùng** (opt-in), không bật mặc định.
- Chỉ dùng trên hệ thống bạn sở hữu/được phép; endpoint reset phải là của app test.
- Nếu không có reset endpoint ⇒ **đừng crawl ghi dữ liệu trên production**; dùng snapshot
  hoặc môi trường throwaway (bài học Strix).

---
### Nguồn
- [Strix — open-source AI pentest agent (Docker sandbox)](https://medium.com/data-science-collective/strix-the-open-source-ai-agent-for-security-testing-44e1ed244a9d)
- [Strix explained (how it works, setup)](https://hoangyell.com/strix-explained/)
- [Integration tests with SQL Server database snapshots](https://johnnyreilly.com/integration-tests-with-sql-server)
- [Test data cleanup techniques in CI/CD](https://www.thegreenreport.blog/articles/techniques-for-effective-test-data-cleanup-in-cicd/techniques-for-effective-test-data-cleanup-in-cicd.html)
- [Compensating transactions & rollback strategies](https://kindatechnical.com/microservices-architecture/compensating-transactions-and-rollback-strategies.html)
- [Compensating transactions in practice](https://levelup.gitconnected.com/compensating-transactions-in-php-undoing-work-that-already-committed-3aaea470c503)
- [Seed & clean up with API steps (reset endpoint gating)](https://docs.does.qa/guides/seed-and-clean-up-with-api-steps/)
