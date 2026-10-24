package dev.dworks.apps.anexplorer.common;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.AnalyticsManager;
import dev.dworks.apps.anexplorer.misc.PermissionUtil;
import dev.dworks.apps.anexplorer.misc.ThemeColors;
import dev.dworks.apps.anexplorer.misc.Utils;

/**
 * Created by HaKr on 18-Oct-14.
 */
public abstract class ActionBarActivity extends BaseCommonActivity {

    public static final int REQUEST_LOCAL_NETWORK = 48;

    private Runnable mPendingLocalNetworkAction;
    private Runnable mPendingLocalNetworkDenied;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Utils.changeThemeStyle(getDelegate());
        super.onCreate(savedInstanceState);
        ThemeColors.applyToActivity(this);
    }

    @Override
    public ActionBar getSupportActionBar() {
        return super.getSupportActionBar();
    }

    @Override
    public void recreate() {
        Utils.changeThemeStyle(getDelegate());
        super.recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AnalyticsManager.setCurrentScreen(this, getTag());
    }

    /** Runs the action once local network access is granted, asking for it if needed. */
    public void runWithLocalNetwork(Runnable action) {
        runWithLocalNetwork(action, null);
    }

    /**
     * Like {@link #runWithLocalNetwork(Runnable)}, running {@code onDenied} (if given) when the
     * user refuses. Only one request is in flight at a time; a newer call replaces the actions.
     */
    public void runWithLocalNetwork(Runnable action, @Nullable Runnable onDenied) {
        if (PermissionUtil.hasLocalNetworkPermission(this)) {
            action.run();
            return;
        }
        final boolean requestPending = null != mPendingLocalNetworkAction;
        mPendingLocalNetworkAction = action;
        mPendingLocalNetworkDenied = onDenied;
        if (!requestPending) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_LOCAL_NETWORK}, REQUEST_LOCAL_NETWORK);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCAL_NETWORK) {
            return;
        }
        final Runnable action = mPendingLocalNetworkAction;
        final Runnable onDenied = mPendingLocalNetworkDenied;
        mPendingLocalNetworkAction = null;
        mPendingLocalNetworkDenied = null;
        if (grantResults.length == 0) {
            // The request was interrupted, not answered
            return;
        }
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (null != action) {
                action.run();
            }
        } else {
            Toast.makeText(this, R.string.local_network_permission_needed, Toast.LENGTH_LONG).show();
            if (null != onDenied) {
                onDenied.run();
            }
        }
    }

    public abstract String getTag();
}
