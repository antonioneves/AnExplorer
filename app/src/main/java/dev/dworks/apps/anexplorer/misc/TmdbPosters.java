package dev.dworks.apps.anexplorer.misc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.dworks.apps.anexplorer.BuildConfig;

/**
 * Looks up the poster of a movie or show on TMDB from its file name, so videos show a cover
 * instead of a frame. Posters are kept in the cache folder, misses too, so each title is asked
 * for once. With no key in the build, or no network, it returns nothing and the frame is used.
 */
public final class TmdbPosters {

    private static final String API = "https://api.themoviedb.org/3/search/";
    private static final String IMAGES = "https://image.tmdb.org/t/p/w342";
    private static final int TIMEOUT = 8000;
    private static final int MAX_POSTER = 2 * 1024 * 1024;
    private static final String FOLDER = "tmdb";
    private static final String MISS = ".miss";

    private static final Pattern YEAR = Pattern.compile("[ ._\\-\\[(]+((?:19|20)\\d{2})(?![0-9])");
    private static final Pattern TAGS = Pattern.compile(
            "[ ._\\-\\[(]+(?:2160p|1080[pi]|720p|480p|4k|uhd|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|"
                    + "hdtv|dvdrip|hdrip|x26[45]|h\\.?26[45]|hevc|xvid|aac|ac3|dts|remux|proper|repack|"
                    + "extended|unrated|multi|dual)(?![0-9a-z]).*$",
            Pattern.CASE_INSENSITIVE);

    private TmdbPosters() {
    }

    public static boolean isEnabled() {
        return !TextUtils.isEmpty(BuildConfig.TMDB_API_KEY);
    }

    /** What the cache knows about a video: asked and found, asked and not found, or not asked yet */
    public static final int UNKNOWN = 0;
    public static final int RECOGNIZED = 1;
    public static final int UNRECOGNIZED = 2;

    /** The title, year and cache key that a file name stands for */
    private static final class Query {
        boolean show;
        String title;
        String year;
        String key;
    }

    private static Query parse(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }
        final String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        final EpisodeName episode = EpisodeName.parse(base);
        final Query q = new Query();
        q.show = episode != null;
        String title;
        if (q.show) {
            title = episode.show;
        } else {
            title = base;
            final Matcher y = YEAR.matcher(title);
            if (y.find() && y.start() > 0) {
                q.year = y.group(1);
                title = title.substring(0, y.start());
            }
        }
        title = TAGS.matcher(title).replaceFirst("").replace('.', ' ').replace('_', ' ').trim();
        if (title.length() < 2) {
            return null;
        }
        q.title = title;
        q.key = (q.show ? "tv-" : "movie-") + title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                + (q.year == null ? "" : "-" + q.year);
        return q;
    }

    /** Reads the cache only, so it is safe to call while listing */
    public static int status(Context context, String fileName) {
        final Query q = isEnabled() ? parse(fileName) : null;
        if (q == null) {
            return UNRECOGNIZED;
        }
        final File folder = new File(context.getCacheDir(), FOLDER);
        if (new File(folder, q.key + ".jpg").isFile()) {
            return RECOGNIZED;
        }
        return new File(folder, q.key + MISS).isFile() ? UNRECOGNIZED : UNKNOWN;
    }

    /** Asks TMDB if the cache doesn't know yet. Blocks on the network, so call it off the main thread. */
    public static int resolve(Context context, String fileName) {
        load(context, fileName);
        return status(context, fileName);
    }

    /** @return the poster for the video's file name, or null when there is none or it can't be fetched */
    public static Bitmap load(Context context, String fileName) {
        final Query q = isEnabled() ? parse(fileName) : null;
        if (q == null) {
            return null;
        }
        final File folder = new File(context.getCacheDir(), FOLDER);
        final File file = new File(folder, q.key + ".jpg");
        final File miss = new File(folder, q.key + MISS);
        if (file.isFile()) {
            return BitmapFactory.decodeFile(file.getPath());
        }
        if (miss.isFile() || !(folder.isDirectory() || folder.mkdirs())) {
            return null;
        }
        try {
            final String path = findPosterPath(q.show, q.title, q.year);
            final byte[] bytes = path == null ? null : download(IMAGES + path);
            if (bytes == null) {
                miss.createNewFile();
                return null;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            // A network error is not remembered, the next listing tries again
            return null;
        }
    }

    private static String findPosterPath(boolean show, String title, String year) throws Exception {
        final StringBuilder url = new StringBuilder(API).append(show ? "tv" : "movie")
                .append("?query=").append(URLEncoder.encode(title, "UTF-8"));
        if (year != null) {
            url.append("&year=").append(year);
        }
        final String key = BuildConfig.TMDB_API_KEY;
        final boolean bearer = key.startsWith("eyJ");
        if (!bearer) {
            url.append("&api_key=").append(URLEncoder.encode(key, "UTF-8"));
        }
        final HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        if (bearer) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("TMDB search answered " + conn.getResponseCode());
            }
            final byte[] body = read(conn.getInputStream(), 1024 * 1024);
            final JSONArray results = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                final String poster = results.getJSONObject(i).optString("poster_path", "");
                if (poster.startsWith("/")) {
                    return poster;
                }
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] download(String url) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("TMDB image answered " + conn.getResponseCode());
            }
            return read(conn.getInputStream(), MAX_POSTER);
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
