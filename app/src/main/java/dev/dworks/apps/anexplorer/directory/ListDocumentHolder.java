package dev.dworks.apps.anexplorer.directory;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.collection.ArrayMap;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import dev.dworks.apps.anexplorer.BaseActivity;
import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.common.RecyclerFragment.RecyclerItemClickListener.OnItemClickListener;
import dev.dworks.apps.anexplorer.cursor.RootCursorWrapper;
import dev.dworks.apps.anexplorer.misc.IconColorUtils;
import dev.dworks.apps.anexplorer.misc.IconHelper;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.misc.MimePredicate;
import dev.dworks.apps.anexplorer.misc.ProviderExecutor;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.ThumbnailCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.provider.ExternalStorageProvider;
import dev.dworks.apps.anexplorer.directory.DocumentsAdapter.Environment;

import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_BROWSE;
import static dev.dworks.apps.anexplorer.BaseActivity.State.MODE_GRID;
import static dev.dworks.apps.anexplorer.BaseActivity.State.MODE_LIST;
import static dev.dworks.apps.anexplorer.fragment.DirectoryFragment.TYPE_RECENT_OPEN;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorInt;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorLong;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorString;

public class ListDocumentHolder extends DocumentHolder {

    public ListDocumentHolder(Context context, ViewGroup parent, int layout, OnItemClickListener onItemClickListener,
                              Environment environment) {
        super(context, parent, layout, onItemClickListener, environment);
    }

    public ListDocumentHolder(Context context, ViewGroup parent, OnItemClickListener onItemClickListener,
                              Environment environment) {
        this(context, parent,
                R.layout.item_doc_list,
                onItemClickListener, environment);
    }

    @Override
    public void setData(Cursor cursor, int position) {
        super.setData(cursor, position);
        final Context context = mContext;
        final BaseActivity.State state = mEnv.getDisplayState();
        final RootsCache roots = DocumentsApplication.getRootsCache(context);

        mDoc.updateFromCursor(cursor, getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY));

        if (state.action == ACTION_BROWSE) {
            if (null != iconView) {
                iconView.setOnClickListener(this);
            }
        }

        final boolean enabled = mEnv.isDocumentEnabled(mDoc.mimeType, mDoc.flags);
        setEnabled(enabled);

        mIconHelper.stopLoading(iconThumb);

        iconMime.animate().cancel();
        iconMime.setAlpha(1f);
        iconThumb.animate().cancel();
        iconThumb.setAlpha(0f);

        mIconHelper.load(mDoc, iconThumb, iconMime, iconMimeBackground);

        boolean hasLine1 = false;
        boolean hasLine2 = false;

        final boolean hideTitle = (state.derivedMode == MODE_GRID) && mEnv.hideGridTiles();
        if (!hideTitle) {
            title.setText(mDoc.displayName);
            hasLine1 = true;
        }

        Drawable iconDrawable = null;
        if (mEnv.getType() == TYPE_RECENT_OPEN) {
            final String docRootId = getCursorString(cursor, RootCursorWrapper.COLUMN_ROOT_ID);
            // We've already had to enumerate roots before any results can
            // be shown, so this will never block.
            final RootInfo root = roots.getRootBlocking(mDoc.authority, docRootId);
            if (state.derivedMode == MODE_GRID) {
                iconDrawable = root.loadGridIcon(context);
            } else {
                iconDrawable = root.loadIcon(context);
            }

            if (summary != null) {
                final boolean alwaysShowSummary = mEnv.getContext().getResources().getBoolean(R.bool.always_show_summary);
                if (alwaysShowSummary) {
                    summary.setText(root.getDirectoryString());
                    summary.setVisibility(View.VISIBLE);
                    hasLine2 = true;
                } else {
                    if (iconDrawable != null && roots.isIconUniqueBlocking(root)) {
                        // No summary needed if icon speaks for itself
                        summary.setVisibility(View.INVISIBLE);
                    } else {
                        summary.setText(root.getDirectoryString());
                        summary.setVisibility(View.VISIBLE);
                        // summary.setTextAlignment(TextView.TEXT_ALIGNMENT_TEXT_END);
                        hasLine2 = true;
                    }
                }
            }
        } else {
            // Directories showing thumbnails in grid mode get a little icon
            // hint to remind user they're a directory.
            // Only drawn when there is no title line (see below)
            if (!hasLine1 && Utils.isDir(mDoc.mimeType) && state.derivedMode == MODE_GRID) {
                iconDrawable = IconUtils.applyTintAttr(context, R.drawable.ic_root_folder,
                        android.R.attr.textColorPrimaryInverse);
            }

            if (summary != null) {
                if (mDoc.summary != null) {
                    summary.setText(mDoc.summary);
                    summary.setVisibility(View.VISIBLE);
                    hasLine2 = true;
                } else {
                    summary.setVisibility(View.GONE);
                }
            }
        }

        final boolean local = !TextUtils.isEmpty(mDoc.path)
                && ExternalStorageProvider.AUTHORITY.equals(mDoc.authority);
        if (badgeStarred != null) {
            badgeStarred.setVisibility(local && mEnv.isStarred(mDoc.path) ? View.VISIBLE : View.GONE);
        }
        if (badgeBookmarked != null) {
            badgeBookmarked.setVisibility(local && Utils.isDir(mDoc.mimeType)
                    && mEnv.isBookmarked(mDoc.path) ? View.VISIBLE : View.GONE);
        }

        if (icon1 != null)
            icon1.setVisibility(View.GONE);
        if (icon2 != null)
            icon2.setVisibility(View.GONE);

        if (iconDrawable != null) {
            if (hasLine1) {
                icon1.setVisibility(View.GONE);
                //icon1.setImageDrawable(iconDrawable);
            } else {
                icon2.setVisibility(View.VISIBLE);
                icon2.setImageDrawable(iconDrawable);
            }
        }

        // The list keeps its details in a column on the right; empty ones collapse there, while
        // grid cards keep their space so every card in a row is the same height
        final View details = itemView.findViewById(R.id.details);
        final int hidden = details != null ? View.GONE : View.INVISIBLE;

        if (mDoc.lastModified == -1) {
            date.setText(null);
            date.setVisibility(hidden);
        } else {
            date.setText(Utils.formatTime(context, mDoc.lastModified));
            date.setVisibility(View.VISIBLE);
            hasLine2 = true;
        }

        final FolderSizeAsyncTask oldSizeTask = (FolderSizeAsyncTask) size.getTag();
        if (oldSizeTask != null) {
            oldSizeTask.preempt();
            size.setTag(null);
        }
        if (Utils.isDir(mDoc.mimeType)) {
            // Folders show how many items they hold (and their size when that is turned on)
            size.setText(null);
            size.setVisibility(hidden);
            // Counting means reading the folder, which only local storage can do quickly
            if (!TextUtils.isEmpty(mDoc.path)
                    && ExternalStorageProvider.AUTHORITY.equals(mDoc.authority)) {
                size.setVisibility(View.VISIBLE);
                final boolean withSize = state.showSize && state.showFolderSize;
                final ArrayMap<String, Integer> counts = DocumentsApplication.getFolderCounts();
                final ArrayMap<String, Long> sizes = DocumentsApplication.getFolderSizes();
                final int count = counts.containsKey(mDoc.path) ? counts.get(mDoc.path) : -1;
                final long sizeInBytes = sizes.containsKey(mDoc.path) ? sizes.get(mDoc.path) : -1;
                if (count != -1 && (!withSize || sizeInBytes != -1)) {
                    size.setText(FolderSizeAsyncTask.format(context, count, withSize ? sizeInBytes : -1));
                } else {
                    final FolderSizeAsyncTask task = new FolderSizeAsyncTask(size, mDoc.path,
                            withSize, state.showHiddenFiles);
                    size.setTag(task);
                    ProviderExecutor.forAuthority(mDoc.authority).execute(task);
                }
                hasLine2 = true;
            }
        } else if (state.showSize) {
            if (mDoc.size == -1) {
                size.setText(null);
                size.setVisibility(hidden);
            } else {
                size.setText(Formatter.formatFileSize(context, mDoc.size));
                size.setVisibility(View.VISIBLE);
                hasLine2 = true;
            }
        } else {
            size.setText(null);
            size.setVisibility(hidden);
        }

        if (line1 != null) {
            line1.setVisibility(hasLine1 ? View.VISIBLE : View.GONE);
        }
        if (details != null) {
            // In the list the second line only holds the summary
            hasLine2 = summary != null && summary.getVisibility() == View.VISIBLE;
        }
        if (line2 != null) {
            line2.setVisibility(hasLine2 ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        //final float iconAlpha = (state.derivedMode == MODE_LIST && !enabled) ? 0.5f : 1f;
        // Text colors enabled/disabled is handle via a color set.
        final float imgAlpha = enabled ? 1f : DISABLED_ALPHA;
        iconMime.setAlpha(imgAlpha);
        iconThumb.setAlpha(imgAlpha);
    }
}
