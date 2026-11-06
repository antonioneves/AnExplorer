package dev.dworks.apps.anexplorer.misc;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.List;

import dev.dworks.apps.anexplorer.viewer.Subtitle;
import dev.dworks.apps.anexplorer.viewer.Viewer;

/** Gives the player the app's TMDB titles, watch history, Watch Next row and subtitle search */
public class ViewerHost implements Viewer.Host {

    @Override
    public String title(String fileName) {
        return TmdbPosters.title(fileName);
    }

    @Override
    public boolean isRecognized(Context context, String fileName) {
        final int known = TmdbPosters.status(context, fileName);
        return known == TmdbPosters.RECOGNIZED
                || (known == TmdbPosters.UNKNOWN && TmdbPosters.resolve(context, fileName) == TmdbPosters.RECOGNIZED);
    }

    @Override
    public long getPosition(Context context, String key) {
        return new WatchHistory(context).getPosition(key);
    }

    /** An unfinished video goes to the launcher's Watch Next row, a finished one leaves it */
    @Override
    public void onStopped(Context context, Uri uri, String type, String key, String fileName,
            long position, long duration) {
        final WatchHistory history = new WatchHistory(context);
        history.savePosition(key, fileName, position, duration);
        if (type != null && type.startsWith("audio/")) {
            return;
        }
        if (history.getState(key) == WatchHistory.VIEWED) {
            WatchNext.remove(context, key);
        } else if (history.getPosition(key) > 0 && duration > 0) {
            WatchNext.update(context, uri, type, key, fileName, position, duration);
        }
    }

    @Override
    public void onEnded(Context context, String key, String fileName) {
        new WatchHistory(context).markViewed(key, fileName);
        WatchNext.remove(context, key);
    }

    @Override
    public boolean canFindSubtitles(Context context) {
        return SubtitleFinder.isConfigured(context);
    }

    @Override
    public File getSavedSubtitle(Context context, String key) {
        return SubtitleFinder.saved(context, key);
    }

    @Override
    public List<? extends Subtitle> findSubtitles(Context context, String fileName) throws Exception {
        return SubtitleFinder.search(context, fileName);
    }

    @Override
    public File downloadSubtitle(Context context, Subtitle subtitle, String key, String fileName)
            throws Exception {
        return SubtitleFinder.download(context, (SubtitleFinder.Subtitle) subtitle, key, fileName);
    }
}
