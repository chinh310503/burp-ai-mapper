package com.msn.aimapper.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal client for any OpenAI-compatible Chat Completions endpoint
 * (OpenAI, OpenRouter, Azure-compatible gateways, local Ollama/LM Studio, ...).
 * Uses only the JDK HTTP client + Gson, so nothing extra needs installing.
 */
public class LlmClient {

    /** One chat message. {@code imageB64} is an optional PNG (base64) for vision. */
    public record Msg(String role, String content, String imageB64) {
        public static Msg system(String c) {
            return new Msg("system", c, null);
        }

        public static Msg user(String c) {
            return new Msg("user", c, null);
        }

        public static Msg assistant(String c) {
            return new Msg("assistant", c, null);
        }

        /** A user message with an attached screenshot (vision fallback). */
        public static Msg userWithImage(String c, String imageB64) {
            return new Msg("user", c, imageB64);
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int maxAttempts;
    /** Whether to send response_format=json_object; auto-disabled if a provider rejects it. */
    private volatile boolean jsonMode;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public LlmClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, 0.2, 0, true, 3);
    }

    public LlmClient(String baseUrl, String apiKey, String model, double temperature,
                     int maxTokens, boolean jsonMode, int maxAttempts) {
        this.baseUrl = normalize(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.jsonMode = jsonMode;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static String normalize(String base) {
        if (base == null || base.isBlank()) {
            return "https://api.openai.com/v1";
        }
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    /**
     * Send a chat request and return the assistant's text content. Retries on
     * timeout / 429 / 5xx with backoff; gives a clear message on auth failure; and
     * transparently drops {@code response_format} for the rest of the session if a
     * provider rejects it (HTTP 400), so odd gateways still work.
     */
    public String chat(List<Msg> messages) throws Exception {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpResponse<String> resp;
            try {
                resp = http.send(buildRequest(messages), HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                // includes HttpTimeoutException (a subclass) -> transient, retry
                last = new RuntimeException("LLM connection error: " + e.getMessage());
                backoff(attempt);
                continue;
            }
            int sc = resp.statusCode();
            if (sc / 100 == 2) {
                return extractContent(resp.body());
            }
            if (sc == 400 && jsonMode) {
                // Some gateways reject response_format; drop it and retry once.
                jsonMode = false;
                continue;
            }
            if (sc == 401 || sc == 403) {
                throw new RuntimeException("LLM auth failed (HTTP " + sc
                        + "): check the API key and Base URL. " + truncate(resp.body(), 300));
            }
            if (sc == 429 || sc / 100 == 5) {
                last = new RuntimeException("LLM HTTP " + sc + ": " + truncate(resp.body(), 300));
                backoff(attempt);
                continue;
            }
            throw new RuntimeException("LLM HTTP " + sc + ": " + truncate(resp.body(), 400));
        }
        throw last != null ? last
                : new RuntimeException("LLM request failed after " + maxAttempts + " attempts");
    }

    private HttpRequest buildRequest(List<Msg> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        if (maxTokens > 0) {
            body.addProperty("max_tokens", maxTokens);
        }
        if (jsonMode) {
            JsonObject fmt = new JsonObject();
            fmt.addProperty("type", "json_object");
            body.add("response_format", fmt);
        }
        JsonArray arr = new JsonArray();
        for (Msg m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            if (m.imageB64() != null && !m.imageB64().isBlank()) {
                // Multimodal content: text part + image part (OpenAI vision format).
                JsonArray parts = new JsonArray();
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", m.content());
                parts.add(textPart);
                JsonObject imgPart = new JsonObject();
                imgPart.addProperty("type", "image_url");
                JsonObject imgUrl = new JsonObject();
                imgUrl.addProperty("url", "data:image/png;base64," + m.imageB64());
                imgPart.add("image_url", imgUrl);
                parts.add(imgPart);
                o.add("content", parts);
            } else {
                o.addProperty("content", m.content());
            }
            arr.add(o);
        }
        body.add("messages", arr);
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
    }

    /**
     * Pull the assistant text out of a chat/completions response, tolerating:
     * a plain string {@code content}, a content PARTS array (content[].text), and
     * reasoning models that leave {@code content} null but fill {@code reasoning_content}.
     */
    private static String extractContent(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM returned no choices: " + truncate(responseBody, 300));
        }
        JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (msg == null) {
            throw new RuntimeException("LLM returned no message: " + truncate(responseBody, 300));
        }
        String text = "";
        if (msg.has("content") && !msg.get("content").isJsonNull()) {
            var c = msg.get("content");
            if (c.isJsonPrimitive()) {
                text = c.getAsString();
            } else if (c.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (var part : c.getAsJsonArray()) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                        sb.append(part.getAsJsonObject().get("text").getAsString());
                    }
                }
                text = sb.toString();
            }
        }
        if (text == null || text.isBlank()) {
            if (msg.has("reasoning_content") && msg.get("reasoning_content").isJsonPrimitive()) {
                text = msg.get("reasoning_content").getAsString();
            }
        }
        return text == null ? "" : text;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(1000L * attempt, 4000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fetch the available model ids from an OpenAI-compatible {@code GET /models}
     * endpoint. Returns them sorted. Works without a model selected yet.
     */
    public static List<String> listModels(String baseUrl, String apiKey) throws Exception {
        String base = normalize(baseUrl);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base + "/models"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        // OpenAI: {"data":[{"id":"gpt-4o"},...]}. Some gateways return {"models":[...]} or a bare array.
        JsonArray data = root.has("data") ? root.getAsJsonArray("data")
                : root.has("models") ? root.getAsJsonArray("models") : null;
        List<String> out = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                var el = data.get(i);
                String id = null;
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("id")) {
                        id = o.get("id").getAsString();
                    } else if (o.has("name")) {
                        id = o.get("name").getAsString();
                    }
                } else if (el.isJsonPrimitive()) {
                    id = el.getAsString();
                }
                if (id != null && !id.isBlank()) {
                    out.add(id);
                }
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Convenience for a single system+user exchange. */
    public String chat(String system, String user) throws Exception {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.system(system));
        msgs.add(Msg.user(user));
        return chat(msgs);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
