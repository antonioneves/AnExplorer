package dev.dworks.apps.anexplorer.viewer;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
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
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.graphics.drawable.GradientDrawable;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

import com.google.android.material.color.MaterialColors;


/**
 * Previews images, PDFs, SVGs and plain-text style files inside the app, built for a remote:
 * arrows scroll or pan, OK zooms, BACK leaves. Opening a photo from a folder makes left and
 * right step through the folder's other photos. Loading runs off the main thread.
 */
public class PreviewActivity extends Activity {

    public static final String EXTRA_NAME = "preview_name";
    public static final String EXTRA_DOCUMENT_ID = "preview_document_id";

    private static final int KIND_NONE = 0;
    private static final int KIND_IMAGE = 1;
    private static final int KIND_PDF = 2;
    private static final int KIND_SVG = 3;
    private static final int KIND_TEXT = 4;
    private static final int KIND_DOCUMENT = 5;
    private static final int KIND_TIFF = 6;
    private static final int KIND_OFFICE = 7;

    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TIFF_BYTES = 48 * 1024 * 1024;
    private static final int MAX_IMAGE_SIDE = 2560;
    /** How long the photo or page counter stays up, as long as a short toast */
    private static final int COUNTER_MILLIS = 2000;
    /** How long the slideshow bar stays up without a key press */
    private static final int BAR_MILLIS = 6000;
    private static final int FADE_MILLIS = 300;
    private static final int[] SLIDESHOW_SECONDS = {3, 5, 10, 30};
    private static final String IMAGE_EXTENSIONS = "|png|jpg|jpeg|gif|webp|bmp|avif|heic|heif|";
    private static final String PLAIN_EXTENSIONS = "|txt|text|log|";
    private static final String TEXT_EXTENSIONS = "|txt|text|log|json|xml|yml|yaml|ini|md|markdown|csv|tsv"
            + "|js|mjs|cjs|jsx|tsx|py|rb|go|rs|java|kt|kts|swift|c|h|cpp|cc|hpp|cs|php|sh|bash|zsh"
            + "|ps1|bat|sql|css|scss|less|html|htm|vue|toml|gradle|dart|lua|r|pl|diff|patch|properties"
            + "|conf|cfg|env|csproj|plist|srt|vtt|nfo|";

    private FrameLayout mRoot;
    private ZoomImageView mImage;
    /** The folder's images and where the one on screen sits, or null when this isn't a slideshow */
    private List<Uri> mImages;
    private int mImageIndex;
    /** The photo or page counter, drawn over the preview and replaced rather than queued */
    private TextView mCounter;
    /** Whether the photos advance by themselves, and the bar that controls it */
    private boolean mPlaying;
    private boolean mAutoplay;
    private LinearLayout mBar;
    private TextView mPlayButton, mIntervalButton;
    private WebView mWeb;
    private String mSlideshow;
    private int mSlides, mSlide, mStep;
    /** Whether the page being shown plays the effects of the click just made */
    private boolean mPlay;
    private ScrollView mScroll;
    private PdfRenderer mPdf;
    private File mPdfFile;
    private int mPage;
    private int mKind;
    private String mName;
    private volatile boolean mDestroyed;

    /** Images waiting for the preview that was opened for them, handed over in memory */
    private static Slideshow sPending;

    /** The images of the folder an image was opened from, so the remote can step through them */
    private static final class Slideshow {
        /** The image that was clicked; a preview opened any other way doesn't take these */
        final String documentId;
        final List<Uri> images;
        final int index;
        /** Whether it starts playing, as when it is started from the file's menu */
        final boolean play;

        Slideshow(String documentId, List<Uri> images, int index, boolean play) {
            this.documentId = documentId;
            this.images = images;
            this.index = index;
            this.play = play;
        }
    }

    /** @return whether this preview can show the file, judged by mime type and then the name */
    public static boolean supports(String mimeType, String name) {
        return kindOf(mimeType, name) != KIND_NONE;
    }

    /** @return whether the file can be one step of an image slideshow, so SVG and TIFF can't */
    public static boolean isImage(String mimeType, String name) {
        return kindOf(mimeType, name) == KIND_IMAGE;
    }

    /**
     * Hands the preview the folder's images in the order the list shows them, so the remote can
     * step through them. They travel in memory, as a folder of photos doesn't fit in an Intent.
     */
    public static void setSlideshow(String documentId, List<Uri> images, int index, boolean play) {
        sPending = new Slideshow(documentId, images, index, play);
    }

    /**
     * Takes the images waiting for this document, if any. Whatever was waiting is dropped, so a
     * preview opened later from somewhere else never inherits another folder's images.
     */
    private static Slideshow takeSlideshow(String documentId) {
        final Slideshow slideshow = sPending;
        sPending = null;
        return slideshow != null && slideshow.documentId.equals(documentId) ? slideshow : null;
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
        if ("tif".equals(ext) || "tiff".equals(ext) || mimeType != null && mimeType.contains("tiff")) {
            return KIND_TIFF;
        }
        if (mimeType != null && mimeType.startsWith("image/")
                || IMAGE_EXTENSIONS.contains("|" + ext + "|")) {
            return KIND_IMAGE;
        }
        if (OfficeHtml.supports(ext)) {
            return KIND_OFFICE;
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
        final Slideshow slideshow = takeSlideshow(getIntent().getStringExtra(EXTRA_DOCUMENT_ID));
        if (slideshow != null && mKind == KIND_IMAGE) {
            mImages = slideshow.images;
            mImageIndex = slideshow.index;
            mAutoplay = slideshow.play;
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
                case KIND_TIFF: {
                    final Bitmap bitmap = TiffDecoder.decode(readBytes(uri, MAX_TIFF_BYTES), MAX_IMAGE_SIDE);
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
                    runOnUiThread(() -> showWeb(loading, svg, "image/svg+xml"));
                    break;
                }
                case KIND_OFFICE: {
                    final String page;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        page = OfficeHtml.page(extensionOf(mName), in);
                    }
                    runOnUiThread(() -> showWeb(loading, page, "text/html"));
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
        if (mImage == null) {
            mImage = new ZoomImageView(this);
            mImage.setFocusable(true);
            mRoot.addView(mImage, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        mImage.setBitmap(bitmap, page, false);
        mImage.requestFocus();
        if (page) {
            showPageNumber();
        } else if (mImages != null && mImages.size() > 1) {
            if (mAutoplay) {
                mAutoplay = false;
                setPlaying(true);
            }
            // Say there are more, so the remote's right key gets a try
            showImagePosition();
        }
    }

    private void showImagePosition() {
        showCounter(getString(R.string.preview_image_position, mImageIndex + 1, mImages.size()));
    }

    private void showPageNumber() {
        showCounter(getString(R.string.preview_page, mPage + 1, mPdf.getPageCount()));
    }

    /**
     * Shows the counter over the preview, with the app's own folder beside it. A Toast carries
     * the launcher icon, whose blue tile is all that shows at that size.
     */
    private void showCounter(String text) {
        if (mCounter == null) {
            // The app's surface colours, so the counter sits on either theme like the rest of
            // the UI; the app's folder is drawn as it is beside its name
            final Context themed = new ContextThemeWrapper(this, R.style.ViewerTheme);
            final int surface = MaterialColors.getColor(themed, R.attr.colorSurfaceContainerHigh, Color.DKGRAY);
            final int onSurface = MaterialColors.getColor(themed, R.attr.colorOnSurface, Color.WHITE);
            final TypedValue icon = new TypedValue();
            final boolean hasIcon = themed.getTheme().resolveAttribute(R.attr.viewerCounterIcon, icon, true);

            mCounter = new TextView(this);
            mCounter.setCompoundDrawablesRelativeWithIntrinsicBounds(hasIcon ? icon.resourceId : 0, 0, 0, 0);
            mCounter.setCompoundDrawablePadding(dp(10));
            mCounter.setTextColor(onSurface);
            mCounter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            mCounter.setGravity(Gravity.CENTER_VERTICAL);
            mCounter.setPadding(dp(16), dp(8), dp(20), dp(8));
            mCounter.setBackgroundResource(R.drawable.preview_counter_background);
            mCounter.setBackgroundTintList(ColorStateList.valueOf(surface));
            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            params.bottomMargin = dp(36);
            mRoot.addView(mCounter, params);
        }
        mCounter.setText(text);
        mCounter.setVisibility(View.VISIBLE);
        mCounter.removeCallbacks(mHideCounter);
        mCounter.postDelayed(mHideCounter, COUNTER_MILLIS);
    }

    private final Runnable mHideCounter = () -> {
        if (mCounter != null) {
            mCounter.setVisibility(View.GONE);
        }
    };

    /** The app's colours, so the bar sits on either theme like the counter */
    private int themeColor(int attr, int fallback) {
        return MaterialColors.getColor(new ContextThemeWrapper(this, R.style.ViewerTheme), attr, fallback);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void showWeb(TextView loading, String content, String mimeType) {
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
        mSlides = "text/html".equals(mimeType) ? OfficeHtml.slideCount(content) : 0;
        if (mSlides > 0) {
            mSlideshow = content;
            mSlide = 1;
            mWeb = web;
            content = slideHtml();
        }
        web.loadDataWithBaseURL(null, content, mimeType, "UTF-8", null);
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

    /**
     * Moves to another photo of the folder off the main thread; the old one stays up until the new
     * one is ready. The slideshow wraps round at the ends, the remote's keys stop there.
     */
    private void turnImage(int delta, boolean wrap) {
        if (mImages == null) {
            return;
        }
        final int count = mImages.size();
        final int target = wrap ? Math.floorMod(mImageIndex + delta, count) : mImageIndex + delta;
        if (target < 0 || target >= count) {
            return;
        }
        mImageIndex = target;
        final Uri uri = mImages.get(target);
        new Thread(() -> {
            try {
                final Bitmap bitmap = decodeImage(uri);
                runOnUiThread(() -> {
                    if (!mDestroyed && target == mImageIndex) {
                        mImage.setBitmap(bitmap, false, false);
                        if (mPlaying) {
                            mImage.setAlpha(0f);
                            mImage.animate().alpha(1f).setDuration(FADE_MILLIS).start();
                        }
                        showImagePosition();
                        scheduleAdvance();
                    }
                });
            } catch (Throwable e) {
                // The next press or tick moves on, so one unreadable photo can't strand the slideshow
                Log.w("PreviewActivity", "Image failed", e);
                runOnUiThread(() -> {
                    if (!mDestroyed && target == mImageIndex) {
                        scheduleAdvance();
                    }
                });
            }
        }, "preview-image").start();
    }

    private final Runnable mAdvance = () -> turnImage(1, true);

    private void scheduleAdvance() {
        mRoot.removeCallbacks(mAdvance);
        if (mPlaying) {
            mRoot.postDelayed(mAdvance, ViewerSettings.getSlideshowSeconds(this) * 1000L);
        }
    }

    private void setPlaying(boolean playing) {
        if (mImages == null || mPlaying == playing) {
            return;
        }
        mPlaying = playing;
        // A slideshow on a TV is left running, so the screen must not turn off under it
        mRoot.setKeepScreenOn(playing);
        if (mPlayButton != null) {
            mPlayButton.setText(playing ? R.string.slideshow_pause : R.string.slideshow_play);
        }
        showCounter(getString(playing ? R.string.slideshow_playing : R.string.slideshow_paused));
        scheduleAdvance();
    }

    /** Back first closes the slideshow bar, then leaves */
    @Override
    public void onBackPressed() {
        if (isBarShown()) {
            hideBar();
        } else {
            super.onBackPressed();
        }
    }

    private boolean isBarShown() {
        return mBar != null && mBar.getVisibility() == View.VISIBLE;
    }

    /** The bar takes the remote's focus, so left, right and OK work its buttons */
    private void showBar() {
        if (mBar == null) {
            mBar = new LinearLayout(this);
            mBar.setOrientation(LinearLayout.HORIZONTAL);
            mBar.setGravity(Gravity.CENTER_VERTICAL);
            mBar.setPadding(dp(8), dp(8), dp(8), dp(8));
            final GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(32));
            background.setColor(themeColor(R.attr.colorSurfaceContainerHigh, Color.DKGRAY));
            mBar.setBackground(background);
            mPlayButton = barButton(mPlaying ? R.string.slideshow_pause : R.string.slideshow_play,
                    v -> {
                        setPlaying(!mPlaying);
                        scheduleBarHide();
                    });
            mIntervalButton = barButton(0, v -> {
                ViewerSettings.setSlideshowSeconds(this, nextSlideshowSeconds());
                updateIntervalButton();
                scheduleAdvance();
                scheduleBarHide();
            });
            final TextView zoom = barButton(R.string.slideshow_zoom, v -> {
                mImage.toggleZoom();
                scheduleBarHide();
            });
            mBar.addView(mPlayButton);
            mBar.addView(mIntervalButton);
            mBar.addView(zoom);
            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            // Above the counter, which shows in the same place
            params.bottomMargin = dp(96);
            mRoot.addView(mBar, params);
        }
        updateIntervalButton();
        mPlayButton.setText(mPlaying ? R.string.slideshow_pause : R.string.slideshow_play);
        mBar.setVisibility(View.VISIBLE);
        mPlayButton.requestFocus();
        scheduleBarHide();
    }

    private void hideBar() {
        if (mBar != null) {
            mBar.removeCallbacks(mHideBar);
            mBar.setVisibility(View.GONE);
        }
        mImage.requestFocus();
    }

    private final Runnable mHideBar = this::hideBar;

    private void scheduleBarHide() {
        mBar.removeCallbacks(mHideBar);
        mBar.postDelayed(mHideBar, BAR_MILLIS);
    }

    private int nextSlideshowSeconds() {
        final int current = ViewerSettings.getSlideshowSeconds(this);
        for (int seconds : SLIDESHOW_SECONDS) {
            if (seconds > current) {
                return seconds;
            }
        }
        return SLIDESHOW_SECONDS[0];
    }

    private void updateIntervalButton() {
        mIntervalButton.setText(getString(R.string.slideshow_interval, ViewerSettings.getSlideshowSeconds(this)));
    }

    /** A pill that lights up while the remote is on it */
    private TextView barButton(int text, View.OnClickListener onClick) {
        final TextView button = new TextView(this);
        if (text != 0) {
            button.setText(text);
        }
        button.setTextColor(themeColor(R.attr.colorOnSurface, Color.WHITE));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(96));
        button.setPadding(dp(20), dp(10), dp(20), dp(10));
        final GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(24));
        focused.setColor(themeColor(R.attr.colorSecondaryContainer, Color.GRAY));
        focused.setStroke(dp(2), themeColor(R.attr.colorPrimary, Color.WHITE));
        final android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        button.setBackground(background);
        button.setFocusable(true);
        // A mouse or touch on the TV must not leave the bar without focus
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        button.setOnClickListener(onClick);
        return button;
    }

    /** Turns to another item of what is open: a page, or another photo of the folder */
    private void turn(int delta) {
        if (mImages != null) {
            turnImage(delta, false);
        } else {
            turnPage(delta);
        }
    }

    private String slideHtml() {
        final android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        return OfficeHtml.showSlide(mSlideshow, mSlide, mStep, m.widthPixels / m.density, m.heightPixels / m.density,
                mPlay);
    }

    /**
     * Moves through a presentation as a slideshow does: each step first plays the clicks of the slide's
     * animations, then turns the page. Going back lands on the finished earlier slide.
     */
    private void turnSlide(int direction) {
        int slide = mSlide, step = mStep;
        if (direction > 0) {
            if (step < OfficeHtml.slideSteps(mSlideshow, slide)) {
                step++;
            } else if (slide < mSlides) {
                slide++;
                step = 0;
            }
        } else if (step > 0) {
            step--;
        } else if (slide > 1) {
            slide--;
            step = OfficeHtml.slideSteps(mSlideshow, slide);
        }
        if (slide != mSlide || step != mStep) {
            mPlay = direction > 0;
            mSlide = slide;
            mStep = step;
            mWeb.loadDataWithBaseURL(null, slideHtml(), "text/html", "UTF-8", null);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mSlides > 0 && mWeb != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    turnSlide(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    turnSlide(-1);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mImage != null && mImages != null && mImages.size() > 1) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    setPlaying(!mPlaying);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    setPlaying(true);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    setPlaying(false);
                    return true;
                default:
                    break;
            }
            if (mBar != null && mBar.hasFocus()) {
                scheduleBarHide();
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    hideBar();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // The buttons move focus and click themselves
                    return super.onKeyDown(keyCode, event);
                }
            } else if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    && event.getRepeatCount() == 0) {
                showBar();
                return true;
            }
        }
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
                    if (!mImage.pan(step, 0)) {
                        turn(-1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!mImage.pan(-step, 0)) {
                        turn(1);
                    }
                    return true;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    turn(1);
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    turn(-1);
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
        if (mCounter != null) {
            mCounter.removeCallbacks(mHideCounter);
        }
        mRoot.removeCallbacks(mAdvance);
        if (mBar != null) {
            mBar.removeCallbacks(mHideBar);
        }
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
