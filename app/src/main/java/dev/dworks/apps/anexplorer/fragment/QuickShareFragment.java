/*
 * Copyright (C) 2014 Hari Krishna Dulipudi
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

package dev.dworks.apps.anexplorer.fragment;

import android.Manifest;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.service.QuickShareReceiveService;

/**
 * Receives files with Quick Share. Shown in the main screen on TV, where stopping leaves the
 * screen open to start again, and on its own in {@link dev.dworks.apps.anexplorer.QuickShareReceiveActivity},
 * which closes when receiving stops.
 */
public class QuickShareFragment extends Fragment implements View.OnClickListener {

    public static final String TAG = "QuickShareFragment";
    public static final String EXTRA_STANDALONE = "standalone";

    private TextView status;
    private TextView visibility;
    private Button action;
    private Dialog incomingDialog;
    private int incomingTransferId = -1;
    private boolean standalone;
    // Off once the user stopped receiving, so coming back here doesn't start it again
    private static final int REQUEST_NEARBY = 49;

    private boolean wantReceiving = true;

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String receivedAction = intent.getAction();
            if (QuickShareReceiveService.ACTION_QUICKSHARE_VISIBLE.equals(receivedAction)) {
                setRunning(true);
                status.setText(R.string.quickshare_receive_ready);
            } else if (QuickShareReceiveService.ACTION_QUICKSHARE_STARTED.equals(receivedAction)) {
                setRunning(true);
                status.setText(R.string.quickshare_receive_waiting);
            } else if (QuickShareReceiveService.ACTION_QUICKSHARE_STOPPED.equals(receivedAction)) {
                onStopped();
            } else if (QuickShareReceiveService.ACTION_QUICKSHARE_INCOMING.equals(receivedAction)) {
                showIncoming(intent.getExtras());
            } else if (QuickShareReceiveService.ACTION_QUICKSHARE_RECEIVED.equals(receivedAction)) {
                status.setText(String.format(Locale.US, getString(R.string.quickshare_receive_saved),
                        intent.getIntExtra(QuickShareReceiveService.EXTRA_COUNT, 0)));
            } else if (QuickShareReceiveService.ACTION_QUICKSHARE_FAILED.equals(receivedAction)) {
                dismissIncoming();
                status.setText(R.string.quickshare_receive_failed);
            } else if (QuickShareReceiveService.ACTION_QUICKSHARE_TEXT.equals(receivedAction)) {
                status.setText(R.string.quickshare_receive_ready);
                showText(intent.getStringExtra(QuickShareReceiveService.EXTRA_DEVICE_NAME),
                        intent.getStringExtra(QuickShareReceiveService.EXTRA_TEXT));
            }
        }
    };

    public static void show(FragmentManager fm) {
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, new QuickShareFragment(), TAG);
        ft.commitAllowingStateLoss();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        standalone = null != getArguments() && getArguments().getBoolean(EXTRA_STANDALONE);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quickshare, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        status = (TextView) view.findViewById(R.id.status);
        visibility = (TextView) view.findViewById(R.id.visibility);
        action = (Button) view.findViewById(R.id.action);
        action.setOnClickListener(this);
        final String deviceName = QuickShareReceiveService.getDeviceName(getActivity());
        ((TextView) view.findViewById(R.id.hint)).setText(getString(R.string.quickshare_receive_hint,
                deviceName));
        ((TextView) view.findViewById(R.id.device_name)).setText(deviceName);
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(QuickShareReceiveService.ACTION_QUICKSHARE_STARTED);
        filter.addAction(QuickShareReceiveService.ACTION_QUICKSHARE_VISIBLE);
        filter.addAction(QuickShareReceiveService.ACTION_QUICKSHARE_STOPPED);
        filter.addAction(QuickShareReceiveService.ACTION_QUICKSHARE_INCOMING);
        filter.addAction(QuickShareReceiveService.ACTION_QUICKSHARE_RECEIVED);
        filter.addAction(QuickShareReceiveService.ACTION_QUICKSHARE_FAILED);
        filter.addAction(QuickShareReceiveService.ACTION_QUICKSHARE_TEXT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getActivity().registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getActivity().registerReceiver(mStateReceiver, filter);
        }
        if (QuickShareReceiveService.isRunning(getActivity())) {
            setRunning(true);
            status.setText(R.string.quickshare_receive_ready);
        } else if (wantReceiving) {
            setRunning(true);
            status.setText(R.string.quickshare_receive_waiting);
            startReceiving();
        } else {
            setRunning(false);
        }

        // A sender may have asked while this screen wasn't visible
        Bundle pending = QuickShareReceiveService.getPendingIncoming();
        if (pending != null) {
            showIncoming(pending);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().unregisterReceiver(mStateReceiver);
    }

    @Override
    public void onDestroy() {
        dismissIncoming();
        super.onDestroy();
    }

    private void setRunning(boolean running) {
        action.setText(running ? R.string.quickshare_receive_stop : R.string.quickshare_receive_start);
        visibility.setText(running ? R.string.quickshare_receive_visible
                : R.string.quickshare_receive_not_visible);
        if (!running) {
            status.setText(R.string.quickshare_receive_off);
        }
    }

    private void onStopped() {
        dismissIncoming();
        if (standalone) {
            getActivity().finish();
        } else {
            setRunning(false);
        }
    }

    /**
     * Asks for consent in the app itself: TV notifications can't show the Accept and
     * Decline actions, and the dialog buttons work with a remote's D-pad.
     */
    private void showIncoming(Bundle incoming) {
        if (incoming == null) {
            return;
        }
        final int transferId = incoming.getInt(QuickShareReceiveService.EXTRA_TRANSFER_ID, -1);
        if (transferId == incomingTransferId && incomingDialog != null && incomingDialog.isShowing()) {
            return;
        }
        dismissIncoming();
        incomingTransferId = transferId;
        final Context context = getActivity();
        String deviceName = incoming.getString(QuickShareReceiveService.EXTRA_DEVICE_NAME);
        incomingDialog = new MaterialAlertDialogBuilder(context)
                .setTitle(String.format(Locale.US, getString(R.string.quickshare_receive_incoming), deviceName))
                .setMessage(incomingMessage(incoming))
                .setCancelable(false)
                .setPositiveButton(R.string.quickshare_receive_accept, (dialog, which) -> {
                    QuickShareReceiveService.respond(context, transferId, true);
                    status.setText(R.string.quickshare_receive_receiving);
                })
                .setNegativeButton(R.string.quickshare_receive_decline, (dialog, which) ->
                        QuickShareReceiveService.respond(context, transferId, false))
                .show();
    }

    /** The summary, then the PIN large enough to compare with the phone from the sofa */
    private CharSequence incomingMessage(Bundle incoming) {
        final String summary = incoming.getString(QuickShareReceiveService.EXTRA_SUMMARY);
        final String pin = incoming.getString(QuickShareReceiveService.EXTRA_PIN);
        if (pin == null) {
            return summary;
        }
        final SpannableStringBuilder message = new SpannableStringBuilder(summary).append("\n\n");
        final int start = message.length();
        message.append(getString(R.string.quickshare_receive_pin, pin));
        message.setSpan(new RelativeSizeSpan(2f), start, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        message.setSpan(new StyleSpan(Typeface.BOLD), start, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return message;
    }

    private void dismissIncoming() {
        if (incomingDialog != null) {
            incomingDialog.dismiss();
            incomingDialog = null;
        }
        incomingTransferId = -1;
    }

    private void showText(String deviceName, String text) {
        new MaterialAlertDialogBuilder(getActivity())
                .setTitle(String.format(Locale.US, getString(R.string.quickshare_receive_from), deviceName))
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Bluetooth and Wi-Fi Direct, which phones use to find and reach the TV */
    public static String[] nearbyPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    /** Finding receivers to send to also takes location, which Play services checks for */
    public static String[] sendPermissions() {
        final String[] nearby = nearbyPermissions();
        final String[] permissions = java.util.Arrays.copyOf(nearby, nearby.length + 2);
        permissions[nearby.length] = Manifest.permission.ACCESS_FINE_LOCATION;
        permissions[nearby.length + 1] = Manifest.permission.ACCESS_COARSE_LOCATION;
        return permissions;
    }

    public static boolean hasNearbyPermissions(Context context) {
        return hasPermissions(context, nearbyPermissions());
    }

    public static boolean hasPermissions(Context context, String[] permissions) {
        for (String permission : permissions) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startReceiving() {
        if (hasNearbyPermissions(getActivity())) {
            QuickShareReceiveService.start(getActivity());
        } else {
            requestPermissions(nearbyPermissions(), REQUEST_NEARBY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_NEARBY || !isAdded() || grantResults.length == 0) {
            return;
        }
        if (hasNearbyPermissions(getActivity())) {
            QuickShareReceiveService.start(getActivity());
        } else {
            // Receiving can't work without it; don't ask again on every resume
            wantReceiving = false;
            Toast.makeText(getActivity(), R.string.quickshare_receive_permission_needed,
                    Toast.LENGTH_LONG).show();
            onStopped();
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.action) {
            if (QuickShareReceiveService.isRunning(getActivity())) {
                wantReceiving = false;
                QuickShareReceiveService.stop(getActivity());
                onStopped();
            } else {
                wantReceiving = true;
                setRunning(true);
                status.setText(R.string.quickshare_receive_waiting);
                startReceiving();
            }
        }
    }
}
