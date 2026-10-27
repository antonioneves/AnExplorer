package dev.dworks.apps.anexplorer.fragment;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
        return view;
    }
}
