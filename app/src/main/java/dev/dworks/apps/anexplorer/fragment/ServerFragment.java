package dev.dworks.apps.anexplorer.fragment;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.BaseColumns;
import androidx.appcompat.app.AppCompatActivity;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collections;

import dev.dworks.apps.anexplorer.common.ActionBarActivity;
import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.libcore.io.IoUtils;
import dev.dworks.apps.anexplorer.misc.ConnectionUtils;
import dev.dworks.apps.anexplorer.misc.QrCode;
import dev.dworks.apps.anexplorer.provider.ExplorerProvider;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.network.NetworkConnection;

import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_FTPSERVER_FAILEDTOSTART;
import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_FTPSERVER_STARTED;
import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_FTPSERVER_STOPPED;
import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_START_FTPSERVER;
import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_STOP_FTPSERVER;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_ROOT;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorInt;
import static dev.dworks.apps.anexplorer.network.NetworkConnection.SERVER;

public class ServerFragment extends Fragment implements View.OnClickListener {

    private TextView status;
    private TextView username;
    private View passwordRow;
    private TextView password;
    private ImageButton passwordVisibility;
    private String passwordValue;
    private boolean passwordShown;
    private TextView path;
    private TextView address;
    private TextView webAddress;
    private Button action;
    private TextView warning;
    private View pathRow;
    private View usernameRow;
    private View pathEdit;
    private View usernameEdit;
    private View qrGroup;
    private View webOffGroup;
    private ImageView qr;
    private NetworkConnection connection;
    private RootInfo root;

    public static void show(FragmentManager fm, RootInfo root) {
        final ServerFragment fragment = new ServerFragment();
        final Bundle args = new Bundle();
        args.putParcelable(EXTRA_ROOT, root);
        fragment.setArguments(args);
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, fragment);
        ft.commitAllowingStateLoss();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return  inflater.inflate(R.layout.fragment_server,container,false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        status =(TextView) view.findViewById(R.id.status);
        username =(TextView) view.findViewById(R.id.username);
        passwordRow = view.findViewById(R.id.password_row);
        password =(TextView) view.findViewById(R.id.password);
        passwordVisibility = (ImageButton) view.findViewById(R.id.password_visibility);
        passwordRow.setOnClickListener(this);
        if (null != passwordVisibility) {
            passwordVisibility.setOnClickListener(this);
        }
        path = (TextView) view.findViewById(R.id.path);
        address = (TextView) view.findViewById(R.id.address);
        webAddress = (TextView) view.findViewById(R.id.web_address);
        warning = (TextView) view.findViewById(R.id.warning);
        qrGroup = view.findViewById(R.id.qr_group);
        webOffGroup = view.findViewById(R.id.web_off_group);
        qr = (ImageView) view.findViewById(R.id.qr);
        action = (Button) view.findViewById(R.id.action);
        action.setOnClickListener(this);
        // The details are edited by their own rows, so there is no separate edit button
        pathRow = view.findViewById(R.id.path_row);
        pathRow.setOnClickListener(this);
        pathEdit = view.findViewById(R.id.path_edit);
        usernameRow = view.findViewById(R.id.username_row);
        usernameRow.setOnClickListener(this);
        usernameEdit = view.findViewById(R.id.username_edit);

    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setRetainInstance(true);
        root = getArguments().getParcelable(EXTRA_ROOT);
        showConnection();
    }

    private void showConnection() {
        connection = NetworkConnection.fromRootInfo(getActivity(), root);
        if (null == connection) {
            // An edit changed the path this root was found by
            connection = NetworkConnection.getDefaultServer(getActivity());
        }
        if (null == connection) {
            return;
        }
        path.setText(connection.getPath());
        username.setText(connection.getUserName());
        passwordValue = connection.getPassword();
        showPassword();
    }

    private String maskedPassword() {
        return TextUtils.join("", Collections.nCopies(passwordValue.length(), "\u2022"));
    }

    private void showPassword() {
        // Anonymous access has no password to show
        final boolean hasPassword = !TextUtils.isEmpty(passwordValue);
        passwordRow.setVisibility(hasPassword ? View.VISIBLE : View.GONE);
        if (!hasPassword) {
            return;
        }
        if (passwordShown || null == passwordVisibility) {
            password.setText(passwordValue);
        } else {
            password.setText(maskedPassword());
        }
        // As wide shown as hidden, so the eye button beside it stays where it is
        password.setMinWidth((int) Math.ceil(Math.max(
                password.getPaint().measureText(passwordValue),
                password.getPaint().measureText(maskedPassword()))));
        if (null != passwordVisibility) {
            passwordVisibility.setImageResource(passwordShown
                    ? R.drawable.design_ic_visibility_off : R.drawable.design_ic_visibility);
            passwordVisibility.setContentDescription(getString(passwordShown
                    ? R.string.ftp_hide_password : R.string.ftp_show_password));
        }
    }

    /** The settings dialogs the Connections list used to offer; TV edits them here instead. */
    private void editServer(String mode) {
        Cursor cursor = null;
        try {
            cursor = getActivity().getContentResolver().query(ExplorerProvider.buildConnection(),
                    null, ExplorerProvider.ConnectionColumns.TYPE + "=?", new String[]{SERVER}, null);
            if (null != cursor && cursor.moveToFirst()) {
                CreateConnectionFragment.show(((AppCompatActivity) getActivity()).getSupportFragmentManager(),
                        getCursorInt(cursor, BaseColumns._ID), mode);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }
    }

    private final ContentObserver mConnectionObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            if (!isAdded()) {
                return;
            }
            showConnection();
            // The running server keeps the settings it started with
            if (ConnectionUtils.isServerRunning(getActivity())) {
                setText(warning, getString(R.string.ftp_restart_to_apply));
            }
        }
    };

    @Override
    public void onResume(){
        super.onResume();
        updateStatus();
        getActivity().getContentResolver().registerContentObserver(
                ExplorerProvider.buildConnection(), true, mConnectionObserver);
        IntentFilter wifiFilter = new IntentFilter();
        wifiFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        getActivity().registerReceiver(mWifiReceiver, wifiFilter);

        IntentFilter ftpFilter = new IntentFilter();
        ftpFilter.addAction(ACTION_FTPSERVER_STARTED);
        ftpFilter.addAction(ACTION_FTPSERVER_STOPPED);
        ftpFilter.addAction(ACTION_FTPSERVER_FAILEDTOSTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getActivity().registerReceiver(mFtpReceiver, ftpFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getActivity().registerReceiver(mFtpReceiver, ftpFilter);
        }
    }

    @Override
    public void onPause(){
        super.onPause();
        getActivity().unregisterReceiver(mWifiReceiver);
        getActivity().unregisterReceiver(mFtpReceiver);
        getActivity().getContentResolver().unregisterContentObserver(mConnectionObserver);
    }

    @Override
    public  void onDestroy(){
        super.onDestroy();
    }

    private void startServer() {
        Intent intent = new Intent(ACTION_START_FTPSERVER);
        intent.putExtras(getArguments());

        intent.setPackage(getActivity().getPackageName());
        getActivity().sendBroadcast(intent);
    }

    private void stopServer() {
        Intent intent = new Intent(ACTION_STOP_FTPSERVER);
        intent.putExtras(getArguments());

        intent.setPackage(getActivity().getPackageName());
        getActivity().sendBroadcast(intent);
    }

    private void updateStatus(){
        setStatus(ConnectionUtils.isServerRunning(getActivity()));
    }

    private void setStatus(boolean running){
        showQrCode(running);
        // The running server keeps the settings it started with, so they are changed while stopped
        setRowsEnabled(!running);
        if(running){
            setAddress(ConnectionUtils.getFTPAddress(getActivity()));
            final String web = ConnectionUtils.getWebAddress(getActivity());
            setText(webAddress, web);
            status.setText(getString(R.string.ftp_status_running));
            action.setText(R.string.stop_ftp);
        } else {
            setAddress("");
            setText(webAddress, "");
            setText(warning, "");
            status.setText(getString(R.string.ftp_status_not_running));
            action.setText(R.string.start_ftp);
        }
    }
    /**
     * While running, the upload web page's address as a QR code, which a phone opens in its browser,
     * or the FTP address if the page didn't start. The user name is in it so the phone only asks
     * for the password, which stays off the screen.
     */
    private void showQrCode(boolean running) {
        final String web = ConnectionUtils.getWebAddress(getActivity());
        final boolean isWeb = !TextUtils.isEmpty(web);
        final String url = isWeb ? web : ConnectionUtils.getFTPAddress(getActivity());
        final boolean show = running && !TextUtils.isEmpty(url);
        if (show) {
            String target = url;
            if (null != connection && !connection.isAnonymousLogin()
                    && !TextUtils.isEmpty(connection.getUserName())) {
                target = url.replaceFirst("^(https?|ftp)://",
                        "$1://" + Uri.encode(connection.getUserName()) + "@");
            }
            qr.setImageBitmap(QrCode.encode(target, 512));
            ((TextView) qrGroup.findViewById(R.id.qr_caption)).setText(
                    isWeb ? R.string.web_scan_to_send : R.string.ftp_scan_to_connect);
        }
        qrGroup.setVisibility(show ? View.VISIBLE : View.GONE);
        webOffGroup.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    /** The rows open the settings dialog, which a running server would only apply after a restart */
    private void setRowsEnabled(boolean enabled) {
        final View[] rows = {pathRow, usernameRow};
        final View[] pencils = {pathEdit, usernameEdit};
        for (int i = 0; i < rows.length; i++) {
            final View row = rows[i];
            if (null == row) {
                continue;
            }
            row.setEnabled(enabled);
            row.setFocusable(enabled);
            // A running server can't take the change yet, so only the edit hint goes away
            pencils[i].setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.path_row) {
            editServer(CreateConnectionFragment.MODE_PATH);
        } else if (view.getId() == R.id.username_row) {
            editServer(CreateConnectionFragment.MODE_CREDENTIALS);
        } else if (view.getId() == R.id.password_row || view.getId() == R.id.password_visibility) {
            passwordShown = !passwordShown;
            showPassword();
        } else if (view.getId() == R.id.action){
            if(!ConnectionUtils.isServerRunning(getActivity())){
                // TVs are often wired, so any local network will do, not only Wi-Fi
                if(ConnectionUtils.isConnectedToLocalNetwork(getActivity()))
                    ((ActionBarActivity) getActivity()).runWithLocalNetwork(new Runnable() {
                        @Override
                        public void run() {
                            startServer();
                        }
                    });
                else
                    setText(warning, getString(R.string.ftp_no_wifi));
            }
            else{
                stopServer();
            }
        }
    }

    private BroadcastReceiver mWifiReceiver = new  BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectionUtils.isConnectedToLocalNetwork(context)){
                setText(warning, "");
            }
            else{
                stopServer();
                setStatus(false);
                setAddress("");
                setText(warning, getString(R.string.ftp_no_wifi));
            }
        }
    };

    private BroadcastReceiver mFtpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if(action == ACTION_FTPSERVER_STARTED) {
            setStatus(true);
        }
        else if(action == ACTION_FTPSERVER_FAILEDTOSTART){
            setStatus(false);
            setText(warning, getString(R.string.server_error));
        }
        else if(action == ACTION_FTPSERVER_STOPPED){
            setStatus(false);
        }
        }
    };

    private void setTintedImage(ImageView imageview, int resourceId){
        imageview.setImageDrawable(IconUtils.applyTintAttr(getActivity(), resourceId,
                android.R.attr.textColorPrimary));
    }

    /** Keeps its line while empty, so the details below don't move when the server starts */
    private void setAddress(String text) {
        address.setText(text);
        address.setVisibility(TextUtils.isEmpty(text) ? View.INVISIBLE : View.VISIBLE);
    }

    private void setText(TextView textView, String text){
        textView.setText(text);
        textView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }
}