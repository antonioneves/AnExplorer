package dev.dworks.apps.anexplorer.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import androidx.recyclerview.widget.RecyclerView;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.EpisodeName;
import dev.dworks.apps.anexplorer.misc.WatchHistory;
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

        void onEpisodeLongClick(DocumentInfo episode, View view);

        void onEpisodeSelected(DocumentInfo episode, boolean selected);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;
    private static final String PREF_SEASON_COLLAPSED = "show_season_collapsed_";

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    /** One season and the episodes found for it so far */
    private static class Season {
        final DocumentInfo doc;
        final List<DocumentInfo> episodes = new ArrayList<>();
        RecyclerView.Adapter<?> adapter;

        Season(DocumentInfo doc) {
            this.doc = doc;
        }
    }

    private final Context mContext;
    private final IconHelper mIconHelper;
    private final SharedPreferences mPreferences;
    private final WatchHistory mHistory;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final OnEpisodeClickListener mListener;
    private final Set<String> mStarredPaths;
    private final Set<String> mSelectedIds = new HashSet<>();
    private final List<Season> mSeasons = new ArrayList<>();
    private final List<Season> mItems = new ArrayList<>();
    private final List<Integer> mItemTypes = new ArrayList<>();
    private boolean mSelecting;
    private boolean mDestroyed;

    public ShowSeasonsAdapter(Context context, IconHelper iconHelper, List<DocumentInfo> seasons,
                              Set<String> starredPaths, OnEpisodeClickListener listener) {
        mContext = context;
        mIconHelper = iconHelper;
        mStarredPaths = starredPaths;
        mListener = listener;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        mHistory = new WatchHistory(context);
        setHasStableIds(true);
        for (DocumentInfo doc : seasons) {
            final Season season = new Season(doc);
            // One adapter per season, so checking an episode keeps the row where it was
            season.adapter = new EpisodeAdapter(season.episodes);
            mSeasons.add(season);
            load(season);
        }
        rebuildItems();
    }

    /** Starts or ends selection, marking the episodes in the given list as checked */
    public void setSelection(boolean selecting, Collection<DocumentInfo> selected) {
        mSelecting = selecting;
        mSelectedIds.clear();
        for (DocumentInfo episode : selected) {
            mSelectedIds.add(episode.documentId);
        }
        notifyEpisodesChanged();
    }

    private void setEpisodeSelected(DocumentInfo episode, boolean selected) {
        if (selected) {
            mSelectedIds.add(episode.documentId);
        } else {
            mSelectedIds.remove(episode.documentId);
        }
        // Only the toggled card needs to redraw, so the row keeps its focus and scroll
        for (Season season : mSeasons) {
            final int index = season.episodes.indexOf(episode);
            if (index >= 0 && season.adapter instanceof EpisodeAdapter) {
                ((EpisodeAdapter) season.adapter).notifyItemChanged(index);
                break;
            }
        }
        mListener.onEpisodeSelected(episode, selected);
    }

    /** Redraws the episode cards, for a selection, a star or a watch mark that changed */
    public void notifyEpisodesChanged() {
        for (Season season : mSeasons) {
            if (season.adapter != null) {
                season.adapter.notifyDataSetChanged();
            }
        }
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
                        if (season.adapter != null) {
                            season.adapter.notifyDataSetChanged();
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
            recyclerView.setAdapter(season.adapter);
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
            private final ImageView watchBadge;
            private final ImageView starBadge;
            private final ImageView checkBadge;
            private DocumentInfo episode;

            EpisodeViewHolder(View v) {
                super(v);
                iconMime = v.findViewById(R.id.icon_mime);
                iconThumb = v.findViewById(R.id.icon_thumb);
                iconMimeBackground = v.findViewById(R.id.icon_mime_background);
                title = v.findViewById(android.R.id.title);
                if (title != null) {
                    // Label on top, so it doesn't sit over the title printed on posters
                    title.setBackgroundResource(R.drawable.recent_title_scrim_top);
                    ((FrameLayout.LayoutParams) title.getLayoutParams()).gravity = Gravity.TOP;
                    title.setPadding(title.getPaddingLeft(), title.getPaddingBottom(),
                            title.getPaddingRight(), title.getPaddingTop());
                }
                final int size = v.getResources().getDimensionPixelSize(R.dimen.episode_badge_size);
                final int margin = size / 4;
                // Bottom corner, opposite the label on top
                watchBadge = new ImageView(v.getContext());
                final FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(size, size,
                        Gravity.BOTTOM | Gravity.END);
                badgeParams.setMargins(margin, margin, margin, margin);
                watchBadge.setLayoutParams(badgeParams);
                watchBadge.setBackgroundResource(R.drawable.watch_badge_scrim);
                watchBadge.setPadding(margin / 2, margin / 2, margin / 2, margin / 2);
                watchBadge.setVisibility(View.GONE);
                ((FrameLayout) iconThumb.getParent()).addView(watchBadge);

                // A starred episode keeps a star in the bottom corner, like the movie posters
                starBadge = new ImageView(v.getContext());
                final FrameLayout.LayoutParams starParams = new FrameLayout.LayoutParams(size, size,
                        Gravity.BOTTOM | Gravity.START);
                starParams.setMargins(margin, margin, margin, margin);
                starBadge.setLayoutParams(starParams);
                starBadge.setBackgroundResource(R.drawable.poster_badge_background);
                starBadge.setPadding(margin / 2, margin / 2, margin / 2, margin / 2);
                starBadge.setImageResource(R.drawable.ic_star);
                starBadge.setContentDescription(v.getContext().getString(R.string.starred));
                starBadge.setVisibility(View.GONE);
                ((FrameLayout) iconThumb.getParent()).addView(starBadge);

                // The selection check covers the star's corner while selecting
                checkBadge = new ImageView(v.getContext());
                final FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(size, size,
                        Gravity.BOTTOM | Gravity.START);
                checkParams.setMargins(margin, margin, margin, margin);
                checkBadge.setLayoutParams(checkParams);
                checkBadge.setBackgroundResource(R.drawable.watch_badge_scrim);
                checkBadge.setPadding(margin / 2, margin / 2, margin / 2, margin / 2);
                checkBadge.setImageResource(R.drawable.checkmark_white);
                checkBadge.setContentDescription(v.getContext().getString(R.string.selected));
                checkBadge.setVisibility(View.GONE);
                ((FrameLayout) iconThumb.getParent()).addView(checkBadge);

                v.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (episode == null) {
                            return;
                        }
                        if (mSelecting) {
                            setEpisodeSelected(episode, !mSelectedIds.contains(episode.documentId));
                        } else {
                            mListener.onEpisodeClick(episode);
                        }
                    }
                });
                v.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View view) {
                        if (episode == null) {
                            return false;
                        }
                        if (mSelecting) {
                            setEpisodeSelected(episode, !mSelectedIds.contains(episode.documentId));
                            return true;
                        }
                        mListener.onEpisodeLongClick(episode, view);
                        return true;
                    }
                });
            }

            void bind(DocumentInfo episode) {
                this.episode = episode;
                final int state = mHistory.getState(episode.documentId);
                watchBadge.setVisibility(state == WatchHistory.NONE ? View.GONE : View.VISIBLE);
                if (state != WatchHistory.NONE) {
                    final boolean viewed = state == WatchHistory.VIEWED;
                    watchBadge.setImageResource(viewed ? R.drawable.ic_watch_viewed : R.drawable.ic_watch_viewing);
                    watchBadge.setContentDescription(watchBadge.getContext().getString(
                            viewed ? R.string.videos_viewed : R.string.videos_viewing));
                }
                final boolean starred = !TextUtils.isEmpty(episode.path) && mStarredPaths.contains(episode.path);
                final boolean selected = mSelectedIds.contains(episode.documentId);
                starBadge.setVisibility(starred && !selected ? View.VISIBLE : View.GONE);
                checkBadge.setVisibility(selected ? View.VISIBLE : View.GONE);
                itemView.setActivated(selected);
                if (title != null) {
                    // The season header already says which show and season, so the card is just the number
                    final EpisodeName parsed = EpisodeName.parse(episode.displayName);
                    title.setText(parsed != null
                            ? title.getContext().getString(R.string.videos_episode, parsed.episode)
                            : episode.displayName);
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
