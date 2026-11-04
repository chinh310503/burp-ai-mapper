package com.msn.aimapper.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.msn.aimapper.export.PathTemplater;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Drives a real Chrome browser (via Selenium) through an LLM-decided action loop
 * so the AI logs in and explores the target. All browser traffic is routed
 * through Burp's proxy, so Burp captures the sitemap and API calls.
 *
 * <p>Everything runs inside the JVM; the only runtime requirement is a Chrome /
 * Chromium / Edge binary on the machine. Selenium Manager fetches the matching
 * driver automatically on first use.</p>
 */
public class BrowserAgent {

    private static final String SYSTEM_PROMPT = """
            You are an autonomous web-exploration agent driving a REAL browser for \
            authorized security mapping. Your GOAL: using the TASK below (which may \
            include one or more accounts and passwords), log in and then explore the \
            web application as BROADLY as possible - visit every distinct page, open \
            menus/dropdowns/modals, expand sections, page through lists, open item \
            detail pages, and submit forms with harmless test data - so the browser \
            triggers as many DISTINCT backend/API requests as possible. The traffic is \
            being captured by a proxy.
            RULES: never log out; never delete data; never change account or security \
            settings; never make a payment. These are also ENFORCED by the tool: any \
            delete/logout/reset/pay action you pick will be blocked and skipped, so do \
            not waste steps on them - map those endpoints by reading the page, not by \
            triggering them.
            COVERAGE: the state shows UNVISITED LINKS REMAINING and a sample of them. \
            Your job is to drive that number to 0. Prefer clicking links / using "goto" \
            to reach unvisited URLs, and open menus/forms that may reveal more links. Do \
            NOT repeat actions listed in ALREADY DONE. Do NOT manually enumerate \
            sequential ids (e.g. /item/1, /item/2, /item/3 ...): one or two examples of \
            each URL shape already map the endpoint - the proxy records the pattern. \
            Spend steps on NEW shapes/features instead.
            STOP ONLY WHEN TRULY DONE: reply {"action":"done"} ONLY when UNVISITED LINKS \
            REMAINING is 0 AND there is nothing new left to open. If links remain, keep \
            going instead of saying done.
            You must reply with EXACTLY ONE JSON object and nothing else. Allowed forms:
            {"action":"click","index":N,"reason":"..."}
            {"action":"type","index":N,"text":"...","enter":true,"reason":"..."}
            {"action":"goto","url":"https://...","reason":"..."}
            {"action":"back","reason":"..."}
            {"action":"wait","reason":"..."}
            {"action":"done","reason":"..."}
            Only use an index that appears in the ELEMENTS list.""";

    private static final String EXTRACT_JS = """
            const sel = 'a,button,input,select,textarea,[role=button],[onclick]';
            const els = Array.from(document.querySelectorAll(sel));
            const out = []; let i = 0;
            for (const el of els) {
              const r = el.getBoundingClientRect();
              const visible = r.width > 0 && r.height > 0 && el.offsetParent !== null;
              if (!visible) continue;
              el.setAttribute('data-aimapper-idx', i);
              const tag = el.tagName.toLowerCase();
              const type = el.getAttribute('type') || '';
              let text = (el.innerText || el.value || el.getAttribute('aria-label')
                || el.getAttribute('placeholder') || el.getAttribute('name') || '').trim();
              if (text.length > 80) text = text.slice(0, 80);
              out.push({i: i, tag: tag, type: type, text: text,
                        name: el.getAttribute('name') || '', href: el.getAttribute('href') || ''});
              i++;
              if (i > 120) break;
            }
            return JSON.stringify(out);""";

    /** When stuck this many steps AND links still remain, jump to an unvisited URL. */
    private static final int STUCK_LIMIT = 4;
    /** Stop only when the frontier is empty AND no progress for this many steps. */
    private static final int DONE_STABLE = 5;
    /** Safety cap on how many URLs we keep queued. */
    private static final int MAX_FRONTIER = 2000;
    /**
     * How many URLs of the SAME shape (e.g. /products/{id}) to queue. Keeping a
     * couple of representatives per shape is enough to exercise every distinct
     * endpoint, while skipping the 3rd..Nth near-identical URL so the step budget
     * reaches genuinely new features instead of re-walking /products/101..125.
     */
    private static final int SAMPLES_PER_TEMPLATE = 2;

    private static final String LINKS_JS =
            "return JSON.stringify(Array.from(document.querySelectorAll('a[href]'))"
            + ".map(a => a.href));";

    /**
     * Destructive tokens in a URL path/query. Matched between word separators so
     * {@code /products/1/delete}, {@code ?action=delete}, {@code /logout} all hit,
     * while a benign {@code /deletion-policy} page does not (token must stand alone
     * between separators). This is a SAFETY guard, so it errs toward blocking.
     */
    private static final Pattern DESTRUCTIVE_URL = Pattern.compile(
            "(?i)(^|[/?&=._-])(delete|destroy|remove|drop|truncate|wipe|purge|erase|"
            + "deactivate|revoke|logout|log-?out|sign-?out|signout|reset|"
            + "pay|payment|checkout|charge|refund|withdraw|transfer)([/?&=._-]|$)");

    /** Destructive wording on a button/link/control. */
    private static final Pattern DESTRUCTIVE_TEXT = Pattern.compile(
            "(?i)\\b(delete|destroy|remove|discard|drop|wipe|purge|erase|deactivate|"
            + "revoke|log\\s?out|sign\\s?out|reset|pay(\\s?now)?|check\\s?out|"
            + "place\\s?order|confirm\\s?order|buy\\s?now|withdraw|transfer|"
            + "unsubscribe|cancel\\s?(account|subscription|order))\\b");

    private final Consumer<String> log;
    private volatile boolean stop;
    private volatile ChromeDriver driver;

    public BrowserAgent(Consumer<String> log) {
        this.log = log;
    }

    public void stop() {
        stop = true;
        ChromeDriver d = driver;
        if (d != null) {
            try {
                d.quit();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Runs the full exploration loop. Blocking: call from a background thread. */
    public void run(AgentConfig cfg, LlmClient llm) {
        stop = false;
        try {
            driver = newDriver(cfg);
            log.accept("[agent] Chrome started; proxy -> " + cfg.proxyEndpoint()
                    + " (insecure certs accepted so Burp's CA works).");
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(2));

            log.accept("[agent] Opening " + cfg.targetUrl);
            driver.get(cfg.targetUrl);

            String targetHost = hostOf(cfg.targetUrl);
            Pattern extraBlock = compileExtra(cfg.extraBlockKeywords);
            if (cfg.blockDestructive) {
                log.accept("[agent] SAFETY: destructive actions (delete/logout/reset/"
                        + "pay/...) are BLOCKED in code - the agent cannot delete data, "
                        + "log out, or pay even if the model tries.");
            } else {
                log.accept("[agent] WARNING: destructive-action blocking is OFF - "
                        + "the agent may delete data, log out or pay. Use only on a "
                        + "throwaway test environment.");
            }
            Set<String> visited = new LinkedHashSet<>();            // URLs navigated to
            Set<String> done = new LinkedHashSet<>();               // action signatures
            LinkedHashSet<String> frontier = new LinkedHashSet<>(); // in-scope URLs discovered, unvisited
            Map<String, Integer> shapeSeen = new HashMap<>();       // URL shape -> # already queued
            Deque<String> recent = new ArrayDeque<>();
            int noProgress = 0;                                     // steps without anything new
            int blocked = 0;                                        // destructive actions blocked

            for (int step = 1; step <= cfg.maxSteps && !stop; step++) {
                String url = normalizeUrl(safe(() -> driver.getCurrentUrl(), cfg.targetUrl));
                String title = safe(() -> driver.getTitle(), "");
                boolean newUrl = visited.add(url);
                frontier.remove(url);

                // Discover in-scope links on this page; enqueue unvisited ones, but
                // only a few per URL shape so near-identical URLs (/user/1, /user/2, ...)
                // don't flood the queue. Mark-on-enqueue keeps the frontier a true
                // "remaining" set.
                for (String href : extractLinks()) {
                    if (frontier.size() >= MAX_FRONTIER) {
                        break;
                    }
                    String n = normalizeUrl(href);
                    if (!inScope(n, targetHost) || visited.contains(n) || frontier.contains(n)) {
                        continue;
                    }
                    // Never queue a destructive URL: this is what keeps the
                    // deterministic frontier jumps (below) from navigating into a
                    // /delete or /logout link without the LLM ever being asked.
                    if (cfg.blockDestructive && isDestructiveUrl(n, extraBlock)) {
                        continue;
                    }
                    String shape = PathTemplater.signature(n);
                    int seen = shapeSeen.getOrDefault(shape, 0);
                    if (seen >= SAMPLES_PER_TEMPLATE) {
                        continue; // already have enough examples of this endpoint shape
                    }
                    shapeSeen.put(shape, seen + 1);
                    frontier.add(n);
                }

                String elementsJson = extractElements();
                java.util.List<JsonObject> elems = parseElems(elementsJson);
                String state = buildStatePrompt(cfg, url, title, elementsJson, visited, done,
                        frontier, recent, step);

                String reply;
                try {
                    reply = llm.chat(SYSTEM_PROMPT, state);
                } catch (Exception e) {
                    log.accept("[agent] LLM error: " + e.getMessage() + " -- stopping.");
                    break;
                }

                JsonObject action = parseAction(reply);
                String type = action == null ? "wait" : getStr(action, "action", "wait");
                String reason = action == null ? "" : getStr(action, "reason", "");

                // --- Completion check (guard against premature 'done') ---
                if (type.equals("done")) {
                    if (frontier.isEmpty()) {
                        log.accept("[agent] Done confirmed at step " + step
                                + " (no unvisited links remain).");
                        break;
                    }
                    String next = pollFirst(frontier);
                    log.accept("[agent] Model said done but ~" + (frontier.size() + 1)
                            + " links remain -> continuing to " + trunc(next, 80));
                    gotoUrl(next);
                    noProgress = 0;
                    sleep(700);
                    continue;
                }

                if (action == null) {
                    log.accept("[agent] Unusable reply: " + trunc(reply, 120));
                }

                // Progress = a newly reached URL, or a not-yet-performed meaningful action.
                JsonObject act = action == null ? new JsonObject() : action;
                String sig = signature(type, act, url, elems);
                boolean newAction = done.add(sig);
                boolean meaningful = type.equals("click") || type.equals("type") || type.equals("goto");
                boolean progress = newUrl || (meaningful && newAction);
                noProgress = progress ? 0 : noProgress + 1;

                log.accept("[agent] step " + step + "/" + cfg.maxSteps + ": " + type
                        + (newUrl || newAction ? "" : " (repeat)")
                        + "  [queue=" + frontier.size() + "]"
                        + (reason.isBlank() ? "" : " - " + reason));

                // --- Termination: frontier drained AND no new activity for a while ---
                if (frontier.isEmpty() && noProgress >= DONE_STABLE) {
                    log.accept("[agent] Frontier empty and no new activity for " + DONE_STABLE
                            + " steps -> crawl complete.");
                    break;
                }

                // --- Loop breaker: stuck but links remain -> deterministically jump ---
                if (noProgress >= STUCK_LIMIT && !frontier.isEmpty()) {
                    String next = pollFirst(frontier);
                    log.accept("[agent] Stuck " + noProgress + " steps -> jumping to unvisited: "
                            + trunc(next, 80));
                    gotoUrl(next);
                    noProgress = 0;
                    sleep(700);
                    continue;
                }

                if (action != null) {
                    // Final hard gate: block destructive actions regardless of what
                    // the model decided. The signature is already in `done`, so it
                    // is listed under ALREADY DONE and won't be re-suggested as new.
                    String blockReason = cfg.blockDestructive
                            ? destructiveReason(act, type, elems, extraBlock) : null;
                    if (blockReason != null) {
                        blocked++;
                        log.accept("[agent] BLOCKED destructive " + type + " (" + blockReason
                                + ") - skipped, not executed.");
                    } else {
                        recent.add(type + describe(action));
                        while (recent.size() > 8) {
                            recent.removeFirst();
                        }
                        execute(action, type);
                    }
                }
                sleep(900);
            }
            log.accept("[agent] Finished. Visited " + visited.size() + " URLs across "
                    + shapeSeen.size() + " distinct URL shapes, " + frontier.size()
                    + " still queued, " + done.size() + " distinct actions, "
                    + blocked + " destructive actions blocked. "
                    + "Switch to Burp and click 'Export sitemap' / 'Export OpenAPI'.");
        } catch (WebDriverException e) {
            log.accept("[agent] Browser error: " + e.getMessage()
                    + "  (is Chrome/Chromium/Edge installed? set a binary path if needed)");
        } catch (RuntimeException e) {
            log.accept("[agent] ERROR: " + e.getMessage());
        } finally {
            ChromeDriver d = driver;
            if (d != null) {
                try {
                    d.quit();
                } catch (RuntimeException ignored) {
                }
            }
            driver = null;
        }
    }

    private ChromeDriver newDriver(AgentConfig cfg) {
        ChromeOptions options = new ChromeOptions();
        if (cfg.chromeBinary != null && !cfg.chromeBinary.isBlank()) {
            options.setBinary(cfg.chromeBinary);
        }
        String endpoint = cfg.proxyEndpoint();
        // Route everything through Burp via a Chrome flag (more reliable than the
        // Selenium Proxy capability, which newer Chrome often ignores).
        options.addArguments("--proxy-server=http://" + endpoint);
        // Chrome bypasses the proxy for localhost/loopback by default; override that
        // so requests to a local target still reach Burp.
        options.addArguments("--proxy-bypass-list=<-loopback>");
        // Accept Burp's MITM certificate.
        options.setAcceptInsecureCerts(true);
        options.addArguments("--ignore-certificate-errors");
        if (cfg.headless) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--window-size=1400,900");
        return new ChromeDriver(options);
    }

    private String extractElements() {
        try {
            Object r = ((JavascriptExecutor) driver).executeScript(EXTRACT_JS);
            return r == null ? "[]" : r.toString();
        } catch (RuntimeException e) {
            return "[]";
        }
    }

    private String buildStatePrompt(AgentConfig cfg, String url, String title,
                                    String elementsJson, Set<String> visited,
                                    Set<String> done, Set<String> frontier,
                                    Deque<String> recent, int step) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK:\n").append(cfg.prompt).append("\n\n");
        sb.append("STEP ").append(step).append(" / ").append(cfg.maxSteps).append('\n');
        sb.append("CURRENT URL: ").append(url).append('\n');
        sb.append("TITLE: ").append(title).append('\n');
        sb.append("UNVISITED LINKS REMAINING: ").append(frontier.size()).append('\n');
        sb.append("NEXT UNVISITED (sample): ").append(firstN(frontier, 10)).append('\n');
        sb.append("VISITED URLS (").append(visited.size()).append("): ")
                .append(lastN(visited, 10)).append('\n');
        sb.append("ALREADY DONE ACTIONS (").append(done.size()).append(") - do NOT repeat: ")
                .append(lastN(done, 20)).append('\n');
        sb.append("RECENT ACTIONS: ").append(String.join(" | ", recent)).append("\n\n");
        sb.append("ELEMENTS (index: tag/type - text [name] href):\n");
        sb.append(formatElements(elementsJson)).append('\n');
        sb.append("\nReturn ONE JSON action.");
        return sb.toString();
    }

    private String formatElements(String elementsJson) {
        try {
            JsonArray arr = JsonParser.parseString(elementsJson).getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                sb.append(getStr(o, "i", "?")).append(": ")
                        .append(getStr(o, "tag", "")).append('/').append(getStr(o, "type", ""))
                        .append(" - ").append(trunc(getStr(o, "text", ""), 80));
                String name = getStr(o, "name", "");
                if (!name.isBlank()) {
                    sb.append(" [").append(name).append(']');
                }
                String href = getStr(o, "href", "");
                if (!href.isBlank()) {
                    sb.append(' ').append(trunc(href, 60));
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "(no elements)";
        }
    }

    private void execute(JsonObject action, String type) {
        try {
            switch (type) {
                case "click" -> {
                    WebElement e = byIndex(action);
                    if (e != null) {
                        scrollTo(e);
                        e.click();
                    }
                }
                case "type" -> {
                    WebElement e = byIndex(action);
                    if (e != null) {
                        scrollTo(e);
                        try {
                            e.clear();
                        } catch (RuntimeException ignored) {
                        }
                        e.sendKeys(getStr(action, "text", ""));
                        if (action.has("enter") && action.get("enter").getAsBoolean()) {
                            e.sendKeys(Keys.RETURN);
                        }
                    }
                }
                case "goto" -> {
                    String u = getStr(action, "url", "");
                    if (!u.isBlank()) {
                        driver.get(u);
                    }
                }
                case "back" -> driver.navigate().back();
                case "wait" -> sleep(1200);
                default -> log.accept("[agent] Unknown action: " + type);
            }
        } catch (RuntimeException e) {
            log.accept("[agent] action '" + type + "' failed: " + shortMsg(e));
        }
    }

    private WebElement byIndex(JsonObject action) {
        if (!action.has("index")) {
            log.accept("[agent] action missing 'index'");
            return null;
        }
        int idx = action.get("index").getAsInt();
        try {
            return driver.findElement(By.cssSelector("[data-aimapper-idx='" + idx + "']"));
        } catch (RuntimeException e) {
            log.accept("[agent] element index " + idx + " not found (page may have changed).");
            return null;
        }
    }

    private void scrollTo(WebElement e) {
        try {
            ((JavascriptExecutor) driver)
                    .executeScript("arguments[0].scrollIntoView({block:'center'});", e);
        } catch (RuntimeException ignored) {
        }
    }

    // --- helpers ---

    /** All anchor hrefs on the current page, resolved to absolute URLs. */
    private java.util.List<String> extractLinks() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Object r = ((JavascriptExecutor) driver).executeScript(LINKS_JS);
            if (r != null) {
                JsonArray arr = JsonParser.parseString(r.toString()).getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    out.add(arr.get(i).getAsString());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private void gotoUrl(String u) {
        try {
            driver.get(u);
        } catch (RuntimeException e) {
            log.accept("[agent] goto failed (" + trunc(u, 60) + "): " + shortMsg(e));
        }
    }

    private static String hostOf(String u) {
        try {
            return new URI(u).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** In-scope = http/https link on the same host as the target. */
    private static boolean inScope(String u, String targetHost) {
        if (u == null || u.isBlank() || targetHost == null) {
            return false;
        }
        String low = u.toLowerCase();
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            return false; // skips javascript:, mailto:, tel:, data:, blob:, ...
        }
        String h = hostOf(u);
        return h != null && h.equalsIgnoreCase(targetHost);
    }

    // --- destructive-action guard ---

    /** Compile the user's extra keywords into a word-boundary pattern (or null). */
    private static Pattern compileExtra(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return null;
        }
        StringBuilder alt = new StringBuilder();
        for (String k : keywords.split("[,\\s]+")) {
            if (k.isBlank()) {
                continue;
            }
            if (alt.length() > 0) {
                alt.append('|');
            }
            alt.append(Pattern.quote(k.trim()));
        }
        if (alt.length() == 0) {
            return null;
        }
        return Pattern.compile("(?i)(" + alt + ")");
    }

    /** True if a URL contains a destructive token (or a user extra keyword). */
    private static boolean isDestructiveUrl(String url, Pattern extra) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (DESTRUCTIVE_URL.matcher(url).find()) {
            return true;
        }
        return extra != null && extra.matcher(url).find();
    }

    /**
     * Why an action is destructive, or null if it's safe. Checks the target URL
     * (goto) or the element's href / visible text / name (click, type).
     */
    private static String destructiveReason(JsonObject a, String type,
                                            java.util.List<JsonObject> elems, Pattern extra) {
        if ("goto".equals(type)) {
            String u = getStr(a, "url", "");
            return isDestructiveUrl(u, extra) ? "url=" + trunc(u, 80) : null;
        }
        if ("click".equals(type) || "type".equals(type)) {
            JsonObject el = elementOf(a, elems);
            if (el != null) {
                String href = getStr(el, "href", "");
                if (!href.isBlank() && isDestructiveUrl(href, extra)) {
                    return "href=" + trunc(href, 80);
                }
                String label = (getStr(el, "text", "") + " " + getStr(el, "name", "")).trim();
                if (!label.isBlank()) {
                    if (DESTRUCTIVE_TEXT.matcher(label).find()) {
                        return "label='" + trunc(label, 60) + "'";
                    }
                    if (extra != null && extra.matcher(label).find()) {
                        return "label='" + trunc(label, 60) + "'";
                    }
                }
            }
        }
        return null;
    }

    /** Resolve the element an action's index points at, or null. */
    private static JsonObject elementOf(JsonObject a, java.util.List<JsonObject> elems) {
        if (a == null || !a.has("index") || elems == null) {
            return null;
        }
        try {
            int idx = a.get("index").getAsInt();
            if (idx >= 0 && idx < elems.size()) {
                return elems.get(idx);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String pollFirst(LinkedHashSet<String> set) {
        java.util.Iterator<String> it = set.iterator();
        if (!it.hasNext()) {
            return null;
        }
        String v = it.next();
        it.remove();
        return v;
    }

    private static String firstN(Set<String> set, int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : set) {
            if (i++ >= n) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(" , ");
            }
            sb.append(trunc(s, 70));
        }
        return sb.toString();
    }

    private static java.util.List<JsonObject> parseElems(String json) {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                out.add(arr.get(i).getAsJsonObject());
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static String normalizeUrl(String u) {
        if (u == null) {
            return "";
        }
        int h = u.indexOf('#');
        return h >= 0 ? u.substring(0, h) : u;
    }

    /** Stable-ish fingerprint of an action, used to detect repeats / stagnation. */
    private static String signature(String type, JsonObject a, String url,
                                    java.util.List<JsonObject> elems) {
        switch (type) {
            case "goto":
                return "goto|" + getStr(a, "url", "");
            case "click":
            case "type": {
                JsonObject el = null;
                if (a.has("index")) {
                    int idx = -1;
                    try {
                        idx = a.get("index").getAsInt();
                    } catch (RuntimeException ignored) {
                    }
                    if (idx >= 0 && idx < elems.size()) {
                        el = elems.get(idx);
                    }
                }
                String key;
                if (el != null) {
                    String href = getStr(el, "href", "");
                    key = !href.isBlank() ? href
                            : getStr(el, "tag", "") + ":" + getStr(el, "text", "")
                              + ":" + getStr(el, "name", "");
                } else {
                    key = "idx" + getStr(a, "index", "?");
                }
                return type.equals("type")
                        ? "type|" + url + "|" + key + "|" + getStr(a, "text", "")
                        : "click|" + url + "|" + key;
            }
            default:
                return type + "|" + url;
        }
    }

    private static JsonObject parseAction(String reply) {
        if (reply == null) {
            return null;
        }
        String s = reply.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        try {
            return JsonParser.parseString(s.substring(a, b + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String describe(JsonObject a) {
        if (a.has("index")) {
            return "#" + a.get("index").getAsString();
        }
        if (a.has("url")) {
            return " " + trunc(a.get("url").getAsString(), 40);
        }
        return "";
    }

    private static String getStr(JsonObject o, String key, String def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String lastN(Set<String> set, int n) {
        String[] a = set.toArray(new String[0]);
        int from = Math.max(0, a.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < a.length; i++) {
            if (sb.length() > 0) {
                sb.append(" , ");
            }
            sb.append(trunc(a[i], 60));
        }
        return sb.toString();
    }

    private interface Sup<T> {
        T get();
    }

    private static <T> T safe(Sup<T> s, T def) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String shortMsg(RuntimeException e) {
        String m = e.getMessage();
        if (m == null) {
            return e.getClass().getSimpleName();
        }
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : trunc(m, 160);
    }

    private static String trunc(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
