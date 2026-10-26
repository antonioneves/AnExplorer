package dev.dworks.apps.anexplorer.network;

import android.text.TextUtils;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import org.apache.commons.net.ftp.FTPFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Vector;

import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.LogUtils;

/**
 * A server reached over SSH ("sftp"), opening at the user's home folder, logging in with a
 * password or a private key. A server's key is
 * trusted the first time and kept, so a later connection to a changed key is refused.
 */
public class SftpNetworkClient extends NetworkClient {

    private static final String TAG = SftpNetworkClient.class.getSimpleName();

    public static final String SCHEME = "sftp";
    public static final int DEFAULT_PORT = 22;

    private static final int TIMEOUT = 15000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String privateKey;

    private Session session;
    private ChannelSftp channel;
    private String workingDirectory = NetworkFile.separator;

    public SftpNetworkClient(String host, int port, String username, String password) {
        this(host, port, username, password, null);
    }

    /** @param privateKey a key file's text to log in with, and the password its passphrase */
    public SftpNetworkClient(String host, int port, String username, String password,
                             String privateKey) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password == null ? "" : password;
        this.privateKey = privateKey;
    }

    public static final int KEY_OK = 0;
    public static final int KEY_UNREADABLE = 1;
    public static final int KEY_WRONG_PASSPHRASE = 2;

    /** Whether a key file's text can log in, with the passphrase if it's encrypted */
    public static int checkPrivateKey(String privateKey, String passphrase) {
        final KeyPair key;
        try {
            key = KeyPair.load(new JSch(), privateKey.getBytes(StandardCharsets.UTF_8), null);
        } catch (JSchException e) {
            LogUtils.LOGD(TAG, "Unreadable private key: " + e);
            return KEY_UNREADABLE;
        }
        try {
            return key.isEncrypted() && !key.decrypt(passphrase) ? KEY_WRONG_PASSPHRASE : KEY_OK;
        } finally {
            key.dispose();
        }
    }

    @Override
    public boolean logout() {
        disconnect();
        return true;
    }

    @Override
    public synchronized boolean isConnected() {
        return null != session && session.isConnected() && null != channel && channel.isConnected();
    }

    @Override
    public boolean isAvailable() {
        return isConnected();
    }

    @Override
    public synchronized void disconnect() {
        if (null != channel) {
            channel.disconnect();
            channel = null;
        }
        if (null != session) {
            session.disconnect();
            session = null;
        }
    }

    @Override
    public boolean login(String username, String password) throws IOException {
        return connectClient();
    }

    @Override
    public boolean login(String username, String password, String account) throws IOException {
        return connectClient();
    }

    @Override
    public synchronized boolean connectClient() throws IOException {
        disconnect();
        try {
            final JSch jsch = new JSch();
            jsch.setKnownHosts(knownHosts().getAbsolutePath());
            final boolean withKey = !TextUtils.isEmpty(privateKey);
            if (withKey) {
                // The password is the key's passphrase, if it has one
                jsch.addIdentity(username + "@" + host, privateKey.getBytes(StandardCharsets.UTF_8),
                        null, password.isEmpty() ? null : password.getBytes(StandardCharsets.UTF_8));
            }
            session = jsch.getSession(username, host, port);
            if (!withKey) {
                session.setPassword(password);
            }
            session.setUserInfo(new TrustOnFirstUse());
            session.setConfig("StrictHostKeyChecking", "ask");
            session.setConfig("PreferredAuthentications", withKey
                    ? "publickey" : "password,keyboard-interactive");
            session.setServerAliveInterval(30000);
            session.connect(TIMEOUT);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(TIMEOUT);
            workingDirectory = channel.pwd();
            return true;
        } catch (JSchException | SftpException e) {
            LogUtils.LOGD(TAG, "SFTP connection to " + host + " failed: " + e);
            disconnect();
            return false;
        }
    }

    @Override
    public synchronized String getWorkingDirectory() {
        return workingDirectory;
    }

    @Override
    public synchronized void changeWorkingDirectory(String pathname) {
        workingDirectory = pathname;
    }

    @Override
    public InputStream getInputStream(String fileName, String directory) {
        try {
            return openInputStream(NetworkFile.join(directory, fileName));
        } catch (IOException e) {
            LogUtils.LOGE(TAG, "Error retrieving file from SFTP server: " + host, e);
            CrashReportingManager.logException(e);
        }
        return null;
    }

    /** On a channel of its own, so that browsing goes on while the file is read */
    @Override
    public InputStream openInputStream(String path) throws IOException {
        final ChannelSftp reader = openChannel();
        try {
            return new FilterInputStream(reader.get(path)) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        reader.disconnect();
                    }
                }
            };
        } catch (SftpException e) {
            reader.disconnect();
            throw new IOException(e);
        }
    }

    /** On a channel of its own too; the file is in place once the stream is closed */
    @Override
    public OutputStream openOutputStream(String path) throws IOException {
        final ChannelSftp writer = openChannel();
        try {
            return new FilterOutputStream(writer.put(path)) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        writer.disconnect();
                    }
                }
            };
        } catch (SftpException e) {
            writer.disconnect();
            throw new IOException(e);
        }
    }

    private synchronized ChannelSftp openChannel() throws IOException {
        ensureConnected();
        try {
            final ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(TIMEOUT);
            return channel;
        } catch (JSchException e) {
            throw new IOException(e);
        }
    }

    @Override
    public synchronized boolean createDirectories(String directory) throws IOException {
        ensureConnected();
        final StringBuilder path = new StringBuilder();
        for (String segment : directory.split(NetworkFile.separator)) {
            if (segment.isEmpty()) {
                continue;
            }
            path.append(NetworkFile.separatorChar).append(segment);
            final String folder = path.toString();
            if (null == stat(folder)) {
                try {
                    channel.mkdir(folder);
                } catch (SftpException e) {
                    throw new IOException(e);
                }
            }
        }
        final SftpATTRS made = stat(path.toString());
        return null != made && made.isDir();
    }

    /** An empty file, without overwriting one that's already there */
    @Override
    public synchronized boolean createFile(String path) throws IOException {
        ensureConnected();
        if (null != stat(path)) {
            return false;
        }
        try {
            channel.put(new ByteArrayInputStream(new byte[0]), path);
            return true;
        } catch (SftpException e) {
            throw new IOException(e);
        }
    }

    /** A folder goes with everything in it, as it does over WebDAV */
    @Override
    public synchronized boolean deleteFile(String path) throws IOException {
        ensureConnected();
        try {
            delete(path);
            return true;
        } catch (SftpException e) {
            LogUtils.LOGD(TAG, "SFTP server refused to delete " + path + ": " + e);
            return false;
        }
    }

    @Override
    public boolean supportsEditing() {
        return true;
    }

    @Override
    public synchronized FTPFile[] listFiles() throws IOException {
        ensureConnected();
        final List<FTPFile> files = new ArrayList<>();
        try {
            for (ChannelSftp.LsEntry entry : (Vector<ChannelSftp.LsEntry>) channel.ls(workingDirectory)) {
                final String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                SftpATTRS attrs = entry.getAttrs();
                if (attrs.isLink()) {
                    // Shown as what it points to; a broken link stays a file
                    final SftpATTRS target = stat(NetworkFile.join(workingDirectory, name));
                    if (null != target) {
                        attrs = target;
                    }
                }
                files.add(file(name, attrs));
            }
        } catch (SftpException e) {
            throw new IOException("Unreadable listing of " + workingDirectory, e);
        }
        return files.toArray(new FTPFile[0]);
    }

    @Override
    public boolean completePendingCommand() {
        return true;
    }

    /** Connects again after the server or the network dropped the session */
    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            final String directory = workingDirectory;
            if (!connectClient()) {
                throw new IOException("Can't connect to SFTP server " + host);
            }
            workingDirectory = directory;
        }
    }

    private SftpATTRS stat(String path) throws IOException {
        try {
            return channel.stat(path);
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return null;
            }
            throw new IOException(e);
        }
    }

    private void delete(String path) throws SftpException {
        final SftpATTRS attrs = channel.lstat(path);
        if (!attrs.isDir()) {
            channel.rm(path);
            return;
        }
        for (ChannelSftp.LsEntry entry : (Vector<ChannelSftp.LsEntry>) channel.ls(path)) {
            final String name = entry.getFilename();
            if (!".".equals(name) && !"..".equals(name)) {
                delete(NetworkFile.join(path, name));
            }
        }
        channel.rmdir(path);
    }

    private static FTPFile file(String name, SftpATTRS attrs) {
        final FTPFile file = new FTPFile();
        file.setName(name);
        file.setType(attrs.isDir() ? FTPFile.DIRECTORY_TYPE : FTPFile.FILE_TYPE);
        file.setSize(attrs.isDir() ? 0 : attrs.getSize());
        final Calendar timestamp = Calendar.getInstance();
        timestamp.setTimeInMillis(attrs.getMTime() * 1000L);
        file.setTimestamp(timestamp);
        return file;
    }

    private static File knownHosts() throws IOException {
        final File file = new File(DocumentsApplication.getInstance().getFilesDir(), "known_hosts");
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("Can't create " + file);
        }
        return file;
    }

    /** Adds a server seen for the first time, but never replaces the key of a known one */
    private static class TrustOnFirstUse implements UserInfo {
        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public boolean promptPassword(String message) {
            return false;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            // JSch also asks whether to swap in a changed key, which is the warning ssh gives
            return null != message && message.contains("Are you sure you want to continue connecting")
                    && !message.contains("HAS CHANGED");
        }

        @Override
        public void showMessage(String message) {
            LogUtils.LOGD(TAG, message);
        }
    }
}
