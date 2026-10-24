package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * Takes the height it is given and picks its width from the image's proportions, so the whole
 * image shows without cropping. With no image it stays square.
 */
public class RatioImageView extends AppCompatImageView {
    // Panoramas would otherwise push every other item off screen
    private static final float MAX_RATIO = 2.4f;
    private static final float MIN_RATIO = 0.5f;

    public RatioImageView(Context context) {
        super(context);
    }

    public RatioImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        float ratio = 1f;
        final Drawable drawable = getDrawable();
        if (null != drawable && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0) {
            ratio = (float) drawable.getIntrinsicWidth() / drawable.getIntrinsicHeight();
            ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
        }
        setMeasuredDimension(View.resolveSize(Math.round(height * ratio), widthMeasureSpec), height);
    }
}
