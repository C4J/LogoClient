package com.commander4j.client.common;

/**
 * Protocol-neutral result returned by labeller operations.
 *
 * <p>On PL3 the result is derived from the ACK/NAK wire response. On PL6
 * (SFTP-based) it wraps the outcome of a file-system operation. Callers
 * use {@link #isOk()} to test success and {@link #getBody()} for any
 * additional detail.
 */
public final class LabellerResult {

    private final String  body;
    private final boolean ok;
    private final String  errorCode;

    private LabellerResult(String body, boolean ok, String errorCode) {
        this.body      = body;
        this.ok        = ok;
        this.errorCode = errorCode;
    }

    /** Creates a successful result with the given body text. */
    public static LabellerResult ok(String body) {
        return new LabellerResult(body, true, "");
    }

    /** Creates a failed result. {@code errorCode} is a short diagnostic token. */
    public static LabellerResult fail(String body, String errorCode) {
        return new LabellerResult(body, false, errorCode);
    }

    /**
     * Converts a PL3 ACK response. The body is everything received before the
     * ACK byte; the error code is the 3-digit decimal prefix on a NAK body.
     */
    public static LabellerResult fromPl3(String body, boolean ack) {
        if (ack) return ok(body);
        String code = (body.length() >= 3)
                ? body.substring(body.length() - 3).trim()
                : "";
        return fail(body, code);
    }

    /** {@code true} if the operation succeeded. */
    public boolean isOk() { return ok; }

    /**
     * Raw response body. May be empty for simple commands.
     * On PL3 this is everything before the ACK/NAK byte.
     */
    public String getBody() { return body; }

    /**
     * Short error code when {@link #isOk()} is {@code false}.
     * On PL3 this is the 3-digit decimal error number. Empty on success.
     */
    public String getErrorCode() { return errorCode; }

    /**
     * Body with leading {@code >} and surrounding whitespace stripped.
     * Useful for parsing PL3 date/time and similar prefixed responses.
     */
    public String getBodyTrimmed() {
        String s = body.trim();
        if (s.startsWith(">")) s = s.substring(1);
        return s.trim();
    }

    @Override
    public String toString() {
        return ok
                ? "OK[body=" + body + "]"
                : "FAIL[error=" + errorCode + ", body=" + body + "]";
    }
}
