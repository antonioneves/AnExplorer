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
     * DOWN from the last row has nowhere to go inside the list, and the focus finder would
     * otherwise pick the nearest view below it, often in the sidebar
     */
    @Override
    public View focusSearch(View focused, int direction) {
        final View next = super.focusSearch(focused, direction);
        if (direction == View.FOCUS_DOWN && isVertical() && focused != null
                && isDescendant(focused) && (next == null || !isDescendant(next))) {
            return focused;
        }
        return next;
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