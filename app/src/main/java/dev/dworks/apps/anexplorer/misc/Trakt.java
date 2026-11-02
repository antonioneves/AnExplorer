package dev.dworks.apps.anexplorer.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import dev.dworks.apps.anexplorer.setting.AppSettings;

/**
 * Keeps the watched state in step with a Trakt account: what finishes playing here is sent to
 * Trakt, and what Trakt says was watched is read back, so a video seen on another device shows as
 * played here too. Videos are matched by title (and year or season and episode), as Trakt has no
 * way to look up a file. Tokens stay in a private prefs file and are never logged.
 */
public final class Trakt {

    private static final String TAG = "Trakt";
    private static final String API = "https://api.trakt.tv";
    private static final int TIMEOUT = 10000;
    private static final int MAX_RESPONSE = 32 * 1024 * 1024;

    private static final String PREFS = "trakt";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String EXPIRES_AT = "expires_at";
    private static final String USER = "user";
    private static final String WATCHED = "watched";
    private static final String PENDING = "pending";
    private static final String SYNCED_AT = "synced_at";

    /** Reading the whole watched list is heavy, so it isn't repeated every time the app opens */
    private static final long SYNC_INTERVAL_MS = 60 * 60 * 1000L;
    private static final long REFRESH_MARGIN_S = 24 * 60 * 60L;

    public static final int POLL_DONE = 0;
    public static final int POLL_PENDING = 1;
    public static final int POLL_SLOW_DOWN = 2;

    private static final Object LOCK = new Object();
    private static volatile Set<String> sWatched;

    private Trakt() {
    }

    public static boolean isConfigured(Context context) {
        return !TextUtils.isEmpty(clientId(context)) && !TextUtils.isEmpty(clientSecret(context));
    }

    public static boolean isSignedIn(Context context) {
        return !TextUtils.isEmpty(prefs(context).getString(REFRESH_TOKEN, null));
    }

    /** @return the account name, or null when it isn't known */
    public static String getUser(Context context) {
        return prefs(context).getString(USER, null);
    }

    public static void signOut(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().clear().apply();
            sWatched = null;
        }
    }

    // Sign in with a code entered on another device

    /** @return device_code, user_code, verification_url, interval and expires_in */
    public static JSONObject requestDeviceCode(Context context) throws IOException, JSONException {
        final JSONObject body = new JSONObject().put("client_id", clientId(context));
        final Response response = post("/oauth/device/code", body, null, context);
        if (response.code != 200) {
            throw new IOException("Trakt answered " + response.code);
        }
        return new JSONObject(response.body);
    }

    /** @return {@link #POLL_DONE} once the code was entered and the account is saved */
    public static int pollDeviceCode(Context context, String deviceCode) throws IOException, JSONException {
        final JSONObject body = new JSONObject()
                .put("code", deviceCode)
                .put("client_id", clientId(context))
                .put("client_secret", clientSecret(context));
        final Response response = post("/oauth/device/token", body, null, context);
        switch (response.code) {
            case 200:
                saveToken(context, new JSONObject(response.body));
                loadUser(context);
                return POLL_DONE;
            case 400:
                return POLL_PENDING;
            case 429:
                return POLL_SLOW_DOWN;
            case 410:
                throw new IOException("The code expired");
            case 418:
                throw new IOException("Sign in was denied");
            default:
                throw new IOException("Trakt answered " + response.code);
        }
    }

    private static void saveToken(Context context, JSONObject token) throws JSONException {
        final long created = token.optLong("created_at", System.currentTimeMillis() / 1000);
        prefs(context).edit()
                .putString(ACCESS_TOKEN, token.getString("access_token"))
                .putString(REFRESH_TOKEN, token.getString("refresh_token"))
                .putLong(EXPIRES_AT, created + token.optLong("expires_in", 7776000))
                .apply();
    }

    private static void loadUser(Context context) {
        try {
            final Response response = request("GET", "/users/settings", null, context);
            if (response.code == 200) {
                final String name = new JSONObject(response.body).getJSONObject("user").optString("username");
                prefs(context).edit().putString(USER, name).apply();
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Couldn't read the account name", e);
        }
    }

    // Watched state

    /** True when Trakt knows the video as watched; reads memory only, so it is safe while listing */
    public static boolean isWatched(Context context, String fileName) {
        if (fileName == null || !isSignedIn(context)) {
            return false;
        }
        final String key = watchedKey(fileName);
        return key != null && watched(context).contains(key);
    }

    /** Queues a video that finished playing and sends it as soon as the network allows */
    public static void markWatched(final Context context, String fileName) {
        if (fileName == null || !isSignedIn(context) || watchedKey(fileName) == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            final SharedPreferences prefs = prefs(app);
            final Set<String> pending = new HashSet<>(prefs.getStringSet(PENDING, new HashSet<String>()));
            pending.add(System.currentTimeMillis() + "|" + fileName);
            prefs.edit().putStringSet(PENDING, pending).apply();
            final Set<String> known = new HashSet<>(watched(app));
            known.add(watchedKey(fileName));
            sWatched = known;
        }
        sync(app, true);
    }

    /**
     * Sends what is queued and reads the watched lists, on a background thread.
     * @param force skip the wait since the last sync
     */
    public static void sync(final Context context, final boolean force) {
        final Context app = context.getApplicationContext();
        if (!isConfigured(app) || !isSignedIn(app)) {
            return;
        }
        if (!force && System.currentTimeMillis() - prefs(app).getLong(SYNCED_AT, 0) < SYNC_INTERVAL_MS) {
            return;
        }
        new Thread(() -> {
            synchronized (LOCK) {
                try {
                    flushPending(app);
                    pullWatched(app);
                    prefs(app).edit().putLong(SYNCED_AT, System.currentTimeMillis()).apply();
                } catch (IOException | JSONException e) {
                    // Whatever is still queued goes out on the next sync
                    Log.w(TAG, "Sync failed: " + e.getMessage());
                }
            }
        }, "TraktSync").start();
    }

    private static void flushPending(Context context) throws IOException, JSONException {
        final SharedPreferences prefs = prefs(context);
        final Set<String> pending = prefs.getStringSet(PENDING, new HashSet<String>());
        if (pending.isEmpty()) {
            return;
        }
        final JSONArray movies = new JSONArray();
        final JSONArray shows = new JSONArray();
        for (String entry : pending) {
            final int bar = entry.indexOf('|');
            final String name = entry.substring(bar + 1);
            final String watchedAt = iso(Long.parseLong(entry.substring(0, bar)));
            final EpisodeName episode = EpisodeName.parse(name);
            if (episode != null) {
                shows.put(new JSONObject()
                        .put("title", episode.show)
                        .put("seasons", new JSONArray().put(new JSONObject()
                                .put("number", episode.season)
                                .put("episodes", new JSONArray().put(new JSONObject()
                                        .put("number", episode.episode)
                                        .put("watched_at", watchedAt))))));
            } else {
                final JSONObject movie = new JSONObject()
                        .put("title", TmdbPosters.title(name))
                        .put("watched_at", watchedAt);
                final String year = TmdbPosters.year(name);
                if (year != null) {
                    movie.put("year", Integer.parseInt(year));
                }
                movies.put(movie);
            }
        }
        final JSONObject body = new JSONObject();
        if (movies.length() > 0) {
            body.put("movies", movies);
        }
        if (shows.length() > 0) {
            body.put("shows", shows);
        }
        final Response response = request("POST", "/sync/history", body, context);
        if (response.code != 201 && response.code != 200) {
            throw new IOException("Trakt answered " + response.code);
        }
        // A title Trakt doesn't know would fail again and again, so it is dropped too
        prefs.edit().remove(PENDING).apply();
    }

    private static void pullWatched(Context context) throws IOException, JSONException {
        final Set<String> keys = new HashSet<>();

        final Response movies = request("GET", "/sync/watched/movies", null, context);
        if (movies.code != 200) {
            throw new IOException("Trakt answered " + movies.code);
        }
        final JSONArray movieList = new JSONArray(movies.body);
        for (int i = 0; i < movieList.length(); i++) {
            final JSONObject movie = movieList.getJSONObject(i).optJSONObject("movie");
            if (movie == null) {
                continue;
            }
            final String title = normalize(movie.optString("title"));
            keys.add("m:" + title);
            if (movie.optInt("year") > 0) {
                keys.add("m:" + title + ":" + movie.optInt("year"));
            }
        }

        final Response full = request("GET", "/sync/watched/shows", null, context);
        if (full.code != 200) {
            throw new IOException("Trakt answered " + full.code);
        }
        final JSONArray showList = new JSONArray(full.body);
        for (int i = 0; i < showList.length(); i++) {
            final JSONObject item = showList.getJSONObject(i);
            final JSONObject show = item.optJSONObject("show");
            final JSONArray seasons = item.optJSONArray("seasons");
            if (show == null || seasons == null) {
                continue;
            }
            final String title = normalize(show.optString("title"));
            for (int s = 0; s < seasons.length(); s++) {
                final JSONObject season = seasons.getJSONObject(s);
                final JSONArray episodes = season.optJSONArray("episodes");
                for (int e = 0; episodes != null && e < episodes.length(); e++) {
                    keys.add("e:" + title + ":" + season.optInt("number") + ":"
                            + episodes.getJSONObject(e).optInt("number"));
                }
            }
        }

        // What is still queued counts as watched until it has been sent
        for (String entry : prefs(context).getStringSet(PENDING, new HashSet<String>())) {
            final String key = watchedKey(entry.substring(entry.indexOf('|') + 1));
            if (key != null) {
                keys.add(key);
            }
        }
        prefs(context).edit().putStringSet(WATCHED, keys).apply();
        sWatched = keys;
    }

    private static Set<String> watched(Context context) {
        Set<String> keys = sWatched;
        if (keys == null) {
            keys = new HashSet<>(prefs(context).getStringSet(WATCHED, new HashSet<String>()));
            sWatched = keys;
        }
        return keys;
    }

    /** How a video is looked up: show, season and episode for an episode, else title and year */
    static String watchedKey(String fileName) {
        final EpisodeName episode = EpisodeName.parse(fileName);
        if (episode != null) {
            return "e:" + normalize(episode.show) + ":" + episode.season + ":" + episode.episode;
        }
        final String title = normalize(TmdbPosters.title(fileName));
        if (title.isEmpty()) {
            return null;
        }
        final String year = TmdbPosters.year(fileName);
        return "m:" + title + (year == null ? "" : ":" + year);
    }

    private static String normalize(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String iso(long millis) {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    // HTTP

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** A call with the account's token, renewed once if Trakt turns it down */
    private static Response request(String method, String path, JSONObject body, Context context)
            throws IOException, JSONException {
        final long now = System.currentTimeMillis() / 1000;
        if (prefs(context).getLong(EXPIRES_AT, 0) - now < REFRESH_MARGIN_S) {
            refreshToken(context);
        }
        Response response = send(method, path, body, prefs(context).getString(ACCESS_TOKEN, null), context);
        if (response.code == 401 && refreshToken(context)) {
            response = send(method, path, body, prefs(context).getString(ACCESS_TOKEN, null), context);
        }
        return response;
    }

    private static boolean refreshToken(Context context) throws IOException, JSONException {
        final String refresh = prefs(context).getString(REFRESH_TOKEN, null);
        if (refresh == null) {
            return false;
        }
        final JSONObject body = new JSONObject()
                .put("refresh_token", refresh)
                .put("client_id", clientId(context))
                .put("client_secret", clientSecret(context))
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                .put("grant_type", "refresh_token");
        final Response response = post("/oauth/token", body, null, context);
        if (response.code == 200) {
            saveToken(context, new JSONObject(response.body));
            return true;
        }
        if (response.code == 400 || response.code == 401) {
            // The account was revoked on Trakt's side, so stop trying with it
            signOut(context);
        }
        return false;
    }

    private static Response post(String path, JSONObject body, String token, Context context) throws IOException {
        return send("POST", path, body, token, context);
    }

    private static Response send(String method, String path, JSONObject body, String token, Context context)
            throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(API + path).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("trakt-api-version", "2");
        conn.setRequestProperty("trakt-api-key", clientId(context));
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        try {
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            final int code = conn.getResponseCode();
            final InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(code, stream == null ? "" : read(stream));
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_RESPONSE) {
                    throw new IOException("Response too large");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String clientId(Context context) {
        return AppSettings.getCloudKey(context, AppSettings.KEY_TRAKT_CLIENT_ID);
    }

    private static String clientSecret(Context context) {
        return AppSettings.getCloudKey(context, AppSettings.KEY_TRAKT_CLIENT_SECRET);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
