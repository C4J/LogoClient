package com.commander4j.client.pl3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Encoder and decoder for the Intel HEX format used by the Logopak
 * PowerLeap III labeller ({@code *HEXFILE} / {@code *TYPEHEX} commands).
 *
 * <p>The Logopak variant of Intel HEX differs from the standard in two ways:
 * <ul>
 *   <li>Lines are terminated with {@code <CR>} only (not {@code <CR><LF>}).</li>
 *   <li>The end-of-file record is {@code :0000000000} rather than the standard
 *       {@code :00000001FF}.</li>
 * </ul>
 */
public final class IntelHexCodec {

    /** Number of data bytes per encoded record (32 bytes = 64 hex chars per line). */
    public static final int RECORD_SIZE = 32;

    private IntelHexCodec() {}

    // -------------------------------------------------------------------------
    // Encode
    // -------------------------------------------------------------------------

    /**
     * Encode raw bytes into an Intel HEX string ready to stream to the labeller.
     *
     * @param data binary content to encode; must not be null
     * @return Intel HEX string (CR-terminated lines, Logopak EOF record)
     * @throws IllegalArgumentException if {@code data} exceeds 65535 bytes
     */
    public static String encode(byte[] data) {
        if (data.length > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Intel HEX 16-bit addressing supports at most 65535 bytes; "
                            + "file is " + data.length + " bytes");
        }
        StringBuilder sb = new StringBuilder();
        int offset  = 0;
        int address = 0;
        while (offset < data.length) {
            int count = Math.min(RECORD_SIZE, data.length - offset);
            sb.append(buildRecord((byte) 0x00, address, data, offset, count));
            sb.append('\r');
            address += count;
            offset  += count;
        }
        sb.append(":0000000000\r");   // Logopak end-of-file record
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Decode
    // -------------------------------------------------------------------------

    /**
     * Decode an Intel HEX string (as returned by {@code *TYPEHEX}) back into
     * raw bytes. Both the standard EOF record ({@code :00000001FF}) and the
     * Logopak-specific end record ({@code :0000000000}) are recognised.
     * Line endings of {@code <CR>}, {@code <LF>}, or {@code <CR><LF>} are all
     * accepted. Checksums are verified for every record.
     *
     * @param hexText Intel HEX encoded text
     * @return decoded raw bytes
     * @throws IOException if a checksum fails or the format is invalid
     */
    public static byte[] decode(String hexText) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (String raw : hexText.split("\r\n|\r|\n")) {
            String line = raw.trim();
            if (line.isEmpty() || !line.startsWith(":")) continue;
            if (line.length() < 11) {
                throw new IOException("Intel HEX record too short: '" + line + "'");
            }

            int byteCount  = Integer.parseInt(line.substring(1, 3), 16);
            int address    = Integer.parseInt(line.substring(3, 7), 16);
            int recordType = Integer.parseInt(line.substring(7, 9), 16);

            int expectedLength = 1 + 2 + 4 + 2 + byteCount * 2 + 2;
            if (line.length() < expectedLength) {
                throw new IOException(
                        "Intel HEX record length mismatch at address 0x"
                                + Integer.toHexString(address) + ": '" + line + "'");
            }

            int sum = 0;
            for (int i = 1; i < expectedLength - 2; i += 2) {
                sum += Integer.parseInt(line.substring(i, i + 2), 16);
            }
            int recordChecksum = Integer.parseInt(
                    line.substring(expectedLength - 2, expectedLength), 16);
            if (((sum + recordChecksum) & 0xFF) != 0) {
                throw new IOException("Intel HEX checksum error at address 0x"
                        + Integer.toHexString(address));
            }

            if (recordType == 0x00) {
                if (byteCount == 0) break;   // Logopak :0000000000 EOF
                for (int i = 0; i < byteCount; i++) {
                    int pos = 9 + i * 2;
                    baos.write(Integer.parseInt(line.substring(pos, pos + 2), 16));
                }
            } else if (recordType == 0x01) {
                break;   // Standard Intel HEX EOF record
            }
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /** Returns a hex-dump string for wire logging (space-separated bytes). */
    public static String hexOf(byte[] bytes) {
        if (bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String buildRecord(byte recordType, int address,
                                      byte[] data, int offset, int count) {
        StringBuilder rec = new StringBuilder(11 + count * 2);
        rec.append(':');
        rec.append(String.format("%02X", count));
        rec.append(String.format("%04X", address & 0xFFFF));
        rec.append(String.format("%02X", recordType & 0xFF));

        int checksum = count
                + ((address >> 8) & 0xFF)
                + (address & 0xFF)
                + (recordType & 0xFF);
        for (int i = 0; i < count; i++) {
            int b = data[offset + i] & 0xFF;
            rec.append(String.format("%02X", b));
            checksum += b;
        }
        rec.append(String.format("%02X", (0x100 - (checksum & 0xFF)) & 0xFF));
        return rec.toString();
    }
}
