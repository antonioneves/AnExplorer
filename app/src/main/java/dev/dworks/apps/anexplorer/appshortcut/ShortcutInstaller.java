/*
 * Copyright (C) 2026 The AnExplorer Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.dworks.apps.anexplorer.appshortcut;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

import dev.dworks.apps.anexplorer.R;

/**
 * Installs a generated shortcut so that the TV launcher notices it. Google TV's launcher only
 * listens for new apps while it is on screen and doesn't reload its list when it comes back, so
 * the confirmation is shown over the launcher: accepting it returns there as the install finishes.
 */
public final class ShortcutInstaller extends BroadcastReceiver {

    private static WeakReference<Activity> sourceActivity = new WeakReference<>(null);

    public static void install(Activity activity, File apk, String shortcutPackage)
            throws IOException {
        sourceActivity = new WeakReference<>(activity);
        final Context context = activity.getApplicationContext();
        final PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(shortcutPackage);
        params.setSize(apk.length());
        final int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
            // Mutable because the system adds the result to this intent
            final PendingIntent result = PendingIntent.getBroadcast(context, sessionId,
                    new Intent(context, ShortcutInstaller.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(result.getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            final Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (null == confirm) {
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(confirm);
            // Only now: newer Android blocks the confirmation once this app has left the foreground
            final Activity activity = sourceActivity.get();
            if (null != activity) {
                activity.moveTaskToBack(true);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS
                && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, R.string.shortcut_add_failed, Toast.LENGTH_LONG).show();
        }
    }
}
