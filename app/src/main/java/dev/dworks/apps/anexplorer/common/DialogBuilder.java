package dev.dworks.apps.anexplorer.common;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

public class DialogBuilder extends DialogCommonBuilder {

    public DialogBuilder(Context context) {
        super(context);
    }

    @Override
    public void showDialog() {
        show();
    }

    @Override
    public void show() {
        super.show();
    }

    @Override
    public Dialog create() {
        return super.create();
    }

    /** The remote's or keyboard's Done/Enter in a name field presses OK, instead of typing a new line. */
    public static void submitOnDone(EditText field, Dialog dialog) {
        field.setOnEditorActionListener((v, actionId, event) -> {
            final boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId != EditorInfo.IME_ACTION_DONE && !enter) {
                return false;
            }
            if (event == null || event.getAction() == KeyEvent.ACTION_UP) {
                ((AlertDialog) dialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            }
            return true;
        });
    }
}
