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
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatSpinner;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;

import dev.dworks.apps.anexplorer.BaseActivity;
import dev.dworks.apps.anexplorer.common.DialogBuilder;
import dev.dworks.apps.anexplorer.common.DialogFragment;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.AsyncTask;
import dev.dworks.apps.anexplorer.misc.ProviderExecutor;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.network.NetworkConnection;
import dev.dworks.apps.anexplorer.network.SftpNetworkClient;
import dev.dworks.apps.anexplorer.network.WebDavNetworkClient;
import dev.dworks.apps.anexplorer.provider.ExplorerProvider;
import dev.dworks.apps.anexplorer.provider.NetworkStorageProvider;
import okhttp3.HttpUrl;

import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_CONNECTION_ID;
import static dev.dworks.apps.anexplorer.network.NetworkConnection.CLIENT;
import static dev.dworks.apps.anexplorer.network.NetworkConnection.SERVER;

/**
 * Dialog to create a new connection.
 */
public class CreateConnectionFragment extends DialogFragment {

    private static final String TAG = "create_connection";
    private static final String EXTRA_SCHEME = "scheme";
    /** How much of the screen a TV keyboard covers, which it doesn't say */
    private static final float TV_KEYBOARD_SHARE = 0.45f;
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
    private String[] schemes;
    private int shownScheme = -1;

    public static void show(FragmentManager fm) {
        final CreateConnectionFragment dialog = new CreateConnectionFragment();
        dialog.show(fm, TAG);
    }

    /** A new connection of the given scheme, such as "ftp" or "webdav" */
    public static void showNew(FragmentManager fm, String scheme) {
        final CreateConnectionFragment dialog = new CreateConnectionFragment();
        final Bundle args = new Bundle();
        args.putString(EXTRA_SCHEME, scheme);
        dialog.setArguments(args);
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
        scheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                onSchemeSelected(view, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        host.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    splitAddress();
                }
            }
        });
        final String newScheme = null != getArguments() ? getArguments().getString(EXTRA_SCHEME) : null;
        final NetworkConnection connection = connection_id != 0
                ? NetworkConnection.fromConnectionId(getActivity(), connection_id) : null;
        showSchemes(context, null != connection ? connection.getScheme() : newScheme);
        if (null != newScheme) {
            scheme.setSelection(Math.max(0, Arrays.asList(schemes).indexOf(newScheme)));
        }
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

        if(null != connection){
            scheme.setSelection(Math.max(0, Arrays.asList(schemes).indexOf(connection.getScheme())));

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
        // A narrow margin above and below, so the dialog has room above a keyboard
        final int margin = getResources().getDimensionPixelSize(R.dimen.connection_dialog_margin);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setBackgroundInsetTop(margin)
                .setBackgroundInsetBottom(margin)
                .setTitle(isServer ? R.string.ftp_settings_title
                        : connection_id == 0 ? R.string.connection_new : R.string.connection_edit)
                .setView(content)
                .create();
        keepAboveKeyboard(dialog);
        clearErrorOnEdit(view, R.id.nameLayout, name);
        clearErrorOnEdit(view, R.id.hostLayout, host);
        clearErrorOnEdit(view, R.id.portLayout, port);
        clearErrorOnEdit(view, R.id.pathLayout, path);
        clearErrorOnEdit(view, R.id.usernameContainer, username);
        clearErrorOnEdit(view, R.id.passwordContainer, password);
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
            if (!TextUtils.isEmpty(host.getText()) && !isValidHost()) {
                ((TextInputLayout) view.findViewById(R.id.hostLayout)).setError(
                        getString(R.string.connection_invalid_host));
            }
            markIfEmpty(view, R.id.portLayout, port);
        }
        if (!anonymous.isChecked()) {
            markIfEmpty(view, R.id.usernameContainer, username);
            markIfEmpty(view, R.id.passwordContainer, password);
        }
    }

    /**
     * Moves the dialog to the top and shrinks it above the keyboard while one is up, so the
     * field being typed in scrolls into view. Asking for the window to resize does nothing now
     * that windows reach the screen's edges, and a TV keyboard lies over the screen, telling
     * only that it's up and not how tall it is.
     */
    private static void keepAboveKeyboard(final AlertDialog dialog) {
        final Window window = dialog.getWindow();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        ViewCompat.setOnApplyWindowInsetsListener(window.getDecorView(), (v, insets) -> {
            final int screenHeight = v.getResources().getDisplayMetrics().heightPixels;
            int keyboard = 0;
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                if (keyboard == 0) {
                    keyboard = (int) (screenHeight * TV_KEYBOARD_SHARE);
                }
            }
            final WindowManager.LayoutParams params = window.getAttributes();
            final int gravity = keyboard > 0 ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
            final int height = keyboard > 0
                    ? screenHeight - keyboard : WindowManager.LayoutParams.WRAP_CONTENT;
            if (params.gravity != gravity || params.height != height) {
                params.gravity = gravity;
                params.height = height;
                window.setAttributes(params);
            }
            return ViewCompat.onApplyWindowInsets(v, insets);
        });
    }

    /** A field marked missing stops saying so once something is typed in it */
    private static void clearErrorOnEdit(View view, int layoutId, AppCompatEditText field) {
        final TextInputLayout layout = (TextInputLayout) view.findViewById(layoutId);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (null != layout.getError()) {
                    layout.setError(null);
                }
            }
        });
    }

    private void markIfEmpty(View view, int layoutId, AppCompatEditText field) {
        final TextInputLayout layout = (TextInputLayout) view.findViewById(layoutId);
        layout.setError(TextUtils.isEmpty(field.getText()) ? getString(R.string.connection_required) : null);
    }

    /** Saves the form when it's complete; returns false and saves nothing otherwise. */
    private boolean save() {
        splitAddress();
        if (!isValidHost()) {
            return false;
        }
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
        networkConnection.host = host.getText().toString().trim();
        String portNumber = port.getText().toString();
        if(!TextUtils.isEmpty(portNumber)) {
            networkConnection.port = Integer.parseInt(portNumber);
        }
        networkConnection.username = username.getText().toString();
        networkConnection.password = password.getText().toString();
        networkConnection.scheme = schemes[scheme.getSelectedItemPosition()];
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
        } else if (WebDavNetworkClient.isWebDav(networkConnection.scheme)) {
            // The share's folder, such as /remote.php/dav/files/me on Nextcloud
            final String folder = path.getText().toString().trim();
            if (!TextUtils.isEmpty(folder)) {
                networkConnection.path = folder.startsWith("/") ? folder : "/" + folder;
            }
        }
        return networkConnection;
    }

    /**
     * Fills the form from a whole address typed or pasted into the host field, such as
     * https://dav.example.com:8443/remote.php/dav, which the clients would take as the host name
     */
    private void splitAddress() {
        final String text = host.getText().toString().trim();
        if (null != editedServer || !text.contains("://")) {
            return;
        }
        final URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return;
        }
        String given = null != uri.getScheme() ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if ("http".equals(given)) {
            given = WebDavNetworkClient.SCHEME;
        } else if ("https".equals(given)) {
            given = WebDavNetworkClient.SCHEME_SECURE;
        }
        final int index = Arrays.asList(schemes).indexOf(given);
        if (index < 0 || TextUtils.isEmpty(uri.getHost())) {
            return;
        }
        scheme.setSelection(index);
        host.setText(uri.getHost());
        port.setText(Integer.toString(uri.getPort() > 0 ? uri.getPort() : defaultPort(given)));
        final String folder = uri.getPath();
        if (WebDavNetworkClient.isWebDav(given) && !TextUtils.isEmpty(folder) && !"/".equals(folder)
                && TextUtils.isEmpty(path.getText())) {
            path.setText(folder);
        }
    }

    /** Whether the WebDAV client can build an address from the host; it throws otherwise */
    private boolean isValidHost() {
        final String text = host.getText().toString().trim();
        if (null != editedServer || TextUtils.isEmpty(text)
                || !WebDavNetworkClient.isWebDav(schemes[scheme.getSelectedItemPosition()])) {
            return true;
        }
        final HttpUrl url = HttpUrl.parse("http://" + text + "/");
        return null != url && url.username().isEmpty() && "/".equals(url.encodedPath())
                && url.port() == 80;
    }

    /** The kinds of the scheme's family, so the FTP dialog offers FTP, FTPS and SFTP */
    private void showSchemes(Context context, String given) {
        final boolean webDav = WebDavNetworkClient.isWebDav(given);
        schemes = getResources().getStringArray(webDav
                ? R.array.webdav_connection_schemes : R.array.ftp_connection_schemes);
        final ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(context,
                webDav ? R.array.webdav_connection_types : R.array.ftp_connection_types,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scheme.setAdapter(adapter);
    }

    /** WebDAV asks for the server's folder, and each scheme brings its usual port */
    private void onSchemeSelected(View view, int position) {
        if (null != editedServer) {
            return;
        }
        final String selected = schemes[position];
        final boolean webDav = WebDavNetworkClient.isWebDav(selected);
        pathContainer.setVisibility(webDav ? View.VISIBLE : View.GONE);
        // SSH always logs in as someone
        final boolean sftp = SftpNetworkClient.SCHEME.equals(selected);
        if (sftp) {
            anonymous.setChecked(false);
        }
        anonymous.setVisibility(sftp ? View.GONE : View.VISIBLE);
        ((TextInputLayout) view.findViewById(R.id.pathLayout)).setHint(
                webDav ? R.string.connection_webdav_folder : R.string.connection_shared_folder);

        final String current = port.getText().toString();
        final boolean usualPort = shownScheme >= 0
                && current.equals(Integer.toString(defaultPort(schemes[shownScheme])));
        if (TextUtils.isEmpty(current) || usualPort) {
            port.setText(Integer.toString(defaultPort(selected)));
        }
        shownScheme = position;
    }

    private static int defaultPort(String scheme) {
        if (SftpNetworkClient.SCHEME.equals(scheme)) {
            return SftpNetworkClient.DEFAULT_PORT;
        }
        return WebDavNetworkClient.isWebDav(scheme) ? WebDavNetworkClient.defaultPort(scheme) : 21;
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
