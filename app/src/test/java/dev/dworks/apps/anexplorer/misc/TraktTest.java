package dev.dworks.apps.anexplorer.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class TraktTest {

    @Test
    public void episodeKeyIgnoresPunctuationAndCase() {
        assertEquals("e:showname:1:2", Trakt.watchedKey("Show.Name.S01E02.1080p.mkv"));
        assertEquals(Trakt.watchedKey("Show.Name.S01E02.mkv"), Trakt.watchedKey("show_name 1x02.mp4"));
    }

    @Test
    public void differentEpisodesHaveDifferentKeys() {
        assertNotEquals(Trakt.watchedKey("Show.S01E02.mkv"), Trakt.watchedKey("Show.S01E03.mkv"));
        assertNotEquals(Trakt.watchedKey("Show.S01E02.mkv"), Trakt.watchedKey("Show.S02E02.mkv"));
    }

    @Test
    public void movieKeyUsesTitleAndYear() {
        assertEquals("m:somemovie:2019", Trakt.watchedKey("Some.Movie.2019.1080p.mkv"));
    }
}
