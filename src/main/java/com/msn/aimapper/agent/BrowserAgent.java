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
            LOGIN: to sign in you MUST fill EVERY field of the login form before \
            submitting. Do it in this order, one action per step: first "type" the \
            username/email into its field, then "type" the password into the password \
            field (type=password), and ONLY THEN "click" the submit button. NEVER click \
            submit while the password field is still empty. After a failed attempt the \
            form reloads EMPTY, so re-type BOTH the username and the password again \
            before submitting. Use the exact credentials from the ACTIVE ACCOUNT / TASK. \
            The ELEMENTS list marks each field "(empty)" or "(filled)" and the state \
            shows a LOGIN FORM hint with the exact field indices - follow it. \
            For a one-shot submit you may use "enter":true on the password "type" action. \
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
              // Whether a form control currently holds a value (so the model can tell a
              // filled field from an empty one - placeholder text alone is NOT "filled").
              const isField = (tag === 'input' || tag === 'textarea' || tag === 'select');
              const filled = isField && el.value != null && String(el.value).length > 0;
              out.push({i: i, tag: tag, type: type, text: text, field: isField, filled: filled,
                        name: el.getAttribute('name') || '', href: el.getAttribute('href') || ''});
              i++;
              if (i > 120) break;
            }
            return JSON.stringify(out);""";

    /** Absolute ceiling when Max steps = 0 (crawl-until-complete), to avoid a true
     *  infinite loop on a pathological site. High enough to mean "map everything". */
    private static final int ABSOLUTE_MAX_STEPS = 100_000;
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

    /**
     * Harvest EVERY potential navigation URL on the page, not just {@code <a href>}:
     * anchors, image-map areas, form actions, {@code data-href/url/go} attributes,
     * inline {@code onclick/onchange} location changes, and {@code <select><option>}
     * values that look like URLs. Also descends into OPEN shadow roots and
     * same-origin iframes, and resolves relative URLs against the right base. The
     * hrefs are collected regardless of visibility, so links hidden inside closed
     * dropdowns / tabs / modals / {@code <details>} are captured without clicking.
     */
    private static final String HARVEST_JS = """
            const out = new Set();
            function add(u, base) {
              if (!u) return;
              u = ('' + u).trim();
              if (!u) return;
              if (/^(javascript:|mailto:|tel:|data:|blob:|sms:)/i.test(u)) return;
              try { out.add(new URL(u, base).href); } catch (e) {}
            }
            function harvest(root, base) {
              root.querySelectorAll('a[href],area[href]').forEach(e => add(e.getAttribute('href'), base));
              root.querySelectorAll('form[action]').forEach(e => add(e.getAttribute('action'), base));
              root.querySelectorAll('[data-href],[data-url],[data-go]').forEach(e => {
                add(e.getAttribute('data-href'), base);
                add(e.getAttribute('data-url'), base);
                add(e.getAttribute('data-go'), base);
              });
              root.querySelectorAll('option[value]').forEach(e => {
                const v = e.getAttribute('value');
                if (v && (v.charAt(0) === '/' || /^https?:/i.test(v))) add(v, base);
              });
              root.querySelectorAll('[onclick],[onchange]').forEach(e => {
                const s = (e.getAttribute('onclick') || '') + ';' + (e.getAttribute('onchange') || '');
                const re = /(?:location\\.(?:href|assign|replace)\\s*\\(?\\s*|(?:window\\.)?location\\s*=\\s*)['"]([^'"]+)['"]/g;
                let m; while ((m = re.exec(s))) add(m[1], base);
              });
              root.querySelectorAll('*').forEach(e => { if (e.shadowRoot) harvest(e.shadowRoot, base); });
            }
            harvest(document, document.baseURI);
            document.querySelectorAll('iframe,frame').forEach(f => {
              try { const d = f.contentDocument; if (d) harvest(d, d.baseURI); } catch (e) {}
            });
            return JSON.stringify(Array.from(out));""";

    /**
     * Reveal hidden content so any lazily-shown links enter the DOM before we
     * harvest: open every {@code <details>}, mark dropdowns/menus open, un-hide
     * {@code [hidden]} panels/modals. Pure DOM mutation - it performs NO navigation
     * and fires no handlers, so it can never trigger a destructive action.
     */
    private static final String REVEAL_JS = """
            try {
              document.querySelectorAll('details').forEach(d => { d.open = true; });
              document.querySelectorAll('.dropdown,.menu,.has-dropdown,.mega').forEach(d => d.classList.add('open'));
              // Un-hide hidden content so lazily-shown links enter the DOM, BUT skip
              // modal/overlay/backdrop/dialog elements: forcing those visible drops a
              // full-page overlay that then intercepts real clicks. Links inside a
              // hidden modal are still harvested (querySelectorAll finds them anyway).
              document.querySelectorAll('[hidden]').forEach(e => {
                try {
                  var cls = (e.className && e.className.toString) ? e.className.toString() : '';
                  var overlay = /modal|overlay|backdrop|dialog|popup|lightbox|drawer/i.test(cls)
                    || e.getAttribute('role') === 'dialog'
                    || e.getAttribute('aria-modal') === 'true';
                  if (!overlay) e.hidden = false;
                } catch (x) {}
              });
              document.querySelectorAll('[aria-expanded="false"]').forEach(e => e.setAttribute('aria-expanded', 'true'));
            } catch (e) {}
            return true;""";

    /** Async fetch of a URL's text (used to seed from sitemap.xml / robots.txt). */
    private static final String FETCH_TEXT_JS =
            "const cb = arguments[arguments.length - 1];"
            + "fetch(arguments[0], {credentials:'include'}).then(r => r.text())"
            + ".then(t => cb(t)).catch(() => cb(''));";

    /**
     * Visible text on a control that means "load the next batch" (safe to click).
     * Deliberately specific: requires a load/show/view/see + more/older wording (or
     * the Vietnamese equivalents), NOT a bare "more", so a blog "Read more" link that
     * would navigate away is not treated as a load-more button.
     */
    private static final Pattern LOAD_MORE_TEXT = Pattern.compile(
            "(?i)(load|show|view|see|fetch)\\s+(more|older|additional|next)"
            + "|load\\s*more|show\\s*more|t\\u1ea3i th\\u00eam|xem th\\u00eam");

    /**
     * HARD-destructive tokens in a URL path/query: a plain GET to these typically
     * mutates server state, so we never navigate to or queue them. Matched between
     * word separators so {@code /products/1/delete}, {@code ?action=delete},
     * {@code /logout} all hit, while a benign {@code /deletion-policy} page does not.
     *
     * <p>NOTE: financial words ({@code pay/payment/checkout/charge/...}) are
     * deliberately NOT here. {@code /checkout} and {@code /payment} are usually
     * read-only PAGES worth mapping; navigating to them is safe. The actual
     * money-moving action is a button/submit, which is caught by
     * {@link #DESTRUCTIVE_TEXT} at click time instead. This keeps us from losing
     * pages while still never pressing "Pay"/"Place order".</p>
     */
    private static final Pattern DESTRUCTIVE_URL = Pattern.compile(
            "(?i)(^|[/?&=._-])(delete|destroy|remove|drop|truncate|wipe|purge|erase|"
            + "deactivate|revoke|logout|log-?out|sign-?out|signout|reset)([/?&=._-]|$)");

    /**
     * Destructive wording on a button/link/control. This is where financial
     * actions are blocked: a control labelled "Pay"/"Checkout"/"Place order" is
     * never clicked, even though the {@code /checkout} PAGE itself may be visited.
     */
    private static final Pattern DESTRUCTIVE_TEXT = Pattern.compile(
            "(?i)\\b(delete|destroy|remove|discard|drop|wipe|purge|erase|deactivate|"
            + "revoke|log\\s?out|sign\\s?out|reset|pay(\\s?now)?|check\\s?out|"
            + "place\\s?order|confirm\\s?order|buy\\s?now|withdraw|transfer|"
            + "unsubscribe|cancel\\s?(account|subscription|order))\\b");

    private final Consumer<String> log;
    private final CoverageSource coverage;   // live proxy coverage (may be null)
    private volatile boolean stop;
    private volatile ChromeDriver driver;
    private volatile String targetHostField; // host of the current run, for coverage lookup
    private volatile int samplesPerTemplate = SAMPLES_PER_TEMPLATE; // from cfg at run start
    private volatile boolean visionNoted;     // log vision-fallback use only once
    /** The account the current pass must log in as (null = prompt-driven single pass). */
    private volatile Account activeAccount;
    /**
     * Every in-scope URL the agent actually landed on, including client-only routes
     * ({@code #/...} hash routes and SPA pushState paths) that the proxy never sees
     * as distinct requests. The export merges these so such pages aren't lost.
     */
    private final Set<String> discovered =
            java.util.Collections.synchronizedSet(new LinkedHashSet<>());

    /** Snapshot of the in-scope URLs the agent reached (for the export to merge in). */
    public Set<String> discoveredUrls() {
        synchronized (discovered) {
            return new LinkedHashSet<>(discovered);
        }
    }

    /** One parsed login from the TASK prompt. */
    record Account(String username, String password, String label) {
        String display() {
            return (label == null || label.isBlank()) ? username : username + " [" + label + "]";
        }
    }

    /**
     * Strict, full-line pattern for an account line, e.g.
     * {@code - user1 / password1  (role: admin)} or {@code user@demo.test / Passw0rd!}.
     * Must match the WHOLE line so prose like "Do not log out or delete data." never
     * matches. Neither token may contain a slash or whitespace (so URLs don't match).
     */
    private static final Pattern ACCOUNT_LINE = Pattern.compile(
            "(?m)^\\s*[-*]?\\s*([^\\s/():]+)\\s*/\\s*([^\\s/()]+)\\s*(?:\\(([^)]*)\\))?\\s*$");

    /**
     * Pull login accounts out of the free-text TASK prompt. Returns them in the
     * order written (so "admin" listed first is crawled first). Empty if none are
     * recognised, in which case the agent does one prompt-driven pass as before.
     */
    static java.util.List<Account> parseAccounts(String prompt) {
        java.util.List<Account> out = new java.util.ArrayList<>();
        if (prompt == null || prompt.isBlank()) {
            return out;
        }
        java.util.regex.Matcher m = ACCOUNT_LINE.matcher(prompt);
        while (m.find()) {
            String user = m.group(1).trim();
            String pass = m.group(2).trim();
            String rawLabel = m.group(3) == null ? "" : m.group(3).trim();
            if (user.length() < 2 || pass.length() < 2) {
                continue;
            }
            if (user.contains("://") || pass.contains("://")) {
                continue;
            }
            // Normalise "role: admin" -> "admin"; keep anything else as-is.
            String label = rawLabel.replaceFirst("(?i)^role\\s*[:=]\\s*", "").trim();
            out.add(new Account(user, pass, label));
        }
        return out;
    }

    public BrowserAgent(Consumer<String> log) {
        this(log, null);
    }

    public BrowserAgent(Consumer<String> log, CoverageSource coverage) {
        this.log = log;
        this.coverage = coverage;
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
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(15)); // for async fetch seeds

            String targetHost = hostOf(cfg.targetUrl);
            targetHostField = targetHost;
            samplesPerTemplate = cfg.samplesPerTemplate > 0
                    ? cfg.samplesPerTemplate : SAMPLES_PER_TEMPLATE;
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

            // Step budget. Max steps = 0 means "crawl until complete": keep going until
            // the frontier is drained (no unvisited links left) or the user clicks Stop,
            // with only a high absolute ceiling as an infinite-loop guard. A positive
            // value is a hard cap per pass.
            boolean unlimited = cfg.maxSteps <= 0;
            int stepCap = unlimited ? ABSOLUTE_MAX_STEPS : cfg.maxSteps;
            String capDisp = unlimited ? "∞" : Integer.toString(cfg.maxSteps);
            if (unlimited) {
                log.accept("[agent] Max steps = 0 -> crawling UNTIL COMPLETE: stops only when "
                        + "no unvisited links remain (or you click Stop). Safety ceiling "
                        + ABSOLUTE_MAX_STEPS + " steps.");
            } else {
                log.accept("[agent] Step cap " + cfg.maxSteps + " per pass. Set Max steps = 0 "
                        + "to crawl the whole sitemap until no links remain.");
            }

            // Multi-account: if the TASK lists several accounts, crawl each in its
            // OWN session (one privilege tier per pass). Between passes we clear the
            // cookies in CODE and log back in as the next account -- this is a
            // managed re-auth, NOT the kind of "logout" the destructive guard blocks,
            // so exploring both tiers no longer conflicts with the safety guard.
            // All passes flow through the same Burp proxy, so the site map (and thus
            // the export) already merges every tier's traffic automatically.
            java.util.List<Account> passes = parseAccounts(cfg.prompt);
            if (passes.isEmpty()) {
                passes = java.util.Collections.singletonList(null); // single prompt-driven pass
            } else if (passes.size() > 1) {
                log.accept("[agent] " + passes.size() + " accounts detected -> crawling each "
                        + "privilege tier in its own session (up to " + capDisp + " steps each).");
            }

            int totalVisited = 0;
            int totalBlocked = 0;
            for (int pass = 0; pass < passes.size() && !stop; pass++) {
            Account acct = passes.get(pass);
            activeAccount = acct;
            if (pass > 0) {
                log.accept("[agent] Switching session to account '" + acct.display()
                        + "' (clearing cookies then re-authenticating in code - "
                        + "this is a managed re-auth, not a blocked logout).");
                try {
                    driver.manage().deleteAllCookies();
                } catch (RuntimeException ignored) {
                }
            }
            log.accept("[agent] Opening " + cfg.targetUrl
                    + (acct != null ? "  (account: " + acct.display() + ")" : ""));
            gotoUrl(cfg.targetUrl);

            Set<String> visited = new LinkedHashSet<>();            // URLs navigated to
            Set<String> done = new LinkedHashSet<>();               // action signatures
            LinkedHashSet<String> frontier = new LinkedHashSet<>(); // in-scope URLs discovered, unvisited
            Map<String, Integer> shapeSeen = new HashMap<>();       // URL shape -> # already queued
            Deque<String> recent = new ArrayDeque<>();
            int noProgress = 0;                                     // steps without anything new
            int blocked = 0;                                        // destructive actions blocked
            boolean completed = false;                              // true if the pass finished naturally

            seedFromSiteFiles(cfg, targetHost, frontier, shapeSeen, extraBlock);

            for (int step = 1; step <= stepCap && !stop; step++) {
                String url = normalizeUrl(safe(() -> driver.getCurrentUrl(), cfg.targetUrl));
                String title = safe(() -> driver.getTitle(), "");
                boolean newUrl = visited.add(url);
                frontier.remove(url);
                if (newUrl) {
                    discovered.add(url);
                    waitForReady(400);   // let async (AJAX) content load before harvesting
                    exhaustPage();       // scroll + click "load more" to surface appended links
                }

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
                    if (seen >= samplesPerTemplate) {
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
                    if (cfg.vision && elems.isEmpty()) {
                        // Vision fallback: no interactive elements to index -> let a
                        // vision-capable model look at a screenshot instead.
                        if (!visionNoted) {
                            log.accept("[agent] Vision fallback: page has no interactive "
                                    + "elements; attaching a screenshot for the model.");
                            visionNoted = true;
                        }
                        String png = screenshotBase64();
                        java.util.List<LlmClient.Msg> msgs = new java.util.ArrayList<>();
                        msgs.add(LlmClient.Msg.system(SYSTEM_PROMPT));
                        msgs.add(png != null ? LlmClient.Msg.userWithImage(state, png)
                                : LlmClient.Msg.user(state));
                        reply = llm.chat(msgs);
                    } else {
                        reply = llm.chat(SYSTEM_PROMPT, state);
                    }
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
                        completed = true;
                        break;
                    }
                    String next = pollFirst(frontier);
                    log.accept("[agent] Model said done but ~" + (frontier.size() + 1)
                            + " links remain -> continuing to " + trunc(next, 80));
                    if (gotoUrl(next)) {
                        visited.add(normalizeUrl(next)); // requested url (redirects won't re-queue it)
                    }
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

                log.accept("[agent] step " + step + "/" + capDisp + " @ " + trunc(url, 70)
                        + ": " + type + targetDesc(type, act, elems)
                        + (newUrl || newAction ? "" : " (repeat)")
                        + "  [queue=" + frontier.size() + "]"
                        + (reason.isBlank() ? "" : " - " + reason));

                // --- Termination: frontier drained AND no new activity for a while ---
                if (frontier.isEmpty() && noProgress >= DONE_STABLE) {
                    log.accept("[agent] Frontier empty and no new activity for " + DONE_STABLE
                            + " steps -> crawl complete.");
                    completed = true;
                    break;
                }

                // --- Loop breaker: stuck but links remain -> deterministically jump ---
                if (noProgress >= STUCK_LIMIT && !frontier.isEmpty()) {
                    String next = pollFirst(frontier);
                    log.accept("[agent] Stuck " + noProgress + " steps -> jumping to unvisited: "
                            + trunc(next, 80));
                    if (gotoUrl(next)) {
                        visited.add(normalizeUrl(next)); // requested url (redirects won't re-queue it)
                    }
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
                        boolean ok = execute(action, type);
                        // Mark the REQUESTED goto url visited (only if navigation
                        // succeeded) even if it redirects elsewhere, so a redirecting
                        // link (e.g. /login?next=... that bounces to /dashboard once
                        // logged in) isn't retried forever - while a transient failure
                        // leaves it in play for a later retry.
                        if (ok && "goto".equals(type)) {
                            String gu = normalizeUrl(getStr(act, "url", ""));
                            if (!gu.isBlank()) {
                                visited.add(gu);
                                frontier.remove(gu);
                            }
                        }
                    }
                }
                sleep(900);
            }
            if (!completed && !stop) {
                if (unlimited) {
                    log.accept("[agent] Hit the safety ceiling " + ABSOLUTE_MAX_STEPS + " with "
                            + frontier.size() + " link(s) still queued (unusually large site).");
                } else {
                    log.accept("[agent] Reached Max steps " + cfg.maxSteps + " with "
                            + frontier.size() + " link(s) still queued -> raise Max steps, or set "
                            + "it to 0 to crawl until the whole sitemap is covered.");
                }
            }
            log.accept("[agent] Pass " + (pass + 1) + "/" + passes.size()
                    + (acct != null ? " [" + acct.display() + "]" : "")
                    + " done: visited " + visited.size() + " URLs across "
                    + shapeSeen.size() + " shapes, " + frontier.size() + " still queued, "
                    + done.size() + " distinct actions, " + blocked + " destructive blocked.");
            totalVisited += visited.size();
            totalBlocked += blocked;
            } // end per-account pass

            log.accept("[agent] Finished " + passes.size() + " pass(es). Visited "
                    + totalVisited + " URLs total, " + totalBlocked
                    + " destructive actions blocked. "
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

    private String screenshotBase64() {
        return safe(() -> ((org.openqa.selenium.TakesScreenshot) driver)
                .getScreenshotAs(org.openqa.selenium.OutputType.BASE64), null);
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
        Account acct = activeAccount;
        if (acct != null) {
            sb.append("ACTIVE ACCOUNT FOR THIS PASS: username=").append(acct.username())
                    .append("  password=").append(acct.password());
            if (acct.label() != null && !acct.label().isBlank()) {
                sb.append("  (").append(acct.label()).append(')');
            }
            sb.append('\n')
                    .append("You are in a FRESH session. If you are not yet logged in as THIS exact "
                            + "account, find the login form and submit these credentials FIRST, then "
                            + "explore everything this account can reach. Ignore the other accounts "
                            + "listed in the TASK during this pass.\n\n");
        }
        String login = loginHint(parseElems(elementsJson));
        if (login != null) {
            sb.append(login).append("\n\n");
        }
        String capDisp = cfg.maxSteps <= 0 ? "∞" : Integer.toString(cfg.maxSteps);
        sb.append("STEP ").append(step).append(" / ").append(capDisp).append('\n');
        sb.append("CURRENT URL: ").append(url).append('\n');
        sb.append("TITLE: ").append(title).append('\n');
        sb.append("UNVISITED LINKS REMAINING: ").append(frontier.size()).append('\n');
        sb.append("NEXT UNVISITED (sample): ").append(firstN(frontier, 10)).append('\n');
        CoverageSource cov = coverage;
        if (cov != null) {
            try {
                Set<String> caps = cov.capturedEndpoints(targetHostField);
                sb.append("DISTINCT ENDPOINTS THE PROXY HAS CAPTURED (").append(caps.size())
                        .append("): ").append(firstN(caps, 12)).append('\n');
            } catch (RuntimeException ignored) {
                // coverage is best-effort; never let it break a step
            }
        }
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

    /**
     * If the page has a login form (a password field), build a hint telling the model
     * exactly which indices are the username, password and submit controls and whether
     * each field is already filled - so it fills BOTH before submitting instead of
     * clicking submit on an empty password and looping. Returns null when no login form.
     */
    private static String loginHint(java.util.List<JsonObject> elems) {
        int pwIdx = -1;
        int userIdx = -1;
        int submitIdx = -1;
        boolean pwFilled = false;
        boolean userFilled = false;
        for (JsonObject e : elems) {
            if ("input".equals(getStr(e, "tag", ""))
                    && "password".equalsIgnoreCase(getStr(e, "type", ""))) {
                pwIdx = intOf(e);
                pwFilled = getBool(e, "filled");
                break;
            }
        }
        if (pwIdx < 0) {
            return null;
        }
        for (JsonObject e : elems) {
            int i = intOf(e);
            if (i == pwIdx) {
                continue;
            }
            String tag = getStr(e, "tag", "");
            String type = getStr(e, "type", "").toLowerCase();
            String name = getStr(e, "name", "").toLowerCase();
            if ("input".equals(tag) && (type.equals("text") || type.equals("email") || type.isEmpty()
                    || name.contains("user") || name.contains("email") || name.contains("login"))) {
                userIdx = i;
                userFilled = getBool(e, "filled");
                break;
            }
        }
        for (JsonObject e : elems) {
            String tag = getStr(e, "tag", "");
            String type = getStr(e, "type", "").toLowerCase();
            String txt = getStr(e, "text", "").toLowerCase();
            if (("button".equals(tag) || ("input".equals(tag)
                    && (type.equals("submit") || type.equals("button"))))
                    && (txt.contains("sign") || txt.contains("log")
                        || txt.contains("submit") || txt.contains("continue"))) {
                submitIdx = intOf(e);
                break;
            }
        }
        StringBuilder sb = new StringBuilder("LOGIN FORM DETECTED - sign in before exploring. ");
        if (userIdx >= 0) {
            sb.append("Username field = index ").append(userIdx)
                    .append(userFilled ? " (filled)" : " (EMPTY - type the username here)").append(". ");
        }
        sb.append("Password field = index ").append(pwIdx)
                .append(pwFilled ? " (filled)" : " (EMPTY - type the password here)").append(". ");
        if (submitIdx >= 0) {
            sb.append("Submit = index ").append(submitIdx).append(". ");
        }
        sb.append("Fill BOTH fields with the ACTIVE ACCOUNT credentials, THEN submit; "
                + "do NOT submit while the password is EMPTY.");
        return sb.toString();
    }

    private static int intOf(JsonObject e) {
        try {
            return e.get("i").getAsInt();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private static boolean getBool(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
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
                if (getBool(o, "field")) {
                    sb.append(getBool(o, "filled") ? " (filled)" : " (empty)");
                }
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

    private boolean execute(JsonObject action, String type) {
        try {
            switch (type) {
                case "click" -> {
                    WebElement e = byIndex(action);
                    if (e != null) {
                        scrollTo(e);
                        clickRobust(e);
                        waitForReady(0);
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
                        waitForReady(0);
                    }
                }
                case "back" -> {
                    driver.navigate().back();
                    waitForReady(0);
                }
                case "wait" -> sleep(1200);
                default -> log.accept("[agent] Unknown action: " + type);
            }
            return true;
        } catch (RuntimeException e) {
            log.accept("[agent] action '" + type + "' failed: " + shortMsg(e));
            return false;
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

    /**
     * Click, falling back to a JS click when a real click is intercepted by an
     * overlay (an open dropdown/menu or a modal covering the target). The JS click
     * fires the element's own handler / follows its href, ignoring the overlay, so
     * the agent doesn't waste steps retrying a blocked click.
     */
    private void clickRobust(WebElement e) {
        try {
            e.click();
        } catch (org.openqa.selenium.ElementClickInterceptedException intercepted) {
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", e);
                log.accept("[agent] (click was intercepted by an overlay -> clicked via JS)");
            } catch (RuntimeException ignored) {
                throw intercepted;
            }
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

    /** Every navigation URL on the page (all mechanisms), resolved to absolute URLs. */
    private java.util.List<String> extractLinks() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            // Reveal hidden content first (pure DOM, no navigation), then harvest.
            safe(() -> ((JavascriptExecutor) driver).executeScript(REVEAL_JS), null);
            Object r = ((JavascriptExecutor) driver).executeScript(HARVEST_JS);
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

    /** Wait until the document finished loading (bounded), then optionally settle. */
    private void waitForReady(long settleMs) {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && !stop) {
            Object st = safe(() -> ((JavascriptExecutor) driver)
                    .executeScript("return document.readyState"), "complete");
            if ("complete".equals(String.valueOf(st))) {
                break;
            }
            sleep(150);
        }
        if (settleMs > 0) {
            sleep(settleMs);
        }
    }

    /**
     * Deterministic coverage pass for a freshly reached page: scroll to the bottom
     * (trigger lazy content) and click "load more"-style buttons a bounded number of
     * times so AJAX-appended links appear. Only clicks controls whose visible text
     * clearly means "load the next batch" - never a destructive control.
     */
    private void exhaustPage() {
        safe(() -> ((JavascriptExecutor) driver)
                .executeScript("window.scrollTo(0, document.body.scrollHeight); return null;"), null);
        waitForReady(300);
        for (int i = 0; i < 8 && !stop; i++) {
            WebElement btn = findLoadMore();
            if (btn == null) {
                break;
            }
            try {
                scrollTo(btn);
                btn.click();
            } catch (RuntimeException e) {
                break;
            }
            waitForReady(400);
        }
    }

    private WebElement findLoadMore() {
        try {
            // Buttons only - anchors are navigation and are already harvested as links,
            // so we never click an <a> here (avoids wandering off during the pass).
            for (WebElement e : driver.findElements(
                    By.cssSelector("button,[role=button],input[type=button]"))) {
                if (!safe(e::isDisplayed, false) || !safe(e::isEnabled, false)) {
                    continue;
                }
                String t = safe(e::getText, "");
                if (t == null || t.isBlank()) {
                    continue;
                }
                String low = t.trim();
                if (LOAD_MORE_TEXT.matcher(low).find()
                        && !DESTRUCTIVE_TEXT.matcher(low).find()) {
                    return e;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * Seed the frontier from the site's own {@code /sitemap.xml} and {@code /robots.txt}
     * (both fetched through the browser, so they also land in Burp). We still crawl
     * links for real, so "unlisted" pages omitted from sitemap.xml are not missed -
     * this only guarantees the listed baseline and the robots-mentioned paths.
     */
    private void seedFromSiteFiles(AgentConfig cfg, String targetHost,
                                   LinkedHashSet<String> frontier,
                                   Map<String, Integer> shapeSeen, Pattern extraBlock) {
        String origin = originOf(cfg.targetUrl);
        if (origin == null) {
            return;
        }
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        // robots.txt: collect any Sitemap: URL and Allow/Disallow path tokens.
        String robots = fetchText(origin + "/robots.txt");
        java.util.List<String> sitemapUrls = new java.util.ArrayList<>();
        sitemapUrls.add(origin + "/sitemap.xml");
        if (robots != null) {
            for (String line : robots.split("\\r?\\n")) {
                String l = line.trim();
                int c = l.indexOf(':');
                if (c < 0) {
                    continue;
                }
                String key = l.substring(0, c).trim().toLowerCase();
                String val = l.substring(c + 1).trim();
                if (key.equals("sitemap") && !val.isBlank()) {
                    sitemapUrls.add(val);
                } else if ((key.equals("allow") || key.equals("disallow"))
                        && val.startsWith("/") && !val.contains("*")) {
                    candidates.add(origin + val);
                }
            }
        }
        // sitemap.xml: pull <loc> entries.
        for (String sm : sitemapUrls) {
            String xml = fetchText(sm);
            if (xml == null) {
                continue;
            }
            java.util.regex.Matcher m = Pattern.compile("(?i)<loc>\\s*(.*?)\\s*</loc>").matcher(xml);
            while (m.find()) {
                candidates.add(m.group(1).trim());
            }
        }
        int added = 0;
        for (String u : candidates) {
            String n = normalizeUrl(u);
            if (!inScope(n, targetHost) || frontier.contains(n)) {
                continue;
            }
            if (cfg.blockDestructive && isDestructiveUrl(n, extraBlock)) {
                continue;
            }
            String shape = PathTemplater.signature(n);
            int seen = shapeSeen.getOrDefault(shape, 0);
            if (seen >= samplesPerTemplate) {
                continue;
            }
            shapeSeen.put(shape, seen + 1);
            frontier.add(n);
            added++;
        }
        if (added > 0) {
            log.accept("[agent] Seeded " + added + " URL(s) from sitemap.xml / robots.txt.");
        }
    }

    private String fetchText(String url) {
        try {
            Object r = ((JavascriptExecutor) driver).executeAsyncScript(FETCH_TEXT_JS, url);
            String s = r == null ? "" : r.toString();
            return s.isBlank() ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** scheme://host[:port] of a URL, or null. */
    private static String originOf(String u) {
        try {
            URI uri = new URI(u);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String o = uri.getScheme() + "://" + uri.getHost();
            return uri.getPort() > 0 ? o + ":" + uri.getPort() : o;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Navigate to a URL. Returns true if navigation did not throw (a 3xx redirect or
     * a 4xx/5xx page still counts as success - the request was made and captured);
     * false only on a real driver error (timeout, unreachable). Callers mark a URL
     * "visited" only on success, so a transient failure can still be retried later.
     */
    private boolean gotoUrl(String u) {
        try {
            driver.get(u);
            waitForReady(0);
            return true;
        } catch (RuntimeException e) {
            log.accept("[agent] goto failed (" + trunc(u, 60) + "): " + shortMsg(e));
            return false;
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
        if (h < 0) {
            return u;
        }
        // Keep client-side ROUTE hashes (#/path, #!/path) as distinct URLs so SPA
        // hash routing (e.g. /app#/settings, /app#/billing) is crawled; drop plain
        // in-page anchor fragments (#section, #reviews) that are not new pages.
        String frag = u.substring(h + 1);
        if (frag.startsWith("/") || frag.startsWith("!/") || frag.startsWith("!")) {
            return u;
        }
        return u.substring(0, h);
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

    /** Short human-readable target of an action, for the step log. */
    private static String targetDesc(String type, JsonObject a, java.util.List<JsonObject> elems) {
        if ("goto".equals(type)) {
            return " -> " + trunc(getStr(a, "url", ""), 70);
        }
        if ("click".equals(type) || "type".equals(type)) {
            String idx = a.has("index") ? " #" + getStr(a, "index", "?") : "";
            JsonObject el = elementOf(a, elems);
            if ("type".equals(type)) {
                // Log what was actually TYPED (not the field's placeholder), masking
                // password-ish fields so credentials never land in the log.
                String fieldName = el != null ? getStr(el, "name", "") : "";
                String elType = el != null ? getStr(el, "type", "") : "";
                String lowName = fieldName.toLowerCase();
                boolean secret = "password".equalsIgnoreCase(elType)
                        || lowName.contains("pass") || lowName.contains("pwd");
                String typed = secret ? "***" : trunc(getStr(a, "text", ""), 40);
                return idx + (fieldName.isBlank() ? "" : " [" + fieldName + "]") + " <= '" + typed + "'";
            }
            if (el != null) {
                String href = getStr(el, "href", "");
                String tgt = !href.isBlank() ? href : getStr(el, "text", "");
                return idx + (tgt.isBlank() ? "" : " '" + trunc(tgt, 50) + "'");
            }
            return idx;
        }
        return "";
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
