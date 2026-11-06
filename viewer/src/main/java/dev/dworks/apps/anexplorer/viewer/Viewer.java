package dev.dworks.apps.anexplorer.viewer;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * What the player needs from the app hosting it: film and show titles, where each video was
 * left, the launcher's Watch Next row and subtitle downloads. Without a host the player still
 * plays everything, with file names for titles and nothing remembered.
 */
public final class Viewer {

    /** The app's side of the player. Each video is identified by the key the app opened it with. */
    public interface Host {

        /** The film or episode title for a file name, or null to show the name itself */
        default String title(String fileName) {
            return null;
        }

        /** Whether the file is a film or episode subtitles can be found for; may use the network */
        default boolean isRecognized(Context context, String fileName) {
            return false;
        }

        /** Where the video was left, in milliseconds, or 0 to start from the beginning */
        default long getPosition(Context context, String key) {
            return 0;
        }

        /** The video stopped before its end: remember where, and put it on Watch Next */
        default void onStopped(Context context, Uri uri, String type, String key, String fileName,
                long position, long duration) {
        }

        /** The video played to its end */
        default void onEnded(Context context, String key, String fileName) {
        }

        /** Whether the player should offer to download subtitles at all */
        default boolean canFindSubtitles(Context context) {
            return false;
        }

        /** The subtitle downloaded for the video earlier, or null */
        default File getSavedSubtitle(Context context, String key) {
            return null;
        }

        /** The subtitles found online for the file; runs off the main thread */
        default List<? extends Subtitle> findSubtitles(Context context, String fileName) throws Exception {
            return Collections.emptyList();
        }

        /** Downloads one of the subtitles found and keeps it for the video; runs off the main thread */
        default File downloadSubtitle(Context context, Subtitle subtitle, String key, String fileName)
                throws Exception {
            throw new UnsupportedOperationException();
        }
    }

    private static Host sHost = new Host() {
    };

    private Viewer() {
    }

    /** Set once, when the app starts */
    public static void setHost(Host host) {
        sHost = host;
    }

    static Host host() {
        return sHost;
    }
}
