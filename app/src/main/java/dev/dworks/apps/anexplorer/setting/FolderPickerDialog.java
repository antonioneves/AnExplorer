package dev.dworks.apps.anexplorer.setting;

import android.content.Context;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Arrays;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;

import static dev.dworks.apps.anexplorer.common.DialogFragment.tintButtons;

/**
 * Picks a folder of internal storage with the remote: OK opens a folder (or goes up), and
 * the dialog's buttons choose the folder it is in, or go back to the default one.
 */
public class FolderPickerDialog {

    public interface Callback {
        /** Null when the default folder was asked for */
        void onFolderPicked(File folder);
    }

    private final Context mContext;
    private final Callback mCallback;
    private final File mRoot = Environment.getExternalStorageDirectory();
    private LayoutInflater mInflater;
    private TextView mPath;
    private ScrollView mScroll;
    private ViewGroup mFolders;
    private View mEmpty;
    private AlertDialog mDialog;
    private File mCurrent;

    public static void show(Context context, File start, Callback callback) {
        new FolderPickerDialog(context, callback).show(start);
    }

    private FolderPickerDialog(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;
    }

    private void show(File start) {
        // The folder may not exist yet (the default one before anything was received)
        File folder = start;
        while (folder != null && !folder.isDirectory()) {
            folder = folder.getParentFile();
        }
        if (folder == null || !isInRoot(folder)) {
            folder = mRoot;
        }

        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mContext);
        mInflater = LayoutInflater.from(builder.getContext());
        final View view = mInflater.inflate(R.layout.dialog_folder_picker, null, false);
        mPath = (TextView) view.findViewById(R.id.path);
        mScroll = (ScrollView) view.findViewById(R.id.scroll);
        mFolders = (ViewGroup) view.findViewById(R.id.folders);
        mEmpty = view.findViewById(android.R.id.empty);

        mDialog = builder
                .setTitle(R.string.folder_picker_title)
                .setView(view)
                .setPositiveButton(R.string.folder_picker_use, (dialog, which) ->
                        mCallback.onFolderPicked(mCurrent))
                .setNeutralButton(R.string.folder_picker_default, (dialog, which) ->
                        mCallback.onFolderPicked(null))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        final File first = folder;
        mDialog.setOnShowListener(dialog -> {
            try {
                tintButtons(mDialog);
            } catch (Exception e) {
                CrashReportingManager.logException(e);
            }
            open(first);
        });
        mDialog.show();
    }

    private boolean isInRoot(File folder) {
        final String root = mRoot.getAbsolutePath();
        final String path = folder.getAbsolutePath();
        return path.equals(root) || path.startsWith(root + "/");
    }

    private void open(File folder) {
        mCurrent = folder;
        mPath.setText(AppSettings.getFolderLabel(mContext, folder));
        mFolders.removeAllViews();
        if (!folder.equals(mRoot)) {
            final File parent = folder.getParentFile();
            addRow(parent, R.drawable.ic_folder_up, mContext.getString(R.string.folder_picker_up),
                    AppSettings.getFolderLabel(mContext, parent));
        }
        final File[] children = folder.listFiles(child -> child.isDirectory() && !child.isHidden());
        if (children != null) {
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                addRow(child, R.drawable.ic_root_folder, child.getName(), null);
            }
        }
        mEmpty.setVisibility(children == null || children.length == 0 ? View.VISIBLE : View.GONE);
        mScroll.scrollTo(0, 0);

        // The D-pad stops at the top row instead of leaving the list
        final View top = mFolders.getChildAt(0);
        if (top != null) {
            top.setId(View.generateViewId());
            top.setNextFocusUpId(top.getId());
            top.requestFocus();
        } else {
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        }
    }

    private void addRow(File folder, int iconId, String title, String summary) {
        final View row = mInflater.inflate(R.layout.item_folder_picker, mFolders, false);
        ((ImageView) row.findViewById(android.R.id.icon)).setImageResource(iconId);
        ((TextView) row.findViewById(android.R.id.title)).setText(title);
        if (summary != null) {
            final TextView summaryView = (TextView) row.findViewById(android.R.id.summary);
            summaryView.setText(summary);
            summaryView.setVisibility(View.VISIBLE);
        }
        row.setOnClickListener(v -> open(folder));
        mFolders.addView(row);
    }
}
