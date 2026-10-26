package dev.dworks.apps.anexplorer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.dworks.apps.anexplorer.common.ActionBarActivity;
import dev.dworks.apps.anexplorer.fragment.QuickShareFragment;
import dev.dworks.apps.anexplorer.protocol.EndpointInfo;
import dev.dworks.apps.anexplorer.service.QuickShareSender;

/**
 * Sends the files it is given with Quick Share: lists the nearby phones that are ready to
 * receive, and once one is picked shows its PIN and how the transfer goes. Leaving cancels.
 */
public class QuickShareSendActivity extends ActionBarActivity implements QuickShareSender.Listener {

    public static final String TAG = "QuickShareSend";
    private static final int REQUEST_NEARBY = 50;

    private QuickShareSender sender;
    private LinearLayout devicesView;
    private ImageView icon;
    private TextView status;
    private TextView pin;
    private TextView hint;
    private Button action;
    private boolean showingTarget;

    /** Opens the send screen for documents of this app */
    public static void start(Context context, ArrayList<Uri> uris) {
        context.startActivity(new Intent(context, QuickShareSendActivity.class)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quickshare_send);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        devicesView = findViewById(R.id.devices);
        icon = findViewById(R.id.icon);
        status = findViewById(R.id.status);
        pin = findViewById(R.id.pin);
        hint = findViewById(R.id.hint);
        action = findViewById(R.id.action);
        action.setOnClickListener(v -> finish());

        ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        List<QuickShareSender.SendFile> files = loadFiles(uris);
        if (files.isEmpty()) {
            finish();
            return;
        }
        showFiles(files);
        sender = new QuickShareSender(this, files, this);
        onStatusChanged(QuickShareSender.Status.SEARCHING, 0);
        if (QuickShareFragment.hasPermissions(this, QuickShareFragment.sendPermissions())) {
            sender.startDiscovery();
        } else {
            requestPermissions(QuickShareFragment.sendPermissions(), REQUEST_NEARBY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NEARBY || grantResults.length == 0) {
            return;
        }
        if (QuickShareFragment.hasPermissions(this, QuickShareFragment.sendPermissions())) {
            sender.startDiscovery();
        } else {
            Toast.makeText(this, R.string.quickshare_receive_permission_needed, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (sender != null) {
            sender.cancel();
        }
        super.onDestroy();
    }

    private List<QuickShareSender.SendFile> loadFiles(ArrayList<Uri> uris) {
        List<QuickShareSender.SendFile> files = new ArrayList<>();
        if (uris == null) {
            return files;
        }
        ContentResolver resolver = getContentResolver();
        for (Uri uri : uris) {
            String name = uri.getLastPathSegment();
            long size = -1;
            try (Cursor cursor = resolver.query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    name = cursor.getString(0);
                    size = cursor.isNull(1) ? -1 : cursor.getLong(1);
                }
            } catch (Exception ignored) {
            }
            String mimeType = resolver.getType(uri);
            files.add(new QuickShareSender.SendFile(uri, name,
                    mimeType == null ? "application/octet-stream" : mimeType, size));
        }
        return files;
    }

    private void showFiles(List<QuickShareSender.SendFile> files) {
        long total = 0;
        for (QuickShareSender.SendFile file : files) {
            total += Math.max(file.size, 0);
        }
        String size = Formatter.formatShortFileSize(this, total);
        ((TextView) findViewById(R.id.files_title)).setText(files.size() == 1 ? files.get(0).name
                : getString(R.string.quickshare_receive_nfiles, files.size()));
        ((TextView) findViewById(R.id.files_summary)).setText(size);
    }

    @Override
    public void onDevicesChanged(List<QuickShareSender.Device> devices) {
        if (sender.getTarget() != null) {
            return;
        }
        showDevices(devices);
        onStatusChanged(sender.getStatus(), 0);
    }

    private void showDevices(List<QuickShareSender.Device> devices) {
        final boolean hadFocus = devicesView.hasFocus() || devicesView.getChildCount() == 0;
        devicesView.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(this);
        for (QuickShareSender.Device device : devices) {
            View item = inflater.inflate(R.layout.item_quickshare_device, devicesView, false);
            ((ImageView) item.findViewById(R.id.icon)).setImageResource(deviceIcon(device.type));
            ((TextView) item.findViewById(R.id.name)).setText(deviceName(device));
            if (sender.getTarget() == null) {
                item.setOnClickListener(v -> sender.send(device));
            } else {
                item.setFocusable(false);
                item.setClickable(false);
            }
            devicesView.addView(item);
        }
        // The first device to show up takes focus from the button, so OK sends right away
        if (devicesView.getChildCount() > 0 && sender.getTarget() == null && hadFocus) {
            devicesView.getChildAt(0).requestFocus();
        }
    }

    private static int deviceIcon(EndpointInfo.DeviceType type) {
        switch (type) {
            case LAPTOP:
                return R.drawable.ic_device_laptop;
            case TABLET:
                return R.drawable.ic_device_tablet;
            default:
                return R.drawable.ic_device_phone;
        }
    }

    private String deviceName(QuickShareSender.Device device) {
        if (device.name != null) {
            return device.name;
        }
        switch (device.type) {
            case PHONE:
                return getString(R.string.quickshare_send_nearby_phone);
            case TABLET:
                return getString(R.string.quickshare_send_nearby_tablet);
            case LAPTOP:
                return getString(R.string.quickshare_send_nearby_computer);
            default:
                return getString(R.string.quickshare_send_unknown_device);
        }
    }

    @Override
    public void onStatusChanged(QuickShareSender.Status state, int progress) {
        final QuickShareSender.Device target = sender.getTarget();
        final String name = target == null ? null : deviceName(target);
        final boolean searching = state == QuickShareSender.Status.SEARCHING;
        final boolean hasDevices = devicesView.getChildCount() > 0;
        icon.setVisibility(searching && hasDevices ? View.GONE : View.VISIBLE);
        if (target != null && !showingTarget) {
            // Only the chosen device stays, and the remote moves to Cancel
            showingTarget = true;
            List<QuickShareSender.Device> chosen = new ArrayList<>();
            chosen.add(target);
            showDevices(chosen);
            action.requestFocus();
        }
        if (target != null) {
            icon.setVisibility(View.GONE);
        }
        String pinCode = sender.getPin();
        boolean showPin = pinCode != null && (state == QuickShareSender.Status.CONNECTING
                || state == QuickShareSender.Status.WAITING);
        pin.setVisibility(showPin ? View.VISIBLE : View.GONE);
        if (showPin) {
            pin.setText(getString(R.string.quickshare_receive_pin, pinCode));
        }
        hint.setVisibility(View.VISIBLE);
        switch (state) {
            case SEARCHING:
                status.setText(hasDevices ? R.string.quickshare_send_choose : R.string.quickshare_send_searching);
                hint.setText(R.string.quickshare_send_hint);
                break;
            case CONNECTING:
                status.setText(getString(R.string.quickshare_send_connecting, name));
                hint.setVisibility(View.GONE);
                break;
            case WAITING:
                status.setText(getString(R.string.quickshare_send_waiting, name));
                hint.setText(R.string.quickshare_send_check_pin);
                break;
            case SENDING:
                status.setText(String.format(Locale.getDefault(),
                        getString(R.string.quickshare_send_progress), progress));
                hint.setVisibility(View.GONE);
                break;
            case SENT:
                status.setText(getString(R.string.quickshare_send_sent, name));
                hint.setVisibility(View.GONE);
                break;
            case DECLINED:
                status.setText(getString(R.string.quickshare_send_declined, name));
                hint.setVisibility(View.GONE);
                break;
            case FAILED:
                status.setText(R.string.quickshare_send_failed);
                hint.setVisibility(View.GONE);
                break;
        }
        boolean finished = state == QuickShareSender.Status.SENT || state == QuickShareSender.Status.DECLINED
                || state == QuickShareSender.Status.FAILED;
        action.setText(finished ? R.string.quickshare_send_done : android.R.string.cancel);
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
