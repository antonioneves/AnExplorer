package dev.dworks.apps.anexplorer.setting;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.Utils;

/**
 * Picks a folder of internal storage with the remote: OK opens a folder (or goes up), and
 * the dialog's buttons choose the folder shown in the title, or go back to the default one.
 */
public class FolderPickerDialog {

    public interface Callback {
        /** Null when the default folder was asked for */
        void onFolderPicked(File folder);
    }

    private final Context mContext;
    private final Callback mCallback;
    private final File mRoot = Environment.getExternalStorageDirectory();
    private final List<File> mEntries = new ArrayList<>();
    private ArrayAdapter<File> mAdapter;
    private ListView mList;
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

        mAdapter = new ArrayAdapter<File>(mContext,
                com.google.android.material.R.layout.mtrl_alert_select_dialog_item, android.R.id.text1, mEntries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final TextView view = (TextView) super.getView(position, convertView, parent);
                final File entry = getItem(position);
                final boolean up = entry.equals(mCurrent.getParentFile());
                view.setText(up ? mContext.getString(R.string.folder_picker_up) : entry.getName());
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        up ? R.drawable.ic_folder_up : R.drawable.ic_root_folder, 0, 0, 0);
                view.setCompoundDrawablePadding(Utils.dpToPx(20));
                view.setCompoundDrawableTintList(ColorStateList.valueOf(view.getCurrentTextColor()));
                return view;
            }
        };
        mList = new ListView(mContext);
        mList.setAdapter(mAdapter);
        mList.setDivider(null);
        mList.setOnItemClickListener((parent, view, position, id) -> open(mEntries.get(position)));

        mDialog = new MaterialAlertDialogBuilder(mContext)
                .setTitle(R.string.folder_picker_title)
                .setView(mList)
                .setPositiveButton(R.string.folder_picker_use, (dialog, which) ->
                        mCallback.onFolderPicked(mCurrent))
                .setNeutralButton(R.string.folder_picker_default, (dialog, which) ->
                        mCallback.onFolderPicked(null))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        mDialog.show();
        open(folder);
    }

    private boolean isInRoot(File folder) {
        final String root = mRoot.getAbsolutePath();
        final String path = folder.getAbsolutePath();
        return path.equals(root) || path.startsWith(root + "/");
    }

    private void open(File folder) {
        mCurrent = folder;
        mDialog.setTitle(AppSettings.getFolderLabel(mContext, folder));
        mEntries.clear();
        if (!folder.equals(mRoot)) {
            mEntries.add(folder.getParentFile());
        }
        final File[] children = folder.listFiles(child -> child.isDirectory() && !child.isHidden());
        if (children != null) {
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            mEntries.addAll(Arrays.asList(children));
        }
        mAdapter.notifyDataSetChanged();
        mList.setSelection(0);
        // An empty folder leaves nothing to open, so the remote goes to the buttons
        if (mEntries.isEmpty()) {
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        } else {
            mList.requestFocus();
        }
    }
}
