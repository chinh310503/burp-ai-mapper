package com.msn.aimapper.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.msn.aimapper.agent.AgentConfig;
import com.msn.aimapper.agent.BrowserAgent;
import com.msn.aimapper.agent.LlmClient;
import com.msn.aimapper.export.OpenApiGenerator;
import com.msn.aimapper.export.PathTemplater;
import com.msn.aimapper.export.SiteMapExtractor;
import com.msn.aimapper.export.SitemapGenerator;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;

/** Swing UI for the "AI Mapper" suite tab. Everything runs in-process (no sidecar). */
public class MapperTab {

    private final MontoyaApi api;
    private final BrowserAgent agent;

    // Task prompt (accounts + passwords + instructions; multiple accounts allowed).
    private final JTextArea prompt = new JTextArea(8, 60);

    private final JTextField targetUrl = new JTextField("https://");

    // AI config
    private final JComboBox<String> model = new JComboBox<>();
    private final JTextField baseUrl = new JTextField("https://api.openai.com/v1");
    private final JPasswordField apiKey = new JPasswordField();
    private final JTextField temperature = new JTextField("0.2");
    private final JTextField maxTokens = new JTextField("0");

    // Browser / proxy
    private final JTextField proxyHost = new JTextField("127.0.0.1");
    private final JTextField proxyPort = new JTextField("8080");
    private final JTextField maxSteps = new JTextField("0");
    private final JTextField samplesPerShape = new JTextField("2");
    private final JTextField chromeBinary = new JTextField();
    private final JCheckBox headless = new JCheckBox("Headless browser");
    private final JCheckBox vision = new JCheckBox("Vision fallback (needs a vision-capable model)");

    // Safety
    private final JCheckBox blockDestructive =
            new JCheckBox("Block destructive actions (delete / logout / reset / pay)", true);
    private final JTextField extraBlockKeywords = new JTextField();

    // Export
    private final JTextField outputDir = new JTextField();
    private final JTextField hostFilter = new JTextField();
    private final JCheckBox inScopeOnly = new JCheckBox("In-scope only", true);
    private final JTextField groundTruth = new JTextField();
    private final JTextField logFile = new JTextField();

    // Where agent/log lines are also written (resolved at Start). null = file off.
    private volatile java.nio.file.Path logFilePath;
    private final Object logLock = new Object();

    private final JTextArea logArea = new JTextArea();
    private final JPanel root = new JPanel(new BorderLayout());

    public MapperTab(MontoyaApi api) {
        this.api = api;
        this.agent = new BrowserAgent(this::log, this::capturedEndpoints);
        build();
    }

    public JComponent component() {
        return root;
    }

    private void build() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        form.add(section("1. Task prompt  (accounts, passwords, what to explore — multiple accounts OK)"));
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.setText(
                "Log in to the target and explore every feature.\n"
              + "Accounts:\n"
              + "  - user1 / password1  (role: admin)\n"
              + "  - user2 / password2  (role: normal)\n"
              + "Start as the admin account. Open every menu, page and detail view, and submit "
              + "forms with harmless test data to trigger API calls. Do not log out or delete data.");
        JScrollPane promptScroll = smooth(new JScrollPane(prompt));
        promptScroll.setAlignmentX(0f);
        form.add(promptScroll);

        form.add(row("Target URL", targetUrl));

        form.add(section("2. AI model  (OpenAI-compatible: OpenAI / OpenRouter / gateway / local)"));
        model.setEditable(true);
        model.addItem("gpt-4.1-mini");
        model.setSelectedItem("gpt-4.1-mini");
        form.add(modelRow());
        form.add(row("Base URL", baseUrl));
        form.add(row("API key", apiKey));
        form.add(row("Temperature", temperature));
        form.add(row("Max tokens (0 = provider default)", maxTokens));

        form.add(section("3. Browser & Burp proxy"));
        form.add(row("Proxy host", proxyHost));
        form.add(row("Proxy port", proxyPort));
        form.add(row("Max steps (0 = crawl until complete)", maxSteps));
        form.add(row("Samples per URL shape", samplesPerShape));
        form.add(row("Chrome binary (optional)", chromeBinary));
        JPanel cb = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        cb.add(headless);
        cb.add(vision);
        form.add(cb);

        form.add(section("4. Safety"));
        JPanel sb = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sb.add(blockDestructive);
        form.add(sb);
        form.add(row("Extra block keywords (optional)", extraBlockKeywords));

        form.add(section("5. Export"));
        form.add(outputRow());
        form.add(row("Host filter (substring)", hostFilter));
        JPanel scope = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        scope.add(inScopeOnly);
        form.add(scope);
        form.add(row("Ground-truth URLs file (optional, for scoring)", groundTruth));
        form.add(row("Log file (optional; blank = <output dir>/ai-mapper.log)", logFile));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton start = new JButton("Start AI browsing");
        JButton stop = new JButton("Stop");
        JButton exSitemap = new JButton("Export sitemap");
        JButton exApi = new JButton("Export OpenAPI");
        JButton exLog = new JButton("Export log");
        JButton score = new JButton("Score vs ground-truth");
        JButton clear = new JButton("Clear log");
        buttons.add(start);
        buttons.add(stop);
        buttons.add(exSitemap);
        buttons.add(exApi);
        buttons.add(exLog);
        buttons.add(score);
        buttons.add(clear);
        form.add(Box.createVerticalStrut(8));
        form.add(buttons);

        start.addActionListener(e -> onStart());
        stop.addActionListener(e -> agent.stop());
        exSitemap.addActionListener(e -> onExportSitemap());
        exApi.addActionListener(e -> onExportApi());
        exLog.addActionListener(e -> onExportLog());
        score.addActionListener(e -> onScore());
        clear.addActionListener(e -> logArea.setText(""));

        logArea.setEditable(false);
        JScrollPane logScroll = smooth(new JScrollPane(logArea));
        logScroll.setPreferredSize(new Dimension(640, 220));

        JScrollPane formScroll = smooth(new JScrollPane(form));
        // Only scroll vertically; never force a horizontal bar on the whole form.
        formScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, formScroll, logScroll);
        split.setResizeWeight(0.68);
        root.add(split, BorderLayout.CENTER);

        loadPrefs();
    }

    private void onStart() {
        AgentConfig cfg = new AgentConfig();
        cfg.prompt = prompt.getText();
        cfg.targetUrl = targetUrl.getText().trim();
        cfg.model = currentModel();
        cfg.baseUrl = baseUrl.getText().trim();
        cfg.apiKey = new String(apiKey.getPassword());
        cfg.temperature = parseDouble(temperature.getText(), 0.2);
        cfg.maxTokens = parseInt(maxTokens.getText(), 0);
        cfg.proxyHost = proxyHost.getText().trim();
        cfg.proxyPort = parseInt(proxyPort.getText(), 8080);
        cfg.maxSteps = parseInt(maxSteps.getText(), 60);
        cfg.samplesPerTemplate = parseInt(samplesPerShape.getText(), 2);
        cfg.chromeBinary = chromeBinary.getText().trim();
        cfg.headless = headless.isSelected();
        cfg.vision = vision.isSelected();
        cfg.blockDestructive = blockDestructive.isSelected();
        cfg.extraBlockKeywords = extraBlockKeywords.getText().trim();

        if (cfg.targetUrl.isBlank() || cfg.targetUrl.equals("https://")) {
            log("[ui] Enter a Target URL first.");
            return;
        }
        if (cfg.apiKey.isBlank()) {
            log("[ui] Enter an AI API key first.");
            return;
        }
        savePrefs();
        resolveLogFile();
        LlmClient llm = new LlmClient(cfg.baseUrl, cfg.apiKey, cfg.model,
                cfg.temperature, cfg.maxTokens, cfg.jsonMode, cfg.llmRetries);
        log("[ui] Starting. Chrome will route through Burp at " + cfg.proxyEndpoint()
                + ". Make sure a Burp proxy listener is running there.");
        new Thread(() -> agent.run(cfg, llm), "ai-mapper-agent").start();
    }

    private void onExportSitemap() {
        runExport("sitemap", outDir -> {
            List<HttpRequestResponse> recs = SiteMapExtractor.collect(
                    api, hostFilter.getText().trim(), inScopeOnly.isSelected());
            // Client-only routes the agent reached but the proxy never saw (hash / SPA
            // pushState pages), filtered by the same host/scope rules as the records.
            List<String> extra = filterClientUrls(agent.discoveredUrls());
            SitemapGenerator.write(recs, outDir, extra);
            log("[export] Wrote sitemap.xml and urls.json (" + recs.size() + " proxy records + "
                    + extra.size() + " client-only URLs) to " + outDir);
        });
    }

    /** Apply the Host filter / In-scope only settings to the agent's client-only URLs. */
    private List<String> filterClientUrls(java.util.Collection<String> urls) {
        String needle = hostFilter.getText().trim().toLowerCase();
        boolean scoped = inScopeOnly.isSelected();
        List<String> out = new java.util.ArrayList<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            if (!needle.isEmpty() && !u.toLowerCase().contains(needle)) {
                continue;
            }
            if (scoped) {
                try {
                    if (!api.scope().isInScope(u)) {
                        continue;
                    }
                } catch (RuntimeException ignored) {
                    // malformed URL -> skip the scope test, keep it
                }
            }
            out.add(u);
        }
        return out;
    }

    private void onExportApi() {
        runExport("openapi", outDir -> {
            List<HttpRequestResponse> recs = SiteMapExtractor.collect(
                    api, hostFilter.getText().trim(), inScopeOnly.isSelected());
            String title = hostFilter.getText().isBlank() ? "Discovered API" : hostFilter.getText().trim();
            OpenApiGenerator.write(recs, outDir, title);
            log("[export] Wrote openapi.json (from " + recs.size() + " records) to " + outDir);
        });
    }

    /** Write the current log pane contents to a file in the Output dir, on demand. */
    private void onExportLog() {
        final String content = logArea.getText();  // read on the EDT (button thread)
        runExport("log", outDir -> {
            java.nio.file.Files.createDirectories(outDir);
            Path f = outDir.resolve("ai-mapper-log.txt");
            java.nio.file.Files.writeString(f, content);
            log("[export] Wrote log (" + content.length() + " chars) to " + f);
        });
    }

    private interface ExportTask {
        void run(Path outDir) throws Exception;
    }

    private void runExport(String what, ExportTask task) {
        String out = outputDir.getText().trim();
        if (out.isBlank()) {
            log("[export] Set an Output dir first.");
            return;
        }
        savePrefs();
        Path outDir = Path.of(out);
        new Thread(() -> {
            try {
                task.run(outDir);
            } catch (Exception ex) {
                log("[export] ERROR building " + what + ": " + ex.getMessage());
            }
        }, "ai-mapper-export").start();
    }

    /**
     * Compare what we discovered against a ground-truth URL list (one URL per line;
     * blank lines and {@code #} comments ignored) and print a coverage score plus the
     * URLs that were missed. "Found" = GET URLs in Burp's site map (under the current
     * host/scope filter) plus the agent's client-only routes.
     */
    private void onScore() {
        String gt = groundTruth.getText().trim();
        if (gt.isBlank()) {
            log("[score] Set a Ground-truth URLs file first.");
            return;
        }
        savePrefs();
        new Thread(() -> {
            try {
                java.util.Set<String> truth = new java.util.TreeSet<>();
                for (String line : java.nio.file.Files.readAllLines(Path.of(gt))) {
                    String s = line.trim();
                    if (!s.isEmpty() && !s.startsWith("#")) {
                        truth.add(normalizeForScore(s));
                    }
                }
                if (truth.isEmpty()) {
                    log("[score] Ground-truth file had no URLs.");
                    return;
                }
                java.util.Set<String> found = new java.util.TreeSet<>();
                String needle = hostFilter.getText().trim().toLowerCase();
                for (HttpRequestResponse rr : api.siteMap().requestResponses()) {
                    var req = rr.request();
                    if (req == null || !"GET".equalsIgnoreCase(req.method())) {
                        continue;
                    }
                    String url = req.url();
                    if (!needle.isEmpty() && !url.toLowerCase().contains(needle)) {
                        continue;
                    }
                    if (inScopeOnly.isSelected()) {
                        try {
                            if (!api.scope().isInScope(url)) {
                                continue;
                            }
                        } catch (RuntimeException ignored) {
                            // keep on malformed URL
                        }
                    }
                    found.add(normalizeForScore(url));
                }
                for (String u : agent.discoveredUrls()) {
                    found.add(normalizeForScore(u));
                }
                java.util.Set<String> missed = new java.util.TreeSet<>(truth);
                missed.removeAll(found);
                int hit = truth.size() - missed.size();
                log(String.format("[score] Coverage %d/%d = %.1f%% of ground-truth GET URLs "
                        + "(found %d URLs total).", hit, truth.size(),
                        100.0 * hit / truth.size(), found.size()));
                int shown = 0;
                for (String m : missed) {
                    if (shown++ >= 40) {
                        log("[score] ... and " + (missed.size() - 40) + " more missed.");
                        break;
                    }
                    log("[score]   MISSED " + m);
                }
                if (missed.isEmpty()) {
                    log("[score] All ground-truth URLs were found.");
                }
            } catch (Exception ex) {
                log("[score] ERROR: " + ex.getMessage());
            }
        }, "ai-mapper-score").start();
    }

    private static String normalizeForScore(String u) {
        if (u == null) {
            return "";
        }
        String s = u.trim();
        // Drop a trailing slash except on the bare origin root (http://host/).
        if (s.endsWith("/") && !s.matches("(?i)https?://[^/]+/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // --- settings persistence (via Burp's extension preferences) ---------------

    private void loadPrefs() {
        try {
            var p = api.persistence().preferences();
            setIf(p.getString("targetUrl"), targetUrl);
            setIf(p.getString("baseUrl"), baseUrl);
            String m = p.getString("model");
            if (m != null && !m.isBlank()) {
                model.setSelectedItem(m);
            }
            setIf(p.getString("proxyHost"), proxyHost);
            setIf(p.getString("proxyPort"), proxyPort);
            setIf(p.getString("maxSteps"), maxSteps);
            setIf(p.getString("samplesPerShape"), samplesPerShape);
            setIf(p.getString("chromeBinary"), chromeBinary);
            setIf(p.getString("temperature"), temperature);
            setIf(p.getString("maxTokens"), maxTokens);
            setIf(p.getString("extraBlockKeywords"), extraBlockKeywords);
            setIf(p.getString("outputDir"), outputDir);
            setIf(p.getString("hostFilter"), hostFilter);
            setIf(p.getString("groundTruth"), groundTruth);
            setIf(p.getString("logFile"), logFile);
            Boolean h = p.getBoolean("headless");
            if (h != null) {
                headless.setSelected(h);
            }
            Boolean vis = p.getBoolean("vision");
            if (vis != null) {
                vision.setSelected(vis);
            }
            Boolean b = p.getBoolean("blockDestructive");
            if (b != null) {
                blockDestructive.setSelected(b);
            }
            Boolean s = p.getBoolean("inScopeOnly");
            if (s != null) {
                inScopeOnly.setSelected(s);
            }
        } catch (RuntimeException ignored) {
            // persistence is best-effort; ignore if unavailable
        }
    }

    private void savePrefs() {
        try {
            var p = api.persistence().preferences();
            // Secrets (API key, and the prompt which may hold passwords) are NOT saved.
            p.setString("targetUrl", targetUrl.getText());
            p.setString("baseUrl", baseUrl.getText());
            p.setString("model", currentModel());
            p.setString("proxyHost", proxyHost.getText());
            p.setString("proxyPort", proxyPort.getText());
            p.setString("maxSteps", maxSteps.getText());
            p.setString("samplesPerShape", samplesPerShape.getText());
            p.setString("chromeBinary", chromeBinary.getText());
            p.setString("temperature", temperature.getText());
            p.setString("maxTokens", maxTokens.getText());
            p.setString("extraBlockKeywords", extraBlockKeywords.getText());
            p.setString("outputDir", outputDir.getText());
            p.setString("hostFilter", hostFilter.getText());
            p.setString("groundTruth", groundTruth.getText());
            p.setString("logFile", logFile.getText());
            p.setBoolean("headless", headless.isSelected());
            p.setBoolean("vision", vision.isSelected());
            p.setBoolean("blockDestructive", blockDestructive.isSelected());
            p.setBoolean("inScopeOnly", inScopeOnly.isSelected());
        } catch (RuntimeException ignored) {
            // persistence is best-effort; ignore if unavailable
        }
    }

    private static void setIf(String v, JTextField field) {
        if (v != null && !v.isEmpty()) {
            field.setText(v);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Live proxy coverage for the agent: distinct "METHOD shape" signatures Burp has
     * captured for the given host so far. Lets the agent steer toward endpoints it
     * has not exercised yet. Best-effort; returns an empty set on any error.
     */
    private java.util.Set<String> capturedEndpoints(String host) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        try {
            String h = host == null ? "" : host.trim();
            for (HttpRequestResponse rr : api.siteMap().requestResponses()) {
                var req = rr.request();
                if (req == null) {
                    continue;
                }
                String reqHost = req.httpService() != null ? req.httpService().host() : "";
                if (!h.isEmpty() && (reqHost == null || !reqHost.equalsIgnoreCase(h))) {
                    continue;
                }
                String method = req.method() == null ? "GET" : req.method().toUpperCase();
                out.add(method + " " + PathTemplater.signature(req.url()));
            }
        } catch (RuntimeException ignored) {
            // best-effort; never break the agent loop
        }
        return out;
    }

    private void log(String msg) {
        String line = "[" + LocalTime.now().withNano(0) + "] " + msg + "\n";
        api.logging().logToOutput(msg);
        appendLog(line);
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /** Append a line to the configured log file (if any). Best-effort, thread-safe. */
    private void appendLog(String line) {
        java.nio.file.Path p = logFilePath;
        if (p == null) {
            return;
        }
        synchronized (logLock) {
            try {
                java.nio.file.Files.writeString(p, line,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // never let logging break the run
            }
        }
    }

    /**
     * Decide where to write the run log (called at Start). If the Log file field is
     * blank, default to {@code <output dir>/ai-mapper.log} when an output dir is set;
     * otherwise file logging stays off. Writes a session header on open.
     */
    private void resolveLogFile() {
        String lf = logFile.getText().trim();
        if (!lf.isEmpty()) {
            logFilePath = Path.of(lf);
        } else {
            String out = outputDir.getText().trim();
            logFilePath = out.isEmpty() ? null : Path.of(out, "ai-mapper.log");
        }
        if (logFilePath == null) {
            return;
        }
        try {
            if (logFilePath.getParent() != null) {
                java.nio.file.Files.createDirectories(logFilePath.getParent());
            }
            String header = "\n=== AI Mapper session " + java.time.LocalDateTime.now().withNano(0)
                    + " | target=" + targetUrl.getText().trim()
                    + " | model=" + currentModel()
                    + " | maxSteps=" + maxSteps.getText().trim()
                    + " | samples=" + samplesPerShape.getText().trim() + " ===\n";
            java.nio.file.Files.writeString(logFilePath, header,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            log("[ui] Logging this run to " + logFilePath);
        } catch (Exception e) {
            logFilePath = null;
            log("[ui] Could not open log file: " + e.getMessage() + " (file logging off).");
        }
    }

    /**
     * A plain JPanel view isn't Scrollable, so the mouse wheel moves only 1px per
     * notch - that's the "slow" scrolling. Bump the unit/block increments so the
     * wheel and scrollbar move a sensible amount per step.
     */
    private static JScrollPane smooth(JScrollPane sp) {
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.getVerticalScrollBar().setBlockIncrement(96);
        sp.getHorizontalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JComponent section(String title) {
        JLabel l = new JLabel(title);
        l.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
        l.setFont(l.getFont().deriveFont(l.getFont().getStyle() | Font.BOLD));
        JPanel p = new JPanel(new BorderLayout());
        p.add(l, BorderLayout.WEST);
        p.setAlignmentX(0f);
        return p;
    }

    /** The currently chosen model id (combo is editable, so read its editor). */
    private String currentModel() {
        Object sel = model.isEditable() && model.getEditor() != null
                ? model.getEditor().getItem() : model.getSelectedItem();
        return sel == null ? "" : sel.toString().trim();
    }

    private JComponent modelRow() {
        JButton fetch = new JButton("Fetch");
        fetch.setToolTipText("Load available models from Base URL /models");
        fetch.addActionListener(e -> onFetchModels());
        JPanel east = new JPanel(new BorderLayout(6, 0));
        east.add(model, BorderLayout.CENTER);
        east.add(fetch, BorderLayout.EAST);

        JPanel p = new JPanel(new GridLayout(1, 2, 8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        p.add(new JLabel("Model"));
        p.add(east);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.setAlignmentX(0f);
        return p;
    }

    private void onFetchModels() {
        String base = baseUrl.getText().trim();
        String key = new String(apiKey.getPassword());
        log("[ui] Fetching models from " + base + "/models ...");
        new Thread(() -> {
            try {
                List<String> ids = LlmClient.listModels(base, key);
                SwingUtilities.invokeLater(() -> {
                    String keep = currentModel();
                    model.removeAllItems();
                    for (String id : ids) {
                        model.addItem(id);
                    }
                    if (!keep.isBlank()) {
                        model.setSelectedItem(keep);
                    }
                    log("[ui] Loaded " + ids.size() + " model(s).");
                });
            } catch (Exception ex) {
                log("[ui] Fetch models failed: " + ex.getMessage()
                        + "  (check Base URL / API key; some gateways need a key).");
            }
        }, "ai-mapper-models").start();
    }

    private JComponent outputRow() {
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Select output folder");
            String cur = outputDir.getText().trim();
            if (!cur.isBlank()) {
                File f = new File(cur);
                fc.setCurrentDirectory(f.exists() ? f : f.getParentFile());
            }
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                outputDir.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel east = new JPanel(new BorderLayout(6, 0));
        east.add(outputDir, BorderLayout.CENTER);
        east.add(browse, BorderLayout.EAST);

        JPanel p = new JPanel(new GridLayout(1, 2, 8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        p.add(new JLabel("Output dir"));
        p.add(east);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.setAlignmentX(0f);
        return p;
    }

    private JComponent row(String label, JComponent field) {
        JPanel p = new JPanel(new GridLayout(1, 2, 8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        p.add(new JLabel(label));
        p.add(field);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.setAlignmentX(0f);
        return p;
    }
}
