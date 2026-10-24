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

package dev.dworks.apps.anexplorer.fragment;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatSpinner;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import dev.dworks.apps.anexplorer.BaseActivity;
import dev.dworks.apps.anexplorer.common.DialogBuilder;
import dev.dworks.apps.anexplorer.common.DialogFragment;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.AsyncTask;
import dev.dworks.apps.anexplorer.misc.ProviderExecutor;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.network.NetworkConnection;
import dev.dworks.apps.anexplorer.provider.ExplorerProvider;
import dev.dworks.apps.anexplorer.provider.NetworkStorageProvider;

import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_CONNECTION_ID;
import static dev.dworks.apps.anexplorer.network.NetworkConnection.CLIENT;
import static dev.dworks.apps.anexplorer.network.NetworkConnection.SERVER;

/**
 * Dialog to create a new connection.
 */
public class CreateConnectionFragment extends DialogFragment {
    private static final String TAG = "create_connection";
    private AppCompatEditText name;
    private AppCompatEditText host;
    private AppCompatEditText port;
    private AppCompatEditText username;
    private AppCompatEditText password;
    private AppCompatSpinner scheme;
    private CompoundButton anonymous;
    private View passwordContainer;
    private View usernameContainer;
    private int connection_id;
    private AppCompatEditText path;
    private View hostContainer;
    private View pathContainer;
    // The built-in server being edited, whose type, host, port and scheme aren't in the form
    private NetworkConnection editedServer;

    public static void show(FragmentManager fm) {
        final CreateConnectionFragment dialog = new CreateConnectionFragment();
        dialog.show(fm, TAG);
    }

    public static void show(FragmentManager fm, int connection_id) {
        final CreateConnectionFragment dialog = new CreateConnectionFragment();
        final Bundle args = new Bundle();
        args.putInt(EXTRA_CONNECTION_ID, connection_id);
        dialog.setArguments(args);
        dialog.show(fm, TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if(null != args){
            connection_id = args.getInt(EXTRA_CONNECTION_ID);
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();

        final LayoutInflater dialogInflater = getActivity().getLayoutInflater();

        final View view = dialogInflater.inflate(R.layout.dialog_create_connection, null, false);
        boolean isServer = false;
        name = (AppCompatEditText) view.findViewById(R.id.name);
        host = (AppCompatEditText) view.findViewById(R.id.host);
        port = (AppCompatEditText) view.findViewById(R.id.port);
        path = (AppCompatEditText) view.findViewById(R.id.path);
        hostContainer = view.findViewById(R.id.hostContainer);
        pathContainer = view.findViewById(R.id.pathContainer);
        username = (AppCompatEditText) view.findViewById(R.id.username);
        usernameContainer = view.findViewById(R.id.usernameContainer);
        password = (AppCompatEditText) view.findViewById(R.id.password);
        passwordContainer = view.findViewById(R.id.passwordContainer);
        scheme = (AppCompatSpinner) view.findViewById(R.id.scheme);
        anonymous = (CompoundButton) view.findViewById(R.id.anonymous);
        anonymous.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                // "anonymous" is only the placeholder login; don't offer it as the user's name
                if (!b && "anonymous".equals(username.getText().toString())) {
                    username.setText("");
                }
                usernameContainer.setVisibility(b ? View.GONE : View.VISIBLE);
                passwordContainer.setVisibility(b ? View.GONE : View.VISIBLE);
            }
        });

        if(connection_id != 0){
            NetworkConnection connection =
                    NetworkConnection.fromConnectionId(getActivity(), connection_id);

            ArrayAdapter myAdap = (ArrayAdapter) scheme.getAdapter();
            int spinnerPosition = myAdap.getPosition(connection.getScheme().toUpperCase());
            scheme.setSelection(spinnerPosition);

            name.setText(connection.getName());
            host.setText(connection.getHost());
            port.setText(Integer.toString(connection.getPort()));
            path.setText(connection.getPath());
            username.setText(connection.getUserName());
            password.setText(connection.getPassword());
            anonymous.setChecked(connection.isAnonymousLogin());
            if(SERVER.equals(connection.getType())){
                editedServer = connection;
                hostContainer.setVisibility(View.GONE);
                pathContainer.setVisibility(View.VISIBLE);
                isServer = true;
            }
        }
        return createDialog(context, view, isServer);
    }

    /**
     * Material 3 fields with the buttons in the layout, so Save stays open on a missing field
     * and marks it, and the remote's focus is easy to see on a filled button.
     */
    private Dialog createDialog(Context context, final View view, boolean isServer) {
        if (isServer) {
            // The type and name don't apply to the built-in server
            view.findViewById(R.id.nameContainer).setVisibility(View.GONE);
        }
        // The fields scroll when they don't fit; the buttons stay in view below them
        final View buttons = view.findViewById(R.id.dialog_buttons);
        ((ViewGroup) buttons.getParent()).removeView(buttons);
        final ScrollView scroller = new ScrollView(context);
        scroller.addView(view);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final LinearLayout.LayoutParams buttonParams = (LinearLayout.LayoutParams) buttons.getLayoutParams();
        buttonParams.setMarginStart(view.getPaddingStart());
        buttonParams.setMarginEnd(view.getPaddingEnd());
        buttonParams.topMargin = 0;
        buttonParams.bottomMargin = view.getPaddingBottom();
        content.addView(buttons, buttonParams);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(isServer ? R.string.ftp_settings_title
                        : connection_id == 0 ? R.string.connection_new : R.string.connection_edit)
                .setView(content)
                .create();
        final Button saveButton = (Button) buttons.findViewById(R.id.dialog_save);
        saveButton.setText(connection_id == 0 ? R.string.connection_add : R.string.connection_save);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (save()) {
                    dialog.dismiss();
                } else {
                    markMissingFields(view);
                }
            }
        });
        buttons.findViewById(R.id.dialog_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        return dialog;
    }

    private void markMissingFields(View view) {
        if (null != editedServer) {
            markIfEmpty(view, R.id.pathLayout, path);
        } else {
            markIfEmpty(view, R.id.nameLayout, name);
            markIfEmpty(view, R.id.hostLayout, host);
            markIfEmpty(view, R.id.portLayout, port);
        }
        if (!anonymous.isChecked()) {
            markIfEmpty(view, R.id.usernameContainer, username);
            markIfEmpty(view, R.id.passwordContainer, password);
        }
    }

    private void markIfEmpty(View view, int layoutId, AppCompatEditText field) {
        final TextInputLayout layout = (TextInputLayout) view.findViewById(layoutId);
        layout.setError(TextUtils.isEmpty(field.getText()) ? getString(R.string.connection_required) : null);
    }

    /** Saves the form when it's complete; returns false and saves nothing otherwise. */
    private boolean save() {
        final NetworkConnection networkConnection = getNetworkConnection();
        if (!validate(networkConnection)) {
            return false;
        }
        new CreateConnectionTask((BaseActivity) getActivity(), networkConnection).executeOnExecutor(
                ProviderExecutor.forAuthority(ExplorerProvider.AUTHORITY));
        return true;
    }

    private NetworkConnection getNetworkConnection(){
        NetworkConnection networkConnection = new NetworkConnection();
        networkConnection.name = name.getText().toString();
        networkConnection.host = host.getText().toString();
        String portNumber = port.getText().toString();
        if(!TextUtils.isEmpty(portNumber)) {
            networkConnection.port = Integer.parseInt(portNumber);
        }
        networkConnection.username = username.getText().toString();
        networkConnection.password = password.getText().toString();
        networkConnection.scheme = scheme.getSelectedItem().toString().toLowerCase();
        networkConnection.type = CLIENT;
        networkConnection.setAnonymous(anonymous.isChecked());
        if (null != editedServer) {
            networkConnection.type = SERVER;
            networkConnection.host = editedServer.getHost();
            networkConnection.port = editedServer.getPort();
            networkConnection.scheme = editedServer.getScheme();
        }
        networkConnection.build();
        if (null != editedServer) {
            // build() resets the path to the root; the server shares the folder in the form
            networkConnection.path = path.getText().toString();
        }
        return networkConnection;
    }

    private boolean validate(NetworkConnection networkConnection){
        boolean isValid = true;
        if(TextUtils.isEmpty(networkConnection.name)){
            return false;
        }
        if(SERVER.equals(networkConnection.type)){
            // The server has no host to check, only a folder to share
            if(TextUtils.isEmpty(networkConnection.path)){
                return false;
            }
        } else if(TextUtils.isEmpty(networkConnection.host)){
            return false;
        }
        if(networkConnection.port == 0){
            return false;
        }
        if(!networkConnection.isAnonymousLogin) {
            if (TextUtils.isEmpty(networkConnection.username)) {
                return false;
            }
            if (TextUtils.isEmpty(networkConnection.password)) {
                return false;
            }
        }
        return isValid;
    }

    private class CreateConnectionTask extends AsyncTask<Void, Void, Boolean> {
        private final BaseActivity mActivity;
        private final NetworkConnection mNetworkConnection;

        public CreateConnectionTask(
                BaseActivity activity, NetworkConnection networkConnection) {
            mActivity = activity;
            mNetworkConnection = networkConnection;
        }

        @Override
        protected void onPreExecute() {
            mActivity.setPending(true);
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            return NetworkStorageProvider.addUpdateConnection(mActivity, mNetworkConnection, connection_id);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                RootsCache.updateRoots(mActivity, NetworkStorageProvider.AUTHORITY);
                ConnectionsFragment connectionsFragment = ConnectionsFragment.get(mActivity.getFragmentManager());
                if(null != connectionsFragment){
                    connectionsFragment.reload();
                    if(connection_id == 0) {
                        connectionsFragment.openConnectionRoot(mNetworkConnection);
                    }
                }
            }
        }
    }
}
