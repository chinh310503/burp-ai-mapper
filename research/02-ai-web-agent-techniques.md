# 02 — Kỹ thuật agent duyệt web (state of the art)

## 1. Ba kiến trúc agent

| Kiến trúc | Đại diện | Cách "nhìn" trang | Ưu / nhược |
| --- | --- | --- | --- |
| **DOM-first** | browser-use | Parse DOM, gắn index cho element tương tác | Rẻ, chính xác selector; yếu với canvas/widget không nhãn |
| **Hybrid** | Stagehand | Accessibility tree để hành động + DOM/screenshot bù chỗ thiếu | Cân bằng tốt nhất hiện nay |
| **Vision-first** | Skyvern | Screenshot + toạ độ | Bao phủ UI lạ; tốn token, toạ độ sai ±10–20px, chậm 1–3s |

**Đồng thuận**: mặc định dùng **accessibility tree / DOM đã lọc**, chỉ **fallback vision**
khi tree/DOM không đủ (canvas, widget không nhãn). Raw DOM quá lớn cho context;
a11y tree thường chỉ 5–10% kích thước raw DOM.

→ Extension hiện tại là **DOM-first** (đúng hướng). Cải thiện nên theo *hybrid nhẹ*:
DOM đã lọc là chính, thêm **vision tùy chọn** làm fallback, không mặc định.

## 2. Vòng lặp action (ReAct)

Chuẩn: **observe → decide → act → evaluate** lặp lại.
- Observe: URL, title, DOM/elements đã đánh index (+ screenshot nếu bật vision).
- Decide: LLM trả reasoning + 1 (hoặc nhiều) action.
- Act: automation thực thi theo index.
- Evaluate: so trạng thái trước/sau để biết có tiến triển không.

Điểm cải thiện so với hiện tại:
- **Multi-action mỗi bước** (vd mở lần lượt 3 menu) giảm số lần gọi LLM.
- **Trạng thái giàu hơn**: cho model thấy "đã bắt bao nhiêu endpoint", "coverage %",
  "frontier còn gì" để quyết định tốt hơn (hiện đã có frontier, thiếu endpoint-captured).

## 3. Độ tin cậy output LLM — Structured Outputs

- **JSON mode** chỉ đảm bảo *parse được*, **không** đảm bảo đúng schema.
- **Structured Outputs `strict: true`** (function calling / `response_format: json_schema`)
  đảm bảo arg khớp JSON Schema — giảm hẳn lỗi thiếu field / sai kiểu / hallucinate param.
- Nếu endpoint không hỗ trợ strict → dùng **validation + retry**.
- Lưu ý **reasoning models**: nội dung có thể nằm ở field khác (`reasoning_content`),
  cần đọc linh hoạt thay vì giả định luôn có `message.content`.

→ Hiện `LlmClient.chat()` giả định luôn có `message.content`, không retry, hardcode
`temperature=0.2`, không có max_tokens, không structured output. Cần nâng cấp.

## 4. Bao phủ link nâng cao (các cơ chế khó)

- **pushState / replaceState (SPA)**: hook `history.pushState`/`replaceState`, và đọc
  link từ **DOM đã render** (không chỉ HTML gốc). Coi mỗi client-route là 1 URL riêng.
- **Hash routing**: coi mỗi `#/route` là URL phân biệt (⚠ hiện extension *cắt* hash).
- **Nhận diện framework SPA**: `data-reactroot` (React), `data-v-` (Vue), `ng-version`
  (Angular) → biết cần render JS.
- **Chờ ổn định**: theo dõi network idle + DOM mutation trước khi extract (thay vì sleep cứng).
- **Shadow DOM**: `querySelectorAll` **không** xuyên shadow root → phải duyệt đệ quy
  `element.shadowRoot` (open). Closed shadow không với tới được.
- **Iframe**: a11y tree/`querySelectorAll` **không** vượt biên frame → phải `switchTo().frame()`
  (same-origin) và extract riêng.
- **Element phi-anchor điều hướng**: `[data-href]/[data-url]/[data-go]`, `onclick=location.*`,
  `<select><option value=url>`, image map `<area href>`, `<form action>` → cần trích riêng,
  không chỉ `a[href]`.

## 5. An toàn / không phá hoại (đúng mục tiêu dự án)

- Guard **ở tầng code** (không chỉ prompt) là đúng — giữ lại.
- Nhưng cần **tách "điều hướng/đọc trang" khỏi "thực thi mutation"**: một trang GET tên
  `/checkout` là trang để *map*, không phải hành động phá hoại. Chỉ nên chặn **submit/
  click nút** thực sự gây mutation (POST delete/pay/transfer…), không chặn *mở trang*.
- **Non-destructive crawling**: ưu tiên đọc endpoint qua DOM/CDP thay vì kích hoạt;
  nếu có môi trường throwaway, cho phép chạm CRUD rồi `POST /admin/reset`.

---
### Nguồn
- [The Framework Wars: browser-use, Stagehand, Skyvern](https://dev.to/stevengonsalvez/browser-tools-for-ai-agents-part-2-the-framework-wars-browser-use-stagehand-skyvern-4gn)
- [Browser agent perception: vision vs DOM (Notte)](https://www.notte.cc/glossary/ai-browser-agents/how-does-a-browser-agent-perceive-a-page-vision-vs-dom)
- [AI Browser Automation: DOM vs Accessibility Tree vs Vision](https://aismith.dev/insights/browser-automation-for-ai-agents-dom-vs-vision)
- [Three Architectures of Browser Agents](https://dev.to/alexey_sokolov_10deecd763/runtime-snapshots-16-the-three-architectures-of-browser-agents-4gkc)
- [OpenAI Structured Outputs for JSON](https://sdtimes.com/ai/openai-adds-support-for-structured-outputs-for-json-in-its-api/)
- [OpenAI function calling (help center)](https://help.openai.com/en/articles/8555517)
- [SPA route discovery (crawler.sh glossary)](https://crawler-sh.pages.dev/glossary/single-page-application/)
