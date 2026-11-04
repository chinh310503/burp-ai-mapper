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

    /** One chat message. */
    public record Msg(String role, String content) {
        public static Msg system(String c) {
            return new Msg("system", c);
        }

        public static Msg user(String c) {
            return new Msg("user", c);
        }

        public static Msg assistant(String c) {
            return new Msg("assistant", c);
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public LlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = normalize(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
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

    /** Send a chat request and return the assistant's text content. */
    public String chat(List<Msg> messages) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.2);
        JsonArray arr = new JsonArray();
        for (Msg m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        body.add("messages", arr);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("LLM HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body(), 500));
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM returned no choices: " + truncate(resp.body(), 300));
        }
        JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String content = msg.get("content").getAsString();
        return content == null ? "" : content;
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
