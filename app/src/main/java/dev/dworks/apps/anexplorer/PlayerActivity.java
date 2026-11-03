package dev.dworks.apps.anexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import dev.dworks.apps.anexplorer.misc.VlcPlayer;
import dev.dworks.apps.anexplorer.misc.SubtitleFinder;
import dev.dworks.apps.anexplorer.misc.WatchHistory;
import dev.dworks.apps.anexplorer.misc.WatchNext;
import dev.dworks.apps.anexplorer.setting.AppSettings;

/**
 * Plays video and audio inside the app so it doesn't depend on another player being installed.
 * The ffmpeg renderers are preferred so formats the platform codecs lack still play.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    /** Document id of the video, so the episode cards can show how far it got */
    public static final String EXTRA_WATCH_KEY = "watch_key";
    /** File name, shown for audio files with no title tag */
    public static final String EXTRA_NAME = "player_name";

    private static final int CONTROLS_TIMEOUT_MS = 4000;

    /**
     * ExoPlayer spends seconds probing these before it fails, so they go straight to libVLC. Any
     * other format ExoPlayer can't read still gets there through onPlayerError.
     */
    private static final Set<String> VLC_ONLY_TYPES = new HashSet<>(Arrays.asList(
            "video/x-ms-wmv", "video/x-ms-asf", "video/vnd.rn-realvideo", "video/wtv", "video/x-amv",
            "video/mxf", "video/x-mtv", "video/x-nsv", "video/x-nuv", "video/x-nut", "video/x-gxf",
            "video/x-mxg", "video/x-gvi", "video/x-bink", "video/x-dv", "video/dv", "video/x-dirac",
            "video/x-xesc",
            "audio/x-ms-wma", "audio/x-ape", "audio/x-wavpack", "audio/x-dsf", "audio/x-musepack",
            "audio/x-oma", "audio/x-tta", "audio/x-vqf", "audio/x-xa", "audio/x-w64", "audio/x-voc",
            "audio/x-caf", "audio/x-pn-realaudio", "audio/x-mod", "audio/x-xm", "audio/x-s3m",
            "audio/x-it", "audio/x-669"));

    private PlayerView mPlayerView;
    private Player mPlayer;
    private boolean mAudio;
    private boolean mScrubbing;
    private boolean mEnded;
    private String mWatchKey;
    private WatchHistory mHistory;
    private final Runnable mHideControls = this::fadeOutControls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final String type = getIntent().getType();
        mAudio = type != null && type.startsWith("audio/");
        if (mAudio) {
            setContentView(R.layout.player_audio);
            mPlayerView = findViewById(R.id.player_view);
            showNowPlaying(MediaMetadata.EMPTY);
        } else {
            mPlayerView = new PlayerView(this);
            setContentView(mPlayerView);
        }
        mPlayerView.setFocusable(true);
        // The stock slide-down stutters on TVs, so the controls fade in and out instead
        mPlayerView.setControllerAnimationEnabled(false);
        mPlayerView.setControllerShowTimeoutMs(0);
        mPlayerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility -> {
                    if (visibility == View.VISIBLE) {
                        final View controls = controls();
                        if (controls != null) {
                            controls.setAlpha(0f);
                        }
                        fadeInControls();
                    }
                });
        // Only the time bar circle shows focus; it takes focus but only scrubs after OK, otherwise it swallows LEFT/RIGHT
        final View timeBar = mPlayerView.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (timeBar != null) {
            timeBar.setFocusable(true);
            timeBar.setOnFocusChangeListener((v, hasFocus) -> setScrubbing(false));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        final Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        mEnded = false;
        mHistory = new WatchHistory(this);
        mWatchKey = getIntent().getStringExtra(EXTRA_WATCH_KEY);
        if (VLC_ONLY_TYPES.contains(getIntent().getType())) {
            startVlc();
            return;
        }
        mPlayer = new ExoPlayer.Builder(this,
                new DefaultRenderersFactory(this)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
                .build();
        mPlayer.addListener(mListener);
        mPlayerView.setPlayer(mPlayer);
        mPlayer.setMediaItem(mediaItem(uri));
        setUpSubtitleSearch();
        start();
    }

    /** The video, with the subtitle downloaded for it earlier, if any */
    private MediaItem mediaItem(Uri uri) {
        final File saved = mWatchKey == null ? null : SubtitleFinder.saved(this, mWatchKey);
        final MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
        if (saved != null) {
            builder.setSubtitleConfigurations(Collections.singletonList(subtitle(saved)));
        }
        return builder.build();
    }

    private MediaItem.SubtitleConfiguration subtitle(File file) {
        final String name = file.getName();
        final String mime = name.endsWith(".vtt") ? MimeTypes.TEXT_VTT
                : name.endsWith(".ass") || name.endsWith(".ssa") ? MimeTypes.TEXT_SSA
                : MimeTypes.APPLICATION_SUBRIP;
        return new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                .setMimeType(mime)
                .setLanguage(AppSettings.getSubtitleLanguage(this))
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();
    }

    /** The download button shows only where it can work: ExoPlayer, a keyed video, a service set up */
    private void setUpSubtitleSearch() {
        final View button = mPlayerView.findViewById(R.id.player_subtitle_download);
        if (button == null) {
            return;
        }
        final boolean available = !mAudio && mWatchKey != null && mPlayer instanceof ExoPlayer
                && SubtitleFinder.isConfigured(this);
        button.setVisibility(available ? View.VISIBLE : View.GONE);
        button.setOnClickListener(v -> searchSubtitles());
    }

    private void searchSubtitles() {
        Toast.makeText(this, R.string.player_subtitle_searching, Toast.LENGTH_SHORT).show();
        final String name = fileName();
        new Thread(() -> {
            try {
                final List<SubtitleFinder.Subtitle> found = SubtitleFinder.search(this, name);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (found.isEmpty()) {
                        Toast.makeText(this, R.string.player_subtitle_none, Toast.LENGTH_LONG).show();
                    } else {
                        chooseSubtitle(found, name);
                    }
                });
            } catch (Exception e) {
                Log.w("PlayerActivity", "Subtitle search failed", e);
                runOnUiThread(() -> Toast.makeText(this, R.string.player_subtitle_failed,
                        Toast.LENGTH_LONG).show());
            }
        }, "SubtitleSearch").start();
    }

    private void chooseSubtitle(List<SubtitleFinder.Subtitle> found, String name) {
        final String[] labels = new String[found.size()];
        for (int i = 0; i < labels.length; i++) {
            final SubtitleFinder.Subtitle item = found.get(i);
            labels[i] = (item.release.isEmpty() ? name : item.release)
                    + " · " + item.language.toUpperCase(Locale.ROOT) + (item.hearingImpaired ? " · HI" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_subtitle_choose)
                .setItems(labels, (dialog, which) -> downloadSubtitle(found.get(which), name))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void downloadSubtitle(SubtitleFinder.Subtitle subtitle, String name) {
        Toast.makeText(this, R.string.player_subtitle_downloading, Toast.LENGTH_SHORT).show();
        final String key = mWatchKey;
        new Thread(() -> {
            try {
                final File file = SubtitleFinder.download(this, subtitle, key, name);
                runOnUiThread(() -> showSubtitle(file));
            } catch (Exception e) {
                Log.w("PlayerActivity", "Subtitle download failed", e);
                runOnUiThread(() -> Toast.makeText(this, R.string.player_subtitle_failed,
                        Toast.LENGTH_LONG).show());
            }
        }, "SubtitleDownload").start();
    }

    /** Puts the subtitle on the playing video, keeping the position and play state */
    private void showSubtitle(File file) {
        if (isFinishing() || isDestroyed() || !(mPlayer instanceof ExoPlayer)) {
            return;
        }
        final MediaItem current = mPlayer.getCurrentMediaItem();
        if (current == null) {
            return;
        }
        final long position = mPlayer.getCurrentPosition();
        final boolean playing = mPlayer.getPlayWhenReady();
        mPlayer.setMediaItem(current.buildUpon()
                .setSubtitleConfigurations(Collections.singletonList(subtitle(file))).build(), position);
        mPlayer.prepare();
        mPlayer.setPlayWhenReady(playing);
    }

    private final Player.Listener mListener = new Player.Listener() {
        @Override
        public void onPlayerError(PlaybackException error) {
            if (mPlayer instanceof ExoPlayer) {
                Log.w("PlayerActivity", "Playback failed, trying libVLC", error);
                switchToVlc();
                return;
            }
            Log.w("PlayerActivity", "Playback failed", error);
            Toast.makeText(PlayerActivity.this, R.string.toast_no_application, Toast.LENGTH_LONG).show();
            finish();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) {
                mEnded = true;
                if (mWatchKey != null) {
                    mHistory.markViewed(mWatchKey, getIntent().getStringExtra(EXTRA_NAME));
                    WatchNext.remove(PlayerActivity.this, mWatchKey);
                }
                finish();
            }
        }

        @Override
        public void onMediaMetadataChanged(MediaMetadata metadata) {
            if (mAudio) {
                showNowPlaying(metadata);
            }
        }
    };

    /**
     * The tags come from the player already reading the file: opening it a second time to read them
     * could break playback on network storage that only allows one transfer per connection.
     */
    private void showNowPlaying(MediaMetadata metadata) {
        final TextView title = findViewById(R.id.player_title);
        title.setText(TextUtils.isEmpty(metadata.title) ? fileName() : metadata.title);
        showTag(findViewById(R.id.player_artist), metadata.artist);
        showTag(findViewById(R.id.player_album), metadata.albumTitle);
        final Bitmap art = decodeArtwork(metadata.artworkData);
        if (art != null) {
            final ImageView view = findViewById(R.id.player_art);
            view.setPadding(0, 0, 0, 0);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setClipToOutline(true);
            view.setImageBitmap(art);
        }
    }

    private static void showTag(TextView view, CharSequence tag) {
        view.setText(tag);
        view.setVisibility(TextUtils.isEmpty(tag) ? View.GONE : View.VISIBLE);
    }

    private String fileName() {
        final String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name != null) {
            return name;
        }
        final Uri uri = getIntent().getData();
        final String segment = uri == null ? null : uri.getLastPathSegment();
        return segment == null ? "" : segment.substring(segment.lastIndexOf('/') + 1);
    }

    /** Covers can be several thousand pixels wide, far more than the space they get here */
    private Bitmap decodeArtwork(byte[] data) {
        if (data == null) {
            return null;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        final int size = findViewById(R.id.player_art).getLayoutParams().width;
        options.inSampleSize = 1;
        while (Math.min(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= size) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    /** The formats ExoPlayer can't read go to libVLC, which plays through the same controls */
    private void switchToVlc() {
        mPlayer.release();
        startVlc();
    }

    private void startVlc() {
        mPlayer = new VlcPlayer(this, getIntent().getData());
        mPlayer.addListener(mListener);
        // libVLC reports no tracks, so the black shutter would otherwise cover its picture
        mPlayerView.setShutterBackgroundColor(Color.TRANSPARENT);
        mPlayerView.setPlayer(mPlayer);
        start();
    }

    private void start() {
        if (mWatchKey != null && AppSettings.isResumePlaybackEnabled(this)
                && mHistory.getPosition(mWatchKey) > 0) {
            mPlayer.seekTo(mHistory.getPosition(mWatchKey));
        }
        mPlayer.prepare();
        mPlayer.play();
        if (mAudio) {
            mPlayerView.showController();
            mPlayerView.findViewById(androidx.media3.ui.R.id.exo_play_pause).requestFocus();
        } else {
            mPlayerView.requestFocus();
        }
    }

    private void setScrubbing(boolean scrubbing) {
        mScrubbing = scrubbing;
        final View timeBar = mPlayerView.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (timeBar != null) {
            timeBar.setActivated(scrubbing);
        }
    }

    /** Only the controls fade, never the video behind them */
    private View controls() {
        return mPlayerView.findViewById(androidx.media3.ui.R.id.exo_controller);
    }

    private void fadeInControls() {
        final View controls = controls();
        mPlayerView.removeCallbacks(mHideControls);
        if (controls != null) {
            controls.animate().cancel();
            if (controls.getAlpha() < 1f) {
                controls.animate().alpha(1f).setDuration(200).withEndAction(null);
            }
        }
        // With no picture to uncover, hiding the controls would only leave an empty screen
        if (!mAudio) {
            mPlayerView.postDelayed(mHideControls, CONTROLS_TIMEOUT_MS);
        }
    }

    private void fadeOutControls() {
        final View controls = controls();
        if (controls == null) {
            mPlayerView.hideController();
            return;
        }
        controls.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            mPlayerView.hideController();
            controls.setAlpha(1f);
        });
    }

    /** Saved here, not in onStop, because the list behind resumes before the player stops and must see it */
    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayer != null && mWatchKey != null && !mEnded) {
            mHistory.savePosition(mWatchKey, getIntent().getStringExtra(EXTRA_NAME),
                    mPlayer.getCurrentPosition(), mPlayer.getDuration());
            updateWatchNext();
        }
    }

    /** An unfinished video goes to the launcher's Watch Next row, a finished one leaves it */
    private void updateWatchNext() {
        if (mAudio) {
            return;
        }
        final long position = mPlayer.getCurrentPosition();
        final long duration = mPlayer.getDuration();
        final String name = fileName();
        if (mHistory.getState(mWatchKey) == WatchHistory.VIEWED) {
            WatchNext.remove(this, mWatchKey);
        } else if (mHistory.getPosition(mWatchKey) > 0 && duration > 0) {
            WatchNext.update(this, getIntent().getData(), getIntent().getType(), mWatchKey, name,
                    position, duration);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mPlayerView.removeCallbacks(mHideControls);
        if (mPlayer != null) {
            mPlayerView.setPlayer(null);
            mPlayer.release();
            mPlayer = null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any D-pad key brings the controls up before PlayerView handles it
        if (mPlayerView.isControllerFullyVisible()) {
            fadeInControls();
        }
        final View focus = getCurrentFocus();
        if (focus != null && focus.getId() == androidx.media3.ui.R.id.exo_progress
                && mPlayerView.isControllerFullyVisible()) {
            final int key = event.getKeyCode();
            final boolean select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
            if (select) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    setScrubbing(!mScrubbing);
                }
                return true;
            }
            if (mScrubbing && key == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    setScrubbing(false);
                }
                return true;
            }
            if (!mScrubbing && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    final View next = focus.focusSearch(key == KeyEvent.KEYCODE_DPAD_LEFT
                            ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                    if (next != null) {
                        next.requestFocus();
                    }
                }
                return true;
            }
        }
        return mPlayerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }
}
