package com.msn.aimapper.export;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * <p>Heuristics: concrete id-like path segments become {@code {id}} parameters;
 * requests are grouped by {@code path + method}; query/path parameters are
 * collected and typed from observed values; JSON request AND response bodies are
 * parsed into real schemas (object/array/properties) and MERGED across every
 * observed sample (a field seen in all samples is marked {@code required});
 * observed cookie/bearer auth becomes a security scheme; and each operation is
 * tagged by its first path segment. Secrets in example bodies are redacted. This
 * is a best-effort spec meant as a starting point, not a hand-authored contract.</p>
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
        Set<String> tags = new TreeSet<>();
        boolean sawCookieAuth = false;
        boolean sawBearerAuth = false;
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

            // Observed auth -> security scheme.
            if (!SiteMapExtractor.header(req, "Cookie").isBlank()) {
                sawCookieAuth = true;
            }
            if (SiteMapExtractor.header(req, "Authorization").toLowerCase().startsWith("bearer")) {
                sawBearerAuth = true;
            }

            String path = PathTemplater.templatize(req.pathWithoutQuery());
            String method = req.method().toLowerCase();

            JsonObject pathItem = child(paths, path);
            JsonObject op = child(pathItem, method);
            if (!op.has("summary")) {
                op.addProperty("summary", req.method() + " " + path);
            }
            String tag = firstSegment(path);
            tags.add(tag);
            if (!op.has("tags")) {
                JsonArray t = new JsonArray();
                t.add(tag);
                op.add("tags", t);
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

        // components.securitySchemes + a root security requirement for what we saw.
        if (sawCookieAuth || sawBearerAuth) {
            JsonObject schemes = new JsonObject();
            JsonArray security = new JsonArray();
            if (sawCookieAuth) {
                JsonObject s = new JsonObject();
                s.addProperty("type", "apiKey");
                s.addProperty("in", "cookie");
                s.addProperty("name", "session");
                schemes.add("cookieAuth", s);
                JsonObject req = new JsonObject();
                req.add("cookieAuth", new JsonArray());
                security.add(req);
            }
            if (sawBearerAuth) {
                JsonObject s = new JsonObject();
                s.addProperty("type", "http");
                s.addProperty("scheme", "bearer");
                schemes.add("bearerAuth", s);
                JsonObject req = new JsonObject();
                req.add("bearerAuth", new JsonArray());
                security.add(req);
            }
            JsonObject components = new JsonObject();
            components.add("securitySchemes", schemes);
            root.add("components", components);
            root.add("security", security);
        }

        JsonArray tagArr = new JsonArray();
        for (String t : tags) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t);
            tagArr.add(o);
        }
        root.add("tags", tagArr);
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
        if (method.equals("GET") || method.equals("HEAD")) {
            return;
        }
        String body = req.bodyToString();
        if (body == null || body.isEmpty()) {
            return;
        }
        String ct = SiteMapExtractor.header(req, "Content-Type").toLowerCase();

        JsonObject rb = op.has("requestBody") ? op.getAsJsonObject("requestBody") : new JsonObject();
        JsonObject content = rb.has("content") ? rb.getAsJsonObject("content") : new JsonObject();

        if (ct.contains("json")) {
            JsonObject media = content.has("application/json")
                    ? content.getAsJsonObject("application/json") : new JsonObject();
            JsonElement parsed = tryParseJson(body);
            if (parsed != null) {
                JsonObject inferred = schemaOf(parsed);
                JsonObject merged = media.has("schema")
                        ? mergeSchema(media.getAsJsonObject("schema"), inferred) : inferred;
                media.add("schema", merged);
            } else if (!media.has("schema")) {
                JsonObject s = new JsonObject();
                s.addProperty("type", "object");
                media.add("schema", s);
            }
            if (!media.has("example")) {
                media.addProperty("example", Redactor.redactBody("application/json",
                        truncate(body, 4000)));
            }
            content.add("application/json", media);
        } else if (ct.contains("x-www-form-urlencoded") || ct.contains("form-data")) {
            String mt = ct.contains("form-data") ? "multipart/form-data"
                    : "application/x-www-form-urlencoded";
            JsonObject media = content.has(mt) ? content.getAsJsonObject(mt) : new JsonObject();
            JsonObject schema = media.has("schema") ? media.getAsJsonObject("schema") : new JsonObject();
            schema.addProperty("type", "object");
            JsonObject props = schema.has("properties")
                    ? schema.getAsJsonObject("properties") : new JsonObject();
            for (ParsedHttpParameter p : req.parameters()) {
                if (p.type() == HttpParameterType.BODY && !props.has(p.name())) {
                    JsonObject ps = new JsonObject();
                    ps.addProperty("type", inferType(p.value()));
                    props.add(p.name(), ps);
                }
            }
            schema.add("properties", props);
            media.add("schema", schema);
            content.add(mt, media);
        } else {
            String mt = ct.isBlank() ? "text/plain" : ct.split(";")[0];
            JsonObject media = content.has(mt) ? content.getAsJsonObject(mt) : new JsonObject();
            if (!media.has("schema")) {
                JsonObject s = new JsonObject();
                s.addProperty("type", "string");
                media.add("schema", s);
            }
            if (!media.has("example")) {
                media.addProperty("example", Redactor.redactBody(mt, truncate(body, 2000)));
            }
            content.add(mt, media);
        }

        rb.add("content", content);
        op.add("requestBody", rb);
    }

    private static void mergeResponse(JsonObject op, HttpResponse resp) {
        JsonObject responses = op.has("responses")
                ? op.getAsJsonObject("responses") : new JsonObject();
        String code = resp != null ? Integer.toString(resp.statusCode()) : "default";
        JsonObject r = responses.has(code) ? responses.getAsJsonObject(code) : new JsonObject();
        if (!r.has("description")) {
            r.addProperty("description", resp != null ? "Observed response" : "No response captured");
        }
        if (resp != null) {
            String respCt = "";
            for (var h : resp.headers()) {
                if ("Content-Type".equalsIgnoreCase(h.name())) {
                    respCt = h.value() == null ? "" : h.value().split(";")[0].trim();
                }
            }
            if (!respCt.isBlank()) {
                JsonObject content = r.has("content") ? r.getAsJsonObject("content") : new JsonObject();
                JsonObject media = content.has(respCt) ? content.getAsJsonObject(respCt) : new JsonObject();
                if (respCt.toLowerCase().contains("json")) {
                    JsonElement parsed = tryParseJson(resp.bodyToString());
                    if (parsed != null) {
                        JsonObject inferred = schemaOf(parsed);
                        JsonObject merged = media.has("schema")
                                ? mergeSchema(media.getAsJsonObject("schema"), inferred) : inferred;
                        media.add("schema", merged);
                    }
                }
                content.add(respCt, media);
                r.add("content", content);
            }
        }
        responses.add(code, r);
        op.add("responses", responses);
    }

    // --- JSON schema inference -------------------------------------------------

    /** Recursively infer an OpenAPI schema from a concrete JSON value. */
    private static JsonObject schemaOf(JsonElement el) {
        JsonObject schema = new JsonObject();
        if (el == null || el.isJsonNull()) {
            schema.addProperty("type", "null");
            return schema;
        }
        if (el.isJsonObject()) {
            schema.addProperty("type", "object");
            JsonObject props = new JsonObject();
            JsonArray required = new JsonArray();
            for (var e : el.getAsJsonObject().entrySet()) {
                props.add(e.getKey(), schemaOf(e.getValue()));
                required.add(e.getKey());
            }
            schema.add("properties", props);
            if (required.size() > 0) {
                schema.add("required", required);
            }
            return schema;
        }
        if (el.isJsonArray()) {
            schema.addProperty("type", "array");
            JsonObject items = null;
            for (JsonElement e : el.getAsJsonArray()) {
                items = items == null ? schemaOf(e) : mergeSchema(items, schemaOf(e));
            }
            schema.add("items", items == null ? new JsonObject() : items);
            return schema;
        }
        // primitive
        var prim = el.getAsJsonPrimitive();
        if (prim.isBoolean()) {
            schema.addProperty("type", "boolean");
        } else if (prim.isNumber()) {
            String n = prim.getAsString();
            schema.addProperty("type", n.matches("-?\\d+") ? "integer" : "number");
        } else {
            schema.addProperty("type", "string");
        }
        return schema;
    }

    /**
     * Merge two inferred schemas (from different samples of the same field):
     * union object properties, intersect {@code required}, merge array items, keep a
     * shared primitive type, and drop {@code type} when samples disagree (= any).
     */
    private static JsonObject mergeSchema(JsonObject a, JsonObject b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        String ta = a.has("type") ? a.get("type").getAsString() : null;
        String tb = b.has("type") ? b.get("type").getAsString() : null;
        // A "null" sample just means the field was nullable; prefer the other type.
        if ("null".equals(ta)) {
            JsonObject m = b.deepCopy();
            m.addProperty("nullable", true);
            return m;
        }
        if ("null".equals(tb)) {
            JsonObject m = a.deepCopy();
            m.addProperty("nullable", true);
            return m;
        }
        JsonObject out = new JsonObject();
        if (ta != null && ta.equals(tb)) {
            out.addProperty("type", ta);
        }
        if ("object".equals(ta) && "object".equals(tb)) {
            JsonObject pa = a.has("properties") ? a.getAsJsonObject("properties") : new JsonObject();
            JsonObject pb = b.has("properties") ? b.getAsJsonObject("properties") : new JsonObject();
            JsonObject props = pa.deepCopy();
            for (var e : pb.entrySet()) {
                if (props.has(e.getKey())) {
                    props.add(e.getKey(),
                            mergeSchema(props.getAsJsonObject(e.getKey()), e.getValue().getAsJsonObject()));
                } else {
                    props.add(e.getKey(), e.getValue());
                }
            }
            out.add("properties", props);
            JsonArray req = intersectRequired(a, b);
            if (req.size() > 0) {
                out.add("required", req);
            }
        } else if ("array".equals(ta) && "array".equals(tb)) {
            JsonObject ia = a.has("items") ? a.getAsJsonObject("items") : new JsonObject();
            JsonObject ib = b.has("items") ? b.getAsJsonObject("items") : new JsonObject();
            out.add("items", mergeSchema(ia, ib));
        }
        if (a.has("nullable") || b.has("nullable")) {
            out.addProperty("nullable", true);
        }
        return out;
    }

    private static JsonArray intersectRequired(JsonObject a, JsonObject b) {
        Set<String> ra = toSet(a.has("required") ? a.getAsJsonArray("required") : new JsonArray());
        Set<String> rb = toSet(b.has("required") ? b.getAsJsonArray("required") : new JsonArray());
        ra.retainAll(rb);
        JsonArray out = new JsonArray();
        for (String s : new TreeSet<>(ra)) {
            out.add(s);
        }
        return out;
    }

    private static Set<String> toSet(JsonArray arr) {
        Set<String> s = new HashSet<>();
        for (JsonElement e : arr) {
            s.add(e.getAsString());
        }
        return s;
    }

    private static JsonElement tryParseJson(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(s);
            return (el.isJsonObject() || el.isJsonArray()) ? el : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstSegment(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        String seg = slash >= 0 ? p.substring(0, slash) : p;
        return seg.isBlank() ? "root" : seg;
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
