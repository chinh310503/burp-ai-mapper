# Dự án tương tự & ý tưởng cải tiến — burp-ai-mapper

> Mục tiêu công cụ: dùng **AI tự duyệt** website (login + đi hết chức năng) để thu
> **sitemap + danh sách API/endpoint** một cách **tự động, không phá hoại dữ liệu**,
> phục vụ việc **kiểm tra độ phủ của pentester** (họ đã test hết endpoint chưa).
> Đây là bài research mở rộng (tiếp nối 01–05), khảo sát các dự án cùng hướng và
> rút ra hạng mục cải tiến có thể làm.

Ngày: 2026-11-14.

---

## 0. Tool hiện tại đang làm gì (tóm tắt cơ chế)

Để so sánh cho đúng, đây là cách `burp-ai-mapper` vận hành (đọc từ source):

1. **Trình duyệt thật qua Burp.** Selenium lái Chrome, Chrome trỏ proxy = Burp
   (`--proxy-server`, `--proxy-bypass-list=<-loopback>`, `acceptInsecureCerts`).
   Toàn bộ traffic do Burp bắt → nguồn dữ liệu chính là **Burp site map**.
2. **Vòng lặp agent (DOM-first).** Mỗi bước:
   - `REVEAL_JS` mở hết `<details>`/dropdown/menu/`[hidden]` (thuần DOM, không điều hướng).
   - `exhaustPage()` cuộn + bấm nút "load more" (chỉ theo text an toàn).
   - `HARVEST_JS` thu **mọi** URL điều hướng (a/area/form/data-*/onclick/onchange/
     select) xuyên **shadow DOM mở** + **iframe same-origin** → nạp **frontier**.
   - `EXTRACT_JS` liệt kê element tương tác (đánh index) → hỏi LLM **1 action JSON**
     (`click/type/goto/back/wait/done`) → Selenium thực thi.
3. **Khử trùng theo "shape".** `PathTemplater.signature()` rút URL về path template
   + tên query đã sort; chỉ nạp `SAMPLES_PER_TEMPLATE` (mặc định 2) URL/shape.
4. **Điều kiện dừng frontier-based** (chống dừng sớm): hoàn tất khi frontier rỗng
   và không có hoạt động mới trong `DONE_STABLE` bước; kẹt thì nhảy URL chưa thăm.
5. **An toàn (code-level guard).** `DESTRUCTIVE_URL` (không nạp/không goto) +
   `DESTRUCTIVE_TEXT` (không click nút Pay/Delete/Logout…); cổng chặn cuối trước mọi
   thao tác. Đa tài khoản = re-auth bằng code (xoá cookie rồi login tier kế).
6. **Audit + hoàn tác.** `mutations.json` (mọi non-GET đã gửi), `rollback-plan.json`,
   auto-rollback edit về giá trị gốc, và restore qua reset endpoint.
7. **Xuất.** `sitemap.xml` (gom shape) · `urls.json` · `endpoints.json` ·
   `openapi.json` (schema đệ quy req+response, gộp mẫu, security scheme, tags) ·
   redact secret · chấm điểm vs ground-truth.

**Điểm mạnh so với mặt bằng chung:** nguồn traffic là Burp (thấy cả body
req/response — thứ mà crawler link-only không có); guard phá hoại ở tầng code;
multi-account; gộp client-only route (hash/pushState) vào export; có sẵn khung
chấm điểm coverage. Đây đã là một thiết kế tốt và khá độc đáo (ít tool ghép
đúng 3 thứ: AI-driven + proxy-capture + non-destructive guard).

---

## 1. Các dự án tương tự trên GitHub / thị trường

### 1.1 AI browser agents (khung để "AI lái trình duyệt")

| Dự án | Cách "nhìn" trang | License | Liên quan |
|---|---|---|---|
| **browser-use** | DOM-first: feed LLM danh sách element tương tác đã đánh index | MIT | **Rất giống** cách tool đang làm (`EXTRACT_JS`). Repo ~80–110K★, nhiều kỹ thuật DOM/serialize đáng tham khảo. |
| **Stagehand** (Browserbase) | Lai code + AI, dùng **accessibility tree** qua CDP; `act/extract/observe`; **cache action** | MIT | Ý tưởng a11y-tree + cache action (chỉ gọi LLM khi trang đổi) → giảm chi phí/bước. |
| **Skyvern** | Vision-first: LLM + computer vision, reason trên screenshot | AGPL-3.0 | Đối chiếu với "Vision fallback" (P4) của tool. Mạnh ở UI vỡ cấu trúc, đắt/bước. |
| **Playwright MCP** | Cho LLM điều khiển Playwright qua MCP | MIT | Hướng MCP (xem §3). |

> Kết luận: tool đang theo trường phái **DOM-first giống browser-use** — hợp lý vì
> rẻ và tất định hơn vision. `browser-use` là nơi tốt nhất để "mượn" cách serialize
> DOM, xử lý iframe/shadow, và xử lý element stale.

### 1.2 Crawler endpoint discovery "kinh điển" của pentest

| Tool | Kiểu | Ghi chú |
|---|---|---|
| **katana** (ProjectDiscovery) | Active crawler, có **headless + parse JS**, phân loại REST/GraphQL/XHR | Mạnh nhất nhóm CLI; chế độ thường không render JS → sót endpoint động. |
| **hakrawler** | Nhẹ, đọc URL từ stdin, in link + vị trí file JS | Nhanh, không render JS. |
| **gau** | **Passive**: lấy URL lịch sử từ Wayback/Common Crawl/OTX | Không gửi request tới target → bổ sung endpoint **cũ/ẩn** mà crawl live không thấy. |
| **LinkFinder / jsluice / xnlinkfinder** | Trích endpoint từ **JavaScript** (regex / parser) | `jsluice` dùng parser thật (ít false-positive hơn LinkFinder regex) + trích secret. |

> Bài học: crawl live (như tool đang làm) **bỏ sót 2 nguồn**: (a) endpoint chỉ nằm
> trong **JS bundle** chưa bao giờ bị gọi lúc duyệt; (b) endpoint **lịch sử** trong
> archive. Ghép thêm 2 nguồn này tăng coverage đáng kể mà vẫn **read-only**.

### 1.3 Burp + AI (cùng hệ sinh thái)

- **ReconAIzer** (Jython): endpoint enumeration, parameter/subdomain/header analysis.
- **Burpference**: đẩy req/resp từ proxy history sang LLM phân tích (single endpoint
  hoặc cả domain).
- **Burp AI Agent (six2dez)** + **PortSwigger official MCP server** (04/2025): cho AI
  agent điều khiển Burp qua **MCP** (đọc HTTP history, replay…).
- **Burp built-in AI** (Pro 2025.2+): gửi prompt qua cloud PortSwigger.

> So sánh: các tool trên thiên về **phân tích/điều khiển Burp**, không tự **duyệt
> chức năng** như tool của bạn. Khác biệt lõi của bạn vẫn giữ nguyên giá trị.

### 1.4 Traffic → OpenAPI (phần export)

- **mitmproxy2swagger** (Python, alufers): HAR/mitmproxy flow → OpenAPI 3.0, có merge.
- **mitm2openapi** (Rust, arkptz): rewrite; quy trình 2 bước **discover → (người
  duyệt) → generate** (có bước con người xác nhận template trước khi sinh spec).

> Bài học: thêm **bước review template giữa chừng** (discover → curate → generate)
> giúp spec sạch hơn — có thể làm tùy chọn cho `openapi.json`.

### 1.5 API discovery toàn diện + GraphQL

- **Vespasian (Praetorian)**: lái **katana headless (full JS)**, ghi mọi request;
  import được Burp/HAR/mitmproxy. **Chấm điểm confidence** mỗi request (content-type,
  URL pattern, method, cấu trúc response, loại bỏ static) thay vì lọc nhị phân.
  **Path normalization** (số/UUID → `{id}`, giữ literal như `/users/me`). **GraphQL
  tiered introspection** (3 tầng payload nhỏ dần để né WAF; fallback suy schema từ
  query quan sát). **Probe read-only**: `OPTIONS` (method + CORS), `?wsdl`, introspection.
- **Goctopus / InQL / gqlhunter / graphqlnomad**: dò path GraphQL + introspection;
  InQL là extension Burp.
- **crawlex (SPA observer)**: inject trước script trang, **wrap history API** để ghi
  `pushState/replaceState` → dựng lại route graph chuẩn hơn.

> Bài học lớn nhất từ Vespasian: **tách capture khỏi analyse** (ghi traffic log trung
> gian, xử lý offline), **confidence scoring** cho endpoint, **probe read-only
> (OPTIONS/introspection)** trước khi đụng gì có side-effect.

---

## 2. Khoảng trống của tool hiện tại (gap)

1. **Chỉ bắt endpoint bị *gọi* lúc duyệt.** Endpoint nằm trong JS bundle nhưng app
   không gọi (admin-only, feature flag, path dựng động) → **không vào Burp** → sót.
2. **Không có nguồn passive** (archive) → sót endpoint cũ/deprecated — mà đây lại là
   thứ pentest hay bỏ quên nhất.
3. **GraphQL** mới nhận diện theo path/content-type, **chưa introspection** → với app
   GraphQL thì "sitemap API" gần như rỗng.
4. **Phân loại API nhị phân** (`looksLikeApi`) → dễ lẫn static/analytics hoặc bỏ sót;
   chưa có confidence score.
5. **Coverage metric còn mỏng**: `Score vs ground-truth` cần có sẵn ground-truth. Khi
   mục tiêu là *kiểm tra pentester đã test đủ chưa*, cần so **"endpoint đã map" vs
   "endpoint pentester đã chạm"** — chưa có.
6. **Chi phí/độ ổn định LLM**: gọi LLM mỗi bước; chưa cache, chưa có bước tất định
   "đi hết frontier không cần LLM" khi trang đơn giản.
7. **Phủ theo *vai trò*** chưa rõ: multi-account có crawl từng tier, nhưng export
   **gộp chung**, không đánh dấu *endpoint nào thấy ở role nào* (BOLA/BFLA coverage).

---

## 3. Đề xuất cải tiến (ưu tiên theo giá trị/chi phí)

### Nhóm A — Tăng coverage, vẫn read-only (giá trị cao, rủi ro thấp)

- **A1. Trích endpoint từ JS bundle (static).** Sau/khi crawl, với mỗi file `.js`
  Burp đã thấy, chạy một bộ trích kiểu **jsluice/LinkFinder** (regex + AST nhẹ) để lấy
  path `"/api/..."`, template literal, `fetch(`/`axios`/`XMLHttpRequest` targets.
  Đánh dấu `source: "js-static"` trong `urls.json`. → bắt endpoint **chưa bị gọi**.
  *Không phá hoại* vì chỉ đọc file JS. Đây là nâng cấp coverage lớn nhất.
- **A2. Seed passive từ archive (tùy chọn).** Thêm ô "Use archive (gau-style)": fetch
  Wayback/Common Crawl cho host target (qua browser → vẫn vào Burp), seed vào frontier.
  Chỉ GET, in-scope, qua guard. → bắt endpoint cũ/ẩn.
- **A3. Probe read-only.** Cho các shape đã biết: gửi **`OPTIONS`** (đọc Allow +
  CORS) để lộ method khác (PUT/DELETE tồn tại mà không cần gọi chúng), và với
  endpoint JSON thử thêm `Accept: application/json`. Thuần đọc, không side-effect.
- **A4. GraphQL introspection (tiered).** Khi phát hiện `/graphql`: POST introspection
  3 tầng (full → no-desc → tối giản) qua **browser fetch** (vẫn vào Burp + dùng
  session sống); fallback suy schema từ query quan sát. Xuất `graphql.json`.
  Introspection là **read-only** (query, không mutation) → an toàn.

### Nhóm B — Chất lượng phân loại & spec

- **B1. Confidence scoring** thay `looksLikeApi` nhị phân: cộng điểm theo content-type
  JSON/GraphQL, path `/api|/v1|/graphql|/rest`, method non-GET, response có cấu trúc,
  trừ điểm static (`.js/.css/.png…`, cache-control immutable). Ngưỡng cấu hình được.
- **B2. Discover → curate → generate cho OpenAPI** (học mitm2openapi): xuất bản nháp
  template cho người xác nhận trước khi sinh spec cuối (tùy chọn).
- **B3. Giữ hand-edit khi xuất lại** `openapi.json` (merge) — đã nằm trong "dời" của P3.

### Nhóm C — Đúng mục tiêu "đo độ phủ pentester"

- **C1. Coverage report = (đã map) ∩ (đã test).** Đây là **tính năng lõi cho mục tiêu
  của bạn**. Input: tập endpoint do tool map được (shape-level). Đối chiếu với traffic
  **do pentester tạo** — ví dụ lọc Burp site map theo công cụ nguồn (Repeater/Intruder/
  Scanner vs Proxy), hoặc theo khoảng thời gian, hoặc import một log riêng của pentester.
  Xuất: `coverage-report.(json|html)` liệt kê mỗi shape: **mapped? tested? method nào
  đã test?** + % phủ. → trả lời trực tiếp "pentester đã test đủ chưa".
- **C2. Phủ theo vai trò (per-role).** Khi multi-account, gắn thẻ **role** cho mỗi
  endpoint lần đầu thấy (`roles:[admin,user]`). Giúp soi BOLA/BFLA: endpoint admin mà
  role user cũng chạm được, hoặc endpoint chưa test ở role nào.
- **C3. Ma trận method × shape.** Export bảng method quan sát/suy luận cho mỗi shape
  (GET/POST/PUT/DELETE) để thấy "shape này mới test GET, chưa đụng DELETE".

### Nhóm D — Ổn định & chi phí agent

- **D1. Bước "drain frontier" tất định.** Khi trang nghèo tương tác hoặc frontier còn
  nhiều, **đi hết bằng goto tuần tự không gọi LLM** (như loop-breaker hiện có, nhưng
  chủ động hơn) → tiết kiệm token, nhanh hơn, phủ chắc hơn.
- **D2. Cache action theo chữ ký trang** (học Stagehand): cùng một signature trang →
  tái dùng quyết định trước, chỉ gọi LLM khi DOM đổi.
- **D3. A11y-tree làm nguồn phụ** cho element list (bổ sung EXTRACT_JS) để ổn định
  index khi layout động.
- **D4. Tùy chọn điều khiển Burp qua MCP** (PortSwigger MCP) — hướng tương lai nếu
  muốn tách agent khỏi extension.

### Nhóm E — An toàn (giữ là điểm mạnh)

- **E1. "Dry-run / read-only mode" tuyệt đối**: chế độ **không submit form nào** (chỉ
  GET + đọc JS + OPTIONS + introspection). Đúng tinh thần "chỉ lấy thông tin, không
  đụng dữ liệu" — phù hợp môi trường production của khách.
- **E2. Honeypot/field nhạy cảm**: mở rộng guard để **không bao giờ** gõ vào field
  kiểu card/CVV/amount kể cả khi không ở nút submit.

---

## 4. Lộ trình gợi ý (độc lập, nghiệm thu riêng)

1. **C1 (coverage report)** — đúng mục tiêu nhất, dùng ngay dữ liệu đang có. *Ưu tiên 1.*
2. **A1 (JS static extraction)** — tăng coverage lớn nhất, read-only. *Ưu tiên 1.*
3. **E1 (read-only mode)** — mở khoá dùng trên production khách. *Ưu tiên 2.*
4. **A4 (GraphQL) + A3 (OPTIONS probe)** — khi gặp target GraphQL/REST cần method map.
5. **B1 (confidence) + C2/C3 (per-role, method matrix)** — chất lượng báo cáo.
6. **D1/D2 (chi phí agent)** — tối ưu khi lõi đã vững.

## 5. Nguồn tham khảo

- browser agents: [So sánh Stagehand/browser-use/Skyvern (gist)](https://gist.github.com/kevinmichaelchen/9d77b8a681238cc45297dff969686175) · [AgentsCamp 2026](https://agentscamp.com/guides/comparisons/browser-agents-compared-2026)
- crawler pentest: [katana (Trickest)](https://trickest.com/tools/katana) · [hakrawler](https://trickest.com/tools/hakrawler) · [gau](https://trickest.com/tools/gau)
- JS endpoint: [LinkFinder](https://github.com/GerbenJavado/LinkFinder) · [jsluice + Semgrep](https://industrialmonitordirect.com/blogs/knowledgebase/extracting-urls-from-spa-javascript-jsluice-and-semgrep)
- Burp + AI: [ReconAIzer/Burpference/Burp AI Agent (ecosyste.ms)](https://awesome.ecosyste.ms/projects/github.com%2Fdreadnode%2Fburpference) · [burpgpt](https://github.com/aress31/burpgpt) · [PortSwigger AI extensibility](https://portswigger.net/blog/the-future-of-security-testing-harness-ai-powered-extensibility-in-burp-nbsp)
- traffic→OpenAPI: [mitmproxy2swagger](https://github.com/alufers/mitmproxy2swagger) · [mitm2openapi](https://awesome.ecosyste.ms/projects/github.com%2Farkptz%2Fmitm2openapi)
- API discovery + GraphQL: [Vespasian (Praetorian)](https://praetorian.com/blog/vespasian-api-endpoint-discovery-tool) · [Goctopus (Escape)](https://escape.tech/blog/introducing-goctopus/) · [InQL (Doyensec)](https://blog.doyensec.com/2020/03/26/graphql-scanner.html)
- đo coverage API: [KushoAI State of API Security 2026](https://reports.kusho.ai/state-of-api-security-2026) · [NHIMG: endpoint discovery chưa đủ](https://nhimg.org/faq/how-should-security-teams-test-apis-when-endpoint-discovery-is-incomplete/)
- crawl4ai / firecrawl (scraping agent nền): [crawl4ai](https://github.com/unclecode/crawl4ai) · [firecrawl](https://github.com/firecrawl/firecrawl)
