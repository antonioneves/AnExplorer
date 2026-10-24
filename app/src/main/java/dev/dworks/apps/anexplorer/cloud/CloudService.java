package dev.dworks.apps.anexplorer.cloud;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Talks to one signed-in cloud drive over its REST API. Each service keeps its own OAuth tokens,
 * renews them when they run out and tells its {@link TokenListener} so they can be saved.
 */
public abstract class CloudService {

    /** Where Dropbox and Box send the browser back to once the user has signed in */
    public static final String REDIRECT_URI = "http://localhost";

    private static final int TIMEOUT = 30 * 1000;

    public interface TokenListener {
        void onTokensChanged(CloudService service);
    }

    /** An error answer from a service; {@link #error} holds its OAuth error code, if any */
    public static class CloudException extends IOException {
        public final int code;
        public final String error;

        public CloudException(int code, String error, String message) {
            super(message);
            this.code = code;
            this.error = error;
        }
    }

    protected final Context context;
    protected String accessToken;
    protected String refreshToken;
    protected long expiresAt;
    public String userName;
    public String userLogin;
    private TokenListener mTokenListener;

    protected CloudService(Context context) {
        this.context = context.getApplicationContext();
    }

    public abstract String getType();

    /** The id of the drive's top folder */
    public abstract String getRootId();

    public abstract List<CloudFile> list(String folderId) throws IOException;

    public abstract CloudFile metadata(String id) throws IOException;

    public abstract InputStream download(CloudFile file) throws IOException;

    public InputStream thumbnail(CloudFile file) throws IOException {
        return null;
    }

    public abstract void createFolder(String parentId, String name) throws IOException;

    public abstract void upload(String parentId, String name, InputStream in, long size,
                                String mimeType) throws IOException;

    public abstract void delete(CloudFile file) throws IOException;

    /** Fills in {@link #userName} and {@link #userLogin} */
    public abstract void fetchAccount() throws IOException;

    protected abstract void refreshAccessToken() throws IOException;

    public void setTokenListener(TokenListener listener) {
        mTokenListener = listener;
    }

    public String saveTokens() {
        final JSONObject json = new JSONObject();
        try {
            json.put("access_token", accessToken);
            json.put("refresh_token", refreshToken);
            json.put("expires_at", expiresAt);
        } catch (JSONException ignored) {
        }
        return json.toString();
    }

    public boolean loadTokens(String saved) {
        if (TextUtils.isEmpty(saved)) {
            return false;
        }
        try {
            final JSONObject json = new JSONObject(saved);
            accessToken = json.optString("access_token", null);
            refreshToken = json.optString("refresh_token", null);
            expiresAt = json.optLong("expires_at");
            return accessToken != null || refreshToken != null;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Takes the tokens from a token endpoint's answer, keeping the refresh token if none came */
    public void setTokens(JSONObject response) {
        accessToken = response.optString("access_token", null);
        final String refresh = response.optString("refresh_token", null);
        if (!TextUtils.isEmpty(refresh)) {
            refreshToken = refresh;
        }
        final long expiresIn = response.optLong("expires_in");
        expiresAt = expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000 : 0;
    }

    protected void setAccessToken(String token, long expiresAt) {
        this.accessToken = token;
        this.expiresAt = expiresAt;
    }

    protected synchronized String token() throws IOException {
        if (accessToken == null
                || (expiresAt > 0 && System.currentTimeMillis() > expiresAt - 60 * 1000)) {
            refreshAccessToken();
            if (mTokenListener != null) {
                mTokenListener.onTokensChanged(this);
            }
        }
        return accessToken;
    }

    protected boolean canRefresh() {
        return refreshToken != null;
    }

    private synchronized void invalidateToken(String used) {
        if (TextUtils.equals(used, accessToken)) {
            accessToken = null;
        }
    }

    protected HttpURLConnection open(String method, String url) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    /** Opens a signed-in connection; the caller sends its body and calls {@link #check} */
    protected HttpURLConnection openAuthorized(String method, String url) throws IOException {
        final HttpURLConnection connection = open(method, url);
        connection.setRequestProperty("Authorization", "Bearer " + token());
        return connection;
    }

    /**
     * Sends a signed-in request and returns the connection once it answered without an error.
     * A request turned away for a stale token is retried once with a new one.
     */
    protected HttpURLConnection request(String method, String url, Map<String, String> headers,
                                        String contentType, byte[] body) throws IOException {
        for (int attempt = 0; ; attempt++) {
            final String token = token();
            final HttpURLConnection connection = open(method, url);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            if (body != null) {
                connection.setDoOutput(true);
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            final int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED && attempt == 0 && canRefresh()) {
                connection.disconnect();
                invalidateToken(token);
                continue;
            }
            check(connection);
            return connection;
        }
    }

    protected JSONObject requestJson(String method, String url, JSONObject body) throws IOException {
        final HttpURLConnection connection = request(method, url, null,
                body != null ? "application/json" : null,
                body != null ? body.toString().getBytes(StandardCharsets.UTF_8) : null);
        return readJson(connection);
    }

    protected JSONObject getJson(String url) throws IOException {
        return requestJson("GET", url, null);
    }

    /** Throws the service's error message if the request failed */
    protected static void check(HttpURLConnection connection) throws IOException {
        final int code = connection.getResponseCode();
        if (code < 400) {
            return;
        }
        String body = "";
        final InputStream error = connection.getErrorStream();
        if (error != null) {
            body = readString(error);
        }
        connection.disconnect();
        String errorCode = null;
        String message = null;
        try {
            final JSONObject json = new JSONObject(body);
            final Object err = json.opt("error");
            if (err instanceof JSONObject) {
                // Graph and Drive put the details in an object
                errorCode = ((JSONObject) err).optString("code", null);
                message = ((JSONObject) err).optString("message", null);
            } else if (err instanceof String) {
                errorCode = (String) err;
            }
            if (message == null) {
                message = json.optString("error_description",
                        json.optString("error_summary", json.optString("message", null)));
            }
        } catch (JSONException ignored) {
        }
        if (TextUtils.isEmpty(message)) {
            message = TextUtils.isEmpty(body) ? "HTTP " + code : body;
        }
        // Microsoft adds trace and correlation ids that mean nothing to the user
        message = message.replaceFirst("(?s)\\s*Trace ID:.*$", "");
        throw new CloudException(code, errorCode, message);
    }

    /** Opens what a download link points to, following redirects to its file host unsigned */
    protected InputStream openStream(HttpURLConnection connection) throws IOException {
        for (int hops = 0; hops < 5; hops++) {
            final int code = connection.getResponseCode();
            if (code < 300 || code >= 400) {
                break;
            }
            final String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("Redirect without a location");
            }
            connection = open("GET", new URL(connection.getURL(), location).toString());
        }
        check(connection);
        return connection.getInputStream();
    }

    /** Posts a form to a token endpoint, which needs no sign-in */
    public static JSONObject postForm(String url, Map<String, String> params) throws IOException {
        final StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (form.length() > 0) {
                form.append('&');
            }
            form.append(encode(param.getKey())).append('=').append(encode(param.getValue()));
        }
        final byte[] body = form.toString().getBytes(StandardCharsets.UTF_8);
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("Accept", "application/json");
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        check(connection);
        return readJson(connection);
    }

    protected static JSONObject readJson(HttpURLConnection connection) throws IOException {
        final String body = readString(connection.getInputStream());
        connection.disconnect();
        if (TextUtils.isEmpty(body)) {
            return new JSONObject();
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Unexpected answer: " + body);
        }
    }

    protected static String readString(InputStream in) throws IOException {
        try (InputStream input = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    protected static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        final byte[] buffer = new byte[64 * 1024];
        long remaining = limit;
        while (remaining != 0) {
            final int read = in.read(buffer, 0,
                    remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                break;
            }
            out.write(buffer, 0, read);
            if (remaining > 0) {
                remaining -= read;
            }
        }
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            return value;
        }
    }

    /** Reads an ISO 8601 time such as 2024-05-12T15:50:38.123Z or 2012-12-12T10:53:43-08:00 */
    protected static long parseTime(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 19) {
            return 0;
        }
        try {
            final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            long time = format.parse(value.substring(0, 19)).getTime();
            int zone = 19;
            if (zone < value.length() && value.charAt(zone) == '.') {
                zone++;
                while (zone < value.length() && Character.isDigit(value.charAt(zone))) {
                    zone++;
                }
            }
            if (zone + 6 <= value.length()
                    && (value.charAt(zone) == '+' || value.charAt(zone) == '-')) {
                final int sign = value.charAt(zone) == '+' ? 1 : -1;
                final int hours = Integer.parseInt(value.substring(zone + 1, zone + 3));
                final int minutes = Integer.parseInt(value.substring(zone + 4, zone + 6));
                time -= sign * (hours * 60L + minutes) * 60 * 1000;
            }
            return time;
        } catch (ParseException | NumberFormatException e) {
            return 0;
        }
    }
}
