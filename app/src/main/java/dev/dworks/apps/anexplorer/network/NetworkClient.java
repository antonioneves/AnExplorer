package dev.dworks.apps.anexplorer.network;

import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by HaKr on 02/01/17.
 */

public abstract class NetworkClient{

    public abstract boolean logout() throws IOException;

    public abstract boolean isConnected();

    public abstract boolean isAvailable();

    public abstract void disconnect() throws IOException;

    public abstract boolean login(String username, String password) throws IOException;

    public abstract boolean login(String username, String password, String account) throws IOException;

    public abstract boolean connectClient() throws IOException ;

    public abstract String getWorkingDirectory() throws IOException;

    public abstract void changeWorkingDirectory(String pathname) throws IOException;

    public abstract InputStream getInputStream(String fileName, String directory);

    public abstract boolean createDirectories(final String directory) throws IOException;

    public abstract boolean deleteFile(final String path) throws IOException;

    public abstract FTPFile[] listFiles() throws IOException ;

    public abstract boolean completePendingCommand() throws IOException ;

    /** Streams a file, or returns null where the provider opens it by URL instead (FTP) */
    public InputStream openInputStream(String path) throws IOException {
        return null;
    }

    /** FTP connections have only ever made a folder for a new document */
    public boolean createFile(String path) throws IOException {
        return createDirectories(path);
    }

    /** Whether documents can be created and deleted, which FTP connections never could */
    public boolean supportsEditing() {
        return false;
    }

    public static NetworkClient create(String scheme, String host, int port, String userName, String password) {
        return create(scheme, host, port, userName, password, null);
    }

    /** @param path the folder of the server a WebDAV connection opens at */
    public static NetworkClient create(String scheme, String host, int port, String userName,
                                       String password, String path) {
        if(WebDavNetworkClient.isWebDav(scheme)){
            return new WebDavNetworkClient(WebDavNetworkClient.SCHEME_SECURE.equals(scheme),
                    host, port, path, userName, password);
        } else if(scheme.compareTo("ftp") == 0){
            return new FTPNetworkClient(host, port, userName, password);
        } else if(scheme.compareTo("ftps") == 0){
            return new FTPSNetworkClient(host, port, userName, password);
        } else if(SftpNetworkClient.SCHEME.equals(scheme)){
            return new SftpNetworkClient(host, port, userName, password);
        }
        return null;
    }

}
