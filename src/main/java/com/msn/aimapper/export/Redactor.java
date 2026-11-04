package com.msn.aimapper.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets before they are written to an export file. Exports are meant to
 * be shared, so credentials that showed up in URLs or request bodies (passwords,
 * tokens, API keys, session ids, signatures) must not leak into them.
 *
 * <p>Conservative by design: it only masks VALUES whose parameter/field NAME looks
 * secret, so ordinary data is preserved. The placeholder keeps the shape visible.</p>
 */
public final class Redactor {

    private static final String MASK = "REDACTED";

    /** Parameter / JSON field names whose values are treated as secret. */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i)(pass(word)?|pwd|token|access[_-]?token|refresh[_-]?token|id[_-]?token|"
            + "api[_-]?key|apikey|secret|client[_-]?secret|auth(orization)?|bearer|"
            + "sig|signature|session|sid|csrf|xsrf|otp|passcode|credential)");

    private Redactor() {
    }

    public static boolean isSecretName(String name) {
        return name != null && SECRET_NAME.matcher(name).find();
    }

    /** Mask secret-named query parameter values in a URL; leave the rest intact. */
    public static String redactUrl(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        String base = url.substring(0, q);
        String query = url.substring(q + 1);
        StringBuilder sb = new StringBuilder(base).append('?');
        String[] parts = query.split("&", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('&');
            }
            String kv = parts[i];
            int eq = kv.indexOf('=');
            if (eq > 0 && isSecretName(kv.substring(0, eq))) {
                sb.append(kv, 0, eq + 1).append(MASK);
            } else {
                sb.append(kv);
            }
        }
        return sb.toString();
    }

    /**
     * Mask secrets in a request/response body example. For JSON, recursively mask
     * secret-named fields; otherwise mask {@code key=value} / {@code key: value}
     * pairs with a secret-looking key.
     */
    public static String redactBody(String contentType, String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            try {
                JsonElement el = JsonParser.parseString(body);
                redactJson(el);
                return el.toString();
            } catch (RuntimeException ignored) {
                // fall through to regex masking
            }
        }
        return redactPairs(body);
    }

    private static void redactJson(JsonElement el) {
        if (el == null) {
            return;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (String key : new java.util.ArrayList<>(o.keySet())) {
                JsonElement v = o.get(key);
                if (isSecretName(key) && v != null && v.isJsonPrimitive()) {
                    o.addProperty(key, MASK);
                } else {
                    redactJson(v);
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                redactJson(e);
            }
        }
    }

    /** Mask {@code key=value} and {@code "key":"value"} pairs with a secret key. */
    private static String redactPairs(String body) {
        // form-encoded:  key=value
        Matcher form = Pattern.compile("([^=&?\\s]+)=([^&\\s]*)").matcher(body);
        StringBuilder sb = new StringBuilder();
        while (form.find()) {
            String rep = isSecretName(form.group(1))
                    ? Matcher.quoteReplacement(form.group(1) + "=" + MASK)
                    : Matcher.quoteReplacement(form.group(0));
            form.appendReplacement(sb, rep);
        }
        form.appendTail(sb);
        return sb.toString();
    }
}
