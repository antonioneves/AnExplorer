package dev.dworks.apps.anexplorer.misc;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.dworks.apps.anexplorer.BuildConfig;
import dev.dworks.apps.anexplorer.setting.AppSettings;

/**
 * Finds subtitles for a video by its TMDB id, so the match the app already makes picks the right
 * ones. SubDL is asked first, and OpenSubtitles only when SubDL has nothing or isn't set up.
 * Everything blocks on the network, so call it off the main thread.
 */
public final class SubtitleFinder {

    private static final String SUBDL_API = "https://api.subdl.com/api/v1/subtitles";
    private static final String SUBDL_DOWNLOAD = "https://dl.subdl.com";
    private static final String OPENSUBTITLES_API = "https://api.opensubtitles.com/api/v1";
    private static final int TIMEOUT = 10000;
    private static final int MAX_DOWNLOAD = 8 * 1024 * 1024;
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private static final String FOLDER = "subtitles";
    private static final String[] EXTENSIONS = {".srt", ".vtt", ".ass", ".ssa"};

    private SubtitleFinder() {
    }

    /** One subtitle a service offers */
    public static final class Subtitle {
        final boolean subdl;
        /** SubDL's download path, or OpenSubtitles' file id */
        final String ref;
        public final String release;
        public final String language;
        public final boolean hearingImpaired;

        Subtitle(boolean subdl, String ref, String release, String language, boolean hearingImpaired) {
            this.subdl = subdl;
            this.ref = ref;
            this.release = release;
            this.language = language;
            this.hearingImpaired = hearingImpaired;
        }
    }

    /** Whether any service has a key, so the player knows to offer the search at all */
    public static boolean isConfigured(Context context) {
        return TmdbPosters.isEnabled() && (!TextUtils.isEmpty(subdlKey(context))
                || !TextUtils.isEmpty(openSubtitlesKey(context)));
    }

    /**
     * @return the subtitles for the file, SubDL's when it has any, otherwise OpenSubtitles'.
     * Empty when none were found or no service could be reached.
     */
    public static List<Subtitle> search(Context context, String fileName) throws IOException {
        final int id = TmdbDetails.findId(context, fileName);
        if (id == 0) {
            return new ArrayList<>();
        }
        final EpisodeName episode = EpisodeName.parse(fileName);
        final String language = AppSettings.getSubtitleLanguage(context);
        IOException failure = null;
        List<Subtitle> found = new ArrayList<>();
        if (!TextUtils.isEmpty(subdlKey(context))) {
            try {
                found = searchSubdl(context, id, episode, language);
            } catch (Exception e) {
                failure = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        if (found.isEmpty() && !TextUtils.isEmpty(openSubtitlesKey(context))) {
            try {
                found = searchOpenSubtitles(context, id, episode, language);
            } catch (Exception e) {
                failure = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        if (found.isEmpty() && failure != null) {
            throw failure;
        }
        return found;
    }

    /**
     * Downloads the subtitle into the app's own folder, named after the video's watch key so it
     * is found again next time.
     * @return the subtitle file, always SubRip or WebVTT or SSA text
     */
    public static File download(Context context, Subtitle subtitle, String watchKey, String fileName)
            throws IOException {
        final byte[] data;
        String name;
        if (subtitle.subdl) {
            final byte[] zip = get(SUBDL_DOWNLOAD + subtitle.ref, null, MAX_DOWNLOAD);
            final Unzipped inner = unzip(zip, EpisodeName.parse(fileName));
            data = inner.data;
            name = inner.name;
        } else {
            data = openSubtitlesFile(context, subtitle.ref);
            name = ".srt";
        }
        // Only one subtitle is kept per video, whatever its format
        for (String other : EXTENSIONS) {
            file(context, watchKey, other).delete();
        }
        final File file = file(context, watchKey, extension(name));
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return file;
    }

    /** @return the subtitle kept for this video, or null */
    public static File saved(Context context, String watchKey) {
        for (String extension : EXTENSIONS) {
            final File file = file(context, watchKey, extension);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private static File file(Context context, String watchKey, String extension) {
        final File folder = new File(context.getFilesDir(), FOLDER);
        folder.mkdirs();
        return new File(folder, name(watchKey) + extension);
    }

    private static String name(String watchKey) {
        return Integer.toHexString(watchKey.hashCode()) + "-" + (watchKey.length() & 0xff);
    }

    private static String extension(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return extension;
            }
        }
        return ".srt";
    }

    private static String subdlKey(Context context) {
        return AppSettings.getCloudKey(context, AppSettings.KEY_SUBDL_API_KEY);
    }

    private static String openSubtitlesKey(Context context) {
        return AppSettings.getCloudKey(context, AppSettings.KEY_OPENSUBTITLES_API_KEY);
    }

    private static List<Subtitle> searchSubdl(Context context, int id, EpisodeName episode, String language)
            throws Exception {
        final StringBuilder url = new StringBuilder(SUBDL_API)
                .append("?api_key=").append(enc(subdlKey(context)))
                .append("&tmdb_id=").append(id)
                .append("&type=").append(episode != null ? "tv" : "movie")
                .append("&languages=").append(enc(language.toUpperCase(Locale.ROOT)))
                .append("&subs_per_page=30");
        if (episode != null) {
            url.append("&season_number=").append(episode.season)
                    .append("&episode_number=").append(episode.episode);
        }
        final JSONObject answer = new JSONObject(new String(get(url.toString(), null, MAX_RESPONSE),
                StandardCharsets.UTF_8));
        final List<Subtitle> list = new ArrayList<>();
        final JSONArray subtitles = answer.optJSONArray("subtitles");
        for (int i = 0; subtitles != null && i < subtitles.length(); i++) {
            final JSONObject item = subtitles.getJSONObject(i);
            final String path = item.optString("url", "");
            if (path.isEmpty()) {
                continue;
            }
            String release = item.optString("release_name", "");
            if (release.isEmpty()) {
                release = item.optString("name", "");
            }
            list.add(new Subtitle(true, path, release, item.optString("language",
                    item.optString("lang", language)), item.optBoolean("hi")));
        }
        return list;
    }

    private static List<Subtitle> searchOpenSubtitles(Context context, int id, EpisodeName episode,
            String language) throws Exception {
        final StringBuilder url = new StringBuilder(OPENSUBTITLES_API).append("/subtitles?")
                .append(episode != null ? "parent_tmdb_id=" : "tmdb_id=").append(id)
                .append("&languages=").append(enc(language.toLowerCase(Locale.ROOT)))
                .append("&order_by=download_count");
        if (episode != null) {
            url.append("&season_number=").append(episode.season)
                    .append("&episode_number=").append(episode.episode);
        }
        final JSONObject answer = new JSONObject(new String(
                get(url.toString(), openSubtitlesHeaders(context, null), MAX_RESPONSE), StandardCharsets.UTF_8));
        final List<Subtitle> list = new ArrayList<>();
        final JSONArray data = answer.optJSONArray("data");
        for (int i = 0; data != null && i < data.length(); i++) {
            final JSONObject attributes = data.getJSONObject(i).optJSONObject("attributes");
            final JSONArray files = attributes == null ? null : attributes.optJSONArray("files");
            if (files == null || files.length() == 0) {
                continue;
            }
            list.add(new Subtitle(false, String.valueOf(files.getJSONObject(0).optLong("file_id")),
                    attributes.optString("release", ""), attributes.optString("language", language),
                    attributes.optBoolean("hearing_impaired")));
        }
        return list;
    }

    private static byte[] openSubtitlesFile(Context context, String fileId) throws IOException {
        try {
            final String user = AppSettings.getCloudKey(context, AppSettings.KEY_OPENSUBTITLES_USER);
            final String password = AppSettings.getCloudKey(context, AppSettings.KEY_OPENSUBTITLES_PASSWORD);
            if (TextUtils.isEmpty(user) || TextUtils.isEmpty(password)) {
                throw new IOException("OpenSubtitles needs a user name and password to download");
            }
            final JSONObject login = new JSONObject(new String(post(OPENSUBTITLES_API + "/login",
                    openSubtitlesHeaders(context, null),
                    new JSONObject().put("username", user).put("password", password).toString()),
                    StandardCharsets.UTF_8));
            final String token = login.optString("token", "");
            if (token.isEmpty()) {
                throw new IOException("OpenSubtitles refused the sign-in");
            }
            final JSONObject link = new JSONObject(new String(post(OPENSUBTITLES_API + "/download",
                    openSubtitlesHeaders(context, token),
                    new JSONObject().put("file_id", Long.parseLong(fileId)).put("sub_format", "srt").toString()),
                    StandardCharsets.UTF_8));
            final String address = link.optString("link", "");
            if (address.isEmpty()) {
                throw new IOException("OpenSubtitles gave no download link");
            }
            return get(address, null, MAX_DOWNLOAD);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    private static String[] openSubtitlesHeaders(Context context, String token) {
        final List<String> headers = new ArrayList<>();
        headers.add("Api-Key");
        headers.add(openSubtitlesKey(context));
        headers.add("User-Agent");
        headers.add("AnExplorer v" + BuildConfig.VERSION_NAME);
        if (token != null) {
            headers.add("Authorization");
            headers.add("Bearer " + token);
        }
        return headers.toArray(new String[0]);
    }

    private static final class Unzipped {
        final String name;
        final byte[] data;

        Unzipped(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    /** SubDL serves a zip; a season pack holds many episodes, so prefer the one that matches */
    private static Unzipped unzip(byte[] zip, EpisodeName episode) throws IOException {
        Unzipped first = null;
        final String tag = episode == null ? null
                : String.format(Locale.ROOT, "s%02de%02d", episode.season, episode.episode);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                final String name = entry.getName();
                if (entry.isDirectory() || !isSubtitle(name)) {
                    continue;
                }
                final Unzipped item = new Unzipped(name, readAll(in, MAX_DOWNLOAD));
                if (first == null) {
                    first = item;
                }
                if (tag != null && name.toLowerCase(Locale.ROOT).replace(" ", "").contains(tag)) {
                    return item;
                }
            }
        }
        if (first == null) {
            throw new IOException("The download held no subtitle");
        }
        return first;
    }

    private static boolean isSubtitle(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static byte[] get(String address, String[] headers, int max) throws IOException {
        return request("GET", address, headers, null, max);
    }

    private static byte[] post(String address, String[] headers, String json) throws IOException {
        return request("POST", address, headers, json, MAX_RESPONSE);
    }

    private static byte[] request(String method, String address, String[] headers, String json, int max)
            throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        for (int i = 0; headers != null && i + 1 < headers.length; i += 2) {
            conn.setRequestProperty(headers[i], headers[i + 1]);
        }
        try {
            if (json != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            final int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Subtitle service answered " + code);
            }
            return readAll(conn.getInputStream(), max);
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > max) {
                throw new IOException("Subtitle answer too large");
            }
        }
        return out.toByteArray();
    }
}
