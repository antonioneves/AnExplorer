package dev.dworks.apps.anexplorer.adapter;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.cursor.RootCursorWrapper;
import dev.dworks.apps.anexplorer.misc.IconHelper;
import dev.dworks.apps.anexplorer.misc.IconColorUtils;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Document;
import dev.dworks.apps.anexplorer.setting.AppSettings;
import dev.dworks.apps.anexplorer.misc.ThemeColors;

import static dev.dworks.apps.anexplorer.BaseActivity.State.MODE_GRID;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorInt;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorLong;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorString;

public class RecentsAdapter extends CursorRecyclerViewAdapter<RecentsAdapter.ViewHolder> {
    private static final int TYPE_RECENT = 0;
    private static final int TYPE_MORE = 1;

    private final IconHelper mIconHelper;
    private final int mDefaultColor;
    private Context mContext;
    private OnItemClickListener onItemClickListener;

    public RecentsAdapter(Context context, Cursor cursor, IconHelper iconHelper){
        super(context, cursor);
        mContext = context;
        mDefaultColor = ThemeColors.getPrimaryColor(context);
        mIconHelper = iconHelper;
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Cursor cursor) {
        viewHolder.setData(cursor);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, int position) {
        if (getItemViewType(position) == TYPE_RECENT) {
            super.onBindViewHolder(viewHolder, position);
        }
    }

    /** The recent files, then a card that opens all of them */
    @Override
    public int getItemCount() {
        final int count = super.getItemCount();
        return count == 0 ? 0 : count + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == super.getItemCount() ? TYPE_MORE : TYPE_RECENT;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_MORE) {
            View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recent_more, parent, false);
            return new MoreViewHolder(itemView);
        }
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent, parent, false);
        return new ViewHolder(itemView);
    }

    public void setOnItemClickListener(OnItemClickListener listener){
        onItemClickListener = listener;
    }

    public OnItemClickListener getOnItemClickListener(){
        return onItemClickListener;
    }

    public interface OnItemClickListener{
        void onItemClick(ViewHolder item, int position);
        void onMoreClick(View view);
    }

    public class MoreViewHolder extends ViewHolder {
        public MoreViewHolder(View v) {
            super(v);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onItemClickListener.onMoreClick(v);
                }
            });
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView iconMime;
        private final ImageView iconThumb;
        private final View iconMimeBackground;
        // Only the TV card shows the name
        private final TextView title;
        private Cursor mCursor;
        public DocumentInfo mDocumentInfo;

        public ViewHolder(View v) {
            super(v);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onItemClickListener.onItemClick(ViewHolder.this, getLayoutPosition());
                }
            });
            iconMime = (ImageView) v.findViewById(R.id.icon_mime);
            iconThumb = (ImageView) v.findViewById(R.id.icon_thumb);
            iconMimeBackground = v.findViewById(R.id.icon_mime_background);
            title = v.findViewById(android.R.id.title);
        }

        public void setData(Cursor cursor){
            mDocumentInfo = DocumentInfo.fromDirectoryCursor(cursor);
            if (null != title) {
                title.setText(mDocumentInfo.displayName);
            }

            mIconHelper.stopLoading(iconThumb);

            iconMime.animate().cancel();
            iconMime.setAlpha(1f);
            iconThumb.animate().cancel();
            iconThumb.setAlpha(0f);

            final Uri uri = DocumentsContract.buildDocumentUri(mDocumentInfo.authority, mDocumentInfo.documentId);
            mIconHelper.load(uri, mDocumentInfo.path, mDocumentInfo.mimeType,
                    mDocumentInfo.flags, mDocumentInfo.icon, mDocumentInfo.lastModified,
                    iconThumb, iconMime, iconMimeBackground);
        }
    }
}