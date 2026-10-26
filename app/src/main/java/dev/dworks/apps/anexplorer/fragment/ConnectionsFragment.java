package dev.dworks.apps.anexplorer.fragment;

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;

import androidx.appcompat.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.PopupMenu;

import dev.dworks.apps.anexplorer.BaseActivity;
import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.adapter.ConnectionsAdapter;
import dev.dworks.apps.anexplorer.cloud.CloudConnection;
import dev.dworks.apps.anexplorer.cloud.CloudSignInActivity;
import dev.dworks.apps.anexplorer.common.DialogBuilder;
import dev.dworks.apps.anexplorer.common.RecyclerFragment;
import dev.dworks.apps.anexplorer.directory.DividerItemDecoration;
import dev.dworks.apps.anexplorer.misc.AnalyticsManager;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.network.NetworkConnection;
import dev.dworks.apps.anexplorer.network.WebDavNetworkClient;
import dev.dworks.apps.anexplorer.provider.CloudStorageProvider;
import dev.dworks.apps.anexplorer.provider.ExplorerProvider;
import dev.dworks.apps.anexplorer.provider.NetworkStorageProvider;
import dev.dworks.apps.anexplorer.setting.AppSettings;
import dev.dworks.apps.anexplorer.ui.SideMenuPopup;
import dev.dworks.apps.anexplorer.misc.ThemeColors;

import static android.widget.LinearLayout.VERTICAL;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorInt;
import static dev.dworks.apps.anexplorer.network.NetworkConnection.SERVER;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_BOX;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_CLOUD;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_DROPBOX;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_GDRIVE;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_ONEDRIVE;

public class ConnectionsFragment extends RecyclerFragment
        implements ConnectionsAdapter.OnItemClickListener {

    public static final String TAG = "ConnectionsFragment";

    private ConnectionsAdapter mAdapter;
    private LoaderManager.LoaderCallbacks<Cursor> mCallbacks;

    private static final int REQUEST_CLOUD_SIGN_IN = 43;

    private final int mLoaderId = 42;
    private RootInfo mConnectionsRoot;
    private View mEmptyGroup;
    private boolean mEmpty;

    public static void show(FragmentManager fm) {
        final ConnectionsFragment fragment = new ConnectionsFragment();
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, fragment, TAG);
        ft.commitAllowingStateLoss();
    }

    public static ConnectionsFragment get(FragmentManager fm) {
        return (ConnectionsFragment) fm.findFragmentByTag(TAG);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mConnectionsRoot = DocumentsApplication.getRootsCache(getActivity()).getConnectionsRoot();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return  inflater.inflate(R.layout.fragment_connections,container,false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        final Resources res = getActivity().getResources();

        // Indent our list divider to align with text
        final boolean insetLeft = res.getBoolean(R.bool.list_divider_inset_left);
        final int insetSize = res.getDimensionPixelSize(R.dimen.list_divider_inset);
        DividerItemDecoration decoration = new DividerItemDecoration(getActivity(), VERTICAL);
        if (insetLeft) {
            decoration.setInset(insetSize, 0);
        } else {
            decoration.setInset(0, insetSize);
        }
        getListView().addItemDecoration(decoration);

        mEmptyGroup = view.findViewById(R.id.empty_group);
        view.findViewById(R.id.add_connection).setOnClickListener(this::showAddMenu);
    }

    /** Hides the toolbar's add button for the one in the middle of the screen while there are none */
    private void showEmpty(boolean empty) {
        if (mEmptyGroup != null) {
            mEmptyGroup.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        // Or the empty list takes the remote's focus from the button
        getListView().setFocusable(!empty);
        if (mEmpty != empty) {
            mEmpty = empty;
            getActivity().invalidateOptionsMenu();
        }
    }

    private void showAddMenu(View anchor) {
        final PopupMenu popup = new PopupMenu(getActivity(), anchor);
        popup.getMenuInflater().inflate(R.menu.connections_options, popup.getMenu());
        final Menu types = popup.getMenu().findItem(R.id.menu_add_connection).getSubMenu();
        SideMenuPopup.show(anchor, types, item -> {
            menuItemAction(item);
            return true;
        });
    }


    @Override
    public void onItemClick(ConnectionsAdapter.ViewHolder item, View view, int position) {
        final Cursor cursor = mAdapter.getItem(position);
        if (cursor != null) {
            openConnectionRoot(cursor);
        }
    }

    @Override
    public void onItemLongClick(ConnectionsAdapter.ViewHolder item, View view, int position) {
        showPopupMenu(view, position);
    }

    @Override
    public void onItemViewClick(ConnectionsAdapter.ViewHolder item, View view, int position) {
        showPopupMenu(view, position);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        final Context context = getActivity();

        mAdapter = new ConnectionsAdapter(context, null);
        mAdapter.setOnItemClickListener(this);
        mCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {

            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                Uri contentsUri = ExplorerProvider.buildConnection();

                // The server has its own screen in the drawer
                final String selection = ExplorerProvider.ConnectionColumns.TYPE + "!=? ";
                final String[] selectionArgs = new String[]{SERVER};

                return new CursorLoader(context, contentsUri, null, selection, selectionArgs, null);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor result) {
                if (!isAdded())
                    return;

                mAdapter.swapCursor(result);
                showEmpty(result == null || result.getCount() == 0);
                if (isResumed()) {
                    setListShown(true);
                } else {
                    setListShownNoAnimation(true);
                }
            }

            @Override
            public void onLoaderReset(Loader<Cursor> loader) {
                mAdapter.swapCursor(null);
            }
        };
        setListAdapter(mAdapter);
        setListShown(false);
        // Kick off loader at least once
        getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);

    }

    public void reload(){
        getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);
        RootsCache.updateRoots(getActivity(), NetworkStorageProvider.AUTHORITY);
        RootsCache.updateRoots(getActivity(), CloudStorageProvider.AUTHORITY);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.connections_options, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        final MenuItem add = menu.findItem(R.id.menu_add_connection);
        if (add != null) {
            add.setVisible(!mEmpty);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        menuItemAction(item);
        return super.onOptionsItemSelected(item);
    }

    private void showPopupMenu(View view, final int position) {
        PopupMenu popup = new PopupMenu(getActivity(), view);

        popup.getMenuInflater().inflate(R.menu.popup_connections, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                return onPopupMenuItemClick(menuItem, position);
            }
        });
        popup.show();
    }

    public boolean onPopupMenuItemClick(MenuItem item, int position) {
        final Cursor cursor = mAdapter.getItem(position);
        int connection_id = getCursorInt(cursor, BaseColumns._ID);
        NetworkConnection networkConnection = NetworkConnection.fromConnectionsCursor(cursor);
        final int id = item.getItemId();
        if (id == R.id.menu_edit) {
            if(!networkConnection.type.startsWith(TYPE_CLOUD)) {
                editConnection(connection_id);
            } else {
                Utils.showSnackBar(getActivity(), "Cloud storage connection can't be edited");
            }
            return true;
        } else if (id == R.id.menu_delete) {
            if(!networkConnection.type.equals(SERVER)) {
                deleteConnection(connection_id);
            } else {
                Utils.showSnackBar(getActivity(), "Default server connection can't be deleted");
            }
            return true;
        }
        return false;
    }

    private void addConnection(String scheme) {
        CreateConnectionFragment.showNew(((DocumentsActivity)getActivity()).getSupportFragmentManager(), scheme);
        AnalyticsManager.logEvent("connection_add");
    }

    private void editConnection(int connection_id) {
        CreateConnectionFragment.show(((DocumentsActivity)getActivity()).getSupportFragmentManager(), connection_id);
        AnalyticsManager.logEvent("connection_edit");
    }

    private void deleteConnection(final int connection_id) {
        DialogBuilder builder = new DialogBuilder(getActivity());
        builder.setMessage("Delete connection?")
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int did) {
                        boolean success = NetworkConnection.deleteConnection(getActivity(), connection_id);
                        if(success){
                            reload();
                        }
                    }
                    }).setNegativeButton(android.R.string.cancel,  null);
        builder.showDialog();
        AnalyticsManager.logEvent("connection_delete");
    }

    public void openConnectionRoot(Cursor cursor) {
        NetworkConnection connection = NetworkConnection.fromConnectionsCursor(cursor);
        DocumentsActivity activity = ((DocumentsActivity)getActivity());
        if (connection.type.startsWith(TYPE_CLOUD)){
            final CloudConnection cloudConnection = CloudConnection.fromCursor(getActivity(), cursor);
            final RootInfo root = cloudConnection != null
                    ? activity.getRoots().getRootInfo(cloudConnection) : null;
            if (root != null) {
                activity.onRootPicked(root, mConnectionsRoot);
            } else {
                // Saved by an older version of the app, which signed in another way
                Utils.showSnackBar(activity, getString(R.string.cloud_sign_in_title,
                        CloudConnection.getTypeName(activity, connection.type)));
                addCloudConnection(connection.type);
            }
        } else {
            activity.onRootPicked(activity.getRoots().getRootInfo(connection), mConnectionsRoot);
        }
    }

    public void openConnectionRoot(NetworkConnection connection) {
        DocumentsActivity activity = ((DocumentsActivity)getActivity());
        activity.onRootPicked(activity.getRoots().getRootInfo(connection), mConnectionsRoot);
    }

    public void addCloudConnection(String cloudType){
        startActivityForResult(CloudSignInActivity.createIntent(getActivity(), cloudType),
                REQUEST_CLOUD_SIGN_IN);
        AnalyticsManager.logEvent("add_cloud");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CLOUD_SIGN_IN) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            return;
        }
        reload();
        // Straight into the new drive
        final String rootId = data.getStringExtra(CloudSignInActivity.EXTRA_ROOT_ID);
        final DocumentsActivity activity = (DocumentsActivity) getActivity();
        final RootInfo root = activity.getRoots().getRootOneshot(CloudStorageProvider.AUTHORITY, rootId);
        if (root != null) {
            activity.onRootPicked(root, mConnectionsRoot);
        }
    }

    public void menuItemAction(MenuItem menuItem) {
        final BaseActivity activity = (BaseActivity) getActivity();
        if(!DocumentsApplication.isPurchased()){
            DocumentsApplication.openPurchaseActivity(activity);
            return;
        }
        final int itemId = menuItem.getItemId();
        if (itemId == R.id.cloud_gdrive){
            addCloudConnection(TYPE_GDRIVE);
        } else if (itemId == R.id.cloud_dropbox){
            addCloudConnection(TYPE_DROPBOX);
        } else if (itemId == R.id.cloud_onedrive){
            addCloudConnection(TYPE_ONEDRIVE);
        } else if (itemId == R.id.cloud_box){
            addCloudConnection(TYPE_BOX);
        } else if (itemId == R.id.network_ftp){
            addConnection("ftp");
            AnalyticsManager.logEvent("add_ftp");
        } else if (itemId == R.id.network_webdav){
            addConnection(WebDavNetworkClient.SCHEME);
            AnalyticsManager.logEvent("add_webdav");
        }
    }
}