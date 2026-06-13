package com.commander4j.client.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Common contract for all Logo labeller hardware generations.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code LogoClientPL3} – PowerLeap III, communicates via TCP using the
 *       LEAP/LAMA/LSP command protocol (ports 8000–8300).</li>
 *   <li>{@code LogoClientPL6} – PowerLeap 6, communicates via SFTP (port 22).
 *       No TCP command channel is available on PL6.</li>
 * </ul>
 *
 * <p>Only operations that have a meaningful equivalent on <em>both</em>
 * hardware generations are defined here. PL3-specific commands (LAMA, LSP,
 * raw LEAP) remain on {@code LogoClientPL3} and are accessible by casting.
 */
public interface ILogoLabeller extends AutoCloseable {

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Opens the connection to the labeller. */
    void connect() throws IOException;

    /** Returns {@code true} if the connection is currently open. */
    boolean isConnected();

    /** Closes the connection. Implementations must be idempotent. */
    @Override
    void close();

    /** Returns the hostname or IP address of the labeller. */
    String getHost();

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * Returns the labeller serial number, or an empty string if unavailable.
     * PL3: read from {@code *VERSION} response. PL6: not available, returns "".
     */
    String getLabellerSerialNumber();

    /**
     * Returns the production line identifier, or an empty string if unavailable.
     * PL3: read from {@code &LABELLERTYPE}. PL6: not available, returns "".
     */
    String getLabellerLine();

    // -------------------------------------------------------------------------
    // Layout files
    // -------------------------------------------------------------------------

    /**
     * Uploads a layout file to the labeller.
     *
     * <p>PL3: encodes as Intel HEX and transfers via {@code *HEXFILE}.
     * PL6: transfers via SFTP to {@code /userapp/software/draw/layouts/}.
     *
     * @param filename remote filename (no path prefix required)
     * @param data     raw layout file bytes
     */
    LabellerResult uploadLayoutFile(String filename, byte[] data) throws IOException;

    /**
     * Downloads a layout file from the labeller.
     *
     * @param filename remote filename
     * @return raw file bytes
     */
    byte[] downloadLayoutFile(String filename) throws IOException;

    /**
     * Deletes a layout file from the labeller.
     *
     * @param filename remote filename
     */
    LabellerResult deleteLayoutFile(String filename) throws IOException;

    /**
     * Lists layout files on the labeller.
     *
     * <p>PL3: queries {@code *DIR,*.llf,SDT} (and {@code *.lqf}).
     * PL6: lists {@code /userapp/software/draw/layouts/}.
     */
    List<FileInfo> listLayoutFiles() throws IOException;

    // -------------------------------------------------------------------------
    // Image files
    // -------------------------------------------------------------------------

    /**
     * Uploads an image file (bitmap, logo) to the labeller.
     *
     * <p>PL3: transfers via {@code *HEXFILE} to {@code /c0/}.
     * PL6: transfers via SFTP to {@code /userapp/software/draw/images/leap/}.
     */
    LabellerResult uploadImageFile(String filename, byte[] data) throws IOException;

    /**
     * Deletes an image file from the labeller.
     */
    LabellerResult deleteImageFile(String filename) throws IOException;

    /**
     * Lists image files on the labeller.
     */
    List<FileInfo> listImageFiles() throws IOException;

    // -------------------------------------------------------------------------
    // Pallet log
    // -------------------------------------------------------------------------

    /**
     * Retrieves all new pallet label log entries from the labeller.
     *
     * <p>PL3: executes {@code *LOGINFO} → {@code *TRXLOG} → {@code *DELLOG}.
     * PL6: reads new lines from {@code leap.log} via SFTP using a persisted
     * byte-offset so only unprocessed entries are returned.
     *
     * @return entries in chronological order; empty if no new data
     */
    List<PalletLogEntry> fetchPalletLog() throws IOException;

    /**
     * Retrieves new pallet log entries and writes one file per unique SSCC
     * to {@code saveDirectory}.
     *
     * <p>File naming: {@code <SSCC>_<timestamp>.txt} where timestamp is
     * {@code yyyyMMddHHmmss} at the time of writing. Files are written
     * atomically (write to {@code .tmp} then rename). If a file for the same
     * SSCC already exists it is not overwritten (idempotent on retry).
     *
     * @param saveDirectory directory to write output files into
     * @return all entries retrieved in this call (same as no-arg overload)
     */
    List<PalletLogEntry> fetchPalletLog(Path saveDirectory) throws IOException;

    // -------------------------------------------------------------------------
    // Generic remote file access (for file browser UI)
    // -------------------------------------------------------------------------

    /**
     * Lists files in any remote directory or matching a filter expression.
     *
     * <p>PL3: {@code path} is passed directly to {@code *DIR,path,SDT}.
     * PL6: {@code path} is an SFTP directory path.
     *
     * @param remotePath directory path or filter spec (e.g. {@code /c0/*.*} on PL3)
     */
    List<FileInfo> listFiles(String remotePath) throws IOException;

    /**
     * Uploads a file to any remote path.
     *
     * <p>PL3: the filename is extracted from {@code remotePath} and transferred
     * via {@code *HEXFILE}. The directory prefix is used for display only.
     * PL6: transferred via SFTP to the full {@code remotePath}.
     *
     * @param remotePath full remote path including filename
     * @param data       file bytes
     */
    LabellerResult uploadFile(String remotePath, byte[] data) throws IOException;

    /**
     * Downloads a file from any remote path.
     *
     * <p>PL3: the filename is extracted from {@code remotePath} and retrieved
     * via {@code *TYPEHEX}.
     * PL6: retrieved via SFTP.
     *
     * @param remotePath full remote path including filename
     * @return file bytes
     */
    byte[] downloadFile(String remotePath) throws IOException;

    /**
     * Deletes a file at a remote path.
     *
     * <p>PL3: uses {@code *DELFILE,path}.
     * PL6: uses SFTP delete.
     *
     * @param remotePath full remote path including filename
     */
    LabellerResult deleteFile(String remotePath) throws IOException;

    // -------------------------------------------------------------------------
    // Continuous polling
    // -------------------------------------------------------------------------

    /**
     * A handle returned by {@link #startContinuousPolling} that allows the
     * caller to stop the background poll loop.
     */
    interface PollHandle {
        /**
         * Signals the poll loop to stop.  Returns immediately; the background
         * thread may still be sleeping in its inter-poll interval when this is
         * called and will exit at the next wake-up.
         */
        void stop();

        /** Returns {@code true} if the poll loop is still running. */
        boolean isRunning();
    }

    /**
     * Starts a background thread that repeatedly calls
     * {@link #fetchPalletLog(Path)} at a fixed interval, writing one file per
     * SSCC to {@code saveDirectory} and notifying {@code onEntries} with each
     * batch of new entries.
     *
     * <p>The thread is a daemon thread so it will not prevent JVM shutdown.
     * Call {@link PollHandle#stop()} to terminate it gracefully.
     *
     * <p>If a poll cycle throws an {@link IOException} (e.g. the labeller is
     * temporarily offline) the error is passed to {@code onError} (if provided)
     * and the loop continues after the normal interval.  The connection is
     * <em>not</em> automatically re-established; callers that need reconnect
     * logic should supply an {@code onError} handler that calls
     * {@link #connect()} after a delay.
     *
     * @param saveDirectory   directory to write per-SSCC output files into;
     *                        may be {@code null} to skip file writing
     * @param intervalSeconds seconds to wait between poll attempts (minimum 1)
     * @param onEntries       callback invoked with each non-empty batch of entries;
     *                        may be {@code null}
     * @param onError         callback invoked when a poll cycle throws an exception;
     *                        may be {@code null}
     * @return a {@link PollHandle} that can be used to stop the loop
     */
    default PollHandle startContinuousPolling(Path saveDirectory,
                                              int intervalSeconds,
                                              Consumer<List<PalletLogEntry>> onEntries,
                                              Consumer<Exception> onError) {
        final int intervalMs = Math.max(1, intervalSeconds) * 1000;
        final AtomicBoolean running = new AtomicBoolean(true);

        Thread t = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    List<PalletLogEntry> entries = (saveDirectory != null)
                            ? fetchPalletLog(saveDirectory)
                            : fetchPalletLog();
                    if (!entries.isEmpty() && onEntries != null) {
                        onEntries.accept(entries);
                    }
                } catch (Exception e) {
                    if (onError != null) {
                        onError.accept(e);
                    }
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            running.set(false);
        }, "logo-poll-" + getHost());
        t.setDaemon(true);
        t.start();

        return new PollHandle() {
            @Override public void stop()        { running.set(false); t.interrupt(); }
            @Override public boolean isRunning() { return running.get() && t.isAlive(); }
        };
    }

    /**
     * Sends a lightweight read-only command to verify the connection is alive.
     *
     * <p>Used by the GUI keepalive thread to detect broken TCP connections
     * that haven't yet surfaced as {@code IOException} on a user operation.
     *
     * <p>Default implementation does nothing; implementations should override
     * with a cheap no-side-effect call (PL3: {@code *VERSION}).
     *
     * @throws IOException if the connection is no longer usable
     */
    default void ping() throws IOException {
        // default: no-op
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /**
     * Registers a wire-level trace logger. On PL3 this captures raw TCP
     * send/receive bytes. On PL6 it logs SFTP operations in a compatible style.
     * Pass {@code null} to disable.
     */
    void setWireLogger(Consumer<String> logger);
}
