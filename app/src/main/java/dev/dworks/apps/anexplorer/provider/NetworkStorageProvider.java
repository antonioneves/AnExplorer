package dev.dworks.apps.anexplorer.provider;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import androidx.collection.ArrayMap;
import android.text.TextUtils;
import android.util.Log;

import org.apache.commons.net.ftp.FTPFile;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.dworks.apps.anexplorer.BuildConfig;
import dev.dworks.apps.anexplorer.cursor.MatrixCursor;
import dev.dworks.apps.anexplorer.cursor.MatrixCursor.RowBuilder;
import dev.dworks.apps.anexplorer.libcore.io.IoUtils;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.misc.MimePredicate;
import dev.dworks.apps.anexplorer.misc.MimeTypes;
import dev.dworks.apps.anexplorer.misc.ParcelFileDescriptorUtil;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Document;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Root;
import androidx.annotation.GuardedBy;
import dev.dworks.apps.anexplorer.network.NetworkClient;
import dev.dworks.apps.anexplorer.network.NetworkConnection;
import dev.dworks.apps.anexplorer.network.NetworkFile;

import static dev.dworks.apps.anexplorer.misc.MimeTypes.BASIC_MIME_TYPE;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorInt;
import static dev.dworks.apps.anexplorer.network.NetworkConnection.SERVER;
import static dev.dworks.apps.anexplorer.provider.CloudStorageProvider.TYPE_CLOUD;
import static dev.dworks.apps.anexplorer.provider.ExplorerProvider.ConnectionColumns;

/**
 * Created by HaKr on 31/12/16.
 */

public class NetworkStorageProvider extends DocumentsProvider {
    private static final String TAG = NetworkStorageProvider.class.getSimpleName();

    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".networkstorage.documents";
    // docId format: adress:/path/to/file

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_CAPACITY_BYTES,
            Root.COLUMN_SUMMARY, Root.COLUMN_PATH,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_PATH, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_SUMMARY,
    };

    private final Object mRootsLock = new Object();

    @GuardedBy("mRootsLock")
    private ArrayMap<String, NetworkConnection> mRoots = new ArrayMap<>();

    /** Folders made here, which a lookup by ID alone would otherwise take for files */
    private final Set<String> mMadeFolders = Collections.synchronizedSet(new HashSet<>());

    @Override
    public boolean onCreate() {
        updateRoots();
        return true;
    }

    @Override
    public void updateRoots() {
        updateConnections();
    }

    public void updateConnections() {
        Cursor cursor = null;
        mRoots.clear();
        try {
            String mSelectionClause = ConnectionColumns.TYPE + " NOT LIKE ?";
            String[] mSelectionArgs = {"%"+TYPE_CLOUD+"%"};
            cursor = getContext().getContentResolver().query(ExplorerProvider.buildConnection(),
                    null, mSelectionClause, mSelectionArgs, null);
            while (cursor.moveToNext()) {
                NetworkConnection networkConnection = NetworkConnection.fromConnectionsCursor(cursor);
                mRoots.put(networkConnection.getRootId(), networkConnection);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load some roots from " + ExplorerProvider.AUTHORITY + ": " + e);
            CrashReportingManager.logException(e);
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        notifyRootsChanged(getContext());
    }

    public static void notifyRootsChanged(Context context) {
        context.getContentResolver()
                .notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null, false);
    }

    public static void notifyDocumentsChanged(Context context, String rootId) {
        Uri uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootId);
        context.getContentResolver().notifyChange(uri, null, false);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        synchronized (mRootsLock) {

            for (Map.Entry<String, NetworkConnection> root : mRoots.entrySet()) {
                NetworkConnection networkConnection = root.getValue();
                String documentId = getDocIdForFile(networkConnection.file);

                int flags = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_ADVANCED
                        | Root.FLAG_SUPPORTS_IS_CHILD;

                boolean isServer = networkConnection.getType().compareToIgnoreCase(SERVER) == 0;
                if(isServer){
                    if(!Utils.hasWiFi(getContext())) {
                        continue;
                    }
                    flags |= Root.FLAG_CONNECTION_SERVER;
                }

                final RowBuilder row = result.newRow();
                // These columns are required
                row.add(Root.COLUMN_ROOT_ID, root.getKey());
                row.add(Root.COLUMN_DOCUMENT_ID, documentId);
                // Named for what it is, whatever it was stored as ("Transfer to PC" on older installs)
                row.add(Root.COLUMN_TITLE, isServer
                        ? getContext().getString(R.string.ftp_server_title) : networkConnection.name);
                row.add(Root.COLUMN_FLAGS, flags);
                if (!isServer) {
                    row.add(Root.COLUMN_ICON, IconUtils.schemeIcon(networkConnection.getScheme()));
                }

                // These columns are optional
                row.add(Root.COLUMN_SUMMARY, isServer ? networkConnection.getPath() : networkConnection.getSummary());
                row.add(Root.COLUMN_PATH, networkConnection.getPath());
            }
        }

        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {

        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        // Lists again when a document is made or deleted in the folder
        result.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId));
        final NetworkFile parent = getFileForDocId(parentDocumentId);
        final NetworkConnection connection = getNetworkConnection(parentDocumentId);
        FTPFile[] files;
        try {
            files = listFiles(connection, parent);
        } catch (IOException e) {
            // The server may have dropped a connection that still looks open, so try once more
            connection.reconnect();
            try {
                files = listFiles(connection, parent);
            } catch (IOException again) {
                CrashReportingManager.logException(again);
                // Tells an unreachable server apart from an empty folder
                final Bundle extras = new Bundle();
                extras.putString(DocumentsContract.EXTRA_ERROR,
                        getContext().getString(R.string.server_connect_failed));
                result.setExtras(extras);
                files = new FTPFile[0];
            }
        }
        for (FTPFile file : files) {
            includeFile(result, null, new NetworkFile(parent, file));
        }
        return result;
    }

    private static FTPFile[] listFiles(NetworkConnection connection, NetworkFile folder)
            throws IOException {
        final NetworkClient client = connection.getConnectedClient();
        client.changeWorkingDirectory(folder.getPath());
        return client.listFiles();
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, final String mode,
                                             CancellationSignal signal)
            throws FileNotFoundException {

        final NetworkFile file = getFileForDocId(documentId);
        final NetworkConnection connection = getNetworkConnection(documentId);

        try {
            final boolean isWrite = (mode.indexOf('w') != -1);
            if (isWrite) {
                return upload(connection, file);
            }
            final InputStream stream = connection.getClient().openInputStream(file.getPath());
            if (null != stream) {
                return ParcelFileDescriptorUtil.pipeFrom(stream);
            } else {
                Uri ftpUri = connection.toUri(file);
                URL url = new URL(ftpUri.toString());
                URLConnection conn = url.openConnection();
                InputStream inputStream = conn.getInputStream();
                if(null != inputStream){
                    return ParcelFileDescriptorUtil.pipeFrom(inputStream);
                }
            }

            return null;
        } catch (Exception e) {
            CrashReportingManager.logException(e);
            throw new FileNotFoundException("Failed to open document with id " + documentId +
                    " and mode " + mode);
        }
    }

    @Override
    public String createDocument(String documentId, String mimeType, String displayName)
            throws FileNotFoundException {

        NetworkFile parent = getFileForDocId(documentId);
        NetworkFile file = new NetworkFile(NetworkFile.join(parent.getPath(), displayName), parent.getHost());
        final NetworkConnection connection = getNetworkConnection(documentId);
        try {
            final NetworkClient client = connection.getConnectedClient();
            final boolean created = Document.MIME_TYPE_DIR.equals(mimeType)
                    ? client.createDirectories(file.getPath()) : client.createFile(file.getPath());
            if (!created) {
                throw new IOException("Server refused to create " + file.getPath());
            }
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                mMadeFolders.add(getDocIdForFile(file));
            }
            notifyFolderChanged(documentId);
        } catch (IOException e) {
            final FileNotFoundException failed = new FileNotFoundException(
                    "Failed to create document with name " + displayName + " and documentId " + documentId);
            failed.initCause(e);
            throw failed;
        }
        return getDocIdForFile(file);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        NetworkFile file = getFileForDocId(documentId);
        final NetworkConnection connection = getNetworkConnection(documentId);
        try {
            if (!connection.getConnectedClient().deleteFile(file.getPath())) {
                throw new IOException("Server refused to delete " + file.getPath());
            }
            notifyFolderChanged(getDocIdForFile(file.getParentFile()));
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
    }

    /**
     * Sends what's written to the server. The writer gets one end of a socket, and this end
     * closes once the server has the whole file, or with an error if it hasn't; so a copy can
     * wait for the upload to finish before it, say, deletes the original.
     */
    private ParcelFileDescriptor upload(NetworkConnection connection, NetworkFile file)
            throws IOException {
        final String folderId = getDocIdForFile(file.getParentFile());
        final OutputStream server = connection.getClient().openOutputStream(file.getPath());
        final ParcelFileDescriptor[] pair = ParcelFileDescriptor.createReliableSocketPair();
        final ParcelFileDescriptor ours = pair[0];
        new Thread(() -> {
            try {
                try (InputStream written = new FileInputStream(ours.getFileDescriptor());
                     OutputStream out = server) {
                    IoUtils.copy(written, out);
                }
                ours.close();
            } catch (IOException e) {
                Log.w(TAG, "Upload of " + file.getPath() + " failed: " + e);
                try {
                    ours.closeWithError(e.getMessage());
                } catch (IOException ignored) {
                }
            }
            // Shown with its size once the server has all of it
            notifyFolderChanged(folderId);
        }, "Network upload").start();
        return pair[1];
    }

    /** So that a folder on screen lists what was made in it or taken out of it */
    private void notifyFolderChanged(String folderDocumentId) {
        getContext().getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, folderDocumentId), null, false);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        NetworkFile file = getFileForDocId(documentId);
        return getTypeForFile(file);
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    /**
     * Get a file's MIME type
     *
     * @param file the File object whose type we want
     * @return the MIME type of the file
     */
    private static String getTypeForFile(NetworkFile file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            return getTypeForName(file.getName());
        }
    }

    /**
     * Get the MIME data type of a document, given its filename.
     *
     * @param name the filename of the document
     * @return the MIME data type of a document
     */
    private static String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1);
            final String mime = MimeTypes.getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return BASIC_MIME_TYPE;
    }

    /**
     * Get the document ID given a File.  The document id must be consistent across time.  Other
     * applications may save the ID and use it to reference documents later.
     * <p/>
     * This implementation is specific to this demo.  It assumes only one root and is built
     * directly from the file structure.  However, it is possible for a document to be a child of
     * multiple directories (for example "android" and "images"), in which case the file must have
     * the same consistent, unique document ID in both cases.
     *
     * @param file the File whose document ID you want
     * @return the corresponding document ID
     */
    private String getDocIdForFile(NetworkFile file) throws FileNotFoundException {
        String path = file.getAbsolutePath();
        String host = file.getHost();

        // Find the most-specific root file
        String mostSpecificId = null;
        String mostSpecificPath = null;
        synchronized (mRootsLock) {
            for (int i = 0; i < mRoots.size(); i++) {
                final String rootId = mRoots.keyAt(i);
                final String rootPath = mRoots.valueAt(i).file.getPath();
                if (host.equals(rootId)) {
                    mostSpecificId = rootId;
                    mostSpecificPath = rootPath;
                }
            }
        }

        if (mostSpecificPath == null) {
            throw new FileNotFoundException("Failed to find root that contains " + path);
        }

        // Start at first char of file under root
        final String rootPath = mostSpecificPath;
        if (rootPath.equals(path)) {
            path = "";
        } else if (rootPath.endsWith("/")) {
            path = path.substring(rootPath.length());
        } else {
            path = path.substring(rootPath.length() + 1);
        }

        return mostSpecificId + ':' + path;
    }

    /**
     * Translate your custom URI scheme into a File object.
     *
     * @param docId the document ID representing the desired file
     * @return a File represented by the given document ID
     * @throws java.io.FileNotFoundException
     */
    private NetworkFile getFileForDocId(String docId) throws FileNotFoundException {
        final int splitIndex = docId.indexOf(':', 1);
        final String tag = docId.substring(0, splitIndex);
        final String path = docId.substring(splitIndex + 1);

        NetworkConnection root;
        synchronized (mRootsLock) {
            root = mRoots.get(tag);
        }
        if (root == null) {
            throw new FileNotFoundException("No root for " + tag);
        }

        NetworkFile target = root.file;
        if (target == null) {
            return null;
        }
        target = new NetworkFile(target.getAbsolutePath() + path, tag);
        return target;
    }

    private String getRootId(String docId){
        final int splitIndex = docId.indexOf(':', 1);
        final String tag = docId.substring(0, splitIndex);
        return tag;
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId  the document ID representing the desired file (may be null if given file)
     * @param file   the File object representing the desired file (may be null if given docID)
     * @throws java.io.FileNotFoundException
     */
    private void includeFile(MatrixCursor result, String docId, NetworkFile file)
            throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
            if (mMadeFolders.contains(docId)) {
                final FTPFile folder = new FTPFile();
                folder.setName(file.getName());
                folder.setType(FTPFile.DIRECTORY_TYPE);
                folder.setTimestamp(Calendar.getInstance());
                file = new NetworkFile(file.getParentFile(), folder);
            }
        }

        int flags = 0;

        final NetworkConnection connection = getNetworkConnection(docId);
        final NetworkClient client = null != connection ? connection.getClient() : null;
        if (file.canWrite()) {
            if (file.isDirectory()) {
                if (null != client && client.canCreateIn(file.getPath())) {
                    flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
                }
            } else {
                flags |= Document.FLAG_SUPPORTS_WRITE;
            }
/*            flags |= Document.FLAG_SUPPORTS_DELETE;
            flags |= Document.FLAG_SUPPORTS_RENAME;
            flags |= Document.FLAG_SUPPORTS_MOVE;
            flags |= Document.FLAG_SUPPORTS_EDIT;*/
            if (null != client && client.canDelete(file.getPath())) {
                flags |= Document.FLAG_SUPPORTS_DELETE;
            }
        }

        final String mimeType = getTypeForFile(file);
        final String displayName = file.getName();
        if (!TextUtils.isEmpty(displayName)) {
            if(displayName.charAt(0) == '.'){
                return;
            }
        }
        if(MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, mimeType)){
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, file.getSize());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_PATH, file.getAbsolutePath());
        row.add(Document.COLUMN_FLAGS, flags);

        // Only publish dates reasonably after epoch
        long lastModified = file.lastModified();
        if (lastModified > 31536000000L) {
            row.add(Document.COLUMN_LAST_MODIFIED, lastModified);
        }
    }

    /**
     * Determine whether the user is logged in.
     */
    private boolean isUserLoggedIn(String docId) {
        return getNetworkConnection(docId).isLoggedIn();
    }

    private NetworkConnection getNetworkConnection(String docId){
        synchronized (mRootsLock) {
            return mRoots.get(getRootId(docId));
        }
    }

    public static boolean addUpdateConnection(Context context, NetworkConnection connection, int id) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(ConnectionColumns.NAME, connection.getName());
        contentValues.put(ConnectionColumns.SCHEME, connection.getScheme());
        contentValues.put(ConnectionColumns.TYPE, connection.getType());
        contentValues.put(ConnectionColumns.PATH, connection.getPath());
        contentValues.put(ConnectionColumns.HOST, connection.getHost());
        contentValues.put(ConnectionColumns.PORT, connection.getPort());
        contentValues.put(ConnectionColumns.USERNAME, connection.getUserName());
        contentValues.put(ConnectionColumns.PASSWORD, connection.getPassword());
        contentValues.put(ConnectionColumns.ANONYMOUS_LOGIN, connection.isAnonymousLogin());
        contentValues.put(ConnectionColumns.PRIVATE_KEY, connection.getPrivateKey());
        Uri uri = null;
        int updated_id = 0;
        if(id == 0) {
            uri = context.getContentResolver().insert(ExplorerProvider.buildConnection(), contentValues);
        } else {
            updated_id  = context.getContentResolver().update(ExplorerProvider.buildConnection(), contentValues,
                    ConnectionColumns._ID + " = ? ", new String[]{String.valueOf(id)});
        }
        return null != uri || 0 != updated_id;
    }
}