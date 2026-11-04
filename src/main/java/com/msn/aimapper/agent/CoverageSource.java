package com.msn.aimapper.agent;

import java.util.Set;

/**
 * Live view of what the proxy (Burp) has actually captured so far, so the agent
 * can steer toward endpoints it has NOT exercised yet instead of re-walking the
 * same ones. Implemented by the UI over Burp's site map; this keeps the agent
 * decoupled from Burp while still getting real coverage feedback (no fragile
 * version-pinned CDP needed, because Burp already sees every request).
 */
@FunctionalInterface
public interface CoverageSource {

    /**
     * Distinct {@code "METHOD shape"} signatures captured for the given host so far
     * (e.g. {@code "GET https://host/products/{id}"}). Never null.
     *
     * @param targetHost host to filter to (blank/null = all hosts)
     */
    Set<String> capturedEndpoints(String targetHost);
}
