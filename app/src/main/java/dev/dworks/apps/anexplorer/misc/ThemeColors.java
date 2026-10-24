package dev.dworks.apps.anexplorer.misc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Window;

import androidx.core.view.WindowCompat;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
import com.google.android.material.color.MaterialColors;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.setting.AppSettings;

/**
 * Resolves the colors the app paints from code.
 *
 * On Android 12+ the whole Material 3 palette is generated from the user's primary color
 * (applied per activity in {@link #applyToActivity(Activity)}), and callers get the matching
 * color roles. Older versions can't override color resources at runtime, so they keep using
 * the user's picked colors directly.
 *
 * Always pass a themed (activity, view or dialog) context: the generated palette only exists
 * on activity resources, not on the application context.
 */
public final class ThemeColors {

    private ThemeColors() {
    }

    public static boolean isDynamic() {
        return DynamicColors.isDynamicColorAvailable();
    }

    /** Generates the activity's palette from the user's primary color; call before onCreate. */
    public static void applyToActivity(Activity activity) {
        if (!isDynamic()) {
            return;
        }
        DynamicColors.applyToActivityIfAvailable(activity, new DynamicColorsOptions.Builder()
                .setContentBasedSource(AppSettings.getPrimaryColor(activity))
                .build());
    }

    /** Background of the toolbar, drawer header and other branded bars. */
    public static int getAppBarColor(Context context) {
        if (!isDynamic()) {
            return AppSettings.getPrimaryColor(context);
        }
        return MaterialColors.getColor(context, R.attr.colorPrimaryContainer,
                AppSettings.getPrimaryColor(context));
    }

    /** App bar color the given candidate primary color would produce, for live previews. */
    public static int getAppBarPreviewColor(Context context, int primaryColor) {
        if (!isDynamic()) {
            return primaryColor;
        }
        return MaterialColors.getColorRoles(context, primaryColor).getAccentContainer();
    }

    /** Text and icon color on top of {@link #getAppBarColor(Context)}. */
    public static int getOnAppBarColor(Context context) {
        if (!isDynamic()) {
            return Color.WHITE;
        }
        return MaterialColors.getColor(context, R.attr.colorOnPrimaryContainer, Color.WHITE);
    }

    /** Filled shapes that carry a white glyph, e.g. the folder and root icon circles. */
    // The tone calculation below is slow (~4ms on a TV) and runs for every file row; the
    // result only changes with the user's color
    private static int sPrimarySeed;
    private static int sPrimaryAccent;
    private static boolean sPrimaryCached;

    public static int getPrimaryColor(Context context) {
        if (!isDynamic()) {
            return AppSettings.getPrimaryColor(context);
        }
        final int seed = AppSettings.getPrimaryColor(context);
        synchronized (ThemeColors.class) {
            if (!sPrimaryCached || seed != sPrimarySeed) {
                // Tone 40 of the user's color: the light scheme's colorPrimary, and dark enough
                // for a white glyph in dark mode too (the dark scheme's roles are too light)
                sPrimaryAccent = MaterialColors.getColorRoles(seed, true).getAccent();
                sPrimarySeed = seed;
                sPrimaryCached = true;
            }
            return sPrimaryAccent;
        }
    }

    /** Lines and text drawn directly on a surface, e.g. storage progress bars. */
    public static int getOnSurfacePrimaryColor(Context context) {
        if (!isDynamic()) {
            return AppSettings.getPrimaryColor(context);
        }
        return MaterialColors.getColor(context, R.attr.colorPrimary,
                AppSettings.getPrimaryColor(context));
    }

    /** The user's accent, harmonized with the generated palette on Android 12+. */
    public static int getAccentColor(Context context) {
        int accent = AppSettings.getAccentColor();
        if (!isDynamic()) {
            return accent;
        }
        return MaterialColors.harmonizeWithPrimary(context, accent);
    }

    /** Colors the status bar like the app bar, with icons that stay readable on it. */
    public static void setUpStatusBar(Activity activity) {
        int color = isDynamic()
                ? getAppBarColor(activity)
                : Utils.getStatusBarColor(AppSettings.getPrimaryColor(activity));
        Window window = activity.getWindow();
        window.setStatusBarColor(color);
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightStatusBars(MaterialColors.isColorLight(color));
    }
}
