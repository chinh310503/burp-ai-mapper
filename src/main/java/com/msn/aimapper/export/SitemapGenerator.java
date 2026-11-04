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
        Files.createDirectories(outDir);
        java.util.Collection<String> extra =
                extraGetUrls == null ? java.util.Collections.emptyList() : extraGetUrls;
        writeXml(records, extra, outDir.resolve("sitemap.xml"));
        writeJson(records, extra, outDir.resolve("urls.json"));
        writeEndpoints(records, outDir.resolve("endpoints.json"));
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
