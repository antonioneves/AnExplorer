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

package dev.dworks.apps.anexplorer.provider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

import dev.dworks.apps.anexplorer.BuildConfig;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.cursor.MatrixCursor;
import dev.dworks.apps.anexplorer.cursor.MatrixCursor.RowBuilder;
import dev.dworks.apps.anexplorer.misc.FileUtils;
import dev.dworks.apps.anexplorer.misc.StorageUtils;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Document;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Root;

import static android.content.pm.PackageManager.GET_UNINSTALLED_PACKAGES;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;

/**
 * Presents a {@link DocumentsContract} view of the installed apps.
 */
@SuppressLint("DefaultLocale")
public class AppsProvider extends DocumentsProvider {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".apps.documents";
    // docId format: user_apps:com.package
    // docId format: system_apps:com.package

    public static final String ROOT_ID_USER_APP = "user_apps:";
    public static final String ROOT_ID_SYSTEM_APP = "system_apps:";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_CAPACITY_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_PATH, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SUMMARY, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
    };

    private PackageManager packageManager;

    @Override
    public boolean onCreate() {
        packageManager = getContext().getPackageManager();
        return true;
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    public static void notifyDocumentsChanged(Context context, String rootId) {
        Uri uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootId);
        context.getContentResolver().notifyChange(uri, null, false);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        includeRoot(result, ROOT_ID_USER_APP, R.drawable.ic_root_apps, R.string.root_apps);
        includeRoot(result, ROOT_ID_SYSTEM_APP, R.drawable.ic_root_system_apps, R.string.root_system_apps);
        return result;
    }

    private void includeRoot(MatrixCursor result, String rootId, int icon, int title) {
        final StorageUtils storageUtils = new StorageUtils(getContext());
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, rootId);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_SEARCH);
        row.add(Root.COLUMN_ICON, icon);
        row.add(Root.COLUMN_TITLE, getContext().getString(title));
        row.add(Root.COLUMN_DOCUMENT_ID, rootId);
        row.add(Root.COLUMN_AVAILABLE_BYTES, storageUtils.getPartionSize(StorageUtils.PARTITION_DATA, false));
        row.add(Root.COLUMN_CAPACITY_BYTES, storageUtils.getPartionSize(StorageUtils.PARTITION_DATA, true));
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeApps(result, rootId, query.toLowerCase());
        return result;
    }

    /**
     * Saves the app's APK to the AppBackup folder, named after its label and version.
     * The target is ignored: APKs always go to AppBackup.
     */
    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        final String packageName = getPackageForDocId(sourceDocumentId);
        final PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new FileNotFoundException(packageName);
        }
        final ApplicationInfo appInfo = packageInfo.applicationInfo;
        final String fileName = getAppLabel(appInfo) + getAppVersion(packageInfo.versionName);
        final File fileFrom = new File(appInfo.sourceDir);
        final File fileTo = Utils.getAppsBackupFile(getContext());
        if (!fileTo.exists()) {
            fileTo.mkdirs();
        }
        if (!FileUtils.copyDocument(fileFrom, fileTo, fileName)) {
            throw new IllegalStateException("Failed to copy " + fileFrom);
        }
        FileUtils.updateMediaStore(getContext(), FileUtils.makeFilePath(fileTo.getPath(),
                fileName + "." + FileUtils.getExtFromFilename(fileFrom.getPath())));
        return sourceDocumentId;
    }

    @Override
    public Cursor queryDocument(String docId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String docId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final MatrixCursor result = new DocumentCursor(resolveDocumentProjection(projection), docId);
        final long token = Binder.clearCallingIdentity();
        try {
            includeApps(result, docId, null);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        throw new FileNotFoundException(docId);
    }

    private void includeApps(MatrixCursor result, String rootId, String query) {
        final boolean showSystem = rootId.startsWith(ROOT_ID_SYSTEM_APP);
        final String parentId = showSystem ? ROOT_ID_SYSTEM_APP : ROOT_ID_USER_APP;
        final List<PackageInfo> packages = packageManager.getInstalledPackages(getAppListFlag());
        for (PackageInfo packageInfo : packages) {
            includeAppFromPackage(result, parentId, packageInfo, showSystem, query);
        }
    }

    private void includeAppFromPackage(MatrixCursor result, String rootId, PackageInfo packageInfo,
                                       boolean showSystem, String query) {
        final ApplicationInfo appInfo = packageInfo.applicationInfo;
        if (appInfo == null || showSystem != isSystemApp(appInfo)) {
            return;
        }
        final String displayName = getAppLabel(appInfo);
        if (null != query && !displayName.toLowerCase().contains(query)
                && !packageInfo.packageName.toLowerCase().contains(query)) {
            return;
        }

        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, rootId + packageInfo.packageName);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SUMMARY, packageInfo.packageName);
        row.add(Document.COLUMN_SIZE, new File(appInfo.sourceDir).length());
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_APK);
        row.add(Document.COLUMN_LAST_MODIFIED, packageInfo.lastUpdateTime);
        row.add(Document.COLUMN_PATH, appInfo.sourceDir);
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_COPY | Document.FLAG_SUPPORTS_THUMBNAIL);
    }

    private String getAppLabel(ApplicationInfo appInfo) {
        final CharSequence label = appInfo.loadLabel(packageManager);
        return TextUtils.isEmpty(label) ? appInfo.packageName : label.toString();
    }

    private static String getAppVersion(String packageVersion) {
        return TextUtils.isEmpty(packageVersion) ? "" : "-" + packageVersion;
    }

    private static int getAppListFlag() {
        return Utils.hasNougat() ? MATCH_UNINSTALLED_PACKAGES : GET_UNINSTALLED_PACKAGES;
    }

    private static boolean isSystemApp(ApplicationInfo appInfo) {
        return (appInfo.flags & (ApplicationInfo.FLAG_UPDATED_SYSTEM_APP | ApplicationInfo.FLAG_SYSTEM)) != 0;
    }

    public static String getPackageForDocId(String docId) {
        return docId.substring(docId.indexOf(':', 1) + 1);
    }

    private class DocumentCursor extends MatrixCursor {
        public DocumentCursor(String[] columnNames, String docId) {
            super(columnNames);
            final Uri notifyUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);
            setNotificationUri(getContext().getContentResolver(), notifyUri);
        }
    }
}
