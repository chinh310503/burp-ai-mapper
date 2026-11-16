# Thiết kế lại Guard chống AI phá hoại dữ liệu

> Mục tiêu: AI crawl để map sitemap/API **mà không bao giờ thực sự phá hoại dữ liệu**
> (xóa, logout, thanh toán, reset…), trên **mọi** site — không vá theo từng trang.
> Tài liệu này nêu nguyên tắc (có research), chỉ ra lỗ hổng hiện tại, và thiết kế guard
> nhiều tầng kèm kế hoạch triển khai.

Ngày: 2026-11-16.

---

## 0. Lỗ hổng của guard hiện tại (bằng chứng thực tế)

Guard đang kiểm tra **hai thứ**: nhãn phần tử được **click** (`DESTRUCTIVE_TEXT`) và URL của
**goto/href** (`DESTRUCTIVE_URL`). Nhưng một form bị **submit** theo nhiều cách khác:

- **Nhấn Enter trong một ô input** (`type` với `enter=true`) → submit form **không qua nút**.
- Click một phần tử bất kỳ trong form kích hoạt submit; JS auto-submit.

Trên site test TrickShop, agent đã **thực sự thanh toán**:
```
step 43 @ /checkout: type #9 [card] <= '4111 1111 1111 1111'  (enter=true → submit)
step 44 @ /checkout/pay: ...        ← POST /checkout/pay ĐÃ CHẠY
```
Trong khi **click** nút "Pay now" thì bị chặn đúng (`BLOCKED destructive click label='Pay now'`).
→ Guard dựa vào "mục tiêu được click" nên **Enter-submit lọt qua**. Đây là lỗ hổng **tổng quát**.

---

## 1. Nguyên tắc (tổng hợp từ research)

**Từ web scanner (ZAP / Burp / Escape / Pentest-Tools):**
- **Safe mode / read-only**: chỉ cho thao tác không nguy hiểm; ZAP có *Safe mode* (chỉ non-dangerous),
  Escape DAST có `read_only` (chỉ test thao tác phân loại là **read**). [refs ở mục 5]
- **Loại trừ route phá hoại TRƯỚC khi quét**: delete, logout, **purchase/pay**, messaging. Dùng test account + test data.
- **State-aware**: method **non-GET** (POST/PUT/PATCH/DELETE) = state-changing → ưu tiên cẩn trọng.
- Quan sát thụ động (passive) là an toàn nhất (không đổi gì) nhưng phủ thấp.

**Từ guardrail cho AI agent:**
- **Chặn ở TẦNG HÀNH ĐỘNG, không tin prompt.** Prompt chỉ là "mềm"; chặn tất định ở nơi
  quyết định thành hành động thật mới chắc. (Extension đã đi đúng hướng: guard ở code.)
- **Phân loại theo HẬU QUẢ**: read → tự do; write/delete/pay → qua cổng. ("Nếu agent làm sai 100 lần thì tệ cỡ nào?")
- **Fail-closed**: không chắc thì **chặn**.
- **Không dựa LLM judge** để quyết định chặn (cùng điểm yếu prompt-injection với agent).
- **Blast radius**: giới hạn thiệt hại kể cả khi đã cho phép (quota, ít quyền, dừng sau vài lần).

**Hệ quả cho extension:** guard phải (a) nhận diện **mọi cách mutate**, không chỉ click; (b) xét **chính form bị submit**
(method + action + nhãn submit + loại field), không chỉ phần tử agent chạm; (c) **fail-closed** theo method non-GET phá hoại.

---

## 2. Guard nhiều tầng (thiết kế)

### Tầng A — Phân loại hành động: READ hay MUTATE
| Hành động | Loại |
|---|---|
| `goto` GET, `back`, `wait`, click **link điều hướng**, `type` **không** submit | READ (an toàn) |
| **Bất kỳ submit form nào**: click nút submit, `type` + Enter, click phần tử trong form kích hoạt submit | MUTATE → phải qua cổng |

### Tầng B — Denylist phá hoại, áp đúng mục tiêu
- `goto`/href → `DESTRUCTIVE_URL` (như hiện tại).
- click control (không submit) → `DESTRUCTIVE_TEXT` theo nhãn (như hiện tại).
- **MỚI — khi là MUTATE (submit form):** lấy **ngữ cảnh form** bao quanh phần tử và chặn nếu:
  `method != GET` **và** ( `action` khớp `DESTRUCTIVE_URL` **hoặc** nhãn nút submit khớp `DESTRUCTIVE_TEXT`
  **hoặc** form chứa field thanh toán: `card/cardnumber/cc-num/cvv/cvc/iban/routing/account-number` ).
  → **bịt Enter-submit** và mọi kiểu submit khác, không chỉ click nút.

### Tầng C — Chế độ an toàn (mode)
- **"Block destructive" (mặc định, luôn bật):** chặn phá hoại (delete/logout/reset/pay…), **cho phép**
  write lành (search, create/edit bằng test data) để vẫn map được endpoint.
- **"Read-only / No mutations" (tùy chọn — đề xuất thêm cho production):** chặn **MỌI** submit non-GET
  (kể cả create/edit), chỉ còn GET + đọc JS + (tương lai) OPTIONS. Tương đương *Safe mode* của ZAP,
  dùng khi crawl trên môi trường thật của khách. Vẫn **map** được endpoint ghi nhờ: harvest `form[action]`,
  trích endpoint từ JS (A1), và OPTIONS probe — tức biết endpoint tồn tại mà không gọi mutate.

### Tầng D — Blast radius / chống lặp
- Một action bị chặn **≥ 3 lần** (theo chữ ký) → đưa vào tập "đừng gợi ý lại" + bỏ khỏi frontier →
  hết cảnh agent bấm "Pay now" hàng chục lần (quan sát thấy ~20+ lần trong log).
- Dedup theo shape (đã có) giới hạn số lần chạm mỗi endpoint.

### Tầng E — Audit + khả nghịch (đã có, giữ nguyên)
- `mutations.json` ghi mọi non-GET đã gửi; `rollback-plan.json` + auto-rollback edit; **không tự xóa** create.
- Guard là tầng **phòng ngừa**; audit/rollback là tầng **khắc phục** — hai lớp bổ trợ.

---

## 3. Triển khai cụ thể (điểm chạm code)

Trong `BrowserAgent.java`:

1. **JS helper `FORM_CONTEXT_JS`** (chạy với 1 đối số = `data-aimapper-idx`): từ phần tử tìm
   `el.closest('form')`, trả JSON `{method, action, submitLabels:[...], fieldNames:[...]}`
   (nhãn mọi `button[type=submit]`/`input[type=submit]`; tên các input).
2. **`destructiveSubmit(formCtx, extra)`**: `true` nếu `method.toUpperCase() != 'GET'` và
   ( `isDestructiveUrl(action)` ∨ `DESTRUCTIVE_TEXT.matcher(join(submitLabels))` ∨ `PAYMENT_FIELD.matcher(join(fieldNames))` ).
   - `PAYMENT_FIELD = (?i)\b(card(number|no)?|cc[-_]?num|cvv|cvc|iban|routing|account[-_]?(no|number))\b`.
3. **Chèn vào cổng chặn trước `execute`** (hàm `destructiveReason` hoặc ngay trước thực thi):
   - `type` + `enter=true` → tính `FORM_CONTEXT` cho phần tử; nếu `destructiveSubmit` → **chặn** (bỏ Enter), log
     `BLOCKED destructive form submit (Enter) on <action>`.
   - `click` → nếu phần tử là submit/nằm trong form sẽ submit → tính `FORM_CONTEXT`; nếu `destructiveSubmit` → chặn.
   - Giữ nguyên check cũ cho goto/href/label.
4. **Chống lặp:** `Map<String,Integer> blockedCount` theo `signature`; khi ≥ 3 → thêm vào `done` ("đã làm")
   và không enqueue → model không được gợi ý lại.
5. **(Tùy chọn) Read-only mode:** cờ `cfg.readOnly`; nếu bật thì **mọi** MUTATE (submit non-GET) đều chặn,
   không chỉ loại phá hoại. (Có thể bật qua từ khóa trong Task prompt để không thêm setting UI, hợp với
   chủ trương tối giản settings — ví dụ prompt chứa `[read-only]`.)

**Lưu ý thiết kế:** mọi quyết định chặn là **tất định ở code**, dựa trên DOM thật (form/method/label/field),
**không** hỏi lại LLM và **không** phụ thuộc chuỗi path riêng của site nào → đúng cả trên site chưa từng thấy.

---

## 4. Nghiệm thu

- **TrickShop:** gõ số thẻ ở `/checkout` rồi Enter → log `BLOCKED destructive form submit` và **`POST /checkout/pay`
  KHÔNG xuất hiện** trong `endpoints.json`/`mutations.json`. `delete`/`logout` vẫn bị chặn. `create`/`edit` lành
  vẫn chạy (vẫn map được các endpoint ghi). Agent không bấm "Pay now" quá 3 lần.
- **Tổng quát:** một form `POST action=/orders/place` nhãn "Place order", hoặc form có field `cvv`, bị chặn submit
  dù agent dùng Enter hay click; một form `GET /search` thì không bị chặn.

## 5. Nguồn tham khảo
- ZAP Safe/Protected mode & rủi ro quét live: [ZAP FAQ – danger scanning live site](https://www.zaproxy.org/faq/is-there-any-danger-when-scanning-with-zap-against-a-live-website-e-g-create-delete-update-corrupt-data/)
- Escape DAST read-only (chỉ test read): [Escape safe-mode](https://docs.escape.tech/documentation/dast/webapp-testing/safe-mode/)
- Loại trừ route phá hoại trước khi quét: [Pentest-Tools website-scanner](https://pentest-tools.com/docs/tools/website-scanner)
- Burp crawl/audit tách pha (crawl-only để map form không gửi payload): [Burp auditing](https://portswigger.net/burp/documentation/scanner/auditing.html)
- Guard ở tầng tool, không tin prompt: [zeroentropy agent guardrails](https://www.zeroentropy.dev/concepts/agent-guardrails/) · [Octomind](https://octomind-web.pages.dev/blog/ai-agent-guardrails)
- Phân loại theo hậu quả + blast radius: [Approval gates & blast radius](https://dev.to/hassann/ai-agent-guardrails-approval-gates-and-blast-radius-control-23hl)
- LLM judge là tầng yếu: [agentic-ai guardrails](https://agentic-ai-wiki.vercel.app/deep-dives/guardrails)
- An toàn tool-use (học thuật): [arXiv 2601.08012](https://www.alphaxiv.org/abs/2601.08012.md)
