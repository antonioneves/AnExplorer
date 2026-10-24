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
    private TextView password;
    private ImageButton passwordVisibility;
    private String passwordValue;
    private boolean passwordShown;
    private TextView path;
    private TextView address;
    private Button action;
    private TextView warning;
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
        password =(TextView) view.findViewById(R.id.password);
        passwordVisibility = (ImageButton) view.findViewById(R.id.password_visibility);
        if (null != passwordVisibility) {
            passwordVisibility.setOnClickListener(this);
        }
        path = (TextView) view.findViewById(R.id.path);
        address = (TextView) view.findViewById(R.id.address);
        warning = (TextView) view.findViewById(R.id.warning);
        action = (Button) view.findViewById(R.id.action);
        action.setOnClickListener(this);
        // Only the TV layout has it
        final View editButton = view.findViewById(R.id.edit_server);
        if (null != editButton) {
            editButton.setOnClickListener(this);
        }

    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setRetainInstance(true);
        root = getArguments().getParcelable(EXTRA_ROOT);
        showConnection();
    }

    private void showConnection() {
        NetworkConnection connection = NetworkConnection.fromRootInfo(getActivity(), root);
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

    private void showPassword() {
        final boolean hasPassword = !TextUtils.isEmpty(passwordValue);
        if (!hasPassword) {
            // Anonymous access has no password; say so instead of leaving the value blank
            password.setText(R.string.ftp_no_password);
        } else if (passwordShown || null == passwordVisibility) {
            password.setText(passwordValue);
        } else {
            password.setText(TextUtils.join("", Collections.nCopies(passwordValue.length(), "\u2022")));
        }
        if (null != passwordVisibility) {
            passwordVisibility.setVisibility(hasPassword ? View.VISIBLE : View.GONE);
            passwordVisibility.setImageResource(passwordShown
                    ? R.drawable.design_ic_visibility_off : R.drawable.design_ic_visibility);
            passwordVisibility.setContentDescription(getString(passwordShown
                    ? R.string.ftp_hide_password : R.string.ftp_show_password));
        }
    }

    /** The settings dialog the Connections list used to offer; TV edits them here instead. */
    private void editServer() {
        Cursor cursor = null;
        try {
            cursor = getActivity().getContentResolver().query(ExplorerProvider.buildConnection(),
                    null, ExplorerProvider.ConnectionColumns.TYPE + "=?", new String[]{SERVER}, null);
            if (null != cursor && cursor.moveToFirst()) {
                CreateConnectionFragment.show(((AppCompatActivity) getActivity()).getSupportFragmentManager(),
                        getCursorInt(cursor, BaseColumns._ID));
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
        if(running){
            setText(address, ConnectionUtils.getFTPAddress(getActivity()));
            status.setText(getString(R.string.ftp_status_running));
            action.setText(R.string.stop_ftp);
        } else {
            setText(address, "");
            setText(warning, "");
            status.setText(getString(R.string.ftp_status_not_running));
            action.setText(R.string.start_ftp);
        }
    }
    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.edit_server) {
            editServer();
        } else if (view.getId() == R.id.password_visibility) {
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
                setText(address, "");
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
            setText(warning, "Oops! Something went wrong");
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

    private void setText(TextView textView, String text){
        textView.setText(text);
        textView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }
}