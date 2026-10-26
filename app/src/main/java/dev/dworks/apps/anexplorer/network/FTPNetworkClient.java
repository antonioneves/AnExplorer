package dev.dworks.apps.anexplorer.network;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.LogUtils;

/**
 * Created by HaKr on 02/01/17.
 */

public class FTPNetworkClient extends NetworkClient {

    private static final String TAG = FTPNetworkClient.class.getSimpleName();

    public FTPClient client;
    public String host;
    public int port;
    public String username;
    public String password;

    public FTPNetworkClient(final String host, final int port){
        this(host, port, "anonymous", "");
    }

    public FTPNetworkClient(final String host, final int port, final String userName, final String password){
        this.host = host;
        this.port = port;
        this.username = userName;
        this.password = password;
        client = newClient();
    }

    /** A client for one connection; a file being read or written gets one of its own */
    protected FTPClient newClient() {
        return new FTPClient();
    }

    @Override
    public boolean logout() throws IOException {
        return client.logout();
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public boolean isAvailable() {
        return client.isAvailable();
    }

    @Override
    public void disconnect() throws IOException {
        client.disconnect();
    }

    @Override
    public boolean login(String username, String password) throws IOException {
        return client.login(username, password);
    }

    @Override
    public boolean login(String username, String password, String account) throws IOException {
        return client.login(username, password, account);
    }

    @Override
    public boolean connectClient() throws IOException {
        return login(client);
    }

    protected boolean login(FTPClient client) throws IOException {
        client.setAutodetectUTF8(true);
        client.setControlEncoding("UTF-8");
        client.connect(host, port);
        client.login(username, password);
        int reply = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            client.disconnect();
            LogUtils.LOGD(TAG, "Negative reply form FTP server, aborting, id was {}:"+ reply);
            return false;
        }
        afterLogin(client);
        client.setFileType(FTP.BINARY_FILE_TYPE);
        client.enterLocalPassiveMode();
        return true;
    }

    /** Whatever else the server needs before files go back and forth */
    protected void afterLogin(FTPClient client) throws IOException {
    }

    private FTPClient openTransferClient() throws IOException {
        final FTPClient transfer = newClient();
        if (!login(transfer)) {
            throw new IOException("FTP server refused the login");
        }
        return transfer;
    }

    /** On a connection of its own, so that browsing goes on while the file is read */
    @Override
    public InputStream openInputStream(String path) throws IOException {
        final FTPClient reader = openTransferClient();
        final InputStream stream = reader.retrieveFileStream(path);
        if (null == stream) {
            reader.disconnect();
            throw new IOException("Can't read " + path + ": " + reader.getReplyString());
        }
        return new FilterInputStream(stream) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                    reader.completePendingCommand();
                    reader.logout();
                } finally {
                    reader.disconnect();
                }
            }
        };
    }

    /** The file is in place once the stream is closed */
    @Override
    public OutputStream openOutputStream(String path) throws IOException {
        final FTPClient writer = openTransferClient();
        final OutputStream stream = writer.storeFileStream(path);
        if (null == stream) {
            writer.disconnect();
            throw new IOException("Can't write " + path + ": " + writer.getReplyString());
        }
        return new FilterOutputStream(stream) {
            private long written;

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
                written += len;
            }

            @Override
            public void close() throws IOException {
                try {
                    out.flush();
                    beforeUploadClose(writer);
                    super.close();
                    if (!writer.completePendingCommand()) {
                        throw new IOException("FTP server didn't keep " + path);
                    }
                    checkSize(writer, path, written);
                    writer.logout();
                } finally {
                    writer.disconnect();
                }
            }
        };
    }

    /** Whatever the data connection needs before an upload ends */
    protected void beforeUploadClose(FTPClient client) {
    }

    /** So a file the server cut short isn't taken for a copy, where the server tells its size */
    private static void checkSize(FTPClient client, String path, long written) throws IOException {
        if (!FTPReply.isPositiveCompletion(client.sendCommand("SIZE", path))) {
            return;
        }
        final String[] reply = client.getReplyString().trim().split("\\s+");
        try {
            final long size = Long.parseLong(reply[reply.length - 1]);
            if (size != written) {
                throw new IOException("FTP server has " + size + " of " + written + " bytes of " + path);
            }
        } catch (NumberFormatException e) {
            LogUtils.LOGD(TAG, "Unreadable SIZE reply: " + client.getReplyString());
        }
    }

    @Override
    public String getWorkingDirectory() throws IOException {
        return client.printWorkingDirectory();
    }

    @Override
    public void changeWorkingDirectory(String pathname) throws IOException {
        client.changeWorkingDirectory(pathname);
    }

    @Override
    public InputStream getInputStream(final String fileName, final String directory) {
        try {
            return openInputStream(NetworkFile.join(directory, fileName));
        } catch (IOException e) {
            LogUtils.LOGE(TAG, "Error retrieving file from FTP server: " + host, e);
            CrashReportingManager.logException(e);
        }
        return null;
    }

    /** Makes every missing folder along the path */
    @Override
    public boolean createDirectories(final String directory) throws IOException {
        final StringBuilder path = new StringBuilder();
        for (String segment : directory.split(NetworkFile.separator)) {
            if (segment.isEmpty()) {
                continue;
            }
            path.append(NetworkFile.separatorChar).append(segment);
            // Refused when the folder is already there, which the check below allows for
            client.makeDirectory(path.toString());
        }
        return client.changeWorkingDirectory(path.length() == 0 ? NetworkFile.separator : path.toString());
    }

    /** An empty file, without overwriting one that's already there */
    @Override
    public boolean createFile(String path) throws IOException {
        if (null != client.mlistFile(path) || client.listFiles(path).length > 0) {
            return false;
        }
        return client.storeFile(path, new ByteArrayInputStream(new byte[0]));
    }

    /** A folder goes with everything in it, as it does over WebDAV and SFTP */
    @Override
    public boolean deleteFile(String path) throws IOException {
        if (client.deleteFile(path)) {
            return true;
        }
        if (!client.changeWorkingDirectory(path)) {
            return false;
        }
        for (FTPFile file : client.listFiles()) {
            final String name = file.getName();
            if (!".".equals(name) && !"..".equals(name)
                    && !deleteFile(NetworkFile.join(path, name))) {
                return false;
            }
        }
        client.changeToParentDirectory();
        return client.removeDirectory(path);
    }

    @Override
    public boolean supportsEditing() {
        return true;
    }

    @Override
    public FTPFile[] listFiles() throws IOException {
        return client.listFiles();
    }

    @Override
    public boolean completePendingCommand() throws IOException {
        return client.completePendingCommand();
    }
}
