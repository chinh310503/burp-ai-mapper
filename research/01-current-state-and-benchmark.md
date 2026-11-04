# 01 — Hiện trạng extension & phân tích benchmark

## 1. Kiến trúc hiện tại (burp-ai-mapper)

Extension Burp thuần Java (Montoya API), đóng gói fat JAR. Luồng:

```
MapperTab (UI Swing)
   └─ BrowserAgent (Selenium ChromeDriver, proxy = Burp)
        vòng lặp: extract links + elements → LlmClient.chat() → 1 JSON action → execute
   └─ Export: SiteMapExtractor (đọc Burp site map) → SitemapGenerator / OpenApiGenerator
```

Thành phần:
- `BrowserAgent` — vòng lặp AI. Frontier (URL chưa thăm, dedupe theo "shape"),
  guard chặn hành động phá hoại, điều kiện dừng frontier-based.
- `LlmClient` — OpenAI-compatible `/chat/completions` + `/models` (JDK HttpClient + Gson).
- `PathTemplater` — `/123` → `/{id}`; tạo "signature" để gom URL cùng endpoint.
- `SiteMapExtractor` / `SitemapGenerator` / `OpenApiGenerator` — xuất từ Burp site map.

### Cơ chế thu thập link (quan trọng)
- Frontier chỉ nạp từ `LINKS_JS = querySelectorAll('a[href]')` → **chỉ thẻ `<a href>`**.
- Element list cho LLM: `querySelectorAll('a,button,input,select,textarea,[role=button],[onclick]')`.
- `normalizeUrl()` **cắt bỏ phần `#...`** của URL.
- Không duyệt vào `<iframe>`, không xử lý shadow DOM.
- API chỉ được bắt khi browser gọi (qua Burp proxy); extension **không tự bắt network**.

## 2. Benchmark target: `web-benmark` (Nexora)

Một web Express (EJS) cố tình phơi link qua **nhiều cơ chế khác nhau**, có login
session thật (2 role), và CRUD thật. Có sẵn **ground-truth** để chấm điểm.

### Tài khoản test (`TEST-ACCOUNTS.md`)
| Role | username / email | password | mở khóa |
| --- | --- | --- | --- |
| customer | `user` / `user@demo.test` | `Passw0rd!` | `/dashboard /orders /cart /checkout /settings /profile` |
| admin | `admin` / `admin@demo.test` | `Adm1n!` | tất cả + `/admin/*` |

Login: `POST /login` field `username`+`password`, set cookie `connect.sid`, redirect
`/dashboard` hoặc `?next=`. Sai → 401. **Không hiển thị account trên trang login** (auto-login là bài test thật).

### Ground-truth (chấm điểm)
- `ground-truth.urls.txt` — **109 GET page URL** (phẳng, để `diff`/`comm`).
- `ground-truth.json` — **128 route** kèm metadata: `method`, `auth` (none/customer/admin),
  `group`, `inSitemapXml`, `jsRequired`, `isPage`, `note`.
- Tổng: 50 trong sitemap.xml · 4 unlisted public · 12 customer · 32 admin · 9 cần JS · 10 CRUD (POST).

### Các cơ chế phơi link (độ khó tăng dần)
- **A. Dễ** — `<a href>` nav/footer, breadcrumb, card, pagination `?page=N`, `sitemap.xml`+`robots.txt`.
- **B. Cần tương tác / suy luận attribute** — dropdown & mega menu (ẩn tới khi click),
  tab panel, modal, accordion `<details>` (`/faq`), `onclick="location.href"`,
  `data-href`/`data-url`/`data-go`, `<select>` jump menu (onchange), image map `<area href>`, `<noscript>` link.
- **C. Động / cần JS** — link JS sinh ra, link nạp bằng AJAX (`fetch /api/links`),
  "Load more" (`/api/products?offset=`), **SPA History API** (`/app/features`, `/app/docs/webhooks`…),
  **hash routing** (`#/settings`, `#/billing`), link trong **iframe** (`/embed`→`/embed/inner`).
- **D. Form** — GET search (query string), POST contact, **login (auth)**.
- **E. Auth-gated** — customer & admin tiers (cần auto-login).
- **F. CRUD "chức năng"** — Products/Users (admin) + Reviews (customer). Mọi write là **POST**
  (không có GET phá hoại), authz enforced (anon→403, sai owner→403). Có `POST /admin/reset` khôi phục seed.
- **G. Edge** — URL tương đối, anchor fragment, `mailto:`/`tel:`/external, **"unlisted" pages**
  (`/careers /gift-cards /deals/flash /deals/clearance` — linked in-page nhưng **không có trong sitemap.xml**),
  xử lý 404/403.

### API JSON thật trong benchmark
- `GET /api/links` — trả list link (chỉ thấy khi chạy JS hoặc gọi thẳng).
- `GET /api/products?offset=N` — "load more".

## 3. Thang điểm benchmark
- **Baseline**: hết nhóm A + sitemap/robots (~40+ URL).
- **Good**: thêm nhóm B, form nhóm D, các trang unlisted.
- **Strong**: auto-login + liệt kê nhóm E (cả 2 tier) gồm form create/edit nhóm F.
- **Excellent**: chạy JS bắt nhóm C (AJAX, SPA route, iframe, load-more) + route client-only
  như `/app/docs/webhooks`; tùy chọn chạm CRUD nhóm F rồi `POST /admin/reset`.

> Điểm mấu chốt: các trang "unlisted" test **follow link thật vs chỉ đọc sitemap.xml**.
> Hash route + iframe + SPA test **thực thi JS**.
