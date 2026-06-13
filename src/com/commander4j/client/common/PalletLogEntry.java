package com.commander4j.client.common;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a single pallet label application event as recorded in the
 * labeller's log (field 19500 on PL3; a line in {@code leap.log} on PL6).
 *
 * <p>The raw data is a semicolon-separated string whose fields are defined
 * by {@code VAR} / {@code FL} commands in the active layout. One of the
 * fields is always the 18-digit decimal SSCC pallet number (EAN-128).
 *
 * <p>The labeller typically applies <b>two labels per pallet</b> (one on
 * each side) and then increments the SSCC. Both labels share the same SSCC,
 * so two consecutive log entries with the same SSCC represent a single
 * completed pallet. Use {@link #isSamePallet(PalletLogEntry)} to test this.
 *
 * <h3>Sequence number</h3>
 * <p>On PL3 with STX/ETX format the entry carries a 3-digit decimal sequence
 * counter appended after a CR inside the block. This counter increments with
 * each label application and can be used to detect gaps (missed applications).
 * The value is {@code -1} when not present (plain-text, Intel HEX, or PL6).
 *
 * <h3>Timestamp</h3>
 * <p>On PL6 the log line includes an ISO-8601 timestamp prefix. This is
 * captured in {@link #getTimestamp()} and is {@code null} on PL3.
 */
public final class PalletLogEntry {

    /** Matches exactly 18 consecutive decimal digits (SSCC-18). */
    private static final Pattern SSCC_PATTERN =
            Pattern.compile("\\b(\\d{18})\\b");

    private final String   rawData;
    private final String[] fields;
    private final int      sequence;
    private final String   sscc;
    private final String   timestamp;   // ISO-8601 from PL6; null on PL3

    /** PL3 constructor — no timestamp. */
    public PalletLogEntry(String rawData, int sequence) {
        this(rawData, sequence, null);
    }

    /** Full constructor used by PL6 parser which supplies a timestamp. */
    public PalletLogEntry(String rawData, int sequence, String timestamp) {
        this.rawData   = rawData;
        this.sequence  = sequence;
        this.timestamp = timestamp;
        this.fields    = rawData.split(";", -1);
        String found = null;
        for (String f : fields) {
            Matcher m = SSCC_PATTERN.matcher(f.trim());
            if (m.find()) { found = m.group(1); break; }
        }
        this.sscc = found;
    }

    /**
     * Returns the raw semicolon-separated field string exactly as stored
     * in the log (field 19500 content on PL3; data portion of log line on PL6).
     */
    public String getRawData() { return rawData; }

    /**
     * Returns all semicolon-delimited fields as a string array.
     * Trailing empty fields are preserved.
     */
    public String[] getFields() { return Arrays.copyOf(fields, fields.length); }

    /**
     * Returns a single field by zero-based index, or an empty string if
     * the index is out of range.
     */
    public String getField(int index) {
        return (index >= 0 && index < fields.length) ? fields[index] : "";
    }

    /**
     * Returns the label-application sequence counter (PL3 STX/ETX format),
     * or {@code -1} if not available.
     */
    public int getSequence() { return sequence; }

    /**
     * Returns the SSCC-18 pallet number found in this log entry, or
     * {@code null} if no 18-digit decimal field could be located.
     */
    public String getSscc() { return sscc; }

    /**
     * Returns the ISO-8601 timestamp from a PL6 log line, or {@code null}
     * when this entry originated from a PL3 log.
     */
    public String getTimestamp() { return timestamp; }

    /**
     * Returns {@code true} if this entry and {@code other} share the same
     * non-null SSCC, indicating both labels were applied to the same pallet.
     */
    public boolean isSamePallet(PalletLogEntry other) {
        return sscc != null && other != null && sscc.equals(other.sscc);
    }

    @Override
    public String toString() {
        return "PalletLogEntry{sscc=" + sscc
                + ", seq=" + sequence
                + (timestamp != null ? ", ts=" + timestamp : "")
                + ", raw=" + rawData + "}";
    }
}
