package dev.dworks.apps.anexplorer.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.dworks.apps.anexplorer.misc.LogUtils;

/** Finds the SMB, FTP, SFTP and WebDAV servers on the network, which announce themselves over mDNS */
public class ServerDiscovery {

    private static final String TAG = ServerDiscovery.class.getSimpleName();

    /** How long servers are listened for, and then how long the last ones get to be looked up */
    private static final long LISTEN_MS = 4000;
    private static final long RESOLVE_MS = 3000;

    private static final String[][] TYPES = {
            {"_smb._tcp.", SmbNetworkClient.SCHEME},
            {"_ftp._tcp.", "ftp"},
            {"_sftp-ssh._tcp.", SftpNetworkClient.SCHEME},
            {"_webdav._tcp.", WebDavNetworkClient.SCHEME},
            {"_webdavs._tcp.", WebDavNetworkClient.SCHEME_SECURE},
    };

    /** A server and the kind it announced itself as */
    public static class Found {
        public final String name;
        public final String scheme;
        public final String host;
        public final int port;

        Found(String name, String scheme, String host, int port) {
            this.name = name;
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }
    }

    public interface Listener {
        /** On the main thread */
        void onFound(List<Found> servers);
    }

    private final NsdManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private final Deque<Object[]> pending = new ArrayDeque<>();
    private final List<Found> found = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private boolean resolving;
    private boolean finished;

    public static void find(Context context, Listener listener) {
        new ServerDiscovery(context).start(listener);
    }

    private ServerDiscovery(Context context) {
        manager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    private void start(Listener listener) {
        if (null == manager) {
            listener.onFound(found);
            return;
        }
        for (String[] type : TYPES) {
            final String scheme = type[1];
            final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    LogUtils.LOGD(TAG, "Search for " + serviceType + " failed: " + errorCode);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo service) {
                    synchronized (ServerDiscovery.this) {
                        pending.add(new Object[]{service, scheme});
                    }
                    resolveNext();
                }

                @Override
                public void onServiceLost(NsdServiceInfo service) {
                }
            };
            try {
                manager.discoverServices(type[0], NsdManager.PROTOCOL_DNS_SD, discovery);
                listeners.add(discovery);
            } catch (RuntimeException e) {
                LogUtils.LOGD(TAG, "Can't search for " + type[0] + ": " + e);
            }
        }
        handler.postDelayed(() -> {
            for (NsdManager.DiscoveryListener discovery : listeners) {
                try {
                    manager.stopServiceDiscovery(discovery);
                } catch (RuntimeException ignored) {
                }
            }
            handler.postDelayed(() -> {
                final List<Found> result;
                synchronized (this) {
                    finished = true;
                    result = new ArrayList<>(found);
                }
                listener.onFound(result);
            }, RESOLVE_MS);
        }, LISTEN_MS);
    }

    /** The platform looks up one service at a time, so the found ones wait in line */
    private void resolveNext() {
        final Object[] next;
        synchronized (this) {
            if (resolving || finished || pending.isEmpty()) {
                return;
            }
            resolving = true;
            next = pending.poll();
        }
        final String scheme = (String) next[1];
        try {
            manager.resolveService((NsdServiceInfo) next[0], new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo service, int errorCode) {
                    done();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo service) {
                    if (service.getHost() instanceof Inet4Address) {
                        final String host = service.getHost().getHostAddress();
                        synchronized (ServerDiscovery.this) {
                            if (!finished && seen.add(scheme + host + service.getPort())) {
                                found.add(new Found(service.getServiceName(), scheme, host, service.getPort()));
                            }
                        }
                    }
                    done();
                }

                private void done() {
                    synchronized (ServerDiscovery.this) {
                        resolving = false;
                    }
                    resolveNext();
                }
            });
        } catch (RuntimeException e) {
            synchronized (this) {
                resolving = false;
            }
        }
    }
}
