package dev.dworks.apps.anexplorer.misc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the show, season and episode out of a file name such as {@code Show.Name.S01E02.mkv} or
 * {@code Show Name 1x02.mp4}, so videos can be grouped the way a media library does. A name that
 * doesn't look like an episode is a movie.
 */
public final class EpisodeName {

    private static final Pattern EPISODE = Pattern.compile(
            "^(.+?)[ ._\\-\\[(]+(?:s(\\d{1,2})[ ._\\-]?e(\\d{1,3})|(\\d{1,2})x(\\d{2,3}))(?![0-9a-z])",
            Pattern.CASE_INSENSITIVE);

    public final String show;
    public final int season;
    public final int episode;

    private EpisodeName(String show, int season, int episode) {
        this.show = show;
        this.season = season;
        this.episode = episode;
    }

    /** @return the parsed name, or null when the file name isn't an episode */
    public static EpisodeName parse(String fileName) {
        if (fileName == null) {
            return null;
        }
        final Matcher m = EPISODE.matcher(fileName);
        if (!m.find()) {
            return null;
        }
        final String show = m.group(1).replace('.', ' ').replace('_', ' ').trim();
        if (show.isEmpty()) {
            return null;
        }
        final boolean sxe = m.group(2) != null;
        return new EpisodeName(show,
                Integer.parseInt(sxe ? m.group(2) : m.group(4)),
                Integer.parseInt(sxe ? m.group(3) : m.group(5)));
    }

    /** Stable and positive, so it fits the numeric part of a document id */
    public long showId() {
        return show.toLowerCase(Locale.ROOT).hashCode() & 0x7fffffffL;
    }
}
