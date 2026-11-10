package com.msn.aimapper.agent;

/** All settings for one AI browsing run. */
public class AgentConfig {

    // What to do. Free-text: may contain one or more accounts + passwords and
    // any specific instructions ("test as admin then as normal user", etc.).
    public String prompt = "";

    // Where to start.
    public String targetUrl = "";

    // Browsing behaviour.
    public int maxSteps = 60;
    public boolean headless = false;
    public String chromeBinary = "";   // optional explicit path to chrome/chromium/edge
    // How many URLs of the same shape (/products/{id}) to queue. Higher = more
    // thorough on id-heavy sites, lower = faster / broader. Default 2.
    public int samplesPerTemplate = 2;
    // Vision fallback: when a page exposes NO interactive elements, attach a
    // screenshot to the LLM call (needs a vision-capable model). Off by default.
    public boolean vision = false;

    // Safety: hard, code-level block on destructive actions (delete / logout /
    // reset / pay / change security settings). On by default. This is enforced
    // in the agent loop regardless of what the LLM decides, so it cannot be
    // bypassed by a bad model reply or by the deterministic frontier jumps.
    public boolean blockDestructive = true;
    // Optional extra keywords (comma/space separated) to also treat as destructive.
    public String extraBlockKeywords = "";

    // Data restore: an app reset endpoint to call so data mutated during the crawl is
    // returned to its seed state. Format: "METHOD url" (method optional, default POST),
    // url absolute or relative to the target, e.g. "POST /admin/reset". Empty = off.
    // The restore step logs in as the admin account (deterministically) first, since
    // reset endpoints are usually admin-only.
    public String restoreRequest = "";
    public boolean restoreAfterCrawl = false;  // auto-call it when the crawl finishes

    // Proxy = Burp's proxy listener.
    public String proxyHost = "127.0.0.1";
    public int proxyPort = 8080;

    // LLM (OpenAI-compatible chat/completions).
    public String model = "gpt-4.1-mini";
    public String baseUrl = "https://api.openai.com/v1";
    public String apiKey = "";

    // LLM behaviour.
    public double temperature = 0.2;
    public int maxTokens = 0;          // 0 = don't send max_tokens (use provider default)
    // Ask for response_format={"type":"json_object"} so the one-action reply is always
    // valid JSON. Auto-disables for the session if a provider rejects it (HTTP 400).
    public boolean jsonMode = true;
    public int llmRetries = 3;         // total attempts per call on timeout / 429 / 5xx

    public String proxyEndpoint() {
        return proxyHost + ":" + proxyPort;
    }
}
