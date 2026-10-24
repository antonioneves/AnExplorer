package dev.dworks.apps.anexplorer.misc;

import android.app.Activity;
import android.content.Context;
import android.view.View;

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
        snackbar.show();
    }
}