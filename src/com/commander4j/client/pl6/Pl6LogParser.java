package com.commander4j.client.pl6;

import com.commander4j.client.common.PalletLogEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the PL6 {@code leap.log} file format into {@link PalletLogEntry} records.
 *
 * <h2>PL6 log format</h2>
 * <p>Each line represents one label application event:
 * <pre>
 *   ISO8601_timestamp &lt;TAB&gt; field_19500_data
 * </pre>
 * Example:
 * <pre>
 *   2001-07-04T12:08:56.235-07:00&#9;27.08.2004;234506;001234567890123456;
 * </pre>
 *
 * <p>The timestamp and data portions are separated by a single TAB character.
 * The data portion is the semicolon-delimited content of field 19500, identical
 * in structure to the PL3 log data.
 *
 * <h2>Log rotation</h2>
 * <p>The log file is rotated daily. {@link LogoClientPL6} tracks the byte offset
 * of the last-read position and calls {@link #parseFrom(String, long)} to process
 * only new content appended since the last poll.
 */
public final class Pl6LogParser {

    private Pl6LogParser() {}

    /**
     * Parse all lines in {@code logContent} that start at or after byte
     * {@code fromOffset} in the original file content (UTF-8).
     *
     * <p>This avoids re-processing log lines that were already returned in a
     * previous poll cycle.
     *
     * @param logContent full text content of {@code leap.log}
     * @param fromOffset byte offset of the first character to process; 0 = from start
     * @return parsed entries in chronological order; empty if no new data
     */
    public static List<PalletLogEntry> parseFrom(String logContent, long fromOffset) {
        if (logContent == null || logContent.isEmpty()) return Collections.emptyList();

        // Truncate to content after fromOffset. The log is expected to contain
        // only ASCII characters (ISO-8601 timestamps, digits, semicolons), so
        // the byte offset from rawBytes.length is equal to the char offset.
        String relevant = fromOffset > 0 && fromOffset < logContent.length()
                ? logContent.substring((int) fromOffset)
                : fromOffset == 0 ? logContent : "";

        return parseLines(relevant);
    }

    /**
     * Parse all lines in {@code logContent}.
     *
     * @param logContent text content to parse
     * @return parsed entries in chronological order
     */
    public static List<PalletLogEntry> parseAll(String logContent) {
        if (logContent == null || logContent.isEmpty()) return Collections.emptyList();
        return parseLines(logContent);
    }

    // -------------------------------------------------------------------------

    private static List<PalletLogEntry> parseLines(String text) {
        List<PalletLogEntry> result = new ArrayList<>();
        for (String line : text.split("[\r\n]+")) {
            PalletLogEntry entry = parseLine(line.trim());
            if (entry != null) result.add(entry);
        }
        return result;
    }

    /**
     * Parse a single log line of the form {@code timestamp<TAB>data}.
     *
     * @param line trimmed log line
     * @return a {@link PalletLogEntry}, or {@code null} if the line is blank or malformed
     */
    static PalletLogEntry parseLine(String line) {
        if (line.isEmpty()) return null;
        int tab = line.indexOf('\t');
        if (tab < 0) {
            // No tab — treat the whole line as data with no timestamp
            return new PalletLogEntry(line, -1, null);
        }
        String timestamp = line.substring(0, tab).trim();
        String data      = line.substring(tab + 1).trim();
        if (data.isEmpty()) return null;
        return new PalletLogEntry(data, -1, timestamp);
    }
}
