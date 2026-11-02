package dev.dworks.apps.anexplorer.misc;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers which videos were watched to the end and how far the unfinished ones got, so episode
 * cards can show it and the player can resume. Kept in its own prefs file, keyed by document id.
 */
public class WatchHistory {

    public static final int NONE = 0;
    public static final int VIEWING = 1;
    public static final int VIEWED = 2;

    private static final String PREFS = "watch_history";
    private static final String POSITION = "pos_";
    private static final String VIEWED_KEY = "done_";
    /** Anything shorter than this isn't a real start, and the last stretch is just credits */
    private static final long MIN_POSITION_MS = 10_000;
    private static final float DONE_FRACTION = 0.95f;

    private final Context mContext;
    private final SharedPreferences mPrefs;

    public WatchHistory(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Like {@link #getState(String)}, but a video Trakt knows as watched also counts, and is then
     * remembered here, so it keeps showing as played without the account.
     */
    public int getState(String key, String fileName) {
        final int state = getState(key);
        if (state != VIEWED && Trakt.isWatched(mContext, fileName)) {
            markViewed(key);
            return VIEWED;
        }
        return state;
    }

    public int getState(String key) {
        if (mPrefs.getBoolean(VIEWED_KEY + key, false)) {
            return VIEWED;
        }
        return mPrefs.getLong(POSITION + key, 0) > 0 ? VIEWING : NONE;
    }

    /** @return where to resume, or 0 to start over */
    public long getPosition(String key) {
        return mPrefs.getLong(POSITION + key, 0);
    }

    public void savePosition(String key, String fileName, long position, long duration) {
        if (duration > 0 && position >= duration * DONE_FRACTION) {
            markViewed(key, fileName);
        } else if (position >= MIN_POSITION_MS) {
            mPrefs.edit().putLong(POSITION + key, position).apply();
        }
    }

    public void markViewed(String key) {
        mPrefs.edit().putBoolean(VIEWED_KEY + key, true).remove(POSITION + key).apply();
    }

    /** For a video watched to the end here: also tells Trakt when signed in */
    public void markViewed(String key, String fileName) {
        markViewed(key);
        Trakt.markWatched(mContext, fileName);
    }
}
