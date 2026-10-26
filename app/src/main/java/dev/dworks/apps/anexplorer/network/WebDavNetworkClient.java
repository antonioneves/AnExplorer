package dev.dworks.apps.anexplorer.network;

import android.text.TextUtils;
import android.util.Xml;

import org.apache.commons.net.ftp.FTPFile;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.LogUtils;
import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

/**
 * A WebDAV share, over http ("webdav") or https ("webdavs"). Paths are relative to the folder
 * of the server the connection was made for, so "/" is that folder.
 */
public class WebDavNetworkClient extends NetworkClient {

    private static final String TAG = WebDavNetworkClient.class.getSimpleName();

    public static final String SCHEME = "webdav";
    public static final String SCHEME_SECURE = "webdavs";

    private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
    private static final String PROPFIND = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
            + "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>"
            + "</d:prop></d:propfind>";
    private static final int MULTI_STATUS = 207;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int UPLOAD_BUFFER = 64 * 1024;

    private final OkHttpClient client;
    private final HttpUrl baseUrl;
    private String workingDirectory = NetworkFile.separator;
    private boolean connected;

    public WebDavNetworkClient(boolean secure, String host, int port, String basePath,
                               final String username, final String password) {
        final HttpUrl.Builder url = new HttpUrl.Builder()
                .scheme(secure ? "https" : "http")
                .host(host)
                .port(port);
        final String segments = trimSlashes(basePath);
        if (!segments.isEmpty()) {
            url.addPathSegments(segments);
        }
        // A trailing slash, so that paths resolve inside the folder
        baseUrl = url.addPathSegment("").build();

        final OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS);
        if (!TextUtils.isEmpty(username)) {
            final String credentials = Credentials.basic(username,
                    password == null ? "" : password, StandardCharsets.UTF_8);
            builder.addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                    .header("Authorization", credentials).build()));
        }
        client = builder.build();
    }

    public static boolean isWebDav(String scheme) {
        return SCHEME.equals(scheme) || SCHEME_SECURE.equals(scheme);
    }

    public static int defaultPort(String scheme) {
        return SCHEME_SECURE.equals(scheme) ? 443 : 80;
    }

    @Override
    public boolean logout() {
        connected = false;
        return true;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isAvailable() {
        return connected;
    }

    @Override
    public void disconnect() {
        connected = false;
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
    public boolean connectClient() throws IOException {
        try (Response response = propfind(baseUrl, "0")) {
            connected = response.code() == MULTI_STATUS;
            if (!connected) {
                LogUtils.LOGD(TAG, "WebDAV server refused the connection: " + response.code());
            }
        }
        return connected;
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
            LogUtils.LOGE(TAG, "Error retrieving file from WebDAV server: " + baseUrl, e);
            CrashReportingManager.logException(e);
        }
        return null;
    }

    @Override
    public InputStream openInputStream(String path) throws IOException {
        final Response response = client.newCall(new Request.Builder()
                .url(urlFor(path, false)).get().build()).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("GET " + path + " failed: " + response.code());
        }
        return response.body().byteStream();
    }

    /** Sent while it's written; the file is in place once the stream is closed */
    @Override
    public OutputStream openOutputStream(String path) throws IOException {
        final PipedInputStream body = new PipedInputStream(UPLOAD_BUFFER);
        final PipedOutputStream stream = new PipedOutputStream(body);
        final Call call = client.newCall(new Request.Builder().url(urlFor(path, false))
                .put(new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return null;
                    }

                    @Override
                    public boolean isOneShot() {
                        return true;
                    }

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {
                        try (Source source = Okio.source(body)) {
                            sink.writeAll(source);
                        }
                    }
                }).build());
        final FutureTask<Integer> upload = new FutureTask<>(() -> {
            try (Response response = call.execute()) {
                return response.code();
            }
        });
        new Thread(upload, "WebDAV upload").start();
        return new FilterOutputStream(stream) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                super.close();
                final int code;
                try {
                    code = upload.get();
                } catch (ExecutionException e) {
                    throw new IOException("PUT " + path + " failed", e.getCause());
                } catch (InterruptedException e) {
                    call.cancel();
                    throw new InterruptedIOException();
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("PUT " + path + " failed: " + code);
                }
            }
        };
    }

    @Override
    public boolean createDirectories(String directory) throws IOException {
        // Each missing folder on the way, since MKCOL makes only the last one
        final StringBuilder path = new StringBuilder();
        int code = METHOD_NOT_ALLOWED;
        for (String segment : trimSlashes(directory).split(NetworkFile.separator)) {
            path.append(NetworkFile.separatorChar).append(segment);
            try (Response response = execute("MKCOL", urlFor(path.toString(), true), null)) {
                code = response.code();
            }
            // 405 is a folder that's already there
            if (code != 201 && code != METHOD_NOT_ALLOWED) {
                return false;
            }
        }
        return code == 201;
    }

    @Override
    public boolean createFile(String path) throws IOException {
        try (Response response = execute("PUT", urlFor(path, false),
                RequestBody.create(new byte[0], null))) {
            return response.isSuccessful();
        }
    }

    @Override
    public boolean deleteFile(String path) throws IOException {
        try (Response response = execute("DELETE", urlFor(path, false), null)) {
            return response.isSuccessful();
        }
    }

    @Override
    public boolean supportsEditing() {
        return true;
    }

    @Override
    public FTPFile[] listFiles() throws IOException {
        final HttpUrl url = urlFor(workingDirectory, true);
        try (Response response = propfind(url, "1")) {
            if (response.code() != MULTI_STATUS) {
                throw new IOException("PROPFIND " + workingDirectory + " failed: " + response.code());
            }
            return parse(url, response.body());
        } catch (XmlPullParserException e) {
            throw new IOException("Unreadable listing of " + workingDirectory, e);
        }
    }

    @Override
    public boolean completePendingCommand() {
        return true;
    }

    private Response propfind(HttpUrl url, String depth) throws IOException {
        return client.newCall(new Request.Builder()
                .url(url)
                .header("Depth", depth)
                .method("PROPFIND", RequestBody.create(PROPFIND, XML))
                .build()).execute();
    }

    private Response execute(String method, HttpUrl url, RequestBody body) throws IOException {
        return client.newCall(new Request.Builder().url(url).method(method, body).build()).execute();
    }

    private HttpUrl urlFor(String path, boolean directory) {
        final HttpUrl.Builder url = baseUrl.newBuilder();
        // The base ends in an empty segment, which the path takes the place of
        url.removePathSegment(baseUrl.pathSize() - 1);
        final String segments = trimSlashes(path);
        if (!segments.isEmpty()) {
            for (String segment : segments.split(NetworkFile.separator)) {
                url.addPathSegment(segment);
            }
        }
        if (directory || segments.isEmpty()) {
            url.addPathSegment("");
        }
        return url.build();
    }

    /** The entries of a multistatus listing, without the folder that was listed */
    private static FTPFile[] parse(HttpUrl folder, ResponseBody body)
            throws XmlPullParserException, IOException {
        final XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(body.byteStream(), null);

        final List<FTPFile> files = new ArrayList<>();
        final List<String> folderSegments = segments(folder);
        String href = null;
        boolean collection = false;
        long size = 0;
        long modified = 0;
        String text = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            final String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                text = null;
                if ("response".equals(name)) {
                    href = null;
                    collection = false;
                    size = 0;
                    modified = 0;
                } else if ("collection".equals(name)) {
                    collection = true;
                }
            } else if (event == XmlPullParser.TEXT) {
                text = parser.getText();
            } else if (event == XmlPullParser.END_TAG) {
                if ("href".equals(name)) {
                    href = text == null ? null : text.trim();
                } else if ("getcontentlength".equals(name) && text != null) {
                    try {
                        size = Long.parseLong(text.trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("getlastmodified".equals(name) && text != null) {
                    modified = parseDate(text.trim());
                } else if ("response".equals(name) && href != null) {
                    final HttpUrl url = folder.resolve(href);
                    final List<String> segments = url == null ? null : segments(url);
                    if (segments != null && !segments.isEmpty() && !segments.equals(folderSegments)) {
                        files.add(file(segments.get(segments.size() - 1), collection, size, modified));
                    }
                }
                text = null;
            }
        }
        return files.toArray(new FTPFile[0]);
    }

    private static FTPFile file(String name, boolean collection, long size, long modified) {
        final FTPFile file = new FTPFile();
        file.setName(name);
        file.setType(collection ? FTPFile.DIRECTORY_TYPE : FTPFile.FILE_TYPE);
        file.setSize(collection ? 0 : size);
        final Calendar timestamp = Calendar.getInstance();
        timestamp.setTimeInMillis(modified);
        file.setTimestamp(timestamp);
        return file;
    }

    /** The decoded path segments of a URL, leaving out empty ones */
    private static List<String> segments(HttpUrl url) {
        final List<String> segments = new ArrayList<>();
        for (String segment : url.pathSegments()) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static long parseDate(String date) {
        final SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            return format.parse(date).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    private static String trimSlashes(String path) {
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
}
