package com.commander4j.client.common;

/**
 * Protocol-neutral description of a remote file or directory entry.
 *
 * <p>Returned by the directory-listing methods on {@link ILogoLabeller}.
 * On PL3 the data is sourced from a {@code *DIR} command response; on PL6
 * it comes from an SFTP directory listing.
 */
public final class FileInfo {

    private final String  name;
    private final long    size;
    private final long    modifiedTimeEpochSeconds;
    private final boolean directory;

    public FileInfo(String name, long size, long modifiedTimeEpochSeconds, boolean directory) {
        this.name                     = name;
        this.size                     = size;
        this.modifiedTimeEpochSeconds = modifiedTimeEpochSeconds;
        this.directory                = directory;
    }

    /** File or directory name (no path prefix). */
    public String getName() { return name; }

    /** File size in bytes; 0 for directories. */
    public long getSize() { return size; }

    /** Last-modified time as Unix epoch seconds; 0 if unavailable. */
    public long getModifiedTimeEpochSeconds() { return modifiedTimeEpochSeconds; }

    /** {@code true} if this entry represents a directory. */
    public boolean isDirectory() { return directory; }

    /** {@code true} if this entry represents a regular file. */
    public boolean isFile() { return !directory; }

    @Override
    public String toString() {
        return (directory ? "DIR  " : "FILE ")
                + name + "  size=" + size;
    }
}
