package com.msn.aimapper;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.msn.aimapper.ui.MapperTab;

/**
 * Entry point for the "AI Sitemap &amp; API Mapper" Burp extension.
 *
 * <p>The extension drives an in-process AI browsing agent (Selenium-controlled
 * Chrome/Chromium/Edge, no external sidecar) whose browser is pointed at Burp's
 * own proxy listener. The agent logs in and explores the target automatically;
 * every request flows through Burp and lands in the site map. The extension then
 * reads that site map and exports a sitemap and an OpenAPI 3.1 document.</p>
 */
public class AiMapperExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("AI Sitemap & API Mapper");

        MapperTab tab = new MapperTab(api);
        api.userInterface().registerSuiteTab("AI Mapper", tab.component());

        api.logging().logToOutput(
                "AI Sitemap & API Mapper loaded. Open the 'AI Mapper' tab to start.");
    }
}
