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

import android.app.Activity;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ActivityNotFoundException;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.RecyclerView;

import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;

import dev.dworks.apps.anexplorer.BaseActivity;
import dev.dworks.apps.anexplorer.BaseActivity.State;
import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.QuickShareSendActivity;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.common.DialogBuilder;
import dev.dworks.apps.anexplorer.common.RecyclerFragment;
import dev.dworks.apps.anexplorer.directory.DividerItemDecoration;
import dev.dworks.apps.anexplorer.directory.DocumentsAdapter;
import dev.dworks.apps.anexplorer.directory.FolderSizeAsyncTask;
import dev.dworks.apps.anexplorer.directory.MarginDecoration;
import dev.dworks.apps.anexplorer.directory.MultiChoiceHelper;
import dev.dworks.apps.anexplorer.directory.MultiChoiceHelper.MultiChoiceModeListener;
import dev.dworks.apps.anexplorer.loader.DirectoryLoader;
import dev.dworks.apps.anexplorer.loader.RecentLoader;
import dev.dworks.apps.anexplorer.misc.AnalyticsManager;
import dev.dworks.apps.anexplorer.misc.AsyncTask;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.IconHelper;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.misc.MimePredicate;
import dev.dworks.apps.anexplorer.misc.MimeTypes;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.SAFManager;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DirectoryResult;
import dev.dworks.apps.anexplorer.adapter.ShowSeasonsAdapter;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Document;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.provider.ExplorerProvider;
import dev.dworks.apps.anexplorer.libcore.io.IoUtils;
import dev.dworks.apps.anexplorer.provider.ExternalStorageProvider;
import dev.dworks.apps.anexplorer.provider.MediaDocumentsProvider;
import dev.dworks.apps.anexplorer.provider.NonMediaDocumentsProvider;
import dev.dworks.apps.anexplorer.provider.RecentsProvider;
import dev.dworks.apps.anexplorer.provider.RecentsProvider.StateColumns;
import dev.dworks.apps.anexplorer.setting.AppSettings;
import dev.dworks.apps.anexplorer.ui.MaterialProgressBar;
import dev.dworks.apps.anexplorer.ui.RecyclerViewPlus;
import dev.dworks.apps.anexplorer.ui.SideMenuPopup;
import dev.dworks.apps.anexplorer.provider.AppsProvider;
import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.misc.ThemeColors;

import static android.widget.LinearLayout.VERTICAL;
import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_BROWSE;
import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_CREATE;
import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_MANAGE;
import static dev.dworks.apps.anexplorer.BaseActivity.State.MODE_GRID;
import static dev.dworks.apps.anexplorer.BaseActivity.State.MODE_UNKNOWN;
import static dev.dworks.apps.anexplorer.BaseActivity.State.SORT_ORDER_UNKNOWN;
import static dev.dworks.apps.anexplorer.BaseActivity.TAG;
import static dev.dworks.apps.anexplorer.misc.AnalyticsManager.FILE_COUNT;
import static dev.dworks.apps.anexplorer.misc.AnalyticsManager.FILE_MOVE;
import static dev.dworks.apps.anexplorer.misc.AnalyticsManager.FILE_TYPE;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_DOC;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_IGNORE_STATE;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_QUERY;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_ROOT;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_TYPE;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorInt;
import static dev.dworks.apps.anexplorer.model.DocumentInfo.getCursorString;
import static dev.dworks.apps.anexplorer.ui.RecyclerViewPlus.TYPE_GRID;
import static dev.dworks.apps.anexplorer.ui.RecyclerViewPlus.TYPE_LIST;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends RecyclerFragment implements MenuItem.OnMenuItemClickListener {

	private static final String KEY_ADAPTER = "key_adapter";
	private View mEmptyGroup;
	private ImageView mEmptyIcon;
	private TextView mEmptyView;
	private TextView mEmptyMessage;

	public static final int TYPE_NORMAL = 1;
	public static final int TYPE_SEARCH = 2;
	public static final int TYPE_RECENT_OPEN = 3;

	public static final int ANIM_NONE = 1;
	public static final int ANIM_SIDE = 2;
	public static final int ANIM_DOWN = 3;
	public static final int ANIM_UP = 4;

	private int mType = TYPE_NORMAL;
	private String mStateKey;
	// Set when the folder was picked from the sidebar, so the user can keep moving through it
	private boolean mKeepSidebarFocus;
	private boolean mKeepTabFocus;
	// The row to focus once the selection bar closes and the files load again; -1 for none
	private int mRefocusPosition = -1;
	private boolean mReloadAppsOnResume;
	private int mLastCheckedPosition = -1;

	private int mLastMode = MODE_UNKNOWN;
	private int mLastSortOrder = SORT_ORDER_UNKNOWN;
	private boolean mLastShowSize = false;
	private boolean mLastShowFolderSize = false;
	private boolean mLastShowThumbnail = false;
    private int mLastShowColor = 0;
    private int mLastShowAccentColor = 0;
    private boolean mLastShowHiddenFiles = false;

	private boolean mHideGridTitles = false;

	private DocumentsAdapter mAdapter;
	private final HashSet<String> mStarredPaths = new HashSet<>();
	private final HashSet<String> mBookmarkedPaths = new HashSet<>();
	private LoaderCallbacks<DirectoryResult> mCallbacks;

	private final int mLoaderId = 42;
	private RootInfo root;
	private DocumentInfo doc;
	private View mAppTabs;
	private View mVideoTabs;
	/** Packages still to uninstall; the system asks about one app at a time */
	private final ArrayList<String> mUninstallQueue = new ArrayList<>();
	/** Android TV's placeholder for apps it lacks, e.g. email; it can't open anything */
	private static final String STUB_PACKAGE = "com.android.tv.frameworkpackagestubs";
    private int mDefaultColor;
    private MaterialProgressBar mProgressBar;
    private boolean isOperationSupported;
	private ContentProviderClient mExternalStorageClient;
	private BaseActivity mActivity;
	private final DocumentsAdapter.Environment mAdapterEnv = new AdapterEnvironment();
	private IconHelper mIconHelper;
	private MultiChoiceHelper mMultiChoiceHelper;
	boolean selectAll = true;

	public static void showNormal(FragmentManager fm, RootInfo root, DocumentInfo doc, int anim) {
		show(fm, TYPE_NORMAL, root, doc, null, anim);
	}

	public static void showSearch(FragmentManager fm, RootInfo root, DocumentInfo doc, String query, int anim) {
		show(fm, TYPE_SEARCH, root, doc, query, anim);
	}

	public static void showRecentsOpen(FragmentManager fm, int anim, RootInfo root) {
		show(fm, TYPE_RECENT_OPEN, root, null, null, anim);
	}

	private static void show(FragmentManager fm, int type, RootInfo root, DocumentInfo doc, String query, int anim) {
		final Bundle args = new Bundle();
		args.putInt(EXTRA_TYPE, type);
		args.putParcelable(EXTRA_ROOT, root);
		args.putParcelable(EXTRA_DOC, doc);
		args.putString(EXTRA_QUERY, query);

		final FragmentTransaction ft = fm.beginTransaction();
		switch (anim) {
		case ANIM_SIDE:
			args.putBoolean(EXTRA_IGNORE_STATE, true);
			break;
		case ANIM_DOWN:
			ft.setCustomAnimations(R.animator.dir_down, R.animator.dir_frozen);
			break;
		case ANIM_UP:
			ft.setCustomAnimations(R.animator.dir_frozen, R.animator.dir_up);
			break;
		}

		final DirectoryFragment fragment = new DirectoryFragment();
		fragment.setArguments(args);

		ft.replace(R.id.container_directory, fragment);
		ft.commitAllowingStateLoss();
	}

	private static String buildStateKey(RootInfo root, DocumentInfo doc) {
        return (root != null ? root.authority : "null") + ';' + (root != null ? root.rootId : "null") + ';' + (doc != null ? doc.documentId : "null");
	}

	public static Fragment get(FragmentManager fm) {
		// TODO: deal with multiple directories shown at once
		return fm.findFragmentById(R.id.container_directory);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final Context context = inflater.getContext();
		mActivity = (BaseActivity) getActivity();
		final View view = inflater.inflate(R.layout.fragment_directory, container, false);

		return view;
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		// The folder being replaced is still on screen here, so focus is still where the user
		// picked it from
		if (context instanceof Activity) {
			mKeepSidebarFocus = isInSidebar(((Activity) context).getCurrentFocus());
			mKeepTabFocus = isInTabs((Activity) context, ((Activity) context).getCurrentFocus());
		}
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		mProgressBar = (MaterialProgressBar) view.findViewById(R.id.progressBar);
		mEmptyGroup = view.findViewById(R.id.empty_group);
		mEmptyIcon = (ImageView) view.findViewById(R.id.empty_icon);
		mEmptyView = (TextView) view.findViewById(android.R.id.empty);
		mEmptyMessage = (TextView) view.findViewById(R.id.empty_message);
		mAppTabs = getActivity().findViewById(R.id.app_tabs);
		mVideoTabs = getActivity().findViewById(R.id.video_tabs);
		getListView().setRecyclerListener(mRecycleListener);
		// Reuse the previous folder's rows: inflating a card costs ~20ms on a TV
		getListView().setRecycledViewPool(mActivity.getDirectoryViewPool());
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		// The tabs live in the toolbar, so they would linger on screens without this fragment.
		// A fragment that replaced this one owns them now and is left alone.
		if (mAppTabs != null && mAppTabs.getTag() == this) mAppTabs.setVisibility(View.GONE);
		if (mVideoTabs != null && mVideoTabs.getTag() == this) mVideoTabs.setVisibility(View.GONE);
		mMultiChoiceHelper.clearChoices();
		// Cancel any outstanding thumbnail requests
		final ViewGroup target = getListView();
		final int count = target.getChildCount();
		for (int i = 0; i < count; i++) {
			final View view = target.getChildAt(i);
			cancelThumbnailTask(view);
		}
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		final Context context = getActivity();
		final State state = getDisplayState(DirectoryFragment.this);

		root = getArguments().getParcelable(EXTRA_ROOT);
		doc = getArguments().getParcelable(EXTRA_DOC);

		// Android 10 dropped this access prompt; all-files access covers the drive instead
		if(null != root && root.isSecondaryStorage() && state.action == ACTION_BROWSE
				&& Build.VERSION.SDK_INT < Build.VERSION_CODES.Q){
			if(!doc.isWriteSupported()){
				SAFManager.takeCardUriPermission(getActivity(), root, doc);
			}
		}
        isOperationSupported = root != null && root.isUsbStorage();

		mIconHelper = new IconHelper(mActivity, MODE_GRID);
		mAdapter = new DocumentsAdapter(mItemListener, mAdapterEnv);

		mMultiChoiceHelper = new MultiChoiceHelper(mActivity, mAdapter);
		mMultiChoiceHelper.setMultiChoiceModeListener(mMultiListener);

		if(null != savedInstanceState) {
			mMultiChoiceHelper.onRestoreInstanceState(savedInstanceState.getParcelable(KEY_ADAPTER));
		}
		mType = getArguments().getInt(EXTRA_TYPE);
		mStateKey = buildStateKey(root, doc);

		if (mType == TYPE_RECENT_OPEN) {
			// Hide titles when showing recents for picking images/videos
			mHideGridTitles = MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, state.acceptMimes);
		} else {
			mHideGridTitles = (doc != null) && doc.isGridTitlesHidden();
		}

		mCallbacks = new LoaderCallbacks<DirectoryResult>() {
			@Override
			public Loader<DirectoryResult> onCreateLoader(int id, Bundle args) {
				final String query = getArguments().getString(EXTRA_QUERY);
				showList(false);
				Uri contentsUri;
				switch (mType) {
				case TYPE_NORMAL:
					contentsUri = DocumentsContract.buildChildDocumentsUri(doc.authority, doc.documentId);
					if (state.action == ACTION_MANAGE) {
						contentsUri = DocumentsContract.setManageMode(contentsUri);
					}
					return new DirectoryLoader(context, mType, root, doc, contentsUri, state.userSortOrder);
				case TYPE_SEARCH:
					// On TV, search the folder being shown rather than the whole storage; the
					// provider accepts a folder's document id in place of a root id
					final boolean searchFolder = null != doc
							&& ExternalStorageProvider.AUTHORITY.equals(root.authority);
					contentsUri = DocumentsContract.buildSearchDocumentsUri(root.authority,
							searchFolder ? doc.documentId : root.rootId, query);
					if (state.action == ACTION_MANAGE) {
						contentsUri = DocumentsContract.setManageMode(contentsUri);
					}
					return new DirectoryLoader(context, mType, root, doc, contentsUri, state.userSortOrder);
				case TYPE_RECENT_OPEN:
					final RootsCache roots = DocumentsApplication.getRootsCache(context);
					return new RecentLoader(context, roots, state);
				default:
					throw new IllegalStateException("Unknown type " + mType);
				}
			}

			@Override
			public void onLoadFinished(Loader<DirectoryResult> loader, DirectoryResult result) {
				if (!isAdded())
					return;

				saveDisplayState();
				loadMarkedPaths();
				mAdapter.swapResult(result);
				final List<DocumentInfo> seasons = isShowFolder() ? readSeasons(result) : null;

				// Push latest state up to UI
				// TODO: if mode change was racing with us, don't overwrite it
				if (result.mode != MODE_UNKNOWN) {
					state.derivedMode = result.mode;
				}
                if (result.sortOrder != SORT_ORDER_UNKNOWN) {
                    state.derivedSortOrder = result.sortOrder;
                }
				((BaseActivity) context).onDirectoryLoaded();

				updateDisplayState();
				showSeasons(seasons);

				if (isResumed()) {
					showList(true);
				} else {
					setListShownNoAnimation(true);
				}

				mLastSortOrder = state.derivedSortOrder;
				// Don't take focus from the search field while the user is typing, or from the
				// sidebar while the user is moving through it, or from the tab they just picked
				if (mRefocusPosition >= 0) {
					// The files changed after the selection bar closed, deleted ones gone
					focusRow();
					mRefocusPosition = -1;
				} else if (mKeepTabFocus) {
					focusSelectedTab();
				} else if (!(getActivity().getCurrentFocus() instanceof EditText) && !mKeepSidebarFocus
						&& !(mAdapter.isEmpty() && (mAppTabs.getVisibility() == View.VISIBLE
						|| mVideoTabs.getVisibility() == View.VISIBLE))) {
					getListView().requestFocus();
				}
			}

			@Override
			public void onLoaderReset(Loader<DirectoryResult> loader) {
				mAdapter.swapResult(null);
			}
		};
		setListAdapter(mAdapter);
		showList(false);
		// Kick off loader at least once
		getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);

		updateDisplayState();
		setUpAppTabs();
		setUpVideoTabs();
	}

	/** APKs shows tabs for installed apps, system apps and APK files, like Google Files */
	private void setUpAppTabs() {
		mAppTabs.setTag(this);
		if (null == root || !(root.isApp() || root.isApk())) {
			mAppTabs.setVisibility(View.GONE);
			return;
		}
		mAppTabs.setVisibility(View.VISIBLE);
		setUpAppTab(R.id.tab_apk_files, NonMediaDocumentsProvider.AUTHORITY,
				NonMediaDocumentsProvider.TYPE_APK_ROOT, root.isApk());
		setUpAppTab(R.id.tab_user_apps, AppsProvider.AUTHORITY, AppsProvider.ROOT_ID_USER_APP,
				root.isUserApp());
		setUpAppTab(R.id.tab_system_apps, AppsProvider.AUTHORITY, AppsProvider.ROOT_ID_SYSTEM_APP,
				root.isSystemApp());
	}

	/**
	 * Videos shows Movies (the root itself), TV Shows and Videos as tabs. Inside a show or a
	 * season they are hidden: BACK returns to the tab it was opened from.
	 */
	private void setUpVideoTabs() {
		mVideoTabs.setTag(this);
		mVideoTabs.setVisibility(View.GONE);
		if (null == root || !root.isVideos() || null == doc) {
			return;
		}
		final String id = doc.documentId;
		final boolean shows = MediaDocumentsProvider.TYPE_VIDEOS_SHOWS.equals(id);
		final boolean other = MediaDocumentsProvider.TYPE_VIDEOS_OTHER.equals(id);
		final boolean movies = MediaDocumentsProvider.TYPE_VIDEOS_ROOT.equals(id)
				|| MediaDocumentsProvider.TYPE_VIDEOS_MOVIES.equals(id);
		if (!(shows || other || movies)) {
			return;
		}
		mVideoTabs.setVisibility(View.VISIBLE);
		setUpVideoTab(R.id.tab_videos_movies, null, movies);
		setUpVideoTab(R.id.tab_videos_shows, MediaDocumentsProvider.TYPE_VIDEOS_SHOWS, shows);
		setUpVideoTab(R.id.tab_videos_other, MediaDocumentsProvider.TYPE_VIDEOS_OTHER, other);
	}

	private void setUpVideoTab(int id, final String groupId, boolean current) {
		final View tab = mVideoTabs.findViewById(id);
		tab.setSelected(current);
		tab.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				final Activity activity = getActivity();
				if (!v.isSelected() && activity instanceof DocumentsActivity) {
					((DocumentsActivity) activity).onVideoTabPicked(groupId);
				}
			}
		});
	}

	private void setUpAppTab(int id, final String authority, final String rootId, boolean current) {
		final View tab = mAppTabs.findViewById(id);
		tab.setSelected(current);
		tab.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				final Activity activity = getActivity();
				final RootInfo target = DocumentsApplication.getRootsCache(activity)
						.getRootInfo(rootId, authority);
				if (null != target && !target.equals(root) && activity instanceof DocumentsActivity) {
					((DocumentsActivity) activity).onRootPicked(target, false);
				}
			}
		});
	}

	/**
	 * Focuses the row at mRefocusPosition, or the last one when fewer are left, once the list
	 * has laid out what it holds now; before that the rows on screen may be ones just deleted
	 */
	private void focusRow() {
		if (!isAdded() || mRefocusPosition < 0) {
			return;
		}
		final int wanted = mRefocusPosition;
		final RecyclerView list = getListView();
		list.post(() -> {
			if (!isAdded()) {
				return;
			}
			final int count = mAdapter.getItemCount();
			if (count == 0) {
				list.requestFocus();
				return;
			}
			final int position = Math.min(wanted, count - 1);
			final View row = list.getLayoutManager().findViewByPosition(position);
			if (null != row) {
				row.requestFocus();
				return;
			}
			list.scrollToPosition(position);
			list.post(() -> {
				final View shown = list.getLayoutManager().findViewByPosition(position);
				(null != shown ? shown : list).requestFocus();
			});
		});
	}

	/** Puts focus back on the tab picked, as swapping the folder can drop it. */
	private void focusSelectedTab() {
		final View strip = mAppTabs.getVisibility() == View.VISIBLE ? mAppTabs
				: mVideoTabs.getVisibility() == View.VISIBLE ? mVideoTabs : null;
		if (null == strip) {
			return;
		}
		final ViewGroup group = (ViewGroup) strip;
		for (int i = 0; i < group.getChildCount(); i++) {
			if (group.getChildAt(i).isSelected()) {
				group.getChildAt(i).requestFocus();
				return;
			}
		}
	}

	private static boolean isInTabs(Activity activity, View view) {
		final View appTabs = activity.findViewById(R.id.app_tabs);
		final View videoTabs = activity.findViewById(R.id.video_tabs);
		for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
			if (v == appTabs || v == videoTabs) {
				return true;
			}
		}
		return false;
	}

	private boolean isInList(View view) {
		for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
			if (v == getListView()) {
				return true;
			}
		}
		return false;
	}

	private boolean isInSidebar(View view) {
		final View sidebar = view != null ? view.getRootView().findViewById(R.id.container_roots) : null;
		for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
			if (v == sidebar) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The list fades in over ~500ms after every load, which is most of the wait once a folder has
	 * loaded; skip the fade when folder animations are off (the default on TV).
	 */
	private void showList(boolean shown) {
		if (AppSettings.getFolderAnimation(getActivity())) {
			setListShown(shown);
		} else {
			setListShownNoAnimation(shown);
		}
	}

	public void saveDisplayState(){
		// Remember last scroll location
		final SparseArray<Parcelable> container = new SparseArray<Parcelable>();
		getView().saveHierarchyState(container);
		final State state = getDisplayState(this);
		state.dirState.put(mStateKey, container);
	}

	// Restore any previous instance state
	public void restoreDisplaySate(){
		final State state = getDisplayState(this);
		final SparseArray<Parcelable> container = state.dirState.remove(mStateKey);
		if (container != null && !getArguments().getBoolean(EXTRA_IGNORE_STATE, false)) {
			getView().restoreHierarchyState(container);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		saveDisplayState();
	}

	@Override
	public void onResume() {
		super.onResume();
		updateDisplayState();
		if (!mUninstallQueue.isEmpty()) {
			uninstallNext();
		} else if (mReloadAppsOnResume) {
			// Back from uninstall or App info, where the app may have gone; the reload refocuses its row
			mReloadAppsOnResume = false;
			AppsProvider.notifyDocumentsChanged(getActivity(), root.rootId);
		}
	}

	/** Asks to uninstall the next queued app */
	private void uninstallNext() {
		final String packageName = mUninstallQueue.remove(0);
		try {
			startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName)));
		} catch (ActivityNotFoundException e) {
			mUninstallQueue.clear();
			Utils.showSnackBar(getActivity(), getString(R.string.unable_to_open_app));
		}
	}

    public void onDisplayStateChanged() {
        updateDisplayState();
    }

	public void onUserSortOrderChanged() {
        updateUserState(StateColumns.SORT_ORDER);
		// Sort order change always triggers reload; we'll trigger state change
		// on the flip side.
		getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);
		getListView().smoothScrollToPosition(0);
	}

    public void onUserModeChanged() {

        updateUserState(StateColumns.MODE);
		// Mode change is just visual change; no need to kick loader, and
		// deliver change event immediately.
		((BaseActivity) getActivity()).onStateChanged();

		updateDisplayState();
	}

    public boolean isEmpty() {
        return null != mAdapter && mAdapter.isEmpty();
    }

    /** Refreshes the star and bookmark badges after they change elsewhere, like the sidebar. */
    public void onMarksChanged() {
        if (mAdapter == null) {
            return;
        }
        loadMarkedPaths();
        mAdapter.notifyDataSetChanged();
    }

    private void updateUserState(String column) {
        final ContentResolver resolver = getActivity().getContentResolver();
        final State state = getDisplayState(this);

        if(column.startsWith(StateColumns.MODE)) {
            AppSettings.setViewMode(getActivity(), state.userMode);
            state.derivedMode = state.userMode;
            return;
        }

        final RootInfo root = getArguments().getParcelable(EXTRA_ROOT);
        final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

        if (root != null && doc != null) {
            final Uri stateUri = RecentsProvider.buildState(root.authority, root.rootId, doc.documentId);
            final ContentValues values = new ContentValues();
            values.put(StateColumns.SORT_ORDER, state.userSortOrder);

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    resolver.insert(stateUri, values);
                    return null;
                }
            }.execute();
        }
        state.derivedSortOrder = state.userSortOrder;
    }

	private void updateDisplayState() {
		final State state = getDisplayState(this);

        mDefaultColor = ThemeColors.getPrimaryColor(getActivity());
        int accentColor = AppSettings.getAccentColor();
        if (mLastMode == state.derivedMode &&  mLastSortOrder == state.derivedSortOrder
                && mLastShowSize == state.showSize
                && mLastShowFolderSize == state.showFolderSize
				&& mLastShowThumbnail == state.showThumbnail
				&& mLastShowHiddenFiles == state.showHiddenFiles
                && (mLastShowColor != 0 && mLastShowColor == mDefaultColor)
                && (mLastShowAccentColor != 0 && mLastShowAccentColor == accentColor))
			return;
        boolean refreshData = mLastShowHiddenFiles != state.showHiddenFiles;
		mLastMode = state.derivedMode;
        mLastSortOrder = state.derivedSortOrder;
		mLastShowSize = state.showSize;
		mLastShowFolderSize = state.showFolderSize;
		mLastShowThumbnail = state.showThumbnail;
		mLastShowHiddenFiles = state.showHiddenFiles;

        mLastShowColor = mDefaultColor;
        mProgressBar.setColor(ThemeColors.getAccentColor(getActivity()));
		mIconHelper.setThumbnailsEnabled(state.showThumbnail);

		// Season carousels need full-width rows, so a show folder stays a list in either view mode
		if (state.derivedMode == MODE_GRID && mShowAdapter == null) {
			((RecyclerViewPlus)getListView()).setType(TYPE_GRID);
		} else {
			((RecyclerViewPlus)getListView()).setType(TYPE_LIST);
		}
		mIconHelper.setViewMode(state.derivedMode);
		setItemDivider();
		restoreDisplaySate();

        if(refreshData) {
            onUserSortOrderChanged();
        }
	}

	private void setItemDivider(){
		if(getListView().getItemDecorationCount() != 0){
			getListView().removeItemDecorationAt(0);
		}
		// Grid tiles and list rows are both separate rounded cards
		getListView().addItemDecoration(new MarginDecoration(mActivity));
	}

	/** Set while a TV show is open: its seasons are shown as carousels instead of folder tiles */
	private ShowSeasonsAdapter mShowAdapter;

	private boolean isShowFolder() {
		return doc != null && doc.documentId != null && doc.documentId.startsWith("videos_show:");
	}

	/** Copies the season folders out before the loader can close its cursor */
	private List<DocumentInfo> readSeasons(DirectoryResult result) {
		final List<DocumentInfo> seasons = new ArrayList<>();
		final Cursor cursor = result.cursor;
		if (cursor != null && cursor.moveToFirst()) {
			do {
				final DocumentInfo season = DocumentInfo.fromDirectoryCursor(cursor);
				if (season.isDirectory()) {
					seasons.add(season);
				}
			} while (cursor.moveToNext());
		}
		return seasons;
	}

	private void showSeasons(List<DocumentInfo> seasons) {
		if (mShowAdapter != null) {
			mShowAdapter.destroy();
			mShowAdapter = null;
		}
		final RecyclerViewPlus list = (RecyclerViewPlus) getListView();
		if (seasons == null || seasons.isEmpty()) {
			if (list.getAdapter() != mAdapter) {
				list.setAdapter(mAdapter);
			}
			return;
		}
		mShowAdapter = new ShowSeasonsAdapter(mActivity, mIconHelper, seasons,
				new ShowSeasonsAdapter.OnEpisodeClickListener() {
					@Override
					public void onEpisodeClick(DocumentInfo episode) {
						((BaseActivity) getActivity()).onDocumentPicked(episode);
					}
				});
		list.setType(RecyclerViewPlus.TYPE_LIST);
		list.setAdapter(mShowAdapter);
	}

	private RecyclerItemClickListener.OnItemClickListener mItemListener
			= new RecyclerItemClickListener.OnItemClickListener() {
		@Override
		public void onItemClick(View view, int position) {
			if (mShowAdapter != null) {
				return;
			}
			// An app can't be opened from here; its actions are in the menu
			if (null != root && root.isApp()) {
				showPopupMenu(view, position);
				return;
			}
			final Cursor cursor = mAdapter.getItem(position);
			if (cursor != null) {
				final String docId = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
				final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
				final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
				if (isDocumentEnabled(docMimeType, docFlags)) {
					final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
					((BaseActivity) getActivity()).onDocumentPicked(doc);
					Bundle params = new Bundle();
					String type = IconUtils.getTypeNameFromMimeType(doc.mimeType);
					params.putString(FILE_TYPE, type);
					if(doc.isDirectory()) {
						AnalyticsManager.logEvent("browse", root, params);
					} else {
						AnalyticsManager.logEvent("open" + "_" + type, params);
					}
				}
			}
		}

		@Override
		public void onItemLongClick(final View view, final int position) {
			if (mShowAdapter != null) {
				return;
			}
			final int viewType = mAdapter.getItemViewType(position);
			if (viewType != DocumentsAdapter.ITEM_TYPE_LIST && viewType != DocumentsAdapter.ITEM_TYPE_GRID) {
				return;
			}
			view.post(new Runnable() {
				@Override
				public void run() {
					showPopupMenu(view, position);
				}
			});
		}

		@Override
		public void onItemViewClick(final View view, final int position) {
			if (mShowAdapter != null) {
				return;
			}
			//final int position = mCurrentView.getPositionForView(v);
			if (position != ListView.INVALID_POSITION) {
				int count = mAdapter.getCheckedItemCount();
				if (view.getId() == android.R.id.icon) {
					if (count == 0) {
						mMultiChoiceHelper.startSupportActionModeIfNeeded();
						mMultiChoiceHelper.setItemChecked(position,true, true);
					} else {
						mMultiChoiceHelper.setItemChecked(position,
								!mAdapter.isItemChecked(position), true);
					}
				} else if (view.getId() == R.id.button_popup) {
					view.post(new Runnable() {
						@Override
						public void run() {
							showPopupMenu(view, position);
						}
					});
				}
			}
		}
	};

	private MultiChoiceModeListener mMultiListener = new MultiChoiceModeListener() {

		private boolean editMode;

		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			editMode = root != null && (root.isEditSupported() || copiesAcross());
			mode.getMenuInflater().inflate(R.menu.mode_directory, menu);
			int count = mAdapter.getCheckedItemCount();
			mode.setTitle(count+"");
			return true;
		}

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {

			final Context context = getActivity();
			if(null != context){
				final BaseActivity activity = (BaseActivity) context;
				if(!activity.getActionMode()){
                    activity.setUpDefaultStatusBar();
					activity.setActionMode(true);
				}
			}
			final int count = mAdapter.getCheckedItemCount();
			final State state = getDisplayState(DirectoryFragment.this);

			final MenuItem open = menu.findItem(R.id.menu_open);
			final MenuItem openWith = menu.findItem(R.id.menu_open_with);
			final MenuItem delete = menu.findItem(R.id.menu_delete);
			final MenuItem rename = menu.findItem(R.id.menu_rename);

			final boolean manageMode = state.action == ACTION_BROWSE;
			final boolean canDelete = doc != null && doc.isDeleteSupported();
			final boolean canRename = doc != null && doc.isRenameSupported();
			open.setVisible(!manageMode);
			openWith.setVisible(manageMode && canOpenWith(getCheckedDocuments()));
			final MenuItem quickShare = menu.findItem(R.id.menu_quickshare);
			if (quickShare != null) {
				quickShare.setVisible(manageMode && !isRecycleBin());
			}
			menu.findItem(R.id.menu_restore).setVisible(manageMode && isRecycleBinTop());
			menu.findItem(R.id.menu_lock).setVisible(manageMode && canLock(getCheckedDocuments()));
			delete.setVisible(manageMode && canDelete);
			if(null != rename) {
				rename.setVisible(manageMode && canRename && count == 1);
			}

            if (mType == TYPE_RECENT_OPEN) {
                delete.setVisible(true);
            }
			final MenuItem edit = menu.findItem(R.id.menu_edit);
			if (edit != null) {
				edit.setVisible(manageMode);
			}
			final MenuItem info = menu.findItem(R.id.menu_info);
			final MenuItem bookmark = menu.findItem(R.id.menu_bookmark);

			final MenuItem copy = menu.findItem(R.id.menu_copy);
			final MenuItem cut = menu.findItem(R.id.menu_cut);
			final MenuItem compress = menu.findItem(R.id.menu_compress);
			copy.setVisible(editMode);
			cut.setVisible(editMode && (!copiesAcross() || allDeletable(getCheckedDocuments())));
			compress.setVisible(editMode && !copiesAcross() && !isOperationSupported);

			info.setVisible(count == 1);
			final MenuItem unbookmark = menu.findItem(R.id.menu_unbookmark);
			final ArrayList<DocumentInfo> checked = getCheckedDocuments();
			final boolean bookmarked = count == 1 && !checked.isEmpty() && isBookmarkedDoc(checked.get(0));
			bookmark.setVisible(count == 1 && !isRecycleBin() && !bookmarked);
			if (unbookmark != null) {
				unbookmark.setVisible(count == 1 && !isRecycleBin() && bookmarked);
			}
			updateStarItems(menu, getCheckedDocuments());

			if (null != root && root.isApp()) {
				for (int i = 0; i < menu.size(); i++) {
					menu.getItem(i).setVisible(false);
				}
				menu.findItem(R.id.menu_select_all).setVisible(true);
				menu.findItem(R.id.menu_save_apk).setVisible(true);
				menu.findItem(R.id.menu_uninstall).setVisible(root.isUserApp());
			}
			return true;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			return handleMenuAction(item);
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
            selectAll = true;
			final Context context = getActivity();
			// The bar's buttons go with it, and focus would fall to the first thing on screen, the
			// sidebar; it goes back to the files instead, where the selection was
			final View focus = null != context ? getActivity().getCurrentFocus() : null;
			if (null != focus && !isInSidebar(focus) && !isInList(focus)) {
				mRefocusPosition = Math.max(mLastCheckedPosition, 0);
				focusRow();
			}
			if(null != context){
				final BaseActivity activity = (BaseActivity) context;
				activity.setActionMode(false);
                activity.setUpStatusBar();
			}
		}

		@Override
		public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
			if (checked) {
				mLastCheckedPosition = position;
				// Directories and footer items cannot be checked
				boolean valid = false;

				final Cursor cursor = mAdapter.getItem(position);
				if (cursor != null) {
					final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
					final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
					// if (!Document.MIME_TYPE_DIR.equals(docMimeType)) {
					valid = isDocumentEnabled(docMimeType, docFlags);
					// }
				}

				if (!valid) {
					mAdapter.setSelected(position, false);
				}
			}

			int count = mAdapter.getCheckedItemCount();
			mode.setTitle(getResources().getString(R.string.mode_selected_count, count));
			// Open with is for a single file, so the bar changes with the count
			mode.invalidate();
		}
	};

	private RecyclerView.RecyclerListener mRecycleListener = new RecyclerView.RecyclerListener() {

		@Override
		public void onViewRecycled(RecyclerView.ViewHolder holder) {
			cancelThumbnailTask(holder.itemView);
			cancelFolderSizeTask(holder.itemView);
		}
	};

	private void cancelThumbnailTask(View view) {
		final ImageView iconThumb = (ImageView) view.findViewById(R.id.icon_thumb);
		if (iconThumb != null) {
			mIconHelper.stopLoading(iconThumb);
		}
	}

	private void cancelFolderSizeTask(View view) {
		final TextView size = (TextView) view.findViewById(R.id.size);
		if (size != null) {
			final FolderSizeAsyncTask oldTask = (FolderSizeAsyncTask) size.getTag();
			if (oldTask != null) {
				oldTask.preempt();
				size.setTag(null);
			}
		}
	}

	private void onQuickShareDocuments(ArrayList<DocumentInfo> docs) {
		final ArrayList<Uri> uris = new ArrayList<>();
		for (DocumentInfo doc : docs) {
			if (!Utils.isDir(doc.mimeType)) {
				uris.add(doc.derivedUri);
			}
		}
		if (uris.isEmpty()) {
			Utils.showError(getActivity(), R.string.quickshare_send_no_files);
			return;
		}
		QuickShareSendActivity.start(getActivity(), uris);
	}

	/**
	 * Whether some app can open this one file. Android TV registers a stub for apps it lacks,
	 * which only says "not supported", so it doesn't count.
	 */
	private boolean canOpenWith(ArrayList<DocumentInfo> docs) {
		if (docs.size() != 1 || Utils.isDir(docs.get(0).mimeType)) {
			return false;
		}
		for (ResolveInfo info : getActivity().getPackageManager().queryIntentActivities(viewIntent(docs.get(0)), 0)) {
			if (!STUB_PACKAGE.equals(info.activityInfo.packageName)) {
				return true;
			}
		}
		return false;
	}

	/** Offers every app that can open or play the file, even when one is the default */
	private void onOpenWith(DocumentInfo doc) {
		final Intent intent = viewIntent(doc);
		Bundle params = new Bundle();
		params.putString(FILE_TYPE, IconUtils.getTypeNameFromMimeType(doc.mimeType));
		AnalyticsManager.logEvent("open_with", params);
		startActivity(Intent.createChooser(intent, getActivity().getText(R.string.open_with)));
	}

	private static Intent viewIntent(DocumentInfo doc) {
		final Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.setDataAndType(doc.derivedUri, doc.mimeType);
		return intent;
	}

	private boolean onDeleteDocuments(ArrayList<DocumentInfo> docs) {
		final Context context = getActivity();
		final ContentResolver resolver = context.getContentResolver();

		boolean hadTrouble = false;
		for (DocumentInfo doc : docs) {
			if (!doc.isDeleteSupported()) {
				Log.w(TAG, "Skipping " + doc);
				hadTrouble = true;
				continue;
			}

			try {
                hadTrouble = ! DocumentsContract.deleteDocument(resolver, doc.derivedUri);
			} catch (Exception e) {
				Log.w(TAG, "Failed to delete " + doc);
				CrashReportingManager.logException(e);
				hadTrouble = true;
			}
		}

		return hadTrouble;
	}

	/** Puts deleted items back where they were; true when some of them couldn't be */
	private boolean onRestoreDocuments(ArrayList<DocumentInfo> docs) {
		boolean hadTrouble = false;
		for (DocumentInfo doc : docs) {
			try {
				hadTrouble |= !ExternalStorageProvider.callRecycleBin(getActivity(),
						ExternalStorageProvider.METHOD_RESTORE, doc.documentId);
			} catch (Exception e) {
				Log.w(TAG, "Failed to restore " + doc);
				CrashReportingManager.logException(e);
				hadTrouble = true;
			}
		}
		return hadTrouble;
	}

	private boolean isRecycleBin() {
		return null != root && root.isRecycleBin();
	}

	/** Whether these local files can be moved into the locked folder */
	private boolean canLock(ArrayList<DocumentInfo> docs) {
		if (!AppSettings.isLockedFolderEnabled(getActivity()) || isRecycleBin()
				|| (null != root && root.isLockedFolder()) || docs.isEmpty()) {
			return false;
		}
		for (DocumentInfo doc : docs) {
			if (!ExternalStorageProvider.AUTHORITY.equals(doc.authority) || !doc.isMoveSupported()) {
				return false;
			}
		}
		return true;
	}

	/** Offers Star when some of these local files aren't starred yet, otherwise Unstar */
	private void updateStarItems(Menu menu, ArrayList<DocumentInfo> docs) {
		final MenuItem star = menu.findItem(R.id.menu_star);
		final MenuItem unstar = menu.findItem(R.id.menu_unstar);
		if (null == star || null == unstar) {
			return;
		}
		final boolean canStar = canStar(docs);
		final boolean allStarred = canStar && allStarred(docs);
		star.setVisible(canStar && !allStarred);
		unstar.setVisible(allStarred);
	}

	private boolean canStar(ArrayList<DocumentInfo> docs) {
		if (!AppSettings.isStarredEnabled(getActivity()) || isRecycleBin()
				|| (null != root && root.isLockedFolder()) || docs.isEmpty()) {
			return false;
		}
		for (DocumentInfo doc : docs) {
			if (!ExternalStorageProvider.AUTHORITY.equals(doc.authority) || TextUtils.isEmpty(doc.path)) {
				return false;
			}
		}
		return true;
	}

	private boolean allStarred(ArrayList<DocumentInfo> docs) {
		if (null != root && root.isStarred()) {
			return true;
		}
		final ContentResolver resolver = getActivity().getContentResolver();
		for (DocumentInfo doc : docs) {
			Cursor cursor = null;
			try {
				cursor = resolver.query(ExplorerProvider.buildStarred(), null,
						ExplorerProvider.StarredColumns.PATH + "=?", new String[]{doc.path}, null);
				if (null == cursor || cursor.getCount() == 0) {
					return false;
				}
			} finally {
				IoUtils.closeQuietly(cursor);
			}
		}
		return true;
	}

	private void starDocuments(ArrayList<DocumentInfo> docs, boolean star) {
		final ContentResolver resolver = getActivity().getContentResolver();
		for (DocumentInfo doc : docs) {
			if (star) {
				final ContentValues values = new ContentValues();
				values.put(ExplorerProvider.StarredColumns.PATH, doc.path);
				resolver.insert(ExplorerProvider.buildStarred(), values);
			} else {
				resolver.delete(ExplorerProvider.buildStarred(),
						ExplorerProvider.StarredColumns.PATH + "=?", new String[]{doc.path});
			}
		}
		Utils.showSnackBar(getActivity(), getString(star ? R.string.starred : R.string.unstarred));
		loadMarkedPaths();
		mAdapter.notifyDataSetChanged();
	}

	/** Reads which paths are starred or bookmarked, for the badges on the rows */
	private void loadMarkedPaths() {
		mStarredPaths.clear();
		mBookmarkedPaths.clear();
		readPaths(ExplorerProvider.buildStarred(), ExplorerProvider.StarredColumns.PATH, mStarredPaths);
		readPaths(ExplorerProvider.buildBookmark(), ExplorerProvider.BookmarkColumns.PATH, mBookmarkedPaths);
	}

	private void readPaths(Uri uri, String column, HashSet<String> paths) {
		Cursor cursor = null;
		try {
			cursor = getActivity().getContentResolver().query(uri, new String[]{column}, null, null, null);
			while (null != cursor && cursor.moveToNext()) {
				paths.add(cursor.getString(0));
			}
		} catch (Exception e) {
			Log.w(TAG, "Couldn't read " + uri, e);
		} finally {
			IoUtils.closeQuietly(cursor);
		}
	}

	/** Moves the files into the locked folder; true when some of them couldn't be */
	private boolean onLockDocuments(ArrayList<DocumentInfo> docs) {
		final ContentResolver resolver = getActivity().getContentResolver();
		final Uri locked = DocumentsContract.buildDocumentUri(ExternalStorageProvider.AUTHORITY,
				ExternalStorageProvider.ROOT_ID_LOCKED + ':');
		boolean hadTrouble = false;
		for (DocumentInfo doc : docs) {
			try {
				hadTrouble |= null == DocumentsContract.moveDocument(resolver, doc.derivedUri, null, locked);
			} catch (Exception e) {
				Log.w(TAG, "Failed to lock " + doc);
				CrashReportingManager.logException(e);
				hadTrouble = true;
			}
		}
		return hadTrouble;
	}

	/** The top of the recycle bin, where the deleted items themselves are listed */
	private boolean isRecycleBinTop() {
		return isRecycleBin() && null != doc && TextUtils.equals(root.documentId, doc.documentId);
	}

	/** Whether deleting these moves them to the recycle bin rather than deleting them for good */
	private boolean goToRecycleBin(ArrayList<DocumentInfo> docs) {
		// The locked folder isn't on a drive with a bin, so deleting from it is for good
		if (isRecycleBin() || (null != root && root.isLockedFolder())
				|| !AppSettings.isRecycleBinEnabled(getActivity())) {
			return false;
		}
		for (DocumentInfo doc : docs) {
			if (!ExternalStorageProvider.AUTHORITY.equals(doc.authority)
					&& !MediaDocumentsProvider.AUTHORITY.equals(doc.authority)
					&& !NonMediaDocumentsProvider.AUTHORITY.equals(doc.authority)) {
				return false;
			}
		}
		return true;
	}

	private class OperationTask extends AsyncTask<Void, Void, Boolean> {

		private Dialog progressDialog;
		private ArrayList<DocumentInfo> docs;
		private int id;
		private boolean toRecycleBin;

		public OperationTask(ArrayList<DocumentInfo> docs, int id) {
			this.docs = docs;
			this.id = id;
			toRecycleBin = id == R.id.menu_delete && goToRecycleBin(docs);
			DialogBuilder builder = new DialogBuilder(getActivity());
			builder.setCancelable(false);
			builder.setIndeterminate(true);

			if (id == R.id.menu_delete) {
				builder.setMessage(R.string.progress_deleting);
			} else if (id == R.id.menu_uncompress) {
				builder.setMessage(R.string.progress_uncompressing);
			} else if (id == R.id.menu_compress) {
				builder.setMessage(R.string.progress_compressing);
			} else if (id == R.id.menu_restore) {
				builder.setMessage(R.string.progress_restoring);
			} else if (id == R.id.menu_lock) {
				builder.setMessage(R.string.progress_moving);
			}
			progressDialog = builder.create();
		}

		@Override
		protected void onPreExecute() {
			progressDialog.show();
			super.onPreExecute();
		}

		@Override
		protected Boolean doInBackground(Void... params) {
			Bundle params2 = new Bundle();
			boolean result = false;
			if (id == R.id.menu_delete) {
				result = onDeleteDocuments(docs);
			} else if (id == R.id.menu_restore) {
				result = onRestoreDocuments(docs);
			} else if (id == R.id.menu_lock) {
				result = onLockDocuments(docs);
			} else if (id == R.id.menu_uncompress) {
                result = onUncompressDocuments(docs);
				params2 = new Bundle();
				AnalyticsManager.logEvent("uncompress", params2);
			} else if (id == R.id.menu_compress) {
                result = onCompressDocuments(doc, docs);
				params2 = new Bundle();
				params2.putInt(FILE_COUNT, docs.size());
				AnalyticsManager.logEvent("compress", params2);
			}

			return result;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);
            if(!Utils.isActivityAlive(getActivity())) {
                return;
            }
			progressDialog.dismiss();
			if (result) {
				if (id == R.id.menu_delete) {
                    if(!((BaseActivity) getActivity()).isSAFIssue(docs.get(0).documentId)) {
						Utils.showError(getActivity(), R.string.toast_failed_delete);
                    }
				} else if (id == R.id.menu_compress) {
                    if(!((BaseActivity) getActivity()).isSAFIssue(docs.get(0).documentId)) {
						Utils.showError(getActivity(), R.string.compress_error);
                    }
				} else if (id == R.id.menu_uncompress) {
                    if(!((BaseActivity) getActivity()).isSAFIssue(doc.documentId)) {
                        Utils.showError(getActivity(), R.string.uncompress_error);
                    }
				} else if (id == R.id.menu_restore) {
					Utils.showError(getActivity(), R.string.toast_failed_restore);
				} else if (id == R.id.menu_lock) {
					Utils.showError(getActivity(), R.string.toast_failed_lock);
				}
			} else if (toRecycleBin) {
				Utils.showSnackBar(getActivity(), getString(R.string.moved_to_bin));
			} else if (id == R.id.menu_lock) {
				Utils.showSnackBar(getActivity(), getString(R.string.moved_to_locked_folder));
			}

			if (mType == TYPE_RECENT_OPEN) {
				onUserSortOrderChanged();
			}
		}
	}

	private void deleteFiles(final ArrayList<DocumentInfo> docs, final int id, String title) {
		DialogBuilder builder = new DialogBuilder(getActivity());
		builder.setMessage(title).setCancelable(false).setPositiveButton(R.string.menu_delete, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int did) {
				new OperationTask(docs, id).execute();
			}
		}).setNegativeButton(android.R.string.cancel, null);
		builder.showDialog();
	}

	public State getDisplayState(Fragment fragment) {
		return ((BaseActivity) fragment.getActivity()).getDisplayState();
	}

    private static boolean isCreateSupported(Fragment fragment) {
        return ((BaseActivity) fragment.getActivity()).isCreateSupported();
    }

    public boolean onCompressDocuments(DocumentInfo parent, ArrayList<DocumentInfo> docs) {
        final Context context = getActivity();
        final ContentResolver resolver = context.getContentResolver();

        boolean hadTrouble = false;
        if (!parent.isArchiveSupported()) {
            Log.w(TAG, "Skipping " + doc);
            hadTrouble = true;
        }

        try {
            ArrayList<String> documentIds = new ArrayList<>();
            for (DocumentInfo doc : docs){
                documentIds.add(DocumentsContract.getDocumentId(doc.derivedUri));
            }
            hadTrouble = ! DocumentsContract.compressDocument(resolver, doc.derivedUri, documentIds);
        } catch (Exception e) {
            Log.w(TAG, "Failed to Compress " + doc);
			CrashReportingManager.logException(e);
            hadTrouble = true;
        }

        return hadTrouble;
    }

    public boolean onUncompressDocuments(ArrayList<DocumentInfo> docs) {
        final Context context = getActivity();
        final ContentResolver resolver = context.getContentResolver();

        boolean hadTrouble = false;
        for (DocumentInfo doc : docs) {
            if (!doc.isArchiveSupported()) {
                Log.w(TAG, "Skipping " + doc);
                hadTrouble = true;
                continue;
            }

            try {
                hadTrouble = ! DocumentsContract.uncompressDocument(resolver, doc.derivedUri);
            } catch (Exception e) {
                Log.w(TAG, "Failed to Uncompress " + doc);
				CrashReportingManager.logException(e);
                hadTrouble = true;
            }
        }

        return hadTrouble;
    }

	private void setEmptyState() {
		// An empty list would swallow focus that should reach the APKs tabs
		if (mAppTabs.getVisibility() == View.VISIBLE || mVideoTabs.getVisibility() == View.VISIBLE) {
			getListView().setFocusable(!mAdapter.isEmpty());
		}
		if (!mAdapter.isEmpty()) {
			mEmptyGroup.setVisibility(View.GONE);
			return;
		}
		mEmptyGroup.setVisibility(View.VISIBLE);
		final String error = mAdapter.getError();
		int icon = R.drawable.ic_folder_empty;
		int title = null != root && (root.isApp() || root.isApk()) ? R.string.empty_nothing : R.string.empty;
		String message = null;
		if (error == null && null != root && root.isStarred()) {
			icon = R.drawable.ic_root_starred;
			title = R.string.empty_starred;
			message = getString(R.string.empty_starred_hint);
		} else if (error == null && isRecycleBin()) {
			icon = R.drawable.ic_root_recycle_bin;
			title = R.string.empty_bin;
			message = getString(R.string.empty_bin_hint);
		} else if (error != null && error.equals(getString(R.string.server_connect_failed))) {
			icon = R.drawable.ic_dialog_alert;
			title = R.string.server_connect_failed;
		} else if (error != null && error.equals(getString(R.string.server_login_refused))) {
			icon = R.drawable.ic_dialog_alert;
			title = R.string.server_login_refused;
			message = getString(R.string.server_login_refused_hint);
		} else if (error != null) {
			final boolean restricted = error.equals(getString(R.string.folder_restricted));
			icon = restricted ? R.drawable.ic_settings_lock : R.drawable.ic_dialog_alert;
			title = R.string.folder_open_failed;
			message = restricted ? error : getString(R.string.folder_open_failed_reason);
		}
		mEmptyIcon.setImageResource(icon);
		mEmptyView.setText(title);
		mEmptyMessage.setText(message);
		mEmptyMessage.setVisibility(message != null ? View.VISIBLE : View.GONE);
	}

	public boolean isDocumentEnabled(String docMimeType, int docFlags) {
		final State state = getDisplayState(DirectoryFragment.this);

		if (Document.MIME_TYPE_HIDDEN.equals(docMimeType)) {
			return false;
		}
		// Directories are always enabled
		if (Utils.isDir(docMimeType)) {
			return true;
		}

		// Read-only files are disabled when creating
		if (state.action == ACTION_CREATE && (docFlags & Document.FLAG_SUPPORTS_WRITE) == 0) {
			return false;
		}

		return MimePredicate.mimeMatches(state.acceptMimes, docMimeType);
	}

	private void showPopupMenu(View view, final int position) {
		PopupMenu popup = new PopupMenu(getActivity(), view);

		popup.getMenuInflater().inflate(R.menu.popup_directory, popup.getMenu());
		// TV reaches multi-select through this menu, since long press opens it
		popup.getMenu().findItem(R.id.menu_select_item).setVisible(true);
		popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem menuItem) {
				return onPopupMenuItemClick(menuItem, position);
			}
		});
		
		final State state = getDisplayState(DirectoryFragment.this);
		final MenuItem openWith = popup.getMenu().findItem(R.id.menu_open_with);
		final MenuItem delete = popup.getMenu().findItem(R.id.menu_delete);
		final MenuItem rename = popup.getMenu().findItem(R.id.menu_rename);
		final MenuItem copy = popup.getMenu().findItem(R.id.menu_copy);
		final MenuItem cut = popup.getMenu().findItem(R.id.menu_cut);
        final MenuItem compress = popup.getMenu().findItem(R.id.menu_compress);
        final MenuItem uncompress = popup.getMenu().findItem(R.id.menu_uncompress);
        final MenuItem bookmark = popup.getMenu().findItem(R.id.menu_bookmark);
        final MenuItem unbookmark = popup.getMenu().findItem(R.id.menu_unbookmark);

        final Cursor cursor = mAdapter.getItem(position);
        final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
		final boolean manageMode = state.action == ACTION_BROWSE;
		if(null != doc){
			final boolean isCompressed = doc != null && MimePredicate.mimeMatches(MimePredicate.COMPRESSED_MIMES, doc.mimeType);
			if(null != compress)
				compress.setVisible(manageMode && doc.isArchiveSupported() && !isCompressed && !isOperationSupported);
			if(null != uncompress)
				uncompress.setVisible(manageMode && doc.isArchiveSupported() && isCompressed && !isOperationSupported);
			final boolean canBookmark = manageMode && doc.isBookmarkSupported() && Utils.isDir(doc.mimeType) && !isOperationSupported;
			final boolean bookmarked = isBookmarkedDoc(doc);
			if(null != bookmark) {
				bookmark.setVisible(canBookmark && !bookmarked);
			}
			if(null != unbookmark) {
				unbookmark.setVisible(canBookmark && bookmarked);
			}
			final ArrayList<DocumentInfo> single = new ArrayList<>();
			single.add(doc);
			openWith.setVisible(manageMode && canOpenWith(single));
			final MenuItem quickShare = popup.getMenu().findItem(R.id.menu_quickshare);
			if (quickShare != null) {
				quickShare.setVisible(manageMode && !Utils.isDir(doc.mimeType) && !isRecycleBin());
			}
			popup.getMenu().findItem(R.id.menu_restore).setVisible(manageMode && isRecycleBinTop());
			popup.getMenu().findItem(R.id.menu_lock).setVisible(manageMode && canLock(single));
			if (manageMode) {
				updateStarItems(popup.getMenu(), single);
			}
			delete.setVisible(manageMode && doc.isDeleteSupported());
			rename.setVisible(manageMode && doc.isRenameSupported());
			copy.setVisible(manageMode && (doc.isCopySupported() || copiesAcross()));
			cut.setVisible(manageMode && (doc.isMoveSupported()
					|| (copiesAcross() && doc.isDeleteSupported())));
		}

		if (null != root && root.isApp()) {
			final Menu menu = popup.getMenu();
			for (int i = 0; i < menu.size(); i++) {
				menu.getItem(i).setVisible(false);
			}
			menu.findItem(R.id.menu_select_item).setVisible(true);
			menu.findItem(R.id.menu_save_apk).setVisible(true);
			menu.findItem(R.id.menu_uninstall).setVisible(root.isUserApp());
			menu.findItem(R.id.menu_app_info).setVisible(true);
		}

		// Beside the item, so every option fits on screen without scrolling
		SideMenuPopup.show(view, popup.getMenu(), new SideMenuPopup.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem menuItem) {
				return onPopupMenuItemClick(menuItem, position);
			}
		});
	}

	@Override
	public boolean onMenuItemClick(MenuItem item) {
		if(handleMenuAction(item)){
			mMultiChoiceHelper.clearChoices();
			return true;
		}
		return false;
	}

	public boolean onPopupMenuItemClick(MenuItem item, int position) {
		if (item.getItemId() == R.id.menu_select_item) {
			mMultiChoiceHelper.startSupportActionModeIfNeeded();
			mMultiChoiceHelper.setItemChecked(position, true, true);
			return true;
		}
		mLastCheckedPosition = position;
		final ArrayList<DocumentInfo> docs = new ArrayList<>();
		final Cursor cursor = mAdapter.getItem(position);
		final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
		docs.add(doc);

		return handleMenuAction(item, docs);
	}

	/** The system screen takes focus away; coming back, the list reloads and focus returns to the row */
	private void leaveForApps() {
		mReloadAppsOnResume = true;
		mRefocusPosition = Math.max(mLastCheckedPosition, 0);
	}

	/** Copies the apps' APKs to the AppBackup folder, off the main thread since APKs can be large */
	private void saveApks(final ArrayList<DocumentInfo> docs) {
		final Activity activity = getActivity();
		final ContentResolver resolver = activity.getContentResolver();
		new Thread(new Runnable() {
			@Override
			public void run() {
				boolean saved = true;
				for (DocumentInfo app : docs) {
					try {
						saved &= null != DocumentsContract.copyDocument(resolver, app.derivedUri, app.derivedUri);
					} catch (Exception e) {
						saved = false;
					}
				}
				final boolean ok = saved;
				activity.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						Utils.showSnackBar(activity, activity.getString(ok ? R.string.apk_saved : R.string.apk_save_failed));
					}
				});
			}
		}).start();
	}

	/** Unselects everything; false when nothing was selected */
	public boolean clearSelection() {
		if (mMultiChoiceHelper == null || mMultiChoiceHelper.getCheckedItemCount() == 0) {
			return false;
		}
		mMultiChoiceHelper.clearChoices();
		return true;
	}

	private ArrayList<DocumentInfo> getCheckedDocuments() {
		final SparseBooleanArray checked = mAdapter.getCheckedItemPositions();
		final ArrayList<DocumentInfo> docs = new ArrayList<>();
		final int size = checked.size();
		for (int i = 0; i < size; i++) {
			if (checked.valueAt(i)) {
				final Cursor cursor = mAdapter.getItem(checked.keyAt(i));
				if(null != cursor) {
					final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
					docs.add(doc);
				}
			}
		}
		return docs;
	}

	public boolean handleMenuAction(MenuItem item){
		final ArrayList<DocumentInfo> docs = getCheckedDocuments();
		if(docs.isEmpty()){
			return false;
		}
		return handleMenuAction(item, docs);
	}

	public boolean handleMenuAction(MenuItem item, ArrayList<DocumentInfo> docs) {
		final int id = item.getItemId();
		Bundle params = new Bundle();
		if (id == R.id.menu_open_with) {
			onOpenWith(docs.get(0));
			return true;
		} else if (id == R.id.menu_quickshare) {
			onQuickShareDocuments(docs);
			return true;
		} else if (id == R.id.menu_copy) {
			moveDocument(docs, false);
			return true;
		} else if (id == R.id.menu_cut) {
			moveDocument(docs, true);
			return true;
		} else if (id == R.id.menu_delete) {
			deleteDocument(docs, id);
			return true;
		} else if (id == R.id.menu_uncompress || id == R.id.menu_compress || id == R.id.menu_restore
				|| id == R.id.menu_lock) {
			new OperationTask(docs, id).execute();
			return true;
		} else if (id == R.id.menu_open) {
			BaseActivity.get(DirectoryFragment.this).onDocumentsPicked(docs);
			return true;
		} else if (id == R.id.menu_info) {
			infoDocument(docs.get(0));
			return true;
		} else if (id == R.id.menu_rename) {
			renameDocument(docs.get(0));
			return true;
		} else if (id == R.id.menu_bookmark) {
			bookmarkDocument(docs.get(0));
			return true;
		} else if (id == R.id.menu_unbookmark) {
			unbookmarkDocument(docs.get(0));
			return true;
		} else if (id == R.id.menu_star || id == R.id.menu_unstar) {
			if (id == R.id.menu_unstar && null != root && root.isStarred()) {
				// The row leaves Starred, and focus would fall to the sidebar
				mRefocusPosition = Math.max(mLastCheckedPosition, 0);
			}
			starDocuments(docs, id == R.id.menu_star);
			clearSelection();
			return true;
		} else if (id == R.id.menu_save_apk) {
			saveApks(docs);
			clearSelection();
			return true;
		} else if (id == R.id.menu_uninstall) {
			mUninstallQueue.clear();
			for (DocumentInfo app : docs) {
				mUninstallQueue.add(AppsProvider.getPackageForDocId(app.documentId));
			}
			leaveForApps();
			uninstallNext();
			return true;
		} else if (id == R.id.menu_app_info) {
			final String packageName = AppsProvider.getPackageForDocId(docs.get(0).documentId);
			leaveForApps();
			try {
				startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
						Uri.parse("package:" + packageName)));
			} catch (ActivityNotFoundException e) {
				Utils.showSnackBar(getActivity(), getString(R.string.unable_to_open_app));
			}
			return true;
		} else if (id == R.id.menu_select_all) {
			int count = mAdapter.getItemCount();
			for (int i = 0; i < count; i++) {
				mAdapter.setSelected(i, selectAll);
			}
			selectAll = !selectAll;
			params.putInt(FILE_COUNT, count);
			AnalyticsManager.logEvent("select", params);
			return false;
		}
		return false;
	}

	private boolean isBookmarkedDoc(DocumentInfo doc) {
		return !TextUtils.isEmpty(doc.path) && mBookmarkedPaths.contains(doc.path);
	}

	/** Deletes every bookmark row for the path, since duplicates can exist */
	private void unbookmarkDocument(DocumentInfo doc) {
		final int rows = getActivity().getContentResolver().delete(ExplorerProvider.buildBookmark(),
				ExplorerProvider.BookmarkColumns.PATH + " = ? ", new String[]{doc.path});
		if (rows > 0) {
			Utils.showSnackBar(getActivity(), getString(R.string.bookmark_removed));
			RootsCache.updateRoots(getActivity(), ExternalStorageProvider.AUTHORITY);
			loadMarkedPaths();
			mAdapter.notifyDataSetChanged();
		}
	}

	private void bookmarkDocument(DocumentInfo doc) {
		DocumentInfo document = doc;
		ContentValues contentValues = new ContentValues();
		contentValues.put(ExplorerProvider.BookmarkColumns.PATH, document.path);
		contentValues.put(ExplorerProvider.BookmarkColumns.TITLE, document.displayName);
		contentValues.put(ExplorerProvider.BookmarkColumns.ROOT_ID, document.displayName);
		Uri uri = getActivity().getContentResolver().insert(ExplorerProvider.buildBookmark(), contentValues);
		if(null != uri) {
			Utils.showSnackBar(getActivity(), getString(R.string.bookmark_added));
			RootsCache.updateRoots(getActivity(), ExternalStorageProvider.AUTHORITY);
			loadMarkedPaths();
			mAdapter.notifyDataSetChanged();
		}
		Bundle params = new Bundle();
		AnalyticsManager.logEvent("bookmarked", root, params);
	}

	private void deleteDocument(ArrayList<DocumentInfo> docs, int type) {
		Bundle params = new Bundle();
		params.putInt(FILE_COUNT, docs.size());
		// Only deleting for good asks first; the bin can give files back
		if (goToRecycleBin(docs)) {
			new OperationTask(docs, type).execute();
		} else {
			deleteFiles(docs, type, getResources().getQuantityString(
					R.plurals.delete_permanently, docs.size(), docs.size()));
		}
		AnalyticsManager.logEvent("delete", params);
	}

	/** Network storage can't copy by itself, but its files can be copied or moved elsewhere */
	private boolean copiesAcross() {
		return root != null && root.isNetworkStorage() && !root.isServer();
	}

	private static boolean allDeletable(ArrayList<DocumentInfo> docs) {
		for (DocumentInfo doc : docs) {
			if (!doc.isDeleteSupported()) {
				return false;
			}
		}
		return true;
	}

	private void moveDocument(ArrayList<DocumentInfo> docs, boolean move) {
		MoveFragment.show(getFragmentManager(), docs, move);
		Bundle params = new Bundle();
		params.putBoolean(FILE_MOVE, move);
		params.putInt(FILE_COUNT, docs.size());
		AnalyticsManager.logEvent("move_"+move, params);
	}

	private void renameDocument(DocumentInfo doc){
		RenameFragment.show(((BaseActivity) getActivity()).getSupportFragmentManager(), doc);
		Bundle params = new Bundle();
		params.putString(FILE_TYPE, IconUtils.getTypeNameFromMimeType(doc.mimeType));
		AnalyticsManager.logEvent("rename", params);
	}

	private void infoDocument(DocumentInfo doc) {
		final BaseActivity activity = (BaseActivity) getActivity();
		DetailFragment.showAsDialog(activity.getSupportFragmentManager(), doc);
		Bundle params = new Bundle();
		String type = IconUtils.getTypeNameFromMimeType(doc.mimeType);
		params.putString(FILE_TYPE, type);
		AnalyticsManager.logEvent("details", params);
	}


	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putParcelable(KEY_ADAPTER, mMultiChoiceHelper.onSaveInstanceState());
	}

	private synchronized ContentProviderClient getExternalStorageClient() {
		if (mExternalStorageClient == null) {
			mExternalStorageClient = getActivity().
					getContentResolver().acquireContentProviderClient(ExternalStorageProvider.AUTHORITY);
		}
		return mExternalStorageClient;
	}

	private final class AdapterEnvironment implements DocumentsAdapter.Environment {

		@Override
		public Context getContext() {
			return mActivity;
		}

		@Override
		public State getDisplayState() {
			return DirectoryFragment.this.getDisplayState(DirectoryFragment.this);
		}

		@Override
		public RootInfo getRoot() {
			return root;
		}

		@Override
		public DocumentInfo getDocumentInfo() {
			return doc;
		}

		@Override
		public int getType() {
			return mType;
		}

		@Override
		public boolean isDocumentEnabled(String mimeType, int flags) {
			return DirectoryFragment.this.isDocumentEnabled(mimeType, flags);
		}

		@Override
		public boolean hideGridTiles() {
			return mHideGridTitles;
		}

		@Override
		public void setEmptyState() {
			DirectoryFragment.this.setEmptyState();
		}

		@Override
		public MultiChoiceHelper getMultiChoiceHelper() {
			return mMultiChoiceHelper;
		}

		@Override
		public IconHelper getIconHelper() {
			return mIconHelper;
		}

		@Override
		public boolean isStarred(String path) {
			return mStarredPaths.contains(path);
		}

		@Override
		public boolean isBookmarked(String path) {
			return mBookmarkedPaths.contains(path);
		}
	}

}