package dev.dworks.apps.anexplorer.network;

import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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

    /**
     * Streams a file from a byte offset, so a player can seek in it without the rest being read first.
     * Servers that can start at an offset override this; the others read and drop the bytes before it.
     */
    public InputStream openInputStream(String path, long offset) throws IOException {
        final InputStream stream = openInputStream(path);
        if (null == stream) {
            return null;
        }
        skipFully(stream, offset);
        return stream;
    }

    /** Drops bytes from the start of a stream; `skip` alone may stop short */
    public static void skipFully(InputStream stream, long count) throws IOException {
        long left = count;
        while (left > 0) {
            final long skipped = stream.skip(left);
            if (skipped > 0) {
                left -= skipped;
            } else if (stream.read() < 0) {
                break;
            } else {
                left--;
            }
        }
    }

    /** Writes a file, which is in place once the stream is closed */
    public OutputStream openOutputStream(String path) throws IOException {
        throw new IOException("Can't write files to this server");
    }

    /** An empty file, without overwriting one that's already there */
    public boolean createFile(String path) throws IOException {
        return false;
    }

    /** Whether documents can be created, written and deleted */
    public boolean supportsEditing() {
        return false;
    }

    /** Whether files and folders can be made in this folder */
    public boolean canCreateIn(String folder) {
        return supportsEditing();
    }

    /** Whether the document at this path can be deleted */
    public boolean canDelete(String path) {
        return supportsEditing();
    }

    public static NetworkClient create(String scheme, String host, int port, String userName, String password) {
        return create(scheme, host, port, userName, password, null);
    }

    /** @param path the folder of the server a WebDAV or SMB connection opens at, or a media server's description */
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
        } else if(SmbNetworkClient.SCHEME.equals(scheme)){
            return new SmbNetworkClient(host, port, path, userName, password);
        } else if(DlnaNetworkClient.SCHEME.equals(scheme)){
            return new DlnaNetworkClient(host, port, path);
        }
        return null;
    }

}
