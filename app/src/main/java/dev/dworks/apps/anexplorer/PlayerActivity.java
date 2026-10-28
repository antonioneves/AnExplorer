package dev.dworks.apps.anexplorer;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Plays video and audio inside the app so it doesn't depend on another player being installed.
 * The ffmpeg renderers are preferred so formats the platform codecs lack still play.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    private static final int CONTROLS_TIMEOUT_MS = 4000;

    private PlayerView mPlayerView;
    private ExoPlayer mPlayer;
    private boolean mScrubbing;
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
        mPlayer = new ExoPlayer.Builder(this,
                new DefaultRenderersFactory(this)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
                .build();
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w("PlayerActivity", "Playback failed", error);
                Toast.makeText(PlayerActivity.this, R.string.toast_no_application, Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    finish();
                }
            }
        });
        mPlayerView.setPlayer(mPlayer);
        mPlayer.setMediaItem(MediaItem.fromUri(uri));
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
