package dev.dworks.apps.anexplorer.fragment;

import android.app.DownloadManager;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import dev.dworks.apps.anexplorer.BuildConfig;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.Snackbars;
import dev.dworks.apps.anexplorer.misc.UpdateChecker;
import dev.dworks.apps.anexplorer.misc.Utils;

/** About, shown in place of the file list like Settings, with the sidebar still beside it. */
public class AboutFragment extends Fragment {
    public static final String TAG = "AboutFragment";

    private boolean mChecking;

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
        ((TextView) view.findViewById(R.id.about_version)).setText(getString(R.string.about_version,
                getString(R.string.app_ver), BuildConfig.VERSION_NAME));
        final View check = view.findViewById(R.id.about_check_update);
        check.setOnClickListener(v -> checkForUpdate((TextView) v));
        return view;
    }

    /** Asks GitHub for a newer release, and offers to download it into Downloads */
    private void checkForUpdate(TextView button) {
        // Left enabled so the D-pad focus stays on it while it checks
        if (mChecking) {
            return;
        }
        mChecking = true;
        button.setText(R.string.pref_update_checking);
        UpdateChecker.check(BuildConfig.VERSION_NAME, new UpdateChecker.Listener() {
            @Override
            public void onChecked(UpdateChecker.Release newer) {
                reset();
                if (!isAdded()) {
                    return;
                }
                if (newer == null) {
                    showSnackbar(R.string.pref_update_latest);
                } else {
                    showUpdate(newer);
                }
            }

            @Override
            public void onFailed() {
                reset();
                if (isAdded()) {
                    showSnackbar(R.string.pref_update_failed);
                }
            }

            private void reset() {
                mChecking = false;
                button.setText(R.string.pref_check_update);
            }
        });
    }

    private void showUpdate(UpdateChecker.Release release) {
        final Context context = getActivity();
        final MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.pref_update_available, release.version))
                .setNegativeButton(android.R.string.cancel, null);
        if (release.apkUrl == null) {
            dialog.setMessage(R.string.pref_update_no_apk);
        } else {
            dialog.setMessage(TextUtils.isEmpty(release.notes) ? getString(R.string.pref_update_message)
                    : release.notes)
                    .setPositiveButton(R.string.pref_update_download, (d, which) -> {
                        final long id = UpdateChecker.download(context, release);
                        showSnackbar(R.string.pref_update_downloading);
                        offerInstall(context, release, id);
                    });
        }
        dialog.show();
    }

    /** Once the APK is downloaded, asks whether to install it, so nobody has to find it in Downloads */
    private void offerInstall(Context context, UpdateChecker.Release release, long id) {
        final Context app = context.getApplicationContext();
        final BroadcastReceiver done = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) {
                    return;
                }
                app.unregisterReceiver(this);
                if (!isAdded()) {
                    return;
                }
                new MaterialAlertDialogBuilder(getActivity())
                        .setTitle(getString(R.string.pref_update_downloaded, release.version))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.pref_update_install,
                                (d, which) -> UpdateChecker.install(app, id))
                        .show();
            }
        };
        ContextCompat.registerReceiver(app, done,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED);
    }

    private void showSnackbar(int msg) {
        if (!Utils.isActivityAlive(getActivity())) {
            return;
        }
        // On the content pane, so the banner doesn't cover the sidebar
        Snackbars.makeSnackbar(getActivity(), msg, Snackbar.LENGTH_SHORT).show();
    }
}
