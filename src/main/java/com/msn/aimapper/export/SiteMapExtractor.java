package com.msn.aimapper.export;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulls request/response records out of Burp's site map and applies simple
 * host / scope filtering.
 */
public final class SiteMapExtractor {

    private SiteMapExtractor() {
    }

    /**
     * @param hostFilter  if non-blank, only keep records whose host contains this substring
     * @param inScopeOnly if true, only keep records Burp considers in-scope
     */
    public static List<HttpRequestResponse> collect(MontoyaApi api,
                                                    String hostFilter,
                                                    boolean inScopeOnly) {
        List<HttpRequestResponse> out = new ArrayList<>();
        String needle = hostFilter == null ? "" : hostFilter.trim().toLowerCase();

        for (HttpRequestResponse rr : api.siteMap().requestResponses()) {
            HttpRequest req = rr.request();
            if (req == null) {
                continue;
            }
            String host = req.httpService() != null ? req.httpService().host() : "";
            if (!needle.isEmpty() && (host == null || !host.toLowerCase().contains(needle))) {
                continue;
            }
            if (inScopeOnly) {
                try {
                    if (!api.scope().isInScope(req.url())) {
                        continue;
                    }
                } catch (RuntimeException ignored) {
                    // malformed URL -> skip the scope test, keep the record
                }
            }
            out.add(rr);
        }
        return out;
    }

    /** Case-insensitive header lookup that avoids relying on version-specific helpers. */
    public static String header(HttpRequest req, String name) {
        if (req == null) {
            return "";
        }
        for (HttpHeader h : req.headers()) {
            if (h.name() != null && h.name().equalsIgnoreCase(name)) {
                return h.value() == null ? "" : h.value();
            }
        }
        return "";
    }
}
