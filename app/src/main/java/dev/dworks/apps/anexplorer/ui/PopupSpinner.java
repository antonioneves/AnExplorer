package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Spinner;

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

    @Override
    public boolean performClick() {
        if (mOnOpenListener == null) {
            return super.performClick();
        }
        mOnOpenListener.onOpen(this);
        return true;
    }
}
