package dev.dworks.apps.anexplorer;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Locale;

import dev.dworks.apps.anexplorer.misc.WatchHistory;

/**
 * Fallback for the files the built-in player can't read, such as AVI, WMV, APE or tracker modules.
 * It plays them with libVLC, which reads nearly every container.
 */
public class VlcPlayerActivity extends Activity {

    private static final int CONTROLS_TIMEOUT_MS = 4000;
    private static final int PROGRESS_INTERVAL_MS = 500;
    private static final int SEEK_STEP_MS = 10000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideControls = this::hideControls;
    private final Runnable mUpdateProgress = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            mHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    private VLCVideoLayout mVideoLayout;
    private LinearLayout mControls;
    private ProgressBar mProgress;
    private TextView mTime;
    private LibVLC mLibVlc;
    private MediaPlayer mPlayer;
    private ParcelFileDescriptor mFd;
    private String mWatchKey;
    private WatchHistory mHistory;
    private long mResumeAt;
    private boolean mEnded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        mVideoLayout = new VLCVideoLayout(this);
        root.addView(mVideoLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mControls = new LinearLayout(this);
        mControls.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (24 * getResources().getDisplayMetrics().density);
        mControls.setPadding(pad, pad, pad, pad);
        mControls.setBackgroundColor(0x99000000);
        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(1000);
        mTime = new TextView(this);
        mTime.setTextColor(Color.WHITE);
        mTime.setTextSize(16);
        mControls.addView(mProgress);
        mControls.addView(mTime);
        final FrameLayout.LayoutParams controls = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(mControls, controls);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        final Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        mHistory = new WatchHistory(this);
        mWatchKey = getIntent().getStringExtra(PlayerActivity.EXTRA_WATCH_KEY);
        mResumeAt = mWatchKey != null ? mHistory.getPosition(mWatchKey) : 0;
        mEnded = false;

        final ArrayList<String> options = new ArrayList<>();
        options.add("--http-reconnect");
        mLibVlc = new LibVLC(this, options);
        mPlayer = new MediaPlayer(mLibVlc);
        mPlayer.attachViews(mVideoLayout, null, false, false);
        mPlayer.setEventListener(this::onPlayerEvent);

        final Media media = openMedia(uri);
        if (media == null) {
            fail(null);
            return;
        }
        mPlayer.setMedia(media);
        media.release();
        mPlayer.play();
        showControls();
        mHandler.post(mUpdateProgress);
    }

    /** Content documents come in as a file descriptor, since libVLC can't open content URIs itself */
    private Media openMedia(Uri uri) {
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                mFd = getContentResolver().openFileDescriptor(uri, "r");
                return mFd == null ? null : new Media(mLibVlc, mFd.getFileDescriptor());
            }
            return new Media(mLibVlc, uri);
        } catch (Exception e) {
            Log.w("VlcPlayerActivity", "Could not open " + displayName(uri), e);
            return null;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
            // Not every provider answers; the last path segment is a fair stand-in
        }
        return uri.getLastPathSegment();
    }

    private void onPlayerEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                if (mResumeAt > 0) {
                    mPlayer.setTime(mResumeAt);
                    mResumeAt = 0;
                }
                break;
            case MediaPlayer.Event.EndReached:
                mEnded = true;
                if (mWatchKey != null) {
                    mHistory.markViewed(mWatchKey);
                }
                finish();
                break;
            case MediaPlayer.Event.EncounteredError:
                fail(null);
                break;
            default:
                break;
        }
    }

    private void fail(Exception e) {
        Log.w("VlcPlayerActivity", "Playback failed", e);
        Toast.makeText(this, R.string.toast_no_application, Toast.LENGTH_LONG).show();
        finish();
    }

    private void updateProgress() {
        if (mPlayer == null) {
            return;
        }
        final long length = mPlayer.getLength();
        final long time = mPlayer.getTime();
        mProgress.setProgress(length > 0 ? (int) (time * 1000 / length) : 0);
        mTime.setText(String.format(Locale.US, "%s / %s", format(time), format(length)));
    }

    private static String format(long ms) {
        final long s = Math.max(0, ms) / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void hideControls() {
        mControls.setVisibility(View.GONE);
    }

    private void showControls() {
        mControls.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideControls);
        mHandler.postDelayed(mHideControls, CONTROLS_TIMEOUT_MS);
    }

    private void seekBy(long delta) {
        final long length = mPlayer.getLength();
        final long target = Math.max(0, mPlayer.getTime() + delta);
        mPlayer.setTime(length > 0 ? Math.min(target, length - 1) : target);
    }

    /** Saved here, not in onStop, because the list behind resumes before the player stops and must see it */
    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayer != null && mWatchKey != null && !mEnded && mResumeAt == 0) {
            mHistory.savePosition(mWatchKey, mPlayer.getTime(), mPlayer.getLength());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacksAndMessages(null);
        if (mPlayer != null) {
            mPlayer.setEventListener(null);
            mPlayer.stop();
            mPlayer.detachViews();
            mPlayer.release();
            mPlayer = null;
        }
        if (mLibVlc != null) {
            mLibVlc.release();
            mLibVlc = null;
        }
        if (mFd != null) {
            try {
                mFd.close();
            } catch (Exception ignored) {
                // Nothing left to read from it
            }
            mFd = null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mPlayer == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (mPlayer.isPlaying()) {
                    mPlayer.pause();
                } else {
                    mPlayer.play();
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                mPlayer.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                mPlayer.pause();
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_STEP_MS);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_STEP_MS);
                break;
            case KeyEvent.KEYCODE_BACK:
                return super.dispatchKeyEvent(event);
            default:
                break;
        }
        showControls();
        return true;
    }
}
