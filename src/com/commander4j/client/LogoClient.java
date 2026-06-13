package com.commander4j.client;

import com.commander4j.client.common.FileInfo;
import com.commander4j.client.common.ILogoLabeller;
import com.commander4j.client.common.LabellerResult;
import com.commander4j.client.common.PalletLogEntry;
import com.commander4j.client.pl3.LogoClientPL3;
import com.commander4j.client.pl6.LogoClientPL6;
import com.commander4j.client.pl6.Pl6Paths;
import com.commander4j.dialog.JDialogAbout;
import com.commander4j.dialog.JDialogLicenses;
import com.commander4j.gui.JButton4j;
import com.commander4j.gui.JCheckBox4j;
import com.commander4j.gui.JComboBox4j;
import com.commander4j.gui.JLabel4j_std;
import com.commander4j.gui.JPasswordField4j;
import com.commander4j.gui.JSpinner4j;
import com.commander4j.gui.JTextField4j;
import com.commander4j.gui.JToggleButton4j;
import com.commander4j.sys.Common;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;


import javax.swing.JOptionPane;

import javax.swing.JFileChooser;
import javax.swing.JFrame;

import javax.swing.JPanel;

import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;

import javax.swing.JTextPane;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.BorderFactory;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;

public class LogoClient extends JFrame
{

	private static final long serialVersionUID = 1L;

	// ── Colours
	// ───────────────────────────────────────────────────────────────
	private static final Color COL_CONNECTED = new Color(0x2E7D32);
	private static final Color COL_DISCONNECTED = new Color(0xC62828);
	private static final Color COL_CONNECTING = new Color(0xE65100);

	// ── Persisted state
	// ───────────────────────────────────────────────────────
	private static final File STATE_FILE = new File("xml/config/state.xml");

	// ── Connection type constants
	// ─────────────────────────────────────────────
	private static final String TYPE_PL3 = "PL3 (TCP)";
	private static final String TYPE_PL6 = "PL6 (SFTP)";

	// ── Directory filter presets
	// ──────────────────────────────────────────────
	private static final String[][] DIR_FILTERS =
	{
			{ "All files (*.*)", "*.*" },
			{ "Label layouts (*.llf)", "*.llf" },
			{ "Logo / PCX (*.pcx)", "*.pcx" },
			{ "Data files (*.ldf)", "*.ldf" },
			{ "Font files (*.fnt)", "*.fnt" },
			{ "Text files (*.txt)", "*.txt" }, };

	// ── C0 control-character tokens recognised in transmitted text
	// ────────────
	private static final Map<String, Character> CONTROL_TOKENS = Map.ofEntries(
			Map.entry("NUL", '\u0000'), Map.entry("SOH", '\u0001'),
			Map.entry("STX", '\u0002'), Map.entry("ETX", '\u0003'),
			Map.entry("EOT", '\u0004'), Map.entry("ENQ", '\u0005'),
			Map.entry("ACK", '\u0006'), Map.entry("BEL", '\u0007'),
			Map.entry("BS",  '\u0008'), Map.entry("TAB", '\u0009'),
			Map.entry("HT",  '\u0009'), Map.entry("LF",  '\n'),
			Map.entry("VT",  '\u000B'), Map.entry("FF",  '\u000C'),
			Map.entry("CR",  '\r'),     Map.entry("SO",  '\u000E'),
			Map.entry("SI",  '\u000F'), Map.entry("DLE", '\u0010'),
			Map.entry("DC1", '\u0011'), Map.entry("DC2", '\u0012'),
			Map.entry("DC3", '\u0013'), Map.entry("DC4", '\u0014'),
			Map.entry("NAK", '\u0015'), Map.entry("SYN", '\u0016'),
			Map.entry("ETB", '\u0017'), Map.entry("CAN", '\u0018'),
			Map.entry("EM",  '\u0019'), Map.entry("SUB", '\u001A'),
			Map.entry("ESC", '\u001B'), Map.entry("FS",  '\u001C'),
			Map.entry("GS",  '\u001D'), Map.entry("RS",  '\u001E'),
			Map.entry("US",  '\u001F'), Map.entry("DEL", '\u007F'));

	private static final Pattern CONTROL_TOKEN_PATTERN =
			Pattern.compile("<([A-Za-z]{1,3})>");

	// ── Connection state
	// ──────────────────────────────────────────────────────
	private ILogoLabeller labeller;

	// ── Auto-reconnect state
	// ──────────────────────────────────────────────────
	private static final int RECONNECT_INTERVAL_MS = 3_000;
	private static final int KEEPALIVE_DEFAULT_MS  = 30_000;
	private volatile int     heartbeatIntervalMs   = KEEPALIVE_DEFAULT_MS;
	private volatile boolean heartbeatEnabled      = true;
	private volatile Thread keepaliveThread;
	private volatile long lastActivityNanos;
	private String savedType;
	private String savedHost;
	private int savedPort;
	private String savedUser;
	private String savedPass;
	private boolean savedWireTrace;
	private volatile boolean autoReconnectEnabled = false;
	private volatile Thread reconnectThread;
	private boolean wasPollingBeforeLoss = false;
	private int savedPollIntervalSecs;
	private String savedPollSavePath;

	// ── Connection panel
	// ──────────────────────────────────────────────────────
	private JComboBox4j<String> typeCombo;
	private JComboBox4j<String> hostCombo;
	private JComboBox4j<String> portCombo;
	private JSpinner4j   timeoutSpinner;
	private JTextField4j userField;
	private JPasswordField4j passField;
	private JToggleButton4j connectBtn = new JToggleButton4j(Common.icon_disconnected);
	private JLabel4j_std statusLabel = new JLabel4j_std("");

	// ── Transfer fields
	// ───────────────────────────────────────────────────────
	/** Source file for upload (Browse … button populates this). */
	private JTextField4j uploadLocalPath;
	/** Destination folder for downloads (Browse … button populates this). */
	private JTextField4j downloadLocalPath;
	/** Local directory where poll-log SSCC files are written. */
	private JTextField4j logSavePath;
	/** Remote path prefix shown in the combo (e.g. /c0/). */
	private JComboBox4j<String> comboBoxRemotePath;
	/**
	 * Set to {@code true} while programmatically changing comboBoxRemotePath to
	 * prevent the ActionListener from triggering a redundant directory refresh.
	 */
	private boolean suppressPathRefresh = false;

	// ── Transfer buttons
	// ──────────────────────────────────────────────────────
	private JButton4j uploadBtn = new JButton4j(Common.icon_upload);
	private JButton4j downloadBtn = new JButton4j(Common.icon_download);
	private JButton4j selectAllBtn = new JButton4j(Common.icon_select);
	private JButton4j unselectAllBtn = new JButton4j(Common.icon_deselect);
	private JButton4j deleteBtn = new JButton4j(Common.icon_deletefile);
	private JButton4j btnAbout = new JButton4j(Common.icon_about);
	private JButton4j btnLicense = new JButton4j(Common.icon_license);
	private JButton4j quitBtn = new JButton4j(Common.icon_exit);
	private JButton4j btnBrowseLocalFile   = new JButton4j(Common.icon_browse);
	private JButton4j btnBrowseLocalFolder = new JButton4j(Common.icon_browse);
	private JButton4j btnBrowseLogPath     = new JButton4j(Common.icon_browse);
	private JProgressBar uploadProgress;

	// ── Transfer in-progress counter (EDT-only, no synchronisation needed) ───
	private int transfersInProgress = 0;

	// ── Directory panel
	// ───────────────────────────────────────────────────────
	private JComboBox4j<String> dirFilterCombo;
	private JLabel4j_std dirCurrentLabel;
	private JTable dirTable;
	private DefaultTableModel dirModel;
	private JButton4j dirListBtn = new JButton4j(Common.icon_reload);
	private JCheckBox4j autoDirToggle   = new JCheckBox4j("Auto DIR");
	private JCheckBox4j heartbeatToggle = new JCheckBox4j("Heartbeat");
	private JSpinner4j  heartbeatIntervalSpinner;

	// ── Log / wire trace
	// ──────────────────────────────────────────────────────
	private JToggleButton4j          logPollBtn           = new JToggleButton4j(Common.icon_clock5);
	private JSpinner4j               pollIntervalSpinner;
	private ILogoLabeller.PollHandle pollHandle;
	private JCheckBox4j wireTraceToggle;
	private JTextArea activityLog;
	private JButton4j btnActSave  = new JButton4j(Common.icon_save);
	private JButton4j btnActClear = new JButton4j(Common.icon_erase);
	private JTextPane wirePane;
	private JButton4j btnWireSave  = new JButton4j(Common.icon_save);
	private JButton4j btnWireClear = new JButton4j(Common.icon_erase);
	private JToggleButton4j btnWireTimestamp = new JToggleButton4j(Common.icon_clock);
	private javax.swing.text.StyledDocument wireDoc;
	private javax.swing.text.SimpleAttributeSet wireAttrTx;
	private javax.swing.text.SimpleAttributeSet wireAttrRx;
	private javax.swing.text.SimpleAttributeSet wireAttrTs;
	/** 0 = none, 1 = TX, 2 = RX — used to detect burst boundary for timestamping. */
	private int wireLastDirection = 0;
	/** Trailing tokens since the last newline, per direction, for EOL-match detection. */
	private final StringBuilder wireTxSinceEol = new StringBuilder();
	private final StringBuilder wireRxSinceEol = new StringBuilder();
	private static int widthadjustment = 0;
	private static int heightadjustment = 0;

	// ── Messages pane
	// ─────────────────────────────────────────────────────
	private JTextArea         messagesField;
	private JLabel4j_std      lblMessageFile   = new JLabel4j_std(" ");
	private JComboBox4j<String> eolCombo;
	private String            currentMessageFile;
	private JButton4j btnMsgOpen   = new JButton4j(Common.icon_open);
	private JButton4j btnMsgClear  = new JButton4j(Common.icon_erase);
	private JButton4j btnMsgSave   = new JButton4j(Common.icon_save);
	private JButton4j btnMsgSaveAs = new JButton4j(Common.icon_select_file);
	private JButton4j btnMsgSend   = new JButton4j(Common.icon_execute);

	// =========================================================================
	// Entry point
	// =========================================================================

	public static void main(String[] args)
	{
		// L&F must be set before ANY Swing component is constructed, otherwise
		// field-initializer buttons lock in their default (Aqua on macOS) UI
		// and Nimbus only partially applies when installed later.
		System.setProperty("apple.laf.useScreenMenuBar", "true");
		JUtility.setLookAndFeel("Nimbus");

		EventQueue.invokeLater(() -> {
			try
			{
				LogoClient frame = new LogoClient();
				frame.setVisible(true);
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		});
	}

	// =========================================================================
	// Constructor
	// =========================================================================

	public LogoClient()
	{
		setTitle(Common.programName + " " + Common.programVersion);

		setResizable(false);
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				doQuit();
			}
		});

		JPanel contentPane = new JPanel();
		contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		contentPane.setLayout(null);
		setContentPane(contentPane);

		JToolBar topToolBar = new JToolBar(JToolBar.HORIZONTAL);
		topToolBar.setFloatable(false);
		topToolBar.setBounds(0, 0, 1600, 44);
		contentPane.add(topToolBar);

		topToolBar.addSeparator();

		topToolBar.add(new JLabel4j_std("Type: "));
		typeCombo = new JComboBox4j<>(new String[] { TYPE_PL3, TYPE_PL6 });
		typeCombo.setPreferredSize(new Dimension(140, 28));
		typeCombo.setMaximumSize(new Dimension(140, 28));
		topToolBar.add(typeCombo);

		topToolBar.addSeparator();

		topToolBar.add(new JLabel4j_std("Host IP: "));
		java.util.Vector<String> hostIps = JUtility.getHostIPAddresses();
		if (!hostIps.contains("127.0.0.1")) hostIps.insertElementAt("127.0.0.1", 0);
		hostCombo = new JComboBox4j<>(hostIps.toArray(new String[0]));
		hostCombo.setEditable(true);
		hostCombo.setFocusable(true);
		hostCombo.getEditor().getEditorComponent().setFocusable(true);
		hostCombo.setSelectedItem("127.0.0.1");
		hostCombo.setToolTipText("Target labeller IP (editable)");
		hostCombo.setPreferredSize(new Dimension(135, 28));
		hostCombo.setMaximumSize(new Dimension(135, 28));
		topToolBar.add(hostCombo);

		topToolBar.addSeparator();

		topToolBar.add(new JLabel4j_std("Host Port: "));
		portCombo = new JComboBox4j<>();
		portCombo.setEditable(true);
		portCombo.setFocusable(true);
		portCombo.getEditor().getEditorComponent().setFocusable(true);
		portCombo.setToolTipText("Target port (editable)");
		portCombo.setPreferredSize(new Dimension(75, 28));
		portCombo.setMaximumSize(new Dimension(75, 28));
		topToolBar.add(portCombo);

		topToolBar.addSeparator();

		topToolBar.add(new JLabel4j_std("Timeout: "));
		timeoutSpinner = new JSpinner4j(new SpinnerNumberModel(5_000, 500, 120_000, 500));
		timeoutSpinner.setToolTipText("Socket read timeout (ms) — raise if directory listings time out");
		timeoutSpinner.setPreferredSize(new Dimension(90, 28));
		timeoutSpinner.setMaximumSize(new Dimension(90, 28));
		timeoutSpinner.addChangeListener(_ -> applyReadTimeout());
		topToolBar.add(timeoutSpinner);

						connectBtn.setToolTipText("Connect");
						connectBtn.setPreferredSize(new Dimension(36, 36));
						connectBtn.setMaximumSize(new Dimension(36, 36));
						topToolBar.add(connectBtn);
						connectBtn.addActionListener(_ -> {
							if (connectBtn.isSelected()) onConnect();
							else onDisconnect();
						});

		topToolBar.addSeparator();

		topToolBar.add(new JLabel4j_std("User: "));
		userField = new JTextField4j();
		userField.setText("");
		userField.setColumns(10);
		userField.setPreferredSize(new Dimension(120, 28));
		userField.setMaximumSize(new Dimension(120, 28));
		topToolBar.add(userField);

		topToolBar.addSeparator();

		topToolBar.add(new JLabel4j_std("Password: "));
		passField = new JPasswordField4j();
		passField.setPreferredSize(new Dimension(120, 28));
		passField.setMaximumSize(new Dimension(120, 28));
		topToolBar.add(passField);

		topToolBar.addSeparator();

		statusLabel.setPreferredSize(new Dimension(200, 28));
		statusLabel.setMaximumSize(new Dimension(200, 28));
		topToolBar.add(statusLabel);

		JPanel dp = new JPanel();
		dp.setLayout(null);
		dp.setBounds(0, 44, 1600, 807);
		contentPane.add(dp);

		// ── Row y=47: Remote path, Filter, User, Password ────────────────────

		JLabel4j_std lblRemotePath = new JLabel4j_std("Remote Path");
		lblRemotePath.setHorizontalAlignment(SwingConstants.TRAILING);
		lblRemotePath.setBounds(6, 118, 86, 24);
		dp.add(lblRemotePath);

		comboBoxRemotePath = new JComboBox4j<>();
		comboBoxRemotePath.setBounds(98, 118, 138, 24);
		comboBoxRemotePath.addActionListener(_ -> {
			if (!suppressPathRefresh && labeller != null)
				doListDirectory(selectedFilter());
		});
		dp.add(comboBoxRemotePath);

		JLabel4j_std lblMaskFilter = new JLabel4j_std("Mask/Filter");
		lblMaskFilter.setHorizontalAlignment(SwingConstants.TRAILING);
		lblMaskFilter.setBounds(248, 118, 68, 24);
		dp.add(lblMaskFilter);

		String[] filterLabels = new String[DIR_FILTERS.length];
		for (int i = 0; i < DIR_FILTERS.length; i++)
			filterLabels[i] = DIR_FILTERS[i][0];
		dirFilterCombo = new JComboBox4j<>(filterLabels);
		dirFilterCombo.setEditable(true);
		dirFilterCombo.setBounds(323, 118, 203, 24);
		dp.add(dirFilterCombo);

		// ── Row y=120: Local download folder ─────────────────────────────────

		JLabel4j_std lblLocalPath = new JLabel4j_std("Local Path");
		lblLocalPath.setHorizontalAlignment(SwingConstants.TRAILING);
		lblLocalPath.setBounds(6, 12, 86, 24);
		dp.add(lblLocalPath);

		downloadLocalPath = new JTextField4j();
		downloadLocalPath.setColumns(10);
		downloadLocalPath.setBounds(98, 12, 509, 24);
		dp.add(downloadLocalPath);

		btnBrowseLocalFolder.setBounds(612, 6, 36, 36);
		btnBrowseLocalFolder.setToolTipText("Browse");
		dp.add(btnBrowseLocalFolder);

		pollIntervalSpinner = new JSpinner4j(new SpinnerNumberModel(30, 5, 300, 5));
		pollIntervalSpinner.setToolTipText("Poll interval in seconds");
		pollIntervalSpinner.setBounds(555, 117, 52, 25);
		dp.add(pollIntervalSpinner);

		logPollBtn.setBounds(612, 112, 36, 36);
		logPollBtn.setToolTipText("Toggle polling");
		logPollBtn.setPreferredSize(new Dimension(36, 36));
		logPollBtn.setMaximumSize(new Dimension(36, 36));
		logPollBtn.setBorderPainted(true);
		logPollBtn.setContentAreaFilled(true);
		logPollBtn.setOpaque(true);
		logPollBtn.addActionListener(_ -> doTogglePoll());
		dp.add(logPollBtn);

		// ── Row y=156: Log save path ─────────────────────────────────────────

		JLabel4j_std lblLogPath = new JLabel4j_std("Log Path");
		lblLogPath.setHorizontalAlignment(SwingConstants.TRAILING);
		lblLogPath.setBounds(6, 82, 86, 24);
		dp.add(lblLogPath);

		logSavePath = new JTextField4j();
		logSavePath.setColumns(10);
		logSavePath.setToolTipText("Directory where one file per SSCC is written when polling");
		logSavePath.setBounds(98, 82, 509, 24);
		dp.add(logSavePath);

		btnBrowseLogPath.setBounds(612, 76, 36, 36);
		btnBrowseLogPath.setToolTipText("Browse");
		dp.add(btnBrowseLogPath);

		// ── Row y=192: Local file for upload ─────────────────────────────────

		JLabel4j_std lblLocalFile = new JLabel4j_std("Local File");
		lblLocalFile.setHorizontalAlignment(SwingConstants.TRAILING);
		lblLocalFile.setBounds(6, 46, 86, 24);
		dp.add(lblLocalFile);

		uploadLocalPath = new JTextField4j();
		uploadLocalPath.setColumns(10);
		uploadLocalPath.setBounds(98, 46, 509, 24);
		dp.add(uploadLocalPath);

		btnBrowseLocalFile.setBounds(612, 41, 36, 36);
		btnBrowseLocalFile.setToolTipText("Browse");
		dp.add(btnBrowseLocalFile);

		dirCurrentLabel = new JLabel4j_std("Listing:  (not connected)");
		dirCurrentLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		dirCurrentLabel.setBounds(346, 530, 256, 25);
		dp.add(dirCurrentLabel);

		autoDirToggle.setBounds(10, 530, 90, 25);
		autoDirToggle.setSelected(true);
		autoDirToggle.setToolTipText("List remote directory automatically on connect");
		dp.add(autoDirToggle);

		heartbeatToggle.setBounds(105, 530, 100, 25);
		heartbeatToggle.setSelected(true);
		heartbeatToggle.setToolTipText("Send a periodic ping to detect broken connections");
		heartbeatToggle.addActionListener(_ -> heartbeatEnabled = heartbeatToggle.isSelected());
		dp.add(heartbeatToggle);

		heartbeatIntervalSpinner = new JSpinner4j(
				new SpinnerNumberModel(KEEPALIVE_DEFAULT_MS, 1_000, 600_000, 1_000));
		heartbeatIntervalSpinner.setBounds(210, 531, 90, 24);
		heartbeatIntervalSpinner.setToolTipText("Heartbeat interval (ms)");
		heartbeatIntervalSpinner.addChangeListener(_ ->
				heartbeatIntervalMs = (Integer) heartbeatIntervalSpinner.getValue());
		dp.add(heartbeatIntervalSpinner);

		// ── Directory table
		// ───────────────────────────────────────────────────

		dirModel = new DefaultTableModel(new String[]
		{ "Select", "Name", "Size (bytes)", "Modified", "Type" }, 0)
		{
			@Override
			public boolean isCellEditable(int r, int c)
			{
				return c == 0;
			}

			@Override
			public Class<?> getColumnClass(int c)
			{
				return c == 0 ? Boolean.class : String.class;
			}
		};
		dirTable = new JTable(dirModel);
		dirTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		dirTable.setFillsViewportHeight(true);
		dirTable.setRowHeight(20);
		dirTable.getTableHeader().setReorderingAllowed(false);
		dirTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		dirTable.getColumnModel().getColumn(0).setPreferredWidth(50);
		dirTable.getColumnModel().getColumn(0).setMaxWidth(50);
		dirTable.getColumnModel().getColumn(1).setPreferredWidth(240);
		dirTable.getColumnModel().getColumn(1).setMaxWidth(240);
		dirTable.getColumnModel().getColumn(2).setPreferredWidth(80);
		dirTable.getColumnModel().getColumn(2).setMaxWidth(80);
		dirTable.getColumnModel().getColumn(3).setPreferredWidth(155);
		dirTable.getColumnModel().getColumn(3).setMaxWidth(155);
		dirTable.getColumnModel().getColumn(4).setPreferredWidth(45);
		dirTable.getColumnModel().getColumn(4).setMaxWidth(45);

		dirTable.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() != 2)
					return;
				int row = dirTable.getSelectedRow();
				if (row < 0)
					return;
				String type = (String) dirModel.getValueAt(row, 4);
				String name = (String) dirModel.getValueAt(row, 1);
				if ("DIR".equals(type) && labeller instanceof LogoClientPL6)
				{
					String cur = currentPath();
					String next;
					if ("..".equals(name))
					{
						next = cur.replaceAll("[^/]+/?$", "");
						if (next.isEmpty())
							next = "/";
					}
					else
					{
						next = (cur.endsWith("/") ? cur : cur + "/") + name + "/";
					}
					suppressPathRefresh = true;
					comboBoxRemotePath.setSelectedItem(next);
					suppressPathRefresh = false;
					doListDirectory(next);
				}
			}
		});

		dirModel.addTableModelListener(_ -> updateDeleteBtn());

		JScrollPane dirScrollPane = new JScrollPane();
		dirScrollPane.setBounds(10, 160, 595, 361);
		dirScrollPane.setViewportView(dirTable); // must be setViewportView, not
													// add()
		dp.add(dirScrollPane);

		// ── Row y=398: Action buttons
		// ─────────────────────────────────────────

		uploadBtn.setBounds(612, 163, 36, 36);
		uploadBtn.setToolTipText("Upload");
		dp.add(uploadBtn);

		dirListBtn.setBounds(612, 201, 36, 36);
		dirListBtn.setToolTipText("Refresh");
		dp.add(dirListBtn);

		downloadBtn.setBounds(612, 239, 36, 36);
		downloadBtn.setToolTipText("Download");
		dp.add(downloadBtn);

		selectAllBtn.setBounds(612, 277, 36, 36);
		selectAllBtn.setToolTipText("Select All");
		dp.add(selectAllBtn);

		unselectAllBtn.setBounds(612, 315, 36, 36);
		unselectAllBtn.setToolTipText("Unselect All");
		dp.add(unselectAllBtn);

		deleteBtn.setBounds(612, 353, 36, 36);
		deleteBtn.setToolTipText("Delete");
		dp.add(deleteBtn);

		wireTraceToggle = new JCheckBox4j("Wire Trace");
		wireTraceToggle.setBounds(1416, 531, 110, 24);
		wireTraceToggle.setSelected(true);
		dp.add(wireTraceToggle);
		wireTraceToggle.addActionListener(_ -> applyWireLoggers());

		// ── Activity log (left panel)
		// ─────────────────────────────────────────

		activityLog = new JTextArea();
		activityLog.setLocation(10, 0);
		activityLog.setEditable(false);
		activityLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		activityLog.setBackground(Color.BLACK);
		activityLog.setForeground(Color.YELLOW);
		activityLog.setCaretColor(Color.YELLOW);

		JScrollPane actScrollPane = new JScrollPane(activityLog);
		actScrollPane.setBounds(10, 560, 595, 219);
		dp.add(actScrollPane);

		btnActSave.setBounds(612, 559, 36, 36);
		btnActSave.setToolTipText("Save activity log…");
		btnActSave.addActionListener(_ -> doSaveTextPane(activityLog.getText(), "activity"));
		dp.add(btnActSave);

		btnActClear.setBounds(612, 595, 36, 36);
		btnActClear.setToolTipText("Clear activity log");
		btnActClear.addActionListener(_ -> activityLog.setText(""));
		dp.add(btnActClear);

		// ── Wire trace pane (right panel)
		// ─────────────────────────────────────

		wirePane = new JTextPane();
		wirePane.setLocation(680, 0);
		wirePane.setEditable(false);
		wirePane.setBackground(Color.BLACK);
		wirePane.setForeground(new Color(0x00C000));
		wirePane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

		JScrollPane wireScrollPane = new JScrollPane(wirePane);
		wireScrollPane.setBounds(680, 560, 846, 219);
		dp.add(wireScrollPane);

		btnWireSave.setBounds(1532, 559, 36, 36);
		btnWireSave.setToolTipText("Save wire trace…");
		btnWireSave.addActionListener(_ -> doSaveTextPane(wirePane.getText(), "wiretrace"));
		dp.add(btnWireSave);

		btnWireClear.setBounds(1532, 595, 36, 36);
		btnWireClear.setToolTipText("Clear wire trace");
		btnWireClear.addActionListener(_ -> {
			wirePane.setText("");
			wireLastDirection = 0;
			wireTxSinceEol.setLength(0);
			wireRxSinceEol.setLength(0);
		});
		dp.add(btnWireClear);

		btnWireTimestamp.setBounds(1532, 631, 36, 36);
		btnWireTimestamp.setToolTipText("Timestamp each TX / RX burst");
		btnWireTimestamp.setBorderPainted(true);
		btnWireTimestamp.setContentAreaFilled(true);
		btnWireTimestamp.setOpaque(true);
		dp.add(btnWireTimestamp);

		// ── Messages pane (top-right, above wire trace) ──────────────────────

		lblMessageFile.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(new Color(170, 170, 170), 1),
				new EmptyBorder(0, 6, 0, 6)));
		lblMessageFile.setOpaque(true);
		lblMessageFile.setBackground(new Color(241, 241, 241));
		lblMessageFile.setBounds(680, 12, 846, 22);
		dp.add(lblMessageFile);

		JScrollPane msgScrollPane = new JScrollPane((Component) null);
		msgScrollPane.setBounds(680, 46, 846, 475);
		dp.add(msgScrollPane);

		messagesField = new JTextArea();
		messagesField.setFont(new Font("Courier New", Font.PLAIN, 11));
		msgScrollPane.setViewportView(messagesField);

		JLabel4j_std lblEol = new JLabel4j_std("EOL");
		lblEol.setBounds(680, 531, 32, 24);
		dp.add(lblEol);

		eolCombo = new JComboBox4j<>(new String[] { "<CR>", "<CR><LF>", "<LF>", "<NONE>" });
		eolCombo.setToolTipText("End-of-line terminator used when transmitting");
		eolCombo.setBounds(716, 531, 140, 24);
		eolCombo.setSelectedIndex(0);
		dp.add(eolCombo);

		// ── Messages pane action buttons (x=1527) ────────────────────────────

		btnMsgOpen.setBounds(1532, 46, 36, 36);
		btnMsgOpen.setToolTipText("Open…");
		dp.add(btnMsgOpen);

		btnMsgClear.setBounds(1532, 82, 36, 36);
		btnMsgClear.setToolTipText("Clear");
		dp.add(btnMsgClear);

		btnMsgSave.setBounds(1532, 118, 36, 36);
		btnMsgSave.setToolTipText("Save");
		dp.add(btnMsgSave);

		btnMsgSaveAs.setBounds(1532, 154, 36, 36);
		btnMsgSaveAs.setToolTipText("Save As…");
		dp.add(btnMsgSaveAs);

		btnMsgSend.setBounds(1532, 190, 36, 36);
		btnMsgSend.setToolTipText("Transmit");
		dp.add(btnMsgSend);

		btnAbout.setBounds(1532, 228, 36, 36);
		btnAbout.setToolTipText("About");
		btnAbout.setPreferredSize(new Dimension(36, 36));
		btnAbout.setMaximumSize(new Dimension(36, 36));
		btnAbout.addActionListener(_ -> {
			JDialogAbout about = new JDialogAbout();
			about.setVisible(true);
		});
		dp.add(btnAbout);

		btnLicense.setBounds(1532, 264, 36, 36);
		btnLicense.setToolTipText("Licences");
		btnLicense.setPreferredSize(new Dimension(36, 36));
		btnLicense.setMaximumSize(new Dimension(36, 36));
		btnLicense.addActionListener(_ -> {
			JDialogLicenses dl = new JDialogLicenses(LogoClient.this);
			dl.setVisible(true);
		});
		dp.add(btnLicense);

		quitBtn.setBounds(1532, 300, 36, 36);
		dp.add(quitBtn);

		quitBtn.setToolTipText("Quit");
		quitBtn.setPreferredSize(new Dimension(36, 36));
		quitBtn.setMaximumSize(new Dimension(36, 36));
		quitBtn.addActionListener(_ -> doQuit());

		btnMsgOpen.addActionListener(_ -> doMsgOpen());
		btnMsgClear.addActionListener(_ -> doMsgClear());
		btnMsgSave.addActionListener(_ -> doMsgSave());
		btnMsgSaveAs.addActionListener(_ -> doMsgSaveAs());
		btnMsgSend.addActionListener(_ -> doMsgSend());

		// ── Upload progress (between messages pane and wire trace) ───────────

		uploadProgress = new JProgressBar(0, 100);
		uploadProgress.setBounds(0, 782, 1585, 19);
		uploadProgress.setForeground(Color.GREEN);
		uploadProgress.setStringPainted(true);
		uploadProgress.setString("");
		dp.add(uploadProgress);

		// ── Initialise wire trace document and colour attributes ──────────────

		wireDoc = wirePane.getStyledDocument();

		wireAttrTx = new javax.swing.text.SimpleAttributeSet();
		javax.swing.text.StyleConstants.setForeground(wireAttrTx, new Color(0x00FFFF)); // cyan — TX

		wireAttrRx = new javax.swing.text.SimpleAttributeSet();
		javax.swing.text.StyleConstants.setForeground(wireAttrRx, new Color(0x00FF00)); // green — RX

		wireAttrTs = new javax.swing.text.SimpleAttributeSet();
		javax.swing.text.StyleConstants.setForeground(wireAttrTs, Color.WHITE);
		javax.swing.text.StyleConstants.setBackground(wireAttrTs, new Color(0x404040));

		// ── Wire events
		// ───────────────────────────────────────────────────────

		typeCombo.addActionListener(_ -> onTypeChanged());

		dirFilterCombo.addActionListener(_ -> {
			if (labeller != null)
				doListDirectory(selectedFilter());
		});
		dirListBtn.addActionListener(_ -> doListDirectory(selectedFilter()));
		downloadBtn.addActionListener(_ -> doDownloadSelected());
		selectAllBtn.addActionListener(_ -> setAllChecked(true));
		unselectAllBtn.addActionListener(_ -> setAllChecked(false));
		deleteBtn.addActionListener(_ -> doDeleteSelected());
		uploadBtn.addActionListener(_ -> doUpload());

		btnBrowseLocalFile.addActionListener(_ -> browseForUploadFile());
		btnBrowseLocalFolder.addActionListener(_ -> browseForDownloadFolder());
		btnBrowseLogPath.addActionListener(_ -> browseForLogSavePath());

		// ── Set initial field states
		// ──────────────────────────────────────────

		downloadLocalPath.setText(System.getProperty("user.dir"));
		logSavePath.setText("labeller" + java.io.File.separator + "log");


		onTypeChanged(); // populate port combo for default type
		updateUiState(false); // all transfer buttons disabled until connected

		loadState();

		setBounds(100, 100, 1600, 887);

		widthadjustment = JUtility.getOSWidthAdjustment();
		heightadjustment = JUtility.getOSHeightAdjustment();

		GraphicsDevice gd = JUtility.getGraphicsDevice();

		GraphicsConfiguration gc = gd.getDefaultConfiguration();

		Rectangle screenBounds = gc.getBounds();

		setBounds(screenBounds.x + ((screenBounds.width - LogoClient.this.getWidth()) / 2), screenBounds.y + ((screenBounds.height - LogoClient.this.getHeight()) / 2), LogoClient.this.getWidth() + widthadjustment,
				LogoClient.this.getHeight() + heightadjustment);

		log(Common.programName + " " + Common.programVersion);
		for (String line : Common.disclaimer.split("\n"))
		{
			log(line);
		}

		setVisible(true);
	}

	// =========================================================================
	// Connect / Disconnect
	// =========================================================================

	private void onConnect()
	{
		String type = (String) typeCombo.getSelectedItem();
		String host = String.valueOf(hostCombo.getSelectedItem()).trim();
		String portRaw = portCombo.getSelectedItem().toString().trim().split("\\s")[0];
		int port;
		try
		{
			port = Integer.parseInt(portRaw);
		}
		catch (NumberFormatException ex)
		{
			log("ERROR: invalid port: " + portRaw);
			return;
		}
		String user = userField.getText().trim();
		String pass = new String(passField.getPassword());
		boolean wireTrace = wireTraceToggle.isSelected();

		setStatus("● Connecting…", COL_CONNECTING);
		connectBtn.setEnabled(false);

		new SwingWorker<String, Void>()
		{
			@Override
			protected String doInBackground() throws Exception
			{
				cleanDisconnect();
				if (TYPE_PL3.equals(type))
				{
					LogoClientPL3 pl3 = new LogoClientPL3(host, port);
					try
					{
						if (wireTrace)
						{
							pl3.setWireLogger(LogoClient.this::wireTrace);
							pl3.setRawLoggers(LogoClient.this::wireTxBytes,
							                  LogoClient.this::wireRxBytes);
						}
						pl3.connect();
						labeller = pl3;
						return "PL3 TCP connected to " + host + ":" + port;
					}
					catch (Throwable t)
					{
						try { pl3.close(); } catch (Exception ignore) {}
						throw t;
					}
				}
				else
				{
					LogoClientPL6 pl6 = new LogoClientPL6(host, port, 15_000);
					try
					{
						if (wireTrace)
							pl6.setWireLogger(LogoClient.this::wireTrace);
						pl6.connect(user, pass);
						labeller = pl6;
						return "PL6 SFTP connected to " + host + ":" + port;
					}
					catch (Throwable t)
					{
						try { pl6.close(); } catch (Exception ignore) {}
						throw t;
					}
				}
			}

			@Override
			protected void done()
			{
				try
				{
					log("✓ " + get());
					setStatus("● Connected  " + host + ":" + port, COL_CONNECTED);
					updateUiState(true);
					if (labeller instanceof LogoClientPL3 pl3)
					{
						suppressPathRefresh = true;
						comboBoxRemotePath.setSelectedItem("/c0/");
						comboBoxRemotePath.setEnabled(pl3.supportsPathPrefixedDir());
						suppressPathRefresh = false;
					}
					savedType = type;
					savedHost = host;
					savedPort = port;
					savedUser = user;
					savedPass = pass;
					savedWireTrace = wireTrace;
					autoReconnectEnabled = true;
					applyReadTimeout();
					startKeepalive();
					if (autoDirToggle.isSelected())
						doListDirectory(selectedFilter());
				}
				catch (Exception ex)
				{
					log("✗ Connect failed: " + rootCause(ex));
					setStatus("● Disconnected", COL_DISCONNECTED);
					connectBtn.setSelected(false);
					connectBtn.setIcon(Common.icon_disconnected);
					connectBtn.setToolTipText("Connect");
					connectBtn.setEnabled(true);
					cleanDisconnect();
				}
			}
		}.execute();
	}

	private void onDisconnect()
	{
		new SwingWorker<Void, Void>()
		{
			@Override
			protected Void doInBackground()
			{
				cleanDisconnect();
				return null;
			}

			@Override
			protected void done()
			{
				log("Disconnected.");
				setStatus("● Disconnected", COL_DISCONNECTED);
				updateUiState(false);
			}
		}.execute();
	}

	private void onTypeChanged()
	{
		String type = (String) typeCombo.getSelectedItem();
		portCombo.removeAllItems();
		suppressPathRefresh = true;
		comboBoxRemotePath.removeAllItems();
		boolean needsCreds;
		if (TYPE_PL3.equals(type))
		{
			portCombo.addItem("8000");
			portCombo.addItem("8100");
			portCombo.addItem("8200");
			portCombo.addItem("8300");
			comboBoxRemotePath.addItem("/c0/");
			comboBoxRemotePath.addItem("/c9/");
			comboBoxRemotePath.addItem("/d0/");
			comboBoxRemotePath.addItem("/r0/");
			comboBoxRemotePath.addItem("/f0/");
			needsCreds = false;
		}
		else
		{
			portCombo.addItem("22");
			comboBoxRemotePath.addItem(Pl6Paths.LAYOUTS_DIR);
			comboBoxRemotePath.addItem(Pl6Paths.IMAGES_DIR);
			comboBoxRemotePath.addItem(Pl6Paths.BASE_DIR);
			comboBoxRemotePath.addItem(Pl6Paths.FONTS_DIR);
			comboBoxRemotePath.addItem(Pl6Paths.LOG_DIR);
			needsCreds = true;
		}
		suppressPathRefresh = false;
		userField.setEnabled(needsCreds);
		passField.setEnabled(needsCreds);
		dirFilterCombo.setEnabled(TYPE_PL3.equals(type));
	}

	private void cleanDisconnect()
	{
		autoReconnectEnabled = false;
		stopKeepalive();
		Thread rt = reconnectThread;
		if (rt != null)
		{
			rt.interrupt();
			reconnectThread = null;
		}
		wasPollingBeforeLoss = false;
		if (pollHandle != null)
		{
			pollHandle.stop();
			pollHandle = null;
		}
		try
		{
			if (labeller != null)
			{
				labeller.close();
				labeller = null;
			}
		}
		catch (Exception ignored)
		{
		}
	}

	// =========================================================================
	// Auto-reconnect
	// =========================================================================

	private boolean isConnectionLoss(Throwable t)
	{
		for (Throwable c = t; c != null; c = c.getCause())
		{
			if (c instanceof java.io.IOException)
				return true;
		}
		return false;
	}

	/** Trigger reconnect if an operation's exception indicates the TCP link is dead. */
	private void maybeHandleConnectionLoss(Throwable ex)
	{
		if (autoReconnectEnabled && labeller != null && isConnectionLoss(ex))
			handleConnectionLost();
	}

	private void handleConnectionLost()
	{
		Thread rt = reconnectThread;
		if (rt != null && rt.isAlive())
			return;

		log("⚠ Connection lost. Reconnecting every " + (RECONNECT_INTERVAL_MS / 1000) + "s…");
		setStatus("● Reconnecting…", COL_CONNECTING);
		stopKeepalive();

		wasPollingBeforeLoss = (pollHandle != null);
		if (wasPollingBeforeLoss)
		{
			savedPollIntervalSecs = (Integer) pollIntervalSpinner.getValue();
			savedPollSavePath = logSavePath.getText().trim();
		}

		if (pollHandle != null)
		{
			pollHandle.stop();
			pollHandle = null;
		}
		try
		{
			if (labeller != null)
				labeller.close();
		}
		catch (Exception ignored)
		{
		}
		labeller = null;

		updateUiState(false);
		connectBtn.setEnabled(false);

		reconnectThread = new Thread(this::reconnectLoop, "logo-reconnect");
		reconnectThread.setDaemon(true);
		reconnectThread.start();
	}

	@SuppressWarnings("resource")
	private void reconnectLoop()
	{
		int attempt = 0;
		while (autoReconnectEnabled && !Thread.currentThread().isInterrupted())
		{
			try
			{
				Thread.sleep(RECONNECT_INTERVAL_MS);
			}
			catch (InterruptedException ie)
			{
				return;
			}
			if (!autoReconnectEnabled)
				return;

			attempt++;
			final int att = attempt;
			SwingUtilities.invokeLater(() -> log("↻ Reconnect attempt " + att + " → " + savedHost + ":" + savedPort));

			try
			{
				final ILogoLabeller newLabeller;
				if (TYPE_PL3.equals(savedType))
				{
					LogoClientPL3 pl3 = new LogoClientPL3(savedHost, savedPort);
					try
					{
						if (savedWireTrace)
						{
							pl3.setWireLogger(LogoClient.this::wireTrace);
							pl3.setRawLoggers(LogoClient.this::wireTxBytes,
							                  LogoClient.this::wireRxBytes);
						}
						pl3.connect();
						newLabeller = pl3;
					}
					catch (Throwable t)
					{
						try { pl3.close(); } catch (Exception ignore) {}
						throw t;
					}
				}
				else
				{
					LogoClientPL6 pl6 = new LogoClientPL6(savedHost, savedPort, 15_000);
					try
					{
						if (savedWireTrace)
							pl6.setWireLogger(LogoClient.this::wireTrace);
						pl6.connect(savedUser, savedPass);
						newLabeller = pl6;
					}
					catch (Throwable t)
					{
						try { pl6.close(); } catch (Exception ignore) {}
						throw t;
					}
				}
				SwingUtilities.invokeLater(() -> onReconnectSuccess(newLabeller));
				return;
			}
			catch (Exception e)
			{
				SwingUtilities.invokeLater(() -> log("  … failed: " + rootCause(e)));
			}
		}
	}

	private void applyReadTimeout()
	{
		if (!(labeller instanceof LogoClientPL3 pl3))
			return;
		try
		{
			pl3.setReadTimeout((Integer) timeoutSpinner.getValue());
		}
		catch (IOException ex)
		{
			log("✗ Could not apply read timeout: " + rootCause(ex));
		}
	}

	private void startKeepalive()
	{
		stopKeepalive();
		markActivity();
		keepaliveThread = new Thread(this::keepaliveLoop, "logo-keepalive");
		keepaliveThread.setDaemon(true);
		keepaliveThread.start();
	}

	private void stopKeepalive()
	{
		Thread k = keepaliveThread;
		if (k != null)
		{
			k.interrupt();
			keepaliveThread = null;
		}
	}

	private void markActivity()
	{
		lastActivityNanos = System.nanoTime();
	}

	private void keepaliveLoop()
	{
		while (autoReconnectEnabled && !Thread.currentThread().isInterrupted())
		{
			int  interval = heartbeatIntervalMs;
			long idleMs   = (System.nanoTime() - lastActivityNanos) / 1_000_000L;
			long waitMs   = interval - idleMs;
			if (waitMs <= 0)
				waitMs = interval;
			try
			{
				Thread.sleep(waitMs);
			}
			catch (InterruptedException ie)
			{
				return;
			}
			if (!autoReconnectEnabled)
				return;
			if (!heartbeatEnabled)
				continue;

			// Only fire once the connection has actually been idle for the
			// full interval — real TX/RX resets the clock via markActivity().
			interval = heartbeatIntervalMs;
			idleMs   = (System.nanoTime() - lastActivityNanos) / 1_000_000L;
			if (idleMs < interval)
				continue;

			// Skip if another path is already exercising the socket.
			// Polling detects loss on its own; user ops will surface it via
			// maybeHandleConnectionLoss. Reconnect already in progress means
			// the connection is known-bad.
			final ILogoLabeller current = labeller;
			if (current == null || pollHandle != null || transfersInProgress > 0
					|| (reconnectThread != null && reconnectThread.isAlive()))
				continue;

			try
			{
				current.ping();
				markActivity();
			}
			catch (Exception e)
			{
				SwingUtilities.invokeLater(() -> {
					log("✗ Keepalive failed: " + rootCause(e));
					if (autoReconnectEnabled && labeller == current)
						handleConnectionLost();
				});
				return;
			}
		}
	}

	private void onReconnectSuccess(ILogoLabeller newLabeller)
	{
		if (!autoReconnectEnabled)
		{
			try { newLabeller.close(); } catch (Exception ignored) {}
			return;
		}
		labeller = newLabeller;
		reconnectThread = null;
		log("✓ Reconnected to " + savedHost + ":" + savedPort);
		setStatus("● Connected  " + savedHost + ":" + savedPort, COL_CONNECTED);
		updateUiState(true);
		// Push the spinner's timeout into the fresh labeller — the new PL3
		// instance starts at its default SO_TIMEOUT; without this the user's
		// GUI value would be silently dropped on every reconnect.
		applyReadTimeout();
		startKeepalive();
		if (labeller instanceof LogoClientPL3 pl3)
		{
			suppressPathRefresh = true;
			comboBoxRemotePath.setSelectedItem("/c0/");
			comboBoxRemotePath.setEnabled(pl3.supportsPathPrefixedDir());
			suppressPathRefresh = false;
		}

		if (wasPollingBeforeLoss)
		{
			wasPollingBeforeLoss = false;
			pollIntervalSpinner.setValue(savedPollIntervalSecs);
			if (savedPollSavePath != null)
				logSavePath.setText(savedPollSavePath);
			log("↻ Resuming polling (interval=" + savedPollIntervalSecs + "s)");
			logPollBtn.setSelected(true);
			doTogglePoll();
		}
	}

	// =========================================================================
	// Upload
	// =========================================================================

	/**
	 * Returns the directory the file chooser should open in. Prefers the parent
	 * of {@code fieldText} if it points to an existing file, or
	 * {@code fieldText} itself if it is an existing directory, falling back to
	 * the application working directory.
	 */
	private File chooserStartDir(String fieldText)
	{
		if (!fieldText.isEmpty())
		{
			File f = new File(fieldText);
			if (f.isFile())
				return f.getParentFile();
			if (f.isDirectory())
				return f;
		}
		return new File(System.getProperty("user.dir"));
	}

	private void browseForUploadFile()
	{
		JFileChooser fc = new JFileChooser();
		fc.setCurrentDirectory(chooserStartDir(downloadLocalPath.getText().trim()));
		if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			uploadLocalPath.setText(fc.getSelectedFile().getAbsolutePath());
	}

	private void doUpload()
	{
		String localStr = uploadLocalPath.getText().trim();
		if (localStr.isEmpty())
		{
			log("No local file selected for upload.");
			return;
		}
		File file = new File(localStr);
		if (!file.exists() || !file.isFile())
		{
			log("File not found: " + localStr);
			return;
		}

		String filename = file.getName();
		String remotePath = (String) comboBoxRemotePath.getSelectedItem();

		uploadBtn.setEnabled(false);
		uploadProgress.setValue(0);
		uploadProgress.setString("Uploading…");
		transfersInProgress++;

		new SwingWorker<Void, Void>()
		{
			boolean cancelled = false;

			@Override
			protected Void doInBackground() throws Exception
			{
				if (labeller == null)
					throw new IllegalStateException("Not connected");

				// Existence check: query the specific path so the labeller filters
				// server-side (PL3 *DIR,<fullpath>,SDT / SFTP ls <fullpath>) —
				// avoids pulling the entire directory just to look for one name.
				// If the query itself fails, fall through to upload so any real
				// connection error surfaces on the upload attempt.
				boolean exists = false;
				try
				{
					for (FileInfo fi : labeller.listFiles(remotePath + filename))
					{
						if (!fi.isDirectory() && fi.getName().equalsIgnoreCase(filename))
						{
							exists = true;
							break;
						}
					}
				}
				catch (Exception ignore)
				{
					// Non-fatal — fall through to upload.
				}

				if (exists)
				{
					final boolean[] proceed = { false };
					SwingUtilities.invokeAndWait(() -> {
						int choice = JOptionPane.showConfirmDialog(
								LogoClient.this,
								"The file already exists on the labeller:\n\n"
										+ remotePath + filename
										+ "\n\nOverwrite?",
								"Confirm overwrite",
								JOptionPane.YES_NO_OPTION,
								JOptionPane.QUESTION_MESSAGE);
						proceed[0] = (choice == JOptionPane.YES_OPTION);
					});
					if (!proceed[0])
					{
						cancelled = true;
						return null;
					}
				}

				byte[] data = Files.readAllBytes(file.toPath());
				log("Uploading " + filename + " (" + data.length + " bytes) → " + remotePath + " …");

				LabellerResult r = labeller.uploadFile(remotePath + filename, data);
				if (!r.isOk())
					throw new IOException("Upload failed: " + r.getBody());
				return null;
			}

			@Override
			protected void done()
			{
				boolean handedOffToRefresh = false;
				try
				{
					get();
					if (cancelled)
					{
						log("Upload cancelled — existing file left untouched.");
						uploadProgress.setString("");
						uploadProgress.setValue(0);
						uploadBtn.setEnabled(true);
					}
					else
					{
						log("✓ Upload complete: " + filename);
						uploadProgress.setString("Upload complete");
						if (labeller instanceof LogoClientPL3)
						{
							// PL3 writes to CMOS flash after receiving the file;
							// poll *DIR with a retry loop until it responds.
							// transfersInProgress is decremented by
							// doPostUploadDirRefresh.
							doPostUploadDirRefresh(selectedFilter());
							handedOffToRefresh = true;
						}
						else
						{
							uploadProgress.setValue(100);
							uploadBtn.setEnabled(true);
							doListDirectory(selectedFilter());
						}
					}
				}
				catch (Exception ex)
				{
					log("✗ Upload failed: " + rootCause(ex));
					uploadProgress.setString("Upload failed");
					uploadBtn.setEnabled(true);
					maybeHandleConnectionLoss(ex);
				}
				finally
				{
					if (!handedOffToRefresh)
						transfersInProgress--;
					markActivity();
				}
			}
		}.execute();
	}

	/**
	 * After a TCP upload the labeller writes the received file to CMOS flash
	 * and becomes temporarily unresponsive. Poll {@code *DIR} every 3 seconds
	 * for up to 36 seconds, refreshing the directory table as soon as a
	 * response arrives.
	 */
	private void doPostUploadDirRefresh(String filter)
	{
		final int MAX_ATTEMPTS = 12;
		final int RETRY_DELAY_MS = 3_000;
		dirListBtn.setEnabled(false);
		uploadProgress.setMaximum(MAX_ATTEMPTS);
		uploadProgress.setValue(MAX_ATTEMPTS);
		uploadProgress.setString("Waiting for labeller…");

		new SwingWorker<List<String[]>, Integer>()
		{
			@Override
			protected List<String[]> doInBackground() throws Exception
			{
				IOException lastEx = null;
				for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
				{
					publish(attempt);
					try
					{
						List<FileInfo> entries = labeller.listFiles(filter);
						List<String[]> rows = new ArrayList<>();
						for (FileInfo fi : entries)
						{
							rows.add(new String[]
							{ fi.getName(), fi.isDirectory() ? "" : String.valueOf(fi.getSize()), formatEpoch(fi.getModifiedTimeEpochSeconds()), fi.isDirectory() ? "DIR" : "FILE" });
						}
						rows.sort((a, b) -> {
							if (a[3].equals(b[3]))
								return a[0].compareToIgnoreCase(b[0]);
							return "DIR".equals(a[3]) ? -1 : 1;
						});
						return rows;
					}
					catch (IOException ex)
					{
						lastEx = ex;
					}
					if (attempt < MAX_ATTEMPTS)
						Thread.sleep(RETRY_DELAY_MS);
				}
				throw new IOException("Labeller did not respond after " + MAX_ATTEMPTS + " attempts: " + lastEx);
			}

			@Override
			protected void process(List<Integer> chunks)
			{
				int attempt = chunks.get(chunks.size() - 1);
				int remaining = MAX_ATTEMPTS - attempt + 1;
				uploadProgress.setValue(remaining);
				uploadProgress.setString("Waiting for labeller… (" + attempt + "/" + MAX_ATTEMPTS + ")");
				log("Post-upload *DIR attempt " + attempt + "/" + MAX_ATTEMPTS);
			}

			@Override
			protected void done()
			{
				try
				{
					List<String[]> rows = get();
					dirModel.setRowCount(0);
					for (String[] row : rows)
					{
						Object[] r = new Object[row.length + 1];
						r[0] = Boolean.FALSE;
						System.arraycopy(row, 0, r, 1, row.length);
						dirModel.addRow(r);
					}
					dirCurrentLabel.setText("Listing:  " + filter + "  (" + rows.size() + " entries)");
					log("✓ Directory refreshed after upload: " + rows.size() + " entries");
					uploadProgress.setValue(0);
					uploadProgress.setString("");
				}
				catch (Exception ex)
				{
					log("✗ Post-upload refresh failed: " + rootCause(ex));
					uploadProgress.setString("Refresh failed");
					maybeHandleConnectionLoss(ex);
				}
				finally
				{
					dirListBtn.setEnabled(true);
					uploadBtn.setEnabled(true);
					transfersInProgress--;
					markActivity();
				}
			}
		}.execute();
	}

	// =========================================================================
	// Download
	// =========================================================================

	private void browseForDownloadFolder()
	{
		JFileChooser fc = new JFileChooser();
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fc.setCurrentDirectory(chooserStartDir(downloadLocalPath.getText().trim()));
		if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			downloadLocalPath.setText(fc.getSelectedFile().getAbsolutePath());
	}

	private void browseForLogSavePath()
	{
		JFileChooser fc = new JFileChooser();
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fc.setCurrentDirectory(chooserStartDir(logSavePath.getText().trim()));
		if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
			logSavePath.setText(fc.getSelectedFile().getAbsolutePath());
	}

	private void doDownloadSelected()
	{
		// Collect all checked non-directory rows
		List<String> filesToDownload = new ArrayList<>();
		for (int row = 0; row < dirModel.getRowCount(); row++)
		{
			if (Boolean.TRUE.equals(dirModel.getValueAt(row, 0)))
			{
				String type = (String) dirModel.getValueAt(row, 4);
				if (!"DIR".equals(type))
				{
					filesToDownload.add((String) dirModel.getValueAt(row, 1));
				}
			}
		}

		if (filesToDownload.isEmpty())
		{
			log("No files checked for download.");
			return;
		}

		String localDir = downloadLocalPath.getText().trim();
		if (localDir.isEmpty())
		{
			JFileChooser fc = new JFileChooser();
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			fc.setCurrentDirectory(chooserStartDir(downloadLocalPath.getText().trim()));
			if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
				return;
			localDir = fc.getSelectedFile().getAbsolutePath();
			downloadLocalPath.setText(localDir);
		}

		// Overwrite confirmation — collect names that already exist locally
		List<String> existing = new ArrayList<>();
		for (String name : filesToDownload)
		{
			if (new File(localDir, name).exists())
				existing.add(name);
		}
		if (!existing.isEmpty())
		{
			String message = (existing.size() == 1)
					? "The file already exists in the download folder:\n\n"
							+ existing.get(0) + "\n\nOverwrite?"
					: existing.size() + " files already exist in the download folder:\n\n"
							+ String.join("\n", existing)
							+ "\n\nOverwrite all existing files?";
			int choice = JOptionPane.showOptionDialog(this, message,
					"Confirm overwrite",
					JOptionPane.YES_NO_CANCEL_OPTION,
					JOptionPane.QUESTION_MESSAGE,
					null,
					new String[] { "Overwrite", "Skip existing", "Cancel" },
					"Skip existing");
			if (choice == 2 || choice == JOptionPane.CLOSED_OPTION)
			{
				log("Download cancelled.");
				return;
			}
			if (choice == 1)
			{
				filesToDownload.removeAll(existing);
				if (filesToDownload.isEmpty())
				{
					log("No new files to download.");
					return;
				}
				log("Skipping " + existing.size() + " existing file(s).");
			}
		}

		final String finalLocalDir = localDir;
		final List<String> finalFiles = new ArrayList<>(filesToDownload);
		final String curPath = currentPath();

		downloadBtn.setEnabled(false);
		transfersInProgress++;
		log("Downloading " + finalFiles.size() + " file(s)…");

		new SwingWorker<Void, String>()
		{
			@Override
			protected Void doInBackground() throws Exception
			{
				for (String name : finalFiles)
				{
					String remoteFull = (curPath.endsWith("/") ? curPath : curPath + "/") + name;
					File dest = new File(finalLocalDir, name);
					if (labeller == null)
						throw new IllegalStateException("Not connected");
					byte[] data = labeller.downloadFile(remoteFull);
					Files.write(dest.toPath(), data);
					publish("✓ Downloaded " + data.length + " bytes → " + dest.getAbsolutePath());
				}
				return null;
			}

			@Override
			protected void process(List<String> messages)
			{
				for (String msg : messages)
					log(msg);
			}

			@Override
			protected void done()
			{
				try
				{
					get();
					log("✓ All downloads complete (" + finalFiles.size() + " file(s))");
				}
				catch (Exception ex)
				{
					log("✗ Download failed: " + rootCause(ex));
					maybeHandleConnectionLoss(ex);
				}
				finally
				{
					downloadBtn.setEnabled(true);
					transfersInProgress--;
					markActivity();
				}
			}
		}.execute();
	}

	// =========================================================================
	// Directory listing
	// =========================================================================

	private void doListDirectory(String path)
	{
		if (path == null || path.isEmpty())
			path = "/";
		final String finalPath = path;
		dirModel.setRowCount(0);
		dirCurrentLabel.setText("Listing:  " + finalPath + "  (loading…)");
		dirListBtn.setEnabled(false);
		transfersInProgress++;

		new SwingWorker<List<String[]>, Void>()
		{
			@Override
			protected List<String[]> doInBackground() throws Exception
			{
				if (labeller == null)
					throw new IllegalStateException("Not connected");
				List<FileInfo> entries = labeller.listFiles(finalPath);
				List<String[]> rows = new ArrayList<>();
				for (FileInfo fi : entries)
				{
					rows.add(new String[]
					{ fi.getName(), fi.isDirectory() ? "" : String.valueOf(fi.getSize()), formatEpoch(fi.getModifiedTimeEpochSeconds()), fi.isDirectory() ? "DIR" : "FILE" });
				}
				rows.sort((a, b) -> {
					if (a[3].equals(b[3]))
						return a[0].compareToIgnoreCase(b[0]);
					return "DIR".equals(a[3]) ? -1 : 1;
				});
				return rows;
			}

			@Override
			protected void done()
			{
				try
				{
					List<String[]> rows = get();
					dirModel.setRowCount(0);
					for (String[] row : rows)
					{
						Object[] r = new Object[row.length + 1];
						r[0] = Boolean.FALSE;
						System.arraycopy(row, 0, r, 1, row.length);
						dirModel.addRow(r);
					}
					dirCurrentLabel.setText("Listing:  " + finalPath + "  (" + rows.size() + " entries)");
					log("Directory listed: " + rows.size() + " entries  [filter: " + finalPath + "]");
				}
				catch (Exception ex)
				{
					log("✗ Directory listing failed: " + rootCause(ex));
					maybeHandleConnectionLoss(ex);
				}
				finally
				{
					dirListBtn.setEnabled(true);
					transfersInProgress--;
					markActivity();
				}
			}
		}.execute();
	}

	// =========================================================================
	// Pallet log polling
	// =========================================================================

	private void doTogglePoll()
	{
		if (labeller == null)
		{
			log("Not connected.");
			logPollBtn.setSelected(false);
			return;
		}

		if (logPollBtn.isSelected())
		{
			// ── Start polling ─────────────────────────────────────────────────
			logPollBtn.setIcon(Common.icon_clock5);
			logPollBtn.setToolTipText("Polling Running");
			int intervalSecs = (Integer) pollIntervalSpinner.getValue();
			pollIntervalSpinner.setEnabled(false);
			logSavePath.setEnabled(false);
			btnBrowseLogPath.setEnabled(false);

			String saveDir = logSavePath.getText().trim();
			final java.nio.file.Path savePath = saveDir.isEmpty() ? null : java.nio.file.Path.of(saveDir);

			log("⏱ Polling started  (interval=" + intervalSecs + "s"
					+ (savePath != null ? "  save=" + savePath : "") + ")");

			pollHandle = labeller.startContinuousPolling(
				savePath,
				intervalSecs,
				entries -> SwingUtilities.invokeLater(() ->
				{
					markActivity();
					log("─── Pallet Log ─── (" + entries.size() + " entries)");
					for (PalletLogEntry e : entries)
					{
						StringBuilder sb = new StringBuilder(e.getRawData());
						if (e.getTimestamp() != null)
							sb.append("  [").append(e.getTimestamp()).append(']');
						if (e.getSequence() >= 0)
							sb.append("  seq=").append(e.getSequence());
						log(sb.toString());
					}
					log("─── End Log ───");
					if (savePath != null)
						log("✓ " + entries.size() + " entr"
								+ (entries.size() == 1 ? "y" : "ies") + " saved to " + savePath);
				}),
				err -> SwingUtilities.invokeLater(() ->
				{
					log("✗ Poll error: " + rootCause(err));
					if (autoReconnectEnabled && isConnectionLoss(err))
						handleConnectionLost();
				})
			);
		}
		else
		{
			// ── Stop polling ──────────────────────────────────────────────────
			logPollBtn.setIcon(Common.icon_clock5);
			stopPollIfRunning();
			log("■ Polling stopped.");
		}
	}

	/** Stops any running poll loop and resets the toggle button to its idle state. */
	private void stopPollIfRunning()
	{
		if (pollHandle != null)
		{
			pollHandle.stop();
			pollHandle = null;
		}
		logPollBtn.setSelected(false);
		logPollBtn.setToolTipText("Polling Stopped");
		if (pollIntervalSpinner != null)
			pollIntervalSpinner.setEnabled(true);
		if (logSavePath != null)
		{
			logSavePath.setEnabled(true);
			btnBrowseLogPath.setEnabled(true);
		}
	}

	// =========================================================================
	// UI state helpers
	// =========================================================================

	private void updateUiState(boolean connected)
	{
		boolean needsCreds = !TYPE_PL3.equals(typeCombo.getSelectedItem());

		connectBtn.setEnabled(true);
		connectBtn.setSelected(connected);
		connectBtn.setIcon(connected ? Common.icon_connected : Common.icon_disconnected);
		connectBtn.setToolTipText(connected ? "Disconnect" : "Connect");
		typeCombo.setEnabled(!connected);
		hostCombo.setEnabled(!connected);
		portCombo.setEnabled(!connected);

		userField.setEnabled(!connected && needsCreds);
		passField.setEnabled(!connected && needsCreds);

		uploadBtn.setEnabled(connected);
		downloadBtn.setEnabled(connected);
		selectAllBtn.setEnabled(connected);
		unselectAllBtn.setEnabled(connected);
		dirListBtn.setEnabled(connected);
		logPollBtn.setEnabled(connected);
		btnMsgSend.setEnabled(connected);
		if (!connected)
			stopPollIfRunning();

		// Delete requires at least one file checkbox to be ticked;
		// updateDeleteBtn() handles the fine-grained check — just ensure it is
		// off when
		// disconnected so a stale enabled state cannot survive across sessions.
		if (!connected)
			deleteBtn.setEnabled(false);

		if (!connected)
		{
			dirModel.setRowCount(0);
			dirCurrentLabel.setText("Listing:  (not connected)");
			uploadProgress.setValue(0);
			uploadProgress.setString("");
			comboBoxRemotePath.setEnabled(true);
		}
	}

	// =========================================================================
	// Messages pane actions
	// =========================================================================

	private void doMsgOpen()
	{
		File dir = new File(downloadLocalPath.getText());
		JFileChooser chooser = new JFileChooser(dir.exists() ? dir : new File(System.getProperty("user.dir")));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		File f = chooser.getSelectedFile();
		try
		{
			String content = Files.readString(f.toPath(), StandardCharsets.ISO_8859_1);
			// Normalise every terminator to LF so JTextArea renders line breaks
			// regardless of source format — labeller LLF files are CR-only and
			// would otherwise collapse to a single line.
			content = content.replaceAll("\\r\\n|\\r|\\n", "\n");
			messagesField.setText(content);
			messagesField.setCaretPosition(0);
			currentMessageFile = f.getName();
			downloadLocalPath.setText(f.getParent());
			lblMessageFile.setText(f.getAbsolutePath());
			log("Opened " + f.getAbsolutePath());
		}
		catch (IOException ex)
		{
			log("✗ Open failed: " + rootCause(ex));
		}
	}

	private void doMsgClear()
	{
		messagesField.setText("");
		lblMessageFile.setText(" ");
		currentMessageFile = null;
	}

	private void doMsgSave()
	{
		if (currentMessageFile == null)
		{
			doMsgSaveAs();
			return;
		}
		File f = new File(downloadLocalPath.getText(), currentMessageFile);
		try
		{
			Files.writeString(f.toPath(), applyEolCombo(messagesField.getText()), StandardCharsets.ISO_8859_1);
			lblMessageFile.setText(f.getAbsolutePath());
			log("Saved " + f.getAbsolutePath());
		}
		catch (IOException ex)
		{
			log("✗ Save failed: " + rootCause(ex));
		}
	}

	private void doMsgSaveAs()
	{
		File dir = new File(downloadLocalPath.getText());
		JFileChooser chooser = new JFileChooser(dir.exists() ? dir : new File(System.getProperty("user.dir")));
		if (currentMessageFile != null)
			chooser.setSelectedFile(new File(dir, currentMessageFile));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		File f = chooser.getSelectedFile();
		try
		{
			Files.writeString(f.toPath(), applyEolCombo(messagesField.getText()), StandardCharsets.ISO_8859_1);
			currentMessageFile = f.getName();
			downloadLocalPath.setText(f.getParent());
			lblMessageFile.setText(f.getAbsolutePath());
			log("Saved " + f.getAbsolutePath());
		}
		catch (IOException ex)
		{
			log("✗ Save failed: " + rootCause(ex));
		}
	}

	private void doSaveTextPane(String text, String defaultStem)
	{
		File dir = new File(downloadLocalPath.getText());
		JFileChooser chooser = new JFileChooser(dir.exists() ? dir : new File(System.getProperty("user.dir")));
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
				.format(Instant.now().atZone(ZoneId.systemDefault()));
		chooser.setSelectedFile(new File(dir, defaultStem + "_" + stamp + ".txt"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		File f = chooser.getSelectedFile();
		try
		{
			Files.writeString(f.toPath(), text, StandardCharsets.ISO_8859_1);
			log("Saved " + f.getAbsolutePath());
		}
		catch (IOException ex)
		{
			log("✗ Save failed: " + rootCause(ex));
		}
	}

	/**
	 * Replaces {@code <NAME>} tokens (case-insensitive) with the corresponding
	 * ASCII control byte. Unknown tokens are passed through unchanged so they
	 * remain visible in the wire trace for the user to investigate.
	 */
	private static String substituteControlTokens(String s)
	{
		Matcher m = CONTROL_TOKEN_PATTERN.matcher(s);
		StringBuilder sb = new StringBuilder();
		while (m.find())
		{
			Character ch = CONTROL_TOKENS.get(m.group(1).toUpperCase());
			String replacement = (ch != null) ? String.valueOf(ch) : m.group();
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private void doMsgSend()
	{
		if (!(labeller instanceof LogoClientPL3 pl3))
		{
			log("✗ Transmit requires a PL3 (TCP) connection");
			return;
		}
		String eolLabel = (String) eolCombo.getSelectedItem();
		String raw = messagesField.getText();
		if (raw.isEmpty())
			return;

		// 1. Line-ending normalisation (skip for <NONE> — preserve source bytes)
		String eolApplied;
		String eol = null;
		if ("<NONE>".equals(eolLabel))
		{
			eolApplied = raw;
		}
		else
		{
			eol = switch (eolLabel)
			{
				case "<LF>"     -> "\n";
				case "<CR><LF>" -> "\r\n";
				default         -> "\r";
			};
			eolApplied = raw.replaceAll("\\r\\n|\\r|\\n", eol);
		}

		// 2. Resolve <STX>/<ETX>/<TAB>/etc. tokens to their ASCII bytes
		String resolved = substituteControlTokens(eolApplied);

		// 3. Ensure the payload ends with the selected EOL so single-line
		// commands like "*VERBOSE" are terminated even when the user hasn't
		// typed a trailing <CR> or newline. Skipped when EOL is <NONE>.
		if (eol != null && !resolved.isEmpty())
		{
			char last = resolved.charAt(resolved.length() - 1);
			if (last != '\r' && last != '\n')
				resolved = resolved + eol;
		}

		final String payload = resolved;

		btnMsgSend.setEnabled(false);
		new SwingWorker<Void, Void>()
		{
			@Override
			protected Void doInBackground() throws Exception
			{
				// Fire-and-forget: in verbose mode there's no binary ACK/NAK
				// terminator, so waiting for a response always times out.
				// Bytes coming back will still appear in the wire pane via
				// the tee as they're drained off the socket.
				pl3.sendTextNoReply(payload, 750);
				return null;
			}

			@Override
			protected void done()
			{
				try
				{
					get();
					log("✓ Transmitted " + payload.length() + " bytes (" + eolLabel + ")");
				}
				catch (Exception ex)
				{
					log("✗ Transmit failed: " + rootCause(ex));
				}
				btnMsgSend.setEnabled(labeller != null && labeller.isConnected());
			}
		}.execute();
	}

	private void doQuit()
	{
		int choice = JOptionPane.showConfirmDialog(this, "Are you sure you want to quit?", "Confirm Quit", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (choice != JOptionPane.YES_OPTION)
			return;

		saveState();

		if (transfersInProgress > 0)
		{
			log("Waiting for " + transfersInProgress + " transfer(s) to complete before closing…");
			setStatus("● Waiting for transfers to finish…", COL_CONNECTING);
			quitBtn.setEnabled(false);
		}

		new SwingWorker<Void, Void>()
		{
			@Override
			protected Void doInBackground() throws Exception
			{
				// Poll on a background thread until all transfers have
				// finished.
				while (transfersInProgress > 0)
				{
					Thread.sleep(200);
				}
				cleanDisconnect();
				return null;
			}

			@Override
			protected void done()
			{
				System.exit(0);
			}
		}.execute();
	}

	private void setAllChecked(boolean checked)
	{
		for (int row = 0; row < dirModel.getRowCount(); row++)
		{
			String type = (String) dirModel.getValueAt(row, 4);
			if (!"DIR".equals(type))
			{
				dirModel.setValueAt(checked, row, 0);
			}
		}
	}

	/**
	 * Enable the Delete button only when connected and at least one
	 * non-directory row is ticked.
	 */
	private void updateDeleteBtn()
	{
		if (labeller == null)
		{
			deleteBtn.setEnabled(false);
			return;
		}
		for (int row = 0; row < dirModel.getRowCount(); row++)
		{
			if (Boolean.TRUE.equals(dirModel.getValueAt(row, 0)))
			{
				String type = (String) dirModel.getValueAt(row, 4);
				if (!"DIR".equals(type))
				{
					deleteBtn.setEnabled(true);
					return;
				}
			}
		}
		deleteBtn.setEnabled(false);
	}

	private void doDeleteSelected()
	{
		List<String> toDelete = new ArrayList<>();
		for (int row = 0; row < dirModel.getRowCount(); row++)
		{
			if (Boolean.TRUE.equals(dirModel.getValueAt(row, 0)))
			{
				String type = (String) dirModel.getValueAt(row, 4);
				if (!"DIR".equals(type))
				{
					toDelete.add((String) dirModel.getValueAt(row, 1));
				}
			}
		}
		if (toDelete.isEmpty())
			return;

		final String curPath = currentPath();
		// currentPath() returns the full filter spec (e.g. "/c0/*.*"); strip
		// the
		// wildcard/filename component to keep only the directory portion.
		final String prefix;
		int lastSlash = curPath.lastIndexOf('/');
		if (lastSlash >= 0)
		{
			prefix = curPath.substring(0, lastSlash + 1); // e.g. "/c0/"
		}
		else
		{
			prefix = curPath.endsWith("/") ? curPath : curPath + "/";
		}

		// Confirm each file individually before sending any delete commands
		List<String> confirmed = new ArrayList<>();
		for (String name : toDelete)
		{
			int choice = JOptionPane.showConfirmDialog(this, "Are you sure you want to permanently delete:\n\n  " + prefix + name + "\n\nThis action cannot be undone.", "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				confirmed.add(name);
			}
		}
		if (confirmed.isEmpty())
			return;

		deleteBtn.setEnabled(false);
		log("Deleting " + confirmed.size() + " file(s)…");
		transfersInProgress++;

		new SwingWorker<Void, String>()
		{
			@Override
			protected Void doInBackground() throws Exception
			{
				for (String name : confirmed)
				{
					String full = prefix + name;
					LabellerResult resp = labeller.deleteFile(full);
					if (resp.isOk())
					{
						publish("✓ Deleted " + full);
					}
					else
					{
						publish("✗ Delete failed for " + full + " (error " + resp.getErrorCode() + ")");
					}
				}
				return null;
			}

			@Override
			protected void process(List<String> messages)
			{
				for (String msg : messages)
					log(msg);
			}

			@Override
			protected void done()
			{
				try
				{
					get();
				}
				catch (Exception ex)
				{
					log("✗ Delete operation failed: " + rootCause(ex));
					maybeHandleConnectionLoss(ex);
				}
				finally
				{
					transfersInProgress--;
					markActivity();
				}
				doListDirectory(curPath);
			}
		}.execute();
	}

	private String selectedFilter()
	{
		String path = (String) comboBoxRemotePath.getSelectedItem();
		if (labeller instanceof LogoClientPL3)
		{
			Object sel = dirFilterCombo.getSelectedItem();
			String text = sel == null ? "*.*" : sel.toString().trim();
			String mask = text.isEmpty() ? "*.*" : text;
			for (String[] pair : DIR_FILTERS)
				if (pair[0].equals(text))
				{
					mask = pair[1];
					break;
				}
			return (path != null ? path : "/c0/") + mask;
		}
		// PL6: just the directory path
		return path != null ? path : Pl6Paths.LAYOUTS_DIR;
	}

	private String currentPath()
	{
		String text = dirCurrentLabel.getText();
		int start = text.indexOf("Listing:  ");
		if (start >= 0)
		{
			text = text.substring(start + "Listing:  ".length());
			int paren = text.indexOf("  (");
			if (paren >= 0)
				text = text.substring(0, paren);
			return text.trim();
		}
		return selectedFilter();
	}

	private void setStatus(String text, Color color)
	{
		SwingUtilities.invokeLater(() -> {
			statusLabel.setText(text);
		});
	}

	// =========================================================================
	// Logging
	// =========================================================================

	private void log(String message)
	{
		SwingUtilities.invokeLater(() -> {
			String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
			activityLog.append("[" + ts + "]  " + message + "\n");
			activityLog.setCaretPosition(activityLog.getDocument().getLength());
		});
	}

	/**
	 * Diagnostic wireLog messages. The raw TX/RX bytes already render in the
	 * wire pane as tokens via the byte-level tee, so the summary-style
	 * {@code >>> TX ...} and {@code <<< RX ...} lines are suppressed here to
	 * avoid cluttering the activity log. Connection events, timeouts, drains
	 * and errors ({@code ---} and {@code !!!}) are retained because they are
	 * useful for diagnosing comms issues.
	 */
	private void wireTrace(String message)
	{
		if (message.startsWith(">>>") || message.startsWith("<<<"))
			return;
		log(message);
	}

	/** Byte-level TX hook from the PL3 tee stream (may run off the EDT). */
	private void wireTxBytes(byte[] bytes)
	{
		appendWireBytes(bytes, true);
	}

	/** Byte-level RX hook from the PL3 tee stream (may run off the EDT). */
	private void wireRxBytes(byte[] bytes)
	{
		appendWireBytes(bytes, false);
	}

	private static final DateTimeFormatter WIRE_TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

	/** Lookup table: byte value 0–255 → human-readable token / character. */
	private static final String[] BYTE_TO_TOKEN = buildByteToTokenTable();

	private static String[] buildByteToTokenTable()
	{
		String[] t = new String[256];
		String[] c0 = {
			"<NUL>","<SOH>","<STX>","<ETX>","<EOT>","<ENQ>","<ACK>","<BEL>",
			"<BS>", "<TAB>","<LF>", "<VT>", "<FF>", "<CR>", "<SO>", "<SI>",
			"<DLE>","<DC1>","<DC2>","<DC3>","<DC4>","<NAK>","<SYN>","<ETB>",
			"<CAN>","<EM>", "<SUB>","<ESC>","<FS>", "<GS>", "<RS>", "<US>"
		};
		for (int i = 0; i < 32; i++) t[i] = c0[i];
		for (int i = 32; i < 127; i++) t[i] = String.valueOf((char) i);
		t[127] = "<DEL>";
		for (int i = 128; i < 256; i++) t[i] = String.format("<x%02X>", i);
		return t;
	}

	private void appendWireBytes(byte[] bytes, boolean isTx)
	{
		if (bytes == null || bytes.length == 0) return;
		SwingUtilities.invokeLater(() -> {
			if (wireTraceToggle != null && !wireTraceToggle.isSelected()) return;
			appendWireBytesOnEdt(bytes, isTx);
		});
	}

	private void appendWireBytesOnEdt(byte[] bytes, boolean isTx)
	{
		final int dir = isTx ? 1 : 2;
		final javax.swing.text.SimpleAttributeSet attr = isTx ? wireAttrTx : wireAttrRx;
		final StringBuilder sinceEol = isTx ? wireTxSinceEol : wireRxSinceEol;
		final String eolSeq = currentEolSequence();   // null if <NONE>

		try
		{
			// New burst: direction change or first-ever bytes. Put the timestamp
			// (if enabled) at the start of its own line.
			boolean newBurst = (wireLastDirection != dir);
			if (newBurst)
			{
				if (wireDoc.getLength() > 0 && !docEndsWithNewline())
					wireDoc.insertString(wireDoc.getLength(), "\n", null);
				if (btnWireTimestamp.isSelected())
				{
					String ts = "[" + LocalTime.now().format(WIRE_TS_FMT) + "]";
					wireDoc.insertString(wireDoc.getLength(), ts, wireAttrTs);
				}
				sinceEol.setLength(0);
			}

			for (byte b : bytes)
			{
				String token = BYTE_TO_TOKEN[b & 0xFF];
				wireDoc.insertString(wireDoc.getLength(), token, attr);
				sinceEol.append(token);
				if (sinceEol.length() > 32)
					sinceEol.delete(0, sinceEol.length() - 32);

				if (eolSeq != null && endsWith(sinceEol, eolSeq))
				{
					wireDoc.insertString(wireDoc.getLength(), "\n", null);
					sinceEol.setLength(0);
				}
			}

			wireLastDirection = dir;
			wirePane.setCaretPosition(wireDoc.getLength());
		}
		catch (Exception ignored)
		{
		}
	}

	private boolean docEndsWithNewline()
	{
		int len = wireDoc.getLength();
		if (len == 0) return true;
		try { return "\n".equals(wireDoc.getText(len - 1, 1)); }
		catch (Exception e) { return false; }
	}

	private static boolean endsWith(StringBuilder sb, String suffix)
	{
		int n = sb.length(), m = suffix.length();
		if (n < m) return false;
		for (int i = 0; i < m; i++)
			if (sb.charAt(n - m + i) != suffix.charAt(i)) return false;
		return true;
	}

	/** Returns the current EOL token sequence, or {@code null} for {@code <NONE>}. */
	private String currentEolSequence()
	{
		String s = (String) eolCombo.getSelectedItem();
		if (s == null || "<NONE>".equals(s)) return null;
		return s;
	}

	/**
	 * Applies the EOL combo's current selection to {@code text}, replacing every
	 * CR/LF/CRLF with the chosen terminator. Returns {@code text} unchanged when
	 * the combo is set to {@code <NONE>}.
	 */
	private String applyEolCombo(String text)
	{
		String eolLabel = (String) eolCombo.getSelectedItem();
		if ("<NONE>".equals(eolLabel)) return text;
		String eol = switch (eolLabel)
		{
			case "<LF>"     -> "\n";
			case "<CR><LF>" -> "\r\n";
			default         -> "\r";
		};
		return text.replaceAll("\\r\\n|\\r|\\n", eol);
	}

	/**
	 * Re-registers wire loggers on the live labeller according to the current
	 * wireTraceToggle state. Called when the toggle is clicked.
	 */
	private void applyWireLoggers()
	{
		if (labeller == null) return;
		boolean on = wireTraceToggle.isSelected();
		labeller.setWireLogger(on ? LogoClient.this::wireTrace : null);
		if (labeller instanceof LogoClientPL3 pl3)
			pl3.setRawLoggers(on ? LogoClient.this::wireTxBytes : null,
			                  on ? LogoClient.this::wireRxBytes : null);
	}

	// =========================================================================
	// Utility
	// =========================================================================

	/**
	 * Format an epoch-seconds timestamp for display; returns "" for
	 * zero/negative values.
	 */
	private static String formatEpoch(long epochSecs)
	{
		if (epochSecs <= 0)
			return "";
		return Instant.ofEpochSecond(epochSecs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	// =========================================================================
	// State persistence
	// =========================================================================

	private void saveState()
	{
		try
		{
			STATE_FILE.getParentFile().mkdirs();
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
			Element root = doc.createElement("state");
			doc.appendChild(root);

			addXmlElement(doc, root, "type",        stateStr(typeCombo.getSelectedItem()));
			addXmlElement(doc, root, "port",        stateStr(portCombo.getSelectedItem()));
			addXmlElement(doc, root, "host",        String.valueOf(hostCombo.getSelectedItem()).trim());
			addXmlElement(doc, root, "user",        userField.getText().trim());
			addXmlElement(doc, root, "remotePath",  stateStr(comboBoxRemotePath.getSelectedItem()));
			addXmlElement(doc, root, "localPath",   downloadLocalPath.getText().trim());
			addXmlElement(doc, root, "logSavePath", logSavePath.getText().trim());
			addXmlElement(doc, root, "filter",      stateStr(dirFilterCombo.getSelectedItem()));
			addXmlElement(doc, root, "autoDir",             String.valueOf(autoDirToggle.isSelected()));
			addXmlElement(doc, root, "heartbeat",           String.valueOf(heartbeatToggle.isSelected()));
			addXmlElement(doc, root, "heartbeatIntervalMs", String.valueOf(heartbeatIntervalSpinner.getValue()));
			addXmlElement(doc, root, "eol",                 stateStr(eolCombo.getSelectedItem()));
			addXmlElement(doc, root, "timeoutMs",           String.valueOf(timeoutSpinner.getValue()));
			addXmlElement(doc, root, "wireTimestamps",      String.valueOf(btnWireTimestamp.isSelected()));

			var tf = TransformerFactory.newInstance().newTransformer();
			tf.setOutputProperty(OutputKeys.INDENT, "yes");
			tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
			tf.transform(new DOMSource(doc), new StreamResult(STATE_FILE));
		}
		catch (Exception ex)
		{
			log("Warning: could not save state: " + ex.getMessage());
		}
	}

	private void loadState()
	{
		if (!STATE_FILE.exists())
			return;
		try
		{
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(STATE_FILE);
			doc.getDocumentElement().normalize();

			String type        = xmlText(doc, "type");
			String port        = xmlText(doc, "port");
			String host        = xmlText(doc, "host");
			String user        = xmlText(doc, "user");
			String remotePath  = xmlText(doc, "remotePath");
			String localPath   = xmlText(doc, "localPath");
			String logSaveDir  = xmlText(doc, "logSavePath");
			String filter      = xmlText(doc, "filter");

			// 1. Type — triggers onTypeChanged() which repopulates port and remotePath combos
			if (!type.isEmpty())
				typeCombo.setSelectedItem(type);

			// 2. Port — override what onTypeChanged() populated
			if (!port.isEmpty())
				portCombo.setSelectedItem(port);

			// 3. Host
			if (!host.isEmpty())
				hostCombo.setSelectedItem(host);

			// 4. User (only meaningful for PL6, but harmless to restore regardless)
			if (!user.isEmpty())
				userField.setText(user);

			// 5. Remote path — labeller is null so the ActionListener won't fire a listing;
			//    add the path to the combo if it isn't already there (e.g. a navigated sub-dir)
			if (!remotePath.isEmpty())
			{
				boolean found = false;
				for (int i = 0; i < comboBoxRemotePath.getItemCount(); i++)
					if (remotePath.equals(comboBoxRemotePath.getItemAt(i)))
					{
						found = true;
						break;
					}
				if (!found)
					comboBoxRemotePath.addItem(remotePath);
				suppressPathRefresh = true;
				comboBoxRemotePath.setSelectedItem(remotePath);
				suppressPathRefresh = false;
			}

			// 6. Local download path
			if (!localPath.isEmpty())
				downloadLocalPath.setText(localPath);

			// 7. Log save path
			if (!logSaveDir.isEmpty())
				logSavePath.setText(logSaveDir);

			// 8. Filter — labeller is null so the ActionListener won't fire a listing
			if (!filter.isEmpty())
				dirFilterCombo.setSelectedItem(filter);

			// 9. Auto-DIR / heartbeat preferences
			String autoDir      = xmlText(doc, "autoDir");
			String heartbeat    = xmlText(doc, "heartbeat");
			String hbIntervalMs = xmlText(doc, "heartbeatIntervalMs");
			if (!autoDir.isEmpty())
				autoDirToggle.setSelected(Boolean.parseBoolean(autoDir));
			if (!heartbeat.isEmpty())
			{
				heartbeatToggle.setSelected(Boolean.parseBoolean(heartbeat));
				heartbeatEnabled = heartbeatToggle.isSelected();
			}
			if (!hbIntervalMs.isEmpty())
			{
				try
				{
					int v = Integer.parseInt(hbIntervalMs);
					heartbeatIntervalSpinner.setValue(v);
					heartbeatIntervalMs = v;
				}
				catch (NumberFormatException ignored) {}
			}

			// 10. EOL terminator
			String eol = xmlText(doc, "eol");
			if (!eol.isEmpty())
				eolCombo.setSelectedItem(eol);

			// 11. Socket read timeout
			String timeoutMs = xmlText(doc, "timeoutMs");
			if (!timeoutMs.isEmpty())
			{
				try { timeoutSpinner.setValue(Integer.parseInt(timeoutMs)); }
				catch (NumberFormatException ignored) {}
			}

			// 12. Wire-pane timestamp toggle
			String wireTs = xmlText(doc, "wireTimestamps");
			if (!wireTs.isEmpty())
				btnWireTimestamp.setSelected(Boolean.parseBoolean(wireTs));
		}
		catch (Exception ex)
		{
			// Silently ignore a missing or corrupt state file
		}
	}

	private void addXmlElement(Document doc, Element parent, String tag, String value)
	{
		Element el = doc.createElement(tag);
		el.setTextContent(value);
		parent.appendChild(el);
	}

	private String xmlText(Document doc, String tag)
	{
		NodeList nl = doc.getElementsByTagName(tag);
		if (nl.getLength() == 0)
			return "";
		var child = nl.item(0).getFirstChild();
		return child == null ? "" : child.getNodeValue().trim();
	}

	private String stateStr(Object obj)
	{
		return obj == null ? "" : obj.toString().trim();
	}

	private String rootCause(Throwable ex)
	{
		Throwable c = ex;
		while (c.getCause() != null)
			c = c.getCause();
		String msg = c.getMessage();
		return (msg != null && !msg.isBlank()) ? msg : c.getClass().getSimpleName();
	}
}
