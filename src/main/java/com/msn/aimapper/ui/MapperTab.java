package com.msn.aimapper.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.msn.aimapper.agent.AgentConfig;
import com.msn.aimapper.agent.BrowserAgent;
import com.msn.aimapper.agent.LlmClient;
import com.msn.aimapper.export.OpenApiGenerator;
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

    // Browser / proxy
    private final JTextField proxyHost = new JTextField("127.0.0.1");
    private final JTextField proxyPort = new JTextField("8080");
    private final JTextField maxSteps = new JTextField("60");
    private final JTextField chromeBinary = new JTextField();
    private final JCheckBox headless = new JCheckBox("Headless browser");

    // Safety
    private final JCheckBox blockDestructive =
            new JCheckBox("Block destructive actions (delete / logout / reset / pay)", true);
    private final JTextField extraBlockKeywords = new JTextField();

    // Export
    private final JTextField outputDir = new JTextField();
    private final JTextField hostFilter = new JTextField();
    private final JCheckBox inScopeOnly = new JCheckBox("In-scope only", true);

    private final JTextArea logArea = new JTextArea();
    private final JPanel root = new JPanel(new BorderLayout());

    public MapperTab(MontoyaApi api) {
        this.api = api;
        this.agent = new BrowserAgent(this::log);
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

        form.add(section("3. Browser & Burp proxy"));
        form.add(row("Proxy host", proxyHost));
        form.add(row("Proxy port", proxyPort));
        form.add(row("Max steps", maxSteps));
        form.add(row("Chrome binary (optional)", chromeBinary));
        JPanel cb = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        cb.add(headless);
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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton start = new JButton("Start AI browsing");
        JButton stop = new JButton("Stop");
        JButton exSitemap = new JButton("Export sitemap");
        JButton exApi = new JButton("Export OpenAPI");
        JButton clear = new JButton("Clear log");
        buttons.add(start);
        buttons.add(stop);
        buttons.add(exSitemap);
        buttons.add(exApi);
        buttons.add(clear);
        form.add(Box.createVerticalStrut(8));
        form.add(buttons);

        start.addActionListener(e -> onStart());
        stop.addActionListener(e -> agent.stop());
        exSitemap.addActionListener(e -> onExportSitemap());
        exApi.addActionListener(e -> onExportApi());
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
    }

    private void onStart() {
        AgentConfig cfg = new AgentConfig();
        cfg.prompt = prompt.getText();
        cfg.targetUrl = targetUrl.getText().trim();
        cfg.model = currentModel();
        cfg.baseUrl = baseUrl.getText().trim();
        cfg.apiKey = new String(apiKey.getPassword());
        cfg.proxyHost = proxyHost.getText().trim();
        cfg.proxyPort = parseInt(proxyPort.getText(), 8080);
        cfg.maxSteps = parseInt(maxSteps.getText(), 60);
        cfg.chromeBinary = chromeBinary.getText().trim();
        cfg.headless = headless.isSelected();
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
        LlmClient llm = new LlmClient(cfg.baseUrl, cfg.apiKey, cfg.model);
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

    private interface ExportTask {
        void run(Path outDir) throws Exception;
    }

    private void runExport(String what, ExportTask task) {
        String out = outputDir.getText().trim();
        if (out.isBlank()) {
            log("[export] Set an Output dir first.");
            return;
        }
        Path outDir = Path.of(out);
        new Thread(() -> {
            try {
                task.run(outDir);
            } catch (Exception ex) {
                log("[export] ERROR building " + what + ": " + ex.getMessage());
            }
        }, "ai-mapper-export").start();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void log(String msg) {
        String line = "[" + LocalTime.now().withNano(0) + "] " + msg + "\n";
        api.logging().logToOutput(msg);
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
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
