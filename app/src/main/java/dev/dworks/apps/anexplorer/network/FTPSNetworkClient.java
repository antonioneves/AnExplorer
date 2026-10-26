package dev.dworks.apps.anexplorer.network;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.LogUtils;

/**
 * Created by HaKr on 02/01/17.
 */

public class FTPSNetworkClient extends NetworkClient {

    private static final String TAG = FTPSNetworkClient.class.getSimpleName();

    FTPSClient client;
    public String host;
    public int port;
    public String username;
    public String password;

    /** The port servers use for implicit FTPS, which starts with TLS instead of asking for it */
    public static final int IMPLICIT_PORT = 990;

    public FTPSNetworkClient(final String host, final int port){
        client = new FTPSClient(port == IMPLICIT_PORT);
        this.host = host;
        this.port = port;
        this.username = "anonymous";
        this.password = "";
    }

    public FTPSNetworkClient(final String host, final int port, final String userName, final String password){
        client = new FTPSClient(port == IMPLICIT_PORT);
        this.host = host;
        this.port = port;
        this.username = userName;
        this.password = password;
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

    /** Logs in and encrypts the transfers too, which many servers insist on */
    private boolean login(FTPSClient client) throws IOException {
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
        client.execPBSZ(0);
        client.execPROT("P");
        client.setFileType(FTP.BINARY_FILE_TYPE);
        client.enterLocalPassiveMode();
        return true;
    }

    /**
     * On a connection of its own, so that browsing goes on while the file is read; Java
     * can't open ftps:// addresses the way it opens ftp:// ones
     */
    @Override
    public InputStream openInputStream(String path) throws IOException {
        final FTPSClient reader = new FTPSClient(port == IMPLICIT_PORT);
        if (!login(reader)) {
            throw new IOException("FTPS server refused the login");
        }
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

    @Override
    public String getWorkingDirectory() throws IOException {
        return client.printWorkingDirectory();
    }

    @Override
    public void changeWorkingDirectory(String pathname) throws IOException {
        client.changeWorkingDirectory(pathname);
    }

    /**
     * Return input stream for given file name
     *
     * @param fileName  name of the remote file
     * @param directory remote directory
     * @return input stream or null
     */
    public InputStream getInputStream(final String fileName, final String directory) {

        try {
            connectClient();
            changeWorkingDirectory(directory);
            return client.retrieveFileStream(fileName);

        } catch (IOException e) {
            LogUtils.LOGE(TAG, "Error retrieving file from FTP server: " + host, e);
            CrashReportingManager.logException(e);
        }
        return null;
    }

    /**
     * Creates directories on FTP server
     *
     * @param directory directory to create
     * @return SimpleFtpClientResult.CREATED on succes SimpleFtpClientResult.ERROR on error
     * @throws IOException on error
     */
    public boolean createDirectories(final String directory) throws IOException {
        String[] iterable = directory.split("/");
        for (String dir : iterable) {
            boolean dirExists = client.changeWorkingDirectory(dir);
            if (!dirExists) {
                // try to create directory:
                client.makeDirectory(dir);
                dirExists = client.changeWorkingDirectory(dir);
            }
            if (!dirExists) {
                LogUtils.LOGD(TAG, "failed to change FTP directory (forms), not doing anything");
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean deleteFile(String path) throws IOException {
        return client.deleteFile(path);
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
