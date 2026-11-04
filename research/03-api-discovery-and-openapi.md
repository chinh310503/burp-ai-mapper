# 03 — Bắt API & sinh OpenAPI

## 1. Nguồn bắt traffic

Hiện tại: **chỉ** dựa vào Burp proxy (browser → proxy Burp → site; extension đọc
`api.siteMap()`). Điểm yếu:
- Phụ thuộc cấu hình proxy đúng; nếu sai listener → không có gì.
- Site map của Burp lưu request + response **nhưng** extension hiện chỉ dùng
  method/url/status/content-type + body request. Chưa dùng **response body** để suy schema.
- Agent **không thấy** mình đã bắt được endpoint nào → không điều hướng theo coverage.

### Bổ sung: Chrome DevTools Protocol (CDP) qua Selenium
- Selenium Java có `HasDevTools` → bật domain `Network`, nghe
  `requestWillBeSent` / `responseReceived` (+ `Network.getResponseBody`).
- Cho phép **bắt trực tiếp request/response body** (kể cả JSON API) ngay trong extension,
  **độc lập** với Burp — dùng để:
  1. Làm giàu dữ liệu suy luận OpenAPI (response schema thật).
  2. Cho agent biết endpoint nào đã chạm (coverage feedback).
- Lưu ý: lớp CDP **gắn version theo Chrome** (`v131.network.Network`…), cần match;
  Selenium coi CDP là *tạm thời* tới khi WebDriver BiDi hoàn chỉnh → thiết kế qua
  interface để sau này thay BiDi dễ. Burp vẫn là "sink" chính; CDP là nguồn làm giàu.

## 2. Sinh OpenAPI từ traffic — tham khảo công cụ có sẵn

| Tool | Ngôn ngữ | Điểm đáng học |
| --- | --- | --- |
| **traffic2openapi** | Go | Pipeline rõ: *Endpoint Clustering → Schema Inference → OpenAPI Generator*; hỗ trợ 3.0/3.1/3.2 |
| **mitmproxy2swagger** | Python | 2 pass: sinh skeleton + path template → người sửa → fill mô tả |
| **har-to-openapi** | TS | Path normalization rules; gộp nhiều HAR |
| **har2openapi** | Node | **Merge step**: hand-edit sống sót qua lần regenerate |
| **harspec** | Python | **Redact secret** trong output |

Bài học áp dụng:
- Tách rõ 3 bước **cluster endpoint → suy schema → sinh spec** (hiện gộp trong 1 file).
- **Path templating** đã có (`PathTemplater`) — tốt; cần đồng bộ giữa crawl & export.
- **Merge/giữ hand-edit** khi xuất lại.
- **Redact secret** (token/cookie/password) khi xuất.

## 3. Suy luận schema — nâng từ "nông" lên "đủ dùng"

Hiện `OpenApiGenerator`:
- JSON body chỉ lưu **example string** (`type: object` + example thô), không parse cây.
- Query/path param: suy kiểu đơn (string/integer/number/boolean).
- Response: chỉ ghi status + content-type, **không có schema**.

Nâng cấp đề xuất:
- **Parse JSON body đệ quy** → suy `object/array/properties`, gộp nhiều example
  (union field, đánh dấu `required` nếu xuất hiện ở mọi mẫu).
- **Response schema** từ response body (có khi bật CDP capture).
- **Gộp query param** cùng endpoint qua nhiều request (nhiều lần gọi khác param).
- **Security scheme**: nhận diện cookie session (`connect.sid`) / `Authorization: Bearer`.
- **Tags** theo segment path đầu (`/admin/*`, `/api/*`) cho spec gọn.
- Phân biệt method khác nhau trên cùng shape (đã làm ở `endpoints.json`).

## 4. Chống rò rỉ dữ liệu nhạy cảm
- Chrome HAR "sanitized" bỏ `Cookie/Set-Cookie/Authorization` nhưng **body & token trên URL
  vẫn còn** → khi xuất phải tự redact.
- Mật khẩu hiện nằm trong Task prompt và **được gửi tới LLM**. Cân nhắc tách trường
  credential riêng (không forward vào prompt, agent điền trực tiếp) hoặc redact trong log/spec.

---
### Nguồn
- [traffic2openapi (Go)](https://pkg.go.dev/github.com/grokify/traffic2openapi@v0.2.0)
- [mitmproxy2swagger](https://docsearch.algolia.com/mcp/docs/repo/alufers/mitmproxy2swagger)
- [har-to-openapi (jonluca)](https://github.com/jonluca/har-to-openapi)
- [har2openapi (dcarr178)](https://github.com/dcarr178/har2openapi)
- [harspec (PyPI)](https://pypi.org/project/harspec/)
- [Selenium CDP network interception guide](https://qaskills.sh/blog/selenium-cdp-chrome-devtools-protocol-guide)
- [Generate OpenAPI from web app traffic](https://dev.to/branch42-labs/what-is-an-openapi-spec-and-how-to-generate-one-from-your-web-apps-traffic-24oh)
