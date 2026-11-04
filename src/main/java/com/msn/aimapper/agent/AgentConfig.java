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

    // Safety: hard, code-level block on destructive actions (delete / logout /
    // reset / pay / change security settings). On by default. This is enforced
    // in the agent loop regardless of what the LLM decides, so it cannot be
    // bypassed by a bad model reply or by the deterministic frontier jumps.
    public boolean blockDestructive = true;
    // Optional extra keywords (comma/space separated) to also treat as destructive.
    public String extraBlockKeywords = "";

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
