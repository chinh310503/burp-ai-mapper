package com.msn.aimapper.export;

import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Collapses concrete path segments that look like identifiers into OpenAPI path
 * parameters, so {@code /users/123/orders/987} and {@code /users/456/orders/1}
 * both map to the single path {@code /users/{id}/orders/{id2}}.
 */
public final class PathTemplater {

    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern LONG_HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");
    private static final Pattern HASHLIKE = Pattern.compile("^[A-Za-z0-9_-]{24,}$");

    private PathTemplater() {
    }

    /** True if the segment looks like a dynamic identifier. */
    public static boolean isIdentifier(String seg) {
        if (seg.isEmpty()) {
            return false;
        }
        return NUMERIC.matcher(seg).matches()
                || UUID.matcher(seg).matches()
                || LONG_HEX.matcher(seg).matches()
                || HASHLIKE.matcher(seg).matches();
    }

    /** Return the templated path, e.g. "/users/{id}/orders/{id2}". */
    public static String templatize(String pathWithoutQuery) {
        String path = pathWithoutQuery == null ? "/" : pathWithoutQuery;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String[] segs = path.split("/", -1);
        int idCount = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i];
            if (i > 0) {
                sb.append('/');
            }
            if (isIdentifier(s)) {
                idCount++;
                sb.append(idCount == 1 ? "{id}" : "{id" + idCount + "}");
            } else {
                sb.append(s);
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? "/" : out;
    }

    /**
     * A "shape" key for grouping URLs that are the same endpoint with different
     * identifiers or query values. Keeps scheme + host, templatizes the path
     * ({@code /user/1} and {@code /user/2} -> {@code /user/{id}}), and appends the
     * SORTED set of query-parameter NAMES (values dropped, so
     * {@code ?category=books} and {@code ?category=toys} collapse, but
     * {@code ?page=1} vs {@code ?page=1&sort=x} stay distinct).
     *
     * <p>Two URLs with the same signature hit the same backend endpoint, so one
     * representative is enough to map the API — this is what lets the crawler
     * sample instead of walking every id, and the export collapse duplicates,
     * without missing any distinct endpoint.</p>
     */
    public static String signature(String url) {
        if (url == null || url.isEmpty()) {
            return "/";
        }
        String base = url;
        String query = "";
        int q = url.indexOf('?');
        if (q >= 0) {
            base = url.substring(0, q);
            query = url.substring(q + 1);
        }
        // Split scheme://host from the path so we only templatize the path part.
        String prefix = "";
        String path = base;
        int scheme = base.indexOf("://");
        if (scheme >= 0) {
            int pathStart = base.indexOf('/', scheme + 3);
            if (pathStart >= 0) {
                prefix = base.substring(0, pathStart);
                path = base.substring(pathStart);
            } else {
                prefix = base;
                path = "/";
            }
        }
        String sig = prefix + templatize(path);
        if (!query.isBlank()) {
            TreeSet<String> names = new TreeSet<>();
            for (String kv : query.split("&")) {
                if (kv.isEmpty()) {
                    continue;
                }
                int eq = kv.indexOf('=');
                String name = eq >= 0 ? kv.substring(0, eq) : kv;
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                sig += "?" + String.join("&", names);
            }
        }
        return sig;
    }
}
