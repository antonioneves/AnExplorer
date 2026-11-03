package dev.dworks.apps.anexplorer.misc;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.snackbar.Snackbar;
import dev.dworks.apps.anexplorer.R;

public class UtilsFlavour {

    public static void showInfo(Context context, int messageId){

    }

    public static void showMessage(Activity activity, String message,
                                   int duration, String action, View.OnClickListener listener){
        Snackbar snackbar = Snackbar.make(activity.findViewById(R.id.content_view), message, duration);
        if (null != listener) {
            snackbar.setAction(action, listener)
                    .setActionTextColor(ThemeColors.getAccentColor(activity));
        }
        hidePasteBarWhileShown(activity, snackbar);
        snackbar.show();
    }

    /** The message sits where it always does, so the paste bar steps aside instead of stacking with it */
    private static void hidePasteBarWhileShown(Activity activity, Snackbar snackbar) {
        final View bar = activity.findViewById(R.id.container_save);
        if (bar == null || bar.getVisibility() == View.GONE) {
            return;
        }
        bar.setVisibility(View.INVISIBLE);
        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar transientBottomBar, int event) {
                // A message that replaces this one takes over, and puts the bar back when it goes
                if (event != DISMISS_EVENT_CONSECUTIVE) {
                    bar.setVisibility(View.VISIBLE);
                }
            }
        });
    }
}
