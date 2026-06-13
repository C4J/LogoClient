package com.commander4j.client.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Base class for labeller client implementations.
 *
 * <p>Holds connection state common to all hardware generations and provides
 * the shared {@link #fetchPalletLog(Path)} implementation that writes one
 * output file per SSCC by delegating to the concrete {@link #fetchPalletLog()}.
 */
public abstract class AbstractLogoLabeller implements ILogoLabeller {

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    protected final String host;
    protected volatile Consumer<String> wireLogger;

    protected AbstractLogoLabeller(String host) {
        this.host = host;
    }

    @Override
    public String getHost() { return host; }

    @Override
    public void setWireLogger(Consumer<String> logger) {
        this.wireLogger = logger;
    }

    /** Logs a wire-trace message if a logger is registered. */
    protected void wireLog(String message) {
        Consumer<String> l = wireLogger;
        if (l != null) l.accept(message);
    }

    // -------------------------------------------------------------------------
    // fetchPalletLog(Path) — shared file-writing logic
    // -------------------------------------------------------------------------

    /**
     * Retrieves new pallet log entries and writes one file per unique SSCC.
     *
     * <p>Entries are grouped by SSCC. Because the labeller applies two labels
     * per pallet (both with the same SSCC), each output file accumulates all
     * entries for that SSCC. Files are written atomically and are not
     * overwritten if they already exist (safe to call on retry after a crash).
     */
    @Override
    public List<PalletLogEntry> fetchPalletLog(Path saveDirectory) throws IOException {
        List<PalletLogEntry> entries = fetchPalletLog();
        if (entries.isEmpty()) return entries;

        // Group by SSCC, preserving insertion order
        Map<String, StringBuilder> bySscc = new LinkedHashMap<>();
        String timestamp = LocalDateTime.now().format(FILE_TS);

        for (PalletLogEntry entry : entries) {
            String sscc = entry.getSscc();
            if (sscc == null) sscc = "UNKNOWN";
            bySscc.computeIfAbsent(sscc, _ -> new StringBuilder())
                  .append(buildFileContent(entry))
                  .append(System.lineSeparator());
        }

        Files.createDirectories(saveDirectory);

        for (Map.Entry<String, StringBuilder> e : bySscc.entrySet()) {
            String sscc    = e.getKey();
            String content = e.getValue().toString();
            String fname   = sscc + "_" + timestamp + ".txt";
            Path   target  = saveDirectory.resolve(fname);

            if (Files.exists(target)) continue;   // idempotent on retry

            Path tmp = saveDirectory.resolve(fname + ".tmp");
            Files.writeString(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        }

        return entries;
    }

    /**
     * Formats a single {@link PalletLogEntry} as the text written to the
     * output file. Subclasses may override to add hardware-specific fields.
     */
    protected String buildFileContent(PalletLogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("SSCC=").append(entry.getSscc() != null ? entry.getSscc() : "").append('\n');
        if (entry.getTimestamp() != null) {
            sb.append("TIMESTAMP=").append(entry.getTimestamp()).append('\n');
        }
        if (entry.getSequence() >= 0) {
            sb.append("SEQUENCE=").append(entry.getSequence()).append('\n');
        }
        String[] fields = entry.getFields();
        for (int i = 0; i < fields.length; i++) {
            sb.append("FIELD_").append(i).append('=').append(fields[i]).append('\n');
        }
        sb.append("RAW=").append(entry.getRawData()).append('\n');
        return sb.toString();
    }
}
