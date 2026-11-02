package dev.dworks.apps.anexplorer.misc;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.Collections;

/**
 * libVLC behind the media3 {@link Player} interface, so the same player screen and controls work
 * for the files ExoPlayer can't read.
 */
@UnstableApi
public final class VlcPlayer extends SimpleBasePlayer {

    private static final String TAG = "VlcPlayer";
    private static final Commands COMMANDS = new Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_RELEASE).build();

    private final Context mContext;
    private final Uri mUri;
    private final MediaItem mMediaItem;
    private final LibVLC mLibVlc;
    private final MediaPlayer mPlayer;
    private ParcelFileDescriptor mFd;
    private SurfaceView mSurface;
    private final View.OnLayoutChangeListener mLayoutListener = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            mPlayer.getVLCVout().setWindowSize(r - l, b - t);
        }
    };
    private boolean mPlayWhenReady;
    private boolean mStarted;
    private boolean mReleased;
    private long mLength = C.TIME_UNSET;
    private int mPlaybackState = Player.STATE_IDLE;
    private long mPendingSeek = C.TIME_UNSET;
    private PlaybackException mError;
    @Nullable
    private MediaMetadata mMetadata;

    public VlcPlayer(Context context, Uri uri) {
        super(Looper.getMainLooper());
        mContext = context.getApplicationContext();
        mUri = uri;
        mMediaItem = MediaItem.fromUri(uri);
        final ArrayList<String> options = new ArrayList<>();
        options.add("--http-reconnect");
        mLibVlc = new LibVLC(mContext, options);
        mPlayer = new MediaPlayer(mLibVlc);
        mPlayer.setEventListener(this::onEvent);
    }

    /** libVLC sends buffering events by the dozen, so only a real change may rebuild the state */
    private void onEvent(MediaPlayer.Event event) {
        int state = mPlaybackState;
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                state = Player.STATE_BUFFERING;
                break;
            case MediaPlayer.Event.Buffering:
                state = event.getBuffering() >= 100f ? Player.STATE_READY : Player.STATE_BUFFERING;
                break;
            case MediaPlayer.Event.Playing:
                if (mPendingSeek != C.TIME_UNSET) {
                    mPlayer.setTime(mPendingSeek);
                    mPendingSeek = C.TIME_UNSET;
                }
                if (!mStarted) {
                    readMetadata();
                    invalidateState();
                }
                mStarted = true;
                state = Player.STATE_READY;
                break;
            case MediaPlayer.Event.Paused:
                state = Player.STATE_READY;
                break;
            case MediaPlayer.Event.EndReached:
                state = Player.STATE_ENDED;
                break;
            case MediaPlayer.Event.EncounteredError:
                mError = new PlaybackException("libVLC could not play the file", null,
                        PlaybackException.ERROR_CODE_UNSPECIFIED);
                state = Player.STATE_IDLE;
                break;
            case MediaPlayer.Event.LengthChanged:
                invalidateState();
                return;
            default:
                return;
        }
        if (state != mPlaybackState || mError != null) {
            mPlaybackState = state;
            invalidateState();
        }
    }

    /** libVLC has the file's tags once it is playing it */
    private void readMetadata() {
        final IMedia media = mPlayer.getMedia();
        if (media == null) {
            return;
        }
        // A file with no title tag gets its location as the title, which for us is "fd://129"
        final String title = media.getMeta(IMedia.Meta.Title, true);
        mMetadata = new MediaMetadata.Builder()
                .setTitle(title != null && title.startsWith("fd://") ? null : title)
                .setArtist(media.getMeta(IMedia.Meta.Artist, true))
                .setAlbumTitle(media.getMeta(IMedia.Meta.Album, true))
                .build();
        media.release();
    }

    @Override
    protected State getState() {
        if (!mReleased) {
            final long length = mPlayer.getLength();
            mLength = length > 0 ? length : C.TIME_UNSET;
        }
        final MediaItemData item = new MediaItemData.Builder(mUri)
                .setMediaItem(mMediaItem)
                .setMediaMetadata(mMetadata)
                .setDurationUs(mLength != C.TIME_UNSET ? mLength * 1000 : C.TIME_UNSET)
                .setIsSeekable(true)
                .build();
        return new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(mPlayWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(mPlaybackState)
                .setPlayerError(mError)
                .setPlaylist(Collections.singletonList(item))
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs(() -> mPendingSeek != C.TIME_UNSET ? mPendingSeek
                        : mReleased ? 0 : Math.max(0, mPlayer.getTime()))
                .setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(15000)
                .build();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        final Media media = openMedia();
        if (media == null) {
            mError = new PlaybackException("Could not open " + mUri, null,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            mPlaybackState = Player.STATE_IDLE;
            return Futures.immediateVoidFuture();
        }
        mPlayer.setMedia(media);
        media.release();
        mPlaybackState = Player.STATE_BUFFERING;
        if (mPlayWhenReady) {
            mPlayer.play();
        }
        return Futures.immediateVoidFuture();
    }

    /** Content documents come in as a file descriptor, since libVLC can't open content URIs itself */
    @Nullable
    private Media openMedia() {
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(mUri.getScheme())) {
                mFd = mContext.getContentResolver().openFileDescriptor(mUri, "r");
                return mFd == null ? null : new Media(mLibVlc, mFd.getFileDescriptor());
            }
            return new Media(mLibVlc, mUri);
        } catch (Exception e) {
            Log.w(TAG, "Could not open " + mUri, e);
            return null;
        }
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        mPlayWhenReady = playWhenReady;
        if (mPlaybackState != Player.STATE_IDLE) {
            if (playWhenReady) {
                if (mPlaybackState == Player.STATE_ENDED) {
                    mPlayer.setTime(0);
                }
                mPlayer.play();
            } else {
                mPlayer.pause();
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (!mStarted) {
            mPendingSeek = positionMs;
        } else {
            mPlayer.setTime(positionMs);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        mPlayer.stop();
        mPlaybackState = Player.STATE_IDLE;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        if (videoOutput instanceof SurfaceView) {
            detachSurface();
            mSurface = (SurfaceView) videoOutput;
            final IVLCVout vout = mPlayer.getVLCVout();
            vout.setVideoView(mSurface);
            vout.attachViews();
            mPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            mSurface.addOnLayoutChangeListener(mLayoutListener);
            if (mSurface.getWidth() > 0) {
                vout.setWindowSize(mSurface.getWidth(), mSurface.getHeight());
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        detachSurface();
        return Futures.immediateVoidFuture();
    }

    private void detachSurface() {
        if (mSurface != null) {
            mSurface.removeOnLayoutChangeListener(mLayoutListener);
            mPlayer.getVLCVout().detachViews();
            mSurface = null;
        }
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        mReleased = true;
        mPlayer.setEventListener(null);
        mPlayer.stop();
        detachSurface();
        mPlayer.release();
        mLibVlc.release();
        if (mFd != null) {
            try {
                mFd.close();
            } catch (Exception ignored) {
                // Nothing left to read from it
            }
            mFd = null;
        }
        return Futures.immediateVoidFuture();
    }
}
