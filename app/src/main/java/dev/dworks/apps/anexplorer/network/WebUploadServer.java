package dev.dworks.apps.anexplorer.network;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.LogUtils;

/**
 * A small web server beside the FTP server, so a phone can send files to the TV from its browser.
 * It asks for the FTP server's login and saves into the FTP server's folder.
 */
public class WebUploadServer {

    private static final String TAG = WebUploadServer.class.getSimpleName();

    private static final int FIRST_PORT = 8080;
    private static final int LAST_PORT = 8099;
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 100;
    private static final int TIMEOUT_MS = 30000;

    private static final Pattern FILENAME = Pattern.compile("filename=\"([^\"]*)\"");
    private static final Pattern BOUNDARY = Pattern.compile("boundary=\"?([^\";]+)\"?");

    private final Context context;
    private final File folder;
    private final String user;
    private final String password;
    private final boolean anonymous;
    private ServerSocket socket;
    private ExecutorService pool;

    public WebUploadServer(Context context, File folder, String user, String password, boolean anonymous) {
        this.context = context.getApplicationContext();
        this.folder = folder;
        this.user = user;
        this.password = password;
        this.anonymous = anonymous;
    }

    /** Starts listening on the first free port from 8080 and returns it */
    public int start() throws IOException {
        folder.mkdirs();
        IOException failure = new IOException("No free port for the web page");
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try {
                socket = new ServerSocket(port);
                break;
            } catch (IOException e) {
                failure = e;
            }
        }
        if (null == socket) {
            throw failure;
        }
        pool = Executors.newCachedThreadPool();
        final ServerSocket listening = socket;
        final Thread acceptor = new Thread(() -> {
            while (!listening.isClosed()) {
                try {
                    final Socket client = listening.accept();
                    pool.execute(() -> handle(client));
                } catch (IOException e) {
                    break;
                }
            }
        }, "WebUploadServer");
        acceptor.setDaemon(true);
        acceptor.start();
        return socket.getLocalPort();
    }

    public void stop() {
        try {
            if (null != socket) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        if (null != pool) {
            pool.shutdownNow();
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(TIMEOUT_MS);
            final BufferedInputStream in = new BufferedInputStream(c.getInputStream(), 65536);
            final OutputStream out = c.getOutputStream();
            final String request = readLine(in);
            if (TextUtils.isEmpty(request)) {
                return;
            }
            final String[] parts = request.split(" ");
            final Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < MAX_HEADERS; i++) {
                final String line = readLine(in);
                if (TextUtils.isEmpty(line)) {
                    break;
                }
                final int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }
            if (parts.length < 2) {
                respond(out, 400, "text/plain", "Bad request", null);
            } else if (!authorized(headers.get("authorization"))) {
                respond(out, 401, "text/plain", "Login needed",
                        "WWW-Authenticate: Basic realm=\"AnExplorer\"\r\n");
            } else {
                final String path = parts[1].split("\\?")[0];
                if ("GET".equals(parts[0]) && ("/".equals(path) || "/index.html".equals(path))) {
                    respond(out, 200, "text/html", page(), null);
                } else if ("POST".equals(parts[0]) && "/upload".equals(path)) {
                    upload(in, out, headers);
                } else {
                    respond(out, 404, "text/plain", "Not found", null);
                }
            }
        } catch (IOException | RuntimeException e) {
            LogUtils.LOGD(TAG, "Web request failed: " + e);
        }
    }

    private boolean authorized(String header) {
        if (anonymous) {
            return true;
        }
        if (null == header || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            final byte[] given = Base64.decode(header.substring(6).trim(), Base64.DEFAULT);
            return MessageDigest.isEqual(given, (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void upload(BufferedInputStream in, OutputStream out, Map<String, String> headers)
            throws IOException {
        final Matcher boundary = BOUNDARY.matcher(headers.getOrDefault("content-type", ""));
        long length = -1;
        try {
            length = Long.parseLong(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException ignored) {
        }
        if (!boundary.find() || length < 0) {
            respond(out, 400, "text/plain", "Bad upload", null);
            return;
        }
        if ("100-continue".equalsIgnoreCase(headers.get("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        final Multipart reader = new Multipart(in, boundary.group(1), length);
        int saved = 0;
        String head;
        while (null != (head = reader.nextPart())) {
            final Matcher name = FILENAME.matcher(head);
            final String clean = name.find() ? cleanName(name.group(1)) : null;
            if (null == clean) {
                reader.copyPart(null);
                continue;
            }
            final File target = reserve(clean);
            boolean whole = false;
            try (OutputStream file = new FileOutputStream(target)) {
                whole = reader.copyPart(file);
            } finally {
                if (!whole) {
                    target.delete();
                }
            }
            if (!whole) {
                respond(out, 400, "text/plain", "Upload cut short", null);
                return;
            }
            saved++;
        }
        respond(out, saved > 0 ? 200 : 400, "text/plain", saved > 0 ? "OK" : "No file", null);
    }

    /** The file's own name: no folders, no hidden dot name, nothing a path could use to escape */
    private static String cleanName(String raw) {
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? null : name;
    }

    /** Creates the empty file under a name that's free, "name (1).ext" and so on, so nothing is overwritten */
    private File reserve(String name) throws IOException {
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final String extension = dot > 0 ? name.substring(dot) : "";
        File target = new File(folder, name);
        for (int i = 1; !target.createNewFile(); i++) {
            if (i > 1000) {
                throw new IOException("No free name for " + name);
            }
            target = new File(folder, base + " (" + i + ")" + extension);
        }
        return target;
    }

    private String page() throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = context.getResources().openRawResource(R.raw.web_upload)) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bytes.write(buffer, 0, read);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                .replace("{{title}}", TextUtils.htmlEncode(context.getString(R.string.web_upload_title)))
                .replace("{{choose}}", TextUtils.htmlEncode(context.getString(R.string.web_upload_choose)))
                .replace("{{drop}}", TextUtils.htmlEncode(context.getString(R.string.web_upload_drop)))
                .replace("{{sending}}", TextUtils.htmlEncode(context.getString(R.string.web_upload_sending)))
                .replace("{{done}}", TextUtils.htmlEncode(context.getString(R.string.web_upload_done)))
                .replace("{{failed}}", TextUtils.htmlEncode(context.getString(R.string.web_upload_failed)));
    }

    private static void respond(OutputStream out, int status, String type, String body, String extra)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        final String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + (null == extra ? "" : extra) + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > MAX_LINE) {
                throw new IOException("Header line too long");
            }
        }
        return b < 0 && line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Reads a multipart/form-data body part by part, without holding a file in memory */
    private static class Multipart {
        private final InputStream in;
        private final byte[] delimiter;
        private final byte[] buffer = new byte[65536];
        private int pos;
        private int limit;
        private long remaining;

        Multipart(InputStream in, String boundary, long length) {
            this.in = in;
            this.remaining = length;
            // The first boundary has no line break before it, so one is added to treat all alike
            this.delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.UTF_8);
            buffer[0] = '\r';
            buffer[1] = '\n';
            limit = 2;
        }

        private boolean fill() throws IOException {
            if (pos > 0) {
                System.arraycopy(buffer, pos, buffer, 0, limit - pos);
                limit -= pos;
                pos = 0;
            }
            if (remaining <= 0 || limit >= buffer.length) {
                return false;
            }
            final int read = in.read(buffer, limit, (int) Math.min(buffer.length - limit, remaining));
            if (read <= 0) {
                return false;
            }
            limit += read;
            remaining -= read;
            return true;
        }

        private int indexOf(byte[] pattern) {
            outer:
            for (int i = pos; i <= limit - pattern.length; i++) {
                for (int j = 0; j < pattern.length; j++) {
                    if (buffer[i + j] != pattern[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        /** The next part's headers, or null after the last one */
        String nextPart() throws IOException {
            int at;
            while ((at = indexOf(delimiter)) < 0) {
                pos = Math.max(pos, limit - delimiter.length + 1);
                if (!fill()) {
                    return null;
                }
            }
            pos = at + delimiter.length;
            while (limit - pos < 2) {
                if (!fill()) {
                    return null;
                }
            }
            if (buffer[pos] == '-' && buffer[pos + 1] == '-') {
                return null;
            }
            pos += 2;
            final byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            while ((at = indexOf(end)) < 0) {
                if (limit - pos > MAX_LINE || !fill()) {
                    return null;
                }
            }
            final String head = new String(buffer, pos, at - pos, StandardCharsets.UTF_8);
            pos = at + end.length;
            return head;
        }

        /** Writes the part's content up to the next boundary; false if the body ended before it */
        boolean copyPart(OutputStream out) throws IOException {
            while (true) {
                final int at = indexOf(delimiter);
                if (at >= 0) {
                    if (null != out) {
                        out.write(buffer, pos, at - pos);
                    }
                    pos = at;
                    return true;
                }
                final int safe = limit - pos - (delimiter.length - 1);
                if (safe > 0) {
                    if (null != out) {
                        out.write(buffer, pos, safe);
                    }
                    pos += safe;
                }
                if (!fill()) {
                    return false;
                }
            }
        }
    }
}
