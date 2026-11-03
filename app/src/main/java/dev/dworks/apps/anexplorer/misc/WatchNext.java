package dev.dworks.apps.anexplorer.misc;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.tvprovider.media.tv.TvContractCompat;
import androidx.tvprovider.media.tv.WatchNextProgram;

import dev.dworks.apps.anexplorer.BuildConfig;
import dev.dworks.apps.anexplorer.PlayerActivity;
import dev.dworks.apps.anexplorer.R;

/**
 * Puts the videos that were left half watched in the Android TV launcher's Watch Next row, so
 * "Continue Watching" opens the player where it stopped. Programs are keyed by the watch key and
 * removed once the video is watched to the end.
 */
public final class WatchNext {

    private static final String TAG = "WatchNext";
    private static final String PREFS = "watch_next";

    private WatchNext() {
    }

    /** Adds or updates the entry. Does the poster lookup, so it runs on its own thread. */
    public static void update(Context context, Uri uri, String mimeType, String key,
            String fileName, long position, long duration) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                publish(app, uri, mimeType, key, fileName, position, duration);
            } catch (Exception e) {
                // The launcher may not have a Watch Next row, and that must never hurt playback
                Log.w(TAG, "Couldn't update Watch Next", e);
            }
        }, TAG).start();
    }

    public static void remove(Context context, String key) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                final long id = prefs.getLong(key, -1);
                if (id >= 0) {
                    app.getContentResolver().delete(
                            TvContractCompat.buildWatchNextProgramUri(id), null, null);
                    prefs.edit().remove(key).apply();
                }
            } catch (Exception e) {
                Log.w(TAG, "Couldn't remove from Watch Next", e);
            }
        }, TAG).start();
    }

    private static void publish(Context context, Uri uri, String mimeType, String key,
            String fileName, long position, long duration) {
        final Intent play = new Intent(Intent.ACTION_VIEW)
                .setClass(context, PlayerActivity.class)
                .setDataAndType(uri, mimeType)
                .putExtra(PlayerActivity.EXTRA_WATCH_KEY, key)
                .putExtra(PlayerActivity.EXTRA_NAME, fileName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final String poster = TmdbPosters.posterUrl(context, fileName);
        final Uri art = poster != null ? Uri.parse(poster) : Uri.parse(
                "android.resource://" + BuildConfig.APPLICATION_ID + "/" + R.drawable.banner);
        final WatchNextProgram.Builder builder = new WatchNextProgram.Builder()
                .setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setTitle(TmdbPosters.title(fileName))
                .setPosterArtUri(art)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setInternalProviderId(key)
                .setLastPlaybackPositionMillis((int) position)
                .setDurationMillis((int) duration)
                .setIntent(play);

        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long id = prefs.getLong(key, -1);
        if (id >= 0) {
            final int rows = context.getContentResolver().update(
                    TvContractCompat.buildWatchNextProgramUri(id),
                    builder.build().toContentValues(), null, null);
            if (rows > 0) {
                return;
            }
        }
        final Uri row = context.getContentResolver().insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI, builder.build().toContentValues());
        if (row != null) {
            prefs.edit().putLong(key, ContentUris.parseId(row)).apply();
        }
    }
}
