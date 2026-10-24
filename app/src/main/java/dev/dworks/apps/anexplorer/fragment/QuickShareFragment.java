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

import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.common.ActionBarActivity;
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
    private Button action;
    private Dialog incomingDialog;
    private int incomingTransferId = -1;
    private boolean standalone;
    // Off once the user stopped receiving, so coming back here doesn't start it again
    private boolean wantReceiving = true;

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String receivedAction = intent.getAction();
            if (QuickShareReceiveService.ACTION_QUICKSHARE_VISIBLE.equals(receivedAction)) {
                setRunning(true);
                status.setText(R.string.quickshare_receive_visible);
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
        action = (Button) view.findViewById(R.id.action);
        action.setOnClickListener(this);
        final String deviceName = QuickShareReceiveService.getDeviceName(getActivity());
        ((TextView) view.findViewById(R.id.hint)).setText(getString(R.string.quickshare_receive_hint,
                deviceName));
        // Only the TV layout lists these
        final TextView device = (TextView) view.findViewById(R.id.device_name);
        if (null != device) {
            device.setText(deviceName);
        }
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
            status.setText(R.string.quickshare_receive_visible);
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
        if (!running) {
            status.setText(R.string.quickshare_receive_not_visible);
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
                .setMessage(incoming.getString(QuickShareReceiveService.EXTRA_SUMMARY))
                .setCancelable(false)
                .setPositiveButton(R.string.quickshare_receive_accept, (dialog, which) -> {
                    QuickShareReceiveService.respond(context, transferId, true);
                    status.setText(R.string.quickshare_receive_receiving);
                })
                .setNegativeButton(R.string.quickshare_receive_decline, (dialog, which) ->
                        QuickShareReceiveService.respond(context, transferId, false))
                .show();
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

    private void startReceiving() {
        ((ActionBarActivity) getActivity()).runWithLocalNetwork(new Runnable() {
            @Override
            public void run() {
                if (isAdded()) {
                    QuickShareReceiveService.start(getActivity());
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                // Receiving can't work without it; don't ask again on every resume
                wantReceiving = false;
                if (isAdded()) {
                    onStopped();
                }
            }
        });
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
