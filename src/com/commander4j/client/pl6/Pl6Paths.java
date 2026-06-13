package com.commander4j.client.pl6;

/**
 * Filesystem path constants for the Logopak PowerLeap 6 Linux system.
 *
 * <p>All paths are confirmed from vendor correspondence (July 2019).
 * The data directory path ({@link #DATA_DIR}) is
 * a placeholder — confirm with Logopak before using {@code setDataReady()}.
 */
public final class Pl6Paths {

    private Pl6Paths() {}

    /** Base directory for all Logopak application files. */
    public static final String BASE_DIR     = "/userapp/software/draw/";

    /** Layout files (*.llf, *.lqf). Upload here to make layouts available. */
    public static final String LAYOUTS_DIR  = BASE_DIR + "layouts/";

    /** Image files for LEAP (*.bmp, *.pcx, *.png, etc.). */
    public static final String IMAGES_DIR   = BASE_DIR + "images/leap/";

    /** Customer-specific font files. */
    public static final String FONTS_DIR    = BASE_DIR + "userfonts/";

    /**
     * Log directory containing {@code leap.log} and daily backup files.
     * The log file grows throughout each day and is rotated at midnight.
     */
    public static final String LOG_DIR      = BASE_DIR + "log/";

    /** Active pallet log file — append-only, one line per label application. */
    public static final String LOG_FILE     = LOG_DIR + "leap.log";

    /**
     * Data / LDF file directory — the upload target for label data files that
     * trigger the data-ready signal on PL6.
     *
     * <p><b>WARNING:</b> This path is not yet confirmed with Logopak. Verify
     * before using {@code setDataReady()} on a live PL6 system.
     */
    public static final String DATA_DIR     = BASE_DIR + "data/";
}
