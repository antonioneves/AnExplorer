package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A frame as wide as its first child, the thumbnail; the views drawn over it, like a long file
 * name, never widen it.
 */
public class ThumbFrameLayout extends FrameLayout {

    public ThumbFrameLayout(Context context) {
        super(context);
    }

    public ThumbFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ThumbFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (getChildCount() > 0) {
            final View thumb = getChildAt(0);
            measureChildWithMargins(thumb, widthMeasureSpec, 0, heightMeasureSpec, 0);
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(thumb.getMeasuredWidth()
                    + getPaddingLeft() + getPaddingRight(), MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
