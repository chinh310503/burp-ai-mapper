package com.msn.aimapper.export;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds an OpenAPI 3.1 document from Burp site-map records.
 *
 * <p>Heuristics: concrete id-like path segments become {@code {id}} parameters,
 * requests are grouped by {@code path + method}, query/body parameters are
 * collected and their types inferred from observed values, and response status
 * codes + content types are recorded. This is a best-effort spec meant as a
 * starting point, not a hand-authored contract.</p>
 */
public final class OpenApiGenerator {

    private OpenApiGenerator() {
    }

    public static void write(Iterable<HttpRequestResponse> records, Path outDir, String title)
            throws IOException {
        Files.createDirectories(outDir);
        JsonObject root = build(records, title);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        Files.write(outDir.resolve("openapi.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject build(Iterable<HttpRequestResponse> records, String title) {
        JsonObject root = new JsonObject();
        root.addProperty("openapi", "3.1.0");

        JsonObject info = new JsonObject();
        info.addProperty("title", (title == null || title.isBlank()) ? "Discovered API" : title);
        info.addProperty("version", "0.1.0");
        info.addProperty("description",
                "Auto-generated from Burp site map by AI Sitemap & API Mapper.");
        root.add("info", info);

        Set<String> serverUrls = new TreeSet<>();
        JsonObject paths = new JsonObject();

        for (HttpRequestResponse rr : records) {
            HttpRequest req = rr.request();
            if (req == null || !looksLikeApi(req, rr.response())) {
                continue;
            }
            if (req.httpService() != null) {
                String scheme = req.httpService().secure() ? "https" : "http";
                int port = req.httpService().port();
                boolean defaultPort = (req.httpService().secure() && port == 443)
                        || (!req.httpService().secure() && port == 80);
                serverUrls.add(scheme + "://" + req.httpService().host()
                        + (defaultPort ? "" : ":" + port));
            }

            String path = PathTemplater.templatize(req.pathWithoutQuery());
            String method = req.method().toLowerCase();

            JsonObject pathItem = child(paths, path);
            JsonObject op = child(pathItem, method);
            if (!op.has("summary")) {
                op.addProperty("summary", req.method() + " " + path);
            }

            mergeParameters(op, req, path);
            mergeRequestBody(op, req);
            mergeResponse(op, rr.response());
        }

        JsonArray servers = new JsonArray();
        for (String url : serverUrls) {
            JsonObject s = new JsonObject();
            s.addProperty("url", url);
            servers.add(s);
        }
        root.add("servers", servers);
        root.add("paths", paths);
        return root;
    }

    /** Keep endpoints that look like API calls (JSON traffic, /api/ paths, or non-GET). */
    private static boolean looksLikeApi(HttpRequest req, HttpResponse resp) {
        String reqCt = SiteMapExtractor.header(req, "Content-Type").toLowerCase();
        String accept = SiteMapExtractor.header(req, "Accept").toLowerCase();
        String respCt = "";
        if (resp != null) {
            for (var h : resp.headers()) {
                if ("Content-Type".equalsIgnoreCase(h.name())) {
                    respCt = h.value() == null ? "" : h.value().toLowerCase();
                }
            }
        }
        String path = req.pathWithoutQuery().toLowerCase();
        boolean jsonish = reqCt.contains("json") || respCt.contains("json")
                || accept.contains("json") || respCt.contains("graphql");
        boolean apiPath = path.contains("/api/") || path.contains("/v1/") || path.contains("/v2/")
                || path.contains("/graphql") || path.contains("/rest/");
        boolean mutating = !"GET".equalsIgnoreCase(req.method())
                && !"HEAD".equalsIgnoreCase(req.method());
        return jsonish || apiPath || mutating;
    }

    private static void mergeParameters(JsonObject op, HttpRequest req, String path) {
        JsonArray params = op.has("parameters")
                ? op.getAsJsonArray("parameters") : new JsonArray();
        Set<String> seen = new HashSet<>();
        for (var el : params) {
            JsonObject p = el.getAsJsonObject();
            seen.add(p.get("in").getAsString() + ":" + p.get("name").getAsString());
        }

        // Query parameters
        for (ParsedHttpParameter p : req.parameters()) {
            if (p.type() == HttpParameterType.URL) {
                addParam(params, seen, p.name(), "query", p.value());
            }
        }
        // Path parameters inferred from the template ({id}, {id2}, ...)
        int from = 0;
        while (true) {
            int open = path.indexOf('{', from);
            if (open < 0) {
                break;
            }
            int close = path.indexOf('}', open);
            if (close < 0) {
                break;
            }
            String name = path.substring(open + 1, close);
            addParam(params, seen, name, "path", "");
            from = close + 1;
        }

        if (params.size() > 0) {
            op.add("parameters", params);
        }
    }

    private static void addParam(JsonArray params, Set<String> seen,
                                 String name, String in, String sampleValue) {
        String key = in + ":" + name;
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.addProperty("in", in);
        p.addProperty("required", in.equals("path"));
        JsonObject schema = new JsonObject();
        schema.addProperty("type", inferType(sampleValue));
        p.add("schema", schema);
        params.add(p);
    }

    private static void mergeRequestBody(JsonObject op, HttpRequest req) {
        String method = req.method().toUpperCase();
        if (method.equals("GET") || method.equals("HEAD") || method.equals("DELETE")) {
            return;
        }
        String body = req.bodyToString();
        if (body == null || body.isEmpty()) {
            return;
        }
        if (op.has("requestBody")) {
            return; // first example wins
        }
        String ct = SiteMapExtractor.header(req, "Content-Type").toLowerCase();

        JsonObject content = new JsonObject();
        JsonObject media = new JsonObject();
        JsonObject schema = new JsonObject();

        if (ct.contains("json")) {
            schema.addProperty("type", "object");
            media.add("schema", schema);
            media.addProperty("example", truncate(body, 4000));
            content.add("application/json", media);
        } else if (ct.contains("x-www-form-urlencoded") || ct.contains("form-data")) {
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            for (ParsedHttpParameter p : req.parameters()) {
                if (p.type() == HttpParameterType.BODY) {
                    JsonObject ps = new JsonObject();
                    ps.addProperty("type", inferType(p.value()));
                    props.add(p.name(), ps);
                }
            }
            schema.add("properties", props);
            media.add("schema", schema);
            content.add(ct.contains("form-data")
                    ? "multipart/form-data" : "application/x-www-form-urlencoded", media);
        } else {
            schema.addProperty("type", "string");
            media.add("schema", schema);
            media.addProperty("example", truncate(body, 2000));
            content.add(ct.isBlank() ? "text/plain" : ct.split(";")[0], media);
        }

        JsonObject rb = new JsonObject();
        rb.add("content", content);
        op.add("requestBody", rb);
    }

    private static void mergeResponse(JsonObject op, HttpResponse resp) {
        JsonObject responses = op.has("responses")
                ? op.getAsJsonObject("responses") : new JsonObject();
        String code = resp != null ? Integer.toString(resp.statusCode()) : "default";
        if (!responses.has(code)) {
            JsonObject r = new JsonObject();
            r.addProperty("description", resp != null ? "Observed response" : "No response captured");
            if (resp != null) {
                String respCt = "";
                for (var h : resp.headers()) {
                    if ("Content-Type".equalsIgnoreCase(h.name())) {
                        respCt = h.value() == null ? "" : h.value().split(";")[0];
                    }
                }
                if (!respCt.isBlank()) {
                    JsonObject content = new JsonObject();
                    content.add(respCt, new JsonObject());
                    r.add("content", content);
                }
            }
            responses.add(code, r);
        }
        op.add("responses", responses);
    }

    private static String inferType(String v) {
        if (v == null || v.isEmpty()) {
            return "string";
        }
        if (v.matches("^-?\\d+$")) {
            return "integer";
        }
        if (v.matches("^-?\\d*\\.\\d+$")) {
            return "number";
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return "boolean";
        }
        return "string";
    }

    private static JsonObject child(JsonObject parent, String key) {
        if (parent.has(key) && parent.get(key).isJsonObject()) {
            return parent.getAsJsonObject(key);
        }
        JsonObject o = new JsonObject();
        parent.add(key, o);
        return o;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
