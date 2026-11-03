package dev.dworks.apps.anexplorer.service;

import android.os.Looper;

import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.TransferRatePermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dev.dworks.apps.anexplorer.misc.ConnectionUtils;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.network.NetworkServiceHandler;
import dev.dworks.apps.anexplorer.network.WebUploadServer;


public class ConnectionsService extends NetworkServerService {

    private FtpServer ftpServer;
    private WebUploadServer webServer;

    @Override
    protected NetworkServiceHandler createServiceHandler(Looper serviceLooper, NetworkServerService service) {
        return new NetworkServiceHandler(serviceLooper, service);
    }

    @Override
    public Object getServer() {
        return ftpServer;
    }

    @Override
    public boolean launchServer() {
        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(ConnectionUtils.getAvailablePortForFTP());

        FtpServerFactory serverFactory = new FtpServerFactory();
        serverFactory.addListener("default", listenerFactory.createListener());

        ConnectionConfigFactory connectionConfigFactory = new ConnectionConfigFactory();
        connectionConfigFactory.setAnonymousLoginEnabled(getNetworkConnection().isAnonymousLogin());
        connectionConfigFactory.setMaxLoginFailures(5);
        connectionConfigFactory.setLoginFailureDelay(2000);
        serverFactory.setConnectionConfig(connectionConfigFactory.createConnectionConfig());

        BaseUser user = new BaseUser();
        user.setName(getNetworkConnection().getUserName());
        user.setPassword(getNetworkConnection().getPassword());
        user.setHomeDirectory(getNetworkConnection().getPath());

        List<Authority> list = new ArrayList<>();
        list.add(new WritePermission());
        list.add(new TransferRatePermission(0, 0));
        list.add(new ConcurrentLoginPermission(10, 10));
        user.setAuthorities(list);

        try {
            serverFactory.getUserManager().save(user);
        } catch (FtpException e) {
            CrashReportingManager.logException(e);
        }

        // do start server
        try{
            ftpServer = serverFactory.createServer();
            ftpServer.start();
            startWebServer();
            return true;
        }catch(Exception e){
            ftpServer = null;
            handleServerStartError(e);
        }
        return false;
    }

    /** The web page is a bonus, so the FTP server runs even when it can't start */
    private void startWebServer() {
        try {
            webServer = new WebUploadServer(getApplicationContext(),
                    new File(getNetworkConnection().getPath()), getNetworkConnection().getUserName(),
                    getNetworkConnection().getPassword(), getNetworkConnection().isAnonymousLogin());
            ConnectionUtils.webPort = webServer.start();
        } catch (Exception e) {
            webServer = null;
            ConnectionUtils.webPort = 0;
            CrashReportingManager.logException(e);
        }
    }

    @Override
    public void stopServer() {
        if (null != webServer) {
            webServer.stop();
            webServer = null;
        }
        ConnectionUtils.webPort = 0;
        ftpServer.stop();
        ftpServer = null;
    }
}
