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
        Files.createDirectories(outDir);
        writeXml(records, outDir.resolve("sitemap.xml"));
        writeJson(records, outDir.resolve("urls.json"));
        writeEndpoints(records, outDir.resolve("endpoints.json"));
    }

    private static void writeXml(Iterable<HttpRequestResponse> records, Path file) throws IOException {
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
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (Map.Entry<String, TreeSet<String>> e : byShape.entrySet()) {
            TreeSet<String> urls = e.getValue();
            if (urls.size() > 1) {
                sb.append("  <!-- ").append(xmlComment(e.getKey()))
                        .append(" (").append(urls.size()).append(" URLs) -->\n");
            }
            sb.append("  <url><loc>").append(xmlEscape(urls.first())).append("</loc></url>\n");
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
                sample.add(u);
            }
            o.add("examples", sample);
            arr.add(o);
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(arr);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeJson(Iterable<HttpRequestResponse> records, Path file) throws IOException {
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
            o.addProperty("url", req.url());
            HttpResponse resp = rr.response();
            o.addProperty("status", resp != null ? (int) resp.statusCode() : 0);
            o.addProperty("contentType", resp != null ? contentType(resp) : "");
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
