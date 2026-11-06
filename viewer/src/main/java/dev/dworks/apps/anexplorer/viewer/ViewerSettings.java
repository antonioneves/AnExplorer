package dev.dworks.apps.anexplorer.viewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import java.util.Locale;

/**
 * The player's and previews' settings. They live in the app's default preferences, where its
 * Settings screen writes them, so the keys must not change.
 */
public final class ViewerSettings {

    public static final String KEY_RESUME_PLAYBACK = "resumePlayback";
    public static final String KEY_SLIDESHOW_SECONDS = "slideshowSeconds";
    public static final String KEY_SUBTITLE_SIZE = "subtitleSize";
    public static final String KEY_SUBTITLE_COLOR = "subtitleColor";
    public static final String KEY_SUBTITLE_BACKGROUND = "subtitleBackground";
    public static final String KEY_SUBTITLE_LANGUAGE = "subtitleLanguage";

    private ViewerSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** Whether a video left unfinished starts again from where it stopped */
    public static boolean isResumePlaybackEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RESUME_PLAYBACK, true);
    }

    /** How long a photo stays up in a slideshow */
    public static int getSlideshowSeconds(Context context) {
        return prefs(context).getInt(KEY_SLIDESHOW_SECONDS, 5);
    }

    public static void setSlideshowSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_SLIDESHOW_SECONDS, seconds).apply();
    }

    /** The two-letter language code subtitles are in, the TV's own language unless set */
    public static String getSubtitleLanguage(Context context) {
        final String value = prefs(context).getString(KEY_SUBTITLE_LANGUAGE, "");
        return TextUtils.isEmpty(value.trim())
                ? Locale.getDefault().getLanguage() : value.trim().toLowerCase(Locale.ROOT);
    }

    /** The subtitle look picked in Settings, or the default when none was picked */
    static String getChoice(Context context, String key, String fallback) {
        return prefs(context).getString(key, fallback);
    }
}
