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

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.content.Intent;
import android.content.Loader;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

import dev.dworks.apps.anexplorer.BaseActivity;
import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.adapter.CommonInfo;
import dev.dworks.apps.anexplorer.adapter.HomeAdapter;
import dev.dworks.apps.anexplorer.common.RecyclerFragment;
import dev.dworks.apps.anexplorer.cursor.LimitCursorWrapper;
import dev.dworks.apps.anexplorer.loader.RecentLoader;
import dev.dworks.apps.anexplorer.misc.AnalyticsManager;
import dev.dworks.apps.anexplorer.misc.IconHelper;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DirectoryResult;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.setting.AppSettings;

import static dev.dworks.apps.anexplorer.BaseActivity.State.MODE_GRID;
import static dev.dworks.apps.anexplorer.adapter.HomeAdapter.TYPE_HEADER;
import static dev.dworks.apps.anexplorer.adapter.HomeAdapter.TYPE_MAIN;
import static dev.dworks.apps.anexplorer.adapter.HomeAdapter.TYPE_RECENT;
import static dev.dworks.apps.anexplorer.adapter.HomeAdapter.TYPE_SHORTCUT;
import static dev.dworks.apps.anexplorer.misc.AnalyticsManager.FILE_TYPE;

/**
 * Display home.
 */
public class HomeFragment extends RecyclerFragment implements HomeAdapter.OnItemClickListener {
    public static final String TAG = "HomeFragment";
    private static final int MAX_RECENT_COUNT = 20;

    private final int mLoaderId = 42;
    private RootsCache roots;
    private LoaderManager.LoaderCallbacks<DirectoryResult> mCallbacks;
    private RootInfo mHomeRoot;
    private BaseActivity mActivity;
    private IconHelper mIconHelper;
    private ArrayList<CommonInfo> mainData;
    private ArrayList<CommonInfo> shortcutsData;
    private HomeAdapter mAdapter;
    private int totalSpanSize;
    private int shortcutSpanSize;

    // A drive plugged in or out changes the storage cards while Home is showing
    private final ContentObserver mRootsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            if (isResumed()) {
                showData();
            }
        }
    };

    // New photos, videos or music (e.g. from Quick Share) change the recent media while Home is
    // showing; the media store sends a burst of changes per file, so reload once they settle
    private final Handler mRecentsHandler = new Handler(Looper.getMainLooper());
    private final Runnable mReloadRecents = new Runnable() {
        @Override
        public void run() {
            if (isResumed()) {
                getRecentsData();
            }
        }
    };
    private final ContentObserver mMediaObserver = new ContentObserver(mRecentsHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mRecentsHandler.removeCallbacks(mReloadRecents);
            mRecentsHandler.postDelayed(mReloadRecents, 1000);
        }
    };

    public static void show(FragmentManager fm) {
        final HomeFragment fragment = new HomeFragment();
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, fragment, TAG);
        ft.commitAllowingStateLoss();
    }

    public static HomeFragment get(FragmentManager fm) {
        return (HomeFragment) fm.findFragmentByTag(TAG);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        totalSpanSize = getResources().getInteger(R.integer.home_span);
        shortcutSpanSize = getResources().getInteger(R.integer.home_shortcut_span);
        mActivity = ((BaseActivity) getActivity());
        mIconHelper = new IconHelper(mActivity, MODE_GRID);
        if(null == mAdapter){
            mAdapter = new HomeAdapter(getActivity(), mIconHelper);
            mAdapter.setOnItemClickListener(this);
        }

        roots = DocumentsApplication.getRootsCache(getActivity());
        mHomeRoot = roots.getHomeRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().getContentResolver().registerContentObserver(
                RootsCache.sNotificationUri, false, mRootsObserver);
        getActivity().getContentResolver().registerContentObserver(
                MediaStore.Files.getContentUri("external"), true, mMediaObserver);
        showData();
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().getContentResolver().unregisterContentObserver(mRootsObserver);
        getActivity().getContentResolver().unregisterContentObserver(mMediaObserver);
        mRecentsHandler.removeCallbacks(mReloadRecents);
    }

    public void showData(){
        roots = DocumentsApplication.getRootsCache(getActivity());
        mIconHelper.setThumbnailsEnabled(mActivity.getDisplayState().showThumbnail);
        getMainData();
        getShortcutsData();
        getRecentsData();
        mAdapter.setData(mainData, shortcutsData);
    }

    private void getMainData(){
        mainData = new ArrayList<>();
        final RootInfo primaryRoot = roots.getPrimaryRoot();
        final RootInfo secondaryRoot = roots.getSecondaryRoot();
        final RootInfo usbRoot = roots.getUSBRoot();
        int type = TYPE_MAIN;
        if(null != primaryRoot){
            mainData.add(CommonInfo.from(primaryRoot, type));
        }
        if(null != secondaryRoot){
            mainData.add(CommonInfo.from(secondaryRoot, type));
        }
        if(null != usbRoot){
            mainData.add(CommonInfo.from(usbRoot, type));
        }
    }

    private void getShortcutsData(){
        shortcutsData = new ArrayList<>();
        for (RootInfo root: roots.getLibraryRoots()) {
            shortcutsData.add(CommonInfo.from(root, TYPE_SHORTCUT));
        }
        final RootInfo recycleBin = roots.getRecycleBinRoot();
        if (null != recycleBin) {
            shortcutsData.add(CommonInfo.from(recycleBin, TYPE_SHORTCUT));
        }
    }

    private void getRecentsData(){
        final BaseActivity.State state = getDisplayState(this);
        mCallbacks = new LoaderManager.LoaderCallbacks<DirectoryResult>() {

            @Override
            public Loader<DirectoryResult> onCreateLoader(int id, Bundle args) {
                return new RecentLoader(getActivity(), roots, state);
            }

            @Override
            public void onLoadFinished(Loader<DirectoryResult> loader, DirectoryResult result) {
                if (!isAdded())
                    return;
                if(null != result.cursor && result.cursor.getCount() != 0) {
                    mAdapter.setRecentData(new LimitCursorWrapper(result.cursor, MAX_RECENT_COUNT));
                }
            }

            @Override
            public void onLoaderReset(Loader<DirectoryResult> loader) {
                mAdapter.setRecentData(null);
            }
        };
        if(AppSettings.getDisplayRecentMedia()) {
            getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);
        }
    }

    public void reloadData(){
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                showData();
            }
        }, 500);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onItemClick(HomeAdapter.ViewHolder item, View view, int position) {
        switch (item.commonInfo.type) {
            case TYPE_MAIN:
            case TYPE_SHORTCUT:
                openRoot(item.commonInfo.rootInfo);
                break;
            case TYPE_RECENT:
                try {
                    final DocumentInfo documentInfo = ((HomeAdapter.GalleryViewHolder)item).getItem(position);
                    openDocument(documentInfo);
                } catch (Exception ignore) {}
                break;
        }
    }

    @Override
    public void onItemLongClick(HomeAdapter.ViewHolder item, View view, int position) {

    }

    @Override
    public void onItemViewClick(HomeAdapter.ViewHolder item, View view, int position) {
        if (view.getId() == R.id.recents){
            openRoot(roots.getRecentsRoot());
        } else if (view.getId() == R.id.action){
            Bundle params = new Bundle();
            Intent intent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
            if(Utils.isIntentAvailable(getActivity(), intent)) {
                getActivity().startActivity(intent);
            } else  {
                Utils.showSnackBar(getActivity(), "Coming Soon!");
            }
            AnalyticsManager.logEvent("storage_analyze", params);
        }

    }

    private static BaseActivity.State getDisplayState(Fragment fragment) {
        return ((BaseActivity) fragment.getActivity()).getDisplayState();
    }

    private void openRoot(RootInfo rootInfo){
        DocumentsActivity activity = ((DocumentsActivity)getActivity());
        activity.onRootPicked(rootInfo, mHomeRoot);
        AnalyticsManager.logEvent("open_shortcuts", rootInfo ,new Bundle());
    }

    private void openDocument(DocumentInfo doc) {
        ((BaseActivity) getActivity()).onDocumentPicked(doc);
        Bundle params = new Bundle();
        String type = IconUtils.getTypeNameFromMimeType(doc.mimeType);
        params.putString(FILE_TYPE, type);
        AnalyticsManager.logEvent("open_image_recent", params);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setListAdapter(mAdapter);
        showData();
        if (isResumed()) {
            setListShown(true);
        } else {
            setListShownNoAnimation(true);
        }

        ((GridLayoutManager)getListView().getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int spanSize = 1;
                switch (mAdapter.getItem(position).type) {
                    case TYPE_MAIN:
                        spanSize = totalSpanSize;
                        break;
                    case TYPE_SHORTCUT:
                        spanSize = shortcutSpanSize;
                        break;
                    case TYPE_RECENT:
                    case TYPE_HEADER:
                        spanSize = totalSpanSize;
                        break;
                }
                return spanSize;
            }
        });
        getListView().addItemDecoration(new ShortcutSpacingDecoration(
                getResources().getDimensionPixelSize(R.dimen.home_shortcut_spacing)));
    }

    /**
     * Spaces the shortcut cards evenly, with outer edges lining up with the full-width
     * cards above them (which carry the same margin themselves).
     */
    private class ShortcutSpacingDecoration extends RecyclerView.ItemDecoration {
        private final int mSpacing;

        ShortcutSpacingDecoration(int spacing) {
            mSpacing = spacing;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
            final int position = parent.getChildAdapterPosition(view);
            if (position == RecyclerView.NO_POSITION || mAdapter.getItem(position).type != TYPE_SHORTCUT) {
                return;
            }
            final GridLayoutManager.LayoutParams lp = (GridLayoutManager.LayoutParams) view.getLayoutParams();
            final int columns = totalSpanSize / shortcutSpanSize;
            final int column = lp.getSpanIndex() / shortcutSpanSize;
            outRect.left = mSpacing - column * mSpacing / columns;
            outRect.right = (column + 1) * mSpacing / columns;
            outRect.top = mSpacing / 2;
            outRect.bottom = mSpacing / 2;
        }
    }
}