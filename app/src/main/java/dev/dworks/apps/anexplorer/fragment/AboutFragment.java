package dev.dworks.apps.anexplorer.fragment;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;

import dev.dworks.apps.anexplorer.R;

/** About, shown in place of the file list like Settings, with the sidebar still beside it. */
public class AboutFragment extends Fragment {
    public static final String TAG = "AboutFragment";

    public static void show(FragmentManager fm) {
        // Kept when already there, so a recreated activity keeps it as it was
        if (fm.findFragmentByTag(TAG) != null) {
            return;
        }
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, new AboutFragment(), TAG);
        ft.commitAllowingStateLoss();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_about, container, false);
        ((TextView) view.findViewById(R.id.about_version)).setText(getString(R.string.app_ver));
        // The logo is pale grey, which nearly disappears on the light background
        final boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (!night) {
            ((ImageView) view.findViewById(R.id.about_logo)).setColorFilter(
                    MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary),
                    PorterDuff.Mode.SRC_IN);
        }
        return view;
    }
}
