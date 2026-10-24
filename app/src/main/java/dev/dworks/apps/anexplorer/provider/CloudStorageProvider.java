package dev.dworks.apps.anexplorer.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import androidx.annotation.GuardedBy;
import androidx.collection.ArrayMap;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;



import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import dev.dworks.apps.anexplorer.BuildConfig;
import dev.dworks.apps.anexplorer.cloud.CloudConnection;
import dev.dworks.apps.anexplorer.cloud.CloudFile;
import dev.dworks.apps.anexplorer.cursor.MatrixCursor;
import dev.dworks.apps.anexplorer.cursor.MatrixCursor.RowBuilder;
import dev.dworks.apps.anexplorer.libcore.io.IoUtils;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.MimePredicate;
import dev.dworks.apps.anexplorer.misc.MimeTypes;
import dev.dworks.apps.anexplorer.misc.ParcelFileDescriptorUtil;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Document;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Root;

import static dev.dworks.apps.anexplorer.misc.MimeTypes.BASIC_MIME_TYPE;
import static dev.dworks.apps.anexplorer.provider.ExplorerProvider.ConnectionColumns;

/**
 * Created by HaKr on 31/12/16.
 */

public class CloudStorageProvider extends DocumentsProvider {
    private static final String TAG = CloudStorageProvider.class.getSimpleName();

    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".cloudstorage.documents";
    // docId format: address:/path/to/file

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_CAPACITY_BYTES,
            Root.COLUMN_SUMMARY, Root.COLUMN_PATH,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_PATH, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_SUMMARY,
    };

    public static final String TYPE_CLOUD = "cloud";
    public static final String TYPE_GDRIVE = "cloud_gdrive";
    public static final String TYPE_DROPBOX = "cloud_dropbox";
    public static final String TYPE_ONEDRIVE = "cloud_onedrive";
    public static final String TYPE_BOX = "cloud_box";

    private final Object mRootsLock = new Object();

    @GuardedBy("mRootsLock")
    private ArrayMap<String, CloudConnection> mRoots = new ArrayMap<>();

    /** Files seen in listings, so opening one needs no extra request */
    private final LruCache<String, CloudFile> mFiles = new LruCache<>(2000);

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
        synchronized (mFiles) {
            mFiles.evictAll();
        }
        mRoots.clear();
        try {
            String mSelectionClause = ConnectionColumns.TYPE + " LIKE ?";
            String[] mSelectionArgs = {"%"+TYPE_CLOUD+"%"};
            cursor = getContext().getContentResolver().query(ExplorerProvider.buildConnection(),
                    null, mSelectionClause, mSelectionArgs, null);
            while (cursor.moveToNext()) {
                CloudConnection connection = CloudConnection.fromCursor(getContext(), cursor);
                if (connection != null && connection.isLoggedIn()) {
                    mRoots.put(connection.clientId, connection);
                }
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

            for (Map.Entry<String, CloudConnection> root : mRoots.entrySet()) {
                CloudConnection connection = root.getValue();
                String documentId = getDocId(root.getKey(), connection.service.getRootId());

                int flags = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_ADVANCED;

                final RowBuilder row = result.newRow();
                // These columns are required
                row.add(Root.COLUMN_ROOT_ID, root.getKey());
                row.add(Root.COLUMN_DOCUMENT_ID, documentId);
                row.add(Root.COLUMN_TITLE, connection.getTypeName(getContext()));
                row.add(Root.COLUMN_FLAGS, flags);

                // These columns are optional
                row.add(Root.COLUMN_SUMMARY, connection.getSummary());
                row.add(Root.COLUMN_PATH, connection.getPath());
            }
        }

        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {

        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId, getFile(documentId));
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new DocumentCursor(resolveDocumentProjection(projection), parentDocumentId);
        final CloudConnection connection = getCloudConnection(parentDocumentId);
        final String rootId = getRootId(parentDocumentId);
        try {
            for (CloudFile file : connection.service.list(getFileId(parentDocumentId))) {
                file.parentDocumentId = parentDocumentId;
                final String docId = getDocId(rootId, file.id);
                synchronized (mFiles) {
                    mFiles.put(docId, file);
                }
                includeFile(result, docId, file);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to list " + parentDocumentId + ": " + e);
            CrashReportingManager.logException(e);
            throw new FileNotFoundException(e.getMessage());
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, final String mode,
                                             CancellationSignal signal)
            throws FileNotFoundException {
        if (mode.indexOf('w') != -1) {
            throw new FileNotFoundException("Cloud files can't be written in place");
        }
        final CloudFile file = getFile(documentId);
        final CloudConnection connection = getCloudConnection(documentId);
        try {
            InputStream inputStream = connection.getInputStream(file);
            return ParcelFileDescriptorUtil.pipeFrom(new BufferedInputStream(inputStream));
        } catch (Exception e) {
            CrashReportingManager.logException(e);
            throw new FileNotFoundException("Failed to open document with id " + documentId +
                    " and mode " + mode + ": " + e.getMessage());
        }
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {

        final CloudFile file = getFile(documentId);
        final CloudConnection connection = getCloudConnection(documentId);

        final long token = Binder.clearCallingIdentity();

        try {
            InputStream inputStream = connection.getThumbnailInputStream(file);
            if(null == inputStream){
                return null;
            }
            final ParcelFileDescriptor pfd = ParcelFileDescriptorUtil.pipeFrom(inputStream);
            return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (Exception e) {
            throw new FileNotFoundException("Failed to open thumbnail for " + documentId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String createDocument(String documentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (!Document.MIME_TYPE_DIR.equals(mimeType)) {
            throw new FileNotFoundException("Only folders can be made in cloud storage");
        }
        try {
            final CloudConnection connection = getCloudConnection(documentId);
            connection.service.createFolder(getFileId(documentId), displayName);
            notifyDocumentsChanged(getContext(), documentId);
        } catch (Exception e) {
            throw new FileNotFoundException("Failed to create document with name " +
                    displayName +" and documentId " + documentId);
        }
        return documentId;
    }

    @Override
    public boolean uploadDocument(String documentId, Uri uploadDocumentUri, String mimeType, String displayName)
            throws FileNotFoundException {
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            return false;
        }
        final ContentResolver resolver = getContext().getContentResolver();
        try {
            final CloudConnection connection = getCloudConnection(documentId);
            long size;
            try (AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uploadDocumentUri, "r")) {
                size = fd != null ? fd.getLength() : -1;
            }
            try (InputStream in = resolver.openInputStream(uploadDocumentUri)) {
                connection.service.upload(getFileId(documentId), displayName,
                        new BufferedInputStream(in), size, mimeType);
            }
            notifyDocumentsChanged(getContext(), documentId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to upload " + displayName + ": " + e);
            throw new FileNotFoundException("Failed to create document with name " +
                    displayName +" and documentId " + documentId);
        }
        return true;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        final CloudFile file = getFile(documentId);
        final CloudConnection connection = getCloudConnection(documentId);
        try {
            connection.service.delete(file);
            synchronized (mFiles) {
                mFiles.remove(documentId);
            }
            if (file.parentDocumentId != null) {
                notifyDocumentsChanged(getContext(), file.parentDocumentId);
            }
        } catch (Exception e) {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return getTypeForFile(getFile(documentId));
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    private static String getTypeForFile(CloudFile file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }
        final String byName = getTypeForName(file.name);
        if (BASIC_MIME_TYPE.equals(byName) && !TextUtils.isEmpty(file.mimeType)
                && !file.mimeType.startsWith("application/vnd.google-apps.")) {
            return file.mimeType;
        }
        return byName;
    }

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

    /** Document ids are the root id and the service's own file id: cloud_dropbox_4:/movies */
    private static String getDocId(String rootId, String fileId) {
        return rootId + ':' + fileId;
    }

    private static String getRootId(String docId) {
        return docId.substring(0, docId.indexOf(':', 1));
    }

    private static String getFileId(String docId) {
        return docId.substring(docId.indexOf(':', 1) + 1);
    }

    /** The file a document id names, from the last listing or else asked of the service */
    private CloudFile getFile(String docId) throws FileNotFoundException {
        synchronized (mFiles) {
            final CloudFile cached = mFiles.get(docId);
            if (cached != null) {
                return cached;
            }
        }
        final CloudConnection connection = getCloudConnection(docId);
        final String fileId = getFileId(docId);
        if (fileId.equals(connection.service.getRootId())) {
            return new CloudFile(fileId, connection.getTypeName(getContext()), true);
        }
        try {
            final CloudFile file = connection.service.metadata(fileId);
            if (file == null) {
                throw new FileNotFoundException("No document " + docId);
            }
            synchronized (mFiles) {
                mFiles.put(docId, file);
            }
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to look up " + docId + ": " + e.getMessage());
        }
    }

    private void includeFile(MatrixCursor result, String docId, CloudFile file) {
        final String displayName = file.name;
        if (!TextUtils.isEmpty(displayName) && displayName.charAt(0) == '.') {
            return;
        }

        int flags = Document.FLAG_SUPPORTS_DELETE;
        if (file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        }
        final String mimeType = getTypeForFile(file);
        if (MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, mimeType)) {
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, file.size);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_PATH, file.path);
        row.add(Document.COLUMN_FLAGS, flags);

        // Only publish dates reasonably after epoch
        if (file.lastModified > 31536000000L) {
            row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified);
        }
    }

    private CloudConnection getCloudConnection(String docId) throws FileNotFoundException {
        final CloudConnection connection;
        synchronized (mRootsLock) {
            connection = mRoots.get(getRootId(docId));
        }
        if (connection == null) {
            throw new FileNotFoundException("No root for " + docId);
        }
        return connection;
    }

    private class DocumentCursor extends MatrixCursor {
        public DocumentCursor(String[] columnNames, String docId) {
            super(columnNames);

            final Uri notifyUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);
            setNotificationUri(getContext().getContentResolver(), notifyUri);
        }

        @Override
        public void close() {
            super.close();
        }
    }
}
