package dev.dworks.apps.anexplorer.ui;

import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * How a text field behaves with a TV remote. The remote's left and right move focus out of the
 * field instead of walking the cursor letter by letter, and the cursor waits at the end when the
 * field gets focus. With the keyboard open, its own keys still move the cursor.
 */
public final class RemoteText {

    private RemoteText() {
    }

    /** True for a left or right from the remote while the keyboard is closed */
    public static boolean isRemoteMove(View field, int keyCode, KeyEvent event) {
        // Keys sent by the keyboard itself still move the cursor: it repeats them until the cursor
        // moves, so swallowing them loops forever
        return (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                && (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == 0 && !isKeyboardShown(field);
    }

    /** Moves focus the way the key points, if there is somewhere to go */
    public static void moveFocus(View field, int keyCode) {
        final View next = field.focusSearch(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
        if (next != null) {
            next.requestFocus();
        }
    }

    public static void selectEnd(EditText field) {
        field.setSelection(field.length());
    }

    public static boolean isKeyboardShown(View field) {
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(field);
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
    }
}
