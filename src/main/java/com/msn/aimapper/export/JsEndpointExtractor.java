package com.msn.aimapper.export;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static (read-only) endpoint extraction from the JavaScript and inline HTML that
 * Burp already captured. This surfaces API paths that exist in the app's code but
 * were never actually called during the crawl - exactly the endpoints a pentester
 * is most likely to have missed - without sending a single request (it only reads
 * response bodies already in the site map, so it can never mutate data).
 *
 * <p>Like LinkFinder/jsluice this is regex-based, so it has the same limitations:
 * endpoints assembled at runtime by string concatenation ({@code base + '/' + id})
 * are only partially recovered, and there can be false positives. Template literals
 * ({@code `/users/${id}`}) are normalised to {@code /users/{id}}. Candidates are
 * resolved to absolute URLs on the target host and heavily filtered to drop static
 * assets and obvious junk.</p>
 */
public final class JsEndpointExtractor {

    private JsEndpointExtractor() {
    }

    /** Don't scan absurdly large bodies, and cap total candidates, to stay bounded. */
    private static final int MAX_BODY = 3_000_000;
    private static final int MAX_RESULTS = 8_000;

    private static final String Q = "[\"'`]";

    // High-confidence call sites.
    private static final Pattern P_FETCH =
            Pattern.compile("fetch\\s*\\(\\s*" + Q + "([^\"'`]+)");
    private static final Pattern P_AXIOS =
            Pattern.compile("\\.(?:get|post|put|patch|delete|head|options|request)\\s*\\(\\s*"
                    + Q + "([^\"'`]+)");
    private static final Pattern P_XHR =
            Pattern.compile("\\.open\\s*\\(\\s*" + Q + "[A-Za-z]+" + Q + "\\s*,\\s*"
                    + Q + "([^\"'`]+)");
    private static final Pattern P_ASSIGN =
            Pattern.compile("(?i)(?:url|uri|path|endpoint|href|action|baseurl|route|api)\\s*[:=]\\s*"
                    + Q + "([^\"'`]+)");
    // Quoted path under a clearly API-ish prefix (kept even if short).
    private static final Pattern P_APIPATH = Pattern.compile(
            Q + "(/(?:api|v\\d+|rest|graphql|gql|auth|oauth|admin|internal|webhooks?|rpc|"
            + "users?|accounts?|public|private|services?)(?:/[^\"'`\\s]*)?)" + Q);
    // Any quoted absolute URL, and any quoted rooted path (broad; filtered hard below).
    private static final Pattern P_ABSURL =
            Pattern.compile(Q + "(https?://[^\"'`\\s]+)" + Q);
    private static final Pattern P_PATH =
            Pattern.compile(Q + "(/[A-Za-z0-9_][^\"'`\\s]{2,})" + Q);

    private static final Pattern[] PATTERNS = {
            P_FETCH, P_AXIOS, P_XHR, P_ASSIGN, P_APIPATH, P_ABSURL, P_PATH
    };

    /** Static-asset / non-endpoint extensions to drop (json/xml kept - may be APIs). */
    private static final Pattern STATIC_EXT = Pattern.compile(
            "(?i)\\.(js|mjs|cjs|jsx|ts|tsx|css|scss|sass|less|map|png|jpe?g|gif|svg|ico|bmp|"
            + "webp|avif|woff2?|ttf|otf|eot|mp4|webm|ogg|mp3|wav|avi|mov|pdf|zip|gz|tgz|tar|"
            + "rar|7z|wasm|md|yaml|yml)([?#].*)?$");
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\$\\{[^}]*\\}");

    /**
     * Extract candidate endpoint URLs (absolute, on {@code targetHost}) from the JS and
     * inline-HTML bodies of the given records. Never null; secrets are NOT redacted here
     * (callers redact on write).
     */
    public static Set<String> extract(Iterable<HttpRequestResponse> records, String targetHost) {
        Set<String> out = new LinkedHashSet<>();
        if (targetHost == null || targetHost.isBlank()) {
            return out;
        }
        for (HttpRequestResponse rr : records) {
            if (out.size() >= MAX_RESULTS) {
                break;
            }
            HttpRequest req = rr.request();
            HttpResponse resp = rr.response();
            if (req == null || resp == null) {
                continue;
            }
            String ct = contentType(resp).toLowerCase();
            String url = req.url() == null ? "" : req.url().toLowerCase();
            boolean isJs = ct.contains("javascript") || ct.contains("ecmascript")
                    || url.endsWith(".js") || url.contains(".js?")
                    || url.endsWith(".mjs") || url.endsWith(".jsx");
            boolean isHtml = ct.contains("text/html");
            if (!isJs && !isHtml) {
                continue;
            }
            String body;
            try {
                body = resp.bodyToString();
            } catch (RuntimeException e) {
                continue;
            }
            if (body == null || body.isEmpty()) {
                continue;
            }
            if (body.length() > MAX_BODY) {
                body = body.substring(0, MAX_BODY);
            }
            String origin = originOf(req.url());
            if (origin == null) {
                continue;
            }
            for (Pattern p : PATTERNS) {
                Matcher m = p.matcher(body);
                while (m.find() && out.size() < MAX_RESULTS) {
                    String abs = resolve(m.group(1), origin, targetHost);
                    if (abs != null) {
                        out.add(abs);
                    }
                }
            }
        }
        return out;
    }

    /** Resolve a raw candidate to an absolute URL on {@code targetHost}, or null to drop it. */
    private static String resolve(String raw, String origin, String targetHost) {
        if (raw == null) {
            return null;
        }
        String cand = TEMPLATE_VAR.matcher(raw.trim()).replaceAll("{id}");
        if (!looksLikeEndpoint(cand)) {
            return null;
        }
        String scheme = origin.startsWith("https") ? "https" : "http";
        String abs;
        if (cand.startsWith("http://") || cand.startsWith("https://")) {
            abs = cand;
        } else if (cand.startsWith("//")) {
            abs = scheme + ":" + cand;
        } else if (cand.startsWith("/")) {
            abs = origin + cand;
        } else {
            return null; // bare relative paths are too noisy to resolve reliably
        }
        String h = hostOf(abs);
        if (h == null || !h.equalsIgnoreCase(targetHost)) {
            return null;
        }
        return abs;
    }

    /** Conservative sanity filter to keep real endpoints and drop assets / junk. */
    private static boolean looksLikeEndpoint(String cand) {
        if (cand.length() < 2) {
            return false;
        }
        // Only rooted paths or absolute URLs (resolve() enforces the scheme forms).
        if (!(cand.startsWith("/") || cand.startsWith("http://")
                || cand.startsWith("https://"))) {
            return false;
        }
        // Obvious non-endpoint characters (selectors, regex literals, templates, globs).
        if (cand.indexOf(' ') >= 0 || cand.indexOf('<') >= 0 || cand.indexOf('>') >= 0
                || cand.indexOf('*') >= 0 || cand.indexOf('\\') >= 0 || cand.indexOf('|') >= 0
                || cand.indexOf('^') >= 0 || cand.contains("{{") || cand.contains("}}")) {
            return false;
        }
        // Path part (without scheme/host and without query/fragment).
        String path = cand;
        int sc = path.indexOf("://");
        if (sc >= 0) {
            int ps = path.indexOf('/', sc + 3);
            path = ps >= 0 ? path.substring(ps) : "/";
        }
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        if (path.isEmpty() || path.equals("/")) {
            return false;
        }
        if (path.contains("//")) {
            return false; // "/a//b" is almost always a string-concat artifact
        }
        if (STATIC_EXT.matcher(path).find()) {
            return false;
        }
        return true;
    }

    private static String contentType(HttpResponse resp) {
        for (var h : resp.headers()) {
            if ("Content-Type".equalsIgnoreCase(h.name())) {
                return h.value() == null ? "" : h.value();
            }
        }
        return "";
    }

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

    private static String hostOf(String u) {
        try {
            return new URI(u).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
