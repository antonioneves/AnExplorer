package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.content.res.TypedArray;

import androidx.annotation.DimenRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.ArrayList;

import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;
import dev.dworks.apps.anexplorer.R;

public class RecyclerViewPlus extends RecyclerViewCompat {
    public static final int TYPE_LIST = 0;
    public static final int TYPE_GRID = 1;
    public static final int TYPE_GALLERY = 2;
    public static final int TYPE_CUSTOM = 3;
    private final int spanCount;

    private RecyclerView.LayoutManager layoutManager;
    private int mType = TYPE_LIST;
    private int columnWidth = -1;
    private Context mContext;
    private boolean mTrapFocusAtEnd = true;
    private int mFocusPosition = NO_POSITION;
    private boolean mHasListFocus;

    public RecyclerViewPlus(Context context) {
        this(context, null);
    }

    public RecyclerViewPlus(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecyclerViewPlus(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mContext = context;
        final TypedArray attributes = context.getTheme().obtainStyledAttributes(attrs, R.styleable.RecyclerViewPlus,
                defStyle, 0);
        mType = attributes.getInt(R.styleable.RecyclerViewPlus_type, TYPE_LIST);
        columnWidth = attributes.getDimensionPixelSize(R.styleable.RecyclerViewPlus_columnWidth, -1);
        spanCount = attributes.getInt(R.styleable.RecyclerViewPlus_span, 1);
        attributes.recycle();
        setType(mType);
    }

    public void setType(int type){
        // A new layout manager throws away every laid-out row, so only swap it when the type changes
        if (type == mType && null != layoutManager) {
            return;
        }
        mType = type;
        updateLayoutManager();
    }

    /** Changes the width each grid column aims for, so a screen can show smaller or larger cards */
    public void setColumnWidth(int width) {
        if (columnWidth != width) {
            columnWidth = width;
            requestLayout();
        }
    }

    private void updateLayoutManager(){
        switch (mType){
            case TYPE_LIST:
                layoutManager =
                        new LinearLayoutManagerCompat(mContext, LinearLayoutManager.VERTICAL, false);
                break;
            case TYPE_GRID:
                layoutManager = new GridLayoutManager(mContext, 1);
                break;
            case TYPE_GALLERY:
                layoutManager =
                        new LinearLayoutManagerCompat(mContext, LinearLayoutManager.HORIZONTAL, false);
                break;
            case TYPE_CUSTOM:
                layoutManager = new GridLayoutManager(mContext, spanCount);
                break;
            default:
                layoutManager =
                        new LinearLayoutManagerCompat(mContext, LinearLayoutManager.VERTICAL, false);
                break;
        }

        if (layoutManager instanceof LinearLayoutManager) {
            // Hand the rows to a shared view pool when this list goes away, so the next folder
            // can reuse them instead of inflating new ones
            ((LinearLayoutManager) layoutManager).setRecycleChildrenOnDetach(true);
        }
        setLayoutManager(layoutManager);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);

        switch (mType){
            case TYPE_GRID:
                if (columnWidth > 0) {
                    //The spanCount will always be at least 1
                    int spanCount = Math.max(1, getMeasuredWidth() / columnWidth);
                    ((GridLayoutManager)layoutManager).setSpanCount(spanCount);
                }
                break;
        }
    }

    /**
     * Scrolling fast with the key held can recycle the focused row before the next one takes the
     * focus. The window is then left with no focus and gives it to the first view it finds, in
     * the sidebar, so the focus is handed back to the row it was on.
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener mFocusListener = (oldFocus, newFocus) -> {
        if (newFocus != null && isDescendant(newFocus)) {
            return;
        }
        if (oldFocus == null && newFocus != null && mHasListFocus) {
            restoreFocus();
        } else {
            mHasListFocus = false;
        }
    };

    private void restoreFocus() {
        final int count = getAdapter() == null ? 0 : getAdapter().getItemCount();
        if (count == 0 || mFocusPosition == NO_POSITION) {
            return;
        }
        final int target = Math.min(mFocusPosition, count - 1);
        post(() -> {
            final ViewHolder holder = findViewHolderForAdapterPosition(target);
            if (holder != null) {
                holder.itemView.requestFocus();
                return;
            }
            scrollToPosition(target);
            post(() -> {
                final ViewHolder late = findViewHolderForAdapterPosition(target);
                if (late != null) {
                    late.itemView.requestFocus();
                }
            });
        });
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        final int position = getChildAdapterPosition(child);
        if (position != NO_POSITION) {
            mFocusPosition = position;
        }
        mHasListFocus = true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusListener);
        super.onDetachedFromWindow();
    }

    /**
     * DOWN from the last row has nowhere to go inside the list, and the focus finder would
     * otherwise pick the nearest view below it, often in the sidebar. A view under the list
     * itself, like the paste bar, is still reachable.
     */
    @Override
    public View focusSearch(View focused, int direction) {
        // Only a view inside the list reaches here, even if fast scrolling has already recycled it
        final View next = super.focusSearch(focused, direction);
        if (mTrapFocusAtEnd && direction == View.FOCUS_DOWN && isVertical()
                && (next == null || !isDescendant(next))) {
            final View bar = findBottomBarButton();
            return bar != null ? bar : focused;
        }
        return next;
    }

    /** The paste or save bar under the list, which the focus finder would skip for the sidebar */
    private View findBottomBarButton() {
        final View bar = getRootView().findViewById(R.id.container_save);
        if (bar == null || bar.getVisibility() != View.VISIBLE) {
            return null;
        }
        final ArrayList<View> buttons = new ArrayList<>();
        bar.addFocusables(buttons, View.FOCUS_DOWN);
        return buttons.isEmpty() ? null : buttons.get(buttons.size() - 1);
    }

    /** A nested list lets focus leave at its end, instead of holding it on the last row */
    public void setTrapFocusAtEnd(boolean trap) {
        mTrapFocusAtEnd = trap;
    }

    private boolean isVertical() {
        return layoutManager instanceof LinearLayoutManager
                && ((LinearLayoutManager) layoutManager).getOrientation() == LinearLayoutManager.VERTICAL;
    }

    private boolean isDescendant(View view) {
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == this) {
                return true;
            }
        }
        return false;
    }
}