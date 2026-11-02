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

package dev.dworks.apps.anexplorer.fragment;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.OperationCanceledException;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.io.File;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dev.dworks.apps.anexplorer.common.DialogFragment;
import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.ContentProviderClientCompat;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.FileUtils;
import dev.dworks.apps.anexplorer.misc.IconColorUtils;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.misc.TmdbDetails;
import dev.dworks.apps.anexplorer.misc.TmdbPosters;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Document;
import dev.dworks.apps.anexplorer.provider.MediaDocumentsProvider;
import dev.dworks.apps.anexplorer.setting.AppSettings;
import dev.dworks.apps.anexplorer.ui.CircleImage;
import dev.dworks.apps.anexplorer.misc.ThemeColors;

/**
 * Display document title editor and save button.
 */
public class DetailFragment extends DialogFragment {
	public static final String TAG_DETAIL = "DetailFragment";
	private static final String EXTRA_DOC = "document";
	private static final String EXTRA_IS_DIALOG = "is_dialog";
	
	private DocumentInfo doc;
	private boolean isDialog;
	
	private TextView name;
	private TextView type;
	private TextView size;
	private TextView contents;
	private TextView modified;
	private TextView path;
	private ImageView iconMime;
	private ImageView iconThumb;
	private FrameLayout icon;
	private View contents_layout;
    private CircleImage iconMimeBackground;
    private View path_layout;
    private LinearLayout tmdbLayout;
    private LinearLayout tmdbRows;
    private TextView tmdbOverviewLabel;
    private TextView tmdbOverview;
    private ScrollView detailScroll;
    private AsyncTask<Void, Void, TmdbDetails.Info> tmdbTask;

    public static void showAsDialog(FragmentManager fm, DocumentInfo doc) {
		final Bundle args = new Bundle();
		args.putParcelable(EXTRA_DOC, doc);
		args.putBoolean(EXTRA_IS_DIALOG, true);
		
		final DetailFragment fragment = new DetailFragment();
		fragment.setArguments(args);
		fragment.show(fm, TAG_DETAIL);
    }
        
	public static DetailFragment get(FragmentManager fm) {
		return (DetailFragment) fm.findFragmentByTag(TAG_DETAIL);
	}
	
	public static void hide(FragmentManager fm){
		if(null != get(fm)){
			fm.beginTransaction().remove(get(fm)).commitAllowingStateLoss();
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle args = getArguments();
		if(null != args){
			doc = args.getParcelable(EXTRA_DOC);
			isDialog = args.getBoolean(EXTRA_IS_DIALOG);
		}
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final View view = inflater.inflate(R.layout.fragment_detail, container, false);

		name = (TextView) view.findViewById(R.id.name);
		type = (TextView) view.findViewById(R.id.type);
		size = (TextView) view.findViewById(R.id.size);
		contents = (TextView) view.findViewById(R.id.contents);
		modified = (TextView) view.findViewById(R.id.modified);
		path = (TextView) view.findViewById(R.id.path);
		
		contents_layout = view.findViewById(R.id.contents_layout);
		path_layout = view.findViewById(R.id.path_layout);
		tmdbLayout = (LinearLayout) view.findViewById(R.id.tmdb_layout);
		tmdbRows = (LinearLayout) view.findViewById(R.id.tmdb_rows);
		tmdbOverviewLabel = (TextView) view.findViewById(R.id.tmdb_overview_label);
		tmdbOverview = (TextView) view.findViewById(R.id.tmdb_overview);
		detailScroll = (ScrollView) view.findViewById(R.id.detail_scroll);

		iconMime = (ImageView) view.findViewById(R.id.icon_mime);
		iconThumb = (ImageView) view.findViewById(R.id.icon_thumb);
        iconMimeBackground = (CircleImage)view.findViewById(R.id.icon_mime_background);

		icon = (FrameLayout)view.findViewById(android.R.id.icon);
		
		return view;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		if(isDialog){
            setShowsDialog(isDialog);
			getDialog().setTitle(R.string.detail_title);
			// Rounded M3 card instead of the default square dialog window
			final MaterialShapeDrawable background = new MaterialShapeDrawable(
					ShapeAppearanceModel.builder()
							.setAllCornerSizes(getResources().getDimension(R.dimen.card_corner_radius))
							.build());
			background.setFillColor(ColorStateList.valueOf(MaterialColors.getColor(getView(),
					com.google.android.material.R.attr.colorSurfaceContainerHigh)));
			// A full window with an inset background keeps the screen showing around the card,
			// however long the path or the TMDB text is, and lets the height follow the content
			final int insetX = getResources().getDimensionPixelSize(R.dimen.detail_inset_horizontal);
			final int insetY = getResources().getDimensionPixelSize(R.dimen.detail_inset_vertical);
			getDialog().getWindow().setBackgroundDrawable(
					new InsetDrawable(background, insetX, insetY, insetX, insetY));
			getDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
		}
		
		name.setText(doc.displayName);
        iconMimeBackground.setBackgroundColor(IconColorUtils.loadMimeColor(getActivity(), doc.mimeType, doc.authority, doc.documentId, ThemeColors.getPrimaryColor(getActivity())));
		path.setText(doc.path);
        if(!TextUtils.isEmpty(doc.path)){
            path.setText(doc.path);
        }
        else{
            path_layout.setVisibility(View.GONE);
        }
		if (doc.lastModified > 0) {
			modified.setText(Utils.formatTime(getActivity(), doc.lastModified));
		} else {
			// A show is a group of episodes with no date of its own, which would read as 1970
			((View) modified.getParent()).setVisibility(View.GONE);
		}
		if (isShowDocument()) {
			((View) size.getParent()).setVisibility(View.GONE);
		}
		type.setText(IconUtils.getTypeNameFromMimeType(doc.mimeType));
		
		if(!TextUtils.isEmpty(doc.summary)){
			contents.setText(doc.summary);
			contents_layout.setVisibility(View.VISIBLE);
		}
        int docIcon = doc.icon;
        iconMime.setAlpha(1f);
        iconThumb.setAlpha(0f);
        iconThumb.setImageDrawable(null);

        if (docIcon != 0) {
            iconMime.setImageDrawable(IconUtils.loadPackageIcon(getActivity(), doc.authority, docIcon));
        } else {
            iconMime.setImageDrawable(IconUtils.loadMimeIcon(getActivity(), doc.mimeType, doc.authority, doc.documentId, DocumentsActivity.State.MODE_GRID));
        }
		new DetailTask().execute();
		if (TmdbDetails.canLoad(doc)) {
			tmdbTask = new TmdbTask().execute();
		}
		if (detailScroll != null) {
			detailScroll.requestFocus();
		}
	}

	@Override
	public void onDestroyView() {
		if (tmdbTask != null) {
			tmdbTask.cancel(true);
			tmdbTask = null;
		}
		super.onDestroyView();
	}

	private class TmdbTask extends AsyncTask<Void, Void, TmdbDetails.Info> {
		@Override
		protected TmdbDetails.Info doInBackground(Void... params) {
			final Context context = getContext();
			if (context == null) {
				return null;
			}
			return TmdbDetails.load(context.getApplicationContext(), doc);
		}

		@Override
		protected void onPostExecute(TmdbDetails.Info info) {
			if (info != null && isAdded() && getView() != null) {
				showTmdb(info);
			}
		}
	}

	private void showTmdb(TmdbDetails.Info info) {
		if (tmdbLayout == null || tmdbRows == null
				|| info.kind < TmdbDetails.MOVIE || info.kind > TmdbDetails.EPISODE) {
			return;
		}
		final LayoutInflater inflater = getLayoutInflater();
		tmdbRows.removeAllViews();
		boolean hasRow = false;

		if (info.kind == TmdbDetails.MOVIE || info.kind == TmdbDetails.SHOW) {
			if (!TextUtils.isEmpty(info.title) && !info.title.equalsIgnoreCase(doc.displayName)) {
				hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_title, info.title);
			}
			if (!TextUtils.isEmpty(info.originalTitle)
					&& !info.originalTitle.equalsIgnoreCase(info.title)) {
				hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_original_title, info.originalTitle);
			}
		} else if (info.kind == TmdbDetails.SEASON || info.kind == TmdbDetails.EPISODE) {
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_show, info.title);
		}

		if (info.kind == TmdbDetails.SEASON) {
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_season,
					getString(R.string.detail_tmdb_season_value, info.season));
		} else if (info.kind == TmdbDetails.EPISODE) {
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_episode,
					getString(R.string.detail_tmdb_season_episode, info.season, info.episode));
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_title, info.episodeTitle);
		}

		final String date = formatDate(info.date);
		if (info.kind == TmdbDetails.MOVIE) {
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_released, date);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_rated, info.certification);
			hasRow |= addRating(inflater, info);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_genres, info.genres);
			hasRow |= addRuntime(inflater, info.runtime);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_director, info.director);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_writer, info.writers);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_cast, info.cast);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_tagline, info.tagline);
		} else if (info.kind == TmdbDetails.SHOW) {
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_first_aired, date);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_rated, info.certification);
			hasRow |= addRating(inflater, info);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_genres, info.genres);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_seasons, count(info.seasons));
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_episodes, count(info.episodes));
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_status, info.status);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_network, info.network);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_creator, info.creator);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_cast, info.cast);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_tagline, info.tagline);
		} else if (info.kind == TmdbDetails.SEASON) {
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_aired, date);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_episodes, count(info.episodes));
			hasRow |= addRating(inflater, info);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_genres, info.genres);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_rated, info.certification);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_cast, info.cast);
		} else {
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_aired, date);
			hasRow |= addRating(inflater, info);
			hasRow |= addRuntime(inflater, info.runtime);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_director, info.director);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_writer, info.writers);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_cast, info.cast);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_guest_stars, info.guestStars);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_genres, info.genres);
			hasRow |= addTmdbRow(inflater, R.string.detail_tmdb_rated, info.certification);
		}

		final boolean hasOverview = !TextUtils.isEmpty(info.overview);
		tmdbOverview.setText(hasOverview ? info.overview : null);
		tmdbOverviewLabel.setVisibility(hasOverview ? View.VISIBLE : View.GONE);
		tmdbOverview.setVisibility(hasOverview ? View.VISIBLE : View.GONE);
		if (hasRow || hasOverview) {
			tmdbLayout.setVisibility(View.VISIBLE);
		}
	}

	private boolean addTmdbRow(LayoutInflater inflater, int label, String value) {
		if (TextUtils.isEmpty(value)) {
			return false;
		}
		final View row = inflater.inflate(R.layout.item_detail_row, tmdbRows, false);
		((TextView) row.findViewById(R.id.label)).setText(label);
		((TextView) row.findViewById(R.id.value)).setText(value);
		tmdbRows.addView(row);
		return true;
	}

	private boolean addRating(LayoutInflater inflater, TmdbDetails.Info info) {
		if (info.rating <= 0) {
			return false;
		}
		final String rating = String.format(Locale.getDefault(), "%.1f", info.rating);
		if (info.votes > 0) {
			final String votes = getResources().getQuantityString(R.plurals.detail_tmdb_vote_count,
					info.votes, NumberFormat.getIntegerInstance().format(info.votes));
			return addTmdbRow(inflater, R.string.detail_tmdb_rating,
					getString(R.string.detail_tmdb_rating_votes, rating, votes));
		}
		return addTmdbRow(inflater, R.string.detail_tmdb_rating,
				getString(R.string.detail_tmdb_rating_value, rating));
	}

	private boolean addRuntime(LayoutInflater inflater, int minutes) {
		if (minutes <= 0) {
			return false;
		}
		final int hours = minutes / 60;
		final int rest = minutes % 60;
		final String value;
		if (hours > 0 && rest > 0) {
			value = getString(R.string.detail_tmdb_runtime_hours, hours, rest);
		} else if (hours > 0) {
			value = getString(R.string.detail_tmdb_runtime_hours_only, hours);
		} else {
			value = getString(R.string.detail_tmdb_runtime_minutes, rest);
		}
		return addTmdbRow(inflater, R.string.detail_tmdb_runtime, value);
	}

	private String count(int value) {
		return value <= 0 ? null : NumberFormat.getIntegerInstance().format(value);
	}

	private String formatDate(String value) {
		if (TextUtils.isEmpty(value)) {
			return null;
		}
		try {
			final SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
			parser.setLenient(false);
			final Date date = parser.parse(value);
			if (date != null) {
				return DateFormat.getDateInstance(DateFormat.MEDIUM).format(date);
			}
		} catch (ParseException e) {
			// An empty or partial date is shown as it came
		}
		return value;
	}

	/** Whether the media provider gives this folder the poster of its show */
	private boolean isShowDocument() {
		return MediaDocumentsProvider.AUTHORITY.equals(doc.authority)
				&& doc.documentId != null
				&& (doc.documentId.startsWith(MediaDocumentsProvider.TYPE_VIDEOS_SHOW + ":")
				|| doc.documentId.startsWith(MediaDocumentsProvider.TYPE_VIDEOS_SEASON + ":"));
	}

	private class DetailTask extends AsyncTask<Void, Void, Void>{

		private Bitmap result;
		String sizeString = "";
		String filePath = "";
		String contentsString;
		
		@Override
		protected Void doInBackground(Void... params) {
			filePath = doc.path;
			final boolean directory = Utils.isDir(doc.mimeType);

			// Show folders have no file size, but their poster is still worth loading
			if (!directory || isShowDocument()) {
				int thumbSize = getResources().getDimensionPixelSize(R.dimen.grid_width);
				Point mThumbSize = new Point(thumbSize, thumbSize);
				final Uri uri = DocumentsContract.buildDocumentUri(doc.authority, doc.documentId);
				final Context context = getActivity();
				final ContentResolver resolver = context.getContentResolver();
				ContentProviderClient client = null;
				try {
					if (TmdbDetails.canLoad(doc) && !directory) {
						result = TmdbPosters.load(context, doc.displayName);
					}
					if (result == null) {
						if (doc.mimeType.equals(Document.MIME_TYPE_APK) && !TextUtils.isEmpty(filePath)) {
							result = ((BitmapDrawable) IconUtils.loadPackagePathIcon(context, filePath, Document.MIME_TYPE_APK)).getBitmap();
						} else {
							client = DocumentsApplication.acquireUnstableProviderOrThrow(resolver, uri.getAuthority());
							result = DocumentsContract.getDocumentThumbnail(resolver, uri, mThumbSize, null);
						}
					}
				} catch (Exception e) {
					if (!(e instanceof OperationCanceledException)) {
						Log.w(TAG_DETAIL, "Failed to load thumbnail for " + uri + ": " + e);
					}
					CrashReportingManager.logException(e);
				} finally {
					ContentProviderClientCompat.releaseQuietly(client);
				}

				if (!directory) {
					sizeString = Formatter.formatFileSize(context, doc.size);
				}
			}

			if (directory) {
				if(!TextUtils.isEmpty(filePath)){
					File dir = new File(filePath);
					// The listing no longer carries the file count, so count it here
					final String[] children = dir.list();
					if (TextUtils.isEmpty(doc.summary) && null != children) {
						contentsString = FileUtils.formatFileCount(children.length);
					}
					sizeString = Formatter.formatFileSize(getActivity(), Utils.getDirectorySize(dir));
				}				
			}
			
			return null;
		}
		
		@Override
		protected void onPostExecute(Void e) {
			super.onPostExecute(e);
			if(!TextUtils.isEmpty(sizeString)) {
                size.setText(sizeString);
            }
			if (!TextUtils.isEmpty(contentsString)) {
				contents.setText(contentsString);
				contents_layout.setVisibility(View.VISIBLE);
			}

			if(null != result){
                ImageView.ScaleType scaleType = doc.mimeType.equals(Document.MIME_TYPE_APK) ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP;
				iconThumb.setScaleType(scaleType);
				iconThumb.setTag(null);
				iconThumb.setImageBitmap(result);
				final float targetAlpha = iconMime.isEnabled() ? 1f : 0.5f;
                iconMimeBackground.animate().alpha(0f).start();
				iconMime.setAlpha(targetAlpha);
				iconMime.animate().alpha(0f).start();
				iconThumb.setAlpha(0f);
				iconThumb.animate().alpha(targetAlpha).start();
			}
		}
	}
}