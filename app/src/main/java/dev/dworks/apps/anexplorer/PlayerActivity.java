package dev.dworks.apps.anexplorer;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import dev.dworks.apps.anexplorer.misc.VlcPlayer;
import dev.dworks.apps.anexplorer.misc.WatchHistory;

/**
 * Plays video and audio inside the app so it doesn't depend on another player being installed.
 * The ffmpeg renderers are preferred so formats the platform codecs lack still play.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    /** Document id of the video, so the episode cards can show how far it got */
    public static final String EXTRA_WATCH_KEY = "watch_key";

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
    private boolean mScrubbing;
    private boolean mEnded;
    private String mWatchKey;
    private WatchHistory mHistory;
    private final Runnable mHideControls = this::fadeOutControls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mPlayerView = new PlayerView(this);
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
        setContentView(mPlayerView);
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
        mPlayer.setMediaItem(MediaItem.fromUri(uri));
        start();
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
                    mHistory.markViewed(mWatchKey);
                }
                finish();
            }
        }
    };

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
        if (mWatchKey != null && mHistory.getPosition(mWatchKey) > 0) {
            mPlayer.seekTo(mHistory.getPosition(mWatchKey));
        }
        mPlayer.prepare();
        mPlayer.play();
        mPlayerView.requestFocus();
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
        mPlayerView.postDelayed(mHideControls, CONTROLS_TIMEOUT_MS);
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
            mHistory.savePosition(mWatchKey, mPlayer.getCurrentPosition(), mPlayer.getDuration());
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
