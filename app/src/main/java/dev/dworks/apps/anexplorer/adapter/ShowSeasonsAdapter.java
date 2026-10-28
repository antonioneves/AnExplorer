package dev.dworks.apps.anexplorer.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import androidx.recyclerview.widget.RecyclerView;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.IconHelper;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;

/**
 * The seasons of a TV show as collapsible groups, each a header over a horizontal row of its
 * episodes, the same look as the recent media on Home. The provider hands the episodes of a season
 * back already ordered by episode number, so they are shown in the order they arrive.
 */
public class ShowSeasonsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnEpisodeClickListener {
        void onEpisodeClick(DocumentInfo episode);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;
    private static final String PREF_SEASON_COLLAPSED = "show_season_collapsed_";

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    /** One season and the episodes found for it so far */
    private static class Season {
        final DocumentInfo doc;
        final List<DocumentInfo> episodes = new ArrayList<>();

        Season(DocumentInfo doc) {
            this.doc = doc;
        }
    }

    private final Context mContext;
    private final IconHelper mIconHelper;
    private final SharedPreferences mPreferences;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final OnEpisodeClickListener mListener;
    private final List<Season> mSeasons = new ArrayList<>();
    private final List<Season> mItems = new ArrayList<>();
    private final List<Integer> mItemTypes = new ArrayList<>();
    private boolean mDestroyed;

    public ShowSeasonsAdapter(Context context, IconHelper iconHelper, List<DocumentInfo> seasons,
                              OnEpisodeClickListener listener) {
        mContext = context;
        mIconHelper = iconHelper;
        mListener = listener;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        setHasStableIds(true);
        for (DocumentInfo doc : seasons) {
            final Season season = new Season(doc);
            mSeasons.add(season);
            load(season);
        }
        rebuildItems();
    }

    /** Stops late episode loads from touching a screen that is gone */
    public void destroy() {
        mDestroyed = true;
    }

    private void load(final Season season) {
        LOADER.execute(new Runnable() {
            @Override
            public void run() {
                final List<DocumentInfo> found = new ArrayList<>();
                final Uri uri = DocumentsContract.buildChildDocumentsUri(season.doc.authority, season.doc.documentId);
                Cursor cursor = null;
                try {
                    cursor = mContext.getContentResolver().query(uri, null, null, null, null);
                    while (cursor != null && cursor.moveToNext()) {
                        found.add(DocumentInfo.fromCursor(cursor, season.doc.authority));
                    }
                } catch (Exception ignored) {
                    // The season just shows no episodes
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mDestroyed) {
                            return;
                        }
                        season.episodes.clear();
                        season.episodes.addAll(found);
                        final int index = mItems.indexOf(season);
                        // The row follows its header, and is absent while collapsed
                        if (index >= 0 && mItemTypes.get(index) == TYPE_HEADER && !isCollapsed(season)) {
                            notifyItemChanged(index + 1);
                        }
                    }
                });
            }
        });
    }

    private void rebuildItems() {
        mItems.clear();
        mItemTypes.clear();
        for (Season season : mSeasons) {
            mItems.add(season);
            mItemTypes.add(TYPE_HEADER);
            if (!isCollapsed(season)) {
                mItems.add(season);
                mItemTypes.add(TYPE_ROW);
            }
        }
    }

    private boolean isCollapsed(Season season) {
        return mPreferences.getBoolean(PREF_SEASON_COLLAPSED + season.doc.documentId, false);
    }

    private void toggle(int headerPosition) {
        final Season season = mItems.get(headerPosition);
        final boolean collapse = !isCollapsed(season);
        mPreferences.edit().putBoolean(PREF_SEASON_COLLAPSED + season.doc.documentId, collapse).apply();
        if (collapse) {
            mItems.remove(headerPosition + 1);
            mItemTypes.remove(headerPosition + 1);
            notifyItemRemoved(headerPosition + 1);
        } else {
            mItems.add(headerPosition + 1, season);
            mItemTypes.add(headerPosition + 1, TYPE_ROW);
            notifyItemInserted(headerPosition + 1);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return mItemTypes.get(position);
    }

    @Override
    public long getItemId(int position) {
        return mItems.get(position).doc.documentId.hashCode() * 2L + mItemTypes.get(position);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_home_header, parent, false));
        }
        return new RowViewHolder(inflater.inflate(R.layout.item_gallery, parent, false));
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        final Season season = mItems.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(season);
        } else {
            ((RowViewHolder) holder).bind(season);
        }
    }

    private class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final ImageView expander;
        private final View toggle;
        private Season season;

        HeaderViewHolder(View v) {
            super(v);
            // Only the title part is selectable
            v.setOnClickListener(null);
            v.setOnLongClickListener(null);
            v.setClickable(false);
            v.setLongClickable(false);
            title = v.findViewById(android.R.id.title);
            expander = v.findViewById(R.id.expander);
            toggle = v.findViewById(R.id.toggle);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    final int position = getAdapterPosition();
                    if (position == RecyclerView.NO_POSITION) {
                        return;
                    }
                    toggle(position);
                    updateExpander();
                }
            });
        }

        void bind(Season season) {
            this.season = season;
            title.setText(season.doc.displayName);
            toggle.setContentDescription(season.doc.displayName);
            updateExpander();
        }

        private void updateExpander() {
            expander.setImageResource(isCollapsed(season) ? R.drawable.expander_open : R.drawable.expander_close);
        }
    }

    private class RowViewHolder extends RecyclerView.ViewHolder {
        private final RecyclerView recyclerView;

        RowViewHolder(View v) {
            super(v);
            // The row itself must not take focus ahead of its episodes
            v.setOnClickListener(null);
            v.setOnLongClickListener(null);
            v.setClickable(false);
            v.setLongClickable(false);
            v.setFocusable(false);
            recyclerView = v.findViewById(R.id.recyclerview);
        }

        void bind(Season season) {
            recyclerView.setAdapter(new EpisodeAdapter(season.episodes));
        }
    }

    private class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder> {
        private final List<DocumentInfo> mEpisodes;

        EpisodeAdapter(List<DocumentInfo> episodes) {
            mEpisodes = episodes;
        }

        @Override
        public int getItemCount() {
            return mEpisodes.size();
        }

        @Override
        public EpisodeViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new EpisodeViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recent, parent, false));
        }

        @Override
        public void onBindViewHolder(EpisodeViewHolder holder, int position) {
            holder.bind(mEpisodes.get(position));
        }

        class EpisodeViewHolder extends RecyclerView.ViewHolder {
            private final ImageView iconMime;
            private final ImageView iconThumb;
            private final View iconMimeBackground;
            private final TextView title;
            private DocumentInfo episode;

            EpisodeViewHolder(View v) {
                super(v);
                iconMime = v.findViewById(R.id.icon_mime);
                iconThumb = v.findViewById(R.id.icon_thumb);
                iconMimeBackground = v.findViewById(R.id.icon_mime_background);
                title = v.findViewById(android.R.id.title);
                v.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (episode != null) {
                            mListener.onEpisodeClick(episode);
                        }
                    }
                });
            }

            void bind(DocumentInfo episode) {
                this.episode = episode;
                if (title != null) {
                    title.setText(episode.displayName);
                }
                mIconHelper.stopLoading(iconThumb);
                iconMime.animate().cancel();
                iconMime.setAlpha(1f);
                iconThumb.animate().cancel();
                iconThumb.setAlpha(0f);
                final Uri uri = DocumentsContract.buildDocumentUri(episode.authority, episode.documentId);
                mIconHelper.load(uri, episode.path, episode.mimeType, episode.flags, episode.icon,
                        episode.lastModified, iconThumb, iconMime, iconMimeBackground);
            }
        }
    }
}
