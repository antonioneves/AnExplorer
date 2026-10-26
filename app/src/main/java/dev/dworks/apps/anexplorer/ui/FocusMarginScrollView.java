package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

import dev.dworks.apps.anexplorer.misc.Utils;

/**
 * A ScrollView that scrolls the focused row a little past its edge, so the row's whole focus
 * ring shows instead of being cut by the top or bottom of the list.
 */
public class FocusMarginScrollView extends ScrollView {

    private final int mMargin = Utils.dpToPx(8);

    public FocusMarginScrollView(Context context) {
        super(context);
    }

    public FocusMarginScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        final Rect padded = new Rect(rect);
        padded.inset(0, -mMargin);
        return super.computeScrollDeltaToGetChildRectOnScreen(padded);
    }
}
