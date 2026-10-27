package dev.dworks.apps.anexplorer.cloud;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.provider.BaseColumns;
import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.libcore.io.IoUtils;
import dev.dworks.apps.anexplorer.provider.ExplorerProvider;
import dev.dworks.apps.anexplorer.provider.ExplorerProvider.ConnectionColumns;

import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorInt;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorString;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_BOX;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_DROPBOX;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_GDRIVE;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_ONEDRIVE;

/** A signed-in cloud account, saved as a row of the connections table */
public class CloudConnection implements CloudService.TokenListener {

    private static final String ROOT = "/";

    public final CloudService service;
    /** The connection's row id, 0 until it is saved */
    public int id;
    /** The root id the provider shows it under, like cloud_dropbox_4 */
    public String clientId;
    public String name;
    public String username;
    private boolean isLoggedIn;
    private Context mContext;

    public CloudConnection(Context context, CloudService service) {
        this.mContext = context.getApplicationContext();
        this.service = service;
    }

    public static CloudConnection fromCursor(Context context, Cursor cursor) {
        final String type = getCursorString(cursor, ConnectionColumns.TYPE);
        final CloudService service = createService(context, type);
        if (service == null) {
            return null;
        }
        final CloudConnection connection = new CloudConnection(context, service);
        connection.id = getCursorInt(cursor, BaseColumns._ID);
        connection.clientId = getCloudStorageId(type, connection.id);
        connection.name = getCursorString(cursor, ConnectionColumns.NAME);
        connection.username = getCursorString(cursor, ConnectionColumns.USERNAME);
        service.userName = connection.name;
        service.userLogin = connection.username;
        connection.isLoggedIn = service.loadTokens(
                getCursorString(cursor, ConnectionColumns.PASSWORD));
        service.setTokenListener(connection);
        return connection;
    }

    public static CloudService createService(Context context, String type) {
        if (TYPE_GDRIVE.equals(type)) {
            return new GoogleDriveService(context);
        } else if (TYPE_DROPBOX.equals(type)) {
            return new DropboxService(context);
        } else if (TYPE_ONEDRIVE.equals(type)) {
            return new OneDriveService(context);
        } else if (TYPE_BOX.equals(type)) {
            return new BoxService(context);
        }
        return null;
    }

    /** Whether the service has the app keys it needs, built in or entered in Settings */
    public static boolean isConfigured(Context context, String type) {
        if (TYPE_DROPBOX.equals(type)) {
            return DropboxService.isConfigured(context);
        } else if (TYPE_ONEDRIVE.equals(type)) {
            return OneDriveService.isConfigured(context);
        } else if (TYPE_BOX.equals(type)) {
            return BoxService.isConfigured(context);
        }
        // Google Drive is set up in the Google Cloud console instead, by package and signature
        return TYPE_GDRIVE.equals(type);
    }

    public String getType() {
        return service.getType();
    }

    public String getPath() {
        return ROOT;
    }

    public String getSummary() {
        return username;
    }

    public boolean isLoggedIn() {
        return isLoggedIn;
    }

    public String getTypeName(Context context) {
        return getTypeName(context, getType());
    }

    public static String getTypeName(Context context, String type) {
        if (TYPE_GDRIVE.equals(type)) {
            return context.getString(R.string.cloud_gdrive);
        } else if (TYPE_DROPBOX.equals(type)) {
            return context.getString(R.string.cloud_dropbox);
        } else if (TYPE_ONEDRIVE.equals(type)) {
            return context.getString(R.string.cloud_onedrive);
        } else if (TYPE_BOX.equals(type)) {
            return context.getString(R.string.cloud_box);
        }
        return context.getString(R.string.cloud);
    }

    public InputStream getInputStream(CloudFile file) throws IOException {
        return service.download(file);
    }

    public InputStream getThumbnailInputStream(CloudFile file) throws IOException {
        return service.thumbnail(file);
    }

    /**
     * Saves a freshly signed-in account. Signing in to the same account again replaces it,
     * as the table keeps one row per name, host and path.
     */
    public boolean save() {
        final ContentValues values = new ContentValues();
        values.put(ConnectionColumns.NAME, TextUtils.isEmpty(service.userName)
                ? service.userLogin : service.userName);
        values.put(ConnectionColumns.SCHEME, "");
        values.put(ConnectionColumns.TYPE, getType());
        values.put(ConnectionColumns.HOST, getType() + ":" + service.userLogin);
        values.put(ConnectionColumns.PATH, ROOT);
        values.put(ConnectionColumns.USERNAME, service.userLogin);
        values.put(ConnectionColumns.PASSWORD, service.saveTokens());
        values.put(ConnectionColumns.ANONYMOUS_LOGIN, false);
        if (mContext.getContentResolver().insert(ExplorerProvider.buildConnection(), values) == null) {
            return false;
        }

        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(ExplorerProvider.buildConnection(), null,
                    ConnectionColumns.TYPE + "=? AND " + ConnectionColumns.HOST + "=?",
                    new String[]{getType(), getType() + ":" + service.userLogin}, null);
            if (cursor != null && cursor.moveToFirst()) {
                id = getCursorInt(cursor, BaseColumns._ID);
                clientId = getCloudStorageId(getType(), id);
                name = getCursorString(cursor, ConnectionColumns.NAME);
                username = service.userLogin;
                isLoggedIn = true;
                return true;
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }
        return false;
    }

    /** Keeps renewed tokens so the account stays signed in */
    @Override
    public void onTokensChanged(CloudService service) {
        if (id == 0) {
            return;
        }
        final ContentValues values = new ContentValues();
        values.put(ConnectionColumns.PASSWORD, service.saveTokens());
        mContext.getContentResolver().update(ExplorerProvider.buildConnection(), values,
                BaseColumns._ID + "=?", new String[]{Integer.toString(id)});
    }

    public static String getCloudStorageId(String type, int id) {
        return type + "_" + id;
    }
}
