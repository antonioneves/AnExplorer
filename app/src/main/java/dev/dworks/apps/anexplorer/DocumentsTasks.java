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

package dev.dworks.apps.anexplorer;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;

import dev.dworks.apps.anexplorer.libcore.io.IoUtils;
import dev.dworks.apps.anexplorer.misc.AsyncTask;
import dev.dworks.apps.anexplorer.misc.ContentProviderClientCompat;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DurableUtils;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.provider.ExternalStorageProvider;
import dev.dworks.apps.anexplorer.provider.RecentsProvider;
import dev.dworks.apps.anexplorer.provider.RecentsProvider.ResumeColumns;

import static dev.dworks.apps.anexplorer.BaseActivity.TAG;
import static dev.dworks.apps.anexplorer.fragment.DirectoryFragment.ANIM_NONE;
import static dev.dworks.apps.anexplorer.fragment.DirectoryFragment.ANIM_SIDE;

/**
 * The background work {@link DocumentsActivity} starts: restoring where the user was, opening a
 * root, and finishing a picker or save. Each task only reaches for what it needs from the
 * activity, which is why they live here and not inside it.
 */
final class DocumentsTasks {
    private DocumentsTasks() {
    }

    static class RestoreRootTask extends AsyncTask<Void, Void, RootInfo> {
        private final DocumentsActivity mActivity;
        private final Uri mRootUri;

        RestoreRootTask(DocumentsActivity activity, Uri rootUri) {
            mActivity = activity;
            mRootUri = rootUri;
        }

        @Override
        protected RootInfo doInBackground(Void... params) {
            final String rootId = DocumentsContract.getRootId(mRootUri);
            return mActivity.mRoots.getRootOneshot(mRootUri.getAuthority(), rootId);
        }

        @Override
        protected void onPostExecute(RootInfo root) {
            if (!Utils.isActivityAlive(mActivity)) {
                return;
            }
            mActivity.mState.restored = true;

            if (root != null) {
                mActivity.onRootPicked(root, true);
            } else {
                Log.w(TAG, "Failed to find root: " + mRootUri);
                mActivity.finish();
            }
        }
    }

    static class RestoreStackTask extends AsyncTask<Void, Void, Void> {
        private final DocumentsActivity mActivity;
        private volatile boolean mRestoredStack;
        private volatile boolean mExternal;

        RestoreStackTask(DocumentsActivity activity) {
            mActivity = activity;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final BaseActivity.State state = mActivity.mState;
            // Restore last stack for calling package
            final String packageName = mActivity.getCallingPackageMaybeExtra();
            final Cursor cursor = mActivity.getContentResolver()
                    .query(RecentsProvider.buildResume(packageName), null, null, null, null);
            try {
                if (null != cursor && cursor.moveToFirst()) {
                    mExternal = cursor.getInt(cursor.getColumnIndex(ResumeColumns.EXTERNAL)) != 0;
                    final byte[] rawStack = cursor.getBlob(
                            cursor.getColumnIndex(ResumeColumns.STACK));
                    DurableUtils.readFromArray(rawStack, state.stack);
                    mRestoredStack = true;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to resume: " + e);
                CrashReportingManager.logException(e);
            } finally {
                IoUtils.closeQuietly(cursor);
            }

            if (mRestoredStack) {
                // Update the restored stack to ensure we have freshest data
                final Collection<RootInfo> matchingRoots = mActivity.mRoots.getMatchingRootsBlocking(state);
                try {
                    state.stack.updateRoot(matchingRoots);
                    state.stack.updateDocuments(mActivity.getContentResolver());
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Failed to restore stack: " + e);
                    CrashReportingManager.logException(e);
                    state.stack.reset();
                    mRestoredStack = false;
                }
            } else {
                RootInfo root = mActivity.getCurrentRoot();
                // Home and the other screens of the app's own have no documents to open
                if (null == root || null == root.authority) {
                    return null;
                }
                final Uri uri = DocumentsContract.buildDocumentUri(root.authority, root.documentId);
                try {
                    final DocumentInfo result = DocumentInfo.fromUri(mActivity.getContentResolver(), uri);
                    if (result != null) {
                        state.stack.push(result);
                        state.stackTouched = true;
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                    CrashReportingManager.logException(e);
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (!Utils.isActivityAlive(mActivity)) {
                return;
            }
            mActivity.mState.restored = true;

            mActivity.onCurrentDirectoryChanged(ANIM_NONE);
        }
    }

    static class PickRootTask extends AsyncTask<Void, Void, DocumentInfo> {
        private final DocumentsActivity mActivity;
        private final RootInfo mRoot;

        PickRootTask(DocumentsActivity activity, RootInfo root) {
            mActivity = activity;
            mRoot = root;
        }

        @Override
        protected DocumentInfo doInBackground(Void... params) {
            try {
                final Uri uri = DocumentsContract.buildDocumentUri(
                        mRoot.authority, mRoot.documentId);
                return DocumentInfo.fromUri(mActivity.getContentResolver(), uri);
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to find root", e);
                CrashReportingManager.logException(e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(DocumentInfo result) {
            if (!Utils.isActivityAlive(mActivity)) {
                return;
            }
            if (result != null) {
                mActivity.mState.stack.push(result);
                mActivity.mState.stackTouched = true;
                mActivity.onCurrentDirectoryChanged(ANIM_SIDE);
            }
        }
    }

    static class UploadFileTask extends AsyncTask<Void, Void, Boolean> {
        private final DocumentsActivity mActivity;
        private final DocumentInfo mCwd;
        private final String mMimeType;
        private final String mDisplayName;
        private final Uri mUri;

        UploadFileTask(DocumentsActivity activity, Uri uri, String name, String mimeType) {
            mActivity = activity;
            mCwd = activity.getCurrentDirectory();
            mDisplayName = name;
            mMimeType = mimeType;
            mUri = uri;
        }

        @Override
        protected void onPreExecute() {
            mActivity.setPending(true);
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            final ContentResolver resolver = mActivity.getContentResolver();
            ContentProviderClient client = null;
            Boolean hadTrouble = false;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, mCwd.derivedUri.getAuthority());
                hadTrouble = !DocumentsContract.uploadDocument(
                        resolver, mCwd.derivedUri, mUri, mMimeType, mDisplayName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to upload document", e);
                CrashReportingManager.logException(e);
            } finally {
                ContentProviderClientCompat.releaseQuietly(client);
            }

            return hadTrouble;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                Utils.showError(mActivity, R.string.upload_error);
            }
            mActivity.setPending(false);
        }
    }

    static class CreateFinishTask extends AsyncTask<Void, Void, Uri> {
        private final DocumentsActivity mActivity;
        private final String mMimeType;
        private final String mDisplayName;

        CreateFinishTask(DocumentsActivity activity, String mimeType, String displayName) {
            mActivity = activity;
            mMimeType = mimeType;
            mDisplayName = displayName;
        }

        @Override
        protected void onPreExecute() {
            mActivity.setPending(true);
        }

        @Override
        protected Uri doInBackground(Void... params) {
            final ContentResolver resolver = mActivity.getContentResolver();
            final DocumentInfo cwd = mActivity.getCurrentDirectory();

            ContentProviderClient client = null;
            Uri childUri = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, cwd.derivedUri.getAuthority());
                childUri = DocumentsContract.createDocument(
                        resolver, cwd.derivedUri, mMimeType, mDisplayName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create document", e);
                CrashReportingManager.logException(e);
            } finally {
                ContentProviderClientCompat.releaseQuietly(client);
            }

            if (childUri != null) {
                mActivity.saveStackBlocking();
            }

            return childUri;
        }

        @Override
        protected void onPostExecute(Uri result) {
            if (!Utils.isActivityAlive(mActivity)) {
                return;
            }
            if (result != null) {
                mActivity.onFinished(result);
            } else {
                final DocumentInfo cwd = mActivity.getCurrentDirectory();
                if (!mActivity.isSAFIssue(cwd.documentId)) {
                    Utils.showError(mActivity, R.string.save_error);
                }
            }
            mActivity.setPending(false);
        }
    }

    /** Saves the stack, then hands the chosen documents back to the caller */
    static class ExistingFinishTask extends AsyncTask<Void, Void, Void> {
        private final DocumentsActivity mActivity;
        private final Uri[] mUris;

        ExistingFinishTask(DocumentsActivity activity, Uri... uris) {
            mActivity = activity;
            mUris = uris;
        }

        @Override
        protected Void doInBackground(Void... params) {
            mActivity.saveStackBlocking();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mActivity.onFinished(mUris);
        }
    }

    static class PickFinishTask extends AsyncTask<Void, Void, Void> {
        private final DocumentsActivity mActivity;
        private final Uri mUri;

        PickFinishTask(DocumentsActivity activity, Uri uri) {
            mActivity = activity;
            mUri = uri;
        }

        @Override
        protected Void doInBackground(Void... params) {
            mActivity.saveStackBlocking();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mActivity.onFinished(mUri);
        }
    }

    static class EmptyRecycleBinTask extends AsyncTask<Void, Void, Boolean> {
        private final DocumentsActivity mActivity;

        EmptyRecycleBinTask(DocumentsActivity activity) {
            mActivity = activity;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            return ExternalStorageProvider.callRecycleBin(mActivity,
                    ExternalStorageProvider.METHOD_EMPTY_BIN, null);
        }

        @Override
        protected void onPostExecute(Boolean emptied) {
            if (!emptied && Utils.isActivityAlive(mActivity)) {
                Utils.showError(mActivity, R.string.toast_failed_delete);
            }
        }
    }
}
