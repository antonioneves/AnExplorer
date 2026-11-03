package dev.dworks.apps.anexplorer.misc;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Asks GitHub whether a release newer than the installed one is published, and downloads it */
public class UpdateChecker {

    private static final String LATEST = "https://api.github.com/repos/antonioneves/AnExplorer/releases/latest";

    /** A published release and the APK that fits this device */
    public static class Release {
        public final String version;
        public final String notes;
        public final String apkName;
        public final String apkUrl;

        Release(String version, String notes, String apkName, String apkUrl) {
            this.version = version.replaceFirst("^[vV]", "");
            this.notes = notes;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
        }
    }

    public interface Listener {
        /** On the main thread: the newer release, or null when the installed one is the latest */
        void onChecked(Release newer);

        /** On the main thread */
        void onFailed();
    }

    public static void check(String installedVersion, Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                final Release latest = fetch();
                final Release newer = latest != null && isNewer(latest.version, installedVersion) ? latest : null;
                main.post(() -> listener.onChecked(newer));
            } catch (IOException | JSONException e) {
                main.post(listener::onFailed);
            }
        }, "UpdateChecker").start();
    }

    /** Null when nothing is published yet */
    private static Release fetch() throws IOException, JSONException {
        final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
        try (Response response = client.newCall(new Request.Builder().url(LATEST)
                .header("Accept", "application/vnd.github+json").build()).execute()) {
            if (response.code() == 404) {
                return null;
            }
            if (!response.isSuccessful()) {
                throw new IOException("GitHub answered " + response.code());
            }
            return parse(new JSONObject(response.body().string()), Build.SUPPORTED_ABIS);
        }
    }

    static Release parse(JSONObject json, String[] abis) {
        final JSONArray assets = json.optJSONArray("assets");
        String name = null;
        String url = null;
        // The build for this device's CPU first, then the universal one, then any APK
        final String[] wanted = new String[abis.length + 1];
        System.arraycopy(abis, 0, wanted, 0, abis.length);
        wanted[abis.length] = "universal";
        for (int i = 0; assets != null && i <= wanted.length && url == null; i++) {
            for (int a = 0; a < assets.length() && url == null; a++) {
                final JSONObject asset = assets.optJSONObject(a);
                final String assetName = asset == null ? "" : asset.optString("name");
                if (assetName.endsWith(".apk") && (i == wanted.length || assetName.contains(wanted[i]))) {
                    name = assetName;
                    url = asset.optString("browser_download_url");
                }
            }
        }
        return new Release(json.optString("tag_name"), json.optString("body"), name, url);
    }

    /** Compares dotted numbers, so "v4.10.0" is newer than "4.9.0"; a tag that isn't one is never newer */
    static boolean isNewer(String candidate, String installed) {
        final int[] a = numbers(candidate);
        final int[] b = numbers(installed);
        if (a == null || b == null) {
            return false;
        }
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            final int x = i < a.length ? a[i] : 0;
            final int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] numbers(String version) {
        final String[] parts = version.replaceFirst("^[vV]", "").split("\\.");
        final int[] result = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                result[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return result;
    }

    /** Into Downloads, where the system shows the progress; opening the APK there installs it */
    public static void download(Context context, Release release) {
        final DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        manager.enqueue(new DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle(release.apkName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, release.apkName));
    }
}
