package com.commander4j.client;

import com.commander4j.client.common.PalletLogEntry;
import com.commander4j.client.pl3.LogoClientPL3;
import com.commander4j.client.pl6.LogoClientPL6;
import com.commander4j.client.pl6.Pl6Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Demonstrates common usage patterns for the Logopak client library.
 *
 * <p>Examples 1–7 cover the PL3 TCP protocol (LEAP / LAMA / LSP commands).
 * Examples 8–10 cover the PL6 SFTP protocol.
 *
 * <p>Each static method is self-contained. Run them from {@link #main}
 * or copy the patterns you need into your own application.
 */
public class LogoClientExample {

    private static final String PL3_IP = "192.168.1.100"; // PL3 labeller — adjust for your network
    private static final String PL6_IP = "192.168.1.200"; // PL6 labeller — adjust for your network
    private static final String PL6_USER = "root";
    private static final String PL6_PASS = "your-password-here"; // adjust for your labeller

    public static void main(String[] args) throws Exception {

        // ── PL3 examples ─────────────────────────────────────────────────────
        basicPrintCycle();
        lamaInfoAndMaintenance();
        lamaLogCycle();
        lspIoControl();
        lspAsyncMonitoring();
        blockSend();
        palletLogPolling();

        // ── PL6 examples ─────────────────────────────────────────────────────
        pl6FileTransfer();
        pl6PalletLogPolling();
        pl6PalletLogToFiles();
    }

    // =========================================================================
    // Example 1 – Basic print cycle (PL3)
    // =========================================================================

    /**
     * Demonstrates the typical sequence for activating a layout, setting field
     * values, and triggering a print.
     *
     * <p>If the connection is refused (labeller thinks the previous session is
     * still open), {@link #connectWithResetFallback} handles the deadlock
     * automatically.
     */
    static void basicPrintCycle() throws IOException {
        try (LogoClientPL3 lab = new LogoClientPL3(PL3_IP, LogoClientPL3.PORT_XA)) {

            connectWithResetFallback(lab);

            sendOrThrow(lab.activateLayout("label1"), "AL");
            sendOrThrow(lab.sendFieldData(1, "BATCH-20240827"), "FD 1");
            sendOrThrow(lab.sendFieldData(2, "2024-08-27"),     "FD 2");
            sendOrThrow(lab.sendFieldData(3, "LOT-042"),        "FD 3");
            sendOrThrow(lab.makeLayout("label1"), "MAKE");

            LogoClientPL3.LabellerResponse printResp = lab.printLabel();
            if (printResp.isOk()) {
                System.out.println("Label printed successfully.");
            } else {
                System.err.println("Print failed: " + printResp);
            }
        }
    }

    // =========================================================================
    // Example 2 – LAMA: firmware info, time sync, counter (PL3)
    // =========================================================================

    /**
     * Demonstrates LAMA-layer commands for checking firmware version, reading
     * and setting the real-time clock, and managing the print counter.
     */
    static void lamaInfoAndMaintenance() throws IOException {
        try (LogoClientPL3 lab = new LogoClientPL3(PL3_IP, LogoClientPL3.PORT_XA)) {

            connectWithResetFallback(lab);

            LogoClientPL3.LabellerResponse ver = lab.lamaGetVersion();
            System.out.println("Firmware version: " + ver.getBody());

            LogoClientPL3.LabellerResponse timeResp = lab.lamaGetTime();
            System.out.println("Labeller time: " + timeResp.getBody());

            sendOrThrow(lab.lamaSetTime(LocalDateTime.now()), "*SETTIME");

            LogoClientPL3.LabellerResponse countResp = lab.lamaGetCount();
            System.out.println("Print counter: " + countResp.getBody());

            sendOrThrow(lab.lamaSetCount(0), "*SETCOUNT");

            LogoClientPL3.LabellerResponse memResp = lab.lamaGetMemoryInfo();
            System.out.println("Memory info: " + memResp.getBody());

            sendOrThrow(lab.lamaShowMessage("info", "Host connected"), "*MSG");
        }
    }

    // =========================================================================
    // Example 3 – LAMA: log file retrieval cycle (PL3)
    // =========================================================================

    /**
     * Demonstrates the three-step log retrieval cycle:
     * <ol>
     *   <li>{@code *LOGINFO} – check whether a log backup exists.</li>
     *   <li>{@code *TRXLOG} – transfer the log data to the host.</li>
     *   <li>{@code *DELLOG} – delete the backup from the labeller CMOS.</li>
     * </ol>
     */
    static void lamaLogCycle() throws IOException {
        try (LogoClientPL3 lab = new LogoClientPL3(PL3_IP, LogoClientPL3.PORT_XA)) {

            connectWithResetFallback(lab);

            LogoClientPL3.LabellerResponse info = lab.lamaLogInfo();
            System.out.println("Log info: " + info.getBody());

            if (!info.isOk()) {
                System.out.println("No log available.");
                return;
            }

            LogoClientPL3.LabellerResponse log = lab.lamaTransferLog();
            if (!log.isOk()) {
                System.err.println("Transfer failed: " + log);
                return;
            }
            String logData = log.getBody();
            System.out.println("Received " + logData.length() + " bytes of log data.");

            sendOrThrow(lab.lamaDeleteLog(), "*DELLOG");
            System.out.println("Log deleted from labeller.");
        }
    }

    // =========================================================================
    // Example 4 – LSP: read and set I/O bits (PL3)
    // =========================================================================

    /**
     * Demonstrates LSP commands for querying and controlling LOGOSTEP I/O.
     */
    static void lspIoControl() throws IOException {
        try (LogoClientPL3 lab = new LogoClientPL3(PL3_IP, LogoClientPL3.PORT_XA)) {

            connectWithResetFallback(lab);

            sendOrThrow(lab.lamaEnableVerbose(), "*VERBOSE");

            LogoClientPL3.LabellerResponse lsState = lab.lspGetLogostepState();
            System.out.println("LOGOSTEP state: " + lsState.getBody());

            LogoClientPL3.LabellerResponse type = lab.lspGetLabellerType();
            System.out.println("Labeller type: " + type.getBody());

            LogoClientPL3.LabellerResponse inState = lab.lspGetState("IN", "001");
            System.out.println("IN001 state: " + inState.getBodyTrimmed());

            sendOrThrow(lab.lspSetBits("OUT", "001", 1),   "&SET OUT001");
            sendOrThrow(lab.lspResetBits("OUT", "001", 1), "&RESET OUT001");

            LogoClientPL3.LabellerResponse symbols = lab.lspListSymbols();
            System.out.println("Symbol table:\n" + symbols.getBody());

            sendOrThrow(lab.lspHalt(), "&HALT");
            sendOrThrow(lab.lspRun(),  "&RUN");
        }
    }

    // =========================================================================
    // Example 5 – LSP: asynchronous I/O monitoring via &REPORT (PL3)
    // =========================================================================

    /**
     * Demonstrates asynchronous I/O state monitoring.
     *
     * <p>After {@link LogoClientPL3#lspEnableReporting} is called, the labeller
     * pushes {@code STATE,name=value} messages whenever a monitored I/O bit
     * changes.
     */
    static void lspAsyncMonitoring() throws IOException, InterruptedException {
        try (LogoClientPL3 lab = new LogoClientPL3(PL3_IP, LogoClientPL3.PORT_XA)) {

            connectWithResetFallback(lab);

            System.out.println("Starting async I/O monitoring for 10 seconds...");

            lab.lspEnableReporting(stateMessage ->
                    System.out.println("I/O change received: " + stateMessage));

            LogoClientPL3.LabellerResponse ver = lab.lamaGetVersion();
            System.out.println("Version (during monitoring): " + ver.getBody());

            Thread.sleep(10_000);

            lab.lspDisableReporting();
            System.out.println("Monitoring stopped.");
        }
    }

    // =========================================================================
    // Example 6 – Block (STX/ETX) command sending (PL3)
    // =========================================================================

    /**
     * Demonstrates sending multiple LEAP FFP field-data commands in a single
     * atomic STX/ETX block.
     */
    static void blockSend() throws IOException {
        try (LogoClientPL3 lab = new LogoClientPL3(PL3_IP, LogoClientPL3.PORT_XA)) {

            connectWithResetFallback(lab);

            sendOrThrow(lab.activateLayout("pallet"), "AL");

            List<String> fieldCommands = List.of(
                    "FD,1,PALLET-001",
                    "FD,2,2024-08-27",
                    "FD,3,GROSS 500KG",
                    "FD,4,ACME CORP"
            );
            sendOrThrow(lab.sendBlock(fieldCommands), "block FD");
            sendOrThrow(lab.printLabel(), "P");

            System.out.println("Pallet label printed.");
        }
    }

    // =========================================================================
    // Example 7 – Pallet log polling and SSCC detection (PL3)
    // =========================================================================

    /**
     * Demonstrates the high-level {@link LogoClientPL3#fetchPalletLog()} API
     * to poll the labeller for completed pallet label applications.
     *
     * <p>The labeller applies two labels per pallet (same SSCC), then increments
     * the counter — so two consecutive entries with the same SSCC represent one
     * completed pallet.
     */
    static void palletLogPolling() throws IOException {
        try (LogoClientPL3 lab = new LogoClientPL3(PL3_IP, LogoClientPL3.PORT_XA)) {

            connectWithResetFallback(lab);

            List<PalletLogEntry> entries = lab.fetchPalletLog();

            if (entries.isEmpty()) {
                System.out.println("No pallet log entries available.");
                return;
            }

            System.out.println("Received " + entries.size() + " log entries.");

            for (int i = 0; i < entries.size(); i++) {
                PalletLogEntry entry = entries.get(i);

                System.out.printf("  Entry %d: SSCC=%-18s  seq=%d  raw=%s%n",
                        i + 1,
                        entry.getSscc() != null ? entry.getSscc() : "(none)",
                        entry.getSequence(),
                        entry.getRawData());

                if (i + 1 < entries.size()) {
                    PalletLogEntry next = entries.get(i + 1);
                    if (entry.isSamePallet(next)) {
                        System.out.printf("    → Pallet SSCC %s completed (both labels applied).%n",
                                entry.getSscc());
                        i++;
                    }
                }
            }

            if (!entries.isEmpty()) {
                PalletLogEntry first = entries.get(0);
                System.out.println("\nFirst entry field breakdown:");
                String[] fields = first.getFields();
                for (int f = 0; f < fields.length; f++) {
                    System.out.printf("  field[%d] = %s%n", f, fields[f]);
                }
            }
        }
    }

    // =========================================================================
    // Example 8 – PL6: file transfer via SFTP
    // =========================================================================

    /**
     * Demonstrates connecting to a PL6 labeller via SFTP and transferring
     * layout and image files.
     *
     * <p>PL6 communicates exclusively via SFTP (port 22). There are no TCP
     * command channels on PL6 — no LAMA, LEAP, or LSP commands are available.
     */
    static void pl6FileTransfer() throws IOException {
        try (LogoClientPL6 pl6 = new LogoClientPL6(PL6_IP)) {
            pl6.connect(PL6_USER, PL6_PASS);

            // Upload a layout file
            byte[] layoutData = Files.readAllBytes(Path.of("label1.llf"));
            pl6.uploadLayoutFile("label1.llf", layoutData);
            System.out.println("Layout uploaded to " + Pl6Paths.LAYOUTS_DIR);

            // List all layout files
            pl6.listLayoutFiles().forEach(fi ->
                    System.out.printf("  %-30s  %d bytes%n", fi.getName(), fi.getSize()));

            // Upload an image
            byte[] imageData = Files.readAllBytes(Path.of("logo.bmp"));
            pl6.uploadImageFile("logo.bmp", imageData);
            System.out.println("Image uploaded to " + Pl6Paths.IMAGES_DIR);

            // Download a layout file back
            byte[] downloaded = pl6.downloadLayoutFile("label1.llf");
            System.out.println("Downloaded " + downloaded.length + " bytes.");

            // Delete a layout file
            pl6.deleteLayoutFile("label1.llf");
            System.out.println("Layout deleted.");
        }
    }

    // =========================================================================
    // Example 9 – PL6: pallet log polling via SFTP
    // =========================================================================

    /**
     * Demonstrates polling the PL6 {@code leap.log} file for new pallet label
     * application events.
     *
     * <p>PL6 writes one line per label application to
     * {@code /userapp/software/draw/log/leap.log} in the format:
     * <pre>
     *   ISO8601_timestamp&lt;TAB&gt;semicolon_delimited_field_19500_data
     * </pre>
     *
     * <p>The client tracks the last-read byte offset so that each call returns
     * only newly appended entries.  Daily log rotation is handled automatically.
     *
     * <p>In a production system this method would be called on a timer
     * (e.g. every 5–30 seconds).
     */
    static void pl6PalletLogPolling() throws IOException, InterruptedException {
        try (LogoClientPL6 pl6 = new LogoClientPL6(PL6_IP)) {
            pl6.connect(PL6_USER, PL6_PASS);

            System.out.println("Polling PL6 pallet log (3 cycles, 5 s apart)...");

            for (int cycle = 1; cycle <= 3; cycle++) {
                List<PalletLogEntry> entries = pl6.fetchPalletLog();
                System.out.printf("Cycle %d: %d new entries%n", cycle, entries.size());
                for (PalletLogEntry e : entries) {
                    System.out.printf("  SSCC=%-18s  ts=%s  raw=%s%n",
                            e.getSscc() != null ? e.getSscc() : "(none)",
                            e.getTimestamp(),
                            e.getRawData());
                }
                if (cycle < 3) Thread.sleep(5_000);
            }
        }
    }

    // =========================================================================
    // Example 10 – PL6: pallet log polling with file output
    // =========================================================================

    /**
     * Demonstrates the {@link LogoClientPL6#fetchPalletLog(Path)} overload that
     * writes one output file per unique SSCC to a local directory.
     *
     * <p>Files are named {@code <SSCC>_<yyyyMMddHHmmss>.txt} and written
     * atomically. If a file for the same SSCC already exists it is not
     * overwritten (safe to call on retry after a crash).
     */
    static void pl6PalletLogToFiles() throws IOException {
        Path outputDir = Path.of("/tmp/pallet_logs");

        try (LogoClientPL6 pl6 = new LogoClientPL6(PL6_IP)) {
            pl6.connect(PL6_USER, PL6_PASS);

            List<PalletLogEntry> entries = pl6.fetchPalletLog(outputDir);
            System.out.printf("Fetched %d entries; files written to %s%n",
                    entries.size(), outputDir);

            for (PalletLogEntry e : entries) {
                System.out.printf("  SSCC=%-18s  ts=%s%n",
                        e.getSscc() != null ? e.getSscc() : "(none)",
                        e.getTimestamp());
            }
        }
    }

    // =========================================================================
    // Helper utilities
    // =========================================================================

    /**
     * Connect to a PL3 labeller. If the first attempt is refused (deadlock
     * state), use the reset port to clear it and then retry once.
     */
    static void connectWithResetFallback(LogoClientPL3 lab) throws IOException {
        try {
            lab.connect();
        } catch (java.net.ConnectException refused) {
            System.out.println("Connection refused – attempting reset port recovery...");
            lab.resetConnection();
            lab.connect();
        }
    }

    /**
     * Throw a descriptive {@link RuntimeException} if the labeller responded
     * with NAK.
     */
    static void sendOrThrow(LogoClientPL3.LabellerResponse response, String context) {
        if (!response.isOk()) {
            throw new RuntimeException(
                    "Labeller command '" + context + "' failed: " + response);
        }
    }
}
