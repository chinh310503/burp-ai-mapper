# AI Sitemap & API Mapper — Burp Suite extension

Extension Burp **self-contained** (Montoya API, Java). Chỉ cần **Add extension** là chạy — **không cần cài Python/pip/Node gì cả**. Extension tự điều khiển **Chrome trên máy** (qua Selenium) bằng một **AI agent**: tự đăng nhập và duyệt hết chức năng của website. Browser đi qua **proxy của Burp**, nên Burp bắt toàn bộ traffic; extension đọc site map đó và xuất **sitemap + OpenAPI**.

```
[Burp extension .jar]
   ├─ BrowserAgent (Selenium)  ──►  Chrome ──(proxy = Burp)──►  website (login + duyệt)
   │        ▲ vòng lặp: đọc element → hỏi AI → thao tác                    │
   │        └─ LlmClient (OpenAI-compatible: base_url/key/model)           ▼
   │                                                               [Burp Proxy] (bắt traffic)
   └─ Export  ◄── đọc Burp site map (Montoya) ──►  sitemap.xml · urls.json · openapi.json
```

Yêu cầu runtime duy nhất: **một trình duyệt Chrome / Chromium / Edge** đã cài trên máy. Selenium Manager tự tải đúng driver ở lần chạy đầu (tự động, không cần cài tay).

---

## Build (một lần)

Cần JDK 21. **Đừng dùng gradle hệ thống nếu là 7.x** (không chạy trên JDK 21). Dùng wrapper kèm sẵn — Gradle 8.5 chạy tốt trên JDK 21 và tự tải khi cần:

```bash
gradlew.bat build
```

(Git Bash/Linux: `./gradlew build`.) Fat JAR ra ở `build/libs/burp-ai-mapper-0.1.0.jar` (đã bundle Selenium + Gson).

> Đã kiểm chứng ở đây: toàn bộ source compile sạch (`javac --release 17`) với **Selenium 4.25.0 + Montoya API 2023.12.1 + Gson 2.10.1**, và Gradle 8.5 wrapper khởi chạy OK trên JDK 21. (Không build ra `.jar` sẵn được vì môi trường tạo file chặn socket loopback của Gradle — máy bạn không bị hạn chế này.)

## Nạp vào Burp

Burp → **Extensions → Add** → type **Java** → chọn JAR. Tab **"AI Mapper"** xuất hiện.

---

## Dùng

Trong tab **AI Mapper**:

1. **Task prompt** — ô text lớn: ghi mục tiêu + **(các) tài khoản & mật khẩu** (hỗ trợ nhiều tài khoản) + hướng dẫn. Ví dụ mẫu đã điền sẵn.
2. **Target URL** — trang bắt đầu.
3. **AI model** — `Base URL` + `API key` (OpenAI-compatible: OpenAI, OpenRouter, gateway nội bộ, Ollama/LM Studio...). Ô **Model** là combobox: gõ tay hoặc bấm **Fetch** để tải danh sách model từ `BaseURL/models` rồi chọn.
4. **Browser & Burp proxy** — proxy trỏ đúng listener của Burp (mặc định `127.0.0.1:8080`); `Max steps`; `Chrome binary` (tùy chọn nếu Chrome ở đường dẫn lạ); `Headless`.
5. **Start AI browsing** — extension mở Chrome, AI tự login + duyệt; traffic chảy vào Burp. Theo dõi ở ô log (tắt Headless để xem trực tiếp).
6. Khi xong (hoặc bấm **Stop**), đặt **Output dir** rồi bấm **Export sitemap** và **Export OpenAPI**.

Kết quả trong Output dir:
- `sitemap.xml` — URL GET, **gom theo "shape"**: `/products/101..125` → **một dòng** `/products/{id}` (kèm comment số URL đã gom). Hết trùng.
- `urls.json` — **mọi request đầy đủ** (method, url, status, content-type) — không gom, không mất gì.
- `endpoints.json` — **danh sách API đã khử trùng**, theo `method + shape` (GET `/products/{id}` khác DELETE `/products/{id}`), kèm `count` + `examples`.
- `openapi.json` — OpenAPI 3.1 (gom `/users/123` → `/users/{id}`; suy luận **schema JSON đệ quy** cho request + **response body**, gộp nhiều mẫu với `required`/`nullable`; **security scheme** cookie/bearer quan sát được; **tags** theo segment path đầu).

- `mutations.json` — **nhật ký audit dữ liệu đã thêm/sửa**: mọi request **không phải GET** agent thực sự gửi (POST/PUT/PATCH/DELETE có response) kèm **body đã gửi**, phân loại `create`/`update`/`delete`, redact secret. Dùng để rà soát — hoặc hoàn tác — những gì bị tạo/sửa trong lúc crawl (phòng phá hoại dữ liệu). Agent cũng ghi realtime dòng `DATA CHANGE: submitted form on … fields={…}` vào log khi submit form.

**Redact secret:** mọi bản xuất tự che giá trị nhạy cảm (password/token/api key/session/signature…) trong URL và body example — an toàn để chia sẻ.

**Chấm điểm:** điền **Ground-truth URLs file** rồi bấm **Score vs ground-truth** để diff kết quả (URL GET của Burp + route client-only) với danh sách chuẩn và in coverage % + các URL còn thiếu.

**Lưu cấu hình:** các trường (trừ API key & Task prompt) được nhớ giữa các phiên qua Burp extension preferences.

> **Lọc nhiễu trình duyệt:** sitemap bạn gửi có lẫn host của Chrome (`clients2.google.com`, `content-autofill.googleapis.com`, `optimizationguide-pa…`) — đó là telemetry của Chrome, không phải app. Để loại: điền **Host filter** = host target (vd `localhost`), hoặc add target vào **Burp scope** rồi bật **In-scope only**.

---

## Cách agent hoạt động (thuần Java, trong jar)

Mỗi bước: thu thập **link in-scope** của trang vào **frontier** (hàng đợi URL đã-phát-hiện-chưa-thăm, dedupe khi enqueue) → inject JS lấy danh sách phần tử tương tác (đánh index) → gửi URL + frontier + element list + prompt cho LLM → LLM trả **một JSON action** (`click`/`type`/`goto`/`back`/`wait`/`done`) → Selenium thực thi. Agent được dặn không logout/xóa/đổi setting/thanh toán.

**Thu thập link đa cơ chế (không chỉ `<a href>`):** mỗi trang được quét lấy mọi URL điều hướng tiềm năng — `a[href]`, image map `<area>`, `form[action]`, `data-href/url/go`, `onclick/onchange` kiểu `location.href=…`, và `<select><option value="/…">` — kể cả link **ẩn** trong dropdown/tab/modal/`<details>` (quét bất kể hiển thị), **xuyên shadow DOM mở** và **iframe same-origin**. Trước khi quét còn chạy một bước "mở hết" thuần-DOM (open `<details>`, bung menu, bỏ `hidden`) và cuộn + bấm nút **"load more"** (chỉ nút có chữ load/show more — không bao giờ nút phá hoại) để lộ nội dung nạp bằng AJAX. Frontier còn được **seed sẵn** từ `sitemap.xml` + `robots.txt`.

**Đa tài khoản:** nếu Task prompt liệt kê nhiều account (dạng `user / pass (role: …)`), agent crawl **mỗi tier một session riêng**, giữa các lượt **xoá cookie trong code rồi đăng nhập account kế** (re-auth do code quản lý — không phải "logout" bị chặn). Mọi lượt đều qua Burp nên site map tự gộp.

**Route client-only:** hash route (`#/settings`) và SPA pushState được agent duyệt tới; vì proxy chỉ thấy `GET /app`, các URL client-only agent chạm được **gộp thêm** vào `sitemap.xml`/`urls.json` khi export (đánh dấu `"source":"crawler-client-route"`).

**Khử trùng URL khi crawl (lấy mẫu theo "shape"):** mỗi URL được rút về một *shape* = path đã template-hóa + tên tham số query đã sort (bỏ giá trị), vd `/products/101` và `/products/125` → `/products/{id}`; `?category=books` và `?category=toys` → `?category`. Frontier chỉ nạp tối đa `SAMPLES_PER_TEMPLATE` (2) URL mỗi shape → agent không đi lại 25 trang product giống nhau mà dành bước cho **chức năng mới**, nhưng vẫn chạm **mọi endpoint khác nhau** (edit ≠ delete, method khác nhau là shape khác → không bỏ sót). Prompt cũng dặn model đừng tự liệt kê id tuần tự.

**Điều kiện dừng (frontier-based, chống dừng sớm):**
- **Hoàn tất** = frontier rỗng **và** không có hoạt động mới trong `DONE_STABLE` (5) bước.
- Model báo `done` nhưng **còn link chưa thăm** → bỏ qua, nhảy tới URL kế trong frontier (verify trước khi dừng).
- **Kẹt/lặp** `STUCK_LIMIT` (4) bước mà frontier còn link → **không dừng**, mà nhảy tới một URL chưa thăm (đổi chiến lược).
- `Max steps` = **0** (mặc định mới) nghĩa là **crawl tới khi hết** sitemap: chỉ dừng khi frontier rỗng (hoặc bạn bấm Stop), có trần an toàn 100.000 bước để tránh lặp vô hạn. Đặt số dương nếu muốn giới hạn cứng — khi đó log sẽ cảnh báo nếu chạm trần mà còn link chưa thăm.
- **Ghi log ra file:** điền **Log file** (hoặc để trống → `<output dir>/ai-mapper.log`) để lưu lại từng bước agent làm (URL hiện tại, action + mục tiêu, queue, lý do, hành động bị chặn) — tiện để rà soát và tinh chỉnh độ phủ.

(Các ngưỡng `STUCK_LIMIT` / `DONE_STABLE` / `MAX_FRONTIER` / `SAMPLES_PER_TEMPLATE` nằm đầu `BrowserAgent.java`; scope = cùng host với Target URL. Site thiên id tuần tự muốn chắc hơn thì tăng `SAMPLES_PER_TEMPLATE` lên 3.)

## HTTPS qua Burp

Chrome được bật `acceptInsecureCerts` + proxy = Burp, nên chấp nhận CA MITM của Burp mà không cần cài CA thủ công.

## Chặn hành động phá hoại (xóa / logout / reset / thanh toán)

Checkbox **"Block destructive actions"** (bật mặc định, ở mục **Safety**) là **guard ở tầng code**, không phải chỉ lời dặn prompt — nên model có chọn nhầm cũng không chạy được:
- **Không nạp vào frontier** URL có token phá hoại → các lệnh nhảy tự động (khi kẹt / khi model báo done sớm) **không bao giờ** đi vào `/delete`, `/logout`...
- **Cổng chặn cuối trước khi thao tác:** mọi `goto`/`click`/`type` được kiểm tra lại; nếu URL / href / nhãn nút khớp mẫu phá hoại → **bỏ qua, không thực thi** và ghi log `BLOCKED destructive ...`.
- Mẫu mặc định: `delete, destroy, remove, drop, truncate, wipe, purge, erase, deactivate, revoke, logout, signout, reset, pay, payment, checkout, charge, refund, withdraw, transfer`. Thêm từ khóa riêng ở ô **Extra block keywords**.
- Prompt cũng báo cho model biết các hành động này bị chặn để nó không phí bước.
- Cuối phiên log ghi số hành động đã chặn. (Mẫu nằm ở đầu `BrowserAgent.java`: `DESTRUCTIVE_URL` / `DESTRUCTIVE_TEXT`.)

> ⚠️ Mẫu ưu tiên AN TOÀN hơn độ phủ: một trang chỉ-đọc có chữ "delete" trong path cũng bị chặn. Muốn test cả flow phá hoại trên môi trường bỏ đi thì tắt checkbox (log sẽ cảnh báo).

## Khôi phục dữ liệu sau crawl

Crawl có thể tạo/sửa dữ liệu (xem `mutations.json`). Mục **Safety → Restore request** cho phép khôi phục bằng **reset endpoint của app** (cách tin cậy nhất — xem [research/05-data-restoration.md](research/05-data-restoration.md)):

- Điền `Restore request`, ví dụ `POST /admin/reset` (hoặc URL tuyệt đối; method mặc định POST).
- Bật **"Auto-restore when crawl finishes"** để tự gọi sau khi crawl xong, hoặc bấm **"Restore now"** bất cứ lúc nào.
- Bước restore tự **đăng nhập admin bằng code** (bước bảo trì tất định, tách khỏi login AI khi crawl) rồi gọi request với cookie phiên. Log `[restore] POST … -> 200`.
- Reset endpoint thường admin-only. Nếu app **không có** reset endpoint → đừng crawl ghi trên production; dùng DB snapshot hoặc môi trường throwaway.

### Rollback compensation (chạy trên MỌI target, không cần reset endpoint)

Phương pháp black-box tổng quát: agent **snapshot giá trị gốc** của form khi vào trang, và khi submit create/edit thì ghi lại cách hoàn tác.

- Bấm **"Run rollback"**: login admin → mỗi **edit** được khôi phục bằng cách **gửi lại giá trị gốc** (re-POST), mỗi **create** được báo để xóa tay / dùng reset endpoint; nếu có `Restore request` thì gọi luôn (phủ cả create).
- Export sinh **`rollback-plan.json`** (redact secret) để review trước khi chạy.
- Giới hạn: edit khôi phục tổng quát; **create** black-box không tự xóa an toàn (không chắc id mới) → cần reset endpoint hoặc xóa tay. Mật khẩu không bị re-submit (giữ nguyên).

## Bảo mật / phạm vi

- Chỉ chạy trên hệ thống **bạn sở hữu hoặc được phép test**.
- **Mật khẩu nằm trong Task prompt → được gửi tới nhà cung cấp LLM** (vì AI cần biết để điền). Cân nhắc dùng model local nếu nhạy cảm.
- Dùng **In-scope only** / **Host filter** để không xuất traffic ngoài phạm vi.
- Giám sát agent (tắt Headless); nó điều khiển browser thật.

---

## Cấu trúc source

```
burp-ai-mapper/
├─ build.gradle, settings.gradle, gradle.properties, gradlew(.bat), gradle/wrapper/
├─ src/main/java/com/msn/aimapper/
│  ├─ AiMapperExtension.java        # entry (BurpExtension)
│  ├─ ui/MapperTab.java             # UI: prompt + AI config + proxy + export
│  ├─ agent/
│  │  ├─ AgentConfig.java           # cấu hình một lần chạy
│  │  ├─ LlmClient.java             # gọi chat/completions (JDK HttpClient + Gson)
│  │  └─ BrowserAgent.java          # Selenium + vòng lặp AI (login & duyệt)
│  └─ export/
│     ├─ SiteMapExtractor.java      # đọc & lọc Burp site map
│     ├─ PathTemplater.java         # /123 → /{id}
│     ├─ SitemapGenerator.java      # sitemap.xml + urls.json
│     └─ OpenApiGenerator.java      # openapi.json (3.1)
└─ src/main/resources/META-INF/services/burp.api.montoya.BurpExtension
```

## Hạn chế đã biết (v0.1)

- Agent điều khiển theo DOM (text + element list). Có **Vision fallback** (checkbox, cần model có thị giác): khi trang không có element tương tác thì đính kèm screenshot cho model — mặc định tắt. Với UI rất động có thể cần tăng `Max steps`/`Samples per URL shape` hoặc bổ sung hướng dẫn trong prompt.
- Suy luận schema OpenAPI đơn giản (string/integer/number/boolean/object); body JSON lưu dạng example.
- Chưa parse sâu GraphQL (nhận diện theo path/content-type).
- Pin Selenium 4.25.0 — Chrome quá mới mà Selenium quá cũ có thể lệch; nâng version trong `build.gradle` nếu cần.
