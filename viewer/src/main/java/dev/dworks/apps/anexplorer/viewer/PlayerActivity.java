package dev.dworks.apps.anexplorer.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.content.res.Resources;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

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
    private MediaSession mSession;
    private int mSleepMinutes;
    private boolean mScrubbing;
    private boolean mEnded;
    private String mWatchKey;
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
        styleSubtitles();
        setUpSubtitleSearch();
        setUpSleepTimer();
        attachSession();
        start();
    }

    /** Lets the remote's media keys, Assistant and the system's now playing card control the player */
    private void attachSession() {
        releaseSession();
        // A second player can start before the first one's activity has stopped, and ids must differ
        mSession = new MediaSession.Builder(this, mPlayer).setId(Long.toHexString(System.nanoTime())).build();
    }

    private void releaseSession() {
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }
    }

    private void setUpSleepTimer() {
        final View button = mPlayerView.findViewById(R.id.player_sleep);
        if (button != null) {
            button.setOnClickListener(v -> showSleepMenu());
        }
    }

    /** Pauses the video after the chosen time, for falling asleep in front of it */
    private void showSleepMenu() {
        final int[] minutes = {0, 15, 30, 45, 60, 90};
        final String[] labels = new String[minutes.length];
        int checked = 0;
        for (int i = 0; i < minutes.length; i++) {
            labels[i] = minutes[i] == 0 ? getString(R.string.player_sleep_off)
                    : getString(R.string.player_sleep_minutes, minutes[i]);
            if (minutes[i] == mSleepMinutes) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_sleep_timer)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    mPlayerView.removeCallbacks(mSleep);
                    mSleepMinutes = minutes[which];
                    if (mSleepMinutes > 0) {
                        mPlayerView.postDelayed(mSleep, mSleepMinutes * 60_000L);
                        Toast.makeText(this, getString(R.string.player_sleep_set, mSleepMinutes),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final Runnable mSleep = new Runnable() {
        @Override
        public void run() {
            mSleepMinutes = 0;
            if (mPlayer != null) {
                mPlayer.pause();
                Toast.makeText(PlayerActivity.this, R.string.player_sleep_paused, Toast.LENGTH_LONG).show();
            }
        }
    };

    /** The size, color and background chosen in Settings */
    private void styleSubtitles() {
        final SubtitleView view = mPlayerView.getSubtitleView();
        if (view == null) {
            return;
        }
        final Resources res = getResources();
        final String size = ViewerSettings.getChoice(this, ViewerSettings.KEY_SUBTITLE_SIZE,
                res.getStringArray(R.array.subtitle_size_values)[1]);
        final String color = ViewerSettings.getChoice(this, ViewerSettings.KEY_SUBTITLE_COLOR,
                res.getStringArray(R.array.subtitle_color_values)[0]);
        final String background = ViewerSettings.getChoice(this, ViewerSettings.KEY_SUBTITLE_BACKGROUND,
                res.getStringArray(R.array.subtitle_background_values)[0]);
        final int back = Color.parseColor(background);
        view.setFractionalTextSize(Float.parseFloat(size));
        view.setStyle(new CaptionStyleCompat(Color.parseColor(color), back, Color.TRANSPARENT,
                Color.alpha(back) == 0 ? CaptionStyleCompat.EDGE_TYPE_OUTLINE : CaptionStyleCompat.EDGE_TYPE_NONE,
                Color.BLACK, null));
    }

    /** The video, with the subtitle downloaded for it earlier, if any */
    private MediaItem mediaItem(Uri uri) {
        final File saved = mWatchKey == null ? null : Viewer.host().getSavedSubtitle(this, mWatchKey);
        final String name = fileName();
        final String title = Viewer.host().title(name);
        final MediaItem.Builder builder = new MediaItem.Builder().setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(TextUtils.isEmpty(title) ? name : title).build());
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
                .setLanguage(ViewerSettings.getSubtitleLanguage(this))
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();
    }

    /** The subtitle button shows for ExoPlayer videos the app knows as a movie or show */
    private void setUpSubtitleSearch() {
        final View button = mPlayerView.findViewById(R.id.player_subtitles);
        if (button == null) {
            return;
        }
        button.setVisibility(View.GONE);
        button.setOnClickListener(v -> showSubtitleMenu());
        if (mAudio) {
            return;
        }
        final String name = fileName();
        new Thread(() -> {
            if (Viewer.host().isRecognized(this, name)) {
                runOnUiThread(() -> button.setVisibility(View.VISIBLE));
            }
        }, "SubtitleMatch").start();
    }

    /** Subtitles off, each track in the video by language, and the download search when set up */
    private void showSubtitleMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Tracks.Group> groups = new ArrayList<>();
        int checked = 0;
        labels.add(getString(R.string.player_subtitle_off));
        groups.add(null);
        final boolean textOff = mPlayer.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        for (Tracks.Group group : mPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT || !group.isSupported()) {
                continue;
            }
            final Format format = group.getTrackFormat(0);
            String label = format.label;
            if (label == null || label.isEmpty()) {
                label = format.language == null || "und".equals(format.language)
                        ? getString(R.string.player_subtitle_track, groups.size())
                        : new Locale(format.language).getDisplayLanguage();
            }
            if (!textOff && group.isSelected()) {
                checked = labels.size();
            }
            labels.add(label);
            groups.add(group);
        }
        final boolean canSearch = mWatchKey != null && mPlayer instanceof ExoPlayer
                && Viewer.host().canFindSubtitles(this);
        if (canSearch) {
            labels.add(getString(R.string.player_subtitle_search));
        }
        final int searchIndex = labels.size() - 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_subtitles)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (canSearch && which == searchIndex) {
                        searchSubtitles();
                        return;
                    }
                    final Tracks.Group group = groups.get(which);
                    final TrackSelectionParameters.Builder params = mPlayer.getTrackSelectionParameters().buildUpon();
                    if (group == null) {
                        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                    } else {
                        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), 0));
                    }
                    mPlayer.setTrackSelectionParameters(params.build());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void searchSubtitles() {
        Toast.makeText(this, R.string.player_subtitle_searching, Toast.LENGTH_SHORT).show();
        final String name = fileName();
        new Thread(() -> {
            try {
                final List<? extends Subtitle> found = Viewer.host().findSubtitles(this, name);
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

    private void chooseSubtitle(List<? extends Subtitle> found, String name) {
        final String[] labels = new String[found.size()];
        for (int i = 0; i < labels.length; i++) {
            final Subtitle item = found.get(i);
            labels[i] = (item.release.isEmpty() ? name : item.release)
                    + " · " + item.language.toUpperCase(Locale.ROOT) + (item.hearingImpaired ? " · HI" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.player_subtitle_choose)
                .setItems(labels, (dialog, which) -> downloadSubtitle(found.get(which), name))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void downloadSubtitle(Subtitle subtitle, String name) {
        Toast.makeText(this, R.string.player_subtitle_downloading, Toast.LENGTH_SHORT).show();
        final String key = mWatchKey;
        new Thread(() -> {
            try {
                final File file = Viewer.host().downloadSubtitle(this, subtitle, key, name);
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
        mPlayer.setTrackSelectionParameters(mPlayer.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build());
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
                    Viewer.host().onEnded(PlayerActivity.this, mWatchKey, fileName());
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
        releaseSession();
        mPlayer.release();
        startVlc();
    }

    private void startVlc() {
        mPlayer = new VlcPlayer(this, getIntent().getData());
        mPlayer.addListener(mListener);
        // libVLC reports no tracks, so the black shutter would otherwise cover its picture
        mPlayerView.setShutterBackgroundColor(Color.TRANSPARENT);
        mPlayerView.setPlayer(mPlayer);
        styleSubtitles();
        setUpSubtitleSearch();
        setUpSleepTimer();
        attachSession();
        start();
    }

    private void start() {
        final long position = mWatchKey != null && ViewerSettings.isResumePlaybackEnabled(this)
                ? Viewer.host().getPosition(this, mWatchKey) : 0;
        if (position > 0) {
            mPlayer.seekTo(position);
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
            Viewer.host().onStopped(this, getIntent().getData(), getIntent().getType(), mWatchKey,
                    fileName(), mPlayer.getCurrentPosition(), mPlayer.getDuration());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mPlayerView.removeCallbacks(mHideControls);
        mSleepMinutes = 0;
        mPlayerView.removeCallbacks(mSleep);
        releaseSession();
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
