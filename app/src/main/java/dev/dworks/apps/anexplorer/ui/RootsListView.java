package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ExpandableListView;

/**
 * The sidebar list. Focus coming in from the content lands on the current root, where
 * ListView would pick the row nearest the tile that had focus, often a section header.
 */
public class RootsListView extends ExpandableListView {

    public RootsListView(Context context) {
        super(context);
    }

    public RootsListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        final int checked = getCheckedItemPosition();
        if (!gainFocus || null == previouslyFocusedRect || checked == INVALID_POSITION) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
            return;
        }
        super.onFocusChanged(true, direction, null);
        final View row = getChildAt(checked - getFirstVisiblePosition());
        setSelectionFromTop(checked, null != row ? row.getTop() : 0);
    }
}
