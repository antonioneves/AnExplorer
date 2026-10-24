package dev.dworks.apps.anexplorer.adapter;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import androidx.core.content.ContextCompat;
import android.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.IconHelper;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.setting.AppSettings;
import dev.dworks.apps.anexplorer.ui.CircleImage;
import dev.dworks.apps.anexplorer.ui.NumberProgressBar;
import dev.dworks.apps.anexplorer.misc.ThemeColors;

public class HomeAdapter extends RecyclerView.Adapter<HomeAdapter.ViewHolder> {
    public static final int TYPE_MAIN = 1;
    public static final int TYPE_SHORTCUT = 2;
    public static final int TYPE_RECENT = 3;
    public static final int TYPE_HEADER = 4;

    public static final int SECTION_STORAGE = 0;
    public static final int SECTION_LIBRARY = 1;
    public static final int SECTION_RECENT = 2;
    /** Sections from this one on get a header that collapses them */
    private static final int FIRST_COLLAPSIBLE_SECTION = SECTION_RECENT;
    private static final int[] SECTION_TITLES = {0, 0, R.string.home_section_recent};
    private static final String PREF_SECTION_COLLAPSED = "home_section_collapsed_";

    private final int mDefaultColor;
    private Activity mContext;
    private OnItemClickListener onItemClickListener;
    private List<CommonInfo> mStorage = new ArrayList<>();
    private List<CommonInfo> mLibrary = new ArrayList<>();
    /** What is shown: each non-empty section's items, under a header if it is collapsible */
    private final ArrayList<CommonInfo> mItems = new ArrayList<>();
    private Cursor recentCursor;
    private final IconHelper mIconHelper;
    private final SharedPreferences mPreferences;

    public HomeAdapter(Activity context, IconHelper iconHelper){
        mContext = context;
        mDefaultColor = ThemeColors.getPrimaryColor(context);
        mIconHelper = iconHelper;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public void setData(List<CommonInfo> storage, List<CommonInfo> library) {
        mStorage = storage;
        mLibrary = library;
        rebuildItems();
    }

    public void setRecentData(Cursor cursor) {
        recentCursor = cursor;
        rebuildItems();
    }

    private void rebuildItems() {
        mItems.clear();
        for (int section = SECTION_STORAGE; section <= SECTION_RECENT; section++) {
            final List<CommonInfo> items = getSectionItems(section);
            if (items.isEmpty()) {
                continue;
            }
            if (section >= FIRST_COLLAPSIBLE_SECTION) {
                mItems.add(CommonInfo.header(section));
            }
            if (!isCollapsed(section)) {
                mItems.addAll(items);
            }
        }
        notifyDataSetChanged();
    }

    private List<CommonInfo> getSectionItems(int section) {
        switch (section) {
            case SECTION_STORAGE:
                return mStorage;
            case SECTION_LIBRARY:
                return mLibrary;
            default:
                if (recentCursor == null || recentCursor.getCount() == 0) {
                    return Collections.emptyList();
                }
                final CommonInfo recent = new CommonInfo();
                recent.type = TYPE_RECENT;
                return Collections.singletonList(recent);
        }
    }

    private boolean isCollapsed(int section) {
        return section >= FIRST_COLLAPSIBLE_SECTION && mPreferences.getBoolean(PREF_SECTION_COLLAPSED + section, false);
    }

    /** Hides or shows the items under the header at this position, remembering the choice. */
    private void toggleSection(int position) {
        final int section = mItems.get(position).section;
        final boolean collapse = !isCollapsed(section);
        mPreferences.edit().putBoolean(PREF_SECTION_COLLAPSED + section, collapse).apply();
        final List<CommonInfo> items = getSectionItems(section);
        if (collapse) {
            mItems.subList(position + 1, position + 1 + items.size()).clear();
            notifyItemRangeRemoved(position + 1, items.size());
        } else {
            mItems.addAll(position + 1, items);
            notifyItemRangeInserted(position + 1, items.size());
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        holder.setData(position);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).type;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        switch (viewType) {
            case TYPE_MAIN: {
                View itemView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_home, parent, false);
                return new MainViewHolder(itemView);
            }
            case TYPE_SHORTCUT: {
                View itemView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_shortcuts, parent, false);
                return new ShortcutViewHolder(itemView);
            }
            case TYPE_RECENT: {
                View itemView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_gallery, parent, false);
                return new GalleryViewHolder(itemView);
            }
            case TYPE_HEADER: {
                View itemView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_home_header, parent, false);
                return new HeaderViewHolder(itemView);
            }
        }
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_shortcuts, parent, false);
        return new ShortcutViewHolder(itemView);
    }

    public void setOnItemClickListener(OnItemClickListener listener){
        onItemClickListener = listener;
    }

    public OnItemClickListener getOnItemClickListener(){
        return onItemClickListener;
    }

    public interface OnItemClickListener{
        void onItemClick(ViewHolder item, View view, int position);
        void onItemLongClick(ViewHolder item, View view, int position);
        void onItemViewClick(ViewHolder item, View view, int position);
    }

    public abstract class ViewHolder extends RecyclerView.ViewHolder {
        protected final CircleImage iconBackground;
        protected final ImageView icon;
        protected final TextView title;
        protected TextView summary;
        protected NumberProgressBar progress;
        protected ImageButton action;
        protected View action_layout;
        protected View card_view;
        protected final ImageView iconMime;
        protected final ImageView iconThumb;
        protected final View iconMimeBackground;
        public CommonInfo commonInfo;

        public ViewHolder(View v) {
            super(v);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(null != onItemClickListener) {
                        onItemClickListener.onItemClick(ViewHolder.this, v, getLayoutPosition());
                    }
                }
            });
            v.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if(null != onItemClickListener) {
                        onItemClickListener.onItemLongClick(ViewHolder.this, v, getLayoutPosition());
                    }
                    return false;
                }
            });
            icon = (ImageView) v.findViewById(android.R.id.icon);
            iconBackground = (CircleImage) v.findViewById(R.id.icon_background);
            title = (TextView) v.findViewById(android.R.id.title);

            card_view = v.findViewById(R.id.card_view);
            summary = (TextView) v.findViewById(android.R.id.summary);
            progress = (NumberProgressBar) v.findViewById(android.R.id.progress);
            action_layout = v.findViewById(R.id.action_layout);
            action = (ImageButton) v.findViewById(R.id.action);

            iconMime = (ImageView) v.findViewById(R.id.icon_mime);
            iconThumb = (ImageView) v.findViewById(R.id.icon_thumb);
            iconMimeBackground = v.findViewById(R.id.icon_mime_background);
        }

        public abstract void setData(int position);
    }

    public class MainViewHolder extends ViewHolder {
        private final int accentColor;
        private final int color;
        private int mPosition;

        public MainViewHolder(View v) {
            super(v);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(null != onItemClickListener) {
                        onItemClickListener.onItemViewClick(MainViewHolder.this, action, getLayoutPosition());
                    }
                }
            });
            accentColor = ThemeColors.getAccentColor(mContext);
            color = ThemeColors.getOnSurfacePrimaryColor(mContext);
        }

        @Override
        public void setData(int position){
            mPosition = position;
            commonInfo = getItem(position);
            icon.setImageDrawable(commonInfo.rootInfo.loadDrawerIcon(mContext));
            title.setText(commonInfo.rootInfo.title);
            int drawableId = -1;
            if (commonInfo.rootInfo.isStorage() && !commonInfo.rootInfo.isSecondaryStorage()) {
                drawableId = R.drawable.ic_analyze;
            }
            if(drawableId != -1) {
                action.setImageDrawable(IconUtils.applyTint(mContext, drawableId, accentColor));
                action_layout.setVisibility(View.VISIBLE);
            } else {
                action.setImageDrawable(null);
                action_layout.setVisibility(View.GONE);
            }
            // Show available space if no summary
            String summaryText = commonInfo.rootInfo.summary;
            if (TextUtils.isEmpty(summaryText) && commonInfo.rootInfo.availableBytes >= 0) {
                summaryText = mContext.getString(R.string.root_available_bytes,
                        Formatter.formatFileSize(mContext, commonInfo.rootInfo.availableBytes));
                try {
                    Long current = 100 * commonInfo.rootInfo.availableBytes / commonInfo.rootInfo.totalBytes ;
                    progress.setVisibility(View.VISIBLE);
                    progress.setMax(100);
                    progress.setProgress(100 - current.intValue());
                    progress.setColor(color);
                    animateProgress(progress, commonInfo.rootInfo);
                }
                catch (Exception e){
                    progress.setVisibility(View.GONE);
                }
            }
            else{
                progress.setVisibility(View.GONE);
            }

            summary.setText(summaryText);
            summary.setVisibility(TextUtils.isEmpty(summaryText) ? View.GONE : View.VISIBLE);
        }
    }

    public class HeaderViewHolder extends ViewHolder {
        private final ImageView expander;

        public HeaderViewHolder(View v) {
            super(v);
            // Only the title part is selectable
            v.setOnClickListener(null);
            v.setOnLongClickListener(null);
            v.setClickable(false);
            v.setLongClickable(false);
            final View toggle = v.findViewById(R.id.toggle);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int position = getAdapterPosition();
                    if (position == RecyclerView.NO_POSITION) {
                        return;
                    }
                    toggleSection(position);
                    updateExpander();
                }
            });
            expander = v.findViewById(R.id.expander);
        }

        @Override
        public void setData(int position){
            commonInfo = getItem(position);
            title.setText(SECTION_TITLES[commonInfo.section]);
            updateExpander();
        }

        private void updateExpander() {
            final boolean collapsed = isCollapsed(commonInfo.section);
            expander.setImageResource(collapsed ? R.drawable.expander_open : R.drawable.expander_close);
            itemView.findViewById(R.id.toggle).setContentDescription(mContext.getString(SECTION_TITLES[commonInfo.section]));
        }
    }

    public class ShortcutViewHolder extends ViewHolder {
        private int mPosition;

        public ShortcutViewHolder(View v) {
            super(v);
        }

        @Override
        public void setData(int position){
            mPosition = position;
            commonInfo = getItem(position);
            if(null == commonInfo){
                return;
            }
            iconBackground.setColor(ContextCompat.getColor(mContext, commonInfo.rootInfo.derivedColor));
            icon.setImageDrawable(commonInfo.rootInfo.loadShortcutIcon(mContext));
            title.setText(commonInfo.rootInfo.title);
        }
    }

    public class GalleryViewHolder extends ViewHolder {
        private int mPosition;
        private final RecyclerView recyclerview;
        private RecentsAdapter adapter;

        public GalleryViewHolder(View v) {
            super(v);

            // The row itself does nothing; left clickable it takes the remote's focus ahead of
            // the media items inside it
            v.setOnClickListener(null);
            v.setOnLongClickListener(null);
            v.setClickable(false);
            v.setLongClickable(false);
            v.setFocusable(false);
            recyclerview = (RecyclerView) v.findViewById(R.id.recyclerview);
        }

        @Override
        public void setData(int position){
            mPosition = position;
            commonInfo = HomeAdapter.this.getItem(position);
            adapter = new RecentsAdapter(mContext, recentCursor, mIconHelper);
            adapter.setOnItemClickListener(new RecentsAdapter.OnItemClickListener() {
                @Override
                public void onItemClick(RecentsAdapter.ViewHolder item, int position) {
                    if(null != onItemClickListener) {
                        onItemClickListener.onItemClick(GalleryViewHolder.this, recyclerview, position);
                    }
                }

                @Override
                public void onMoreClick(View view) {
                    if(null != onItemClickListener) {
                        onItemClickListener.onItemViewClick(GalleryViewHolder.this, view, getLayoutPosition());
                    }
                }
            });
            recyclerview.setAdapter(adapter);
        }

        public DocumentInfo getItem(int position){
           return DocumentInfo.fromDirectoryCursor(adapter.getItem(position));
        }
    }

    public CommonInfo getItem(int position){
        return mItems.get(position);
    }

    private void animateProgress(final NumberProgressBar item, RootInfo root){
        try {
            final double percent = (((root.totalBytes - root.availableBytes) / (double) root.totalBytes) * 100);
            final Timer timer = new Timer();
            item.setProgress(0);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if(Utils.isActivityAlive(mContext)){
                        mContext.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (item.getProgress() >= (int) percent) {
                                    timer.cancel();
                                } else {
                                    item.setProgress(item.getProgress() + 1);
                                }
                            }
                        });
                    }
                }
            }, 50, 20);
        }
        catch (Exception e){
            item.setVisibility(View.GONE);
            CrashReportingManager.logException(e);
        }
    }
}