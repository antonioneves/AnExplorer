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

    /**
     * Moves focus the way the key points, if there is somewhere to go. The search can hand back a
     * view that isn't beside the field (it wraps to another row), so only a view on the same row
     * and on that side is taken.
     */
    public static void moveFocus(View field, int keyCode) {
        final boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        final View next = field.focusSearch(left ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
        if (next == null || next == field) {
            return;
        }
        final int[] from = new int[2];
        final int[] to = new int[2];
        field.getLocationOnScreen(from);
        next.getLocationOnScreen(to);
        final int fromCenter = from[0] + field.getWidth() / 2;
        final int toCenter = to[0] + next.getWidth() / 2;
        final boolean sameRow = to[1] < from[1] + field.getHeight() && to[1] + next.getHeight() > from[1];
        if (sameRow && (left ? toCenter < fromCenter : toCenter > fromCenter)) {
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
