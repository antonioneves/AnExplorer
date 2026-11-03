package dev.dworks.apps.anexplorer.misc;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.snackbar.Snackbar;
import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.fragment.MoveFragment;

public class UtilsFlavour {

    public static void showInfo(Context context, int messageId){

    }

    public static void showMessage(Activity activity, String message,
                                   int duration, String action, View.OnClickListener listener){
        // One banner at the bottom: with the paste bar up, the message goes into it
        if (null == listener && activity instanceof DocumentsActivity) {
            final MoveFragment move = MoveFragment.get(activity.getFragmentManager());
            if (move != null && move.flash(message)) {
                return;
            }
        }
        Snackbar snackbar = Snackbar.make(activity.findViewById(R.id.content_view), message, duration);
        if (null != listener) {
            snackbar.setAction(action, listener)
                    .setActionTextColor(ThemeColors.getAccentColor(activity));
        }
        // Sit above the paste bar instead of covering it
        final View bar = activity.findViewById(R.id.container_save);
        if (bar != null && bar.getVisibility() == View.VISIBLE && bar instanceof ViewGroup
                && ((ViewGroup) bar).getChildCount() > 0) {
            snackbar.setAnchorView(bar);
        }
        snackbar.show();
    }
}