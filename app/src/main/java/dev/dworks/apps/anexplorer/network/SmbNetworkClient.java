package dev.dworks.apps.anexplorer.network;

import android.text.TextUtils;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.PipeShare;
import com.hierynomus.smbj.share.Share;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.rapid7.client.dcerpc.Interface;
import com.rapid7.client.dcerpc.mssrvs.ServerService;
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1;
import com.rapid7.client.dcerpc.transport.SMBTransport;
import com.rapid7.helper.smbj.share.NamedPipe;

import org.apache.commons.net.ftp.FTPFile;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.LogUtils;

/**
 * A Windows (SMB 2/3) server. A connection made for a share, or a folder in one, opens there;
 * one made for just the server opens at its list of shares, whose names are the first
 * segment of every path.
 */
public class SmbNetworkClient extends NetworkClient {

    private static final String TAG = SmbNetworkClient.class.getSimpleName();

    public static final String SCHEME = "smb";
    public static final int DEFAULT_PORT = 445;

    /** Folders and printers both appear as shares; only these hold files */
    private static final int SHARE_TYPE_MASK = 0xFF;
    private static final int SHARE_TYPE_DISK = 0;
    private static final int SHARE_SPECIAL = 0x80000000;

    /** Seconds before a server that stopped answering is given up on */
    private static final int TIMEOUT = 20;

    private final String host;
    private final int port;
    private final String basePath;
    private final String username;
    private final String password;
    private final SMBClient client;
    private final Map<String, DiskShare> shares = new HashMap<>();
    private Connection connection;
    private Session session;
    private String workingDirectory = NetworkFile.separator;

    public SmbNetworkClient(String host, int port, String basePath, String username, String password) {
        this.host = host;
        this.port = port;
        this.basePath = trimSlashes(basePath);
        this.username = username;
        this.password = password;
        client = new SMBClient(SmbConfig.builder()
                // Android's own provider has no MD4, which NTLM needs
                .withSecurityProvider(new BCSecurityProvider())
                .withTimeout(TIMEOUT, TimeUnit.SECONDS)
                .withSocketFactory(new ConnectTimeout())
                .build());
    }

    @Override
    public synchronized boolean logout() throws IOException {
        disconnect();
        return true;
    }

    @Override
    public synchronized boolean isConnected() {
        return session != null && connection != null && connection.isConnected();
    }

    @Override
    public boolean isAvailable() {
        return isConnected();
    }

    @Override
    public synchronized void disconnect() throws IOException {
        shares.clear();
        session = null;
        if (connection != null) {
            final Connection closing = connection;
            connection = null;
            closing.close(true);
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
        connection = client.connect(host, port);
        try {
            try {
                session = connection.authenticate(authentication());
            } catch (SMBApiException e) {
                if (!TextUtils.isEmpty(username)) {
                    throw e;
                }
                // Servers that turn away anonymous users often let guests in
                session = connection.authenticate(AuthenticationContext.guest());
            }
            // A share named in the connection should be there, and readable
            final String share = shareOf(basePath);
            if (share != null) {
                share(share);
            }
        } catch (SMBRuntimeException e) {
            throw dropped(e);
        }
        return true;
    }

    @Override
    public String getWorkingDirectory() {
        return NetworkFile.separator;
    }

    @Override
    public void changeWorkingDirectory(String pathname) {
        workingDirectory = pathname;
    }

    @Override
    public InputStream getInputStream(String fileName, String directory) {
        try {
            return openInputStream(NetworkFile.join(directory, fileName));
        } catch (IOException e) {
            LogUtils.LOGE(TAG, "Error retrieving file from SMB server: " + host, e);
            CrashReportingManager.logException(e);
        }
        return null;
    }

    /**
     * Reads no further than the file's end: some servers answer a read past it with a reply
     * smbj can't parse, which fails the whole stream
     */
    @Override
    public InputStream openInputStream(String path) throws IOException {
        final File file = open(path, AccessMask.GENERIC_READ, SMB2CreateDisposition.FILE_OPEN);
        final long size;
        final int chunk;
        try {
            size = file.getFileInformation(FileStandardInformation.class).getEndOfFile();
            synchronized (this) {
                chunk = connection.getNegotiatedProtocol().getMaxReadSize();
            }
        } catch (SMBRuntimeException e) {
            file.close();
            throw dropped(e);
        }
        return new InputStream() {
            private final byte[] buffer = new byte[(int) Math.min(chunk, Math.max(size, 1))];
            private long position;
            private int start;
            private int end;

            @Override
            public int read() throws IOException {
                final byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (start == end) {
                    if (position >= size) {
                        return -1;
                    }
                    try {
                        end = file.read(buffer, position, 0,
                                (int) Math.min(buffer.length, size - position));
                    } catch (SMBRuntimeException e) {
                        throw dropped(e);
                    }
                    start = 0;
                    if (end <= 0) {
                        end = 0;
                        return -1;
                    }
                    position += end;
                }
                final int count = Math.min(len, end - start);
                System.arraycopy(buffer, start, b, off, count);
                start += count;
                return count;
            }

            @Override
            public int available() {
                return end - start;
            }

            @Override
            public void close() throws IOException {
                try {
                    file.close();
                } catch (SMBRuntimeException e) {
                    throw dropped(e);
                }
            }
        };
    }

    /** The file is in place once the stream is closed */
    @Override
    public OutputStream openOutputStream(String path) throws IOException {
        final File file = open(path, AccessMask.GENERIC_WRITE, SMB2CreateDisposition.FILE_OVERWRITE_IF);
        return new FilterOutputStream(file.getOutputStream()) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    file.close();
                }
            }
        };
    }

    @Override
    public boolean createDirectories(String directory) throws IOException {
        final String full = fullPath(directory);
        final String shareName = shareOf(full);
        final String inShare = pathInShare(full);
        if (shareName == null || inShare.isEmpty()) {
            return false;
        }
        return onShare(shareName, share -> {
            final StringBuilder path = new StringBuilder();
            boolean created = false;
            for (String segment : inShare.split("\\\\")) {
                if (path.length() > 0) {
                    path.append('\\');
                }
                path.append(segment);
                // Servers differ in how they say a folder is missing, but agree on one being there
                try {
                    share.mkdir(path.toString());
                    created = true;
                } catch (SMBApiException e) {
                    if (e.getStatus() != NtStatus.STATUS_OBJECT_NAME_COLLISION) {
                        throw new IOException(e);
                    }
                    created = false;
                }
            }
            return created;
        });
    }

    /** An empty file, without overwriting one that's already there */
    @Override
    public boolean createFile(String path) throws IOException {
        try {
            open(path, AccessMask.GENERIC_WRITE, SMB2CreateDisposition.FILE_CREATE).close();
            return true;
        } catch (SMBApiException e) {
            LogUtils.LOGD(TAG, "SMB server refused to create " + path + ": " + e.getStatus());
            return false;
        }
    }

    /** A folder goes with everything in it, as it does over WebDAV */
    @Override
    public boolean deleteFile(String path) throws IOException {
        final String full = fullPath(path);
        final String shareName = shareOf(full);
        final String inShare = pathInShare(full);
        if (shareName == null || inShare.isEmpty()) {
            return false;
        }
        try {
            return onShare(shareName, share -> {
                if (share.getFileInformation(inShare).getStandardInformation().isDirectory()) {
                    share.rmdir(inShare, true);
                } else {
                    share.rm(inShare);
                }
                return true;
            });
        } catch (SMBApiException e) {
            LogUtils.LOGD(TAG, "SMB server refused to delete " + path + ": " + e.getStatus());
            return false;
        }
    }

    @Override
    public boolean supportsEditing() {
        return true;
    }

    /** Not in the list of shares, which only the server's admin can change */
    @Override
    public boolean canCreateIn(String folder) {
        return shareOf(fullPath(folder)) != null;
    }

    /** Nor a whole share */
    @Override
    public boolean canDelete(String path) {
        return fullPath(path).indexOf(NetworkFile.separatorChar) >= 0;
    }

    @Override
    public FTPFile[] listFiles() throws IOException {
        final String full = fullPath(workingDirectory);
        final String shareName = shareOf(full);
        if (shareName == null) {
            return listShares();
        }
        final List<FileIdBothDirectoryInformation> entries =
                onShare(shareName, share -> share.list(pathInShare(full)));
        final List<FTPFile> files = new ArrayList<>();
        for (FileIdBothDirectoryInformation entry : entries) {
            final String name = entry.getFileName();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            final boolean directory = (entry.getFileAttributes()
                    & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
            files.add(file(name, directory, entry.getEndOfFile(),
                    entry.getLastWriteTime().toEpochMillis()));
        }
        return files.toArray(new FTPFile[0]);
    }

    @Override
    public boolean completePendingCommand() {
        return true;
    }

    /** The pipe and IPC$ are closed after, as servers stop answering when too many are left open */
    private FTPFile[] listShares() throws IOException {
        final List<NetShareInfo1> shares;
        try (PipeShare ipc = (PipeShare) session().connectShare("IPC$");
             NamedPipe pipe = new NamedPipe(ipc.getTreeConnect().getSession(), ipc, "srvsvc")) {
            final SMBTransport transport = new SMBTransport(pipe);
            transport.bind(Interface.SRVSVC_V3_0, Interface.NDR_32BIT_V2);
            shares = new ServerService(transport).getShares1();
        } catch (SMBRuntimeException e) {
            throw dropped(e);
        }
        final List<FTPFile> files = new ArrayList<>();
        for (NetShareInfo1 share : shares) {
            if (isFolderShare(share.getType(), share.getNetName())) {
                files.add(file(share.getNetName(), true, 0, 0));
            }
        }
        return files.toArray(new FTPFile[0]);
    }

    /** Not printers, pipes or the hidden admin shares like C$ */
    static boolean isFolderShare(int type, String name) {
        return (type & SHARE_TYPE_MASK) == SHARE_TYPE_DISK && (type & SHARE_SPECIAL) == 0
                && !name.endsWith("$");
    }

    private File open(String path, AccessMask access, SMB2CreateDisposition disposition)
            throws IOException {
        final String full = fullPath(path);
        final String shareName = shareOf(full);
        if (shareName == null || pathInShare(full).isEmpty()) {
            throw new IOException("Not a file: " + path);
        }
        return onShare(shareName, share -> share.openFile(pathInShare(full), EnumSet.of(access),
                null, SMB2ShareAccess.ALL, disposition,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)));
    }

    private interface ShareTask<T> {
        T run(DiskShare share) throws IOException;
    }

    /**
     * Runs a task on a share, and once more on the share connected again if it was closed
     * under the task, as the connection dropping by another task or the server closes them all.
     * The server's refusals stay as they are; smbj's other errors become IOExceptions.
     */
    private <T> T onShare(String name, ShareTask<T> task) throws IOException {
        final DiskShare share = share(name);
        try {
            return task.run(share);
        } catch (SMBApiException e) {
            throw e;
        } catch (SMBRuntimeException e) {
            if (share.isConnected()) {
                throw dropped(e);
            }
            LogUtils.LOGD(TAG, "SMB share " + name + " was closed, connecting again: " + e);
            synchronized (this) {
                // Unless another task has connected again already, the connection is gone too
                if (shares.get(name) == share) {
                    disconnect();
                }
            }
        }
        try {
            return task.run(share(name));
        } catch (SMBApiException e) {
            throw e;
        } catch (SMBRuntimeException e) {
            throw dropped(e);
        }
    }

    /** A server that stopped answering leaves the connection looking open, so it's let go */
    private IOException dropped(SMBRuntimeException e) {
        if (!(e instanceof SMBApiException)) {
            try {
                disconnect();
            } catch (IOException ignored) {
            }
        }
        return new IOException(e);
    }

    private synchronized Session session() throws IOException {
        if (!isConnected()) {
            connectClient();
        }
        return session;
    }

    /** The share, connected again if the server has let it go */
    private synchronized DiskShare share(String name) throws IOException {
        DiskShare share = shares.get(name);
        if (share != null && share.isConnected() && isConnected()) {
            return share;
        }
        final Share connected;
        try {
            connected = session().connectShare(name);
        } catch (SMBRuntimeException e) {
            throw dropped(e);
        }
        if (!(connected instanceof DiskShare)) {
            connected.close();
            throw new IOException(name + " isn't a shared folder");
        }
        share = (DiskShare) connected;
        shares.put(name, share);
        return share;
    }

    private AuthenticationContext authentication() {
        if (TextUtils.isEmpty(username)) {
            return AuthenticationContext.anonymous();
        }
        // A user of a domain can be given as DOMAIN\\user
        String domain = null;
        String user = username;
        final int slash = username.indexOf('\\');
        if (slash > 0) {
            domain = username.substring(0, slash);
            user = username.substring(slash + 1);
        }
        return new AuthenticationContext(user,
                (password == null ? "" : password).toCharArray(), domain);
    }

    /** The path from the server, share first, without slashes at either end */
    private String fullPath(String path) {
        final String relative = trimSlashes(path);
        if (basePath.isEmpty()) {
            return relative;
        }
        return relative.isEmpty() ? basePath : basePath + NetworkFile.separator + relative;
    }

    static String shareOf(String full) {
        if (full.isEmpty()) {
            return null;
        }
        final int slash = full.indexOf(NetworkFile.separatorChar);
        return slash < 0 ? full : full.substring(0, slash);
    }

    /** The path inside its share, in the backslashes SMB uses */
    static String pathInShare(String full) {
        final int slash = full.indexOf(NetworkFile.separatorChar);
        return slash < 0 ? "" : full.substring(slash + 1).replace(NetworkFile.separatorChar, '\\');
    }

    private static FTPFile file(String name, boolean directory, long size, long modified) {
        final FTPFile file = new FTPFile();
        file.setName(name);
        file.setType(directory ? FTPFile.DIRECTORY_TYPE : FTPFile.FILE_TYPE);
        file.setSize(directory ? 0 : size);
        final Calendar timestamp = Calendar.getInstance();
        timestamp.setTimeInMillis(modified);
        file.setTimestamp(timestamp);
        return file;
    }

    static String trimSlashes(String path) {
        if (path == null) {
            return "";
        }
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == NetworkFile.separatorChar) {
            start++;
        }
        while (end > start && path.charAt(end - 1) == NetworkFile.separatorChar) {
            end--;
        }
        return path.substring(start, end);
    }

    /**
     * Gives up on an unreachable server in time. A read timeout instead would fail smbj's reader
     * on an idle connection, and it hangs closing that connection
     */
    private static class ConnectTimeout extends SocketFactory {
        @Override
        public Socket createSocket() {
            return new Socket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return connect(new InetSocketAddress(host, port));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return connect(new InetSocketAddress(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException();
        }

        private static Socket connect(InetSocketAddress address) throws IOException {
            final Socket socket = new Socket();
            try {
                socket.connect(address, (int) TimeUnit.SECONDS.toMillis(TIMEOUT));
            } catch (IOException e) {
                socket.close();
                throw e;
            }
            return socket;
        }
    }
}
