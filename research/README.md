# Research — AI Sitemap & API Mapper cải thiện

Thư mục này chứa toàn bộ nghiên cứu nền + phân tích hiện trạng để làm cơ sở cho
kế hoạch cải thiện extension `burp-ai-mapper`.

Mục tiêu dự án: **dùng AI tự động điều khiển trình duyệt, dùng *hết* chức năng của
website để trích xuất sitemap + danh sách API, nhưng KHÔNG phá hoại dữ liệu
(không xóa / logout nhầm / thanh toán).**

## Thứ tự đọc

| File | Nội dung |
| --- | --- |
| [01-current-state-and-benchmark.md](01-current-state-and-benchmark.md) | Hiện trạng extension + phân tích target benchmark `web-benmark` (Nexora) và ground-truth |
| [02-ai-web-agent-techniques.md](02-ai-web-agent-techniques.md) | State-of-the-art agent duyệt web: DOM vs a11y tree vs vision, action loop, structured output |
| [03-api-discovery-and-openapi.md](03-api-discovery-and-openapi.md) | Bắt traffic (CDP), suy luận schema, sinh OpenAPI từ HAR/traffic |
| [04-gap-analysis.md](04-gap-analysis.md) | Khoảng cách giữa hiện trạng và mục tiêu, map từng gap với test-case benchmark |
| [05-data-restoration.md](05-data-restoration.md) | Khôi phục dữ liệu sau crawl (Strix + reset-endpoint/snapshot/compensation) |
| [IMPROVEMENT-PLAN.md](IMPROVEMENT-PLAN.md) | **Kế hoạch cải thiện theo giai đoạn (P0→P4)** + tiêu chí nghiệm thu |

## Nguồn tham khảo chính

- browser-use / Stagehand / Skyvern — 3 kiến trúc agent (DOM-first / hybrid / vision-first)
- Playwright/Selenium accessibility-tree & CDP network interception
- traffic2openapi, mitmproxy2swagger, har-to-openapi — sinh OpenAPI từ traffic
- OpenAI Structured Outputs (strict JSON schema) cho vòng lặp action tin cậy

(Chi tiết link nằm ở cuối mỗi file.)
