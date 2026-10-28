package dev.dworks.apps.anexplorer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import dev.dworks.apps.anexplorer.misc.PreviewFormatter;
import dev.dworks.apps.anexplorer.ui.ZoomImageView;

/**
 * Previews images, PDFs, SVGs and plain-text style files inside the app, built for a remote:
 * arrows scroll or pan, OK zooms, BACK leaves. Loading runs off the main thread.
 */
public class PreviewActivity extends Activity {

    public static final String EXTRA_NAME = "preview_name";

    private static final int KIND_NONE = 0;
    private static final int KIND_IMAGE = 1;
    private static final int KIND_PDF = 2;
    private static final int KIND_SVG = 3;
    private static final int KIND_TEXT = 4;
    private static final int KIND_DOCUMENT = 5;

    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGE_SIDE = 2560;
    private static final String IMAGE_EXTENSIONS = "|png|jpg|jpeg|gif|webp|bmp|avif|heic|heif|";
    private static final String PLAIN_EXTENSIONS = "|txt|text|log|";
    private static final String TEXT_EXTENSIONS = "|txt|text|log|json|xml|yml|yaml|ini|md|markdown|csv|tsv"
            + "|js|mjs|cjs|jsx|tsx|py|rb|go|rs|java|kt|kts|swift|c|h|cpp|cc|hpp|cs|php|sh|bash|zsh"
            + "|ps1|bat|sql|css|scss|less|html|htm|vue|toml|gradle|dart|lua|r|pl|diff|patch|properties"
            + "|conf|cfg|env|csproj|plist|srt|vtt|nfo|";

    private FrameLayout mRoot;
    private ZoomImageView mImage;
    private ScrollView mScroll;
    private PdfRenderer mPdf;
    private File mPdfFile;
    private int mPage;
    private int mKind;
    private String mName;
    private volatile boolean mDestroyed;

    /** @return whether this preview can show the file, judged by mime type and then the name */
    public static boolean supports(String mimeType, String name) {
        return kindOf(mimeType, name) != KIND_NONE;
    }

    /**
     * Text with a text/* mime type still goes to the existing text viewer, so only the
     * formats that other apps usually mislabel are claimed here.
     */
    private static int kindOf(String mimeType, String name) {
        final String ext = extensionOf(name);
        if ("application/pdf".equals(mimeType) || "pdf".equals(ext)) {
            return KIND_PDF;
        }
        if ("svg".equals(ext) || "image/svg+xml".equals(mimeType)) {
            return KIND_SVG;
        }
        if (mimeType != null && mimeType.startsWith("image/") && !mimeType.contains("tiff")
                || IMAGE_EXTENSIONS.contains("|" + ext + "|")) {
            return KIND_IMAGE;
        }
        if (PreviewFormatter.isDocument(ext)) {
            return KIND_DOCUMENT;
        }
        if (mimeType != null && mimeType.startsWith("text/") && PLAIN_EXTENSIONS.contains("|" + ext + "|")) {
            return KIND_NONE;
        }
        if ("ts".equals(ext) && (mimeType == null || !mimeType.contains("typescript"))) {
            return KIND_NONE;
        }
        if (!ext.isEmpty() && TEXT_EXTENSIONS.contains("|" + ext + "|")
                || "application/json".equals(mimeType) || "application/xml".equals(mimeType)) {
            return KIND_TEXT;
        }
        return KIND_NONE;
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Color.BLACK);
        setContentView(mRoot);

        final Uri uri = getIntent().getData();
        mName = getIntent().getStringExtra(EXTRA_NAME);
        mKind = kindOf(getIntent().getType(), mName);
        if (uri == null || mKind == KIND_NONE) {
            finish();
            return;
        }
        final TextView loading = label(getString(R.string.preview_loading));
        mRoot.addView(loading);
        new Thread(() -> load(uri, loading), "preview-load").start();
    }

    private TextView label(String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        view.setGravity(Gravity.CENTER);
        view.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    private void load(Uri uri, TextView loading) {
        try {
            switch (mKind) {
                case KIND_IMAGE: {
                    final Bitmap bitmap = decodeImage(uri);
                    runOnUiThread(() -> showImage(loading, bitmap, false));
                    break;
                }
                case KIND_PDF: {
                    mPdfFile = copyToCache(uri);
                    mPdf = new PdfRenderer(ParcelFileDescriptor.open(mPdfFile, ParcelFileDescriptor.MODE_READ_ONLY));
                    final Bitmap page = renderPage(0);
                    runOnUiThread(() -> showImage(loading, page, true));
                    break;
                }
                case KIND_SVG: {
                    final String svg = new String(readBytes(uri, MAX_TEXT_BYTES), "UTF-8");
                    runOnUiThread(() -> showSvg(loading, svg));
                    break;
                }
                case KIND_DOCUMENT: {
                    final CharSequence text;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        text = PreviewFormatter.document(extensionOf(mName), in);
                    }
                    runOnUiThread(() -> showText(loading, text, false));
                    break;
                }
                default: {
                    final byte[] bytes = readBytes(uri, MAX_TEXT_BYTES);
                    final String raw = PreviewFormatter.decode(bytes);
                    final String ext = extensionOf(mName);
                    final CharSequence text;
                    if ("md".equals(ext) || "markdown".equals(ext)) {
                        text = PreviewFormatter.markdown(raw);
                    } else if ("csv".equals(ext) || "tsv".equals(ext)) {
                        text = PreviewFormatter.delimited(raw);
                    } else if (PreviewFormatter.isCode(mName)) {
                        text = PreviewFormatter.highlight(mName, raw);
                    } else {
                        text = raw;
                    }
                    runOnUiThread(() -> showText(loading, text, bytes.length >= MAX_TEXT_BYTES));
                    break;
                }
            }
        } catch (Throwable e) {
            Log.w("PreviewActivity", "Preview failed", e);
            runOnUiThread(() -> {
                if (!mDestroyed) {
                    Toast.makeText(this, R.string.preview_failed, Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }
    }

    private Bitmap decodeImage(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            final ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                final int side = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (side > MAX_IMAGE_SIDE) {
                    final float scale = (float) MAX_IMAGE_SIDE / side;
                    decoder.setTargetSize(Math.round(info.getSize().getWidth() * scale),
                            Math.round(info.getSize().getHeight() * scale));
                }
            });
        }
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_IMAGE_SIDE) {
            options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
        }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            final Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) {
                throw new IOException("Unsupported image");
            }
            return bitmap;
        }
    }

    /** PdfRenderer needs a seekable file, which network and cloud providers can't always give */
    private File copyToCache(Uri uri) throws IOException {
        final File file = new File(getCacheDir(), "preview.pdf");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(file)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while (in != null && (read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return file;
    }

    private byte[] readBytes(Uri uri, int limit) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while (in != null && out.size() < limit && (read = in.read(buffer)) > 0) {
                out.write(buffer, 0, Math.min(read, limit - out.size()));
            }
        }
        return out.toByteArray();
    }

    private synchronized Bitmap renderPage(int index) {
        final PdfRenderer.Page page = mPdf.openPage(index);
        try {
            final int width = 1600;
            final int height = Math.max(1, Math.round(width * (float) page.getHeight() / page.getWidth()));
            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } finally {
            page.close();
        }
    }

    private void showImage(TextView loading, Bitmap bitmap, boolean page) {
        if (mDestroyed) {
            return;
        }
        mRoot.removeView(loading);
        mImage = new ZoomImageView(this);
        mImage.setFocusable(true);
        mRoot.addView(mImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mImage.setBitmap(bitmap, page, false);
        mImage.requestFocus();
        if (page) {
            showPageNumber();
        }
    }

    private void showPageNumber() {
        Toast.makeText(this, getString(R.string.preview_page, mPage + 1, mPdf.getPageCount()),
                Toast.LENGTH_SHORT).show();
    }

    private void showSvg(TextView loading, String svg) {
        if (mDestroyed) {
            return;
        }
        mRoot.removeView(loading);
        final WebView web;
        try {
            web = new WebView(this);
        } catch (Throwable e) {
            Toast.makeText(this, R.string.preview_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        web.setBackgroundColor(Color.WHITE);
        web.getSettings().setJavaScriptEnabled(false);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setBuiltInZoomControls(false);
        web.loadDataWithBaseURL(null, svg, "image/svg+xml", "UTF-8", null);
        mRoot.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.requestFocus();
    }

    private void showText(TextView loading, CharSequence text, boolean truncated) {
        if (mDestroyed) {
            return;
        }
        mRoot.removeView(loading);
        final TextView view = new TextView(this);
        view.setText(truncated
                ? new SpannableStringBuilder(text).append("\n\n").append(getString(R.string.preview_truncated))
                : text);
        view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        final int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
                getResources().getDisplayMetrics()));
        view.setPadding(pad, pad, pad, pad);
        mScroll = new ScrollView(this);
        mScroll.setFocusable(true);
        mScroll.setSmoothScrollingEnabled(true);
        mScroll.addView(view);
        mRoot.addView(mScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mScroll.requestFocus();
    }

    /** Moves to another PDF page off the main thread; the old page stays up until the new one is ready */
    private void turnPage(int delta) {
        final int target = mPage + delta;
        if (mPdf == null || target < 0 || target >= mPdf.getPageCount()) {
            return;
        }
        mPage = target;
        new Thread(() -> {
            try {
                final Bitmap bitmap = renderPage(target);
                runOnUiThread(() -> {
                    if (!mDestroyed && target == mPage) {
                        mImage.setBitmap(bitmap, true, delta < 0);
                        showPageNumber();
                    }
                });
            } catch (Throwable e) {
                Log.w("PreviewActivity", "Page failed", e);
            }
        }, "preview-page").start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mImage != null) {
            final float step = mImage.panStep();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    mImage.toggleZoom();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!mImage.pan(0, -step) && mPdf != null) {
                        turnPage(1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (!mImage.pan(0, step) && mPdf != null) {
                        turnPage(-1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!mImage.pan(step, 0) && mPdf != null) {
                        turnPage(-1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!mImage.pan(-step, 0) && mPdf != null) {
                        turnPage(1);
                    }
                    return true;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    turnPage(1);
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    turnPage(-1);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        super.onDestroy();
        synchronized (this) {
            if (mPdf != null) {
                mPdf.close();
                mPdf = null;
            }
        }
        if (mPdfFile != null) {
            mPdfFile.delete();
        }
    }
}
