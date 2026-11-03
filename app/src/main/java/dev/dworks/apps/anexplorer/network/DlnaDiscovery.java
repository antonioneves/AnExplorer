package dev.dworks.apps.anexplorer.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import dev.dworks.apps.anexplorer.misc.LogUtils;

/** Finds the media servers on the network by asking for them over SSDP, which UPnP uses */
public class DlnaDiscovery {

    private static final String TAG = DlnaDiscovery.class.getSimpleName();

    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    private static final String TARGET = "urn:schemas-upnp-org:device:MediaServer:1";
    /** Servers answer within a second or two of being asked */
    private static final int LISTEN_MS = 3000;

    /** A media server and where its description is */
    public static class Server {
        public final String name;
        public final String host;
        public final int port;
        public final String path;

        Server(String name, String host, int port, String path) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.path = path;
        }
    }

    /** Blocks for a few seconds; call it off the main thread */
    public static List<Server> find() {
        final Map<String, HttpUrl> locations = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            final String search = "M-SEARCH * HTTP/1.1\r\nHOST: " + GROUP + ":" + PORT + "\r\n"
                    + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + TARGET + "\r\n\r\n";
            final byte[] data = search.getBytes(StandardCharsets.US_ASCII);
            final InetAddress group = InetAddress.getByName(GROUP);
            // UDP drops packets now and then, so ask twice
            socket.send(new DatagramPacket(data, data.length, group, PORT));
            socket.send(new DatagramPacket(data, data.length, group, PORT));
            final long end = System.currentTimeMillis() + LISTEN_MS;
            final byte[] buffer = new byte[2048];
            while (true) {
                final long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                socket.setSoTimeout((int) left);
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                }
                final String location = header(new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8), "location");
                final HttpUrl url = null == location ? null : HttpUrl.parse(location);
                if (null != url) {
                    locations.put(location, url);
                }
            }
        } catch (IOException e) {
            LogUtils.LOGD(TAG, "Media server search failed: " + e);
        }
        return describe(locations.values());
    }

    private static List<Server> describe(Iterable<HttpUrl> locations) {
        final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build();
        final List<Server> servers = new ArrayList<>();
        for (HttpUrl location : locations) {
            try (Response response = client.newCall(new Request.Builder().url(location).get().build()).execute()) {
                if (!response.isSuccessful()) {
                    continue;
                }
                final DlnaNetworkClient.Description description =
                        DlnaNetworkClient.parseDescription(response.body().string(), location);
                if (description.hasContentDirectory()) {
                    final String query = null == location.encodedQuery() ? "" : "?" + location.encodedQuery();
                    servers.add(new Server(null != description.name ? description.name : location.host(),
                            location.host(), location.port(), location.encodedPath() + query));
                }
            } catch (Exception e) {
                LogUtils.LOGD(TAG, "Not a usable media server at " + location + ": " + e);
            }
        }
        return servers;
    }

    /** The value of a header in an SSDP answer, whatever case the server writes its name in */
    private static String header(String message, String name) {
        for (String line : message.split("\r\n")) {
            final int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
