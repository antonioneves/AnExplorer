package dev.dworks.apps.anexplorer.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class EpisodeNameTest {

    private static void assertEpisode(String name, String show, int season, int episode) {
        final EpisodeName parsed = EpisodeName.parse(name);
        assertNotNull(name, parsed);
        assertEquals(show, parsed.show);
        assertEquals(season, parsed.season);
        assertEquals(episode, parsed.episode);
    }

    @Test
    public void sxxExxWithDots() {
        assertEpisode("Show.Name.S01E02.1080p.mkv", "Show Name", 1, 2);
    }

    @Test
    public void sxxExxIsCaseInsensitive() {
        assertEpisode("show_name_s10e105.mp4", "show name", 10, 105);
    }

    @Test
    public void nxnn() {
        assertEpisode("Show Name 1x02.mp4", "Show Name", 1, 2);
    }

    @Test
    public void yearAfterTheShowNameIsDropped() {
        assertEpisode("Show Name (2019) S02E03.mkv", "Show Name", 2, 3);
        assertEpisode("Show.Name.2019.S02E03.mkv", "Show Name", 2, 3);
    }

    @Test
    public void movieIsNotAnEpisode() {
        assertNull(EpisodeName.parse("Some Movie 2019 1080p.mkv"));
    }

    @Test
    public void resolutionIsNotAnEpisode() {
        assertNull(EpisodeName.parse("Show 1920x1080.mkv"));
    }

    @Test
    public void nullAndEmpty() {
        assertNull(EpisodeName.parse(null));
        assertNull(EpisodeName.parse(""));
    }

    @Test
    public void nameWithoutShowIsNotAnEpisode() {
        assertNull(EpisodeName.parse("S01E02.mkv"));
    }

    @Test
    public void showIdIgnoresCaseAndIsPositive() {
        final long a = EpisodeName.parse("Show.Name.S01E02.mkv").showId();
        final long b = EpisodeName.parse("SHOW NAME 3x04.mkv").showId();
        assertEquals(a, b);
        assertEquals(true, a >= 0);
    }
}
