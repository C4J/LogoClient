package com.commander4j.client.pl3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.commander4j.client.common.AbstractLogoLabeller;
import com.commander4j.client.common.FileInfo;
import com.commander4j.client.common.ILogoLabeller;
import com.commander4j.client.common.LabellerResult;
import com.commander4j.client.common.PalletLogEntry;

/**
 * TCP client for the Logopak PowerLeap III labeller (VCOMSVR).
 * Independent compatibility implementation for local testing — not a Logopak
 * product; see README.txt.
 *
 * <p>Supports all three LEAP command layers:
 * <ul>
 *   <li><b>LEAP FFP</b> – layout management, field data, print control (no prefix)</li>
 *   <li><b>LAMA / LACE</b> – file management, time/counters, firmware, logging
 *       ({@code *} prefix)</li>
 *   <li><b>LSP / LOGOSTEP</b> – I/O monitoring and control ({@code &} prefix)</li>
 * </ul>
 *
 * <h2>Connection model</h2>
 * <p>The labeller acts as the TCP server (VCOMSVR). Open one persistent connection
 * per data port and keep it open for the labeller's lifetime. Do not open and close
 * the socket on a per-command basis. Only one connection per data port is allowed
 * at a time.
 *
 * <h2>Response format</h2>
 * <pre>
 *   000&lt;ACK&gt;  (0x06) – success
 *   xxx&lt;NAK&gt;  (0x15) – failure; xxx is a 3-digit decimal error code
 * </pre>
 * Every command must have its response read before the next command is sent.
 * This class does that automatically.
 *
 * <h2>Block commands (STX/ETX)</h2>
 * <p>Multiple LEAP FFP commands can be combined into a single atomic block using
 * {@link #sendBlock(List)}. One ACK/NAK is returned for the whole block. The
 * {@code P} (print) command is not permitted inside a block and must be sent
 * separately.
 *
 * <h2>Asynchronous state reporting</h2>
 * <p>When {@link #lspEnableReporting(Consumer)} is called, a background reader
 * thread is started. Unsolicited {@code STATE,name=value} push messages from the
 * labeller are dispatched to the registered listener on that thread. In this mode
 * {@link #sendRaw} uses an internal queue to decouple sending from receiving so
 * that async pushes do not corrupt synchronous command responses.
 *
 * <h2>Connection deadlock recovery</h2>
 * <p>If the labeller refuses a new connection because it thinks the previous one
 * is still open, call {@link #resetConnection()} before retrying {@link #connect()}.
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * try (LogoClientPL3 lab = new LogoClientPL3("192.168.1.100", LogoClientPL3.PORT_XA)) {
 *     lab.connect();
 *     lab.activateLayout("label1");
 *     lab.sendFieldData(1, "BATCH-001");
 *     lab.sendFieldData(2, "2024-08-27");
 *     LabellerResponse r = lab.printLabel();
 *     if (!r.isOk()) System.err.println("Print failed: " + r);
 * }
 * }</pre>
 */
public class LogoClientPL3 extends AbstractLogoLabeller implements ILogoLabeller {

    // =========================================================================
    // Data port constants
    // =========================================================================

    /** Data socket XA (primary). Corresponding reset port: 9084. */
    public static final int PORT_XA = 8000;
    /** Data socket XB. Corresponding reset port: 9184. */
    public static final int PORT_XB = 8100;
    /** Data socket XC. Corresponding reset port: 9284. */
    public static final int PORT_XC = 8200;
    /** Data socket XD. Corresponding reset port: 9384. */
    public static final int PORT_XD = 8300;

    // =========================================================================
    // Protocol constants
    // =========================================================================

    private static final byte BYTE_STX = 0x02;   // start of STX/ETX block
    private static final byte BYTE_ETX = 0x03;   // end of STX/ETX block
    private static final int  BYTE_ACK = 0x06;   // success response
    private static final int  BYTE_NAK = 0x15;   // error response
    private static final byte BYTE_CR  = 0x0D;   // command terminator

    // =========================================================================
    // Configuration
    // =========================================================================

    private static final int DEFAULT_CONNECT_TIMEOUT_MS      = 3_000;
    private static final int DEFAULT_SO_TIMEOUT_MS           = 5_000;
    private static final int DEFAULT_TRANSFER_SO_TIMEOUT_MS  = 5_000;   // for *TYPEHEX / *HEXFILE
    private static final int ASYNC_RESPONSE_TIMEOUT_MS       = 10_000;

    /**
     * Number of data bytes per Intel HEX record when encoding files for upload.
     * The Logopak protocol allows up to 255 (0xFF); 32 bytes is the standard
     * compromise between line length and address overhead.
     */
    public static final int INTEL_HEX_RECORD_SIZE = 32;

    // =========================================================================
    // State
    // =========================================================================

    // host and wireLogger are inherited from AbstractLogoLabeller
    private final int    dataPort;
    private final int    resetPort;

    /** Configurable socket read timeout. Starts at {@link #DEFAULT_SO_TIMEOUT_MS}. */
    private int          configuredSoTimeoutMs = DEFAULT_SO_TIMEOUT_MS;

    private Socket       socket;
    private OutputStream out;
    private InputStream  in;

    /** Raw byte-level TX sink (post-format, pre-send). */
    private volatile Consumer<byte[]> rawTxSink;

    /** Raw byte-level RX sink (every byte read off the socket). */
    private volatile Consumer<byte[]> rawRxSink;

    // Async-mode infrastructure (activated by lspEnableReporting)
    private volatile Consumer<String>               stateListener = null;
    private volatile Thread                          readerThread  = null;
    private final    BlockingQueue<LabellerResponse> responseQueue = new LinkedBlockingQueue<>();

    // Timestamp of the most recent byte received by the reader thread (async mode).
    // Updated on every byte; used by waitForResponse() to implement an idle timeout
    // that resets whenever data arrives, rather than a fixed total-response timeout.
    private volatile long lastByteReceivedAt = 0;

    // Component versions – populated by initialiseSession() on each connect().
    // Keys are upper-case component names (e.g. "LAMA", "LEAP", "AGENT", "LSP", "CLI").
    // Values are the version number parsed as a Double (e.g. 7.401).
    private final HashMap<String, Double> versions = new HashMap<>();

    // Machine identity – populated by initialiseSession() via ?serialnumber / ?line.
    // Both may be empty on unconfigured machines or older firmware without AGENT support.
    private String labellerSerialNumber = "";
    private String labellerLine         = "";

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Create a client targeting the given labeller host and data port.
     *
     * @param host     IP address or hostname of the labeller
     * @param dataPort one of {@link #PORT_XA}, {@link #PORT_XB},
     *                 {@link #PORT_XC}, {@link #PORT_XD}
     */
    public LogoClientPL3(String host, int dataPort) {
        super(host);
        this.dataPort  = dataPort;
        this.resetPort = resolveResetPort(dataPort);
    }

    // =========================================================================
    // Connection management
    // =========================================================================

    /**
     * Open the TCP connection to the labeller. Idempotent – does nothing if
     * already connected.
     *
     * @throws IOException if the connection cannot be established
     */
    public void connect() throws IOException {
        if (isConnected()) return;
        wireLog("--- CONNECT  " + host + ":" + dataPort
                + "  (connect-timeout=" + DEFAULT_CONNECT_TIMEOUT_MS + " ms"
                + "  so-timeout=" + configuredSoTimeoutMs + " ms)");
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, dataPort), DEFAULT_CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(configuredSoTimeoutMs);
        out = new TeeOutputStream(socket.getOutputStream(), () -> rawTxSink);
        in  = new TeeInputStream (socket.getInputStream(),  () -> rawRxSink);
        wireLog("--- CONNECTED  " + host + ":" + dataPort
                + "  local=" + socket.getLocalAddress() + ":" + socket.getLocalPort());
        initialiseSession();
    }

    /**
     * Returns {@code true} if the socket is currently open and connected.
     */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Set the socket read timeout in milliseconds. Takes effect immediately
     * if connected; otherwise is applied on the next {@link #connect()}.
     * Command-level timeouts (e.g. response wait loops) honour this value too.
     */
    public void setReadTimeout(int ms) throws IOException {
        if (ms < 1) ms = 1;
        this.configuredSoTimeoutMs = ms;
        if (isConnected()) {
            socket.setSoTimeout(ms);
            wireLog("--- SO_TIMEOUT set to " + ms + " ms");
        }
    }

    /**
     * Close the connection and stop any background reader thread.
     *
     * <p>The documentation recommends keeping the connection open for the
     * labeller's lifetime, but this method provides a clean shutdown path
     * for application exit or error recovery.
     */
    @Override
    public void close() {
        wireLog("--- DISCONNECT  " + host + ":" + dataPort);
        stopReaderThread();
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {
        } finally {
            socket = null;
            out    = null;
            in     = null;
        }
    }

    /**
     * Use the labeller's reset port to break a connection deadlock.
     *
     * <p>Use this when {@link #connect()} is refused because the labeller
     * believes a previous connection is still active. The documented sequence:
     * <ol>
     *   <li>Host connects to the reset port.</li>
     *   <li>Labeller tears down the stale data connection, then closes the
     *       reset port (sends EOF).</li>
     *   <li>Host waits for that EOF.</li>
     *   <li>Host can now call {@link #connect()} again.</li>
     * </ol>
     *
     * <p>Do <em>not</em> use this as a normal connection-close mechanism.
     *
     * @throws IOException if the reset port cannot be reached
     */
    public void resetConnection() throws IOException {
        try (Socket rst = new Socket()) {
            rst.connect(new InetSocketAddress(host, resetPort), DEFAULT_CONNECT_TIMEOUT_MS);
            rst.setSoTimeout(10_000);
            InputStream rstIn = rst.getInputStream();
            //noinspection StatementWithEmptyBody
            while (rstIn.read() != -1) { /* drain until labeller closes the reset port */ }
        }
    }

    /**
     * Called at the end of {@link #connect()} to put the labeller into a known,
     * deterministic state and capture version information.
     *
     * <ol>
     *   <li>{@code *ACKNAK} – resets any pre-existing verbose mix back to binary
     *       ACK/NAK mode.</li>
     *   <li>{@code *VERBOSE} – switches to text / CMD-&gt; mode so that all
     *       subsequent error responses carry a human-readable description.  This
     *       is the format that {@link #readResponseDirect()} is optimised for.</li>
     *   <li>{@code ?versions} – queries firmware component versions so that
     *       callers can branch on {@link #getVersionLeap()} etc.</li>
     * </ol>
     *
     * A NAK from any of these commands is silently ignored (the labeller may not
     * support the command on older firmware); an {@link IOException} propagates
     * normally.
     */
    private void initialiseSession() throws IOException {
        lamaEnableAckNakMode();
        lamaEnableVerbose();
        labellerSerialNumber = agentQuerySingleValue("serialnumber");
        labellerLine         = agentQuerySingleValue("line");
        agentQueryVersions();
    }

    // =========================================================================
    // Version accessors (populated by initialiseSession on connect)
    // =========================================================================

    /**
     * Returns the firmware version for the named component (e.g. {@code "LAMA"},
     * {@code "LEAP"}, {@code "AGENT"}, {@code "LSP"}, {@code "CLI"}) as a
     * {@code Double}, or {@code null} if that component was not reported by the
     * labeller.  The name is matched case-insensitively.
     *
     * <pre>{@code
     * Double lama = labeller.getVersion("LAMA");  // e.g. 7.401
     * if (lama != null && lama >= 7.401) { ... }
     * }</pre>
     */
    public Double getVersion(String name) {
        return versions.get(name.toUpperCase());
    }

    /**
     * Returns an unmodifiable view of all component versions reported by the
     * most recent {@code ?versions} query.  Keys are upper-case component names;
     * values are the version number as a {@code Double}.
     */
    public Map<String, Double> getVersions() {
        return Collections.unmodifiableMap(versions);
    }

    // =========================================================================
    // LEAP FFP – print control
    // =========================================================================

    /**
     * {@code P} – Print one label. ACK is returned only after the label has been
     * physically applied (or an error has occurred).
     *
     * <p><b>Important:</b> this command must be sent as a standalone command.
     * It is not permitted inside an STX/ETX block (see {@link #sendBlock}).
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse printLabel() throws IOException {
        return sendRaw("P");
    }

    /**
     * {@code P,N} – Print one label, receiving ACK at the <em>start</em> of
     * printing (non-blocking) rather than at completion.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse printLabelNonBlocking() throws IOException {
        return sendRaw("P,N");
    }

    // =========================================================================
    // LEAP FFP – layout management
    // =========================================================================

    /**
     * {@code MAKE} – Prepare the current layout for printing.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse makeLayout() throws IOException {
        return sendRaw("MAKE");
    }

    /**
     * {@code MAKE,<id>} – Prepare the named layout for printing.
     *
     * @param layoutId layout identifier
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse makeLayout(String layoutId) throws IOException {
        return sendRaw("MAKE," + layoutId);
    }

    /**
     * {@code AL,<id>} – Activate an existing layout in memory for subsequent
     * field updates and printing.
     *
     * @param layoutId layout identifier
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse activateLayout(String layoutId) throws IOException {
        return sendRaw("AL," + layoutId);
    }

    /**
     * {@code LAY,<id>} – Define or redefine a layout by its ID.
     *
     * <p>Full syntax: {@code LAY,id[,x,y,w,h][,O opts][,T on,off][,Z dot shift]}.
     * For complete parameter control pass the full string to {@link #sendRaw}.
     *
     * @param layoutId layout identifier
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse defineLayout(String layoutId) throws IOException {
        return sendRaw("LAY," + layoutId);
    }

    /**
     * {@code LBL,<width>,<height>,<gap>,<offset>,<radii>,<step>} – Set the
     * physical label dimensions. All values are in millimetres.
     *
     * @param widthMm  label width
     * @param heightMm label height
     * @param gapMm    gap between labels
     * @param offsetMm offset
     * @param radiiMm  corner radii
     * @param stepMm   step distance
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse setLabelSize(double widthMm, double heightMm, double gapMm,
                                          double offsetMm, double radiiMm, double stepMm)
            throws IOException {
        return sendRaw(String.format("LBL,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                widthMm, heightMm, gapMm, offsetMm, radiiMm, stepMm));
    }

    /**
     * {@code LK,<id>} – Kill (remove) a layout from labeller memory.
     *
     * @param layoutId layout identifier
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse killLayout(String layoutId) throws IOException {
        return sendRaw("LK," + layoutId);
    }

    /**
     * {@code CLAY,<id>} – Clear all ASCII/Unicode variable fields in the layout,
     * resetting them to their default (empty) values.
     *
     * @param layoutId layout identifier
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse clearLayoutFields(String layoutId) throws IOException {
        return sendRaw("CLAY," + layoutId);
    }

    /**
     * {@code CLID,<id>} – Change the ID of the currently active layout.
     *
     * @param newId new layout identifier
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse changeLayoutId(String newId) throws IOException {
        return sendRaw("CLID," + newId);
    }

    // =========================================================================
    // LEAP FFP – file operations
    // =========================================================================

    /**
     * {@code LOAD,<filename>} – Execute LEAP FFP commands from a file on the
     * labeller's CMOS disk. Unlike {@link #lamaLoad(String)}, this does
     * <em>not</em> kill existing layouts first.
     *
     * @param filename file on the labeller (e.g. {@code "label1.llf"})
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse loadFile(String filename) throws IOException {
        return sendRaw("LOAD," + filename);
    }

    /**
     * {@code LAD,<filename>} – Load only data commands from a file (BD, FD, FF,
     * FLP, FRP, QA, UBD, UD, UF, UQA are executed; structural layout commands
     * are ignored).
     *
     * @param filename file on the labeller's CMOS disk
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse loadArticleData(String filename) throws IOException {
        return sendRaw("LAD," + filename);
    }

    // =========================================================================
    // LEAP FFP – field data
    // =========================================================================

    /**
     * {@code FD,<fieldNumber>,<value>} – Write a text value to the specified
     * label field.
     *
     * @param fieldNumber field number as defined in the layout
     * @param value       the text to write
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse sendFieldData(int fieldNumber, String value) throws IOException {
        return sendRaw("FD," + fieldNumber + "," + value);
    }

    /**
     * {@code BD,<fieldNumber>,<value>} – Write bulk data to the specified field.
     *
     * @param fieldNumber field number
     * @param value       the value to write
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse sendBulkData(int fieldNumber, String value) throws IOException {
        return sendRaw("BD," + fieldNumber + "," + value);
    }

    /**
     * {@code UD,<fieldNumber>,<value>} – Write Unicode text to the specified field.
     *
     * @param fieldNumber field number
     * @param value       Unicode text to write
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse sendUnicodeData(int fieldNumber, String value) throws IOException {
        return sendRaw("UD," + fieldNumber + "," + value);
    }

    /**
     * {@code FF,<fieldNumber>,<filename>} – Load field content from a file on
     * the labeller's CMOS disk.
     *
     * @param fieldNumber field number
     * @param filename    file on the labeller
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse sendFieldFile(int fieldNumber, String filename) throws IOException {
        return sendRaw("FF," + fieldNumber + "," + filename);
    }

    /**
     * {@code UF,<fieldNumber>,<filename>} – Load Unicode field content from a
     * file on the labeller's CMOS disk.
     *
     * @param fieldNumber field number
     * @param filename    file on the labeller
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse sendUnicodeFile(int fieldNumber, String filename) throws IOException {
        return sendRaw("UF," + fieldNumber + "," + filename);
    }

    // =========================================================================
    // LEAP FFP – read/query commands
    // =========================================================================

    /**
     * {@code RCID} – Read the current layout IDs.
     * Reply body format: {@code Current ID: $1,nnn2,$3,$4}.
     *
     * @return labeller response; body contains the current ID string
     * @throws IOException on communication error
     */
    public LabellerResponse readCurrentIds() throws IOException {
        return sendRaw("RCID");
    }

    /**
     * {@code RD} – Read the labeller's real-time clock.
     * Reply body format: {@code >yyyy.mm.dd hh:mm:ss} (CR before the final ACK).
     * Use {@link LabellerResponse#getBodyTrimmed()} to strip the leading {@code >}.
     *
     * @return labeller response; body contains the date-time string
     * @throws IOException on communication error
     */
    public LabellerResponse readDateTime() throws IOException {
        return sendRaw("RD");
    }

    /**
     * {@code READ,<key>} – Read a labeller configuration value.
     *
     * <p>Common key values: {@code contrast}, {@code labelsize}, {@code maxdots},
     * {@code resolution}, {@code speed}, {@code printer}, {@code paper}.
     *
     * @param key configuration key
     * @return labeller response; body contains the value
     * @throws IOException on communication error
     */
    public LabellerResponse readValue(String key) throws IOException {
        return sendRaw("READ," + key);
    }

    /**
     * {@code REL} – Read (and clear) error log messages stored on the labeller.
     *
     * @return labeller response; body contains error log entries
     * @throws IOException on communication error
     */
    public LabellerResponse readErrorLog() throws IOException {
        return sendRaw("REL");
    }

    /**
     * {@code C,<nnn>} – Set the print contrast value.
     *
     * @param contrast contrast value
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse setContrast(int contrast) throws IOException {
        return sendRaw("C," + contrast);
    }

    /**
     * {@code CC} – Clear the print counter.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse clearCounter() throws IOException {
        return sendRaw("CC");
    }

    // =========================================================================
    // LEAP FFP – block (STX/ETX) sending
    // =========================================================================

    /**
     * Send multiple LEAP FFP commands as a single atomic STX/ETX block.
     *
     * <p>All commands are transmitted as:
     * {@code <STX> cmd1<CR> cmd2<CR> ... <ETX>}
     * and a single ACK/NAK is returned for the entire block.
     *
     * <p><b>Restriction:</b> the {@code P} (print) command is not allowed inside
     * a block per the protocol documentation. An {@link IllegalArgumentException}
     * is thrown if any entry in the list is {@code "P"} or starts with {@code "P,"}.
     *
     * @param commands list of command strings, without trailing CR
     * @return labeller response for the entire block
     * @throws IOException              on communication error
     * @throws IllegalArgumentException if the list is empty or contains the P command
     * @throws IllegalStateException    if not connected
     */
    public LabellerResponse sendBlock(List<String> commands) throws IOException {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("Command block must not be empty");
        }
        for (String cmd : commands) {
            String upper = cmd.trim().toUpperCase();
            if (upper.equals("P") || upper.startsWith("P,")) {
                throw new IllegalArgumentException(
                        "The P (print) command cannot be sent inside an STX/ETX block: '" + cmd + "'");
            }
        }
        if (!isConnected()) {
            throw new IllegalStateException("Not connected to labeller at " + host + ":" + dataPort);
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(BYTE_STX);
        for (String cmd : commands) {
            buf.write(cmd.getBytes(StandardCharsets.US_ASCII));
            buf.write(BYTE_CR);
        }
        buf.write(BYTE_ETX);
        synchronized (out) {
            out.write(buf.toByteArray());
            out.flush();
        }
        return waitForResponse();
    }

    // =========================================================================
    // LAMA / LACE commands
    // =========================================================================

    /**
     * {@code *VERSION} – Query the LAMA firmware version string.
     *
     * @return labeller response; body contains the version
     * @throws IOException on communication error
     */
    public LabellerResponse lamaGetVersion() throws IOException {
        return sendRaw("*VERSION");
    }

    /**
     * Sends {@code ?versions} to verify the TCP connection is still alive.
     * Uses the same LEAP AGENT query issued during {@link #connect()}, so it
     * is guaranteed to be supported by any labeller the client can connect to.
     * Synchronises on the output stream so it serialises cleanly with any
     * concurrent user command.
     */
    @Override
    public void ping() throws IOException {
        agentQueryVersions();
    }

    /**
     * {@code *GETTIME} – Read the labeller real-time clock via LAMA.
     * Reply body format: {@code YYYYMMDDHHmmss} (14 characters, before ACK).
     *
     * @return labeller response; body is the 14-character timestamp string
     * @throws IOException on communication error
     */
    public LabellerResponse lamaGetTime() throws IOException {
        return sendRaw("*GETTIME");
    }

    /**
     * {@code *SETTIME,<YYYYMMDDHHmmss>} – Set the labeller real-time clock.
     *
     * @param dateTime the date/time to set
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaSetTime(LocalDateTime dateTime) throws IOException {
        String ts = dateTime.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return sendRaw("*SETTIME," + ts);
    }

    /**
     * {@code *SETTIME,<timestamp>} – Set the labeller clock using a pre-formatted
     * string. Accepted formats: {@code YYYYMMDDHHmmss} (14 chars) or
     * {@code DDMMYYhhmm} (10 chars).
     *
     * @param timestamp pre-formatted timestamp string
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaSetTime(String timestamp) throws IOException {
        return sendRaw("*SETTIME," + timestamp);
    }

    /**
     * {@code *GETCOUNT} – Read the current print counter value.
     *
     * @return labeller response; body contains the counter value
     * @throws IOException on communication error
     */
    public LabellerResponse lamaGetCount() throws IOException {
        return sendRaw("*GETCOUNT");
    }

    /**
     * {@code *SETCOUNT,<n>} – Set the print counter to the given value.
     *
     * @param count new counter value
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaSetCount(long count) throws IOException {
        return sendRaw("*SETCOUNT," + count);
    }

    /**
     * {@code *MEM} – Query memory and disk usage information.
     *
     * @return labeller response; body contains memory/disk info
     * @throws IOException on communication error
     */
    public LabellerResponse lamaGetMemoryInfo() throws IOException {
        return sendRaw("*MEM");
    }

    /**
     * {@code *TYPE,<filename>} – Read a text file stored on the labeller's CMOS
     * disk. The file contents are returned in the response body.
     *
     * <p>File name rules: characters a–z, 0–9, underscore, period; max 26 chars
     * (plus extension); best performance at 8 characters.
     *
     * @param filename file name, with optional path prefix (e.g. {@code "label1.llf"},
     *                 {@code "/c9/settings.ini"})
     * @return labeller response; body contains the file text
     * @throws IOException on communication error
     */
    public LabellerResponse lamaTypeFile(String filename) throws IOException {
        return sendRaw("*TYPE," + filename);
    }

    /**
     * {@code *TYPEHEX,<filename>} – Transfer a binary file from the labeller to
     * the host in Intel HEX format.
     *
     * <p>The protocol is <em>stop-and-wait</em>: after receiving each complete
     * Intel HEX record line (terminated with {@code CR+LF}) the host must send
     * a binary ACK byte (0x06) before the labeller will send the next record.
     * Without the per-record ACK the labeller stalls indefinitely after the
     * first block, regardless of how long the socket timeout is.
     *
     * <p>Flow:
     * <ol>
     *   <li>Host sends {@code *TYPEHEX,<filename><CR>}</li>
     *   <li>Labeller sends one Intel HEX record line ending {@code <CR><LF>}</li>
     *   <li>Host sends ACK (0x06)</li>
     *   <li>Steps 2–3 repeat for every record</li>
     *   <li>After the EOF record and its ACK, the labeller sends {@code CMD->}</li>
     * </ol>
     *
     * @param filename file on the labeller (e.g. {@code "nestle.pcx"})
     * @return labeller response; body contains the full Intel HEX text
     * @throws IOException on communication error or timeout
     */
    public LabellerResponse lamaTypeFileHex(String filename) throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException(
                    "Not connected to labeller at " + host + ":" + dataPort);
        }
        int saved = socket.getSoTimeout();
        int transferTimeout = Math.max(saved, DEFAULT_TRANSFER_SO_TIMEOUT_MS);
        wireLog("--- SO_TIMEOUT set to " + transferTimeout
                + " ms for *TYPEHEX transfer");
        socket.setSoTimeout(transferTimeout);
        try {
            // Send the command
            byte[] cmdBytes = ("*TYPEHEX," + filename + "\r")
                    .getBytes(StandardCharsets.US_ASCII);
            wireLog(">>> TX  \"*TYPEHEX," + filename + "\\r\"  [" + hexOf(cmdBytes) + "]");
            synchronized (out) {
                out.write(cmdBytes);
                out.flush();
            }
            return readIntelHexDownload();
        } finally {
            try { socket.setSoTimeout(saved); } catch (IOException ignored) {}
            wireLog("--- SO_TIMEOUT restored to " + saved + " ms");
        }
    }

    /**
     * Reads a stop-and-wait Intel HEX download stream from the labeller.
     *
     * <p>After each complete {@code :…<CR><LF>} record line a binary ACK (0x06)
     * is sent to release the labeller for the next record.  When the EOF record
     * ({@code :0000000000} or {@code :00000001xx}) is received and ACKed, the
     * method returns immediately with the accumulated HEX text — the labeller
     * does <em>not</em> send a {@code CMD->} prompt after {@code *TYPEHEX}.
     * If the labeller responds with {@code CMD->} before any HEX data, that is
     * treated as an error (e.g. file not found).
     */
    private LabellerResponse readIntelHexDownload() throws IOException {
        StringBuilder hexContent  = new StringBuilder();   // accumulated HEX lines
        StringBuilder line        = new StringBuilder();   // current line buffer
        List<Integer> raw         = new ArrayList<>();     // hex dump for wire log

        try {
            int b;
            while ((b = in.read()) != -1) {
                raw.add(b);

                // Binary ACK/NAK byte – treat as early labeller error signal
                if (b == BYTE_ACK) {
                    wireLog("<<< RX  ACK(binary)  raw=[" + hexOf(raw) + "]");
                    return LabellerResponse.ack(hexContent.toString());
                }
                if (b == BYTE_NAK) {
                    wireLog("<<< RX  NAK(binary)  body=" + hexContent
                            + "  raw=[" + hexOf(raw) + "]");
                    return LabellerResponse.nak(hexContent.toString());
                }

                line.append((char) b);

                // Check for CMD-> prompt (end of transfer or error before any HEX data)
                if (endsWithCmdPrompt(line)) {
                    if (hexContent.length() > 0) {
                        // Received at least some HEX; treat CMD-> as success terminator
                        wireLog("<<< RX  ACK(CMD->)  records=" + countLines(hexContent)
                                + "  raw=[" + hexOf(raw) + "]");
                        return LabellerResponse.ack(hexContent.toString());
                    } else {
                        // CMD-> with no HEX data = labeller error (e.g. file not found)
                        return parseCmdPromptResponse(line, raw);
                    }
                }

                // Line complete when we receive LF (lines are CR+LF terminated)
                if (b == '\n') {
                    String lineStr = line.toString();
                    String trimmed = lineStr.trim();
                    line.setLength(0);

                    if (trimmed.startsWith(":")) {
                        hexContent.append(lineStr);   // keep original line endings
                        wireLog("<<< RX  HEX  " + trimmed);

                        // Detect EOF record: :0000000000 (Logopak) or :00000001FF (standard)
                        boolean isEof = trimmed.equals(":0000000000")
                                || trimmed.startsWith(":00000001");

                        // Send ACK to release the labeller for the next record
                        wireLog(">>> TX  ACK (0x06)");
                        synchronized (out) {
                            out.write(BYTE_ACK);
                            out.flush();
                        }

                        if (isEof) {
                            // Transfer complete — return immediately.
                            // The labeller does not send CMD-> after *TYPEHEX.
                            int records = countLines(hexContent);
                            wireLog("--- *TYPEHEX complete  records=" + records
                                    + "  bytes(hex)=" + hexContent.length());
                            return LabellerResponse.ack(hexContent.toString());
                        }
                    }
                }
            }

            wireLog("!!! CONNECTION CLOSED during *TYPEHEX transfer"
                    + "  records=" + countLines(hexContent)
                    + "  raw=[" + hexOf(raw) + "]");
            throw new IOException("Connection closed by labeller during *TYPEHEX transfer");

        } catch (java.net.SocketTimeoutException e) {
            wireLog("!!! SOCKET TIMEOUT during *TYPEHEX transfer"
                    + "  records=" + countLines(hexContent)
                    + "  raw=[" + hexOf(raw) + "]");
            throw e;
        }
    }

    /**
     * Reads a stop-and-wait Intel HEX download stream from the labeller as
     * produced by the {@code *TRXLOG} command.
     *
     * <p>The Logopak log-transfer variant of the Intel HEX protocol differs
     * from the {@code *TYPEHEX} variant in two ways:
     * <ul>
     *   <li>HEX records are terminated with {@code <CR>} only (not {@code <CR><LF>}).</li>
     *   <li>The per-record acknowledgement is the standard LEAP positive response
     *       {@code "000\u0006\r"} rather than a bare {@code 0x06} byte.</li>
     * </ul>
     *
     * <p>After ACKing the EOF record ({@code :0000000000}) the method returns
     * immediately with the accumulated HEX text; no further labeller response
     * is expected.
     */
    private LabellerResponse readLeapLogDownload() throws IOException {
        StringBuilder hexContent = new StringBuilder();
        StringBuilder line       = new StringBuilder();
        List<Integer> raw        = new ArrayList<>();

        // Standard LEAP positive acknowledgement sent after each received HEX record
        final byte[] LEAP_ACK = "000\006\r".getBytes(StandardCharsets.US_ASCII);

        try {
            int b;
            while ((b = in.read()) != -1) {
                raw.add(b);

                // Early binary ACK/NAK from labeller — error signal before any HEX data
                if (b == BYTE_ACK) {
                    wireLog("<<< RX  ACK(binary/early)  raw=[" + hexOf(raw) + "]");
                    return LabellerResponse.ack(hexContent.toString());
                }
                if (b == BYTE_NAK) {
                    wireLog("<<< RX  NAK(binary)  body=" + hexContent
                            + "  raw=[" + hexOf(raw) + "]");
                    return LabellerResponse.nak(hexContent.toString());
                }

                line.append((char) b);

                // Check for CMD-> prompt (error before any data, or unexpected terminator)
                if (endsWithCmdPrompt(line)) {
                    if (hexContent.length() > 0) {
                        wireLog("<<< RX  ACK(CMD->)  records=" + countLines(hexContent)
                                + "  raw=[" + hexOf(raw) + "]");
                        return LabellerResponse.ack(hexContent.toString());
                    } else {
                        return parseCmdPromptResponse(line, raw);
                    }
                }

                // Logopak *TRXLOG uses CR-only line termination (not CR+LF)
                if (b == '\r') {
                    String lineStr = line.toString();
                    String trimmed = lineStr.trim();
                    line.setLength(0);

                    if (trimmed.startsWith(":")) {
                        hexContent.append(lineStr);
                        wireLog("<<< RX  HEX  " + trimmed);

                        // EOF record: :0000000000 (Logopak) or :00000001xx (standard)
                        boolean isEof = trimmed.equals(":0000000000")
                                || trimmed.startsWith(":00000001");

                        // Per-record ACK: standard LEAP positive response "000<ACK><CR>"
                        // (reversed-engineered from Logosoft ReceiveLogJob/ReceiveFileJob)
                        wireLog(">>> TX  LEAP_ACK  \"000\\006\\r\"");
                        synchronized (out) {
                            out.write(LEAP_ACK);
                            out.flush();
                        }

                        if (isEof) {
                            int records = countLines(hexContent);
                            wireLog("--- *TRXLOG complete  records=" + records
                                    + "  bytes(hex)=" + hexContent.length());
                            return LabellerResponse.ack(hexContent.toString());
                        }
                    }
                }
            }

            wireLog("!!! CONNECTION CLOSED during *TRXLOG transfer"
                    + "  records=" + countLines(hexContent)
                    + "  raw=[" + hexOf(raw) + "]");
            throw new IOException("Connection closed by labeller during *TRXLOG transfer");

        } catch (java.net.SocketTimeoutException e) {
            wireLog("!!! SOCKET TIMEOUT during *TRXLOG transfer"
                    + "  records=" + countLines(hexContent)
                    + "  raw=[" + hexOf(raw) + "]");
            throw e;
        }
    }

    /** Returns the number of non-blank lines in a StringBuilder. */
    private static int countLines(StringBuilder sb) {
        int count = 0;
        int start = 0;
        for (int i = 0; i <= sb.length(); i++) {
            if (i == sb.length() || sb.charAt(i) == '\n') {
                if (i > start) count++;
                start = i + 1;
            }
        }
        return count;
    }

    /**
     * {@code *LOAD,<filename>} – Kill all layouts currently in memory, then load
     * and execute a layout file from the labeller's CMOS disk.
     *
     * <p><em>Warning:</em> unlike the LEAP FFP {@link #loadFile(String)}, this
     * command destroys all loaded layouts before loading the new file.
     *
     * @param filename layout file to load (e.g. {@code "label1.llf"})
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaLoad(String filename) throws IOException {
        return sendRaw("*LOAD," + filename);
    }

    /**
     * {@code *LOAD,<filename>,RDR} – Load a layout file and reset the LACE
     * data-ready flag after loading.
     *
     * @param filename layout file to load
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaLoadResetDataReady(String filename) throws IOException {
        return sendRaw("*LOAD," + filename + ",RDR");
    }

    /**
     * {@code *LOAD,<filename>,SDR} – Load a layout file and set the LACE
     * data-ready flag after loading.
     *
     * @param filename layout file to load
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaLoadSetDataReady(String filename) throws IOException {
        return sendRaw("*LOAD," + filename + ",SDR");
    }

    /**
     * {@code *SAVE,<filename>} – Save the current layout(s) to a file on the
     * labeller's CMOS disk.
     *
     * @param filename target file name (e.g. {@code "label1.llf"})
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaSave(String filename) throws IOException {
        return sendRaw("*SAVE," + filename);
    }

    /**
     * {@code *LOGINFO} – Query whether a log file and/or log backup currently
     * exist on the labeller's CMOS disk.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaLogInfo() throws IOException {
        return sendRaw("*LOGINFO");
    }

    /**
     * {@code *TRXLOG} – Transfer the log backup file ({@code logbak.txt}) to
     * the host using the stop-and-wait Intel HEX protocol.
     *
     * <p>If no backup exists the labeller first renames the current log to
     * {@code logbak.txt} before starting the transfer.
     *
     * <p>The protocol is <em>stop-and-wait</em>: after receiving each CR-terminated
     * Intel HEX record the host sends the standard LEAP positive acknowledgement
     * {@code "000\u0006\r"} before the labeller transmits the next record.
     * The Logopak-variant EOF record is {@code :0000000000}.
     *
     * @return labeller response; body contains the full Intel HEX text of the log
     * @throws IOException on communication error or timeout
     */
    public LabellerResponse lamaTransferLog() throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException(
                    "Not connected to labeller at " + host + ":" + dataPort);
        }
        int saved = socket.getSoTimeout();
        int transferTimeout = Math.max(saved, DEFAULT_TRANSFER_SO_TIMEOUT_MS);
        wireLog("--- SO_TIMEOUT set to " + transferTimeout
                + " ms for *TRXLOG transfer");
        socket.setSoTimeout(transferTimeout);
        try {
            byte[] cmdBytes = "*TRXLOG\r".getBytes(StandardCharsets.US_ASCII);
            wireLog(">>> TX  \"*TRXLOG\\r\"  [" + hexOf(cmdBytes) + "]");
            synchronized (out) {
                out.write(cmdBytes);
                out.flush();
            }
            return readLeapLogDownload();
        } finally {
            try { socket.setSoTimeout(saved); } catch (IOException ignored) {}
            wireLog("--- SO_TIMEOUT restored to " + saved + " ms");
        }
    }

    /**
     * {@code *DELLOG} – Delete the log backup file ({@code logbak.txt}) from the
     * labeller CMOS. Should be called after {@link #lamaTransferLog()} has
     * successfully retrieved the log.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaDeleteLog() throws IOException {
        return sendRaw("*DELLOG");
    }

    // =========================================================================
    // Pallet log polling (field 19500 / SSCC tracking)
    // =========================================================================

    /**
     * Field number used by the Logopak pallet label log. The labeller writes
     * a semicolon-separated string containing all variable fields (including
     * the SSCC-18 pallet number) to this log field on each label application.
     */
    public static final int LOG_DATA_FIELD = 19500;

    /**
     * Writes a value to the pallet log data field (field {@value #LOG_DATA_FIELD}).
     *
     * <p>This is a convenience wrapper around {@link #sendFieldData(int, String)}.
     * Use it to pre-populate the log field when the layout does not populate it
     * automatically.
     *
     * @param data semicolon-separated field data to record
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse sendLogData(String data) throws IOException {
        return sendFieldData(LOG_DATA_FIELD, data);
    }

    /**
     * Performs a complete pallet log poll cycle:
     * <ol>
     *   <li>{@code *LOGINFO} – check how many log buffers are available.</li>
     *   <li>{@code *TRXLOG}  – transfer the log data to the host via stop-and-wait
     *       Intel HEX protocol.</li>
     *   <li>{@code *DELLOG}  – delete the transferred log from the labeller's
     *       CMOS flash.</li>
     * </ol>
     *
     * <p>The {@code *LOGINFO} two-bit status determines the number of cycles:
     * <ul>
     *   <li>{@code "00"} – no log available; returns empty list immediately.</li>
     *   <li>{@code "01"} or {@code "10"} – one buffer; one {@code *TRXLOG}/{@code *DELLOG} cycle.</li>
     *   <li>{@code "11"} – double buffer (both primary and backup exist);
     *       two {@code *TRXLOG}/{@code *DELLOG} cycles, results combined.</li>
     * </ul>
     *
     * <p>The log data format is detected automatically by {@link Pl3LogParser}:
     * <ul>
     *   <li><b>STX/ETX</b> – body starts with {@code \u0002} (STX).</li>
     *   <li><b>Intel HEX</b> – body starts with {@code :};
     *       decoded to plain text then parsed as newline/CR-delimited entries.</li>
     *   <li><b>Plain text</b> – all other responses; one log entry per line.</li>
     * </ul>
     *
     * <p><b>Error handling:</b> if {@code *TRXLOG} fails the method throws
     * {@link IOException} and does <em>not</em> call {@code *DELLOG}, so the
     * log data is preserved on the labeller.
     *
     * @return immutable list of {@link PalletLogEntry} records in the order
     *         they were stored (oldest first); empty if no log data is available
     * @throws IOException on communication error or if {@code *TRXLOG} fails
     */
    public List<PalletLogEntry> fetchPalletLog() throws IOException {
        // 1. Check how many log buffers are available
        LabellerResponse info = lamaLogInfo();
        if (!info.isOk()) {
            // NAK from *LOGINFO – no log or labeller offline
            return Collections.emptyList();
        }
        int bufferCount = parseLogInfoStatus(info.getBodyTrimmed());
        if (bufferCount == 0) {
            return Collections.emptyList();
        }

        // 2. Transfer + delete for each available buffer
        //    (normally 1; 2 when *LOGINFO returns "11" — double buffer)
        List<PalletLogEntry> all = new ArrayList<>();
        for (int i = 0; i < bufferCount; i++) {
            LabellerResponse trx = lamaTransferLog();
            if (!trx.isOk()) {
                throw new IOException(
                        "*TRXLOG failed (error " + trx.getErrorCode() + "): " + trx.getBody());
            }
            all.addAll(Pl3LogParser.parse(trx.getBody()));
            // Delete the backup log from CMOS (best-effort; data already in memory)
            lamaDeleteLog();
        }

        return Collections.unmodifiableList(all);
    }

    /**
     * Interprets the two-bit status string returned by {@code *LOGINFO}.
     *
     * <ul>
     *   <li>{@code "00"} or empty → 0 (no log available)</li>
     *   <li>{@code "01"} or {@code "10"} → 1 (one buffer)</li>
     *   <li>{@code "11"} → 2 (double buffer — both primary and backup present)</li>
     * </ul>
     *
     * @param status trimmed body from the {@code *LOGINFO} response
     * @return number of transfer cycles to perform (0, 1, or 2)
     */
    private static int parseLogInfoStatus(String status) {
        if (status == null || status.isEmpty()) return 0;

        // In verbose / CMD-> mode the labeller appends trailing status text after
        // the two-bit code, e.g. "00\r\r0 No error(s)."  Extract only the first
        // token (everything before the first CR, LF, or space).
        int eol = Integer.MAX_VALUE;
        for (char ch : new char[]{'\r', '\n', ' '}) {
            int idx = status.indexOf(ch);
            if (idx >= 0 && idx < eol) eol = idx;
        }
        String token = (eol == Integer.MAX_VALUE ? status : status.substring(0, eol)).trim();

        if (token.equals("00") || token.equals("0") || token.equals("000") || token.isEmpty()) {
            return 0;
        }
        if (token.equals("11")) {
            return 2;
        }
        // "01", "10", or any other non-empty/non-zero token → one buffer
        return 1;
    }

    /** @deprecated Use {@link Pl3LogParser#parse(String)} directly. */
    @Deprecated
    public static List<PalletLogEntry> parsePalletLog(String rawBody) {
        return Pl3LogParser.parse(rawBody);
    }

    /** @deprecated Use {@link Pl3LogParser#parseStxEtx(String)} directly. */
    @Deprecated
    public static List<PalletLogEntry> parseLogStxEtx(String raw) {
        return Pl3LogParser.parseStxEtx(raw);
    }

    /** @deprecated Use {@link Pl3LogParser#parsePlainText(String)} directly. */
    @Deprecated
    public static List<PalletLogEntry> parseLogLines(String text) {
        return Pl3LogParser.parsePlainText(text);
    }

    // PalletLogEntry is now com.commander4j.client.common.PalletLogEntry (top-level class).

    /**
     * {@code *MSG,<type>,<message>} – Display a message on the labeller's GUI.
     *
     * @param type    message severity: {@code "info"}, {@code "error"}, or
     *                {@code "warning"}
     * @param message text to display
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaShowMessage(String type, String message) throws IOException {
        return sendRaw("*MSG," + type + "," + message);
    }

    /**
     * {@code *RDR} – Reset the LACE data-ready flag (signals that data is
     * <em>not</em> yet ready).
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaResetDataReady() throws IOException {
        return sendRaw("*RDR");
    }

    /**
     * {@code *SDR} – Set the LACE data-ready flag (signals that data
     * <em>is</em> ready).
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaSetDataReady() throws IOException {
        return sendRaw("*SDR");
    }

    /**
     * {@code *VERBOSE} – Enable verbose mode. Required before calling
     * {@link #lspListSymbols()} and certain other diagnostic commands.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaEnableVerbose() throws IOException {
        return sendRaw("*VERBOSE");
    }

    /**
     * {@code *ACKNAK} – Switch the response format to bare {@code <ACK>} /
     * {@code <NAK>} bytes instead of the normal {@code 000<ACK>} /
     * {@code xxx<NAK>} format.
     *
     * <p>This class's {@link #sendRaw} handles both response formats
     * transparently, so no code changes are required after calling this.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaEnableAckNakMode() throws IOException {
        return sendRaw("*ACKNAK");
    }

    /**
     * {@code *LAMAON} – Enable LAMA (Logopak Application Management Access) mode.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaOn() throws IOException {
        return sendRaw("*LAMAON");
    }

    /**
     * {@code *LAMAOFF} – Disable LAMA mode.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaOff() throws IOException {
        return sendRaw("*LAMAOFF");
    }

    /**
     * {@code *YMODEMERROR} – Query the last error code from a YMODEM file transfer.
     *
     * <p>Returns {@code 000<ACK>} if no error, or an error code and message such
     * as {@code "982 YMODEM protocol timeout detected!"} followed by NAK.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lamaGetYmodemError() throws IOException {
        return sendRaw("*YMODEMERROR");
    }

    // =========================================================================
    // LAMA / LACE – file directory listing
    // =========================================================================

    /**
     * {@code *DIR,<wildcard>,SDT} – List files on the labeller's CMOS disk
     * matching the given wildcard pattern, with size, date and time details.
     *
     * <p>The {@code SDT} flags request:
     * <ul>
     *   <li><b>S</b> – file size in bytes</li>
     *   <li><b>D</b> – last-modified date (YYMMDD)</li>
     *   <li><b>T</b> – last-modified time (hhmm)</li>
     * </ul>
     *
     * <p>Each line in the response body has the format:
     * {@code name.ext,size,YYMMDD,hhmm} — fields after the name are present
     * only when the corresponding flag was specified.
     *
     * <p>Example patterns: {@code "*.*"} (all files), {@code "*.llf"} (layouts),
     * {@code "*.pcx"} (logos).
     *
     * @param wildcard file filter pattern, e.g. {@code "*.*"} or {@code "*.llf"}
     * @return labeller response; body contains one {@code name,size,date,time}
     *         line per matching file, terminated by {@code 000<ACK>}
     * @throws IOException on communication error
     */
    public LabellerResponse lamaListFiles(String wildcard) throws IOException {
        String spec = wildcard;
        if (spec.startsWith("/") && !supportsPathPrefixedDir()) {
            int lastSlash = spec.lastIndexOf('/');
            spec = (lastSlash >= 0) ? spec.substring(lastSlash + 1) : spec;
            if (spec.isEmpty()) spec = "*.*";
        }
        return sendRaw("*DIR," + spec + ",SDT");
    }

    /**
     * {@code *DIR,*.*,SDT} – List all files on the labeller's CMOS disk.
     * The {@code SDT} suffix requests a sorted, detailed listing.
     *
     * @return labeller response; body contains the file listing
     * @throws IOException on communication error
     */
    public LabellerResponse lamaListAllFiles() throws IOException {
        return sendRaw("*DIR,*.*,SDT");
    }

    /**
     * {@code *DELETE,<filename>} – Delete a file from the labeller.
     *
     * <p>The LAMA documentation explicitly states that path prefixes must
     * <em>not</em> be included (e.g. {@code *DELETE,/c0/layout.llf} is wrong;
     * {@code *DELETE,layout.llf} is correct). The labeller locates the file
     * using its internal path table. Any leading path component supplied by
     * the caller is stripped automatically.
     *
     * @param filename bare file name or a path-prefixed name such as
     *                 {@code /c0/layout.llf}; the path prefix is always stripped
     * @return labeller response; {@code 000<ACK>} on success, {@code 992<NAK>}
     *         if the file was not found
     * @throws IOException on communication error
     */
    public LabellerResponse lamaDeleteFile(String filename) throws IOException {
        String name = filename;
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        return sendRaw("*DELETE," + name);
    }

    // =========================================================================
    // LAMA / LACE – Intel HEX file transfer (*HEXFILE / *TYPEHEX)
    // =========================================================================

    /**
     * {@code *HEXFILE,<filename>} – Upload a binary file to the labeller's CMOS
     * disk in Intel HEX format.
     *
     * <p>Protocol sequence (stop-and-wait, per VCOMSVR manual):
     * <ol>
     *   <li>Send {@code *HEXFILE,<filename><CR>}.</li>
     *   <li>Read {@code 000<ACK>} — labeller acknowledges the command.</li>
     *   <li>For each Intel HEX data record:
     *     <ol type="a">
     *       <li>Send the record terminated with {@code <CR><LF>}.</li>
     *       <li>Read {@code 000<ACK>} — labeller confirms the record before
     *           the host may send the next one.  The labeller fires
     *           {@code 996 Receive timeout!} if the next record does not
     *           arrive within ~4 s of its ACK.</li>
     *     </ol>
     *   </li>
     *   <li>Send the End Block record {@code :0000000000<CR><LF>}.</li>
     *   <li>Read {@code 000<ACK>} — transfer complete.</li>
     * </ol>
     * <p>{@code 000<ACK>} is three ASCII {@code '0'} chars followed by a
     * binary ACK byte (0x06).  Every step — the initial command, each data
     * record, and the End Block — receives the same handshake response.
     *
     * <p>Data is encoded using {@link #INTEL_HEX_RECORD_SIZE} (32) bytes per
     * record with a 16-bit sequential address and a validated checksum.
     * Files larger than 64 KB are not supported by the basic 16-bit addressing
     * scheme; labeller layout/data files are well within this limit.
     *
     * <p>Default file paths (LAMA 7.401+):
     * <ul>
     *   <li>{@code *.llf}, {@code *.ldf}, {@code *.pcx} etc. → {@code /c0/}</li>
     *   <li>{@code *.lsp}, {@code *.ini} → {@code /c9/}</li>
     *   <li>{@code mcp.bin}, {@code *.gui} → {@code /f0/} (flash)</li>
     * </ul>
     *
     * @param filename remote filename on the labeller (e.g. {@code "label1.llf"})
     * @param data     raw binary content to upload
     * @return final labeller response
     * @throws IOException              on communication or encoding error
     * @throws IllegalStateException    if not connected
     * @throws IllegalArgumentException if {@code data} is null
     */
    public LabellerResponse lamaUploadHexFile(String filename, byte[] data) throws IOException {
        if (data == null) throw new IllegalArgumentException("data must not be null");
        if (!isConnected()) {
            throw new IllegalStateException("Not connected to labeller at " + host + ":" + dataPort);
        }

        // Drain any stale data that may have accumulated in the receive path since
        // the last command.  This prevents a lingering DIR or 996 response from
        // being mis-read as the *HEXFILE response.
        //
        // Root cause: if two DIR commands were sent in quick succession (e.g. one
        // before wire-trace was enabled and one after), the second DIR response
        // can arrive while the first sendRaw() is still being consumed, and the
        // surplus bytes sit in the OS TCP receive buffer until the next read.
        // Without this drain, lamaUploadHexFile's waitForResponse() would return
        // the stale DIR body and report a false "upload OK", while the real
        // HEXFILE response would be consumed by the subsequent *DIR poll.
        drainReceiveBuffer();

        int saved = socket.getSoTimeout();
        int transferTimeout = Math.max(saved, DEFAULT_TRANSFER_SO_TIMEOUT_MS);
        wireLog("--- SO_TIMEOUT set to " + transferTimeout
                + " ms for *HEXFILE transfer");
        socket.setSoTimeout(transferTimeout);

        try {
            // ── Step 1: send *HEXFILE command, read initial 000<ACK> ─────────────
            //
            // From the VCOMSVR manual (verbatim example):
            //
            //   Host sends:     *HEXFILE,nologo.pcx<CR>
            //   Labeller sends: 000<ACK>                 ← command accepted
            //   Host sends:     :B30000000A05…92<CR><LF> ← one data record
            //   Labeller sends: 000<ACK>                 ← record accepted
            //   Host sends:     :0000000000<CR><LF>      ← End Block (EOF)
            //   Labeller sends: 000<ACK>                 ← transfer complete
            //
            // "000<ACK>" = ASCII "000" (3 × 0x30) + binary ACK byte (0x06).
            // waitForResponse() reads text until the 0x06 byte and returns a
            // LabellerResponse with body "000" and isOk()=true.
            //
            // Every step — command, each data record, and the EOF record — is
            // acknowledged with 000<ACK> before the host may send the next item.
            byte[] cmdBytes = ("*HEXFILE," + filename + "\r")
                    .getBytes(StandardCharsets.US_ASCII);
            wireLog(">>> TX  \"*HEXFILE," + filename + "\\r\"  [" + hexOf(cmdBytes) + "]");
            synchronized (out) {
                out.write(cmdBytes);
                out.flush();
            }

            LabellerResponse cmdAck = waitForResponse();
            wireLog("--- *HEXFILE command ACK  body=\"" + cmdAck.getBody()
                    + "\"  ok=" + cmdAck.isOk());
            if (!cmdAck.isOk()) {
                wireLog("!!! *HEXFILE command rejected: " + cmdAck);
                return cmdAck;
            }

            // ── Step 2: send each HEX record, wait for 000<ACK> after each ───────
            //
            // Records (data AND EOF) use CRLF line endings.
            // The EOF record (:0000000000) is NOT a special case — it receives
            // the same 000<ACK> handshake as data records.
            // The labeller fires 996 Receive timeout if the next record does not
            // arrive within ~4 s of its ACK, so don't delay between records.
            String hexText = IntelHexCodec.encode(data);
            String[] records = hexText.split("\r");
            int sent = 0;
            LabellerResponse lastAck = cmdAck;

            for (String record : records) {
                if (record.isEmpty()) continue;
                sent++;

                boolean isEof = record.equals(":0000000000")
                        || record.startsWith(":00000001");

                byte[] recBytes = (record + "\r\n").getBytes(StandardCharsets.US_ASCII);
                wireLog(">>> TX  HEX[" + sent + "]  " + record
                        + (isEof ? "  (End Block)" : ""));
                synchronized (out) {
                    out.write(recBytes);
                    out.flush();
                }

                lastAck = waitForResponse();   // 000<ACK> for every record
                wireLog("<<< RX  ACK[" + sent + "]  body=\"" + lastAck.getBody()
                        + "\"  ok=" + lastAck.isOk()
                        + (isEof ? "  (End Block)" : ""));

                if (!lastAck.isOk()) {
                    wireLog("!!! *HEXFILE  labeller NAK on record " + sent
                            + ": " + lastAck);
                    return lastAck;
                }
            }

            // The EOF record's 000<ACK> signals transfer complete (lastAck).
            // Some firmware versions additionally emit a CMD-> status prompt;
            // try to read it with a short timeout so we don't block forever if
            // the firmware just ends at the EOF ACK.
            wireLog("--- *HEXFILE  " + sent
                    + " records sent (incl. End Block)  checking for trailing CMD->");
            int savedInner = socket.getSoTimeout();
            try {
                socket.setSoTimeout(2_000);
                try {
                    LabellerResponse trailing = waitForResponse();
                    wireLog("--- *HEXFILE trailing CMD->  body=\""
                            + trailing.getBody() + "\"  ok=" + trailing.isOk());
                    wireLog("--- *HEXFILE complete  result="
                            + (trailing.isOk() ? "OK" : "FAIL"));
                    return trailing;
                } catch (java.net.SocketTimeoutException ste) {
                    // No trailing CMD-> — the End Block's 000<ACK> is the final word.
                    wireLog("--- *HEXFILE complete (no trailing CMD->)"
                            + "  result=" + (lastAck.isOk() ? "OK" : "FAIL"));
                    return lastAck;
                }
            } finally {
                socket.setSoTimeout(savedInner);
            }

        } finally {
            try { socket.setSoTimeout(saved); } catch (IOException ignored) {}
            wireLog("--- SO_TIMEOUT restored to " + saved + " ms");
        }
    }

    /**
     * Download a binary file from the labeller using {@code *TYPEHEX} and
     * decode the Intel HEX response into raw bytes.
     *
     * <p>This is a convenience wrapper around {@link #lamaTypeFileHex(String)}
     * that performs the Intel HEX decoding automatically.
     *
     * @param filename remote filename on the labeller (e.g. {@code "label1.llf"})
     * @return the decoded raw binary content
     * @throws IOException if the command fails or the HEX data is malformed
     */
    public byte[] lamaDownloadHexFileAsBytes(String filename) throws IOException {
        LabellerResponse resp = lamaTypeFileHex(filename);
        if (!resp.isOk()) {
            throw new IOException(
                    "*TYPEHEX failed for '" + filename + "': " + resp);
        }
        return IntelHexCodec.decode(resp.getBody());
    }

    // =========================================================================
    // Intel HEX codec (public static utilities)
    // =========================================================================

    /**
     * Encode a byte array as an Intel HEX text string suitable for sending to
     * the labeller via {@code *HEXFILE}.
     *
     * <p>Each data record contains up to {@link #INTEL_HEX_RECORD_SIZE} bytes.
     * Lines are terminated with {@code <CR>} only (not {@code <CR><LF>}) as
     * required by the Logopak protocol. The sequence ends with the Logopak
     * end-of-file record {@code :0000000000<CR>}.
     *
     * @param data binary content to encode; must not be null
     * @return Intel HEX encoded string ready to write to the labeller socket
     * @throws IllegalArgumentException if {@code data} exceeds 65535 bytes
     */
    /** @deprecated Use {@link IntelHexCodec#encode(byte[])} directly. */
    @Deprecated
    public static String encodeIntelHex(byte[] data) {
        return IntelHexCodec.encode(data);
    }

    /** @deprecated Use {@link IntelHexCodec#decode(String)} directly. */
    @Deprecated
    public static byte[] decodeIntelHex(String hexText) throws IOException {
        return IntelHexCodec.decode(hexText);
    }

    // =========================================================================
    // LSP / LOGOSTEP – I/O state commands
    // =========================================================================

    /**
     * {@code &GETSTAT,<port><number>} – Get the current state of a LOGOSTEP I/O
     * port immediately (does not wait for a state change).
     *
     * <p>Reply format: {@code {3.[Port][Number]:[State]}<CR>000<ACK>}
     *
     * <p>Example: {@code lspGetState("IN", "001")} sends {@code &GETSTAT,IN001}.
     *
     * @param portName   port type identifier, e.g. {@code "IN"}, {@code "OUT"},
     *                   {@code "MOT"}
     * @param portNumber 3-digit zero-padded port number, e.g. {@code "001"}
     * @return labeller response; body contains the state string
     * @throws IOException on communication error
     */
    public LabellerResponse lspGetState(String portName, String portNumber) throws IOException {
        return sendRaw("&GETSTAT," + portName + portNumber);
    }

    /**
     * {@code &SET,<port><number>,<bits>} – Set I/O bits on the specified
     * LOGOSTEP port.
     *
     * @param portName   port type identifier (e.g. {@code "OUT"})
     * @param portNumber 3-digit zero-padded port number (e.g. {@code "001"})
     * @param bits       bitmask of bits to set
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspSetBits(String portName, String portNumber, int bits)
            throws IOException {
        return sendRaw(String.format("&SET,%s%s,%d", portName, portNumber, bits));
    }

    /**
     * {@code &RESET,<port><number>,<bits>} – Reset (clear) I/O bits on the
     * specified LOGOSTEP port.
     *
     * @param portName   port type identifier
     * @param portNumber 3-digit zero-padded port number
     * @param bits       bitmask of bits to reset
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspResetBits(String portName, String portNumber, int bits)
            throws IOException {
        return sendRaw(String.format("&RESET,%s%s,%d", portName, portNumber, bits));
    }

    /**
     * {@code &TEST,<symbolicName>} – Test the state of a named LOGOSTEP I/O bit.
     *
     * <p>The labeller sends {@code 000<ACK>} followed by a
     * {@code STATE,name=value<CR>} push. In synchronous mode the STATE line
     * appears at the start of the next read. For robust monitoring of I/O
     * changes use {@link #lspEnableReporting(Consumer)} instead.
     *
     * @param symbolicName symbolic I/O name (e.g. {@code "label.sensor"})
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspTest(String symbolicName) throws IOException {
        return sendRaw("&TEST," + symbolicName);
    }

    /**
     * {@code &LABELLERTYPE} – Query the labeller type identifier string.
     *
     * @return labeller response; body contains the type string
     * @throws IOException on communication error
     */
    public LabellerResponse lspGetLabellerType() throws IOException {
        return sendRaw("&LABELLERTYPE");
    }

    // =========================================================================
    // LSP / LOGOSTEP – interpreter control
    // =========================================================================

    /**
     * {@code &LOGOSTEPSTATE} – Query the current state of the LOGOSTEP
     * interpreter (running, halted, etc.).
     *
     * @return labeller response; body contains the state description
     * @throws IOException on communication error
     */
    public LabellerResponse lspGetLogostepState() throws IOException {
        return sendRaw("&LOGOSTEPSTATE");
    }

    /**
     * {@code &HALT} – Halt the LOGOSTEP interpreter.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspHalt() throws IOException {
        return sendRaw("&HALT");
    }

    /**
     * {@code &RUN} – Start the LOGOSTEP interpreter.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspRun() throws IOException {
        return sendRaw("&RUN");
    }

    /**
     * {@code &CONTINUE} – Resume the LOGOSTEP interpreter after a halt.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspContinue() throws IOException {
        return sendRaw("&CONTINUE");
    }

    // =========================================================================
    // LSP / LOGOSTEP – symbol table and timestamp recording
    // =========================================================================

    /**
     * {@code &SYMBOLTABLE} – List all symbolic I/O names defined in the
     * LOGOSTEP configuration.
     *
     * <p>Requires verbose mode to be enabled first (see {@link #lamaEnableVerbose()}).
     *
     * @return labeller response; body contains the symbol table listing
     * @throws IOException on communication error
     */
    public LabellerResponse lspListSymbols() throws IOException {
        return sendRaw("&SYMBOLTABLE");
    }

    /**
     * {@code &NODELIST} – List all LOGOSTEP nodes.
     *
     * @return labeller response; body contains the node list
     * @throws IOException on communication error
     */
    public LabellerResponse lspListNodes() throws IOException {
        return sendRaw("&NODELIST");
    }

    /**
     * {@code &TIMESTAMP,<name>} – Enable timestamp recording for the named
     * I/O bit.
     *
     * @param symbolicName symbolic I/O name
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspEnableTimestamp(String symbolicName) throws IOException {
        return sendRaw("&TIMESTAMP," + symbolicName);
    }

    /**
     * {@code &UNTIMESTAMP,<name>} – Disable timestamp recording for the named
     * I/O bit.
     *
     * @param symbolicName symbolic I/O name
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspDisableTimestamp(String symbolicName) throws IOException {
        return sendRaw("&UNTIMESTAMP," + symbolicName);
    }

    /**
     * {@code &TIMESTAMPLIST} – Retrieve the accumulated timestamp log.
     *
     * @return labeller response; body contains the timestamp log entries
     * @throws IOException on communication error
     */
    public LabellerResponse lspGetTimestampList() throws IOException {
        return sendRaw("&TIMESTAMPLIST");
    }

    /**
     * {@code &TIMESSETUP} – Query or set LOGOSTEP timing entries.
     * Call with no argument to retrieve current settings; pass a value string
     * to update.
     *
     * @param args optional arguments; pass {@code null} or empty string to query
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspTimesSetup(String args) throws IOException {
        return sendRaw((args == null || args.isEmpty()) ? "&TIMESSETUP" : "&TIMESSETUP," + args);
    }

    /**
     * {@code &LISTABLE} – List all entries registered with {@code &CHECK}
     * for asynchronous bit monitoring.
     *
     * @return labeller response; body contains the monitoring table
     * @throws IOException on communication error
     */
    public LabellerResponse lspListMonitorTable() throws IOException {
        return sendRaw("&LISTABLE");
    }

    /**
     * {@code &RESTABLE} – Clear all {@code &CHECK} asynchronous monitoring entries.
     *
     * @return labeller response
     * @throws IOException on communication error
     */
    public LabellerResponse lspClearMonitorTable() throws IOException {
        return sendRaw("&RESTABLE");
    }

    // =========================================================================
    // LSP – asynchronous I/O state reporting (&REPORT / &NOREPORT)
    // =========================================================================

    /**
     * Enable asynchronous I/O state push messages and start the background
     * reader thread.
     *
     * <p>Sends {@code &REPORT}. When any monitored I/O bit changes state, the
     * labeller pushes {@code STATE,name=value<CR>} messages unsolicited. The
     * background reader thread dispatches each such line to the supplied
     * {@code listener}.
     *
     * <p>While reporting is active, {@link #sendRaw} and all command methods
     * use an internal blocking queue so that unsolicited pushes do not corrupt
     * synchronous command responses.
     *
     * <p>Stop reporting by calling {@link #lspDisableReporting()}.
     *
     * @param listener callback invoked (on the reader thread) with each raw
     *                 {@code STATE,name=value} string; must be thread-safe
     * @throws IOException on communication error
     */
    public void lspEnableReporting(Consumer<String> listener) throws IOException {
        this.stateListener = listener;
        startReaderThread();
        sendRaw("&REPORT");
    }

    /**
     * Disable asynchronous I/O state reporting.
     *
     * <p>Sends {@code &NOREPORT} and stops the background reader thread.
     *
     * @throws IOException on communication error
     */
    public void lspDisableReporting() throws IOException {
        sendRaw("&NOREPORT");
        stopReaderThread();
        this.stateListener = null;
    }

    // =========================================================================
    // AGENT commands
    // =========================================================================

    /**
     * {@code ?versions} – Query software and hardware version strings from the labeller.
     *
     * <p>On success the version map accessible via {@link #getVersion(String)} and
     * {@link #getVersions()} is populated.  The map is cleared and repopulated on
     * each successful call; it retains its previous contents if the command returns NAK.
     *
     * <p>Example response body:
     * <pre>
     * AGENT 7.500 Versions
     * LACE         7.500    2012-06-08 09:21:08
     * LEAP         7.500    2012-06-08 09:19:45
     * LSP          7.500    2012-06-08 09:19:53
     * LAMA         7.500    2012-06-08 09:19:25
     * ...
     * 0 No Error(s)
     * </pre>
     *
     * @return labeller response (multi-line body on success)
     * @throws IOException on communication error
     */
    public LabellerResponse agentQueryVersions() throws IOException {
        LabellerResponse r = sendRaw("?versions");
        if (r.isOk()) {
            parseVersions(r.getBody());
        }
        return r;
    }

    private void parseVersions(String body) {
        versions.clear();
        for (String line : body.split("\r")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length < 2) continue;
            try {
                versions.put(tokens[0].toUpperCase(), Double.parseDouble(tokens[1]));
            } catch (NumberFormatException ignored) {
                // non-numeric version token – skip
            }
        }
    }

    /**
     * Returns the machine serial number retrieved from Flash on the CPUPL2 board
     * via {@code ?serialnumber} during connection initialisation.
     *
     * <p>Returns an empty string if the machine has not been configured with a serial
     * number or if the labeller firmware does not support AGENT commands.
     */
    public String getLabellerSerialNumber() {
        return labellerSerialNumber;
    }

    /**
     * Returns the line/conveyor designation retrieved from Flash on the CPUPL2 board
     * via {@code ?line} during connection initialisation.
     *
     * <p>Returns an empty string if the machine has not been configured with a line
     * name or if the labeller firmware does not support AGENT commands.
     */
    public String getLabellerLine() {
        return labellerLine;
    }

    /**
     * Send a single-value AGENT query and return the value from the {@code >} prefixed
     * response line.  Handles the AGENT response format:
     * <pre>
     * &gt;value\r
     * 0 No Error(s)\r
     * </pre>
     *
     * @param command AGENT command name without the {@code ?} prefix (e.g. {@code "serialnumber"})
     * @return the value string, or an empty string if the command returns NAK, the value
     *         is blank, or the AGENT layer is unavailable
     */
    private String agentQuerySingleValue(String command) {
        try {
            LabellerResponse r = sendRaw("?" + command);
            if (!r.isOk()) return "";
            for (String line : r.getBody().split("\r")) {
                String trimmed = line.trim();
                if (trimmed.startsWith(">")) {
                    return trimmed.substring(1).trim();
                }
            }
        } catch (IOException ignored) {
            // AGENT layer unavailable on this firmware – leave field empty
        }
        return "";
    }

    /**
     * Returns {@code true} if the connected LAMA firmware supports path-prefixed
     * {@code *DIR} commands (e.g. {@code *DIR,/c9/*.llf,SDT}).
     * Path-prefix support was introduced in LAMA 7.401. Returns {@code false}
     * when the version is unknown or older, in which case only bare wildcards
     * (e.g. {@code *.llf}) should be used.
     */
    public boolean supportsPathPrefixedDir() {
        Double lama = versions.get("LAMA");
        return lama != null && lama >= 7.401;
    }

    // =========================================================================
    // Low-level send / receive
    // =========================================================================

    /**
     * Send a raw command string (without trailing {@code \r}) and return the
     * labeller's response. The {@code \r} terminator is appended automatically.
     *
     * <p>This is the escape hatch for commands not yet wrapped by a dedicated
     * method. All three command layers work:
     * <pre>{@code
     * // LEAP FFP
     * sendRaw("MAKE,label1");
     * // LAMA/LACE
     * sendRaw("*GETTIME");
     * // LSP
     * sendRaw("&GETSTAT,IN001");
     * }</pre>
     *
     * @param command command text without trailing CR
     * @return the labeller response
     * @throws IOException           on communication error or read timeout
     * @throws IllegalStateException if called before {@link #connect()}
     */
    public LabellerResponse sendRaw(String command) throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException(
                    "Not connected to labeller at " + host + ":" + dataPort);
        }
        LabellerResponse result = sendRawOnce(command);

        // Error 996 "Receive timeout" means the labeller's inactivity timer fired
        // during a gap between commands and left a stale error message in the
        // stream.  It is safe to retry once after a brief pause; the second
        // attempt always reaches the labeller cleanly.
        if (!result.isOk() && result.getBody().contains("996")) {
            wireLog("--- 996 Receive timeout detected — waiting 500 ms then retrying");
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            result = sendRawOnce(command);
        }
        return result;
    }

    /**
     * Send raw text exactly as supplied (no terminator appended) and return
     * the labeller's response. Caller is responsible for embedding whatever
     * line terminators the device expects.
     *
     * <p>Used by manual-transmit UIs where the operator chooses the EOL
     * sequence. For normal command traffic use {@link #sendRaw(String)}.
     */
    public LabellerResponse sendText(String text) throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException(
                    "Not connected to labeller at " + host + ":" + dataPort);
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        wireLog(">>> TX  (" + bytes.length + " bytes)  [" + hexOf(bytes) + "]");
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
        return waitForResponse();
    }

    /**
     * Fire-and-forget text send: writes the bytes verbatim (no appended
     * terminator) and returns immediately without waiting for a response.
     *
     * <p>Intended for manual / diagnostic UIs where the host is running in
     * verbose mode (no binary ACK/NAK terminator, so
     * {@link #waitForResponse()} would always time out). Any bytes the
     * labeller sends back will still be captured by the raw RX tee and the
     * reader thread; they just aren't parsed as a {@link LabellerResponse}
     * here.
     *
     * <p>After writing, reads up to {@code drainMs} milliseconds of any
     * immediately-available response bytes off the socket so they flow
     * through the RX tee without sitting in the OS socket buffer. This is
     * a best-effort drain — a late response arriving after the drain window
     * will be surfaced by whatever calls {@code read()} next.
     */
    public void sendTextNoReply(String text, int drainMs) throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException(
                    "Not connected to labeller at " + host + ":" + dataPort);
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        wireLog(">>> TX  (" + bytes.length + " bytes, no-reply)  [" + hexOf(bytes) + "]");
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
        drainIncomingFor(Math.max(0, drainMs));
    }

    /**
     * Reads any immediately-available RX bytes for up to {@code millis}
     * milliseconds so they flow through the tee. Stops at the first idle
     * period or when the window expires.
     */
    private void drainIncomingFor(int millis) throws IOException {
        if (millis <= 0) return;
        // If the async reader thread is running, it already owns the input
        // stream — let it surface the response; don't compete for bytes.
        if (readerThread != null && readerThread.isAlive()) return;

        int saved = configuredSoTimeoutMs;
        try {
            socket.setSoTimeout(50);      // short per-read poll window
            long deadline = System.currentTimeMillis() + millis;
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                try {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) break;        // EOF
                    if (n == 0) break;
                } catch (java.net.SocketTimeoutException ste) {
                    // no data ready — loop again until overall deadline
                }
            }
        } finally {
            try { socket.setSoTimeout(saved); } catch (IOException ignored) {}
        }
    }

    /** Single attempt: write {@code command<CR>} and read one response. */
    private LabellerResponse sendRawOnce(String command) throws IOException {
        byte[] bytes = (command + "\r").getBytes(StandardCharsets.US_ASCII);
        wireLog(">>> TX  \"" + command + "\\r\"  [" + hexOf(bytes) + "]");
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
        return waitForResponse();
    }

    /**
     * Like {@link #sendRaw(String)} but temporarily widens the socket
     * read-timeout for the duration of the response read.  Used by file-
     * transfer commands ({@code *TYPEHEX}, {@code *HEXFILE}) where the
     * labeller may pause several seconds between HEX chunks while reading
     * from slow flash storage.
     */
    @SuppressWarnings("unused")
	private LabellerResponse sendRawWithExtendedTimeout(String command, int soTimeoutMs)
            throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException(
                    "Not connected to labeller at " + host + ":" + dataPort);
        }
        int saved = socket.getSoTimeout();
        wireLog("--- SO_TIMEOUT widened to " + soTimeoutMs + " ms for file transfer");
        socket.setSoTimeout(soTimeoutMs);
        try {
            byte[] bytes = (command + "\r").getBytes(StandardCharsets.US_ASCII);
            wireLog(">>> TX  \"" + command + "\\r\"  [" + hexOf(bytes) + "]");
            synchronized (out) {
                out.write(bytes);
                out.flush();
            }
            return waitForResponse();
        } finally {
            try { socket.setSoTimeout(saved); } catch (IOException ignored) {}
            wireLog("--- SO_TIMEOUT restored to " + saved + " ms");
        }
    }

    // =========================================================================
    // Internal: receive-buffer drain
    // =========================================================================

    /**
     * Discards any bytes that are already waiting in the receive path before a
     * command whose response must be read cleanly.
     *
     * <p>In <b>synchronous mode</b> (no reader thread): sets a 150 ms SO_TIMEOUT
     * and reads until the socket goes quiet, logging anything discarded.  The
     * 150 ms cost is paid only when there actually is stale data.
     *
     * <p>In <b>async mode</b> (reader thread active): empties the
     * {@link #responseQueue} of any items that the reader thread queued before
     * the command was sent.
     */
    private void drainReceiveBuffer() {
        if (readerThread != null && readerThread.isAlive()) {
            // Async mode – drain the response queue
            int n = 0;
            LabellerResponse stale;
            while ((stale = responseQueue.poll()) != null) {
                wireLog("--- DRAIN  discarding stale queued response: "
                        + escaped(stale.getBody()));
                n++;
            }
            if (n > 0) wireLog("--- DRAIN  removed " + n + " stale response(s) from queue");
            return;
        }

        // Synchronous mode – drain the socket receive buffer with a short timeout
        if (socket == null) return;
        int saved = -1;
        try {
            saved = socket.getSoTimeout();
            socket.setSoTimeout(150);          // just long enough to catch in-flight bytes
            StringBuilder stale = new StringBuilder();
            List<Integer>  raw  = new ArrayList<>();
            try {
                int b;
                while ((b = in.read()) != -1) {
                    raw.add(b);
                    stale.append((char) b);
                }
            } catch (java.net.SocketTimeoutException expected) {
                // no (more) data – that is the normal exit
            }
            if (!raw.isEmpty()) {
                wireLog("--- DRAIN  discarded " + raw.size()
                        + " stale byte(s) before *HEXFILE: "
                        + escaped(stale.toString())
                        + "  raw=[" + hexOf(raw) + "]");
            }
        } catch (IOException e) {
            wireLog("--- DRAIN  IO error while draining: " + e.getMessage());
        } finally {
            if (saved >= 0 && socket != null) {
                try { socket.setSoTimeout(saved); } catch (IOException ignored) {}
            }
        }
    }

    // =========================================================================
    // Internal: response waiting
    // =========================================================================

    private LabellerResponse waitForResponse() throws IOException {
        if (readerThread != null && readerThread.isAlive()) {
            // Async mode: idle timeout — the deadline is extended each time the reader
            // thread receives a byte, so a long but continuous response (e.g. a large
            // *DIR listing arriving line-by-line) never times out.  The clock only
            // counts down during genuine silence on the wire.
            try {
                long deadline = System.currentTimeMillis() + ASYNC_RESPONSE_TIMEOUT_MS;
                while (true) {
                    LabellerResponse r = responseQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (r != null) return r;
                    // Extend the deadline as long as the reader thread is receiving bytes
                    long idleUntil = lastByteReceivedAt + ASYNC_RESPONSE_TIMEOUT_MS;
                    if (idleUntil > deadline) deadline = idleUntil;
                    if (System.currentTimeMillis() >= deadline) {
                        wireLog("!!! TIMEOUT  (" + ASYNC_RESPONSE_TIMEOUT_MS
                                + " ms idle) — no data received from " + host + ":" + dataPort);
                        throw new IOException(
                                "Timeout waiting for response from labeller at " + host);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for labeller response", e);
            }
        } else {
            // Synchronous mode: read directly from the socket
            return readResponseDirect();
        }
    }

    /**
     * Read the response directly from the socket input stream (synchronous mode).
     *
     * <p>VCOMSVR uses a text-terminal protocol: every response ends with the
     * five-byte prompt {@code CMD->} rather than an ACK/NAK byte. The method
     * therefore accepts three possible terminators:
     * <ul>
     *   <li>{@code CMD->} — normal VCOMSVR text response (most commands)</li>
     *   <li>ACK (0x06) — binary ACK from a lower-level LEAP layer</li>
     *   <li>NAK (0x15) — binary NAK from a lower-level LEAP layer</li>
     * </ul>
     * When a {@code CMD->} prompt is detected, success is determined by
     * inspecting the last non-blank line: a line beginning with {@code "0 "}
     * (e.g. {@code "0 No error(s)."}) is treated as ACK; anything else as NAK.
     */
    private LabellerResponse readResponseDirect() throws IOException {
        StringBuilder  sb  = new StringBuilder();
        List<Integer>  raw = new ArrayList<>();
        try {
            int b;
            while ((b = in.read()) != -1) {
                raw.add(b);
                if (b == BYTE_ACK) {
                    LabellerResponse r = LabellerResponse.ack(sb.toString());
                    wireLog("<<< RX  ACK  body=" + escaped(sb.toString())
                            + "  raw=[" + hexOf(raw) + "]");
                    return r;
                }
                if (b == BYTE_NAK) {
                    LabellerResponse r = LabellerResponse.nak(sb.toString());
                    wireLog("<<< RX  NAK  body=" + escaped(sb.toString())
                            + "  raw=[" + hexOf(raw) + "]");
                    return r;
                }
                sb.append((char) b);
                if (endsWithCmdPrompt(sb)) {
                    return parseCmdPromptResponse(sb, raw);
                }
            }
            wireLog("!!! CONNECTION CLOSED — partial body=" + escaped(sb.toString())
                    + "  raw=[" + hexOf(raw) + "]");
            throw new IOException("Connection closed by labeller before response was complete");
        } catch (java.net.SocketTimeoutException e) {
            wireLog("!!! SOCKET TIMEOUT (" + socket.getSoTimeout() + " ms)"
                    + "  partial body=" + escaped(sb.toString())
                    + "  raw=[" + hexOf(raw) + "]");
            throw e;
        }
    }

    /**
     * Returns {@code true} when the buffer ends with the five-character
     * VCOMSVR command prompt {@code CMD->}.
     */
    private static boolean endsWithCmdPrompt(StringBuilder sb) {
        int len = sb.length();
        return len >= 5
                && sb.charAt(len - 5) == 'C'
                && sb.charAt(len - 4) == 'M'
                && sb.charAt(len - 3) == 'D'
                && sb.charAt(len - 2) == '-'
                && sb.charAt(len - 1) == '>';
    }

    /**
     * Converts a {@code CMD->}-terminated buffer into a {@link LabellerResponse}.
     *
     * <p>Strips the trailing {@code CMD->} and any trailing CR/LF characters,
     * then examines the last non-blank line of the body. If that line starts
     * with {@code "0 "} (e.g. {@code "0 No error(s)."}) the response is ACK;
     * otherwise it is NAK.
     */
    private LabellerResponse parseCmdPromptResponse(StringBuilder sb, List<Integer> raw) {
        // Remove trailing "CMD->"
        String body = sb.substring(0, sb.length() - 5);
        // Strip trailing CR / LF
        int end = body.length();
        while (end > 0 && (body.charAt(end - 1) == '\r' || body.charAt(end - 1) == '\n')) {
            end--;
        }
        body = body.substring(0, end);

        // Determine success from last non-blank line
        boolean success = false;
        String[] lines = body.split("\r");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                success = line.startsWith("0 ");
                break;
            }
        }

        if (success) {
            wireLog("<<< RX  ACK(CMD->)  body=" + escaped(body)
                    + "  raw=[" + hexOf(raw) + "]");
            return LabellerResponse.ack(body);
        } else {
            wireLog("<<< RX  NAK(CMD->)  body=" + escaped(body)
                    + "  raw=[" + hexOf(raw) + "]");
            return LabellerResponse.nak(body);
        }
    }

    // =========================================================================
    // Internal: async reader thread
    // =========================================================================

    private void startReaderThread() {
        if (readerThread != null && readerThread.isAlive()) return;
        readerThread = new Thread(this::readerLoop, "logo-reader-" + host);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void stopReaderThread() {
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
            readerThread = null;
        }
    }

    /**
     * Background reader loop (async mode only).
     *
     * <p>Reads bytes from the socket continuously and routes them as follows:
     * <ul>
     *   <li>When {@code CMD->} is detected at the end of the buffer, the
     *       accumulated text is converted to a {@link LabellerResponse} via
     *       {@link #parseCmdPromptResponse} and placed on {@link #responseQueue}.
     *       This is the normal VCOMSVR text-terminal terminator.</li>
     *   <li>When an ACK (0x06) or NAK (0x15) byte is received, the accumulated
     *       text is similarly queued (binary LEAP layer fall-back).</li>
     *   <li>When a CR-terminated line that starts with {@code STATE,} is completed,
     *       it is dispatched to the registered {@link #stateListener}. The buffer
     *       is then cleared so the STATE line does not contaminate the next
     *       command response.</li>
     *   <li>All other bytes are accumulated in the buffer.</li>
     * </ul>
     */
    private void readerLoop() {
        StringBuilder sb  = new StringBuilder();
        List<Integer>  raw = new ArrayList<>();
        try {
            int b;
            while (!Thread.currentThread().isInterrupted() && (b = in.read()) != -1) {
                lastByteReceivedAt = System.currentTimeMillis();
                raw.add(b);
                if (b == BYTE_ACK) {
                    wireLog("<<< RX  ACK  body=" + escaped(sb.toString())
                            + "  raw=[" + hexOf(raw) + "]");
                    responseQueue.put(LabellerResponse.ack(sb.toString()));
                    sb.setLength(0);
                    raw.clear();
                } else if (b == BYTE_NAK) {
                    wireLog("<<< RX  NAK  body=" + escaped(sb.toString())
                            + "  raw=[" + hexOf(raw) + "]");
                    responseQueue.put(LabellerResponse.nak(sb.toString()));
                    sb.setLength(0);
                    raw.clear();
                } else {
                    sb.append((char) b);
                    if (endsWithCmdPrompt(sb)) {
                        // Normal VCOMSVR text-terminal response terminator
                        responseQueue.put(parseCmdPromptResponse(sb, raw));
                        sb.setLength(0);
                        raw.clear();
                    } else if (b == '\r') {
                        // Check if the current buffer is a complete STATE push message
                        String line = sb.toString().trim();
                        if (line.startsWith("STATE,")) {
                            wireLog("<<< STATE  " + line
                                    + "  raw=[" + hexOf(raw) + "]");
                            Consumer<String> l = stateListener;
                            if (l != null) l.accept(line);
                            sb.setLength(0);
                            raw.clear();
                        }
                        // Otherwise this CR is part of a multi-line response body;
                        // leave it in the buffer until CMD-> arrives.
                    }
                }
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                wireLog("!!! READER IO ERROR: " + e.getMessage()
                        + "  partial raw=[" + hexOf(raw) + "]");
                // Connection dropped unexpectedly; unblock any waiting sendRaw() call
                responseQueue.offer(LabellerResponse.nak("999"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Wire logger
    // =========================================================================

    /**
     * Register a wire-level trace logger. When set, the labeller emits
     * detailed diagnostic messages for every command sent and every byte
     * stream received, including hex dumps. This is invaluable for diagnosing
     * protocol mismatches, unexpected responses, and timeout root causes.
     *
     * <p>Message prefixes:
     * <ul>
     *   <li>{@code ---} – connection lifecycle (CONNECT / CONNECTED / DISCONNECT)</li>
     *   <li>{@code >>>} – TX: command bytes sent to the labeller</li>
     *   <li>{@code <<<} – RX: response received from the labeller (ACK / NAK / STATE)</li>
     *   <li>{@code !!!} – error or timeout (includes any partial bytes received)</li>
     * </ul>
     *
     * <p>The logger is called from the thread that triggered the event (either
     * the calling thread or the background reader thread). Implementations must
     * be thread-safe.
     *
     * @param logger callback that receives each trace line; {@code null} to disable
     */
    // setWireLogger(Consumer) is inherited from AbstractLogoLabeller.
    // wireLog(String) is inherited from AbstractLogoLabeller.

    /** Disable wire logging (equivalent to {@code setWireLogger(null)}). */
    public void clearWireLogger() {
        this.wireLogger = null;
    }

    /**
     * Registers byte-level TX/RX sinks. Every byte that crosses the socket
     * in either direction is forwarded to the matching sink. Either may be
     * {@code null} to disable that direction.
     */
    public void setRawLoggers(Consumer<byte[]> tx, Consumer<byte[]> rx) {
        this.rawTxSink = tx;
        this.rawRxSink = rx;
    }

    /**
     * OutputStream that forwards a copy of every write to a lazily-resolved
     * byte sink. The supplier is read on every write so the sink can be
     * swapped at runtime without recreating the stream.
     */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final java.util.function.Supplier<Consumer<byte[]>> sinkSupplier;

        TeeOutputStream(OutputStream delegate, java.util.function.Supplier<Consumer<byte[]>> sinkSupplier) {
            this.delegate     = delegate;
            this.sinkSupplier = sinkSupplier;
        }

        @Override public void write(int b) throws IOException {
            delegate.write(b);
            Consumer<byte[]> s = sinkSupplier.get();
            if (s != null) s.accept(new byte[] { (byte) b });
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            Consumer<byte[]> s = sinkSupplier.get();
            if (s != null && len > 0) {
                byte[] copy = new byte[len];
                System.arraycopy(b, off, copy, 0, len);
                s.accept(copy);
            }
        }

        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    /**
     * InputStream that forwards a copy of every successfully-read byte to a
     * lazily-resolved byte sink.
     */
    private static final class TeeInputStream extends InputStream {
        private final InputStream delegate;
        private final java.util.function.Supplier<Consumer<byte[]>> sinkSupplier;

        TeeInputStream(InputStream delegate, java.util.function.Supplier<Consumer<byte[]>> sinkSupplier) {
            this.delegate     = delegate;
            this.sinkSupplier = sinkSupplier;
        }

        @Override public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                Consumer<byte[]> s = sinkSupplier.get();
                if (s != null) s.accept(new byte[] { (byte) b });
            }
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                Consumer<byte[]> s = sinkSupplier.get();
                if (s != null) {
                    byte[] copy = new byte[n];
                    System.arraycopy(b, off, copy, 0, n);
                    s.accept(copy);
                }
            }
            return n;
        }

        @Override public int available() throws IOException { return delegate.available(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    /**
     * Returns a hex-dump string of a list of byte values, e.g. {@code "41 4C 0D"}.
     * Non-printable bytes are shown numerically; printable ASCII bytes are also
     * annotated in the {@link #escaped} output.
     */
    private static String hexOf(List<Integer> bytes) {
        if (bytes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(bytes.size() * 3);
        for (int b : bytes) sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    private static String hexOf(byte[] bytes) {
        if (bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    /**
     * Returns the string with control characters shown as escape sequences so
     * the wire log is human-readable on a single line.
     */
    private static String escaped(String s) {
        if (s == null || s.isEmpty()) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case 0x06 -> sb.append("<ACK>");
                case 0x15 -> sb.append("<NAK>");
                case 0x02 -> sb.append("<STX>");
                case 0x03 -> sb.append("<ETX>");
                default   -> {
                    if (c < 0x20 || c > 0x7E) sb.append(String.format("\\x%02X", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private static int resolveResetPort(int dataPort) {
        return switch (dataPort) {
            case PORT_XA -> 9084;
            case PORT_XB -> 9184;
            case PORT_XC -> 9284;
            case PORT_XD -> 9384;
            default -> throw new IllegalArgumentException(
                    "Unknown data port " + dataPort
                            + ". Use one of the PORT_XA/XB/XC/XD constants.");
        };
    }

    // =========================================================================
    // ILogoLabeller interface implementations
    // =========================================================================

    @Override
    public LabellerResult uploadLayoutFile(String filename, byte[] data) throws IOException {
        LabellerResponse r = lamaUploadHexFile(filename, data);
        return LabellerResult.fromPl3(r.getBody(), r.isOk());
    }

    @Override
    public byte[] downloadLayoutFile(String filename) throws IOException {
        return lamaDownloadHexFileAsBytes(filename);
    }

    @Override
    public LabellerResult deleteLayoutFile(String filename) throws IOException {
        LabellerResponse r = lamaDeleteFile(filename);
        return LabellerResult.fromPl3(r.getBody(), r.isOk());
    }

    @Override
    public List<FileInfo> listLayoutFiles() throws IOException {
        return toFileInfoList(lamaListFiles("*.llf,*.lqf"));
    }

    @Override
    public LabellerResult uploadImageFile(String filename, byte[] data) throws IOException {
        LabellerResponse r = lamaUploadHexFile(filename, data);
        return LabellerResult.fromPl3(r.getBody(), r.isOk());
    }

    @Override
    public LabellerResult deleteImageFile(String filename) throws IOException {
        LabellerResponse r = lamaDeleteFile(filename);
        return LabellerResult.fromPl3(r.getBody(), r.isOk());
    }

    @Override
    public List<FileInfo> listImageFiles() throws IOException {
        return toFileInfoList(lamaListFiles("*.bmp,*.pcx,*.gif,*.png"));
    }

    @Override
    public List<FileInfo> listFiles(String remotePath) throws IOException {
        return toFileInfoList(lamaListFiles(remotePath));
    }

    @Override
    public LabellerResult uploadFile(String remotePath, byte[] data) throws IOException {
        // PL3 *HEXFILE does not accept a path; extract the filename component
        String filename = remotePath.contains("/")
                ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                : remotePath;
        LabellerResponse r = lamaUploadHexFile(filename, data);
        return LabellerResult.fromPl3(r.getBody(), r.isOk());
    }

    @Override
    public byte[] downloadFile(String remotePath) throws IOException {
        String filename = remotePath.contains("/")
                ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                : remotePath;
        return lamaDownloadHexFileAsBytes(filename);
    }

    @Override
    public LabellerResult deleteFile(String remotePath) throws IOException {
        LabellerResponse r = lamaDeleteFile(remotePath);
        return LabellerResult.fromPl3(r.getBody(), r.isOk());
    }

    /**
     * Convert a {@code *DIR,spec,SDT} response body into {@link FileInfo} records.
     *
     * <p>Response line format: {@code name.ext,size,YYMMDD,hhmm} or
     * {@code name.ext,size,YYMMDD,HHMMSS}. Trailing status lines such as
     * {@code "0 No error(s)."} are skipped automatically.
     */
    private static List<FileInfo> toFileInfoList(LabellerResponse r) {
        List<FileInfo> result = new ArrayList<>();
        if (!r.isOk() || r.getBody() == null) return result;
        for (String raw : r.getBody().split("[\r\n]+")) {
            String line = raw.trim();
            if (line.isEmpty() || line.matches("^\\d+ .*")) continue;
            String[] fields = line.split(",", -1);
            String name = fields[0].trim();
            if (name.isEmpty()) continue;

            long size      = 0;
            long epochSecs = 0;
            if (fields.length > 1) {
                try { size = Long.parseLong(fields[1].trim()); }
                catch (NumberFormatException ignored) {}
            }
            if (fields.length > 2) {
                String d = fields[2].trim();
                String t = fields.length > 3 ? fields[3].trim() : "";
                if (d.length() == 6) {
                    try {
                        int yy   = Integer.parseInt(d.substring(0, 2));
                        int mm   = Integer.parseInt(d.substring(2, 4));
                        int dd   = Integer.parseInt(d.substring(4, 6));
                        int yyyy = yy >= 70 ? 1900 + yy : 2000 + yy;
                        int hour = 0, min = 0, sec = 0;
                        if (t.length() >= 4) {
                            hour = Integer.parseInt(t.substring(0, 2));
                            min  = Integer.parseInt(t.substring(2, 4));
                        }
                        if (t.length() == 6) {
                            sec = Integer.parseInt(t.substring(4, 6));
                        }
                        epochSecs = LocalDateTime.of(yyyy, mm, dd, hour, min, sec)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toEpochSecond();
                    } catch (Exception ignored) {}
                }
            }
            boolean isDir = !name.contains(".");
            result.add(new FileInfo(name, size, epochSecs, isDir));
        }
        return result;
    }

    // =========================================================================
    // Inner class: LabellerResponse
    // =========================================================================

    /**
     * Encapsulates a response received from the Logopak labeller.
     *
     * <p>A response consists of:
     * <ul>
     *   <li><b>ok</b> – {@code true} if the labeller sent ACK (0x06);
     *       {@code false} for NAK (0x15).</li>
     *   <li><b>body</b> – all text preceding the ACK/NAK byte. Empty for simple
     *       commands; contains data for read commands such as {@code *TRXLOG},
     *       {@code *GETTIME}, {@code RD}, {@code RCID}, {@code &GETSTAT},
     *       {@code *TYPEHEX}, etc.</li>
     *   <li><b>errorCode</b> – the 3-digit decimal error code extracted from the
     *       body when NAK is received; empty string on success.</li>
     * </ul>
     *
     * <h3>Common LAMA error codes</h3>
     * <ul>
     *   <li>932 – Unknown command</li>
     *   <li>982 – YMODEM protocol timeout</li>
     *   <li>992 – File not found</li>
     *   <li>999 – Labeller offline</li>
     * </ul>
     *
     * <h3>LSP error codes</h3>
     * <ul>
     *   <li>710–723 – I/O related errors</li>
     *   <li>999 – Labeller offline</li>
     * </ul>
     */
    public static final class LabellerResponse {

        private final String  body;
        private final boolean ok;
        private final String  errorCode;

        private LabellerResponse(String body, boolean ok) {
            this.body      = body;
            this.ok        = ok;
            // The error code is the 3-digit number at the end of the body before NAK
            this.errorCode = (!ok && body.length() >= 3)
                    ? body.substring(body.length() - 3).trim()
                    : "";
        }

        static LabellerResponse ack(String body) { return new LabellerResponse(body, true);  }
        static LabellerResponse nak(String body) { return new LabellerResponse(body, false); }

        /** Returns {@code true} if the labeller acknowledged the command successfully. */
        public boolean isOk()         { return ok;        }

        /**
         * Returns the raw response body (all bytes before the ACK/NAK byte).
         * May be empty for simple commands such as {@code P}, {@code CLAY}, etc.
         */
        public String  getBody()      { return body;      }

        /**
         * Returns the 3-digit error code string when {@link #isOk()} is
         * {@code false}, or an empty string on success.
         */
        public String  getErrorCode() { return errorCode; }

        /**
         * Returns the body with leading {@code >} (as used by the {@code RD}
         * command) and surrounding whitespace stripped. Useful for parsing
         * date/time and similar responses.
         */
        public String getBodyTrimmed() {
            String s = body.trim();
            if (s.startsWith(">")) s = s.substring(1);
            return s.trim();
        }

        @Override
        public String toString() {
            return ok
                    ? "ACK[body=" + body + "]"
                    : "NAK[error=" + errorCode + ", body=" + body + "]";
        }
    }
}
