package com.commander4j.client.common;

import com.jcraft.jsch.*;

import java.io.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * Generic SFTP client used by {@code LogoClientPL6} to transfer files to and
 * from Logopak PowerLeap 6 labellers.
 *
 * <h2>Dependency</h2>
 * <p>Requires the JSch library (mwiede fork recommended):
 * <pre>{@code
 * <dependency>
 *     <groupId>com.github.mwiede</groupId>
 *     <artifactId>jsch</artifactId>
 *     <version>0.2.20</version>
 * </dependency>
 * }</pre>
 *
 * Both the mwiede fork and the original JCraft JSch use the same
 * {@code com.jcraft.jsch} import namespace, so this class compiles
 * against either version without modification.
 */
public class LogoClientSftp implements AutoCloseable {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Default SFTP / SSH port. */
    public static final int DEFAULT_SFTP_PORT  = 22;

    /** Default connection timeout in milliseconds. */
    public static final int DEFAULT_TIMEOUT_MS = 15_000;

    private static final int BUFFER_SIZE = 8_192;

    // =========================================================================
    // State
    // =========================================================================

    private final String host;
    private final int    port;
    private final int    timeoutMs;

    private JSch        jsch;
    private Session     session;
    private ChannelSftp channel;

    /** Host key checking mode applied to every new session. Default: {@code "accept-new"}. */
    private String strictHostKeyCheckingMode = "accept-new";

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Create a client targeting the given host using the standard SSH port (22).
     *
     * @param host IP address or hostname of the SFTP server
     */
    public LogoClientSftp(String host) {
        this(host, DEFAULT_SFTP_PORT, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Create a client with a custom port and timeout.
     *
     * @param host      IP address or hostname
     * @param port      SSH port (typically 22)
     * @param timeoutMs connection and channel timeout in milliseconds
     */
    public LogoClientSftp(String host, int port, int timeoutMs) {
        this.host      = host;
        this.port      = port;
        this.timeoutMs = timeoutMs;
        this.jsch      = new JSch();
    }

    // =========================================================================
    // Connection management
    // =========================================================================

    /**
     * Connect and authenticate with a username and password.
     *
     * @param user     SSH username
     * @param password SSH password
     * @throws JSchException if authentication fails or the server is unreachable
     */
    public void connect(String user, String password) throws JSchException {
        session = jsch.getSession(user, host, port);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", strictHostKeyCheckingMode);
        session.setTimeout(timeoutMs);
        session.setServerAliveInterval(10_000);
        session.setServerAliveCountMax(3);
        session.connect(timeoutMs);
        openSftpChannel();
    }

    /**
     * Connect and authenticate using a private key file.
     *
     * @param user           SSH username
     * @param privateKeyPath absolute path to the private key file
     * @param passphrase     key passphrase, or {@code null} for unencrypted keys
     * @throws JSchException if authentication fails
     */
    public void connectWithKey(String user, String privateKeyPath, String passphrase)
            throws JSchException {
        if (passphrase != null) {
            jsch.addIdentity(privateKeyPath, passphrase);
        } else {
            jsch.addIdentity(privateKeyPath);
        }
        session = jsch.getSession(user, host, port);
        session.setConfig("StrictHostKeyChecking", strictHostKeyCheckingMode);
        session.setTimeout(timeoutMs);
        session.setServerAliveInterval(10_000);
        session.setServerAliveCountMax(3);
        session.connect(timeoutMs);
        openSftpChannel();
    }

    /**
     * Connect using a private key supplied as a byte array.
     *
     * @param user        SSH username
     * @param privateKey  private key bytes (PEM or OpenSSH format)
     * @param passphrase  passphrase bytes, or {@code null} for unencrypted keys
     * @throws JSchException if authentication fails
     */
    public void connectWithKey(String user, byte[] privateKey, byte[] passphrase)
            throws JSchException {
        jsch.addIdentity("labeller-key", privateKey, null, passphrase);
        session = jsch.getSession(user, host, port);
        session.setConfig("StrictHostKeyChecking", strictHostKeyCheckingMode);
        session.setTimeout(timeoutMs);
        session.setServerAliveInterval(10_000);
        session.setServerAliveCountMax(3);
        session.connect(timeoutMs);
        openSftpChannel();
    }

    /**
     * Provide a known-hosts file for host key verification.
     * Call this before any {@code connect} method.
     *
     * @param knownHostsPath path to the known_hosts file
     * @throws JSchException if the file cannot be read
     */
    public void setKnownHosts(String knownHostsPath) throws JSchException {
        jsch.setKnownHosts(knownHostsPath);
    }

    /**
     * Control whether the server's host key must match a stored entry.
     * Must be called before {@link #connect(String, String)} or
     * {@link #connectWithKey} to have any effect.
     *
     * @param strict {@code true} for strict checking ({@code "yes"}),
     *               {@code false} to disable ({@code "no"})
     */
    public void setStrictHostKeyChecking(boolean strict) {
        strictHostKeyCheckingMode = strict ? "yes" : "no";
    }

    /** Returns {@code true} if the SSH session and SFTP channel are open. */
    public boolean isConnected() {
        return session != null && session.isConnected()
                && channel != null && channel.isConnected();
    }

    /** Returns the hostname or IP address this client connects to. */
    public String getHost() { return host; }

    /** Close the SFTP channel and SSH session. */
    @Override
    public void close() {
        if (channel != null && channel.isConnected()) channel.disconnect();
        if (session != null && session.isConnected())  session.disconnect();
        channel = null;
        session = null;
    }

    // =========================================================================
    // File operations
    // =========================================================================

    /**
     * List the files in a remote directory.
     *
     * @param remotePath remote directory path, e.g. {@code "/c0/"}
     * @return list of {@link FileEntry} objects; never null, may be empty
     * @throws SftpException if the path does not exist or cannot be listed
     */
    public List<FileEntry> listFiles(String remotePath) throws SftpException {
        Vector<ChannelSftp.LsEntry> entries = channel.ls(remotePath);
        List<FileEntry> result = new ArrayList<>(entries.size());
        for (ChannelSftp.LsEntry e : entries) {
            if (!e.getFilename().equals(".") && !e.getFilename().equals("..")) {
                result.add(new FileEntry(e));
            }
        }
        return result;
    }

    /**
     * List only the filenames in a remote directory.
     *
     * @param remotePath remote directory path
     * @return list of filename strings
     * @throws SftpException if the path does not exist
     */
    public List<String> listFileNames(String remotePath) throws SftpException {
        List<String> names = new ArrayList<>();
        for (FileEntry e : listFiles(remotePath)) names.add(e.name());
        return names;
    }

    /**
     * Upload a file to the remote server.
     *
     * @param remotePath full remote path and filename
     * @param data       raw binary content to upload
     * @throws SftpException if the transfer fails
     */
    public void uploadFile(String remotePath, byte[] data) throws SftpException {
        try (InputStream in = new ByteArrayInputStream(data)) {
            channel.put(in, remotePath, ChannelSftp.OVERWRITE);
        } catch (IOException e) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE,
                    "Stream close error: " + e.getMessage());
        }
    }

    /**
     * Upload a file from an {@link InputStream}.
     *
     * @param remotePath full remote path and filename
     * @param source     input stream supplying the file content; closed by this method
     * @throws SftpException if the transfer fails
     * @throws IOException   if reading from {@code source} fails
     */
    public void uploadFile(String remotePath, InputStream source)
            throws SftpException, IOException {
        try (source) {
            channel.put(source, remotePath, ChannelSftp.OVERWRITE);
        }
    }

    /**
     * Upload a file with a progress monitor callback.
     *
     * @param remotePath full remote path and filename
     * @param data       raw binary content to upload
     * @param monitor    progress callback (may be {@code null})
     * @throws SftpException if the transfer fails
     */
    public void uploadFile(String remotePath, byte[] data, SftpProgressMonitor monitor)
            throws SftpException {
        try (InputStream in = new ByteArrayInputStream(data)) {
            channel.put(in, remotePath, monitor, ChannelSftp.OVERWRITE);
        } catch (IOException e) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE,
                    "Stream close error: " + e.getMessage());
        }
    }

    /**
     * Download a file from the remote server.
     *
     * @param remotePath full remote path and filename
     * @return raw binary content of the file
     * @throws SftpException if the file does not exist or the transfer fails
     * @throws IOException   if the data stream cannot be read
     */
    public byte[] downloadFile(String remotePath) throws SftpException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = channel.get(remotePath)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * Download a file with a progress monitor callback.
     *
     * @param remotePath full remote path and filename
     * @param monitor    progress callback (may be {@code null})
     * @return raw binary content of the file
     * @throws SftpException if the transfer fails
     * @throws IOException   if the data stream cannot be read
     */
    public byte[] downloadFile(String remotePath, SftpProgressMonitor monitor)
            throws SftpException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = channel.get(remotePath, monitor)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * Delete a file from the remote server.
     *
     * @param remotePath full remote path and filename
     * @throws SftpException if the file does not exist or cannot be deleted
     */
    public void deleteFile(String remotePath) throws SftpException {
        channel.rm(remotePath);
    }

    /**
     * Rename or move a file on the remote server.
     *
     * @param fromPath existing remote path
     * @param toPath   new remote path
     * @throws SftpException if the operation fails
     */
    public void renameFile(String fromPath, String toPath) throws SftpException {
        channel.rename(fromPath, toPath);
    }

    /** Create a remote directory. */
    public void makeDirectory(String remotePath) throws SftpException {
        channel.mkdir(remotePath);
    }

    /** Remove a remote directory (must be empty). */
    public void removeDirectory(String remotePath) throws SftpException {
        channel.rmdir(remotePath);
    }

    /** Change the remote working directory. */
    public void changeDirectory(String remotePath) throws SftpException {
        channel.cd(remotePath);
    }

    /** Return the current remote working directory. */
    public String printWorkingDirectory() throws SftpException {
        return channel.pwd();
    }

    /**
     * Query the attributes of a remote file or directory without downloading it.
     *
     * @param remotePath remote path
     * @return a {@link FileEntry} containing the file's metadata
     * @throws SftpException if the path does not exist
     */
    public FileEntry stat(String remotePath) throws SftpException {
        SftpATTRS attrs = channel.stat(remotePath);
        String name = remotePath.contains("/")
                ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                : remotePath;
        return new FileEntry(name, attrs);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void openSftpChannel() throws JSchException {
        Channel ch = session.openChannel("sftp");
        ch.connect(timeoutMs);
        channel = (ChannelSftp) ch;
    }

    // =========================================================================
    // Inner class: FileEntry
    // =========================================================================

    /**
     * Metadata for a single remote file or directory entry.
     */
    public static final class FileEntry {

        private final String    name;
        private final SftpATTRS attrs;

        FileEntry(ChannelSftp.LsEntry entry) {
            this.name  = entry.getFilename();
            this.attrs = entry.getAttrs();
        }

        FileEntry(String name, SftpATTRS attrs) {
            this.name  = name;
            this.attrs = attrs;
        }

        /** The file or directory name (not the full path). */
        public String name() { return name; }

        /** File size in bytes ({@code 0} for directories). */
        public long size() { return attrs.getSize(); }

        /** Last-modified time as a Unix epoch timestamp (seconds). */
        public int modifiedTime() { return attrs.getMTime(); }

        /** Returns {@code true} if this entry is a directory. */
        public boolean isDirectory() { return attrs.isDir(); }

        /** Returns {@code true} if this entry is a regular file. */
        public boolean isFile() { return !attrs.isDir() && !attrs.isLink(); }

        /** Returns {@code true} if this entry is a symbolic link. */
        public boolean isLink() { return attrs.isLink(); }

        /** Unix permission string, e.g. {@code "-rw-r--r--"}. */
        public String permissions() { return attrs.getPermissionsString(); }

        /** The raw SFTP attribute object for additional metadata. */
        public SftpATTRS rawAttrs() { return attrs; }

        @Override
        public String toString() {
            return String.format("%-30s  %8d bytes  %s",
                    name, attrs.getSize(), attrs.getPermissionsString());
        }
    }
}
