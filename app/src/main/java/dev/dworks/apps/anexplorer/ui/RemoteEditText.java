package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.appcompat.widget.AppCompatEditText;

/** A text field that follows {@link RemoteText} */
public class RemoteEditText extends AppCompatEditText {

    public RemoteEditText(Context context) {
        super(context);
    }

    public RemoteEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RemoteEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (RemoteText.isRemoteMove(this, keyCode, event)) {
            RemoteText.moveFocus(this, keyCode);
            return true;
        }
        if (RemoteText.isRemoteVertical(this, keyCode, event)) {
            return false;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (RemoteText.isRemoteVertical(this, keyCode, event)) {
            return false;
        }
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
