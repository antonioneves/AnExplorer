package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Shows one bitmap that the remote can zoom and pan. The D-pad moves the image, and a move that
 * is already at the edge is reported back so pages can turn.
 */
public class ZoomImageView extends View {

    private static final float[] ZOOM_STEPS = {1f, 2f, 4f};
    /** The desk colour around a page, the same grey the Word previews use */
    private static final int DESK = 0xFF303030;
    private static final float PAGE_MARGIN_X = 0.12f;
    private static final float PAGE_MARGIN_Y = 0.04f;

    private final Matrix mMatrix = new Matrix();
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap mBitmap;
    private boolean mFitWidth;
    private int mZoomStep;
    private float mScale;
    private float mTx;
    private float mTy;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** @param fitWidth true for pages, which sit on a grey desk with margins and scroll down, false to fit whole */
    public void setBitmap(Bitmap bitmap, boolean fitWidth, boolean startAtBottom) {
        mBitmap = bitmap;
        mFitWidth = fitWidth;
        mZoomStep = 0;
        resetPosition(startAtBottom);
        invalidate();
    }

    private float baseScale() {
        if (mBitmap == null || getWidth() == 0) {
            return 1f;
        }
        final float w = (float) (getWidth() - 2 * insetX()) / mBitmap.getWidth();
        final float h = (float) getHeight() / mBitmap.getHeight();
        return mFitWidth ? w : Math.min(w, h);
    }

    private float insetX() {
        return mFitWidth ? getWidth() * PAGE_MARGIN_X : 0;
    }

    private float insetY() {
        return mFitWidth ? getHeight() * PAGE_MARGIN_Y : 0;
    }

    private void resetPosition(boolean startAtBottom) {
        if (mBitmap == null) {
            return;
        }
        mScale = baseScale() * ZOOM_STEPS[mZoomStep];
        mTx = (getWidth() - mBitmap.getWidth() * mScale) / 2f;
        mTy = (getHeight() - mBitmap.getHeight() * mScale) / 2f;
        if (mBitmap.getHeight() * mScale + 2 * insetY() > getHeight()) {
            mTy = startAtBottom ? getHeight() - mBitmap.getHeight() * mScale - insetY() : insetY();
        }
        clamp();
    }

    private void clamp() {
        final float w = mBitmap.getWidth() * mScale;
        final float h = mBitmap.getHeight() * mScale;
        final float ix = insetX();
        final float iy = insetY();
        mTx = w + 2 * ix <= getWidth() ? (getWidth() - w) / 2f
                : Math.max(getWidth() - w - ix, Math.min(ix, mTx));
        mTy = h + 2 * iy <= getHeight() ? (getHeight() - h) / 2f
                : Math.max(getHeight() - h - iy, Math.min(iy, mTy));
    }

    /** Steps through fit, 2x and 4x, keeping the middle of the screen where it was */
    public void toggleZoom() {
        if (mBitmap == null) {
            return;
        }
        final float cx = (getWidth() / 2f - mTx) / mScale;
        final float cy = (getHeight() / 2f - mTy) / mScale;
        mZoomStep = (mZoomStep + 1) % ZOOM_STEPS.length;
        mScale = baseScale() * ZOOM_STEPS[mZoomStep];
        mTx = getWidth() / 2f - cx * mScale;
        mTy = getHeight() / 2f - cy * mScale;
        clamp();
        invalidate();
    }

    /** @return false when the image was already at that edge and did not move */
    public boolean pan(float dx, float dy) {
        if (mBitmap == null) {
            return false;
        }
        final float oldX = mTx;
        final float oldY = mTy;
        mTx += dx;
        mTy += dy;
        clamp();
        invalidate();
        return oldX != mTx || oldY != mTy;
    }

    public float panStep() {
        return getWidth() / 4f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetPosition(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mBitmap == null) {
            return;
        }
        if (mFitWidth) {
            canvas.drawColor(DESK);
        }
        mMatrix.setScale(mScale, mScale);
        mMatrix.postTranslate(mTx, mTy);
        canvas.drawBitmap(mBitmap, mMatrix, mPaint);
    }
}
