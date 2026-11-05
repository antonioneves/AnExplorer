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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.core.graphics.ColorUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import dev.dworks.apps.anexplorer.BuildConfig;

/**
 * Makes a small APK that Android TV's launcher shows as an app of its own and that opens the
 * chosen app. The stock launcher only lists apps with a leanback launcher activity, so a shortcut
 * has to be an app: this copies a template and swaps its package, label, icon and target.
 */
public final class ShortcutApkBuilder {

    private static final String SHORTCUT_PACKAGE_PREFIX = BuildConfig.APPLICATION_ID + ".shortcut.s";
    private static final String TEMPLATE_ASSET = "shortcut-template.apk";
    private static final String TEMPLATE_PACKAGE = "dev.dworks.apps.anexplorer.shortcuttemplate";
    private static final String TEMPLATE_LABEL = "__LABEL__";
    private static final String MANIFEST_ENTRY = "AndroidManifest.xml";
    private static final String TARGET_ENTRY = "assets/target.txt";

    private static final int ICON_SIZE = 192;
    private static final int BANNER_WIDTH = 320;
    private static final int BANNER_HEIGHT = 180;
    private static final int BANNER_BACKGROUND = 0xFF202124;
    private static final int BANNER_ICON_SIZE = 96;
    private static final int BANNER_ICON_LEFT = 20;
    private static final int BANNER_LABEL_GAP = 14;
    private static final float BANNER_LABEL_MAX_SIZE = 32f;
    private static final float BANNER_LABEL_MIN_SIZE = 18f;
    private static final int ADAPTIVE_LAYER_SIZE = 108;
    private static final int ADAPTIVE_SAFE_SIZE = 66;
    private static final int HUE_BUCKETS = 12;
    private static final float MIN_COLOR_WEIGHT = 8f;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ShortcutApkBuilder() {
    }

    /** A package name that belongs to this app, so no one else's shortcut can collide with it. */
    public static String shortcutPackage(String packageName) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(packageName.getBytes(StandardCharsets.UTF_8));
            final StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(HEX[(hash[i] >> 4) & 0x0F]).append(HEX[hash[i] & 0x0F]);
            }
            return SHORTCUT_PACKAGE_PREFIX + result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isShortcutPackage(String packageName) {
        return packageName.startsWith(SHORTCUT_PACKAGE_PREFIX);
    }

    public static File build(Context context, String packageName) throws Exception {
        final PackageManager packageManager = context.getPackageManager();
        final ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
        String label = appInfo.loadLabel(packageManager).toString().trim();
        if (TextUtils.isEmpty(label)) {
            label = packageName;
        }

        final String shortcutPackage = shortcutPackage(packageName);
        final File directory = new File(context.getCacheDir(), "shortcuts");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Can't create " + directory);
        }
        final File unsigned = new File(directory, shortcutPackage + ".unsigned");
        final File output = new File(directory, shortcutPackage + ".apk");

        final Bitmap icon = renderIcon(packageManager, appInfo);
        final byte[] iconPng;
        final byte[] bannerPng;
        try {
            iconPng = toPng(icon);
            bannerPng = toPng(renderBanner(packageManager, appInfo, label, icon));
        } finally {
            icon.recycle();
        }

        writeUnsignedApk(context, unsigned, shortcutPackage, label, packageName, iconPng, bannerPng);
        final ShortcutSigningKey.Material signingKey = ShortcutSigningKey.get(context);
        ApkSignerV1.sign(unsigned, output, signingKey.key, signingKey.certificate);
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
        return output;
    }

    private static void writeUnsignedApk(Context context, File output, String shortcutPackage,
                                         String label, String targetPackage, byte[] iconPng,
                                         byte[] bannerPng) throws Exception {
        final Map<String, String> replacements = new HashMap<>();
        replacements.put(TEMPLATE_PACKAGE, shortcutPackage);
        replacements.put(TEMPLATE_LABEL, label);

        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(
                context.getAssets().open(TEMPLATE_ASSET)));
             ZipOutputStream out = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(output)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                final String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/")
                        || name.equals(TARGET_ENTRY)) {
                    continue;
                }
                byte[] data = readEntry(in);
                if (name.equals(MANIFEST_ENTRY)) {
                    data = BinaryXmlEditor.replaceStrings(data, replacements);
                } else if (isTemplatePng(data, BANNER_WIDTH, BANNER_HEIGHT)) {
                    // The resource optimizer renames drawables, so the size tells them apart
                    data = bannerPng;
                } else if (isTemplatePng(data, ICON_SIZE, ICON_SIZE)) {
                    data = iconPng;
                }
                writeEntry(out, name, data, entry);
            }
            writeEntry(out, TARGET_ENTRY, targetPackage.getBytes(StandardCharsets.UTF_8), null);
        }
    }

    private static boolean isTemplatePng(byte[] data, int width, int height) {
        return data.length > 24
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G'
                && readInt(data, 16) == width
                && readInt(data, 20) == height;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static byte[] readEntry(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] data, ZipEntry original)
            throws IOException {
        final ZipEntry entry = new ZipEntry(name);
        entry.setTime(original != null ? original.getTime() : System.currentTimeMillis());
        entry.setMethod(original != null ? original.getMethod() : ZipEntry.DEFLATED);
        if (entry.getMethod() == ZipEntry.STORED) {
            final CRC32 crc = new CRC32();
            crc.update(data);
            entry.setSize(data.length);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static Bitmap renderIcon(PackageManager packageManager, ApplicationInfo appInfo) {
        final Drawable drawable = packageManager.getApplicationIcon(appInfo);
        final Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
        drawable.draw(canvas);
        return bitmap;
    }

    private static Bitmap renderBanner(PackageManager packageManager, ApplicationInfo appInfo,
                                       String label, Bitmap icon) {
        final Bitmap bitmap = Bitmap.createBitmap(BANNER_WIDTH, BANNER_HEIGHT,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(BANNER_BACKGROUND);

        final Drawable banner = appInfo.loadBanner(packageManager);
        if (banner != null) {
            drawCenterCrop(canvas, banner);
            return bitmap;
        }

        final Drawable appIcon = packageManager.getApplicationIcon(appInfo);
        if (!drawAdaptiveIcon(canvas, appIcon)) {
            drawIconColorBackground(canvas, icon);
            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            final int top = (BANNER_HEIGHT - BANNER_ICON_SIZE) / 2;
            canvas.drawBitmap(icon, null, new Rect(BANNER_ICON_LEFT, top,
                    BANNER_ICON_LEFT + BANNER_ICON_SIZE, top + BANNER_ICON_SIZE), paint);
        }
        drawLabel(canvas, bitmap, label);
        return bitmap;
    }

    /** Spreads the icon's background over the whole tile, so it looks made for the TV. */
    private static boolean drawAdaptiveIcon(Canvas canvas, Drawable appIcon) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !(appIcon instanceof AdaptiveIconDrawable)) {
            return false;
        }
        final Drawable background = ((AdaptiveIconDrawable) appIcon).getBackground();
        final Drawable foreground = ((AdaptiveIconDrawable) appIcon).getForeground();
        if (background == null || foreground == null) {
            return false;
        }
        final int top = (BANNER_HEIGHT - BANNER_WIDTH) / 2;
        background.setBounds(0, top, BANNER_WIDTH, top + BANNER_WIDTH);
        background.draw(canvas);

        // Only the middle of a layer is sure to show, so that part is what gets the icon's size
        final int size = BANNER_ICON_SIZE * ADAPTIVE_LAYER_SIZE / ADAPTIVE_SAFE_SIZE;
        final int left = BANNER_ICON_LEFT + (BANNER_ICON_SIZE - size) / 2;
        final int foregroundTop = (BANNER_HEIGHT - size) / 2;
        foreground.setBounds(left, foregroundTop, left + size, foregroundTop + size);
        foreground.draw(canvas);
        return true;
    }

    private static void drawIconColorBackground(Canvas canvas, Bitmap icon) {
        final int color = dominantColor(icon);
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.7f;
        final Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, 0, BANNER_WIDTH, BANNER_HEIGHT, color,
                Color.HSVToColor(hsv), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, BANNER_WIDTH, BANNER_HEIGHT, paint);
    }

    /** The icon's strongest colour, dimmed enough for the icon and the name to stand out on it. */
    private static int dominantColor(Bitmap icon) {
        final Bitmap small = Bitmap.createScaledBitmap(icon, 32, 32, true);
        final float[] weights = new float[HUE_BUCKETS];
        final float[] strongest = new float[HUE_BUCKETS];
        final int[] colors = new int[HUE_BUCKETS];
        final float[] hsv = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                final int pixel = small.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);
                final float weight = hsv[1] * hsv[2] * Color.alpha(pixel) / 255f;
                final int bucket = Math.min((int) (hsv[0] / 360f * HUE_BUCKETS), HUE_BUCKETS - 1);
                weights[bucket] += weight;
                if (weight > strongest[bucket]) {
                    strongest[bucket] = weight;
                    colors[bucket] = pixel;
                }
            }
        }
        small.recycle();

        int best = 0;
        for (int i = 1; i < HUE_BUCKETS; i++) {
            if (weights[i] > weights[best]) {
                best = i;
            }
        }
        // A black, white or grey icon has no colour to borrow
        if (weights[best] < MIN_COLOR_WEIGHT) {
            return BANNER_BACKGROUND;
        }
        Color.colorToHSV(colors[best], hsv);
        hsv[1] = Math.min(hsv[1], 0.85f);
        hsv[2] = Math.min(hsv[2], 0.6f);
        return Color.HSVToColor(hsv);
    }

    private static void drawLabel(Canvas canvas, Bitmap bitmap, String label) {
        final int left = BANNER_ICON_LEFT + BANNER_ICON_SIZE + BANNER_LABEL_GAP;
        final int width = BANNER_WIDTH - left - BANNER_LABEL_GAP;
        final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(labelColor(bitmap, left, width));
        paint.setTypeface(Typeface.DEFAULT_BOLD);

        StaticLayout layout = null;
        for (float size = BANNER_LABEL_MAX_SIZE; size >= BANNER_LABEL_MIN_SIZE; size -= 2) {
            paint.setTextSize(size);
            layout = StaticLayout.Builder.obtain(label, 0, label.length(), paint, width)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0, 0.95f)
                    .build();
            if (labelFits(layout, paint, label, width)) {
                break;
            }
        }
        canvas.save();
        canvas.translate(left, (BANNER_HEIGHT - layout.getHeight()) / 2f);
        layout.draw(canvas);
        canvas.restore();
    }

    /** Whether the whole name shows, with no word split across two lines. */
    private static boolean labelFits(StaticLayout layout, TextPaint paint, String label, int width) {
        if (layout.getEllipsisCount(layout.getLineCount() - 1) > 0) {
            return false;
        }
        for (String word : label.split("\\s+")) {
            if (paint.measureText(word) > width) {
                return false;
            }
        }
        return true;
    }

    private static int labelColor(Bitmap bitmap, int left, int width) {
        long red = 0, green = 0, blue = 0, count = 0;
        for (int y = 0; y < BANNER_HEIGHT; y += 6) {
            for (int x = left; x < left + width; x += 6) {
                final int pixel = bitmap.getPixel(x, y);
                red += Color.red(pixel);
                green += Color.green(pixel);
                blue += Color.blue(pixel);
                count++;
            }
        }
        final int average = Color.rgb((int) (red / count), (int) (green / count),
                (int) (blue / count));
        return ColorUtils.calculateContrast(Color.WHITE, average) >= 3 ? Color.WHITE : 0xFF202124;
    }

    private static void drawCenterCrop(Canvas canvas, Drawable drawable) {
        final int width = drawable.getIntrinsicWidth();
        final int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            drawable.setBounds(0, 0, BANNER_WIDTH, BANNER_HEIGHT);
        } else {
            final float scale = Math.max(BANNER_WIDTH / (float) width,
                    BANNER_HEIGHT / (float) height);
            final int scaledWidth = Math.round(width * scale);
            final int scaledHeight = Math.round(height * scale);
            drawable.setBounds((BANNER_WIDTH - scaledWidth) / 2,
                    (BANNER_HEIGHT - scaledHeight) / 2,
                    (BANNER_WIDTH + scaledWidth) / 2,
                    (BANNER_HEIGHT + scaledHeight) / 2);
        }
        drawable.draw(canvas);
    }

    private static byte[] toPng(Bitmap bitmap) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }
}
