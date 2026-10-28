package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

/**
 * A Spinner that hands its click to a listener instead of opening the stock dropdown,
 * so the app can show its own popup. Spinner itself refuses setOnClickListener.
 */
public class PopupSpinner extends Spinner {

    public interface OnOpenListener {
        void onOpen(PopupSpinner spinner);
    }

    private OnOpenListener mOnOpenListener;

    public PopupSpinner(Context context) {
        super(context);
    }

    public PopupSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PopupSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnOpenListener(OnOpenListener listener) {
        mOnOpenListener = listener;
    }

    /**
     * Spinner sizes itself to the widest item of the popup, which leaves a gap between
     * the selected title and the arrow, so it is sized to the selected title instead.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final SpinnerAdapter adapter = getAdapter();
        if (adapter != null && adapter.getCount() > 0
                && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            final View selected = adapter.getView(Math.max(getSelectedItemPosition(), 0), null, this);
            selected.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            final int width = selected.getMeasuredWidth() + getPaddingLeft() + getPaddingRight();
            if (width > 0 && width < getMeasuredWidth()) {
                setMeasuredDimension(width, getMeasuredHeight());
            }
        }
    }

    @Override
    public boolean performClick() {
        if (mOnOpenListener == null) {
            return super.performClick();
        }
        mOnOpenListener.onOpen(this);
        return true;
    }
}
