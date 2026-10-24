package dev.dworks.apps.anexplorer.directory;

import android.content.Context;
import android.net.Uri;
import androidx.core.os.CancellationSignal;
import androidx.core.os.OperationCanceledException;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.TextView;

import java.io.File;

import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.AsyncTask;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.ProviderExecutor;
import dev.dworks.apps.anexplorer.misc.Utils;

import static dev.dworks.apps.anexplorer.BaseActivity.TAG;

/** Counts a folder's items, and adds up its size when asked to, then shows both in the item's size slot. */
public class FolderSizeAsyncTask extends AsyncTask<Uri, Void, Void> implements ProviderExecutor.Preemptable {
		private final TextView mSizeView;
		private final CancellationSignal mSignal;
		private final String mPath;
		private final int mPosition;
		private final boolean mWithSize;
		private final boolean mWithHidden;
		private int mCount = -1;
		private long mSize = -1;

		public FolderSizeAsyncTask(TextView sizeView, String path, int position, boolean withSize,
								   boolean withHidden) {
			mSizeView = sizeView;
			mSignal = new CancellationSignal();
			mPath = path;
			mPosition = position;
			mWithSize = withSize;
			mWithHidden = withHidden;
		}

		@Override
		public void preempt() {
			cancel(false);
			mSignal.cancel();
		}

		@Override
		protected Void doInBackground(Uri... params) {
			if (isCancelled() || TextUtils.isEmpty(mPath))
				return null;

			final File dir = new File(mPath);
			final String[] names = dir.list();
			if (names != null) {
				int count = 0;
				for (String name : names) {
					if (mWithHidden || !name.startsWith(".")) {
						count++;
					}
				}
				mCount = count;
			}
			if (mWithSize) {
				try {
					mSize = Utils.getDirectorySize(dir, mSignal);
				} catch (Exception e) {
					if (!(e instanceof OperationCanceledException)) {
						Log.w(TAG, "Failed to calculate size for " + mPath + ": " + e);
						CrashReportingManager.logException(e);
					}
				}
			}
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			if (isCancelled() || mSizeView.getTag() != this) {
				return;
			}
			mSizeView.setTag(null);
			if (mCount != -1) {
				DocumentsApplication.getFolderCounts().put(mPosition, mCount);
			}
			if (mSize != -1) {
				DocumentsApplication.getFolderSizes().put(mPosition, mSize);
			}
			mSizeView.setText(format(mSizeView.getContext(), mCount, mSize));
		}

		/** "12 items", "12 items · 3.4 MB" or just the size, whichever parts are known */
		public static String format(Context context, int count, long size) {
			final String items = count == -1 ? null
					: context.getResources().getQuantityString(R.plurals.folder_item_count, count, count);
			final String bytes = size == -1 ? null : Formatter.formatFileSize(context, size);
			if (items != null && bytes != null) {
				return items + " · " + bytes;
			}
			return items != null ? items : bytes;
		}
	}
