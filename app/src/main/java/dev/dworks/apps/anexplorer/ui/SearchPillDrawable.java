package dev.dworks.apps.anexplorer.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import com.google.android.material.shape.MaterialShapeDrawable;

/**
 * Rounded pill background that can shrink to a circle at its end. Changing it only redraws,
 * unlike swapping inset drawables, which relayouts the toolbar on every frame.
 */
public class SearchPillDrawable extends Drawable {
    private final MaterialShapeDrawable mShape;
    private final int mHeight;
    private final int mStartInset;
    private final int mEndInset;
    private float mCollapsed;

    public SearchPillDrawable(MaterialShapeDrawable shape, int height, int startInset, int endInset) {
        mShape = shape;
        mHeight = height;
        mStartInset = startInset;
        mEndInset = endInset;
    }

    public void setCollapsed(float collapsed) {
        mCollapsed = collapsed;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect bounds = getBounds();
        final int top = bounds.centerY() - mHeight / 2;
        final int bottom = top + mHeight;
        final int right = bounds.right - mEndInset;
        // Fully collapsed it is a circle as wide as it is tall
        final int collapsedLeft = right - (bottom - top);
        final int start = bounds.left + mStartInset;
        final int left = (int) (start + (collapsedLeft - start) * mCollapsed);
        mShape.setBounds(left, top, right, bottom);
        mShape.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        mShape.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mShape.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
