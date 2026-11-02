package dev.dworks.apps.anexplorer.misc;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.provider.MediaDocumentsProvider;

/**
 * Reads the TMDB details of a movie, show, season or episode and keeps them in the same cache
 * folder as the posters. The media provider's document id says which of the four a document is,
 * so a show folder isn't mistaken for a movie. A request that fails on the network isn't
 * remembered, but a title TMDB doesn't know is, so it isn't asked for again.
 */
public final class TmdbDetails {

    public static final int MOVIE = 1;
    public static final int SHOW = 2;
    public static final int SEASON = 3;
    public static final int EPISODE = 4;

    private static final String FOLDER = "tmdb";
    private static final String JSON = ".json";
    private static final String MISS = ".info-miss";
    private static final int VERSION = 1;

    private TmdbDetails() {
    }

    /** The details the Info panel shows; empty fields are left out of the panel */
    public static final class Info {
        public int kind;
        public int id;
        public String title;
        public String originalTitle;
        public String date;
        public String overview;
        public String tagline;
        public String genres;
        public String status;
        public String network;
        public String creator;
        public String director;
        public String writers;
        public String cast;
        public String guestStars;
        public String certification;
        public String episodeTitle;
        public int runtime;
        public int seasons;
        public int episodes;
        public int season;
        public int episode;
        public double rating;
        public int votes;
    }

    /** Whether this document is a movie, show, season or episode the panel could describe */
    public static boolean canLoad(DocumentInfo doc) {
        if (!TmdbPosters.isEnabled() || doc == null || TextUtils.isEmpty(doc.documentId)
                || TextUtils.isEmpty(doc.displayName)
                || !MediaDocumentsProvider.AUTHORITY.equals(doc.authority)) {
            return false;
        }
        final String id = doc.documentId;
        return id.startsWith(MediaDocumentsProvider.TYPE_VIDEO + ":")
                || id.startsWith(MediaDocumentsProvider.TYPE_VIDEOS_SHOW + ":")
                || id.startsWith(MediaDocumentsProvider.TYPE_VIDEOS_SEASON + ":");
    }

    /** @return the details for a media provider document, or null when there are none */
    public static Info load(Context context, DocumentInfo doc) {
        if (!canLoad(doc)) {
            return null;
        }
        try {
            final String id = doc.documentId;
            if (id.startsWith(MediaDocumentsProvider.TYPE_VIDEOS_SHOW + ":")) {
                return loadShow(context, doc.displayName);
            }
            if (id.startsWith(MediaDocumentsProvider.TYPE_VIDEOS_SEASON + ":")) {
                final long value = Long.parseLong(id.substring(id.indexOf(':') + 1));
                final String show = findShowName(context, value / 1000);
                return TextUtils.isEmpty(show) ? null : loadSeason(context, show, (int) (value % 1000));
            }
            if (id.startsWith(MediaDocumentsProvider.TYPE_VIDEO + ":")) {
                final EpisodeName episode = EpisodeName.parse(doc.displayName);
                if (episode != null) {
                    return loadEpisode(context, episode.show, episode.season, episode.episode);
                }
                return loadMovie(context, TmdbPosters.title(doc.displayName),
                        TmdbPosters.year(doc.displayName));
            }
        } catch (Exception e) {
            // No details are better than a broken Info panel
        }
        return null;
    }

    private static Info loadMovie(Context context, String title, String year) {
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        final String key = TmdbPosters.key(false, title, year);
        final Info cached = read(context, key);
        if (cached != null) {
            return cached;
        }
        if (hasMiss(context, key)) {
            return null;
        }
        try {
            final JSONObject result = search("movie", title, year);
            if (result == null) {
                miss(context, key);
                return null;
            }
            final Info info = fetchMovie(result.optInt("id"), result);
            if (info != null) {
                write(context, key, info);
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static Info loadShow(Context context, String title) {
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        final String key = TmdbPosters.key(true, title, null);
        final Info cached = read(context, key);
        if (cached != null) {
            return cached;
        }
        if (hasMiss(context, key)) {
            return null;
        }
        try {
            final JSONObject result = search("tv", title, null);
            if (result == null) {
                miss(context, key);
                return null;
            }
            final Info info = fetchShow(result.optInt("id"), result);
            if (info != null) {
                write(context, key, info);
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static Info loadSeason(Context context, String showTitle, int season) {
        final Info show = loadShow(context, showTitle);
        if (show == null || show.id <= 0) {
            return null;
        }
        final String key = "season-" + slug(showTitle) + "-" + season;
        final Info cached = read(context, key);
        if (cached != null) {
            return cached;
        }
        if (hasMiss(context, key)) {
            return null;
        }
        try {
            final JSONObject data = TmdbApi.get("/tv/" + show.id + "/season/" + season,
                    single("append_to_response", "credits"));
            if (data == null) {
                miss(context, key);
                return null;
            }
            final Info info = new Info();
            info.kind = SEASON;
            info.id = show.id;
            info.title = show.title;
            info.originalTitle = show.originalTitle;
            info.genres = show.genres;
            info.certification = show.certification;
            info.season = data.optInt("season_number", season);
            info.date = string(data, "air_date");
            info.overview = string(data, "overview");
            info.rating = data.optDouble("vote_average", 0);
            info.votes = data.optInt("vote_count");
            final JSONArray episodes = data.optJSONArray("episodes");
            info.episodes = episodes == null ? 0 : episodes.length();
            final JSONObject credits = data.optJSONObject("credits");
            if (credits != null) {
                info.cast = joinNames(credits.optJSONArray("cast"), "name", 6);
            }
            if (TextUtils.isEmpty(info.cast)) {
                info.cast = show.cast;
            }
            write(context, key, info);
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static Info loadEpisode(Context context, String showTitle, int season, int episode) {
        final Info show = loadShow(context, showTitle);
        if (show == null || show.id <= 0) {
            return null;
        }
        final String key = "episode-" + slug(showTitle) + "-s" + season + "e" + episode;
        final Info cached = read(context, key);
        if (cached != null) {
            return cached;
        }
        if (hasMiss(context, key)) {
            return null;
        }
        try {
            final JSONObject data = TmdbApi.get(
                    "/tv/" + show.id + "/season/" + season + "/episode/" + episode, null);
            if (data == null) {
                final Info fallback = fallbackEpisode(show, season, episode);
                write(context, key, fallback);
                return fallback;
            }
            final Info info = new Info();
            info.kind = EPISODE;
            info.id = show.id;
            info.title = show.title;
            info.originalTitle = show.originalTitle;
            info.genres = show.genres;
            info.certification = show.certification;
            info.cast = show.cast;
            info.season = data.optInt("season_number", season);
            info.episode = data.optInt("episode_number", episode);
            info.episodeTitle = string(data, "name");
            info.date = string(data, "air_date");
            info.overview = string(data, "overview");
            info.rating = data.optDouble("vote_average", 0);
            info.votes = data.optInt("vote_count");
            info.runtime = data.optInt("runtime");
            info.guestStars = joinNames(data.optJSONArray("guest_stars"), "name", 5);
            final JSONArray crew = data.optJSONArray("crew");
            info.director = joinCrew(crew, "Director", 2);
            info.writers = joinCrew(crew, new String[] { "Writer", "Screenplay", "Teleplay" }, 3);
            write(context, key, info);
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static Info fetchMovie(int id, JSONObject search) throws IOException, JSONException {
        final JSONObject data = TmdbApi.get("/movie/" + id,
                single("append_to_response", "credits,release_dates"));
        if (data == null) {
            return null;
        }
        final Info info = new Info();
        info.kind = MOVIE;
        info.id = id;
        info.title = first(string(data, "title"), string(search, "title"));
        info.originalTitle = string(data, "original_title");
        info.date = string(data, "release_date");
        info.overview = string(data, "overview");
        info.tagline = string(data, "tagline");
        info.rating = data.optDouble("vote_average", 0);
        info.votes = data.optInt("vote_count");
        info.genres = joinNames(data.optJSONArray("genres"), "name", 0);
        info.runtime = data.optInt("runtime");
        info.status = string(data, "status");
        final JSONObject credits = data.optJSONObject("credits");
        if (credits != null) {
            info.cast = joinNames(credits.optJSONArray("cast"), "name", 6);
            final JSONArray crew = credits.optJSONArray("crew");
            info.director = joinCrew(crew, "Director", 2);
            info.writers = joinCrew(crew, new String[] { "Writer", "Screenplay", "Story" }, 3);
        }
        info.certification = pickCertification(data.optJSONObject("release_dates"));
        return info;
    }

    private static Info fetchShow(int id, JSONObject search) throws IOException, JSONException {
        final JSONObject data = TmdbApi.get("/tv/" + id,
                single("append_to_response", "credits,content_ratings"));
        if (data == null) {
            return null;
        }
        final Info info = new Info();
        info.kind = SHOW;
        info.id = id;
        info.title = first(string(data, "name"), string(search, "name"));
        info.originalTitle = string(data, "original_name");
        info.date = string(data, "first_air_date");
        info.overview = string(data, "overview");
        info.tagline = string(data, "tagline");
        info.rating = data.optDouble("vote_average", 0);
        info.votes = data.optInt("vote_count");
        info.genres = joinNames(data.optJSONArray("genres"), "name", 0);
        info.seasons = data.optInt("number_of_seasons");
        info.episodes = data.optInt("number_of_episodes");
        info.status = string(data, "status");
        info.network = joinNames(data.optJSONArray("networks"), "name", 3);
        info.creator = joinNames(data.optJSONArray("created_by"), "name", 3);
        final JSONArray runtimes = data.optJSONArray("episode_run_time");
        if (runtimes != null && runtimes.length() > 0) {
            info.runtime = runtimes.optInt(0);
        }
        final JSONObject credits = data.optJSONObject("credits");
        if (credits != null) {
            info.cast = joinNames(credits.optJSONArray("cast"), "name", 6);
        }
        info.certification = pickCertification(data.optJSONObject("content_ratings"));
        return info;
    }

    /** An episode TMDB doesn't list still shares its show's cast, genres and certification */
    private static Info fallbackEpisode(Info show, int season, int episode) {
        final Info info = new Info();
        info.kind = EPISODE;
        info.id = show.id;
        info.title = show.title;
        info.originalTitle = show.originalTitle;
        info.genres = show.genres;
        info.certification = show.certification;
        info.cast = show.cast;
        info.season = season;
        info.episode = episode;
        return info;
    }

    private static JSONObject search(String type, String title, String year)
            throws IOException, JSONException {
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("query", title);
        params.put("include_adult", "false");
        if (year != null) {
            params.put("tv".equals(type) ? "first_air_date_year" : "year", year);
        }
        final JSONObject answer = TmdbApi.get("/search/" + type, params);
        final JSONArray results = answer == null ? null : answer.optJSONArray("results");
        if (results == null || results.length() == 0) {
            return null;
        }
        final String wanted = title.toLowerCase(Locale.ROOT);
        JSONObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < results.length(); i++) {
            final JSONObject result = results.optJSONObject(i);
            if (result == null || result.optBoolean("adult")) {
                continue;
            }
            final String name = string(result, "tv".equals(type) ? "name" : "title");
            if (TextUtils.isEmpty(name)) {
                continue;
            }
            final String lower = name.toLowerCase(Locale.ROOT);
            double score = lower.equals(wanted) ? 3 : lower.startsWith(wanted) ? 2 : lower.contains(wanted) ? 1 : 0;
            final String date = string(result, "tv".equals(type) ? "first_air_date" : "release_date");
            if (year != null && date.startsWith(year)) {
                score += 1;
            }
            score += result.optDouble("popularity", 0) / 1000.0;
            if (score > bestScore) {
                bestScore = score;
                best = result;
            }
        }
        return best;
    }

    private static String first(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    /** Android's optString turns a JSON null into "null"; treat it as empty */
    private static String string(JSONObject object, String name) {
        if (object == null || object.isNull(name)) {
            return "";
        }
        final String value = object.optString(name);
        return "null".equals(value) ? "" : value;
    }

    private static String joinNames(JSONArray array, String key, int limit) {
        if (array == null) {
            return null;
        }
        final Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < array.length() && (limit <= 0 || names.size() < limit); i++) {
            final String name = string(array.optJSONObject(i), key);
            if (!TextUtils.isEmpty(name)) {
                names.add(name);
            }
        }
        return join(names);
    }

    private static String joinCrew(JSONArray crew, String job, int limit) {
        return joinCrew(crew, new String[] { job }, limit);
    }

    private static String joinCrew(JSONArray crew, String[] jobs, int limit) {
        if (crew == null) {
            return null;
        }
        final Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < crew.length() && names.size() < limit; i++) {
            final JSONObject member = crew.optJSONObject(i);
            if (member == null) {
                continue;
            }
            final String job = string(member, "job");
            for (String wanted : jobs) {
                if (wanted.equals(job)) {
                    final String name = string(member, "name");
                    if (!TextUtils.isEmpty(name)) {
                        names.add(name);
                    }
                    break;
                }
            }
        }
        return join(names);
    }

    private static String join(Set<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        final StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(name);
        }
        return out.toString();
    }

    /** Prefers the US rating, then the UK one, then any rating the answer carries */
    private static String pickCertification(JSONObject data) {
        if (data == null) {
            return null;
        }
        final JSONArray results = data.optJSONArray("results");
        if (results == null) {
            return null;
        }
        String fallback = null;
        for (int i = 0; i < results.length(); i++) {
            final JSONObject result = results.optJSONObject(i);
            if (result == null) {
                continue;
            }
            String rating = null;
            final JSONArray releases = result.optJSONArray("release_dates");
            if (releases != null) {
                for (int j = 0; j < releases.length(); j++) {
                    final String value = string(releases.optJSONObject(j), "certification");
                    if (!TextUtils.isEmpty(value)) {
                        rating = value;
                        break;
                    }
                }
            } else {
                rating = string(result, "rating");
            }
            if (TextUtils.isEmpty(rating)) {
                continue;
            }
            final String country = result.optString("iso_3166_1");
            if ("US".equals(country)) {
                return rating;
            }
            if (fallback == null || "GB".equals(country)) {
                fallback = rating;
            }
        }
        return fallback;
    }

    private static String findShowName(Context context, long showId) {
        final ContentResolver resolver = context.getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[] { MediaStore.Video.VideoColumns.DISPLAY_NAME }, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                final EpisodeName episode = EpisodeName.parse(cursor.getString(0));
                if (episode != null && episode.showId() == showId) {
                    return episode.show;
                }
            }
        } catch (Exception e) {
            // The media store may be unavailable; the season then has no details
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static String slug(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private static Map<String, String> single(String key, String value) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put(key, value);
        return params;
    }

    private static File folder(Context context) {
        final File folder = new File(context.getCacheDir(), FOLDER);
        if (!folder.isDirectory()) {
            folder.mkdirs();
        }
        return folder;
    }

    private static Info read(Context context, String key) {
        final File file = new File(folder(context), key + JSON);
        if (!file.isFile()) {
            return null;
        }
        try {
            final JSONObject json = new JSONObject(new String(readAll(file), StandardCharsets.UTF_8));
            if (json.optInt("v") != VERSION) {
                file.delete();
                return null;
            }
            final Info info = new Info();
            info.kind = json.optInt("kind");
            info.id = json.optInt("id");
            info.title = json.optString("title");
            info.originalTitle = json.optString("originalTitle");
            info.date = json.optString("date");
            info.overview = json.optString("overview");
            info.tagline = json.optString("tagline");
            info.genres = json.optString("genres");
            info.status = json.optString("status");
            info.network = json.optString("network");
            info.creator = json.optString("creator");
            info.director = json.optString("director");
            info.writers = json.optString("writers");
            info.cast = json.optString("cast");
            info.guestStars = json.optString("guestStars");
            info.certification = json.optString("certification");
            info.episodeTitle = json.optString("episodeTitle");
            info.runtime = json.optInt("runtime");
            info.seasons = json.optInt("seasons");
            info.episodes = json.optInt("episodes");
            info.season = json.optInt("season");
            info.episode = json.optInt("episode");
            info.rating = json.optDouble("rating", 0);
            info.votes = json.optInt("votes");
            return info;
        } catch (Exception e) {
            file.delete();
            return null;
        }
    }

    private static void write(Context context, String key, Info info) {
        final File folder = folder(context);
        final File file = new File(folder, key + JSON);
        final File temp = new File(folder, key + JSON + ".tmp");
        try {
            final JSONObject json = toJson(info);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) {
                file.delete();
                if (!temp.renameTo(file)) {
                    temp.delete();
                }
            }
        } catch (Exception e) {
            temp.delete();
        }
    }

    private static JSONObject toJson(Info info) throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("v", VERSION);
        json.put("kind", info.kind);
        json.put("id", info.id);
        json.put("title", nonNull(info.title));
        json.put("originalTitle", nonNull(info.originalTitle));
        json.put("date", nonNull(info.date));
        json.put("overview", nonNull(info.overview));
        json.put("tagline", nonNull(info.tagline));
        json.put("genres", nonNull(info.genres));
        json.put("status", nonNull(info.status));
        json.put("network", nonNull(info.network));
        json.put("creator", nonNull(info.creator));
        json.put("director", nonNull(info.director));
        json.put("writers", nonNull(info.writers));
        json.put("cast", nonNull(info.cast));
        json.put("guestStars", nonNull(info.guestStars));
        json.put("certification", nonNull(info.certification));
        json.put("episodeTitle", nonNull(info.episodeTitle));
        json.put("runtime", info.runtime);
        json.put("seasons", info.seasons);
        json.put("episodes", info.episodes);
        json.put("season", info.season);
        json.put("episode", info.episode);
        json.put("rating", info.rating);
        json.put("votes", info.votes);
        return json;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean hasMiss(Context context, String key) {
        return new File(folder(context), key + MISS).isFile();
    }

    private static void miss(Context context, String key) {
        try {
            new File(folder(context), key + MISS).createNewFile();
        } catch (IOException e) {
            // The next visit simply asks again
        }
    }
}
