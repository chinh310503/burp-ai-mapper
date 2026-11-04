# KẾ HOẠCH CẢI THIỆN — burp-ai-mapper

> Mục tiêu: AI tự động dùng **hết** chức năng website để trích xuất **sitemap + danh
> sách API** đầy đủ, **không phá hoại dữ liệu**. Đo bằng benchmark Nexora (ground-truth
> 109 URL / 128 route). Đích: từ ~*Good* lên ***Excellent*** + OpenAPI chất lượng dùng được.

Lộ trình chia 5 giai đoạn **P0→P4**, mỗi giai đoạn giao được giá trị độc lập và có thể
dừng/nghiệm thu riêng. Ưu tiên: sửa đúng & an toàn trước, rồi bao phủ, rồi chất lượng.

---

## P0 — Sửa bug & an toàn (nhỏ, giá trị cao, ít rủi ro)

**Vì sao trước**: đang *mất trang* và *mâu thuẫn mục tiêu* do guard; sửa xong là tăng
coverage ngay mà không đụng kiến trúc.

1. **Tách "đọc trang" khỏi "mutation" trong guard** (🐞1).
   - Cho phép **điều hướng GET** tới trang dù tên chứa `checkout/pay/...`; chỉ chặn khi
     hành động là **submit/click nút gây mutation** (POST) hoặc href rõ ràng phá hoại
     (`/delete`, `/logout`, `?action=delete`).
   - Bỏ `checkout/pay/payment/charge/...` khỏi mẫu **URL-navigation**; giữ chúng ở mẫu
     **TEXT nút** (nút "Pay now"/"Place order"/"Checkout" vẫn bị chặn click).
   - Kết quả: `/checkout` được map; vẫn không bấm thanh toán.
2. **Luồng đổi tài khoản an toàn** (🐞2).
   - Thêm cơ chế re-auth **do extension quản lý**: xoá cookie phiên (`driver.manage()
     .deleteAllCookies()`) rồi login account kế tiếp — **không** coi là "logout phá hoại".
   - Parse **nhiều account** từ prompt (hoặc trường riêng) → crawl tuần tự từng tier,
     gộp site map. Logout do agent chủ động vẫn chặn; re-auth do code thì cho phép.
3. **Giữ hash-route như URL riêng** (🐞3): `normalizeUrl` chỉ bỏ fragment *thuần* (`#abc`
   anchor), nhưng **giữ** hash dạng route (`#/...`). Enqueue `/app#/settings`, `#/billing`.
4. **Dọn tài liệu lỗi thời** (🐞4): bỏ nhắc "Python sidecar" trong README/`AiMapperExtension`
   javadoc/`build.gradle`.

**Nghiệm thu P0**: `/checkout` xuất hiện trong `urls.json` (là GET thật qua Burp); agent
**duyệt tới** các hash-route `/app#/settings`, `/app#/billing` (thấy trong log crawl);
chạy được 2 account liên tiếp (log "Switching session…"); không có hành động phá hoại nào
thực thi (log `BLOCKED` cho delete/pay).

> Lưu ý: hash-route là **client-only** nên Burp chỉ ghi `GET /app` (hash không gửi lên
> server) ⇒ chúng **chưa** xuất hiện *riêng* trong `urls.json`. Để đưa URL client-side
> (hash + pushState) vào bản xuất cần ghi lại "client URL đã thăm" và gộp vào export —
> đã chuyển thành một hạng mục của **P1** (mục khám phá SPA).

**Trạng thái**: P0 đã triển khai trên branch `p0-safety-and-coverage-fixes`
(guard tách đọc/mutation, multi-account re-auth, giữ hash-route, dọn docs). Build `compileJava` PASS.

---

## P1 — Bao phủ link (đạt *Strong*, lõi của benchmark)

**Vì sao**: đây là mục tiêu chính "dùng hết chức năng". Làm cho việc phát hiện link
**tất định** thay vì phó mặc LLM.

5. **Trích link đa cơ chế** (thay `LINKS_JS`): một script JS thu mọi URL điều hướng tiềm năng:
   - `a[href]`, `area[href]`, `form[action]`;
   - `[data-href] / [data-url] / [data-go]`;
   - `onclick`/`onchange` chứa `location.href|assign|replace` hoặc `window.location=` (regex);
   - `<select><option value="/...">` (option là URL);
   - resolve URL tương đối về tuyệt đối; bỏ `javascript:/mailto:/tel:/#thuần`.
   - **Xuyên shadow DOM mở** (đệ quy `shadowRoot`) và **duyệt iframe same-origin**
     (`switchTo().frame` + extract, rồi `defaultContent`).
6. **Pass "mở hết" tất định** mỗi trang (trước khi hỏi LLM):
   - click mọi `.dropbtn`/`[data-modal]`/tab/`<details>`/menu → re-extract link;
   - scroll tới cuối + click nút "Load more"/"Show more" (nhận theo text) tới khi hết;
   - mục tiêu: ép lộ link ẩn mà không tốn bước LLM.
7. **Khám phá SPA**: hook `history.pushState/replaceState` (ghi lại path), thu link
   `[data-spa]` + router link; điều hướng lần lượt các client-route còn thiếu trong frontier.
   **Gộp URL client-side đã thăm (hash + pushState) vào bản xuất** (từ P0 chuyển sang):
   ghi lại tập "client URL đã thăm" trong `BrowserAgent` và cho `SitemapGenerator` gộp vào
   `urls.json`/`sitemap.xml` — vì Burp chỉ thấy `GET /app`, không thấy `#/settings`.
8. **Chủ động nạp `sitemap.xml` + `robots.txt`**: fetch và seed frontier từ chúng (vẫn
   crawl thật để bắt trang unlisted — không chỉ tin sitemap).

**Nghiệm thu P1**: so `urls.json` với `ground-truth.urls.txt` qua `comm`; bắt được
image-map, data-*, select-jump, iframe inner, SPA `/app/docs/webhooks`, hash route,
AJAX `/api/links`. Coverage GET page ≥ ~90%.

**Trạng thái**: P1 đã triển khai trên branch `p0-safety-and-coverage-fixes`. Gồm:
harvest đa cơ chế (`HARVEST_JS`: a/area/form/data-*/onclick/onchange/select + shadow DOM
+ iframe same-origin), pass "mở hết" (`REVEAL_JS`) + scroll + load-more (`exhaustPage`),
`waitForReady` sau mọi điều hướng, seed từ `sitemap.xml`/`robots.txt` (`seedFromSiteFiles`),
và gộp URL client-only vào export (`BrowserAgent.discoveredUrls()` →
`SitemapGenerator.write(records, outDir, extraGetUrls)`). Build `shadowJar` PASS (fat JAR OK).
Còn lại trong P1 (có thể làm sau khi chạy thử benchmark): tinh chỉnh ngưỡng chờ/`SAMPLES`
theo kết quả `comm` thực tế.

---

## P2 — Bắt API trực tiếp + "bộ não" agent tin cậy (đạt *Excellent*)

9. **CDP network capture** (song song Burp): bật Selenium `Network` domain, log
   request+response (kèm body JSON). Dùng để (a) làm giàu OpenAPI (response schema),
   (b) phản hồi **coverage endpoint** cho agent. Thiết kế qua interface `TrafficSink`
   để sau thay WebDriver BiDi. Burp vẫn là nguồn chính khi có.
10. **Nâng cấp LlmClient**:
    - **Structured Outputs** (strict JSON schema cho action) khi provider hỗ trợ;
      fallback: parse hiện tại + **retry** 1–2 lần khi reply hỏng.
    - Cấu hình `temperature`/`max_tokens`; đọc content **linh hoạt** (hỗ trợ reasoning model).
    - Thông điệp lỗi rõ ràng (401/429/timeout).
11. **State giàu hơn cho agent**: thêm "ENDPOINTS CAPTURED (n)", "COVERAGE ~x%",
    cho phép **multi-action/bước**. Prompt nhấn: ưu tiên feature **mới**, không enum id.

**Nghiệm thu P2**: `openapi.json` có response content-type/schema từ body thật; agent
chạy ổn định với ≥2 provider (vd OpenAI + 1 local) không vỡ vì reply lỗi; đạt cả 2 tier.

---

## P3 — Chất lượng OpenAPI & trải nghiệm

12. **Suy luận schema sâu** (`OpenApiGenerator`): parse JSON body **đệ quy** (object/array/
    properties), **gộp nhiều mẫu** (union + `required` nếu luôn có), **response schema** từ
    body; **gộp query param** qua nhiều request; **security scheme** (cookie `connect.sid` /
    Bearer); **tags** theo segment đầu; **merge** giữ hand-edit khi xuất lại.
13. **Redact secret** khi xuất (token/cookie/password trên URL/body/header) + tùy chọn
    **không gửi mật khẩu cho LLM** (trường credential riêng, agent điền trực tiếp).
14. **Lưu cấu hình** giữa phiên (model/baseURL/proxy/safety…), che API key.
15. **Chế độ chấm điểm**: nút "Score vs ground-truth" — nạp `ground-truth.urls.txt`,
    diff với kết quả, in coverage theo nhóm (A–G) ngay trong tab.

**Nghiệm thu P3**: `openapi.json` import được vào Swagger UI không lỗi; spec có schema
body/response; không lộ secret; bảng điểm hiện coverage từng nhóm.

---

## P4 — Mở rộng (tùy chọn, sau khi lõi vững)

16. **Vision fallback** (chụp màn hình khi DOM/a11y không đủ) — bật tùy chọn.
17. **GraphQL**: introspection + nhận diện `/graphql` sâu hơn.
18. **WebDriver BiDi**: thay dần CDP (Selenium coi CDP là tạm thời).
19. **Điều chỉnh dedupe**: `SAMPLES_PER_TEMPLATE`, nhận diện thêm kiểu id (slug, base64).

---

## Thứ tự & phụ thuộc
- **P0 độc lập**, làm trước (quick win).
- **P1** dựa trên P0 (guard đã tách) — giá trị coverage lớn nhất.
- **P2** dựa P1 (có link để crawl rồi mới tối ưu capture & brain).
- **P3** dựa P2 (có capture body mới suy schema sâu).
- **P4** sau cùng.

## Cách đo xuyên suốt
Sau mỗi giai đoạn: chạy Nexora (`npm start` @ :3000), crawl qua Burp, export, rồi:
```bash
comm -23 ground-truth.urls.txt <(sort urls_from_export.txt)   # URL còn thiếu
```
Ghi lại coverage từng nhóm A–G + số endpoint OpenAPI + số hành động phá hoại bị chặn (phải = mọi delete/pay gặp phải).

## Gợi ý điểm chạm code
- `BrowserAgent.java`: `LINKS_JS`, `EXTRACT_JS`, `normalizeUrl`, guard (`DESTRUCTIVE_URL/TEXT`,
  `destructiveReason`), vòng lặp `run()`, thêm pass mở-hết + iframe/shadow + re-auth.
- `LlmClient.java`: structured output + retry + config + đọc content linh hoạt.
- `OpenApiGenerator.java`: suy schema sâu + response + security + tags + merge.
- `SiteMapExtractor.java` / mới `CdpTrafficSink`: nguồn traffic bổ sung + redact.
- `AgentConfig.java` / `MapperTab.java`: trường credential riêng, lưu cấu hình, nút score.
