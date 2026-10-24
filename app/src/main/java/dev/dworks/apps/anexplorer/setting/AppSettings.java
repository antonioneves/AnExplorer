/*
 * Copyright (C) 2014 Hari Krishna Dulipudi
 * Copyright (C) 2013 The Android Open Source Project
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

package dev.dworks.apps.anexplorer.setting;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.PreferenceManager;
import androidx.core.content.ContextCompat;
import android.text.TextUtils;

import dev.dworks.apps.anexplorer.BaseActivity.State;
import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.AnalyticsManager;
import dev.dworks.apps.anexplorer.misc.PreferenceUtils;

/** The app's settings, read and written in the default shared preferences; edited in {@link SettingsFragment}. */
public class AppSettings {

    public static final String TAG = "Settings";

    public static final String KEY_ADVANCED_DEVICES = "advancedDevices";
    public static final String KEY_FILE_SIZE = "fileSize";
    public static final String KEY_FOLDER_SIZE = "folderSize";
    public static final String KEY_FILE_THUMBNAIL = "fileThumbnail";
    public static final String KEY_FILE_HIDDEN = "fileHidden";
    private static final String KEY_PIN = "pin";
    public static final String KEY_PIN_ENABLED = "pin_enable";
    public static final String KEY_PIN_SET = "pin_set";
    public static final String KEY_PRIMARY_COLOR = "primaryColor";
    public static final String KEY_ACCENT_COLOR = "accentColor";
    public static final String KEY_THEME_STYLE = "themeStyle";
    public static final String KEY_FOLDER_ANIMATIONS = "folderAnimations";
    public static final String KEY_RECENT_MEDIA = "recentMedia";
    public static final String KEY_VIEW_MODE = "viewMode";

    private AppSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean getDisplayAdvancedDevices(Context context) {
        return prefs(context).getBoolean(KEY_ADVANCED_DEVICES, true);
    }

    public static boolean getDisplayFileSize(Context context) {
        return prefs(context).getBoolean(KEY_FILE_SIZE, true);
    }

    public static boolean getDisplayFolderSize(Context context) {
        return prefs(context).getBoolean(KEY_FOLDER_SIZE, false);
    }

    public static boolean getDisplayFileThumbnail(Context context) {
        return prefs(context).getBoolean(KEY_FILE_THUMBNAIL, true);
    }

    public static boolean getDisplayFileHidden(Context context) {
        return prefs(context).getBoolean(KEY_FILE_HIDDEN, false);
    }

    public static boolean getDisplayRecentMedia() {
        return prefs(DocumentsApplication.getInstance().getBaseContext())
                .getBoolean(KEY_RECENT_MEDIA, true);
    }

    /** Grid or list, the same in every folder */
    public static int getViewMode(Context context) {
        return prefs(context).getInt(KEY_VIEW_MODE, State.MODE_GRID);
    }

    public static void setViewMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_VIEW_MODE, mode).apply();
    }

    public static int getPrimaryColor(Context context) {
        int newColor = ContextCompat.getColor(context, R.color.primaryColor);
        return prefs(context).getInt(KEY_PRIMARY_COLOR, newColor);
    }

    public static int getPrimaryColor() {
        return prefs(DocumentsApplication.getInstance().getBaseContext())
                .getInt(KEY_PRIMARY_COLOR, Color.parseColor("#0288D1"));
    }

    public static void setPrimaryColor(int color) {
        prefs(DocumentsApplication.getInstance().getBaseContext()).edit()
                .putInt(KEY_PRIMARY_COLOR, color).commit();
    }

    public static int getAccentColor() {
        return prefs(DocumentsApplication.getInstance().getBaseContext())
                .getInt(KEY_ACCENT_COLOR, Color.parseColor("#EF3A0F"));
    }

    public static void setAccentColor(int color) {
        prefs(DocumentsApplication.getInstance().getBaseContext()).edit()
                .putInt(KEY_ACCENT_COLOR, color).commit();
    }

    public static String getThemeStyle() {
        return prefs(DocumentsApplication.getInstance().getBaseContext())
                .getString(KEY_THEME_STYLE, "1");
    }

    public static void setThemeStyle(int style) {
        PreferenceUtils.set(KEY_THEME_STYLE, String.valueOf(style));
    }

    public static boolean getFolderAnimation(Context context) {
        return prefs(context).getBoolean(KEY_FOLDER_ANIMATIONS,
                context.getResources().getBoolean(R.bool.folder_animations_default));
    }

    public static boolean isPinEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PIN_ENABLED, false) && isPinProtected(context);
    }

    public static boolean isPinProtected(Context context) {
        return !TextUtils.isEmpty(prefs(context).getString(KEY_PIN, ""));
    }

    public static void setPin(Context context, String pin) {
        prefs(context).edit().putString(KEY_PIN, pin).commit();
    }

    public static boolean checkPin(Context context, String pin) {
        String saved = prefs(context).getString(KEY_PIN, "");
        if (TextUtils.isEmpty(pin))
            return TextUtils.isEmpty(saved);
        return pin.equals(saved);
    }

    public static void logSettingEvent(String key) {
        AnalyticsManager.logEvent("settings_" + key.toLowerCase());
    }
}
