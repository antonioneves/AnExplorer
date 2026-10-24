package dev.dworks.apps.anexplorer.cloud;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.common.ActionBarActivity;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.provider.CloudStorageProvider;

import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_BOX;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_DROPBOX;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_GDRIVE;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_ONEDRIVE;

/**
 * Signs in to a cloud drive in the way that suits a TV for it: Google Drive with the Google
 * account already on the TV, OneDrive with a code entered on another device, and Dropbox and Box
 * on their sign-in page. Finishes with {@link #EXTRA_ROOT_ID} set once the account is saved.
 */
public class CloudSignInActivity extends ActionBarActivity {

    public static final String TAG = "CloudSignIn";
    public static final String EXTRA_TYPE = "extra.type";
    public static final String EXTRA_ROOT_ID = "extra.root_id";

    private static final int REQUEST_GOOGLE = 7;

    private String mType;
    private String mTypeName;
    private CloudService mService;
    private String mState;
    private String mCodeVerifier;
    private volatile boolean mFinished;
    private boolean mRedirectHandled;

    private WebView mWebView;
    private View mDeviceCode;
    private View mProgress;
    private TextView mProgressText;

    public static Intent createIntent(Context context, String type) {
        return new Intent(context, CloudSignInActivity.class).putExtra(EXTRA_TYPE, type);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_sign_in);
        mWebView = findViewById(R.id.webview);
        mDeviceCode = findViewById(R.id.device_code);
        mProgress = findViewById(R.id.progress);
        mProgressText = findViewById(R.id.progress_text);

        mType = getIntent().getStringExtra(EXTRA_TYPE);
        mService = CloudConnection.createService(this, mType);
        if (mService == null) {
            finish();
            return;
        }
        mTypeName = CloudConnection.getTypeName(this, mType);
        setTitle(getString(R.string.cloud_sign_in_title, mTypeName));
        showProgress();

        if (!CloudConnection.isConfigured(mType)) {
            final String key = TYPE_DROPBOX.equals(mType) ? "DROPBOX_CLIENT_ID"
                    : TYPE_ONEDRIVE.equals(mType) ? "ONEDRIVE_CLIENT_ID"
                    : "BOX_CLIENT_ID and BOX_CLIENT_KEY";
            showError(getString(R.string.cloud_not_configured, mTypeName, key));
            return;
        }
        mState = randomString(16);
        if (TYPE_GDRIVE.equals(mType)) {
            signInGoogle();
        } else if (TYPE_ONEDRIVE.equals(mType)) {
            signInWithDeviceCode();
        } else if (TYPE_DROPBOX.equals(mType)) {
            mCodeVerifier = randomString(64);
            signInOnWeb(DropboxService.getAuthorizeUrl(codeChallenge(mCodeVerifier), mState));
        } else if (TYPE_BOX.equals(mType)) {
            signInOnWeb(BoxService.getAuthorizeUrl(mState));
        }
    }

    @Override
    protected void onDestroy() {
        mFinished = true;
        if (mWebView != null) {
            mWebView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public String getTag() {
        return TAG;
    }

    private void showProgress() {
        mWebView.setVisibility(View.GONE);
        mDeviceCode.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mProgressText.setText(getString(R.string.cloud_connecting, mTypeName));
    }

    // Google Drive

    private void signInGoogle() {
        Identity.getAuthorizationClient(this)
                .authorize(GoogleDriveService.authorizationRequest(null))
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        try {
                            startIntentSenderForResult(result.getPendingIntent().getIntentSender(),
                                    REQUEST_GOOGLE, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            fail(e.getMessage());
                        }
                    } else {
                        onGoogleAuthorized(result);
                    }
                })
                .addOnFailureListener(this::onGoogleFailed);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GOOGLE) {
            return;
        }
        if (resultCode != RESULT_OK && data == null) {
            finish();
            return;
        }
        // A failure comes back as a cancelled result carrying the error
        try {
            onGoogleAuthorized(Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(data));
        } catch (ApiException e) {
            onGoogleFailed(e);
        }
    }

    private void onGoogleAuthorized(AuthorizationResult result) {
        ((GoogleDriveService) mService).setAuthorization(result);
        complete();
    }

    private void onGoogleFailed(Exception e) {
        Log.w(TAG, "Google authorization failed", e);
        final boolean unregistered = e instanceof ApiException
                && (((ApiException) e).getStatusCode() == CommonStatusCodes.DEVELOPER_ERROR
                || String.valueOf(e.getMessage()).contains("UNREGISTERED_ON_API_CONSOLE"));
        if (e instanceof ApiException
                && ((ApiException) e).getStatusCode() == CommonStatusCodes.CANCELED) {
            finish();
        } else if (unregistered) {
            showError(getString(R.string.cloud_google_not_registered, getPackageName(),
                    getSigningFingerprint()));
        } else {
            fail(e.getMessage());
        }
    }

    // OneDrive

    private void signInWithDeviceCode() {
        final OneDriveService service = (OneDriveService) mService;
        new Thread(() -> {
            try {
                final JSONObject code = OneDriveService.requestDeviceCode();
                runOnUiThread(() -> showDeviceCode(code.optString("verification_uri"),
                        code.optString("user_code")));

                final String deviceCode = code.optString("device_code");
                long interval = Math.max(code.optLong("interval", 5), 1) * 1000;
                final long expires = System.currentTimeMillis()
                        + code.optLong("expires_in", 900) * 1000;
                while (!mFinished) {
                    Thread.sleep(interval);
                    if (System.currentTimeMillis() > expires) {
                        // Codes last a quarter of an hour; start over with a new one
                        runOnUiThread(this::signInWithDeviceCode);
                        return;
                    }
                    try {
                        service.pollDeviceCode(deviceCode);
                        break;
                    } catch (CloudService.CloudException e) {
                        if ("slow_down".equals(e.error)) {
                            interval += 5000;
                        } else if (!"authorization_pending".equals(e.error)) {
                            throw e;
                        }
                    }
                }
                if (!mFinished) {
                    runOnUiThread(() -> {
                        showProgress();
                        complete();
                    });
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Device code sign in failed", e);
                runOnUiThread(() -> fail(e.getMessage()));
            }
        }).start();
    }

    private void showDeviceCode(String url, String userCode) {
        if (mFinished) {
            return;
        }
        mProgress.setVisibility(View.GONE);
        mDeviceCode.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.device_title))
                .setText(getString(R.string.cloud_sign_in_title, mTypeName));
        ((TextView) findViewById(R.id.device_message))
                .setText(getString(R.string.cloud_device_code_message,
                        url.replaceFirst("^https?://", "")));
        ((TextView) findViewById(R.id.device_user_code)).setText(userCode);
        ((ImageView) findViewById(R.id.device_qr)).setImageBitmap(qrCode(url, 480));
    }

    private static Bitmap qrCode(String text, int size) {
        try {
            final BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            final int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565);
        } catch (WriterException e) {
            return null;
        }
    }

    // Dropbox and Box

    @SuppressLint("SetJavaScriptEnabled")
    private void signInOnWeb(String url) {
        // Start signed out, so another account can be added
        CookieManager.getInstance().removeAllCookies(null);
        final WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleRedirect(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleRedirect(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!handleRedirect(url)) {
                    super.onPageStarted(view, url, favicon);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!mRedirectHandled) {
                    mProgress.setVisibility(View.GONE);
                    mWebView.setVisibility(View.VISIBLE);
                    mWebView.requestFocus();
                }
            }
        });
        mWebView.loadUrl(url);
    }

    /** Picks the sign-in answer out of the page the service sends the browser back to */
    private boolean handleRedirect(String url) {
        if (url == null || !url.startsWith(CloudService.REDIRECT_URI)) {
            return false;
        }
        if (mRedirectHandled) {
            return true;
        }
        mRedirectHandled = true;
        mWebView.stopLoading();
        showProgress();

        final Uri uri = Uri.parse(url);
        final String error = uri.getQueryParameter("error");
        if (error != null) {
            if ("access_denied".equals(error)) {
                finish();
            } else {
                final String description = uri.getQueryParameter("error_description");
                fail(TextUtils.isEmpty(description) ? error : description);
            }
            return true;
        }
        final String code = uri.getQueryParameter("code");
        if (code == null || !mState.equals(uri.getQueryParameter("state"))) {
            fail("No sign-in code came back");
            return true;
        }
        new Thread(() -> {
            try {
                if (mService instanceof DropboxService) {
                    ((DropboxService) mService).exchangeCode(code, mCodeVerifier);
                } else {
                    ((BoxService) mService).exchangeCode(code);
                }
                runOnUiThread(this::complete);
            } catch (Exception e) {
                Log.w(TAG, "Code exchange failed", e);
                runOnUiThread(() -> fail(e.getMessage()));
            }
        }).start();
        return true;
    }

    // Finishing

    /** Looks up who signed in, saves the account and shows it */
    private void complete() {
        new Thread(() -> {
            try {
                mService.fetchAccount();
                final CloudConnection connection = new CloudConnection(this, mService);
                if (!connection.save()) {
                    throw new Exception("The account couldn't be saved");
                }
                RootsCache.updateRoots(this, CloudStorageProvider.AUTHORITY);
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.cloud_connected, mTypeName),
                            Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_ROOT_ID, connection.clientId));
                    finish();
                });
            } catch (Exception e) {
                Log.w(TAG, "Saving the account failed", e);
                runOnUiThread(() -> fail(e.getMessage()));
            }
        }).start();
    }

    private void fail(String message) {
        showError(getString(R.string.cloud_sign_in_failed, mTypeName, message));
    }

    private void showError(String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mFinished = true;
        mProgress.setVisibility(View.GONE);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.cloud_sign_in_title, mTypeName))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(dialog -> finish())
                .show();
    }

    // Helpers

    private static String randomString(int bytes) {
        final byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String codeChallenge(String verifier) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The SHA-1 of the certificate this build is signed with, as the Google console wants it */
    @SuppressWarnings("deprecation")
    private String getSigningFingerprint() {
        try {
            final Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final PackageInfo info = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                signatures = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNATURES).signatures;
            }
            final byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(signatures[0].toByteArray());
            final StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                if (hex.length() > 0) {
                    hex.append(':');
                }
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "?";
        }
    }
}
