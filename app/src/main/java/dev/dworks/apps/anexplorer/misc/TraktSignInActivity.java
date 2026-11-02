package dev.dworks.apps.anexplorer.misc;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.common.ActionBarActivity;

/**
 * Signs in to Trakt with a code entered on another device, as there is no keyboard to type a
 * password on a TV. Finishes with RESULT_OK once the account is saved.
 */
public class TraktSignInActivity extends ActionBarActivity {

    public static final String TAG = "TraktSignIn";

    private volatile boolean mFinished;
    private View mDeviceCode;
    private View mProgress;

    public static Intent createIntent(Context context) {
        return new Intent(context, TraktSignInActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_sign_in);
        mDeviceCode = findViewById(R.id.device_code);
        mProgress = findViewById(R.id.progress);
        final String name = getString(R.string.trakt);
        setTitle(getString(R.string.cloud_sign_in_title, name));
        ((TextView) findViewById(R.id.progress_text)).setText(getString(R.string.cloud_connecting, name));
        signIn();
    }

    @Override
    protected void onDestroy() {
        mFinished = true;
        super.onDestroy();
    }

    @Override
    public String getTag() {
        return TAG;
    }

    private void signIn() {
        new Thread(() -> {
            try {
                final JSONObject code = Trakt.requestDeviceCode(this);
                runOnUiThread(() -> showDeviceCode(code.optString("verification_url"),
                        code.optString("user_code")));

                final String deviceCode = code.optString("device_code");
                long interval = Math.max(code.optLong("interval", 5), 1) * 1000;
                final long expires = System.currentTimeMillis() + code.optLong("expires_in", 600) * 1000;
                while (!mFinished) {
                    Thread.sleep(interval);
                    if (System.currentTimeMillis() > expires) {
                        // Start over with a new code
                        runOnUiThread(this::signIn);
                        return;
                    }
                    final int result = Trakt.pollDeviceCode(this, deviceCode);
                    if (result == Trakt.POLL_DONE) {
                        break;
                    } else if (result == Trakt.POLL_SLOW_DOWN) {
                        interval += 5000;
                    }
                }
                if (!mFinished) {
                    Trakt.sync(this, true);
                    runOnUiThread(() -> {
                        setResult(RESULT_OK);
                        finish();
                    });
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Sign in failed: " + e.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.cloud_sign_in_failed,
                            getString(R.string.trakt), e.getMessage()), Toast.LENGTH_LONG).show();
                    finish();
                });
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
                .setText(getString(R.string.cloud_sign_in_title, getString(R.string.trakt)));
        ((TextView) findViewById(R.id.device_message))
                .setText(getString(R.string.cloud_device_code_message, url.replaceFirst("^https?://", "")));
        ((TextView) findViewById(R.id.device_user_code)).setText(userCode);
        ((ImageView) findViewById(R.id.device_qr)).setImageBitmap(QrCode.encode(url, 480));
    }
}
