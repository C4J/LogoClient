package com.commander4j.client.pl6;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.commander4j.client.common.AbstractLogoLabeller;
import com.commander4j.client.common.FileInfo;
import com.commander4j.client.common.ILogoLabeller;
import com.commander4j.client.common.LabellerResult;
import com.commander4j.client.common.LogoClientSftp;
import com.commander4j.client.common.PalletLogEntry;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

/**
 * Logopak PowerLeap 6 labeller client.
 * Independent compatibility implementation for local testing — not a Logopak
 * product; see README.txt.
 *
 * <p>PL6 is a Linux-based system that communicates exclusively via SFTP
 * (port 22). It does <em>not</em> support the LEAP/LAMA/LSP TCP command
 * protocol used by PL3. Confirmed by vendor correspondence:
 * <blockquote>"No * Commands. No ! Commands. No ? Commands."</blockquote>
 *
 * <h2>Connection</h2>
 * <pre>{@code
 * try (LogoClientPL6 pl6 = new LogoClientPL6("192.168.1.200")) {
 *     pl6.connect("root", "your-password-here");
 *
 *     // Upload a layout
 *     byte[] layout = Files.readAllBytes(Path.of("label1.llf"));
 *     pl6.uploadLayoutFile("label1.llf", layout);
 *
 *     // Fetch new pallet log entries
 *     List<PalletLogEntry> entries = pl6.fetchPalletLog();
 * }
 * }</pre>
 *
 * <h2>Pallet log polling</h2>
 * <p>PL6 writes one line per label application to
 * {@code /userapp/software/draw/log/leap.log}. This client tracks the
 * last-read byte offset so that each call to {@link #fetchPalletLog()}
 * returns only newly appended entries. The offset is reset automatically
 * when daily log rotation is detected (current file size &lt; stored offset).
 *
 * <h2>Data-ready</h2>
 * <p>On PL6 data-ready is signalled by uploading an LDF data file. Deleting
 * the file clears the data-ready flag. The exact directory path is defined
 * in {@link Pl6Paths#DATA_DIR} — verify with Logopak before use.
 *
 * <h2>Limitations vs PL3</h2>
 * <ul>
 *   <li>No LEAP print commands ({@code P}, {@code MAKE}, {@code FD}, etc.).</li>
 *   <li>No LAMA commands ({@code *VERSION}, {@code *SETTIME}, etc.).</li>
 *   <li>No LSP I/O commands ({@code &GETSTAT}, {@code &SET}, etc.).</li>
 *   <li>Serial number and line identifier are not available.</li>
 * </ul>
 */
public class LogoClientPL6 extends AbstractLogoLabeller implements ILogoLabeller {

    private final LogoClientSftp sftp;

    /** Byte offset into leap.log after the last successful {@link #fetchPalletLog()} call. */
    private long logOffset = 0;

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Create a PL6 client using the standard SFTP port (22).
     *
     * @param host IP address or hostname of the PL6 labeller
     */
    public LogoClientPL6(String host) {
        this(host, LogoClientSftp.DEFAULT_SFTP_PORT, LogoClientSftp.DEFAULT_TIMEOUT_MS);
    }

    /**
     * Create a PL6 client with a custom port and timeout.
     *
     * @param host      IP address or hostname
     * @param port      SSH/SFTP port (typically 22)
     * @param timeoutMs connection timeout in milliseconds
     */
    public LogoClientPL6(String host, int port, int timeoutMs) {
        super(host);
        this.sftp = new LogoClientSftp(host, port, timeoutMs);
    }

    // =========================================================================
    // ILogoLabeller — lifecycle
    // =========================================================================

    @Override
    public void connect() throws IOException {
        throw new UnsupportedOperationException(
                "Use connect(String user, String password) for PL6");
    }

    /**
     * Connect to the PL6 labeller via SFTP with username and password.
     *
     * @param user     SSH username (typically {@code "root"})
     * @param password SSH password
     * @throws IOException if the connection or authentication fails
     */
    public void connect(String user, String password) throws IOException {
        try {
            sftp.setStrictHostKeyChecking(false);
            sftp.connect(user, password);
            wireLog("--- PL6 SFTP CONNECT  " + host + "  user=" + user);
        } catch (JSchException e) {
            throw new IOException("PL6 SFTP connect failed: " + e.getMessage(), e);
        }
    }

    /**
     * Connect using a private key file.
     *
     * @param user           SSH username
     * @param privateKeyPath path to the private key file
     * @param passphrase     key passphrase, or {@code null}
     * @throws IOException if the connection or authentication fails
     */
    public void connectWithKey(String user, String privateKeyPath, String passphrase)
            throws IOException {
        try {
            sftp.connectWithKey(user, privateKeyPath, passphrase);
            wireLog("--- PL6 SFTP CONNECT (key)  " + host + "  user=" + user);
        } catch (JSchException e) {
            throw new IOException("PL6 SFTP key-connect failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return sftp.isConnected();
    }

    @Override
    public void close() {
        sftp.close();
        wireLog("--- PL6 SFTP DISCONNECT  " + host);
    }

    // =========================================================================
    // ILogoLabeller — identity
    // =========================================================================

    @Override
    public String getLabellerSerialNumber() {
        return "";   // Not available on PL6 without reading a config file
    }

    @Override
    public String getLabellerLine() {
        return "";   // Not available on PL6 without reading a config file
    }

    // =========================================================================
    // ILogoLabeller — layout files
    // =========================================================================

    @Override
    public LabellerResult uploadLayoutFile(String filename, byte[] data) throws IOException {
        return sftpPut(Pl6Paths.LAYOUTS_DIR + filename, data);
    }

    @Override
    public byte[] downloadLayoutFile(String filename) throws IOException {
        return sftpGet(Pl6Paths.LAYOUTS_DIR + filename);
    }

    @Override
    public LabellerResult deleteLayoutFile(String filename) throws IOException {
        return sftpDelete(Pl6Paths.LAYOUTS_DIR + filename);
    }

    @Override
    public List<FileInfo> listLayoutFiles() throws IOException {
        return sftpList(Pl6Paths.LAYOUTS_DIR);
    }

    // =========================================================================
    // ILogoLabeller — image files
    // =========================================================================

    @Override
    public LabellerResult uploadImageFile(String filename, byte[] data) throws IOException {
        return sftpPut(Pl6Paths.IMAGES_DIR + filename, data);
    }

    @Override
    public LabellerResult deleteImageFile(String filename) throws IOException {
        return sftpDelete(Pl6Paths.IMAGES_DIR + filename);
    }

    @Override
    public List<FileInfo> listImageFiles() throws IOException {
        return sftpList(Pl6Paths.IMAGES_DIR);
    }

    // =========================================================================
    // ILogoLabeller — data-ready
    // =========================================================================

    /**
     * Signal data-ready on PL6 by uploading an LDF data file.
     *
     * <p><b>WARNING:</b> The target directory ({@link Pl6Paths#DATA_DIR}) is
     * not yet confirmed with Logopak. Verify before use on a live PL6 system.
     *
     * @param ldfFilename LDF filename (e.g. {@code "data.ldf"})
     * @param ldfData     LDF file contents
     */
    public LabellerResult setDataReady(String ldfFilename, byte[] ldfData) throws IOException {
        return sftpPut(Pl6Paths.DATA_DIR + ldfFilename, ldfData);
    }

    /**
     * Clear data-ready on PL6 by deleting the LDF data file.
     *
     * <p><b>WARNING:</b> {@link Pl6Paths#DATA_DIR} is not yet confirmed.
     *
     * @param ldfFilename LDF filename to delete
     */
    public LabellerResult clearDataReady(String ldfFilename) throws IOException {
        return sftpDelete(Pl6Paths.DATA_DIR + ldfFilename);
    }

    // =========================================================================
    // ILogoLabeller — pallet log
    // =========================================================================

    /**
     * Fetch new pallet log entries from {@code leap.log} via SFTP.
     *
     * <p>Only lines appended since the last call are returned (tracked via
     * an internal byte-offset counter). Daily log rotation is detected by
     * checking whether the file has shrunk since the last read; if so the
     * offset is reset to 0.
     *
     * @return new entries in chronological order; empty if no new data
     * @throws IOException if the SFTP download fails
     */
    @Override
    public List<PalletLogEntry> fetchPalletLog() throws IOException {
        byte[] rawBytes;
        try {
            rawBytes = sftp.downloadFile(Pl6Paths.LOG_FILE);
        } catch (SftpException e) {
            // File may not exist yet if no labels have been applied
            wireLog("--- PL6 leap.log not found: " + e.getMessage());
            return Collections.emptyList();
        }

        String content    = new String(rawBytes, StandardCharsets.UTF_8);
        long   fileLength = rawBytes.length;

        // Detect log rotation: if file is shorter than our stored offset, it was rotated
        if (fileLength < logOffset) {
            wireLog("--- PL6 log rotation detected (file=" + fileLength
                    + " < offset=" + logOffset + "), resetting offset");
            logOffset = 0;
        }

        List<PalletLogEntry> entries = Pl6LogParser.parseFrom(content, logOffset);
        wireLog("--- PL6 fetchPalletLog  offset=" + logOffset
                + "  fileLen=" + fileLength + "  newEntries=" + entries.size());

        logOffset = fileLength;
        return Collections.unmodifiableList(entries);
    }

    /**
     * Reset the log read offset. Call this if you want to re-process the
     * entire current log file from the beginning on the next poll.
     */
    public void resetLogOffset() {
        logOffset = 0;
    }

    /** Returns the current byte offset into {@code leap.log}. */
    public long getLogOffset() {
        return logOffset;
    }

    // =========================================================================
    // Generic SFTP file access (for GUI file browser)
    // =========================================================================

    /**
     * List files in any remote directory. Use this for the generic file
     * browser rather than the domain-specific layout/image methods.
     *
     * @param remotePath remote directory path
     * @return file listing
     * @throws IOException if the directory cannot be listed
     */
    @Override
    public List<FileInfo> listFiles(String remotePath) throws IOException {
        return sftpList(remotePath);
    }

    /**
     * Download any file by full remote path.
     *
     * @param remotePath full remote path
     * @return file bytes
     * @throws IOException on SFTP error
     */
    @Override
    public byte[] downloadFile(String remotePath) throws IOException {
        return sftpGet(remotePath);
    }

    /**
     * Upload any file to a full remote path.
     *
     * @param remotePath full remote path
     * @param data       file bytes
     * @throws IOException on SFTP error
     */
    @Override
    public LabellerResult uploadFile(String remotePath, byte[] data) throws IOException {
        return sftpPut(remotePath, data);
    }

    /**
     * Delete any file by full remote path.
     *
     * @param remotePath full remote path
     * @throws IOException on SFTP error
     */
    @Override
    public LabellerResult deleteFile(String remotePath) throws IOException {
        return sftpDelete(remotePath);
    }

    // =========================================================================
    // SFTP helpers
    // =========================================================================

    private LabellerResult sftpPut(String remotePath, byte[] data) throws IOException {
        try {
            sftp.uploadFile(remotePath, data);
            wireLog(">>> SFTP PUT  " + remotePath + "  (" + data.length + " bytes)");
            return LabellerResult.ok(remotePath);
        } catch (SftpException e) {
            wireLog("!!! SFTP PUT FAILED  " + remotePath + "  " + e.getMessage());
            throw new IOException("SFTP upload failed [" + remotePath + "]: " + e.getMessage(), e);
        }
    }

    private byte[] sftpGet(String remotePath) throws IOException {
        try {
            byte[] data = sftp.downloadFile(remotePath);
            wireLog("<<< SFTP GET  " + remotePath + "  (" + data.length + " bytes)");
            return data;
        } catch (SftpException e) {
            wireLog("!!! SFTP GET FAILED  " + remotePath + "  " + e.getMessage());
            throw new IOException("SFTP download failed [" + remotePath + "]: " + e.getMessage(), e);
        }
    }

    private LabellerResult sftpDelete(String remotePath) throws IOException {
        try {
            sftp.deleteFile(remotePath);
            wireLog(">>> SFTP DEL  " + remotePath);
            return LabellerResult.ok(remotePath);
        } catch (SftpException e) {
            wireLog("!!! SFTP DEL FAILED  " + remotePath + "  " + e.getMessage());
            throw new IOException("SFTP delete failed [" + remotePath + "]: " + e.getMessage(), e);
        }
    }

    private List<FileInfo> sftpList(String remotePath) throws IOException {
        try {
            List<LogoClientSftp.FileEntry> entries = sftp.listFiles(remotePath);
            List<FileInfo> result = new ArrayList<>(entries.size());
            for (LogoClientSftp.FileEntry e : entries) {
                result.add(new FileInfo(e.name(), e.size(), e.modifiedTime(), e.isDirectory()));
            }
            wireLog("<<< SFTP LS  " + remotePath + "  (" + result.size() + " entries)");
            return result;
        } catch (SftpException e) {
            wireLog("!!! SFTP LS FAILED  " + remotePath + "  " + e.getMessage());
            throw new IOException("SFTP list failed [" + remotePath + "]: " + e.getMessage(), e);
        }
    }
}
