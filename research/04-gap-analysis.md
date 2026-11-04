# 04 — Phân tích khoảng cách (gap) vs mục tiêu & benchmark

Chấm hiện trạng extension theo từng test-case của benchmark Nexora.
Ký hiệu: ✅ làm được · ⚠️ một phần / phụ thuộc may rủi của LLM · ❌ thiếu · 🐞 bug.

## A. Bug / lỗ hổng an toàn (ưu tiên sửa ngay)

| # | Vấn đề | Chi tiết | Ảnh hưởng |
| --- | --- | --- | --- |
| 🐞1 | **Over-block `/checkout`** | `DESTRUCTIVE_URL` khớp token `checkout` → URL GET `/checkout` (trang auth-gated cần map) bị loại khỏi frontier **và** chặn điều hướng | **Mất 1 trang** benchmark yêu cầu; nhầm trang đọc = hành động phá hoại |
| 🐞2 | **Logout bị chặn ⇒ không đổi được tài khoản** | guard chặn `logout` ⇒ agent không thể logout để đăng nhập account thứ 2 | Không đạt mức *Excellent* (exercise **cả 2 tier**); khó test đa tài khoản |
| 🐞3 | **Cắt hash khỏi URL** | `normalizeUrl()` bỏ `#...` ⇒ `/app#/settings`, `/app#/billing` biến mất | Mất 2 hash-route trong ground-truth |
| 🐞4 | Comment/README lỗi thời | nhắc "Python sidecar" dù code đã thuần Java/Selenium | Gây hiểu nhầm |

## B. Bao phủ link — các cơ chế khó

| Cơ chế (nhóm benchmark) | Hiện trạng | Vì sao |
| --- | --- | --- |
| `<a href>`, footer, breadcrumb, card, pagination (A) | ✅ | `LINKS_JS` bắt mọi `a[href]` |
| sitemap.xml / robots.txt (A) | ⚠️ | Chỉ bắt nếu browser/agent ghé; không chủ động fetch |
| Dropdown / mega menu / tab / modal / accordion (B) | ⚠️ | Phụ thuộc LLM chịu click; không có pass "mở hết" tất định |
| `onclick=location.href`, home hero (B) | ⚠️ | `[onclick]` có trong element list nhưng link **không** vào frontier |
| `data-href` / `data-url` / `data-go` (B) | ⚠️/❌ | Chỉ thấy nếu nằm trên tag được liệt kê; link không vào frontier |
| `<select>` jump menu (B) | ⚠️ | `select` có trong element list; option-value-URL không vào frontier |
| Image map `<area href>` (B) | ❌ | Không trích `<area>`, không vào frontier |
| `<noscript>` link (B) | ❌ | Không render khi bật JS |
| Link JS sinh ra (C) | ✅ | Là `a[href]` sau khi JS chạy |
| AJAX link `/api/links` (C) | ⚠️ | Chỉ khi ghé `/discover`; là anchor sau fetch |
| "Load more" `/api/products?offset` (C) | ⚠️ | Cần click nút load-more (LLM) |
| **SPA pushState** `/app/*` (C) | ⚠️ | `href="/app/x"` vào frontier; nhưng điều hướng/khám phá chưa chắc chắn |
| **Hash route** `#/settings` (C) | ❌ | Bị `normalizeUrl` cắt (🐞3) |
| **Iframe** `/embed/inner` (C) | ❌ | Không `switchTo().frame()`, không trích trong frame |

## C. Auth & chức năng

| Mục | Hiện trạng |
| --- | --- |
| Auto-login 1 account | ⚠️ LLM tự đọc form điền; không có login flow tất định/kiểm chứng |
| Đa tài khoản / 2 tier | ❌ (do 🐞2) |
| Create/Edit form (nhóm F) | ⚠️ Điền form phụ thuộc LLM; không có chiến lược điền dữ liệu test an toàn |
| Delete / reset (nhóm F) | ✅ chặn (đúng mục tiêu không phá hoại) |

## D. Bắt API & OpenAPI

| Mục | Hiện trạng |
| --- | --- |
| Nguồn traffic | ❌ chỉ Burp proxy; không CDP; agent không thấy coverage endpoint |
| Response schema | ❌ không dùng response body |
| JSON body schema | ⚠️ chỉ lưu example thô, không parse cây |
| Security scheme / tags | ❌ |
| Redact secret khi xuất | ❌ |

## E. LLM client & vận hành

| Mục | Hiện trạng |
| --- | --- |
| Structured output / strict schema | ❌ (tự cắt `{...}` từ text) |
| Retry khi reply hỏng | ❌ |
| Cấu hình temperature/max_tokens | ❌ hardcode |
| Reasoning model (content field khác) | ❌ giả định `message.content` |
| Lưu cấu hình giữa phiên | ❌ gõ lại mỗi lần |
| Chấm điểm vs ground-truth | ❌ phải `comm` thủ công |

## Tổng kết mức đạt dự kiến (chưa cải thiện)
Khoảng **Good → chớm Strong**: lấy tốt nhóm A, phần lớn B/D nếu LLM chịu khó, các
trang unlisted (nhờ follow link). **Mất**: hash route, iframe inner, image map,
`/checkout`, đa tài khoản; **yếu**: SPA/load-more/menu tất định, chất lượng OpenAPI.

→ Chi tiết lộ trình đưa lên **Excellent** + OpenAPI chất lượng: xem
[IMPROVEMENT-PLAN.md](IMPROVEMENT-PLAN.md).
