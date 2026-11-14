package com.msn.aimapper.export;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes sitemap artifacts from the collected records:
 * <ul>
 *   <li>{@code sitemap.xml}   – sitemaps.org XML, GET URLs COLLAPSED by shape
 *       (one representative per endpoint, e.g. {@code /products/{id}})</li>
 *   <li>{@code urls.json}     – every observed request (method, url, status, type), complete</li>
 *   <li>{@code endpoints.json} – deduped API list grouped by method + shape, with count + examples</li>
 * </ul>
 */
public final class SitemapGenerator {

    private SitemapGenerator() {
    }

    public static void write(Iterable<HttpRequestResponse> records, Path outDir) throws IOException {
        write(records, outDir, java.util.Collections.emptyList());
    }

    /**
     * @param extraGetUrls additional in-scope GET page URLs the crawler reached that
     *                     the proxy never saw as distinct requests - client-only
     *                     routes such as {@code /app#/settings} (hash routing) and
     *                     SPA pushState paths. Merged into sitemap.xml and urls.json.
     */
    public static void write(Iterable<HttpRequestResponse> records, Path outDir,
                             java.util.Collection<String> extraGetUrls) throws IOException {
        write(records, outDir, extraGetUrls, java.util.Collections.emptyList());
    }

    /**
     * @param extraGetUrls  client-only GET routes the crawler reached (see above)
     * @param jsStaticUrls  endpoint URLs extracted STATICALLY from captured JS / inline
     *                      HTML (never requested - candidates that exist in the app's
     *                      code but may never have been exercised). Merged into
     *                      sitemap.xml and urls.json tagged {@code source:"js-static"},
     *                      and listed with an {@code observed} gap flag in
     *                      {@code js-endpoints.json}.
     */
    public static void write(Iterable<HttpRequestResponse> records, Path outDir,
                             java.util.Collection<String> extraGetUrls,
                             java.util.Collection<String> jsStaticUrls) throws IOException {
        Files.createDirectories(outDir);
        java.util.Collection<String> extra =
                extraGetUrls == null ? java.util.Collections.emptyList() : extraGetUrls;
        java.util.Collection<String> js =
                jsStaticUrls == null ? java.util.Collections.emptyList() : jsStaticUrls;
        // sitemap.xml lists every GET-able location, so both client routes and the
        // static JS candidates are included there.
        java.util.List<String> allExtraLocs = new java.util.ArrayList<>(extra);
        allExtraLocs.addAll(js);
        writeXml(records, allExtraLocs, outDir.resolve("sitemap.xml"));
        writeJson(records, extra, js, outDir.resolve("urls.json"));
        writeEndpoints(records, outDir.resolve("endpoints.json"));
        writeMutations(records, outDir.resolve("mutations.json"));
        writeJsEndpoints(records, js, outDir.resolve("js-endpoints.json"));
    }

    /**
     * Static-JS endpoint candidates with a coverage gap flag: {@code observed} is true
     * when the same endpoint SHAPE (method-agnostic) actually appeared in captured
     * proxy traffic. {@code observed:false} means the endpoint exists in the app's code
     * but was never exercised during the crawl - the highest-signal "did the pentester
     * even touch this?" list. Secrets in URLs are redacted.
     */
    private static void writeJsEndpoints(Iterable<HttpRequestResponse> records,
                                         java.util.Collection<String> jsUrls, Path file)
            throws IOException {
        java.util.Set<String> observedShapes = new java.util.HashSet<>();
        for (HttpRequestResponse rr : records) {
            HttpRequest req = rr.request();
            if (req != null) {
                observedShapes.add(PathTemplater.signature(req.url()));
            }
        }
        JsonArray arr = new JsonArray();
        TreeSet<String> sorted = new TreeSet<>();
        for (String u : jsUrls) {
            if (u != null && !u.isBlank()) {
                sorted.add(u);
            }
        }
        for (String u : sorted) {
            JsonObject o = new JsonObject();
            o.addProperty("url", Redactor.redactUrl(u));
            String shape = PathTemplater.signature(u);
            o.addProperty("shape", Redactor.redactUrl(shape));
            o.addProperty("observed", observedShapes.contains(shape));
            o.addProperty("source", "js-static");
            arr.add(o);
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(arr);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Audit trail of data CHANGES: every non-GET request the agent actually sent
     * (POST/PUT/PATCH/DELETE, response present) with its request body, so you can
     * review - or undo - anything that was created or modified during the crawl.
     * Secrets in the URL/body are redacted. Classified as create/update/delete.
     */
    private static void writeMutations(Iterable<HttpRequestResponse> records, Path file)
            throws IOException {
        JsonArray arr = new JsonArray();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (HttpRequestResponse rr : records) {
            HttpRequest req = rr.request();
            if (req == null) {
                continue;
            }
            String method = req.method() == null ? "" : req.method().toUpperCase();
            if (method.isEmpty() || method.equals("GET") || method.equals("HEAD")
                    || method.equals("OPTIONS")) {
                continue; // only state-changing verbs
            }
            HttpResponse resp = rr.response();
            if (resp == null) {
                continue; // only requests that were actually sent (a real change)
            }
            String body = "";
            try {
                body = req.bodyToString();
            } catch (RuntimeException ignored) {
                body = "";
            }
            if (body == null) {
                body = "";
            }
            String key = method + " " + req.url() + " " + body;
            if (!seen.add(key)) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("kind", classifyMutation(method, req.url()));
            o.addProperty("method", method);
            o.addProperty("url", Redactor.redactUrl(req.url()));
            o.addProperty("status", (int) resp.statusCode());
            if (!body.isBlank()) {
                String ct = SiteMapExtractor.header(req, "Content-Type");
                String shown = body.length() > 2000 ? body.substring(0, 2000) + "...[truncated]" : body;
                o.addProperty("requestBody", Redactor.redactBody(ct, shown));
            }
            arr.add(o);
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(arr);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Best-effort create/update/delete label from the verb and path. */
    private static String classifyMutation(String method, String url) {
        String u = url == null ? "" : url.toLowerCase();
        if ("DELETE".equals(method) || u.matches(".*(^|[/?&=._-])(delete|destroy|remove)([/?&=._-]|$).*")) {
            return "delete";
        }
        if (u.contains("/new") || u.contains("/create") || u.contains("/register")
                || u.contains("/signup") || u.contains("/sign-up")) {
            return "create";
        }
        if (u.contains("/edit") || u.contains("/update") || "PUT".equals(method) || "PATCH".equals(method)) {
            return "update";
        }
        return "POST".equals(method) ? "create-or-submit" : method.toLowerCase();
    }

    private static void writeXml(Iterable<HttpRequestResponse> records,
                                 java.util.Collection<String> extraGetUrls,
                                 Path file) throws IOException {
        // Group GET URLs by shape so /products/101..125 collapse to one entry.
        // Sorted maps/sets keep output stable and the representative deterministic.
        Map<String, TreeSet<String>> byShape = new TreeMap<>();
        for (HttpRequestResponse rr : records) {
            HttpRequest req = rr.request();
            if (req != null && "GET".equalsIgnoreCase(req.method())) {
                String shape = PathTemplater.signature(req.url());
                byShape.computeIfAbsent(shape, k -> new TreeSet<>()).add(req.url());
            }
        }
        // Client-only routes the proxy never recorded (hash / SPA pushState pages).
        for (String u : extraGetUrls) {
            if (u != null && !u.isBlank()) {
                byShape.computeIfAbsent(PathTemplater.signature(u), k -> new TreeSet<>()).add(u);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (Map.Entry<String, TreeSet<String>> e : byShape.entrySet()) {
            TreeSet<String> urls = e.getValue();
            if (urls.size() > 1) {
                sb.append("  <!-- ").append(xmlComment(e.getKey()))
                        .append(" (").append(urls.size()).append(" URLs) -->\n");
            }
            sb.append("  <url><loc>").append(xmlEscape(Redactor.redactUrl(urls.first())))
                    .append("</loc></url>\n");
        }
        sb.append("</urlset>\n");
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeEndpoints(Iterable<HttpRequestResponse> records, Path file) throws IOException {
        // Deduped, method-aware API list: one row per METHOD + shape.
        Map<String, JsonObject> byKey = new TreeMap<>();
        Map<String, TreeSet<String>> examples = new TreeMap<>();
        for (HttpRequestResponse rr : records) {
            HttpRequest req = rr.request();
            if (req == null) {
                continue;
            }
            String method = req.method() == null ? "GET" : req.method().toUpperCase();
            String shape = PathTemplater.signature(req.url());
            String key = method + " " + shape;
            TreeSet<String> ex = examples.computeIfAbsent(key, k -> new TreeSet<>());
            ex.add(req.url());
            JsonObject o = byKey.get(key);
            if (o == null) {
                o = new JsonObject();
                o.addProperty("method", method);
                o.addProperty("endpoint", shape);
                byKey.put(key, o);
            }
        }
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
            JsonObject o = e.getValue();
            TreeSet<String> ex = examples.get(e.getKey());
            o.addProperty("count", ex.size());
            JsonArray sample = new JsonArray();
            int n = 0;
            for (String u : ex) {
                if (n++ >= 3) {
                    break;
                }
                sample.add(Redactor.redactUrl(u));
            }
            o.add("examples", sample);
            arr.add(o);
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(arr);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeJson(Iterable<HttpRequestResponse> records,
                                  java.util.Collection<String> extraGetUrls,
                                  java.util.Collection<String> jsStaticUrls,
                                  Path file) throws IOException {
        // De-duplicate by METHOD + URL, keeping the first response seen.
        Map<String, JsonObject> unique = new LinkedHashMap<>();
        for (HttpRequestResponse rr : records) {
            HttpRequest req = rr.request();
            if (req == null) {
                continue;
            }
            String key = req.method() + " " + req.url();
            if (unique.containsKey(key)) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("method", req.method());
            o.addProperty("url", Redactor.redactUrl(req.url()));
            HttpResponse resp = rr.response();
            o.addProperty("status", resp != null ? (int) resp.statusCode() : 0);
            o.addProperty("contentType", resp != null ? contentType(resp) : "");
            unique.put(key, o);
        }
        // Client-only routes the proxy never recorded, flagged so they're distinguishable.
        for (String u : extraGetUrls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            String key = "GET " + u;
            if (unique.containsKey(key)) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("method", "GET");
            o.addProperty("url", Redactor.redactUrl(u));
            o.addProperty("status", 0);
            o.addProperty("contentType", "");
            o.addProperty("source", "crawler-client-route");
            unique.put(key, o);
        }
        // Endpoints extracted statically from JS / inline HTML (never requested). Added
        // only if not already observed, and tagged so they can be filtered out.
        for (String u : jsStaticUrls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            String key = "GET " + u;
            if (unique.containsKey(key)) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("method", "GET");
            o.addProperty("url", Redactor.redactUrl(u));
            o.addProperty("status", 0);
            o.addProperty("contentType", "");
            o.addProperty("source", "js-static");
            unique.put(key, o);
        }
        JsonArray arr = new JsonArray();
        unique.values().forEach(arr::add);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(arr);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String contentType(HttpResponse resp) {
        for (var h : resp.headers()) {
            if ("Content-Type".equalsIgnoreCase(h.name())) {
                return h.value() == null ? "" : h.value();
            }
        }
        return "";
    }

    /** Make a string safe inside an XML comment ("--" is illegal there). */
    private static String xmlComment(String s) {
        return s.replace("--", "-‑").replace("<", "(").replace(">", ")");
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
