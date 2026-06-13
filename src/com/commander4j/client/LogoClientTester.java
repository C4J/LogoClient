package com.commander4j.client;

import com.commander4j.client.common.LogoClientSftp;
import com.commander4j.client.common.PalletLogEntry;
import com.commander4j.client.pl3.LogoClientPL3;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;

/**
 * Swing JFrame test harness for the Logopak client library.
 *
 * <p>Tests two transport types from a single window:
 * <ul>
 *   <li><b>Labeller TCP</b> – {@link LogoClientPL3}: IntelHEX file transfer,
 *       pallet log polling, LEAP FFP / LAMA / LSP commands.</li>
 *   <li><b>SFTP</b> – {@link LogoClientSftp}: directory listing, upload, download.</li>
 * </ul>
 *
 * <p>SFTP requires JSch ({@code com.github.mwiede:jsch:0.2.20} or
 * {@code com.jcraft:jsch:0.1.55}) on the class path.
 */
public class LogoClientTester extends JFrame {

    // =========================================================================
    // Connection state
    // =========================================================================

    private static final long serialVersionUID = 1L;
    private LogoClientPL3  labeller;
    private LogoClientSftp sftp;

    // =========================================================================
    // Connection panel widgets
    // =========================================================================

    private JComboBox<String> typeCombo;
    private JTextField        hostField;
    private JComboBox<String> portCombo;
    private JTextField        userField;
    private JPasswordField    passField;
    private JButton           connectBtn;
    private JButton           disconnectBtn;
    private JLabel            statusLabel;

    // =========================================================================
    // Tabs
    // =========================================================================

    private JTabbedPane tabbedPane;

    // ── Upload ───────────────────────────────────────────────────────────────
    private JTextField   uploadLocalPath;
    private JTextField   uploadRemotePath;
    private JButton      uploadBtn;
    private JProgressBar uploadProgress;

    // ── Download ─────────────────────────────────────────────────────────────
    private JTextField   downloadRemotePath;
    private JTextField   downloadLocalPath;
    private JButton      downloadBtn;
    private JProgressBar downloadProgress;

    // ── Directory ────────────────────────────────────────────────────────────
    private JComboBox<String> dirFilterCombo;
    private JLabel            dirCurrentLabel;
    private JTable            dirTable;
    private DefaultTableModel dirModel;
    private JButton           dirListBtn;
    private JButton           dirDownloadBtn;

    // ── Pallet Log ────────────────────────────────────────────────────────────
    private JButton           logFetchBtn;
    private JLabel            logInfoLabel;
    private JTable            logTable;
    private DefaultTableModel logModel;
    private JTextArea         logRawArea;

    // ── LEAP FFP ──────────────────────────────────────────────────────────────
    private JTextField leapLayoutId;
    private JTextField leapFieldNum;
    private JTextField leapFieldValue;
    private JTextField leapRawCmd;
    private JTextArea  leapResultArea;

    // ── Activity log ─────────────────────────────────────────────────────────
    private JTextArea activityLog;

    // ── Wire trace ───────────────────────────────────────────────────────────
    private JTextPane              wirePane;
    private javax.swing.text.StyledDocument wireDoc;
    private javax.swing.text.SimpleAttributeSet attrTx;
    private javax.swing.text.SimpleAttributeSet attrRxOk;
    private javax.swing.text.SimpleAttributeSet attrRxNak;
    private javax.swing.text.SimpleAttributeSet attrWarn;
    private javax.swing.text.SimpleAttributeSet attrInfo;
    private JToggleButton          wireTraceToggle;

    // =========================================================================
    // Colours
    // =========================================================================

    private static final Color COL_CONNECTED    = new Color(0x2E7D32);
    private static final Color COL_DISCONNECTED = new Color(0xC62828);
    private static final Color COL_CONNECTING   = new Color(0xE65100);
    private static final Color COL_HDR_BG       = new Color(0xEEF2F7);

    // =========================================================================
    // Cross-platform font helpers
    // =========================================================================

    /**
     * Returns the best available monospaced font for the current platform at the
     * given point size.  Preference order:
     * <ul>
     *   <li>Windows  – Consolas (ships with Vista+)</li>
     *   <li>macOS    – Menlo (ships with Snow Leopard+)</li>
     *   <li>Linux    – DejaVu Sans Mono (most distros)</li>
     *   <li>Fallback – Courier New, then the logical Font.MONOSPACED name</li>
     * </ul>
     * Using a named font avoids the rendering-size discrepancy between the
     * logical {@code Font.MONOSPACED} alias on Windows (Courier New, appears
     * small) and macOS (Menlo, appears larger).
     */
    private static Font monoFont(int size) {
        java.awt.GraphicsEnvironment ge =
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> available = new java.util.HashSet<>(
                java.util.Arrays.asList(ge.getAvailableFontFamilyNames()));
        for (String name : new String[]{"Consolas", "Menlo", "DejaVu Sans Mono", "Courier New"}) {
            if (available.contains(name)) return new Font(name, Font.PLAIN, size);
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /**
     * Scales every font registered in the UIManager defaults by {@code factor}.
     * Call once before any Swing component is created to ensure all controls
     * (buttons, labels, tables, menus …) use the scaled size.
     */
    private static void scaleUIFonts(float factor) {
        javax.swing.UIDefaults defs = UIManager.getDefaults();
        for (Object key : java.util.Collections.list(defs.keys())) {
            Object val = defs.get(key);
            if (val instanceof Font) {
                Font f = (Font) val;
                defs.put(key, f.deriveFont(f.getSize2D() * factor));
            }
        }
    }

    /** Point size used for all mono / code text areas across the UI. */
    private static final int MONO_SIZE;

    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        // Windows Consolas 14pt ≈ macOS Menlo 13pt in perceived character size
        MONO_SIZE = os.contains("win") ? 14 : 13;
    }

    private static final String TYPE_TCP  = "Labeller TCP";
    private static final String TYPE_SFTP = "SFTP";

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            // Windows renders UI fonts noticeably smaller than macOS at the same
            // nominal point size.  Scale everything up by 25% on Windows so that
            // labels, buttons, and table headers match the macOS experience.
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                scaleUIFonts(1.25f);
            }
            new LogoClientTester().setVisible(true);
        });
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    public LogoClientTester() {
        super("Logo PowerLeap III — Test Harness");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(950, 680));
        setPreferredSize(new Dimension(1150, 800));
        setLayout(new BorderLayout());

        add(buildConnectionPanel(), BorderLayout.NORTH);
        add(buildTabbedPane(),      BorderLayout.CENTER);
        add(buildActivityPanel(),   BorderLayout.SOUTH);

        // Initialise port/field visibility for the default type (TCP)
        onTypeChanged();
        updateUiState(false);

        pack();
        setLocationRelativeTo(null);
    }

    // =========================================================================
    // Connection panel
    // =========================================================================

    private JPanel buildConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(COL_HDR_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, new Color(0x90A4AE)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 5, 3, 5);
        c.anchor = GridBagConstraints.WEST;
        c.fill   = GridBagConstraints.HORIZONTAL;

        // ── Row 0: Type, Host, Port, User, Password ───────────────────────
        int col = 0;

        c.gridx = col++; c.gridy = 0; c.weightx = 0;
        panel.add(label("Connection Type:"), c);

        typeCombo = new JComboBox<>(new String[]{ TYPE_TCP, TYPE_SFTP });
        typeCombo.setPreferredSize(new Dimension(140, 26));
        c.gridx = col++; c.weightx = 0;
        panel.add(typeCombo, c);

        c.gridx = col++; c.weightx = 0;
        panel.add(label("Host / IP:"), c);

        hostField = new JTextField("192.168.1.100", 14);
        c.gridx = col++; c.weightx = 1;
        panel.add(hostField, c);

        c.gridx = col++; c.weightx = 0;
        panel.add(label("Port:"), c);

        portCombo = new JComboBox<>();
        portCombo.setEditable(true);
        portCombo.setPreferredSize(new Dimension(130, 26));
        c.gridx = col++; c.weightx = 0;
        panel.add(portCombo, c);

        c.gridx = col++; c.weightx = 0;
        panel.add(label("Username:"), c);

        userField = new JTextField("admin", 8);
        c.gridx = col++; c.weightx = 0;
        panel.add(userField, c);

        c.gridx = col++; c.weightx = 0;
        panel.add(label("Password:"), c);

        passField = new JPasswordField("3142", 8);
        c.gridx = col++; c.weightx = 0;
        panel.add(passField, c);

        // ── Row 1: Status + buttons ────────────────────────────────────────
        col = 0;
        c.gridy = 1;

        statusLabel = new JLabel("● Disconnected");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        statusLabel.setForeground(COL_DISCONNECTED);
        c.gridx = col; c.gridwidth = 2; c.weightx = 0;
        panel.add(statusLabel, c);
        c.gridwidth = 1;

        col = 2;

        connectBtn = new JButton("Connect");
        styleButton(connectBtn, new Color(0x1565C0), Color.WHITE);
        c.gridx = col++; c.weightx = 0;
        panel.add(connectBtn, c);

        disconnectBtn = new JButton("Disconnect");
        c.gridx = col++;
        panel.add(disconnectBtn, c);

        // Spacer
        c.gridx = col; c.weightx = 1;
        panel.add(new JLabel(), c);

        // Wire events
        typeCombo.addActionListener(_ -> onTypeChanged());
        connectBtn.addActionListener(_ -> onConnect());
        disconnectBtn.addActionListener(_ -> onDisconnect());

        return panel;
    }

    private void onTypeChanged() {
        String type = (String) typeCombo.getSelectedItem();
        portCombo.removeAllItems();
        boolean needsCreds;
        if (TYPE_TCP.equals(type)) {
            portCombo.addItem("8000  (XA — primary)");
            portCombo.addItem("8100  (XB)");
            portCombo.addItem("8200  (XC)");
            portCombo.addItem("8300  (XD)");
            needsCreds = false;
        } else {
            portCombo.addItem("22");
            needsCreds = true;
        }
        userField.setEnabled(needsCreds);
        passField.setEnabled(needsCreds);
    }

    // =========================================================================
    // Tabbed pane
    // =========================================================================

    private JTabbedPane buildTabbedPane() {
        tabbedPane = new JTabbedPane();
        tabbedPane.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        tabbedPane.addTab("Upload",      icon("up"),  buildUploadTab());
        tabbedPane.addTab("Download",    icon("dn"),  buildDownloadTab());
        tabbedPane.addTab("Directory",   icon("dir"), buildDirectoryTab());
        tabbedPane.addTab("Pallet Log",  icon("log"), buildPalletLogTab());
        tabbedPane.addTab("LEAP / LAMA", icon("cmd"), buildLeapTab());
        return tabbedPane;
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    private JPanel buildUploadTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(titledBorder("Upload File to Labeller"));
        GridBagConstraints c = gbc();

        c.gridy = 0;
        c.gridx = 0; c.weightx = 0; p.add(label("Local File:"), c);
        uploadLocalPath = new JTextField(35);
        c.gridx = 1; c.weightx = 1; p.add(uploadLocalPath, c);
        JButton browse = new JButton("Browse…");
        c.gridx = 2; c.weightx = 0; p.add(browse, c);
        browse.addActionListener(_ -> browseOpen(uploadLocalPath));

        c.gridy = 1;
        c.gridx = 0; c.weightx = 0; p.add(label("Remote Path / Filename:"), c);
        uploadRemotePath = new JTextField("/c0/", 35);
        c.gridx = 1; c.weightx = 1; p.add(uploadRemotePath, c);
        JLabel hint = new JLabel("<html><i>e.g. /c0/label1.llf &nbsp;(TCP: filename only)</i></html>");
        hint.setForeground(new Color(0x607080));
        c.gridx = 2; c.weightx = 0; p.add(hint, c);

        c.gridy = 2; c.gridx = 0; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL;
        uploadProgress = progressBar();
        p.add(uploadProgress, c);
        c.gridwidth = 1;

        uploadBtn = new JButton("  Upload  ");
        styleButton(uploadBtn, new Color(0x1B5E20), Color.WHITE);
        c.gridy = 3; c.gridx = 1; c.anchor = GridBagConstraints.CENTER; c.fill = GridBagConstraints.NONE;
        p.add(uploadBtn, c);
        uploadBtn.addActionListener(_ -> doUpload());

        // Note panel
        JTextArea note = noteArea(
            "TCP mode uses *HEXFILE (Intel HEX).  Provide a filename only (e.g. label1.llf); the\n" +
            "labeller stores it in its default path.  SFTP accepts full remote paths.");
        c.gridy = 4; c.gridx = 0; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; c.weighty = 0;
        p.add(note, c);

        filler(p, c, 5);
        return p;
    }

    // ── Download ─────────────────────────────────────────────────────────────

    private JPanel buildDownloadTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(titledBorder("Download File from Labeller"));
        GridBagConstraints c = gbc();

        c.gridy = 0;
        c.gridx = 0; c.weightx = 0; p.add(label("Remote Path / Filename:"), c);
        downloadRemotePath = new JTextField("/c0/label1.llf", 35);
        c.gridx = 1; c.weightx = 1; p.add(downloadRemotePath, c);
        JLabel hint = new JLabel("<html><i>TCP: filename only (e.g. label1.llf)</i></html>");
        hint.setForeground(new Color(0x607080));
        c.gridx = 2; c.weightx = 0; p.add(hint, c);

        c.gridy = 1;
        c.gridx = 0; c.weightx = 0; p.add(label("Save To:"), c);
        downloadLocalPath = new JTextField(35);
        c.gridx = 1; c.weightx = 1; p.add(downloadLocalPath, c);
        JButton browse = new JButton("Browse…");
        c.gridx = 2; c.weightx = 0; p.add(browse, c);
        browse.addActionListener(_ -> browseSave(downloadLocalPath, downloadRemotePath.getText()));

        c.gridy = 2; c.gridx = 0; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL;
        downloadProgress = progressBar();
        p.add(downloadProgress, c);
        c.gridwidth = 1;

        downloadBtn = new JButton("  Download  ");
        styleButton(downloadBtn, new Color(0x1565C0), Color.WHITE);
        c.gridy = 3; c.gridx = 1; c.anchor = GridBagConstraints.CENTER; c.fill = GridBagConstraints.NONE;
        p.add(downloadBtn, c);
        downloadBtn.addActionListener(_ -> doDownload());

        JTextArea note = noteArea(
            "TCP mode uses *TYPEHEX (Intel HEX) and decodes the response automatically.\n" +
            "SFTP transfers in binary mode.");
        c.gridy = 4; c.gridx = 0; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; c.weighty = 0;
        p.add(note, c);

        filler(p, c, 5);
        return p;
    }

    // ── Directory ─────────────────────────────────────────────────────────────

    /** Wildcard entries shown in the filter combo for TCP/labeller connections. */
    private static final String[][] DIR_FILTERS = {
        { "All files (*.*)",        "*.*"   },
        { "Label layouts (*.llf)",  "*.llf" },
        { "Logo / PCX (*.pcx)",     "*.pcx" },
        { "Data files (*.ldf)",     "*.ldf" },
        { "Font files (*.fnt)",     "*.fnt" },
        { "Text files (*.txt)",     "*.txt" },
    };

    private JPanel buildDirectoryTab() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(titledBorder("Remote Directory Browser"));

        // ── Toolbar ────────────────────────────────────────────────────────
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.setBackground(new Color(0xF8F8F8));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDDDDDD)));

        dirCurrentLabel = new JLabel("Listing:  (not connected)");
        dirCurrentLabel.setFont(dirCurrentLabel.getFont().deriveFont(Font.BOLD));
        toolbar.add(dirCurrentLabel);
        toolbar.add(Box.createHorizontalStrut(12));

        // Filter combo — for TCP shows wildcard presets; for SFTP the path is typed
        toolbar.add(label("Filter / Path:"));
        String[] filterLabels = new String[DIR_FILTERS.length];
        for (int i = 0; i < DIR_FILTERS.length; i++) filterLabels[i] = DIR_FILTERS[i][0];
        dirFilterCombo = new JComboBox<>(filterLabels);
        dirFilterCombo.setPreferredSize(new Dimension(200, 26));
        dirFilterCombo.setEditable(true);   // allow SFTP paths to be typed directly
        toolbar.add(dirFilterCombo);

        dirListBtn     = new JButton("⟳ Refresh");
        dirDownloadBtn = new JButton("⬇ Download Selected");
        toolbar.add(dirListBtn);
        toolbar.add(dirDownloadBtn);

        // Auto-list when the combo selection changes (only if already connected)
        dirFilterCombo.addActionListener(_ -> {
            if (labeller != null || sftp != null) {
                doListDirectory(selectedFilter());
            }
        });
        dirListBtn.addActionListener(_ -> doListDirectory(selectedFilter()));
        dirDownloadBtn.addActionListener(_ -> doDownloadSelected());

        p.add(toolbar, BorderLayout.NORTH);

        // ── Table ──────────────────────────────────────────────────────────
        dirModel = new DefaultTableModel(new String[]{"Name", "Size (bytes)", "Modified", "Type"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        dirTable = new JTable(dirModel);
        dirTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dirTable.setFillsViewportHeight(true);
        dirTable.setRowHeight(24);
        dirTable.getTableHeader().setReorderingAllowed(false);
        dirTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        dirTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        dirTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        dirTable.getColumnModel().getColumn(3).setPreferredWidth(60);

        // Double-click a directory row to navigate (SFTP only)
        dirTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = dirTable.getSelectedRow();
                    if (row < 0) return;
                    String type = (String) dirModel.getValueAt(row, 3);
                    String name = (String) dirModel.getValueAt(row, 0);
                    if ("DIR".equals(type) && sftp != null) {
                        String cur = currentPath();
                        String next;
                        if ("..".equals(name)) {
                            next = cur.replaceAll("[^/]+/?$", "");
                            if (next.isEmpty()) next = "/";
                        } else {
                            next = (cur.endsWith("/") ? cur : cur + "/") + name + "/";
                        }
                        dirFilterCombo.setSelectedItem(next);
                        doListDirectory(next);
                    }
                }
            }
        });

        p.add(new JScrollPane(dirTable), BorderLayout.CENTER);
        return p;
    }

    /**
     * Returns the wildcard (for labeller) or path (for SFTP) from the
     * current combo selection.  For labeller connections the combo items map
     * display labels → wildcard patterns via {@link #DIR_FILTERS}.
     */
    private String selectedFilter() {
        Object sel = dirFilterCombo.getSelectedItem();
        String text = sel == null ? "*.*" : sel.toString().trim();
        if (labeller != null) {
            // Map display label → wildcard pattern
            for (String[] pair : DIR_FILTERS) {
                if (pair[0].equals(text)) return pair[1];
            }
            // User may have typed a wildcard directly (e.g. "*.llf")
            return text.isEmpty() ? "*.*" : text;
        }
        // SFTP — treat the whole string as a remote path
        return text.isEmpty() ? "/" : text;
    }

    // ── Pallet Log ────────────────────────────────────────────────────────────

    private JPanel buildPalletLogTab() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(titledBorder("Pallet Log — Field 19500 / SSCC-18 Tracking (TCP only)"));

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        toolbar.setBackground(new Color(0xF8F8F8));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDDDDDD)));

        logFetchBtn = new JButton("  Fetch Pallet Log  (*LOGINFO → *TRXLOG → *DELLOG)  ");
        styleButton(logFetchBtn, new Color(0x4A148C), Color.WHITE);
        toolbar.add(logFetchBtn);

        logInfoLabel = new JLabel("No log fetched yet.");
        logInfoLabel.setFont(logInfoLabel.getFont().deriveFont(Font.ITALIC));
        toolbar.add(logInfoLabel);
        p.add(toolbar, BorderLayout.NORTH);

        // Split: parsed entries (top) / raw text (bottom)
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.65);

        logModel = new DefaultTableModel(new String[]{"#", "SSCC-18", "Seq", "All Fields", "Raw Data"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        logTable = new JTable(logModel);
        logTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        logTable.setFillsViewportHeight(true);
        logTable.setRowHeight(22);
        logTable.getColumnModel().getColumn(0).setPreferredWidth(35);
        logTable.getColumnModel().getColumn(1).setPreferredWidth(165);
        logTable.getColumnModel().getColumn(2).setPreferredWidth(45);
        logTable.getColumnModel().getColumn(3).setPreferredWidth(250);
        logTable.getColumnModel().getColumn(4).setPreferredWidth(380);

        // Show field breakdown when a row is selected
        logTable.getSelectionModel().addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting()) {
                int row = logTable.getSelectedRow();
                if (row >= 0) {
                    String raw = (String) logModel.getValueAt(row, 4);
                    String[] fields = raw.split(";", -1);
                    StringBuilder sb = new StringBuilder("Entry ").append(row + 1).append(" — field breakdown:\n\n");
                    for (int i = 0; i < fields.length; i++) {
                        sb.append(String.format("  field[%2d] = %s%n", i, fields[i]));
                    }
                    logRawArea.setText(sb.toString());
                    logRawArea.setCaretPosition(0);
                }
            }
        });

        split.setTopComponent(new JScrollPane(logTable));

        logRawArea = new JTextArea();
        logRawArea.setEditable(false);
        logRawArea.setFont(monoFont(MONO_SIZE));
        JScrollPane rawScroll = new JScrollPane(logRawArea);
        rawScroll.setBorder(BorderFactory.createTitledBorder("Selected Entry — Field Breakdown"));
        split.setBottomComponent(rawScroll);

        p.add(split, BorderLayout.CENTER);

        logFetchBtn.addActionListener(_ -> doFetchPalletLog());
        return p;
    }

    // ── LEAP / LAMA ────────────────────────────────────────────────────────────

    private JPanel buildLeapTab() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(titledBorder("LEAP FFP / LAMA Commands (TCP Connection Only)"));

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();

        // Section: Layout
        sectionHeader(grid, c, "Layout Management", 0);

        c.gridy = 1; c.gridx = 0; c.weightx = 0; p(grid, c, label("Layout ID:"));
        leapLayoutId = new JTextField("label1", 14);
        c.gridx = 1; c.weightx = 1; p(grid, c, leapLayoutId);

        JButton alBtn   = new JButton("Activate Layout (AL)");
        JButton makeBtn = new JButton("Make Layout (MAKE)");
        JButton ldBtn   = new JButton("LAMA Load (*LOAD)");
        c.gridx = 2; c.gridy = 1; c.weightx = 0; p(grid, c, alBtn);
        c.gridx = 3; p(grid, c, makeBtn);
        c.gridx = 4; p(grid, c, ldBtn);

        alBtn.addActionListener(_ -> doLeapRaw("AL,"   + leapLayoutId.getText().trim()));
        makeBtn.addActionListener(_ -> doLeapRaw("MAKE," + leapLayoutId.getText().trim()));
        ldBtn.addActionListener(_ -> doLeapRaw("*LOAD," + leapLayoutId.getText().trim()));

        // Section: Field Data
        sectionHeader(grid, c, "Field Data", 2);

        c.gridy = 3; c.gridx = 0; c.weightx = 0; p(grid, c, label("Field Number:"));
        leapFieldNum = new JTextField("1", 6);
        c.gridx = 1; c.weightx = 0; p(grid, c, leapFieldNum);

        c.gridy = 4; c.gridx = 0; p(grid, c, label("Field Value:"));
        leapFieldValue = new JTextField("TEST-001", 20);
        c.gridx = 1; c.weightx = 1; p(grid, c, leapFieldValue);

        JButton fdBtn  = new JButton("Send Field Data (FD)");
        JButton clayBtn = new JButton("Clear All Fields (CLAY)");
        c.gridx = 2; c.gridy = 3; c.weightx = 0; p(grid, c, fdBtn);
        c.gridx = 3; p(grid, c, clayBtn);
        fdBtn.addActionListener(_ -> doLeapRaw("FD," + leapFieldNum.getText().trim() + "," + leapFieldValue.getText().trim()));
        clayBtn.addActionListener(_ -> doLeapRaw("CLAY," + leapLayoutId.getText().trim()));

        // Section: Print
        sectionHeader(grid, c, "Print Control", 5);

        JButton printBtn   = new JButton("Print Label  (P)");
        JButton printNbBtn = new JButton("Print Non-Blocking  (P,N)");
        styleButton(printBtn, new Color(0xBF360C), Color.WHITE);
        c.gridy = 6; c.gridx = 2; p(grid, c, printBtn);
        c.gridx = 3; p(grid, c, printNbBtn);
        printBtn.addActionListener(_ -> doLeapRaw("P"));
        printNbBtn.addActionListener(_ -> doLeapRaw("P,N"));

        // Section: Query Commands
        sectionHeader(grid, c, "Query Commands", 7);

        JButton rdBtn      = new JButton("Read DateTime (RD)");
        JButton rcidBtn    = new JButton("Read Current IDs (RCID)");
        JButton verBtn     = new JButton("LAMA Version (*VERSION)");
        JButton getTimeBtn = new JButton("LAMA Get Time (*GETTIME)");
        JButton memBtn     = new JButton("Memory Info (*MEM)");
        JButton syncBtn    = new JButton("Sync Clock to Host (*SETTIME)");
        JButton ccBtn      = new JButton("Clear Counter (CC)");
        JButton relBtn     = new JButton("Read Error Log (REL)");
        c.gridy = 8; c.gridx = 2; p(grid, c, rdBtn);
        c.gridx = 3; p(grid, c, rcidBtn);
        c.gridx = 4; p(grid, c, verBtn);
        c.gridy = 9; c.gridx = 2; p(grid, c, getTimeBtn);
        c.gridx = 3; p(grid, c, memBtn);
        c.gridx = 4; p(grid, c, syncBtn);
        c.gridy = 10; c.gridx = 2; p(grid, c, ccBtn);
        c.gridx = 3; p(grid, c, relBtn);

        rdBtn.addActionListener(_ -> doLeapRaw("RD"));
        rcidBtn.addActionListener(_ -> doLeapRaw("RCID"));
        verBtn.addActionListener(_ -> doLeapRaw("*VERSION"));
        getTimeBtn.addActionListener(_ -> doLeapRaw("*GETTIME"));
        memBtn.addActionListener(_ -> doLeapRaw("*MEM"));
        ccBtn.addActionListener(_ -> doLeapRaw("CC"));
        relBtn.addActionListener(_ -> doLeapRaw("REL"));
        syncBtn.addActionListener(_ -> {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            doLeapRaw("*SETTIME," + ts);
        });

        // Section: Raw command
        sectionHeader(grid, c, "Raw Command", 11);

        c.gridy = 12; c.gridx = 0; c.weightx = 0; p(grid, c, label("Command:"));
        leapRawCmd = new JTextField(30);
        leapRawCmd.setToolTipText("Type any command without trailing CR, e.g. *DIR,*.llf or &GETSTAT,IN001");
        c.gridx = 1; c.weightx = 1; p(grid, c, leapRawCmd);
        JButton rawBtn = new JButton("Send");
        c.gridx = 2; c.weightx = 0; p(grid, c, rawBtn);
        rawBtn.addActionListener(_ -> {
            String cmd = leapRawCmd.getText().trim();
            if (!cmd.isEmpty()) { doLeapRaw(cmd); leapRawCmd.selectAll(); }
        });
        leapRawCmd.addActionListener(_ -> rawBtn.doClick()); // Enter to send

        // Result area
        leapResultArea = new JTextArea(8, 60);
        leapResultArea.setEditable(false);
        leapResultArea.setFont(monoFont(MONO_SIZE));

        JScrollPane gridScroll  = new JScrollPane(grid);
        JScrollPane resultScroll = new JScrollPane(leapResultArea);
        resultScroll.setBorder(BorderFactory.createTitledBorder("Responses"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, gridScroll, resultScroll);
        split.setResizeWeight(0.65);
        p.add(split, BorderLayout.CENTER);

        return p;
    }

    // =========================================================================
    // Bottom panel: Activity Log + Wire Trace
    // =========================================================================

    private JPanel buildActivityPanel() {
        // ── Colour attributes for wire trace ────────────────────────────────
        attrTx    = styledAttr(new Color(0x69F0AE), false); // green  – TX
        attrRxOk  = styledAttr(new Color(0x40C4FF), false); // cyan   – RX ACK
        attrRxNak = styledAttr(new Color(0xFF6E40), false); // orange – RX NAK
        attrWarn  = styledAttr(new Color(0xFF5252), true);  // red    – timeout/error
        attrInfo  = styledAttr(new Color(0x90A4AE), false); // grey   – connect info

        // ── Activity log ────────────────────────────────────────────────────
        activityLog = new JTextArea();
        activityLog.setEditable(false);
        activityLog.setFont(monoFont(MONO_SIZE));
        activityLog.setBackground(new Color(0x1E2430));
        activityLog.setForeground(new Color(0xCDD3DE));
        activityLog.setCaretColor(new Color(0xCDD3DE));

        JPanel actPanel = new JPanel(new BorderLayout(2, 2));
        actPanel.add(activityLogHeader(), BorderLayout.NORTH);
        actPanel.add(new JScrollPane(activityLog), BorderLayout.CENTER);

        // ── Wire trace ──────────────────────────────────────────────────────
        wirePane = new JTextPane();
        wirePane.setEditable(false);
        wirePane.setFont(monoFont(MONO_SIZE));
        wirePane.setBackground(new Color(0x0D1117));
        wireDoc  = wirePane.getStyledDocument();

        JPanel wirePanel = new JPanel(new BorderLayout(2, 2));
        wirePanel.add(wireTraceHeader(), BorderLayout.NORTH);
        wirePanel.add(new JScrollPane(wirePane), BorderLayout.CENTER);

        // ── Split ────────────────────────────────────────────────────────────
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, actPanel, wirePanel);
        split.setResizeWeight(0.45);
        split.setBorder(null);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(2, 0, 0, 0, new Color(0x90A4AE)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        outer.setPreferredSize(new Dimension(0, 200));
        outer.add(split, BorderLayout.CENTER);
        return outer;
    }

    private JPanel activityLogHeader() {
        JPanel hdr = new JPanel(new BorderLayout());
        JLabel lbl = new JLabel("Activity Log");
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        hdr.add(lbl, BorderLayout.WEST);
        JButton clr = new JButton("Clear");
        clr.addActionListener(_ -> activityLog.setText(""));
        hdr.add(clr, BorderLayout.EAST);
        return hdr;
    }

    private JPanel wireTraceHeader() {
        JPanel hdr = new JPanel(new BorderLayout());

        wireTraceToggle = new JToggleButton("Wire Trace: OFF", false);
        wireTraceToggle.setFont(wireTraceToggle.getFont().deriveFont(Font.BOLD));
        wireTraceToggle.setForeground(new Color(0x757575));
        wireTraceToggle.addActionListener(_ -> {
            boolean on = wireTraceToggle.isSelected();
            wireTraceToggle.setText(on ? "Wire Trace: ON " : "Wire Trace: OFF");
            wireTraceToggle.setForeground(on ? new Color(0x00C853) : new Color(0x757575));
            // Apply immediately if already connected
            if (labeller != null) {
                labeller.setWireLogger(on ? this::wireTrace : null);
                if (on) wireTrace("--- Wire trace enabled on live connection");
            }
        });
        hdr.add(wireTraceToggle, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JLabel legend = new JLabel(
            "<html><font color='#69F0AE'>>>> TX</font>  "
          + "<font color='#40C4FF'><<< RX ACK</font>  "
          + "<font color='#FF6E40'><<< RX NAK</font>  "
          + "<font color='#FF5252'>!!! WARN</font>  "
          + "<font color='#90A4AE'>--- INFO</font></html>");
        legend.setFont(legend.getFont().deriveFont(12f));
        right.add(legend);

        JButton clr = new JButton("Clear");
        clr.addActionListener(_ -> {
            try { wireDoc.remove(0, wireDoc.getLength()); }
            catch (Exception ignored) {}
        });
        right.add(clr);
        hdr.add(right, BorderLayout.EAST);
        return hdr;
    }

    /** Append a line to the wire trace pane with appropriate colour. */
    private void wireTrace(String message) {
        SwingUtilities.invokeLater(() -> {
            String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            String line = "[" + ts + "]  " + message + "\n";
            javax.swing.text.SimpleAttributeSet attr;
            if      (message.startsWith(">>>")) attr = attrTx;
            else if (message.startsWith("<<< RX  ACK")) attr = attrRxOk;
            else if (message.startsWith("<<< RX  NAK") || message.startsWith("<<< RX  STATE")) attr = attrRxNak;
            else if (message.startsWith("!!!")) attr = attrWarn;
            else                                attr = attrInfo;
            try {
                wireDoc.insertString(wireDoc.getLength(), line, attr);
                wirePane.setCaretPosition(wireDoc.getLength());
            } catch (Exception ignored) {}
        });
    }

    /** Build a {@link javax.swing.text.SimpleAttributeSet} with the given foreground colour. */
    private static javax.swing.text.SimpleAttributeSet styledAttr(Color fg, boolean bold) {
        javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setForeground(a, fg);
        javax.swing.text.StyleConstants.setBold(a, bold);
        return a;
    }

    // =========================================================================
    // Connect / Disconnect
    // =========================================================================

    private void onConnect() {
        String type    = (String) typeCombo.getSelectedItem();
        String host    = hostField.getText().trim();
        String portRaw = portCombo.getSelectedItem().toString().trim().split("\\s")[0];
        int    port;
        try { port = Integer.parseInt(portRaw); }
        catch (NumberFormatException ex) { log("ERROR: invalid port: " + portRaw); return; }

        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());

        setStatus("● Connecting…", COL_CONNECTING);
        connectBtn.setEnabled(false);

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                cleanDisconnect();
                if (TYPE_TCP.equals(type)) {
                    labeller = new LogoClientPL3(host, port);
                    labeller.connect();
                    return "TCP connected to " + host + ":" + port;
                } else {
                    sftp = new LogoClientSftp(host, port, 15_000);
                    sftp.setStrictHostKeyChecking(false);
                    sftp.connect(user, pass);
                    return "SFTP connected to " + host + ":" + port;
                }
            }
            @Override protected void done() {
                try {
                    log("✓ " + get());
                    // Auto-wire the logger if Wire Trace toggle is on
                    if (labeller != null && wireTraceToggle.isSelected()) {
                        labeller.setWireLogger(LogoClientTester.this::wireTrace);
                    }
                    setStatus("● Connected  [" + type + "  " + host + ":" + port + "]", COL_CONNECTED);
                    updateUiState(true);
                } catch (Exception ex) {
                    log("✗ Connect failed: " + rootCause(ex));
                    setStatus("● Disconnected", COL_DISCONNECTED);
                    connectBtn.setEnabled(true);
                    cleanDisconnect();
                }
            }
        }.execute();
    }

    private void onDisconnect() {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() { cleanDisconnect(); return null; }
            @Override protected void done() {
                log("Disconnected.");
                setStatus("● Disconnected", COL_DISCONNECTED);
                updateUiState(false);
            }
        }.execute();
    }

    private void cleanDisconnect() {
        try { if (labeller != null) { labeller.close(); labeller = null; } } catch (Exception ignored) {}
        try { if (sftp    != null) { sftp.close();     sftp    = null; } } catch (Exception ignored) {}
    }

    private void updateUiState(boolean connected) {
        boolean isTcp = TYPE_TCP.equals(typeCombo.getSelectedItem());

        // Connection fields
        connectBtn.setEnabled(!connected);
        disconnectBtn.setEnabled(connected);
        typeCombo.setEnabled(!connected);
        hostField.setEnabled(!connected);
        portCombo.setEnabled(!connected);
        if (!connected) {
            // Re-apply visibility based on type
            boolean nc = !TYPE_TCP.equals(typeCombo.getSelectedItem());
            userField.setEnabled(nc);
            passField.setEnabled(nc);
        } else {
            userField.setEnabled(false);
            passField.setEnabled(false);
        }

        // Transfer buttons
        uploadBtn.setEnabled(connected);
        downloadBtn.setEnabled(connected);
        dirListBtn.setEnabled(connected);
        dirDownloadBtn.setEnabled(connected);

        // TCP-only tabs
        boolean tcpOk = connected && isTcp;
        tabbedPane.setEnabledAt(3, tcpOk); // Pallet Log
        tabbedPane.setEnabledAt(4, tcpOk); // LEAP / LAMA
        logFetchBtn.setEnabled(tcpOk);

        // Switch away from a now-disabled tab
        if (!tcpOk && tabbedPane.getSelectedIndex() >= 3) {
            tabbedPane.setSelectedIndex(0);
        }
    }

    // =========================================================================
    // Upload
    // =========================================================================

    private void doUpload() {
        String localStr  = uploadLocalPath.getText().trim();
        String remotePath = uploadRemotePath.getText().trim();
        if (localStr.isEmpty())  { log("ERROR: select a local file first."); return; }
        if (remotePath.isEmpty()) { log("ERROR: enter a remote path."); return; }
        File f = new File(localStr);
        if (!f.isFile()) { log("ERROR: local file not found: " + localStr); return; }

        uploadBtn.setEnabled(false);
        uploadProgress.setIndeterminate(true);
        uploadProgress.setString("Uploading…");

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                byte[] data = Files.readAllBytes(f.toPath());
                if (labeller != null) {
                    // Derive filename for labeller (strip path)
                    String name = remotePath.contains("/")
                            ? remotePath.substring(remotePath.lastIndexOf('/') + 1) : remotePath;
                    if (name.isEmpty()) name = f.getName();
                    LogoClientPL3.LabellerResponse r = labeller.lamaUploadHexFile(name, data);
                    if (!r.isOk()) throw new IOException("*HEXFILE failed: " + r);
                    return "IntelHEX upload OK — " + data.length + " raw bytes → " + name
                            + "  (tip: round-trip test — download then re-upload a known-good .llf"
                            + " if the file does not appear in the directory)";
                } else if (sftp != null) {
                    String remote = remotePath.endsWith("/") ? remotePath + f.getName() : remotePath;
                    sftp.uploadFile(remote, data);
                    return "SFTP upload OK — " + data.length + " bytes → " + remote;
                } else {
                    throw new IllegalStateException("Not connected");
                }
            }
            @Override protected void done() {
                uploadProgress.setIndeterminate(false);
                uploadBtn.setEnabled(true);
                try {
                    log("✓ " + get());
                    // After *HEXFILE the labeller writes to CMOS flash — during the
                    // write it ignores all commands (socket timeout, empty body).
                    // We don't know how long the write takes (depends on file size),
                    // so we switch to the directory tab and poll until it responds.
                    tabbedPane.setSelectedIndex(2);
                    doPostUploadDirRefresh(selectedFilter());
                } catch (Exception ex) {
                    log("✗ Upload failed: " + rootCause(ex));
                    uploadProgress.setString("Failed ✗");
                }
            }
        }.execute();
    }

    /**
     * Polls {@code *DIR} after a HEXFILE upload, retrying until the labeller
     * responds or the attempt limit is reached.
     *
     * <p>The labeller is silent while it writes to CMOS flash; during this time
     * every command returns an empty socket timeout.  We keep trying every
     * {@code RETRY_DELAY_MS} milliseconds rather than guessing a fixed wait
     * time (write duration scales with file size).
     */
    private void doPostUploadDirRefresh(String filter) {
        final int MAX_ATTEMPTS  = 12;   // ~36 s window (12 × 3 s)
        final int RETRY_DELAY_MS = 3_000;

        uploadProgress.setMaximum(MAX_ATTEMPTS);
        uploadProgress.setValue(MAX_ATTEMPTS);
        uploadProgress.setString("Waiting for labeller…");
        dirListBtn.setEnabled(false);

        new SwingWorker<List<String[]>, Integer>() {
            @Override protected List<String[]> doInBackground() throws Exception {
                IOException lastEx = null;
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    publish(attempt);
                    try {
                        LogoClientPL3.LabellerResponse r = labeller.lamaListFiles(filter);
                        if (r.isOk()) {
                            List<String[]> rows = new ArrayList<>();
                            for (String line : r.getBody().split("[\r\n]+")) {
                                String name = line.trim();
                                if (name.isEmpty()) continue;
                                if (name.matches("^\\d+ .*")) continue;
                                String type = name.contains(".") ? "FILE" : "DIR";
                                rows.add(new String[]{ name, "", "", type });
                            }
                            rows.sort((a, b) -> {
                                if (a[3].equals(b[3])) return a[0].compareToIgnoreCase(b[0]);
                                return "DIR".equals(a[3]) ? -1 : 1;
                            });
                            return rows;
                        }
                        // Labeller returned a non-OK response (e.g. 996) — wait and retry
                        lastEx = new IOException("*DIR returned: " + r);
                    } catch (IOException ex) {
                        // Socket timeout — labeller still writing to flash
                        lastEx = ex;
                    }
                    if (attempt < MAX_ATTEMPTS) {
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                }
                throw new IOException(
                        "Labeller did not respond to *DIR after " + MAX_ATTEMPTS + " attempts: " + lastEx);
            }

            @Override protected void process(List<Integer> attempts) {
                int attempt = attempts.get(attempts.size() - 1);
                int remaining = MAX_ATTEMPTS - attempt + 1;
                uploadProgress.setValue(remaining);
                uploadProgress.setString(
                        "Waiting for labeller… (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");
                log("Post-upload *DIR  attempt " + attempt + "/" + MAX_ATTEMPTS);
            }

            @Override protected void done() {
                try {
                    List<String[]> rows = get();
                    dirModel.setRowCount(0);
                    for (String[] row : rows) dirModel.addRow(row);
                    dirCurrentLabel.setText("Listing:  " + filter +
                            "  (" + rows.size() + " entries)");
                    log("Post-upload directory listed: " + rows.size() + " entries");
                    uploadProgress.setValue(MAX_ATTEMPTS);
                    uploadProgress.setString("Done ✓");
                } catch (Exception ex) {
                    log("✗ Post-upload directory refresh failed: " + rootCause(ex));
                    uploadProgress.setString("Refresh failed ✗");
                } finally {
                    dirListBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    // =========================================================================
    // Download
    // =========================================================================

    private void doDownload() {
        String remote   = downloadRemotePath.getText().trim();
        String localStr = downloadLocalPath.getText().trim();
        if (remote.isEmpty())   { log("ERROR: enter a remote path."); return; }
        if (localStr.isEmpty()) { log("ERROR: select a local save location."); return; }

        downloadBtn.setEnabled(false);
        downloadProgress.setIndeterminate(true);
        downloadProgress.setString("Downloading…");

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                byte[] data;
                if (labeller != null) {
                    String name = remote.contains("/")
                            ? remote.substring(remote.lastIndexOf('/') + 1) : remote;
                    data = labeller.lamaDownloadHexFileAsBytes(name);
                } else if (sftp != null) {
                    data = sftp.downloadFile(remote);
                } else {
                    throw new IllegalStateException("Not connected");
                }
                Files.write(Paths.get(localStr), data);
                return "Downloaded " + data.length + " bytes → " + localStr;
            }
            @Override protected void done() {
                try {
                    log("✓ " + get());
                    downloadProgress.setString("Done ✓");
                } catch (Exception ex) {
                    log("✗ Download failed: " + rootCause(ex));
                    downloadProgress.setString("Failed ✗");
                } finally {
                    downloadProgress.setIndeterminate(false);
                    downloadBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    // =========================================================================
    // Directory
    // =========================================================================

    private void doListDirectory(String path) {
        if (path.isEmpty()) path = "/";
        final String finalPath = path;
        dirListBtn.setEnabled(false);

        new SwingWorker<List<String[]>, Void>() {
            @Override protected List<String[]> doInBackground() throws Exception {
                List<String[]> rows = new ArrayList<>();
                if (labeller != null) {
                    // *DIR,<wildcard> — lists the labeller's single working directory
                    LogoClientPL3.LabellerResponse r = labeller.lamaListFiles(finalPath);
                    if (!r.isOk()) throw new IOException("*DIR failed: " + r);
                    for (String line : r.getBody().split("[\r\n]+")) {
                        String name = line.trim();
                        if (name.isEmpty()) continue;
                        // Filter out VCOMSVR status lines: "0 No error(s).", "996 …" etc.
                        if (name.matches("^\\d+ .*")) continue;
                        String type = name.contains(".") ? "FILE" : "DIR";
                        rows.add(new String[]{ name, "", "", type });
                    }
                } else if (sftp != null) {
                    for (LogoClientSftp.FileEntry e : sftp.listFiles(finalPath)) {
                        String size = e.isDirectory() ? "" : String.valueOf(e.size());
                        String date = "";
                        try {
                            date = Instant.ofEpochSecond(e.modifiedTime())
                                          .atZone(ZoneId.systemDefault())
                                          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                        } catch (Exception ignored2) {}
                        rows.add(new String[]{ e.name(), size, date, e.isDirectory() ? "DIR" : "FILE" });
                    }
                } else {
                    throw new IllegalStateException("Not connected");
                }
                // Sort: directories first, then files alphabetically
                rows.sort((a, b) -> {
                    if (a[3].equals(b[3])) return a[0].compareToIgnoreCase(b[0]);
                    return "DIR".equals(a[3]) ? -1 : 1;
                });
                return rows;
            }
            @Override protected void done() {
                try {
                    List<String[]> rows = get();
                    dirModel.setRowCount(0);
                    for (String[] row : rows) dirModel.addRow(row);
                    dirCurrentLabel.setText("Listing:  " + finalPath +
                            "  (" + rows.size() + " entries)");
                    log("Directory listed: " + rows.size() + " entries  [filter: " + finalPath + "]");
                } catch (Exception ex) {
                    log("✗ Directory listing failed: " + rootCause(ex));
                } finally {
                    dirListBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    private void doDownloadSelected() {
        int row = dirTable.getSelectedRow();
        if (row < 0) { log("No file selected."); return; }
        String name = (String) dirModel.getValueAt(row, 0);
        String type = (String) dirModel.getValueAt(row, 3);
        if ("DIR".equals(type)) { log("Cannot download a directory."); return; }

        String curPath  = currentPath();
        String remoteFull = (curPath.endsWith("/") ? curPath : curPath + "/") + name;

        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(name));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File dest = fc.getSelectedFile();

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                byte[] data;
                if (labeller != null) {
                    data = labeller.lamaDownloadHexFileAsBytes(name);
                } else if (sftp != null) {
                    data = sftp.downloadFile(remoteFull);
                } else {
                    throw new IllegalStateException("Not connected");
                }
                Files.write(dest.toPath(), data);
                return "Downloaded " + data.length + " bytes → " + dest.getAbsolutePath();
            }
            @Override protected void done() {
                try { log("✓ " + get()); }
                catch (Exception ex) { log("✗ Download failed: " + rootCause(ex)); }
            }
        }.execute();
    }

    // =========================================================================
    // Pallet Log
    // =========================================================================

    private void doFetchPalletLog() {
        if (labeller == null) { log("ERROR: Pallet log requires a TCP connection."); return; }
        logFetchBtn.setEnabled(false);
        logInfoLabel.setText("Fetching…");

        new SwingWorker<List<PalletLogEntry>, Void>() {
            @Override protected List<PalletLogEntry> doInBackground() throws Exception {
                return labeller.fetchPalletLog();
            }
            @Override protected void done() {
                try {
                    List<PalletLogEntry> entries = get();
                    logModel.setRowCount(0);
                    logRawArea.setText("");

                    if (entries.isEmpty()) {
                        logInfoLabel.setText("No log entries — labeller returned nothing.");
                        log("Pallet log: empty.");
                        return;
                    }

                    for (int i = 0; i < entries.size(); i++) {
                        PalletLogEntry e = entries.get(i);
                        String sscc   = e.getSscc() != null ? e.getSscc() : "(not found)";
                        String seq    = e.getSequence() >= 0 ? String.valueOf(e.getSequence()) : "—";
                        String fields = String.join("  |  ", e.getFields());
                        logModel.addRow(new Object[]{ i + 1, sscc, seq, fields, e.getRawData() });
                    }

                    // Detect paired pallets (same SSCC = both labels applied)
                    int pallets = 0;
                    for (int i = 0; i < entries.size(); i++) {
                        if (i + 1 < entries.size() && entries.get(i).isSamePallet(entries.get(i + 1))) {
                            pallets++;
                            i++;
                        }
                    }

                    String summary = entries.size() + " log entries retrieved";
                    if (pallets > 0) summary += " — " + pallets + " complete pallet(s) detected";
                    logInfoLabel.setText(summary + ".");
                    log("✓ Pallet log: " + summary + ".");

                } catch (Exception ex) {
                    log("✗ Fetch pallet log failed: " + rootCause(ex));
                    logInfoLabel.setText("Error — see activity log.");
                } finally {
                    logFetchBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    // =========================================================================
    // LEAP / LAMA raw commands
    // =========================================================================

    private void doLeapRaw(String cmd) {
        if (labeller == null) { log("ERROR: LEAP commands require a TCP connection."); return; }
        new SwingWorker<LogoClientPL3.LabellerResponse, Void>() {
            @Override protected LogoClientPL3.LabellerResponse doInBackground() throws Exception {
                return labeller.sendRaw(cmd);
            }
            @Override protected void done() {
                try {
                    LogoClientPL3.LabellerResponse r = get();
                    String line = cmd + "  →  " + r;
                    leapResultArea.append(line + "\n");
                    leapResultArea.setCaretPosition(leapResultArea.getDocument().getLength());
                    log((r.isOk() ? "✓" : "✗") + "  " + line);
                } catch (Exception ex) {
                    log("✗ Command \"" + cmd + "\" failed: " + rootCause(ex));
                }
            }
        }.execute();
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private void browseOpen(JTextField target) {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            target.setText(fc.getSelectedFile().getAbsolutePath());
    }

    private void browseSave(JTextField target, String hint) {
        JFileChooser fc = new JFileChooser();
        if (hint != null && !hint.isBlank()) {
            String name = hint.contains("/") ? hint.substring(hint.lastIndexOf('/') + 1) : hint;
            if (!name.isEmpty()) fc.setSelectedFile(new File(name));
        }
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
            target.setText(fc.getSelectedFile().getAbsolutePath());
    }

    private String currentPath() {
        // Label text is "Listing:  <path>  (N entries)" — extract the path portion
        String text = dirCurrentLabel.getText();
        int start = text.indexOf("Listing:  ");
        if (start >= 0) {
            text = text.substring(start + "Listing:  ".length());
            int paren = text.indexOf("  (");
            if (paren >= 0) text = text.substring(0, paren);
            return text.trim();
        }
        // Fallback: return combo value
        return selectedFilter();
    }

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            activityLog.append("[" + ts + "]  " + message + "\n");
            activityLog.setCaretPosition(activityLog.getDocument().getLength());
        });
    }

    private String rootCause(Throwable ex) {
        Throwable c = ex;
        while (c.getCause() != null) c = c.getCause();
        String msg = c.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : c.getClass().getSimpleName();
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets  = new Insets(4, 6, 4, 6);
        c.anchor  = GridBagConstraints.WEST;
        c.fill    = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private JLabel label(String text) { return new JLabel(text); }

    private void styleButton(JButton btn, Color bg, Color fg) {
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
    }

    private JProgressBar progressBar() {
        JProgressBar pb = new JProgressBar();
        pb.setStringPainted(true);
        pb.setString("");
        return pb;
    }

    private TitledBorder titledBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0xB0BEC5)),
                title, TitledBorder.LEFT, TitledBorder.TOP);
    }

    private JTextArea noteArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setWrapStyleWord(true);
        area.setLineWrap(true);
        area.setFont(area.getFont().deriveFont(Font.ITALIC));
        area.setForeground(new Color(0x546E7A));
        area.setBackground(new Color(0xF0F4F8));
        area.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return area;
    }

    private void filler(JPanel p, GridBagConstraints c, int row) {
        c.gridy = row; c.gridx = 0; c.gridwidth = 3;
        c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        p.add(new JPanel(), c);
        c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL; c.gridwidth = 1;
    }

    private Icon icon(String type) { return null; } // placeholder; no external icon resources needed

    // LEAP tab layout shorthands
    private void p(JPanel panel, GridBagConstraints c, JComponent comp) {
        panel.add(comp, c);
    }

    private void sectionHeader(JPanel panel, GridBagConstraints c, String title, int row) {
        JLabel lbl = new JLabel(title);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        lbl.setForeground(new Color(0x1565C0));
        lbl.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x90CAF9)));
        GridBagConstraints sc = (GridBagConstraints) c.clone();
        sc.gridy = row; sc.gridx = 0; sc.gridwidth = 5;
        sc.fill = GridBagConstraints.HORIZONTAL;
        sc.insets = new Insets(12, 6, 4, 6);
        panel.add(lbl, sc);
    }
}
