package dev.dworks.apps.anexplorer.shortcuttemplate;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Launches the app named in assets/target.txt. This APK is a copy of the template for one
 * app, made by AnExplorer, so a launcher treats it as an app of its own.
 */
public final class ShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String packageName = readTarget();
        final Intent launch = findLaunchIntent(packageName);
        if (launch == null) {
            Toast.makeText(this, R.string.shortcut_app_missing, Toast.LENGTH_LONG).show();
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.shortcut_app_missing, Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    private Intent findLaunchIntent(String packageName) {
        if (packageName == null) {
            return null;
        }
        final PackageManager manager = getPackageManager();
        Intent intent = manager.getLeanbackLaunchIntentForPackage(packageName);
        if (intent == null) {
            intent = manager.getLaunchIntentForPackage(packageName);
        }
        return intent;
    }

    private String readTarget() {
        try (InputStream in = getAssets().open("target.txt")) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[256];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
