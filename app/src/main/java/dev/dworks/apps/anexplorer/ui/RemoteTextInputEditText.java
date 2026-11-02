package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;

import com.google.android.material.textfield.TextInputEditText;

/** A Material text field that follows {@link RemoteText} */
public class RemoteTextInputEditText extends TextInputEditText {

    public RemoteTextInputEditText(Context context) {
        super(context);
    }

    public RemoteTextInputEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RemoteTextInputEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (RemoteText.isRemoteMove(this, keyCode, event)) {
            RemoteText.moveFocus(this, keyCode);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return RemoteText.isRemoteMove(this, keyCode, event) || super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (focused) {
            RemoteText.selectEnd(this);
        }
    }
}
