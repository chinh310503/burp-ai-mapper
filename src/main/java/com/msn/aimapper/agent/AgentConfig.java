package com.msn.aimapper.agent;

/** All settings for one AI browsing run. */
public class AgentConfig {

    // What to do. Free-text: may contain one or more accounts + passwords and
    // any specific instructions ("test as admin then as normal user", etc.).
    public String prompt = "";

    // Where to start.
    public String targetUrl = "";

    // Browsing behaviour. The crawl ALWAYS runs until complete (no step cap) - it
    // stops only when the frontier is drained or the user clicks Stop, with a high
    // absolute safety ceiling in BrowserAgent as the only infinite-loop guard.
    public boolean headless = false;
    public String chromeBinary = "";   // optional explicit path to chrome/chromium/edge
    // How many URLs of the same shape (/products/{id}) to queue. Higher = more
    // thorough on id-heavy sites, lower = faster / broader. Default 2.
    public int samplesPerTemplate = 2;
    // Vision fallback: when a page exposes NO interactive elements, attach a
    // screenshot to the LLM call (needs a vision-capable model). Off by default.
    public boolean vision = false;

    // Safety is NOT configurable - these are always-on defaults enforced in code:
    //  - destructive actions (delete / logout / reset / pay / ...) are ALWAYS blocked
    //    in the agent loop, regardless of what the LLM decides;
    //  - when the crawl finishes, every record the agent EDITED is ALWAYS rolled back
    //    to its original values (black-box compensation; created records are left
    //    as-is since a black-box crawler can't safely delete them).

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
