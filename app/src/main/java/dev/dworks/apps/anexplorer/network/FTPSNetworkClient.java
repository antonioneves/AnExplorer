package dev.dworks.apps.anexplorer.network;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.IOException;
import java.net.Socket;

/**
 * Created by HaKr on 02/01/17.
 */

public class FTPSNetworkClient extends FTPNetworkClient {

    /** The port servers use for implicit FTPS, which starts with TLS instead of asking for it */
    public static final int IMPLICIT_PORT = 990;

    private static final int DRAIN_TIMEOUT = 200;

    public FTPSNetworkClient(final String host, final int port){
        super(host, port);
    }

    public FTPSNetworkClient(final String host, final int port, final String userName, final String password){
        super(host, port, userName, password);
    }

    @Override
    protected FTPClient newClient() {
        return new Client(port == IMPLICIT_PORT);
    }

    /** Encrypts the transfers too, which many servers insist on */
    @Override
    protected void afterLogin(FTPClient client) throws IOException {
        ((FTPSClient) client).execPBSZ(0);
        ((FTPSClient) client).execPROT("P");
    }

    /**
     * Over TLS 1.3 the server sends session tickets on the data connection, which an upload
     * never reads; a socket closed with data unread is reset, and the server then drops the end
     * of the file. Reading them first lets the connection close as it should.
     */
    @Override
    protected void beforeUploadClose(FTPClient client) {
        final Socket socket = ((Client) client).dataSocket;
        if (null == socket) {
            return;
        }
        try {
            socket.setSoTimeout(DRAIN_TIMEOUT);
            socket.getInputStream().read();
        } catch (IOException e) {
            // Timed out, with the tickets read
        }
    }

    /** Remembers its data connection, so an upload can end it cleanly */
    private static class Client extends FTPSClient {
        Socket dataSocket;

        Client(boolean implicit) {
            super(implicit);
        }

        @Override
        protected Socket _openDataConnection_(String command, String arg) throws IOException {
            dataSocket = super._openDataConnection_(command, arg);
            return dataSocket;
        }
    }
}
