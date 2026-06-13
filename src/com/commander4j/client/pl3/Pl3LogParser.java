package com.commander4j.client.pl3;

import com.commander4j.client.common.PalletLogEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the raw body returned by the {@code *TRXLOG} command into a list of
 * {@link PalletLogEntry} records.
 *
 * <h2>Supported formats</h2>
 * <ul>
 *   <li><b>STX/ETX</b> – body starts with {@code \u0002} (STX). Each entry is
 *       wrapped in {@code <STX>data\rNNN<ETX>} where {@code NNN} is a 3-digit
 *       decimal sequence counter.</li>
 *   <li><b>Intel HEX</b> – body starts with {@code ':'}. Decoded to ASCII text
 *       then parsed as plain text.</li>
 *   <li><b>Plain text</b> – all other responses; one log entry per non-empty line.</li>
 * </ul>
 *
 * <p>The labeller format is selected in:
 * {@code Settings → System Settings → Communication → Logdata Format}
 * (system.sup key {@code LOGFILE_TRANSMIT_FORMAT}). STXETX is recommended
 * as it carries the sequence counter.
 */
public final class Pl3LogParser {

    private Pl3LogParser() {}

    /**
     * Auto-detects the format of {@code rawBody} and delegates to the
     * appropriate parser.
     *
     * @param rawBody the {@code *TRXLOG} response body; may be null or empty
     * @return parsed entries in document order; never null
     */
    public static List<PalletLogEntry> parse(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) return Collections.emptyList();

        if (rawBody.charAt(0) == '\u0002') {           // STX — STX/ETX block format
            return parseStxEtx(rawBody);
        }
        if (rawBody.charAt(0) == ':') {                // ':' — Intel HEX encoded
            try {
                byte[] decoded = IntelHexCodec.decode(rawBody);
                String text = new String(decoded, StandardCharsets.US_ASCII);
                return parsePlainText(text);
            } catch (IllegalArgumentException | IOException e) {
                // Malformed HEX — fall through to plain-text parsing
            }
        }
        return parsePlainText(rawBody);
    }

    /**
     * Parses a STX/ETX formatted log stream.
     *
     * <p>Each entry has the structure:
     * <pre>  STX  field19500_data  CR  NNN  ETX</pre>
     * where {@code NNN} is a 3-digit decimal label-application sequence counter.
     *
     * @param raw raw log body from {@code *TRXLOG} (may contain multiple blocks)
     * @return parsed entries in document order
     */
    public static List<PalletLogEntry> parseStxEtx(String raw) {
        List<PalletLogEntry> result = new ArrayList<>();
        int i = 0;
        while (i < raw.length()) {
            int stx = raw.indexOf('\u0002', i);
            if (stx < 0) break;
            int etx = raw.indexOf('\u0003', stx + 1);
            if (etx < 0) break;

            String block = raw.substring(stx + 1, etx);
            int crPos = block.lastIndexOf('\r');
            String data;
            int sequence = -1;
            if (crPos >= 0) {
                data = block.substring(0, crPos);
                String seqStr = block.substring(crPos + 1).trim();
                try { sequence = Integer.parseInt(seqStr); }
                catch (NumberFormatException ignored) {}
            } else {
                data = block;
            }
            if (!data.isEmpty()) {
                result.add(new PalletLogEntry(data, sequence));
            }
            i = etx + 1;
        }
        return result;
    }

    /**
     * Parses a plain-text log where each non-empty line is a single log entry.
     * Lines are split on {@code \r\n}, {@code \r}, or {@code \n}.
     *
     * @param text decoded plain-text log content
     * @return parsed entries in document order
     */
    public static List<PalletLogEntry> parsePlainText(String text) {
        List<PalletLogEntry> result = new ArrayList<>();
        for (String line : text.split("[\r\n]+")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(new PalletLogEntry(trimmed, -1));
            }
        }
        return result;
    }
}
