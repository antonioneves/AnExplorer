package dev.dworks.apps.anexplorer.misc;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import dev.dworks.apps.anexplorer.BuildConfig;

/**
 * The small part of the TMDB API the app needs: one GET that adds the key (v3 or v4) and reads
 * one JSON object. A 404 is an empty answer, not an error, so an unknown episode isn't retried
 * on every visit.
 */
final class TmdbApi {

    private static final String API = "https://api.themoviedb.org/3";
    private static final int TIMEOUT = 8000;
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;

    private TmdbApi() {
    }

    static JSONObject get(String path, Map<String, String> params) throws IOException, JSONException {
        final String key = BuildConfig.TMDB_API_KEY;
        if (TextUtils.isEmpty(key)) {
            return null;
        }

        final StringBuilder url = new StringBuilder(API).append(path);
        char separator = '?';
        if (params != null) {
            for (Map.Entry<String, String> param : params.entrySet()) {
                url.append(separator)
                        .append(URLEncoder.encode(param.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(param.getValue(), "UTF-8"));
                separator = '&';
            }
        }

        final boolean bearer = key.startsWith("eyJ");
        if (!bearer) {
            url.append(separator).append("api_key=").append(URLEncoder.encode(key, "UTF-8"));
        }

        final HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        if (bearer) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        try {
            final int code = conn.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                throw new IOException("TMDB answered " + code);
            }
            final byte[] body = read(conn.getInputStream(), MAX_RESPONSE);
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        try (InputStream stream = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > max) {
                    throw new IOException("Response too large");
                }
            }
            return out.toByteArray();
        }
    }
}
