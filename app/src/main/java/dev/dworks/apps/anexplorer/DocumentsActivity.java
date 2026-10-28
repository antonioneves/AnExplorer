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

package dev.dworks.apps.anexplorer;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.view.MenuInflater;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.view.ActionMode;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteFullException;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.core.content.ContextCompat;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.MenuItemCompat;
import androidx.core.view.ViewCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.ColorFilter;
import android.graphics.Canvas;
import android.animation.ValueAnimator;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.javiersantos.appupdater.AppUpdater;
import com.github.javiersantos.appupdater.enums.Display;
import com.github.javiersantos.appupdater.enums.UpdateFrom;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import dev.dworks.apps.anexplorer.archive.DocumentArchiveHelper;
import dev.dworks.apps.anexplorer.common.DialogBuilder;
import dev.dworks.apps.anexplorer.common.RootsCommonFragment;
import dev.dworks.apps.anexplorer.fragment.ConnectionsFragment;
import dev.dworks.apps.anexplorer.fragment.CreateDirectoryFragment;
import dev.dworks.apps.anexplorer.fragment.CreateFileFragment;
import dev.dworks.apps.anexplorer.fragment.DirectoryFragment;
import dev.dworks.apps.anexplorer.fragment.AboutFragment;
import dev.dworks.apps.anexplorer.fragment.HomeFragment;
import dev.dworks.apps.anexplorer.fragment.MoveFragment;
import dev.dworks.apps.anexplorer.fragment.PickFragment;
import dev.dworks.apps.anexplorer.fragment.RecentsCreateFragment;
import dev.dworks.apps.anexplorer.fragment.SaveFragment;
import dev.dworks.apps.anexplorer.fragment.QuickShareFragment;
import dev.dworks.apps.anexplorer.fragment.ServerFragment;
import dev.dworks.apps.anexplorer.libcore.io.IoUtils;
import dev.dworks.apps.anexplorer.misc.AnalyticsManager;
import dev.dworks.apps.anexplorer.misc.AsyncTask;
import dev.dworks.apps.anexplorer.misc.ConnectionUtils;
import dev.dworks.apps.anexplorer.misc.ContentProviderClientCompat;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.DocumentCopier;
import dev.dworks.apps.anexplorer.misc.FileUtils;
import dev.dworks.apps.anexplorer.misc.IntentUtils;
import dev.dworks.apps.anexplorer.misc.MimePredicate;
import dev.dworks.apps.anexplorer.misc.PermissionUtil;
import dev.dworks.apps.anexplorer.misc.PinViewHelper;
import dev.dworks.apps.anexplorer.misc.PinViewHelper.PINDialogFragment;
import dev.dworks.apps.anexplorer.misc.ProviderExecutor;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.SAFManager;
import dev.dworks.apps.anexplorer.misc.SystemBarTintManager;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.DocumentStack;
import dev.dworks.apps.anexplorer.model.DocumentsContract;
import dev.dworks.apps.anexplorer.model.DocumentsContract.Root;
import dev.dworks.apps.anexplorer.model.DurableUtils;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.network.NetworkConnection;
import dev.dworks.apps.anexplorer.provider.ExternalStorageProvider;
import dev.dworks.apps.anexplorer.provider.MediaDocumentsProvider;
import dev.dworks.apps.anexplorer.provider.RecentsProvider;
import dev.dworks.apps.anexplorer.provider.RecentsProvider.RecentColumns;
import dev.dworks.apps.anexplorer.provider.RecentsProvider.ResumeColumns;
import dev.dworks.apps.anexplorer.setting.AppSettings;
import dev.dworks.apps.anexplorer.setting.SettingsFragment;
import dev.dworks.apps.anexplorer.ui.PopupSpinner;
import dev.dworks.apps.anexplorer.ui.SideMenuPopup;
import dev.dworks.apps.anexplorer.ui.DirectoryContainerView;
import dev.dworks.apps.anexplorer.misc.ThemeColors;

import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_BROWSE;
import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_CREATE;
import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_GET_CONTENT;
import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_MANAGE;
import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_OPEN;
import static dev.dworks.apps.anexplorer.BaseActivity.State.ACTION_OPEN_TREE;
import static dev.dworks.apps.anexplorer.BaseActivity.State.MODE_GRID;
import static dev.dworks.apps.anexplorer.BaseActivity.State.MODE_LIST;
import static dev.dworks.apps.anexplorer.fragment.DirectoryFragment.ANIM_DOWN;
import static dev.dworks.apps.anexplorer.fragment.DirectoryFragment.ANIM_NONE;
import static dev.dworks.apps.anexplorer.fragment.DirectoryFragment.ANIM_SIDE;
import static dev.dworks.apps.anexplorer.fragment.DirectoryFragment.ANIM_UP;
import static dev.dworks.apps.anexplorer.misc.AnalyticsManager.FILE_COUNT;
import static dev.dworks.apps.anexplorer.misc.AnalyticsManager.FILE_MOVE;
import static dev.dworks.apps.anexplorer.misc.AnalyticsManager.FILE_TYPE;
import static dev.dworks.apps.anexplorer.misc.SAFManager.ADD_STORAGE_REQUEST_CODE;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_ROOT;
import static dev.dworks.apps.anexplorer.provider.ExternalStorageProvider.isDownloadAuthority;

public class DocumentsActivity extends BaseActivity implements MenuItem.OnMenuItemClickListener {

    private static final String EXTRA_STATE = "state";
    private static final String EXTRA_AUTHENTICATED = "authenticated";
    private static final String EXTRA_ACTIONMODE = "actionmode";
    private static final String EXTRA_SEARCH_STATE = "searchsate";
    private static final String EXTRA_SETTINGS = "settings";
    private static final String EXTRA_ABOUT = "about";
    private static final int UPLOAD_FILE = 99;

    private static final int CODE_FORWARD = 42;


    private SearchView mSearchView;
    private MenuItem mSearchMenuItem;
    private SearchPillDrawable mSearchPill;
    private int mToolbarInsetStart = -1;

    private Toolbar mToolbar;
    private PopupSpinner mToolbarStack;


    private DirectoryContainerView mDirectoryContainer;

    private boolean mIgnoreNextNavigation;
    private boolean mIgnoreNextClose;
    private boolean mIgnoreNextCollapse;

    private boolean mSearchExpanded;
    private boolean mSearchResultShown;
    /** Settings are shown in place of the current root, which stays as it was underneath */
    private boolean mShowingSettings;
    /** About is shown the same way, instead of Settings */
    private boolean mShowingAbout;

    private RootsCache mRoots;
    private State mState;
	private boolean mAuthenticated;
    /** Whether the PIN was entered for the locked folder; it locks again on leaving it */
    private boolean mLockedFolderOpen;
    private long mStoppedAt;
    private boolean mActionMode;
    private RootInfo mParentRoot;

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final String MEDIA_EXTENSIONS = "|mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mts|mpg|mpeg|wmv|flv|3gp|ogv"
            + "|mp3|flac|wav|ogg|oga|m4a|aac|opus|wma|";

    /** Files whose mime type is generic are judged by extension, so Nova still gets them */
    private static boolean isPlayable(String mimeType, String name) {
        if (mimeType != null && (mimeType.startsWith("video/") || mimeType.startsWith("audio/"))) {
            return true;
        }
        final int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || (mimeType != null && !"application/octet-stream".equals(mimeType)
                && !"application/mp2t".equals(mimeType))) {
            return false;
        }
        return MEDIA_EXTENSIONS.contains("|" + name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) + "|");
    }

    @Override
    public void onCreate(Bundle icicle) {
        setTheme(R.style.DocumentsTheme_Document);
        if(Utils.hasLollipop()){
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        } else if(Utils.hasKitKat()){
            setTheme(R.style.DocumentsTheme_Translucent);
        }
        setUpStatusBar();
        // The TV keyboard is a panel in the middle of the screen; resizing the window around it
        // leaves a black band, so keep the app full size behind it
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
    	
/*		StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll()
				.penaltyLog()
				.build());
		StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll()
				.penaltyLog()
				.build())x;
*/
        super.onCreate(icicle);
        // Android 16 no longer calls onBackPressed() for apps targeting it
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!handleBack()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

		mRoots = DocumentsApplication.getRootsCache(this);

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        final Context context = this;
        final Resources res = getResources();

        mDirectoryContainer = (DirectoryContainerView) findViewById(R.id.container_directory);

        if (icicle != null) {
            mState = icicle.getParcelable(EXTRA_STATE);
            mAuthenticated = icicle.getBoolean(EXTRA_AUTHENTICATED);
            mActionMode = icicle.getBoolean(EXTRA_ACTIONMODE);
            mShowingSettings = icicle.getBoolean(EXTRA_SETTINGS);
            mShowingAbout = icicle.getBoolean(EXTRA_ABOUT);
        } else {
            buildDefaultState();
        }

        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        mToolbar.setTitleTextAppearance(context, R.style.TextAppearance_AppCompat_Widget_ActionBar_Title);
        applyTvTitleColor();
        if(Utils.hasKitKat() && !Utils.hasLollipop()) {
            ((LinearLayout.LayoutParams) mToolbar.getLayoutParams()).setMargins(0, getStatusBarHeight(this), 0, 0);
            mToolbar.setPadding(0, getStatusBarHeight(this), 0, 0);
        }


        mToolbarStack = (PopupSpinner) findViewById(R.id.stack);
        mToolbarStack.setOnItemSelectedListener(mStackListener);
        // The Spinner opens the same panel as the sort menu instead of its own list
        mToolbarStack.setOnOpenListener(this::showStackPopup);

        setSupportActionBar(mToolbar);

        initProtection();

        // Hide roots when we're managing a specific root
        if (mState.action == ACTION_MANAGE) {
            findViewById(R.id.container_roots).setVisibility(View.GONE);
        }

        if (mState.action == ACTION_CREATE) {
            final String mimeType = getIntent().getType();
            final String title = getIntent().getStringExtra(IntentUtils.EXTRA_TITLE);
            SaveFragment.show(getFragmentManager(), mimeType, title);
        } else if (mState.action == ACTION_OPEN_TREE) {
            PickFragment.show(getFragmentManager());
        }

        if (mState.action == ACTION_BROWSE) {
            final Intent moreApps = new Intent(getIntent());
            moreApps.setComponent(null);
            moreApps.setPackage(null);
            RootsCommonFragment.show(getFragmentManager(), moreApps);
        } else if (mState.action == ACTION_OPEN || mState.action == ACTION_CREATE
                || mState.action == ACTION_GET_CONTENT || mState.action == ACTION_OPEN_TREE) {
            RootsCommonFragment.show(getFragmentManager(), new Intent());
        }

        if (!mState.restored) {
            if (mState.action == ACTION_MANAGE) {
                final Uri rootUri = getIntent().getData();
                new RestoreRootTask(rootUri).executeOnExecutor(getCurrentExecutor());
            } else {
            	if(isDownloadAuthority(getIntent())){
            		onRootPicked(getDownloadRoot(), true);
            	} else if(ConnectionUtils.isServerAuthority(getIntent())){
                    RootInfo root = getIntent().getExtras().getParcelable(EXTRA_ROOT);
                    onRootPicked(root, true);
                } else{
                    try {
                        new RestoreStackTask().execute();
                    }
                    catch (SQLiteFullException e){
                        CrashReportingManager.logException(e);
                    }
            	}
            }
        } else {
            onCurrentDirectoryChanged(ANIM_NONE);
        }

        if(!PermissionUtil.hasStoragePermission(this)) {
            requestStoragePermissions();
        }
        checkLatestVersion();
    }

    private void checkLatestVersion() {
        UpdateFrom updateFrom = Utils.isGoogleBuild() ? UpdateFrom.GOOGLE_PLAY : UpdateFrom.AMAZON;
        AppUpdater appUpdater = new AppUpdater(this)
                .showEvery(3)
                .setUpdateFrom(updateFrom)
                .setDisplay(Display.DIALOG);
        appUpdater.start();
    }

    @Override
    public String getTag() {
        return null;
    }

    @Override
    public void again() {
        if(Utils.hasMarshmallow()) {
            RootsCache.updateRoots(this, ExternalStorageProvider.AUTHORITY);
            mRoots = DocumentsApplication.getRootsCache(this);
            final Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mRoots.updateAsync();
                    final RootInfo root = getCurrentRoot();
                    if(root.isHome()){
                        HomeFragment homeFragment = HomeFragment.get(getFragmentManager());
                        if(null != homeFragment) {
                            homeFragment.reloadData();
                        }
                    }
                }
            }, 500);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public int getGravity() {
        if(Utils.hasJellyBeanMR1()){
            Configuration config = getResources().getConfiguration();
            if(config.getLayoutDirection() != View.LAYOUT_DIRECTION_LTR){
                return Gravity.LEFT;
            }
        }
        return Gravity.RIGHT;
    }

    public static boolean isRTL(Locale locale) {
        final int directionality = Character.getDirectionality(locale.getDisplayName().charAt(0));
        return directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    private void initProtection() {

		if(mAuthenticated || !AppSettings.isPinEnabled(this)){
			return;
		}
        final Dialog d = new Dialog(this, R.style.DocumentsTheme_DailogPIN);
        d.setContentView(new PinViewHelper((LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE), null, null) {
            public void onEnter(String password) {
                super.onEnter(password);
                if (AppSettings.checkPin(DocumentsActivity.this, password)) {
                	mAuthenticated = true;
                	d.dismiss();
                }
                else {
                    Utils.showError(DocumentsActivity.this, R.string.incorrect_pin);
                }
            }

            public void onCancel() {
                super.onCancel();
                finish();
                d.dismiss();
            }
        }.getView(), new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        
        PINDialogFragment pinFragment = new PINDialogFragment();
        pinFragment.setDialog(d);
        pinFragment.setCancelable(false);
        pinFragment.show(getFragmentManager(), "PIN Dialog");
	}


    private void buildDefaultState() {
        mState = new State();

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (IntentUtils.ACTION_OPEN_DOCUMENT.equals(action)) {
            mState.action = ACTION_OPEN;
        } else if (IntentUtils.ACTION_CREATE_DOCUMENT.equals(action)) {
            mState.action = ACTION_CREATE;
        } else if (IntentUtils.ACTION_GET_CONTENT.equals(action)) {
            mState.action = ACTION_GET_CONTENT;
        } else if (IntentUtils.ACTION_OPEN_DOCUMENT_TREE.equals(action)) {
            mState.action = ACTION_OPEN_TREE;
        } else if (DocumentsContract.ACTION_MANAGE_ROOT.equals(action)) {
            //mState.action = ACTION_MANAGE;
            mState.action = ACTION_BROWSE;
        } else{
            mState.action = ACTION_BROWSE;
        }

        if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT) {
            mState.allowMultiple = intent.getBooleanExtra(IntentUtils.EXTRA_ALLOW_MULTIPLE, false);
        }
        
        if (mState.action == ACTION_GET_CONTENT || mState.action == ACTION_BROWSE) {
            mState.acceptMimes = new String[] { "*/*" };
            mState.allowMultiple = true;
        }
        else if (intent.hasExtra(IntentUtils.EXTRA_MIME_TYPES)) {
            mState.acceptMimes = intent.getStringArrayExtra(IntentUtils.EXTRA_MIME_TYPES);
        } else {
            mState.acceptMimes = new String[] { intent.getType() };
        }

        mState.localOnly = intent.getBooleanExtra(IntentUtils.EXTRA_LOCAL_ONLY, true);
        mState.forceAdvanced = intent.getBooleanExtra(DocumentsContract.EXTRA_SHOW_ADVANCED	, false);
        mState.showAdvanced = mState.forceAdvanced
                | AppSettings.getDisplayAdvancedDevices(this);
        mState.derivedMode = AppSettings.getViewMode(this);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if(menuAction(item)){
            return true;
        }
        return false;
    }

    private class RestoreRootTask extends AsyncTask<Void, Void, RootInfo> {
        private Uri mRootUri;

        public RestoreRootTask(Uri rootUri) {
            mRootUri = rootUri;
        }

        @Override
        protected RootInfo doInBackground(Void... params) {
            final String rootId = DocumentsContract.getRootId(mRootUri);
            return mRoots.getRootOneshot(mRootUri.getAuthority(), rootId);
        }

        @Override
        protected void onPostExecute(RootInfo root) {
            if(!Utils.isActivityAlive(DocumentsActivity.this)){
                return;
            }
            mState.restored = true;

            if (root != null) {
                onRootPicked(root, true);
            } else {
                Log.w(TAG, "Failed to find root: " + mRootUri);
                finish();
            }
        }
    }

    private class RestoreStackTask extends AsyncTask<Void, Void, Void> {
        private volatile boolean mRestoredStack;
        private volatile boolean mExternal;

        @Override
        protected Void doInBackground(Void... params) {
            // Restore last stack for calling package
            final String packageName = getCallingPackageMaybeExtra();
            final Cursor cursor = getContentResolver()
                    .query(RecentsProvider.buildResume(packageName), null, null, null, null);
            try {
                if (null != cursor && cursor.moveToFirst()) {
                    mExternal = cursor.getInt(cursor.getColumnIndex(ResumeColumns.EXTERNAL)) != 0;
                    final byte[] rawStack = cursor.getBlob(
                            cursor.getColumnIndex(ResumeColumns.STACK));
                    DurableUtils.readFromArray(rawStack, mState.stack);
                    mRestoredStack = true;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to resume: " + e);
                CrashReportingManager.logException(e);
            } finally {
                IoUtils.closeQuietly(cursor);
            }

            if (mRestoredStack) {
                // Update the restored stack to ensure we have freshest data
                final Collection<RootInfo> matchingRoots = mRoots.getMatchingRootsBlocking(mState);
                try {
                    mState.stack.updateRoot(matchingRoots);
                    mState.stack.updateDocuments(getContentResolver());
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Failed to restore stack: " + e);
                    CrashReportingManager.logException(e);
                    mState.stack.reset();
                    mRestoredStack = false;
                }
            }
            else{
            	RootInfo root = getCurrentRoot();
                // Home and the other screens of the app's own have no documents to open
                if(null == root || null == root.authority){
                    return null;
                }
                final Uri uri = DocumentsContract.buildDocumentUri(root.authority, root.documentId);
                DocumentInfo result;
				try {
					result = DocumentInfo.fromUri(getContentResolver(), uri);
	                if (result != null) {
	                    mState.stack.push(result);
	                    mState.stackTouched = true;
	                }
				} catch (FileNotFoundException e) {
					e.printStackTrace();
                    CrashReportingManager.logException(e);
				}
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if(!Utils.isActivityAlive(DocumentsActivity.this)){
                return;
            }
            mState.restored = true;

            onCurrentDirectoryChanged(ANIM_NONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadDisplaySettings();
        // Away for a while, e.g. on another app, the locked folder locks again; a quick look at
        // a file in a viewer doesn't
        if (mStoppedAt > 0 && SystemClock.elapsedRealtime() - mStoppedAt > LOCKED_FOLDER_TIMEOUT) {
            mLockedFolderOpen = false;
        }
        mStoppedAt = 0;
        final RootInfo root = getCurrentRoot();
        if (!mLockedFolderOpen && null != root && root.isLockedFolder() && !mShowingSettings && !mShowingAbout) {
            askLockedFolderPin(null);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mStoppedAt = SystemClock.elapsedRealtime();
    }

    private static final long LOCKED_FOLDER_TIMEOUT = 60 * 1000;

    /**
     * Asks for the PIN before showing the locked folder: then opens root, when given. Cancelled,
     * it goes back Home if the locked folder is showing.
     */
    private void askLockedFolderPin(final RootInfo root) {
        if (!AppSettings.isPinProtected(this)) {
            mLockedFolderOpen = true;
            if (null != root) {
                onRootPicked(root, true);
            }
            return;
        }
        if (null != getFragmentManager().findFragmentByTag(TAG_LOCKED_PIN)) {
            return;
        }
        final Dialog d = new Dialog(this, R.style.DocumentsTheme_DailogPIN);
        final PinViewHelper pin = new PinViewHelper(getLayoutInflater(), null, null) {
            public void onEnter(String password) {
                super.onEnter(password);
                if (AppSettings.checkPin(DocumentsActivity.this, password)) {
                    mLockedFolderOpen = true;
                    d.dismiss();
                    if (null != root) {
                        onRootPicked(root, true);
                    }
                } else {
                    setInstruction(R.string.incorrect_pin);
                }
            }

            public void onCancel() {
                super.onCancel();
                d.dismiss();
                final RootInfo current = getCurrentRoot();
                if (null != current && current.isLockedFolder()) {
                    onRootPicked(mRoots.getHomeRoot(), true);
                } else {
                    // The sidebar already marked the locked folder when it was picked
                    final RootsCommonFragment roots = RootsCommonFragment.get(getFragmentManager());
                    if (roots != null) {
                        roots.onCurrentRootChanged();
                    }
                }
            }
        };
        d.setContentView(pin.getView(), new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        final PINDialogFragment fragment = new PINDialogFragment();
        fragment.setDialog(d);
        fragment.setCancelable(false);
        fragment.show(getFragmentManager(), TAG_LOCKED_PIN);
    }

    private static final String TAG_LOCKED_PIN = "LockedFolderPin";

    /** Applies the display settings (file size, thumbnails, hidden files...) to the current state. */
    public void loadDisplaySettings() {
        if (mState.action == ACTION_MANAGE) {
            mState.showSize = true;
            mState.showFolderSize = false;
            mState.showThumbnail = true;
        } else {
            mState.showSize = AppSettings.getDisplayFileSize(this);
            mState.showFolderSize = AppSettings.getDisplayFolderSize(this);
            mState.showThumbnail = AppSettings.getDisplayFileThumbnail(this);
            mState.showHiddenFiles = AppSettings.getDisplayFileHidden(this);
            invalidateMenu();
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        updateActionBar();
    }

    /** TV titles in the accent color, the same for a root's title and a folder's path. */
    private void applyTvTitleColor() {
        mToolbar.setTitleTextColor(getTvTitleColor());
    }

    private int getTvTitleColor() {
        return MaterialColors.getColor(mToolbar, androidx.appcompat.R.attr.colorPrimary);
    }

    public void updateActionBar() {
        final RootInfo root = getCurrentRoot();
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        if (mShowingSettings || mShowingAbout) {
            setTitle(getString(mShowingAbout ? R.string.menu_about : R.string.menu_settings));
            mToolbarStack.setVisibility(View.GONE);
            mToolbarStack.setAdapter(null);
        } else if (mSearchExpanded) {
            setTitle(null);
            mToolbarStack.setVisibility(View.GONE);
            mToolbarStack.setAdapter(null);
        } else if (null != root && (root.isApp() || root.isApk()
                || (root.isVideos() && mState.stack.size() <= 2))) {
            // The top tabs name the screen, so no title or folder dropdown beside them
            setTitle(null);
            mToolbarStack.setVisibility(View.GONE);
            mToolbarStack.setAdapter(null);
        } else {
            if (mState.stack.size() <= 1) {
                if(null != root){
                    setTitle(root.title);
                    AnalyticsManager.setCurrentScreen(this, root.derivedTag);
                }
                mToolbarStack.setVisibility(View.GONE);
                mToolbarStack.setAdapter(null);
            } else {
                setTitle(null);
                mToolbarStack.setVisibility(View.VISIBLE);
                mToolbarStack.setAdapter(mStackAdapter);
                mIgnoreNextNavigation = true;
                mToolbarStack.setSelection(mStackAdapter.getCount() - 1);
            }
        }
    }

    public void setTitle(String title){
        mToolbar.setTitle(title);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        if (null != mToolbarActionMode) {
            return mToolbarActionMode.onCreateMenu(menu);
        }
        getMenuInflater().inflate(R.menu.activity, menu);
        replaceSortSubMenu(menu);

        final MenuItem searchMenu = menu.findItem(R.id.menu_search);
        mSearchMenuItem = searchMenu;
        mSearchView = (SearchView) searchMenu.getActionView();
        styleTvSearchView();
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mSearchView.removeCallbacks(mTvSearchRunnable);
                showTvSearchResults(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (mSearchExpanded) {
                    // Search as the user types, once they pause
                    mSearchView.removeCallbacks(mTvSearchRunnable);
                    mSearchView.postDelayed(mTvSearchRunnable, 400);
                }
                return false;
            }
        });

        searchMenu.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchExpanded = true;
                // Let the pill start closer to the toolbar's edge
                mToolbarInsetStart = mToolbar.getContentInsetStart();
                mToolbar.setContentInsetsRelative(
                        (int) (4 * getResources().getDisplayMetrics().density),
                        mToolbar.getContentInsetEnd());
                final View pill = item.getActionView();
                if (pill instanceof ViewGroup && !item.isActionViewExpanded()) {
                    animateSearchPillIn((ViewGroup) pill, pill.findViewWithTag("search_go"));
                }
                // The toolbar adds its collapse button after this callback
                mToolbar.post(() -> {
                    setToolbarCollapseButtonVisible(false);
                    mSearchView.setIconified(false);
                    final View mag = mSearchView.findViewById(androidx.appcompat.R.id.search_button);
                    if (mag != null) {
                        mag.setVisibility(View.GONE);
                    }
                });
                updateActionBar();
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchExpanded = mSearchResultShown = false;
                setToolbarCollapseButtonVisible(true);
                if (mToolbarInsetStart >= 0) {
                    mToolbar.setContentInsetsRelative(mToolbarInsetStart, mToolbar.getContentInsetEnd());
                }
                if (mIgnoreNextCollapse) {
                    mIgnoreNextCollapse = false;
                    updateActionBar();
                    return true;
                }

                mState.currentSearch = null;
                onCurrentDirectoryChanged(ANIM_NONE);
                return true;
            }
        });

        mSearchView.setOnCloseListener(new SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                mSearchExpanded = mSearchResultShown = false;
                if (mIgnoreNextClose) {
                    mIgnoreNextClose = false;
                    updateActionBar();
                    return false;
                }

                mState.currentSearch = null;
                onCurrentDirectoryChanged(ANIM_NONE);
                return false;
            }
        });

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (null != mToolbarActionMode) {
            return mToolbarActionMode.onPrepareMenu(menu);
        }
        updateMenuItems(menu);
        return true;
    }

    public void updateMenuItems(Menu menu){
        mMenuMode = mState.derivedMode;
        mMenuShowSize = mState.showSize;
        mMenuEmpty = isDirectoryEmpty();
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        menu.findItem(R.id.menu_create_dir).setVisible(showActionMenu());
        menu.findItem(R.id.menu_create_file).setVisible(showActionMenu());
        final MenuItem search = menu.findItem(R.id.menu_search);
        final MenuItem sort = menu.findItem(R.id.menu_sort);
        // Not in the menu on TV, where sorting has its own panel (showSortPanel)
        final MenuItem sortSize = menu.findItem(R.id.menu_sort_size);
        final MenuItem grid = menu.findItem(R.id.menu_grid);
        final MenuItem list = menu.findItem(R.id.menu_list);
        final MenuItem settings = menu.findItem(R.id.menu_settings);

        // TV has Settings and About in the drawer, so drop the overflow button entirely
        menu.findItem(R.id.menu_receive_quickshare).setVisible(false);
        menu.findItem(R.id.menu_about).setVisible(false);
        menu.findItem(R.id.menu_exit).setVisible(false);

        sort.setVisible(cwd != null);
        grid.setVisible(!RootInfo.isOtherRoot(getCurrentRoot()) && mState.derivedMode != MODE_GRID);
        // Home, Connections, FTP server and Quick Share have no file listing to switch
        final boolean noListing = RootInfo.isOtherRoot(getCurrentRoot()) && cwd == null;
        list.setVisible(!noListing && mState.derivedMode != MODE_LIST);

        if (mState.currentSearch != null) {
            // Search uses backend ranking; no sorting
            //sort.setVisible(false);

            search.expandActionView();

            mSearchView.setIconified(false);
            mSearchView.clearFocus();
            mSearchView.setQuery(mState.currentSearch, false);
        } else {
            mIgnoreNextClose = true;
            mSearchView.setIconified(true);
            mSearchView.clearFocus();

            mIgnoreNextCollapse = true;
            search.collapseActionView();
        }

        // Only sort by size when visible
        if (null != sortSize) {
            sortSize.setVisible(mState.showSize);
        }

        final boolean searchVisible;
        if (mState.action == ACTION_CREATE || mState.action == ACTION_OPEN_TREE) {
            searchVisible = false;

            // No display options in recent directories
            if (cwd == null) {
                grid.setVisible(false);
                list.setVisible(false);
            }
            if (mState.action == State.ACTION_CREATE) {
                if (null != SaveFragment.get(fm))
                    SaveFragment.get(fm).setSaveEnabled(cwd != null && cwd.isCreateSupported());
            }
        } else {
            searchVisible = root != null
                    && ((root.flags & Root.FLAG_SUPPORTS_SEARCH) != 0);
            // TODO: Is this useful?
            if(null != SaveFragment.get(fm))
                SaveFragment.get(fm).setSaveEnabled(cwd != null && cwd.isCreateSupported());

            if(null != MoveFragment.get(fm))
                MoveFragment.get(fm).setSaveEnabled(cwd != null
                        && (cwd.isMoveSupported() || cwd.isCreateSupported()));
        }

        // TODO: close any search in-progress when hiding
        search.setVisible(searchVisible);

        settings.setVisible(false);
        final MenuItem emptyBin = menu.findItem(R.id.menu_empty_bin);
        emptyBin.setVisible(null != root && root.isRecycleBin()
                && null != cwd && TextUtils.equals(root.documentId, cwd.documentId));
        emptyBin.setEnabled(!mMenuEmpty);
        emptyBin.getIcon().mutate().setAlpha(mMenuEmpty ? 97 : 255);

        // Settings have nothing to create, search, sort or lay out
        if (mShowingSettings || mShowingAbout) {
            menu.findItem(R.id.menu_create_dir).setVisible(false);
            menu.findItem(R.id.menu_create_file).setVisible(false);
            search.setVisible(false);
            sort.setVisible(false);
            grid.setVisible(false);
            list.setVisible(false);
            menu.findItem(R.id.menu_empty_bin).setVisible(false);
        }
    }

    /** The remote's Menu key opens the focused item's menu, the same as a long press. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            final View focused = getCurrentFocus();
            if (focused != null && focused.isLongClickable()) {
                if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                    focused.performLongClick();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (null != mToolbarActionMode) {
            return mToolbarActionMode.onItemClicked(item);
        }
        if(menuAction(item)){
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Settings open as a tab: in place of the file list, with the sidebar still beside them. */
    public void openSettings() {
        if (mShowingSettings) {
            return;
        }
        mShowingSettings = true;
        mShowingAbout = false;
        onCurrentDirectoryChanged(ANIM_NONE);
        AnalyticsManager.logEvent("setting_open");
    }

    public boolean isShowingAbout() {
        return mShowingAbout;
    }

    /** Back from About to the section that was open before. */
    private void closeAbout() {
        mShowingAbout = false;
        onCurrentDirectoryChanged(ANIM_NONE);
    }

    public boolean isShowingSettings() {
        return mShowingSettings;
    }

    /** Back from Settings to the section that was open before. */
    private void closeSettings() {
        mShowingSettings = false;
        loadDisplaySettings();
        onCurrentDirectoryChanged(ANIM_NONE);
    }

    public void openAbout() {
        if (mShowingAbout) {
            return;
        }
        mShowingAbout = true;
        mShowingSettings = false;
        onCurrentDirectoryChanged(ANIM_NONE);
        AnalyticsManager.logEvent("about_open");
    }

    public boolean menuAction(MenuItem item) {

        final int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.menu_empty_bin) {
            emptyRecycleBin();
            return true;
        }  else if (id == R.id.menu_create_dir) {
            createFolder();
            return true;
        } else if (id == R.id.menu_create_file) {
            onStateChanged();
            createFile();
            return true;
        } else if (id == R.id.menu_search) {
            return false;
        } else if (id == R.id.menu_sort) {
            showSortPanel();
            return true;
        } else if (id == R.id.menu_sort_name) {
            setUserSortOrder(State.SORT_ORDER_DISPLAY_NAME);
            Bundle params = new Bundle();
            params.putString("type", "name");
            AnalyticsManager.logEvent("sort_name", params);
            return true;
        } else if (id == R.id.menu_sort_date) {
            setUserSortOrder(State.SORT_ORDER_LAST_MODIFIED);
            Bundle params = new Bundle();
            params.putString("type", "modified");
            AnalyticsManager.logEvent("sort_modified", params);
            return true;
        } else if (id == R.id.menu_sort_size) {
            setUserSortOrder(State.SORT_ORDER_SIZE);
            Bundle params = new Bundle();
            params.putString("type", "size");
            AnalyticsManager.logEvent("sort_size", params);
            return true;
        } else if (id == R.id.menu_sort_played) {
            setUserSortOrder(State.SORT_ORDER_PLAYED);
            Bundle params = new Bundle();
            params.putString("type", "played");
            AnalyticsManager.logEvent("sort_played", params);
            return true;
        } else if (id == R.id.menu_grid) {
            setUserMode(State.MODE_GRID);
            Bundle params = new Bundle();
            params.putString("type", "grid");
            AnalyticsManager.logEvent("display_grid", params);
            return true;
        } else if (id == R.id.menu_list) {
            setUserMode(State.MODE_LIST);
            Bundle params = new Bundle();
            params.putString("type", "list");
            AnalyticsManager.logEvent("display_list", params);
            return true;
        } else if (id == R.id.menu_settings) {
            openSettings();
            return true;
        } else if (id == R.id.menu_receive_quickshare) {
            openQuickShareReceiver();
            return true;
        } else if (id == R.id.menu_about) {
            openAbout();
            return true;
        } else if (id == R.id.menu_exit) {
            Bundle params = new Bundle();
            AnalyticsManager.logEvent("app_exit");
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        return false;
    }

    private void openQuickShareReceiver() {
        startActivity(new Intent(this, QuickShareReceiveActivity.class));
        AnalyticsManager.logEvent("receive_quickshare_open");
    }

    private void createFolder() {
        CreateDirectoryFragment.show(getSupportFragmentManager());
        Bundle params = new Bundle();
        params.putString(FILE_TYPE, "folder");
        AnalyticsManager.logEvent("create_folder", params);
    }

    private void createFile() {
        CreateFileFragment.show(getSupportFragmentManager(), "text/plain", "File");
        Bundle params = new Bundle();
        params.putString(FILE_TYPE, "file");
        AnalyticsManager.logEvent("create_file", params);
    }

    private void uploadFile(){
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            this.startActivityForResult(Intent.createChooser(intent, "Select a File to Upload"), UPLOAD_FILE);
        } catch(android.content.ActivityNotFoundException e) {
            Utils.showError(this, R.string.upload_error);
        }
        Bundle params = new Bundle();
        params.putString(FILE_TYPE, "file");
        AnalyticsManager.logEvent("upload_file", params);
    }

    /**
     * Update UI to reflect internal state changes not from user.
     */
    public void onStateChanged() {
        invalidateMenu();
    }

    /**
     * A toolbar item with a submenu always opens it as the old drop-down, so on TV the sort item
     * becomes a plain button that opens {@link #showSortPanel()}.
     */
    private void replaceSortSubMenu(Menu menu) {
        final MenuItem sort = menu.findItem(R.id.menu_sort);
        if (null == sort) {
            return;
        }
        final int group = sort.getGroupId();
        final int order = sort.getOrder();
        final CharSequence title = sort.getTitle();
        final Drawable icon = sort.getIcon();
        menu.removeItem(R.id.menu_sort);
        menu.add(group, R.id.menu_sort, order, title)
                .setIcon(icon)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
    }

    /** "Sort by" panel under the sort button, with the current order checked. */
    private void showSortPanel() {
        View anchor = mToolbar.findViewById(R.id.menu_sort);
        if (null == anchor) {
            anchor = mToolbar;
        }
        final Menu menu = new PopupMenu(this, anchor).getMenu();
        getMenuInflater().inflate(R.menu.sort, menu);
        menu.findItem(R.id.menu_sort_size).setVisible(mState.showSize);
        final RootInfo root = getCurrentRoot();
        menu.findItem(R.id.menu_sort_played).setVisible(root != null && root.isVideos());
        final int checked;
        switch (mState.derivedSortOrder) {
            case State.SORT_ORDER_LAST_MODIFIED:
                checked = R.id.menu_sort_date;
                break;
            case State.SORT_ORDER_SIZE:
                checked = R.id.menu_sort_size;
                break;
            case State.SORT_ORDER_PLAYED:
                checked = R.id.menu_sort_played;
                break;
            default:
                checked = R.id.menu_sort_name;
                break;
        }
        menu.findItem(checked).setChecked(true);
        SideMenuPopup.show(anchor, getString(R.string.menu_sort), menu,
                new SideMenuPopup.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        return onOptionsItemSelected(item);
                    }
                }, true);
    }

    // What the toolbar menu was last prepared with; see onDirectoryLoaded
    private int mMenuMode = State.MODE_UNKNOWN;
    private boolean mMenuShowSize;
    private boolean mMenuEmpty;

    /**
     * Rebuilding the toolbar menu takes ~80ms on a TV and would run between the folder loading and
     * its first frame. Changing folder already rebuilt it, so only a different view mode or size
     * option from the loaded folder needs another.
     */
    @Override
    public void onDirectoryLoaded() {
        if (mState.derivedMode != mMenuMode || mState.showSize != mMenuShowSize
                || isDirectoryEmpty() != mMenuEmpty) {
            invalidateMenu();
        }
    }

    private boolean isDirectoryEmpty() {
        final Fragment fragment = DirectoryFragment.get(getFragmentManager());
        return fragment instanceof DirectoryFragment && ((DirectoryFragment) fragment).isEmpty();
    }

    /**
     * Set state sort order based on explicit user action.
     */
    private void setUserSortOrder(int sortOrder) {
        mState.userSortOrder = sortOrder;
        Fragment fragment = DirectoryFragment.get(getFragmentManager());
        if(fragment instanceof DirectoryFragment) {
            final DirectoryFragment directory = (DirectoryFragment) fragment;
            if (directory != null) {
                directory.onUserSortOrderChanged();
            }
        }
    }

    /**
     * Set state mode based on explicit user action.
     */
    private void setUserMode(int mode) {
        mState.userMode = mode;
        Fragment fragment = DirectoryFragment.get(getFragmentManager());
        if(fragment instanceof DirectoryFragment) {
            final DirectoryFragment directory = (DirectoryFragment) fragment;
            if (directory != null) {
                directory.onUserModeChanged();
            }
        }
    }

    /**
     * refresh Data currently shown
     */
    private void refreshData() {
        Fragment fragment = DirectoryFragment.get(getFragmentManager());
        if(fragment instanceof DirectoryFragment) {
            final DirectoryFragment directory = (DirectoryFragment) fragment;
            if (directory != null) {
                directory.onUserSortOrderChanged();
            }
        }
    }


    public void setPending(boolean pending) {
        final SaveFragment save = SaveFragment.get(getFragmentManager());
        if (save != null) {
            save.setPending(pending);
        }

        final RootInfo root = getCurrentRoot();
        if(root != null && root.isUsbStorage()){
            refreshData();
        }
    }

    private final Runnable mTvSearchRunnable = () -> {
        if (mSearchView != null) {
            showTvSearchResults(mSearchView.getQuery().toString());
        }
    };

    /**
     * Swaps only the file list for the results, leaving the toolbar and the search field (and
     * its focus) alone; an empty query brings the folder back.
     */
    private void showTvSearchResults(String query) {
        if (!Utils.isActivityAlive(this) || !mSearchExpanded) {
            return;
        }
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();
        if (root == null || cwd == null) {
            return;
        }
        final String search = TextUtils.isEmpty(query.trim()) ? null : query.trim();
        if (TextUtils.equals(search, mState.currentSearch)) {
            return;
        }
        mState.currentSearch = search;
        mSearchResultShown = search != null;
        if (search != null) {
            DirectoryFragment.showSearch(getFragmentManager(), root, cwd, search, ANIM_NONE);
            Bundle params = new Bundle();
            params.putString("query", search);
            AnalyticsManager.logEvent("search", root, params);
        } else {
            DirectoryFragment.showNormal(getFragmentManager(), root, cwd, ANIM_NONE);
        }
    }

    /** Grows the pill leftwards around the search icon, which stays put, then fades the rest in. */
    private void animateSearchPillIn(ViewGroup pill, View searchButton) {
        if (mSearchPill == null) {
            return;
        }
        for (int i = 0; i < pill.getChildCount(); i++) {
            final View child = pill.getChildAt(i);
            if (child != searchButton) {
                child.setAlpha(0f);
                child.animate().alpha(1f).setStartDelay(90).setDuration(130).start();
            }
        }
        mSearchPill.setCollapsed(1f);
        final ValueAnimator grow = ValueAnimator.ofFloat(1f, 0f);
        grow.setDuration(220);
        grow.setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator());
        grow.addUpdateListener(animation -> mSearchPill.setCollapsed((float) animation.getAnimatedValue()));
        grow.start();
    }

    /**
     * Rounded pill background that can shrink to a circle at its end. Changing it only redraws,
     * unlike swapping inset drawables, which relayouts the toolbar on every frame.
     */
    private static class SearchPillDrawable extends Drawable {
        private final MaterialShapeDrawable mShape;
        private final int mHeight;
        private final int mEndInset;
        private float mCollapsed;

        SearchPillDrawable(MaterialShapeDrawable shape, int height, int endInset) {
            mShape = shape;
            mHeight = height;
            mEndInset = endInset;
        }

        void setCollapsed(float collapsed) {
            mCollapsed = collapsed;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect bounds = getBounds();
            final int top = bounds.centerY() - mHeight / 2;
            final int bottom = top + mHeight;
            final int right = bounds.right - mEndInset;
            // Fully collapsed it is a circle as wide as it is tall
            final int collapsedLeft = right - (bottom - top);
            final int left = (int) (bounds.left + (collapsedLeft - bounds.left) * mCollapsed);
            mShape.setBounds(left, top, right, bottom);
            mShape.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            mShape.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mShape.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static boolean isSearchKeyboardShown(View field) {
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(field);
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    private void showSearchKeyboard(View field) {
        field.requestFocus();
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void setToolbarCollapseButtonVisible(boolean visible) {
        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        if (toolbar == null) {
            return;
        }
        final CharSequence description = getString(androidx.appcompat.R.string.abc_toolbar_collapse_description);
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            final View child = toolbar.getChildAt(i);
            if (child instanceof ImageButton && description.equals(child.getContentDescription())
                    && child.getParent() == toolbar) {
                child.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        }
    }

    private Toolbar.LayoutParams newSearchPillParams(int margin, float density) {
        final Toolbar.LayoutParams lp = new Toolbar.LayoutParams(
                (int) (640 * density), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(0, margin * 3 / 2, margin, margin * 3 / 2);
        return lp;
    }

    private ImageButton newSearchPillButton(int icon, int size, ColorStateList tint) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(tint);
        // A round highlight that stays inside the pill; a background with a focused state also
        // turns off the platform's square focus highlight
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ColorUtils.setAlphaComponent(tint.getDefaultColor(), 0x3D));
        final Drawable highlight = circle;
        final StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, highlight);
        background.addState(new int[]{android.R.attr.state_pressed}, highlight);
        button.setBackground(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setDefaultFocusHighlightEnabled(false);
        }
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return button;
    }

    /** Rounded, faded search field; Back closes it once the keyboard is hidden and it is empty. */
    private void styleTvSearchView() {
        final float radius = getResources().getDimension(R.dimen.card_corner_radius) * 2;
        final MaterialShapeDrawable background = new MaterialShapeDrawable(
                ShapeAppearanceModel.builder().setAllCornerSizes(radius).build());
        final int fill = MaterialColors.getColor(mSearchView,
                com.google.android.material.R.attr.colorOnSurface);
        background.setFillColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(fill, 40)));
        final float density = getResources().getDisplayMetrics().density;
        final int margin = (int) (8 * density);
        // Buttons are as tall as the pill so their round highlight follows its border
        final int iconSize = (int) (36 * density);
        // Puts the search button's centre where the toolbar's search icon was
        final int endInset = (int) (6 * density);
        final ColorStateList iconTint = ColorStateList.valueOf(fill);

        // Back button, field and search button share one rounded pill
        final LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        // The toolbar ignores margins on action views, so inset the drawn pill instead
        // The search button keeps the toolbar search icon's spot, so the pill ends just past it
        mSearchPill = new SearchPillDrawable(background, iconSize, endInset);
        pill.setBackground(mSearchPill);
        pill.setMinimumWidth((int) (640 * density));
        pill.setLayoutParams(newSearchPillParams(margin, density));

        final ImageButton back = newSearchPillButton(R.drawable.abc_ic_ab_back_material, iconSize, iconTint);
        back.setContentDescription(getString(androidx.appcompat.R.string.abc_toolbar_collapse_description));
        back.setOnClickListener(v -> mSearchMenuItem.collapseActionView());
        final ImageButton clear = newSearchPillButton(R.drawable.abc_ic_clear_material, iconSize, iconTint);
        clear.setContentDescription(getString(androidx.appcompat.R.string.abc_searchview_description_clear));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> {
            mSearchView.setQuery("", false);
            mSearchView.requestFocus();
        });
        final ImageButton go = newSearchPillButton(R.drawable.abc_ic_search_api_material, iconSize, iconTint);
        go.setContentDescription(getString(R.string.menu_search));
        go.setTag("search_go");
        // Search runs as the user types, so this is only a marker, not a stop for the remote
        go.setBackground(null);
        go.setFocusable(false);
        go.setClickable(false);
        go.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        mSearchView.setBackground(null);
        mSearchView.setQueryHint(getString(androidx.appcompat.R.string.abc_search_hint));
        final View closeButton = mSearchView.findViewById(androidx.appcompat.R.id.search_close_btn);
        if (closeButton != null) {
            // Replaced by the pill's own clear button, which shows only when there is text
            closeButton.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
            closeButton.setEnabled(false);
            closeButton.setFocusable(false);
        }
        final View magIcon = mSearchView.findViewById(androidx.appcompat.R.id.search_button);
        if (magIcon != null) {
            // The pill has its own search button on the right
            magIcon.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
            magIcon.setVisibility(View.GONE);
        }
        mSearchView.setMaxWidth(Integer.MAX_VALUE);
        pill.addView(back);
        pill.addView(mSearchView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        pill.addView(clear);
        final LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        goParams.setMarginEnd(endInset);
        pill.addView(go, goParams);
        final TextView queryText = mSearchView.findViewById(androidx.appcompat.R.id.search_src_text);
        if (queryText != null) {
            // Selecting the field with the remote brings the keyboard back after Back hid it
            queryText.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    showSearchKeyboard(v);
                }
                // With the keyboard hidden, the remote's left and right leave the field instead of
                // walking the cursor letter by letter. Keys sent by the keyboard itself still move
                // the cursor: it repeats them until the cursor moves, so swallowing them loops forever
                if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                        && (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == 0 && !isSearchKeyboardShown(v)) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        final View next = v.focusSearch(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                        if (next != null) {
                            next.requestFocus();
                        }
                    }
                    return true;
                }
                return false;
            });
            queryText.setOnClickListener(this::showSearchKeyboard);
            queryText.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                    clear.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
                }

                @Override
                public void afterTextChanged(android.text.Editable text) {
                }
            });
        }
        mSearchMenuItem.setActionView(pill);

        final View root = getWindow().getDecorView();
        final boolean[] imeShown = {false};
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            final boolean shown = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (imeShown[0] && !shown && mSearchExpanded && mSearchMenuItem != null) {
                if (TextUtils.isEmpty(mSearchView.getQuery())) {
                    mSearchMenuItem.collapseActionView();
                } else {
                    // The search field drops focus when Back hides the keyboard, which sends
                    // focus to the sidebar; keep it on the field so OK reopens the keyboard
                    final View field = mSearchView.findViewById(androidx.appcompat.R.id.search_src_text);
                    if (field != null) {
                        field.post(field::requestFocus);
                    }
                }
            }
            imeShown[0] = shown;
            return ViewCompat.onApplyWindowInsets(v, insets);
        });
    }

    /** Steps back inside the app; false when Back should leave it */
    private boolean handleBack() {
        if (null != mToolbarActionMode) {
            mToolbarActionMode.finish();
            return true;
        }
        // Back clears a selection before it leaves the folder
        final Fragment directory = DirectoryFragment.get(getFragmentManager());
        if (directory instanceof DirectoryFragment && ((DirectoryFragment) directory).clearSelection()) {
            return true;
        }
        if (mSearchExpanded && null != mSearchView && TextUtils.isEmpty(mSearchView.getQuery())) {
            if (mSearchMenuItem != null && mSearchMenuItem.collapseActionView()) {
                return true;
            }
        }
        if (mShowingSettings) {
            closeSettings();
            return true;
        }
        if (mShowingAbout) {
            closeAbout();
            return true;
        }
        if (mState.stackTouched && mState.stack.size() > 1) {
            mState.stack.pop();
            onCurrentDirectoryChanged(ANIM_UP);
            return true;
        }
        if (null != mParentRoot) {
            onRootPicked(mParentRoot, true);
            mParentRoot = null;
            return true;
        }
        // Back goes to Home first, and only leaves the app from there
        final RootInfo root = getCurrentRoot();
        final RootInfo home = mRoots.getHomeRoot();
        if (null != root && !root.isHome() && null != home) {
            onRootPicked(home, true);
            return true;
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable(EXTRA_STATE, mState);
        state.putBoolean(EXTRA_AUTHENTICATED, mAuthenticated);
        state.putBoolean(EXTRA_ACTIONMODE, mActionMode);
        state.putBoolean(EXTRA_SEARCH_STATE, mSearchResultShown);
        state.putBoolean(EXTRA_SETTINGS, mShowingSettings);
        state.putBoolean(EXTRA_ABOUT, mShowingAbout);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
    }

    private BaseAdapter mStackAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return mState.stack.size();
        }

        @Override
        public DocumentInfo getItem(int position) {
            return mState.stack.get(mState.stack.size() - position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir_title, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = getCurrentRoot();
                title.setText(root.title);
            } else {
                title.setText(doc.displayName);
            }
            title.setTextColor(getTvTitleColor());

            // No padding when shown in actionbar
            //convertView.setPadding(0, 0, 0, 0);
            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_subdir, parent, false);
            }

            final ImageView subdir = (ImageView) convertView.findViewById(R.id.subdir);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final DocumentInfo doc = getItem(position);

            if (position == 0) {
                final RootInfo root = getCurrentRoot();
                title.setText(root.title);
                subdir.setVisibility(View.GONE);
            } else {
                title.setText(doc.displayName);
                subdir.setVisibility(View.VISIBLE);
            }

            return convertView;
        }
    };

    private void showStackPopup(View anchor) {
        final Menu menu = new PopupMenu(this, anchor).getMenu();
        for (int i = 0; i < mStackAdapter.getCount(); i++) {
            final String name = i == 0 ? getCurrentRoot().title : ((DocumentInfo) mStackAdapter.getItem(i)).displayName;
            final MenuItem item = menu.add(0, i, i, name);
            item.setCheckable(true).setChecked(i == mStackAdapter.getCount() - 1);
            if (i > 0) {
                // The arrow steps in one level further for each folder down
                final Drawable arrow = ContextCompat.getDrawable(this, R.drawable.ic_subdirectory_arrow);
                item.setIcon(new InsetDrawable(arrow, (i - 1) * (int) (16 * getResources().getDisplayMetrics().density), 0, 0, 0));
            }
        }
        SideMenuPopup.showBelowStart(anchor, menu, item -> {
            final int position = item.getItemId();
            if (position < mStackAdapter.getCount() - 1) {
                while (mState.stack.size() > position + 1) {
                    mState.stackTouched = true;
                    mState.stack.pop();
                }
                onCurrentDirectoryChanged(ANIM_UP);
            }
            return true;
        });
    }

    private AdapterView.OnItemSelectedListener mStackListener = new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (mIgnoreNextNavigation) {
                mIgnoreNextNavigation = false;
                return;
            }

            while (mState.stack.size() > position + 1) {
                mState.stackTouched = true;
                mState.stack.pop();
            }
            onCurrentDirectoryChanged(ANIM_UP);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // Ignored
        }
    };

    public RootInfo getCurrentRoot() {
        if (mState.stack.root != null) {
            return mState.stack.root;
        } else {
            return mState.action == ACTION_BROWSE ? mRoots.getDefaultRoot() : mRoots.getStorageRoot();
        }
    }
    
    public RootInfo getDownloadRoot() {
    	return mRoots.getDownloadRoot();
    }

    public RootsCache getRoots(){
        return mRoots;
    }

    public DocumentInfo getCurrentDirectory() {
        return mState.stack.peek();
    }

    private String getCallingPackageMaybeExtra() {
        final String extra = getIntent().getStringExtra(DocumentsContract.EXTRA_PACKAGE_NAME);
        return (extra != null) ? extra : getCallingPackage();
    }

    public Executor getCurrentExecutor() {
        final DocumentInfo cwd = getCurrentDirectory();
        if (cwd != null && cwd.authority != null) {
            return ProviderExecutor.forAuthority(cwd.authority);
        } else {
            return AsyncTask.THREAD_POOL_EXECUTOR;
        }
    }

    public State getDisplayState() {
        return mState;
    }
    
    public boolean isCreateSupported() {
        final DocumentInfo cwd = getCurrentDirectory();
        if (mState.action == ACTION_OPEN_TREE) {
            return cwd != null && cwd.isCreateSupported();
        } else if (mState.action == ACTION_CREATE || mState.action == ACTION_GET_CONTENT) {
            return false;
        } else {
            return cwd != null && cwd.isCreateSupported();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public void onCurrentDirectoryChanged(int anim) {
    	//FIX for java.lang.IllegalStateException ("Activity has been destroyed") 
        if(!Utils.isActivityAlive(DocumentsActivity.this)){
            return;
        }
        final FragmentManager fm = getFragmentManager();
        if (mShowingSettings || mShowingAbout) {
            if (mShowingAbout) {
                AboutFragment.show(fm);
            } else {
                SettingsFragment.show(fm);
            }
            final RootsCommonFragment roots = RootsCommonFragment.get(fm);
            if (roots != null) {
                roots.onCurrentRootChanged();
            }
            updateActionBar();
            invalidateMenu();
            return;
        }
        final RootInfo root = getCurrentRoot();
        DocumentInfo cwd = getCurrentDirectory();

        //TODO : this has to be done nicely
        if(cwd == null && (null != root && null != root.authority && !root.isServerStorage())){
	        final Uri uri = DocumentsContract.buildDocumentUri(
	                root.authority, root.documentId);
	        DocumentInfo result;
			try {
				result = DocumentInfo.fromUri(getContentResolver(), uri);
	            if (result != null) {
	                mState.stack.push(result);
	                mState.stackTouched = true;
	                cwd = result;
	            }
			} catch (FileNotFoundException e) {
                CrashReportingManager.logException(e);
			}
        }
        if(!AppSettings.getFolderAnimation(this)){
            anim = 0;
        }

        if (cwd == null) {
            // No directory means recents
        	if (mState.action == ACTION_CREATE || mState.action == ACTION_OPEN_TREE) {
                RecentsCreateFragment.show(fm);
            } else {
                if(null != root && root.isHome()){
                    HomeFragment.show(fm);
                } else if(null != root && root.isConnections()){
                    ConnectionsFragment.show(fm);
                } else if(null != root && root.isQuickShare()){
                    QuickShareFragment.show(fm);
                } else if(null != root && root.isServerStorage()){
                    ServerFragment.show(fm, root);
                } else {
                    DirectoryFragment.showRecentsOpen(fm, anim, root);
                    mState.derivedMode = AppSettings.getViewMode(this);
                }
            }
        } else {
            if (mState.currentSearch != null && mSearchResultShown) {
                // Ongoing search
                DirectoryFragment.showSearch(fm, root, cwd, mState.currentSearch, anim);
                mSearchResultShown = false;
            } else {
                // Normal boring directory
                DirectoryFragment.showNormal(fm, root, cwd, anim);
            }
        }

        // Forget any replacement target
        if (mState.action == ACTION_CREATE) {
            final SaveFragment save = SaveFragment.get(fm);
            if (save != null) {
                save.setReplaceTarget(null);
            }
        }

        if (mState.action == ACTION_OPEN_TREE) {
            final PickFragment pick = PickFragment.get(fm);
            if (pick != null && null != cwd)  {
                    final CharSequence displayName = (mState.stack.size() <= 1) && null != root
                            ? root.title : cwd.displayName;
                pick.setPickTarget(cwd, displayName);
            }
        }

        final MoveFragment move = MoveFragment.get(fm);
        if (move != null) {
            move.setReplaceTarget(cwd);
        }

        final RootsCommonFragment roots = RootsCommonFragment.get(fm);
        if (roots != null) {
            roots.onCurrentRootChanged();
        }

        updateActionBar();
        invalidateMenu();
        dumpStack();
    }

    /**
     * Opens the folder that holds the file at {@code path}, for rows in virtual lists such as
     * Movies whose real location is only known by its path.
     */
    @Override
    public void showInFolder(final String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DocumentStack stack = buildFolderStack(path);
                if (stack == null) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            onStackPicked(stack);
                        }
                    }
                });
            }
        }).start();
    }

    private DocumentStack buildFolderStack(String path) {
        RootInfo best = null;
        for (RootInfo candidate : mRoots.getRootsBlocking()) {
            if (!ExternalStorageProvider.AUTHORITY.equals(candidate.authority)
                    || TextUtils.isEmpty(candidate.path)) {
                continue;
            }
            final String base = candidate.path.endsWith("/") ? candidate.path : candidate.path + "/";
            if (path.startsWith(base) && (best == null || candidate.path.length() > best.path.length())) {
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        final String base = best.path.endsWith("/") ? best.path : best.path + "/";
        final String[] parts = path.substring(base.length()).split("/");
        final DocumentStack stack = new DocumentStack();
        stack.root = best;
        try {
            stack.push(DocumentInfo.fromUri(getContentResolver(),
                    DocumentsContract.buildDocumentUri(best.authority, best.documentId)));
            String relative = "";
            // The last part is the file itself, so the stack ends at its folder
            for (int i = 0; i < parts.length - 1; i++) {
                relative = relative.isEmpty() ? parts[i] : relative + "/" + parts[i];
                stack.push(DocumentInfo.fromUri(getContentResolver(), DocumentsContract.buildDocumentUri(
                        best.authority, best.rootId + ":" + relative)));
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Failed to open folder: " + e);
            return null;
        }
        return stack;
    }

    public void onStackPicked(DocumentStack stack) {
        try {
            // Update the restored stack to ensure we have freshest data
            stack.updateDocuments(getContentResolver());

            mState.stack = stack;
            mState.stackTouched = true;
            onCurrentDirectoryChanged(ANIM_SIDE);

        } catch (FileNotFoundException e) {
            Log.w(TAG, "Failed to restore stack: " + e);
            CrashReportingManager.logException(e);
        }
    }
    public void onRootPicked(RootInfo root, RootInfo parentRoot) {
        mParentRoot = parentRoot;
        onRootPicked(root, true);
    }

    public void onRootPicked(final RootInfo root, final boolean closeDrawer) {

        if(null == root){
            return;
        }
        if (root.isLockedFolder() && !mLockedFolderOpen) {
            askLockedFolderPin(root);
            return;
        }
        if (!root.isLockedFolder()) {
            mLockedFolderOpen = false;
        }
        mShowingSettings = false;
        mShowingAbout = false;
        // Connections to LAN servers need local network access on Android 17+
        if (root.isNetworkStorage() && !root.isServerStorage()
                && !PermissionUtil.hasLocalNetworkPermission(this)) {
            runWithLocalNetwork(new Runnable() {
                @Override
                public void run() {
                    onRootPicked(root, closeDrawer);
                }
            });
            return;
        }
        // Local storage can't be listed without all-files access; ask instead of failing
        if (ExternalStorageProvider.AUTHORITY.equals(root.authority)
                && !PermissionUtil.hasStoragePermission(this)) {
            requestStoragePermissions();
            return;
        }
        // Clear entire backstack and start in new root
        mState.stack.root = root;
        mState.stack.clear();
        mState.stackTouched = true;

        if (RootInfo.isOtherRoot(root) || mRoots.isRecentsRoot(root)) {
            onCurrentDirectoryChanged(ANIM_SIDE);
        } else {
            new PickRootTask(root).executeOnExecutor(getCurrentExecutor());
        }
    }

    private class PickRootTask extends AsyncTask<Void, Void, DocumentInfo> {
        private RootInfo mRoot;

        public PickRootTask(RootInfo root) {
            mRoot = root;
        }

        @Override
        protected DocumentInfo doInBackground(Void... params) {
            try {
                final Uri uri = DocumentsContract.buildDocumentUri(
                        mRoot.authority, mRoot.documentId);
                return DocumentInfo.fromUri(getContentResolver(), uri);
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to find root", e);
                CrashReportingManager.logException(e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(DocumentInfo result) {
            if(!Utils.isActivityAlive(DocumentsActivity.this)){
                return;
            }
            if (result != null) {
                mState.stack.push(result);
                mState.stackTouched = true;
                onCurrentDirectoryChanged(ANIM_SIDE);
            }
        }
    }

    private class UploadFileTask extends AsyncTask<Void, Void, Boolean> {
        private final DocumentInfo mCwd;
        private final String mMimeType;
        private final String mDisplayName;
        private final Uri mUri;

        public UploadFileTask(Uri uri, String name, String mimeType) {
            mCwd = getCurrentDirectory();
            mDisplayName = name;
            mMimeType = mimeType;
            mUri = uri;
        }

        @Override
        protected void onPreExecute() {
            setPending(true);
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            final ContentResolver resolver = getContentResolver();
            ContentProviderClient client = null;
            Boolean hadTrouble = false;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, mCwd.derivedUri.getAuthority());
                hadTrouble = !DocumentsContract.uploadDocument(
                        resolver, mCwd.derivedUri, mUri, mMimeType, mDisplayName);
            } catch (Exception e) {
                Log.w(DocumentsActivity.TAG, "Failed to upload document", e);
                CrashReportingManager.logException(e);
            } finally {
                ContentProviderClientCompat.releaseQuietly(client);
            }

            return hadTrouble;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                Utils.showError(DocumentsActivity.this, R.string.upload_error);
            }
            setPending(false);
        }
    }

    public void onAppPicked(ResolveInfo info) {
        final Intent intent = new Intent(getIntent());
        intent.setFlags(intent.getFlags() & ~Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        intent.setComponent(new ComponentName(
                info.activityInfo.applicationInfo.packageName, info.activityInfo.name));
        startActivityForResult(intent, CODE_FORWARD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult() code=" + resultCode);

        // Only relay back results when not canceled; otherwise stick around to
        // let the user pick another app/backend.
        if (requestCode == CODE_FORWARD && resultCode != RESULT_CANCELED) {

            // Remember that we last picked via external app
            final String packageName = getCallingPackageMaybeExtra();
            final ContentValues values = new ContentValues();
            values.put(ResumeColumns.EXTERNAL, 1);
            getContentResolver().insert(RecentsProvider.buildResume(packageName), values);

            // Pass back result to original caller
            setResult(resultCode, data);
            finish();
        } else if(requestCode == ADD_STORAGE_REQUEST_CODE){
            SAFManager.onActivityResult(this, requestCode, resultCode, data);
        } else if(requestCode == UPLOAD_FILE) {
            if(resultCode == Activity.RESULT_OK) {
                final Uri uri = data.getData();
                final String name = FileUtils.getFilenameFromContentUri(this, uri);
                new UploadFileTask(uri, name,
                        FileUtils.getTypeForName(name)).executeOnExecutor(getCurrentExecutor());
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Switches between the Videos tabs. Back to the root first, so switching doesn't pile up
     * history and BACK from TV Shows or Videos lands on Movies, the root itself.
     *
     * @param groupId the tab's group document, or null for Movies
     */
    public void onVideoTabPicked(String groupId) {
        while (mState.stack.size() > 1) {
            mState.stack.pop();
        }
        if (null != groupId) {
            try {
                mState.stack.push(DocumentInfo.fromUri(getContentResolver(),
                        DocumentsContract.buildDocumentUri(MediaDocumentsProvider.AUTHORITY, groupId)));
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to open video tab " + groupId, e);
            }
        }
        mState.stackTouched = true;
        onCurrentDirectoryChanged(ANIM_DOWN);
    }

    public void onDocumentPicked(DocumentInfo doc) {
        final FragmentManager fm = getFragmentManager();
        if (doc.isDirectory() || DocumentArchiveHelper.isSupportedArchiveType(doc.mimeType)) {
            mState.stack.push(doc);
            mState.stackTouched = true;
            onCurrentDirectoryChanged(ANIM_DOWN);
            final MoveFragment move = MoveFragment.get(fm);
            if (move != null) {
                move.setReplaceTarget(doc);
            }
        } else if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT){
        	// Explicit file picked, return
            new ExistingFinishTask(doc.derivedUri).executeOnExecutor(getCurrentExecutor());
        } else if (mState.action == ACTION_BROWSE) {

            // Fall back to viewing
            final RootInfo rootInfo = getCurrentRoot();
            final Intent view = new Intent(Intent.ACTION_VIEW);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            view.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if(RootInfo.isMedia(rootInfo)){
                view.setDataAndType(MediaDocumentsProvider.getMediaUriForDocumentId(doc.documentId), doc.mimeType);
            } else {
                Uri contentUri = null;
                if((rootInfo.isStorage() || doc.isMedia()) && !TextUtils.isEmpty(doc.path)){
                    contentUri = FileUtils.getContentUriFromFilePath(this, new File(doc.path).getAbsolutePath());
                }
                if(null == contentUri){
                    contentUri = doc.derivedUri;
                }
                view.setDataAndType(contentUri, doc.mimeType);
            }
            if((MimePredicate.mimeMatches(MimePredicate.SPECIAL_MIMES, doc.mimeType)
                    || !Utils.isIntentAvailable(this, view)) && !Utils.hasNougat()){
            	try {
                	File file = new File(doc.path);
    				view.setDataAndType(Uri.fromFile(file), doc.mimeType);
				} catch (Exception e) {
					view.setDataAndType(doc.derivedUri, doc.mimeType);
                    CrashReportingManager.logException(e);
				}
            }

            // Nova always wins over the preview for formats it plays
            if (isPlayable(doc.mimeType, doc.displayName)) {
                view.setClass(this, PlayerActivity.class);
                view.putExtra(PlayerActivity.EXTRA_WATCH_KEY, doc.documentId);
            } else if (PreviewActivity.supports(doc.mimeType, doc.displayName)) {
                view.setClass(this, PreviewActivity.class);
                view.putExtra(PreviewActivity.EXTRA_NAME, doc.displayName);
            }

            if(Utils.isIntentAvailable(this, view)){
                //TODO: This temporarily fixes crash when the Activity that is opened is not
                // exported gives java.lang.SecurityException: Permission Denial:
                try {
                    startActivity(view);
                } catch (Exception e){
                    CrashReportingManager.logException(e);
                }
            }
            else{
                Utils.showError(this, R.string.toast_no_application);
            }
        } else if (mState.action == ACTION_CREATE) {
            // Replace selected file
            // TODO: null pointer crash
            SaveFragment.get(fm).setReplaceTarget(doc);
        } else if (mState.action == ACTION_MANAGE) {
            // First try managing the document; we expect manager to filter
            // based on authority, so we don't grant.
            final Intent manage = new Intent(DocumentsContract.ACTION_MANAGE_DOCUMENT);
            manage.setData(doc.derivedUri);

            if(Utils.isIntentAvailable(this, manage)){
                try {
                    startActivity(manage);
                } catch (ActivityNotFoundException ex) {
                    // Fall back to viewing
                    CrashReportingManager.logException(ex);
                    final Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    view.setData(doc.derivedUri);

                    try {
                        startActivity(view);
                    } catch (ActivityNotFoundException ex2) {
                        Utils.showError(this, R.string.toast_no_application);
                        CrashReportingManager.logException(ex2);
                    }
                }
            }
            else{
                Utils.showError(this, R.string.toast_no_application);
            }
        }
    }
	
    public void onDocumentsPicked(List<DocumentInfo> docs) {
        if (mState.action == ACTION_OPEN || mState.action == ACTION_GET_CONTENT || mState.action == ACTION_BROWSE) {
            final int size = docs.size();
            final Uri[] uris = new Uri[size];
            for (int i = 0; i < size; i++) {
                uris[i] = docs.get(i).derivedUri;
            }
            new ExistingFinishTask(uris).executeOnExecutor(getCurrentExecutor());
        }
    }

    public void onSaveRequested(DocumentInfo replaceTarget) {
        new ExistingFinishTask(replaceTarget.derivedUri).executeOnExecutor(getCurrentExecutor());
    }

    public void onSaveRequested(String mimeType, String displayName) {
        new CreateFinishTask(mimeType, displayName).executeOnExecutor(getCurrentExecutor());
    }

    public void onPickRequested(DocumentInfo pickTarget) {
        final Uri viaUri = DocumentsContract.buildTreeDocumentUri(pickTarget.authority,
                pickTarget.documentId);
        new PickFinishTask(viaUri).executeOnExecutor(getCurrentExecutor());
    }

    public void onMoveRequested(ArrayList<DocumentInfo> docs, DocumentInfo toDoc, boolean deleteAfter) {
    	new MoveTask(docs, toDoc, deleteAfter).executeOnExecutor(getCurrentExecutor());
    }

    private void saveStackBlocking() {
        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(mState.stack);
        if (mState.action == ACTION_CREATE || mState.action == ACTION_OPEN_TREE) {
            // Remember stack for last create
            values.clear();
            values.put(RecentColumns.KEY, mState.stack.buildKey());
            values.put(RecentColumns.STACK, rawStack);
            resolver.insert(RecentsProvider.buildRecent(), values);
        }

        // Remember location for next app launch
        final String packageName = getCallingPackageMaybeExtra();
        values.clear();
        values.put(ResumeColumns.STACK, rawStack);
        values.put(ResumeColumns.EXTERNAL, 0);
        resolver.insert(RecentsProvider.buildResume(packageName), values);
    }

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void onFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mState.acceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            if(Utils.hasJellyBean()){
                intent.setClipData(clipData);	
            }
            else{
            	intent.setData(uris[0]);
            }
        }

        if (mState.action == DocumentsActivity.State.ACTION_GET_CONTENT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (mState.action == ACTION_OPEN_TREE) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        } else {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }

        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    private class CreateFinishTask extends AsyncTask<Void, Void, Uri> {
        private final String mMimeType;
        private final String mDisplayName;

        public CreateFinishTask(String mimeType, String displayName) {
            mMimeType = mimeType;
            mDisplayName = displayName;
        }

        @Override
        protected void onPreExecute() {
            setPending(true);
        }

        @Override
        protected Uri doInBackground(Void... params) {
            final ContentResolver resolver = getContentResolver();
            final DocumentInfo cwd = getCurrentDirectory();

            ContentProviderClient client = null;
            Uri childUri = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, cwd.derivedUri.getAuthority());
                childUri = DocumentsContract.createDocument(
                		resolver, cwd.derivedUri, mMimeType, mDisplayName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create document", e);
                CrashReportingManager.logException(e);
            } finally {
            	ContentProviderClientCompat.releaseQuietly(client);
            }

            if (childUri != null) {
                saveStackBlocking();
            }

            return childUri;
        }

        @Override
        protected void onPostExecute(Uri result) {
            if(!Utils.isActivityAlive(DocumentsActivity.this)){
                return;
            }
            if (result != null) {
                onFinished(result);
            } else {
                final DocumentInfo cwd = getCurrentDirectory();
                if(!isSAFIssue(cwd.documentId)) {
                    Utils.showError(DocumentsActivity.this, R.string.save_error);
                }
            }
            setPending(false);
        }
    }

    private class ExistingFinishTask extends AsyncTask<Void, Void, Void> {
		private final Uri[] mUris;

        public ExistingFinishTask(Uri... uris) {
            mUris = uris;
        }

        @Override
        protected Void doInBackground(Void... params) {
            saveStackBlocking();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            onFinished(mUris);
        }
    }

    private class PickFinishTask extends android.os.AsyncTask<Void, Void, Void> {
        private final Uri mUri;

        public PickFinishTask(Uri uri) {
            mUri = uri;
        }

        @Override
        protected Void doInBackground(Void... params) {
            saveStackBlocking();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            onFinished(mUri);
        }
    }
    
    private void emptyRecycleBin() {
        final DialogBuilder builder = new DialogBuilder(this);
        builder.setMessage(R.string.empty_bin_confirm)
                .setPositiveButton(R.string.menu_empty_bin, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new EmptyRecycleBinTask().execute();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        builder.showDialog();
    }

    private class EmptyRecycleBinTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Void... params) {
            return ExternalStorageProvider.callRecycleBin(DocumentsActivity.this,
                    ExternalStorageProvider.METHOD_EMPTY_BIN, null);
        }

        @Override
        protected void onPostExecute(Boolean emptied) {
            if (!emptied && Utils.isActivityAlive(DocumentsActivity.this)) {
                Utils.showError(DocumentsActivity.this, R.string.toast_failed_delete);
            }
        }
    }

    private class MoveTask extends AsyncTask<Void, Void, Boolean> {
        private final DocumentInfo toDoc;
        private final ArrayList<DocumentInfo> docs;
		private boolean deleteAfter;

        public MoveTask(ArrayList<DocumentInfo> docs, DocumentInfo toDoc, boolean deleteAfter) {
            this.docs = docs;
            this.toDoc = toDoc;
            this.deleteAfter = deleteAfter;
        }

        @Override
        protected void onPreExecute() {
        	setMovePending(true);
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            final ContentResolver resolver = getContentResolver();
            final DocumentInfo cwd = null == toDoc ? getCurrentDirectory() : toDoc;

			boolean hadTrouble = false;
			final DocumentCopier copier = new DocumentCopier(resolver);
    		for (DocumentInfo doc : docs) {
    			try {
                    if (!DocumentCopier.copiesItself(doc, cwd, deleteAfter)) {
                        // Another storage, so the files are read and written here
                        hadTrouble |= !copier.copy(doc, cwd.derivedUri, deleteAfter);
                    } else if(deleteAfter) {
                        hadTrouble |= DocumentsContract.moveDocument(resolver, doc.derivedUri, null,
                                cwd.derivedUri) == null;
                    } else {
                        hadTrouble |= DocumentsContract.copyDocument(resolver, doc.derivedUri,
                                cwd.derivedUri) == null;
                    }
    			} catch (Exception e) {
    				Log.w(TAG, "Failed to move " + doc);
    				hadTrouble = true;
                    CrashReportingManager.logException(e);
    			}
    		}

            Bundle params2 = new Bundle();
            params2.putBoolean(FILE_MOVE, deleteAfter);
            params2.putInt(FILE_COUNT, docs.size());
            AnalyticsManager.logEvent("files_moved", params2);

            return hadTrouble;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if(!Utils.isActivityAlive(DocumentsActivity.this)){
                return;
            }
            if (result){
                //if(!isSAFIssue(toDoc.documentId)){
                Utils.showError(DocumentsActivity.this, R.string.save_error);
                //}
            }
            MoveFragment.hide(getFragmentManager());
            setMovePending(false);
            refreshData();
        }
    }

    public void setMovePending(boolean pending) {
        final MoveFragment move = MoveFragment.get(getFragmentManager());
        if (move != null) {
            move.setPending(pending);
        }
    }

    private void dumpStack() {
        Log.d(TAG, "Current stack: ");
        Log.d(TAG, " * " + mState.stack.root);
        for (DocumentInfo doc : mState.stack) {
            Log.d(TAG, " +-- " + doc);
        }
    }

    public static DocumentsActivity get(Fragment fragment) {
        return (DocumentsActivity) fragment.getActivity();
    }

	public boolean getActionMode() {
		return mActionMode;
	}

	public void setActionMode(boolean actionMode) {
		mActionMode = actionMode;
        // TV keeps its toolbar and shows the selection in it (ToolbarActionMode)
        mToolbar.setVisibility(actionMode && null == mToolbarActionMode ? View.INVISIBLE : View.VISIBLE);
	}

    private ToolbarActionMode mToolbarActionMode;

    @Override
    public ActionMode startSupportActionMode(@NonNull ActionMode.Callback callback) {
        if (null != mToolbarActionMode) {
            mToolbarActionMode.finish();
        }
        mToolbarActionMode = new ToolbarActionMode(callback);
        mToolbarActionMode.start();
        return mToolbarActionMode;
    }

    /**
     * Selection mode on TV: instead of overlaying a contextual bar, the toolbar itself turns
     * into the selection bar, with every action shown in it rather than in an overflow menu.
     */
    private class ToolbarActionMode extends ActionMode {
        // Bar order: open, then edit actions, then sharing, then details; Delete always goes last
        private final int[] ACTION_ORDER = {
                R.id.menu_open, R.id.menu_restore, R.id.menu_select_all, R.id.menu_copy, R.id.menu_cut, R.id.menu_lock, R.id.menu_rename,
                R.id.menu_compress, R.id.menu_open_with, R.id.menu_quickshare, R.id.menu_bookmark, R.id.menu_info,
        };
        private final ActionMode.Callback callback;
        private CharSequence title;
        private CharSequence subtitle;
        private View customView;
        private int onColor;
        private Drawable toolbarBackground;

        ToolbarActionMode(ActionMode.Callback callback) {
            this.callback = callback;
        }

        void start() {
            final TypedArray a = obtainStyledAttributes(new int[]{
                    androidx.appcompat.R.attr.actionModeBackground,
                    com.google.android.material.R.attr.colorOnPrimaryContainer});
            final Drawable background = a.getDrawable(0);
            onColor = a.getColor(1, Color.WHITE);
            a.recycle();
            toolbarBackground = mToolbar.getBackground();
            getSupportActionBar().setBackgroundDrawable(background);
            mToolbar.setTitleTextColor(onColor);
            mToolbarStack.setVisibility(View.GONE);
            mToolbarStack.setAdapter(null);
            final Drawable close = ContextCompat.getDrawable(DocumentsActivity.this, R.drawable.ic_action_close).mutate();
            close.setTint(onColor);
            mToolbar.setNavigationIcon(close);
            mToolbar.setNavigationContentDescription(android.R.string.cancel);
            mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            supportInvalidateOptionsMenu();
        }

        boolean onCreateMenu(Menu menu) {
            // Re-creating the menu resets the title; keep the "N selected" one
            final CharSequence currentTitle = title;
            final boolean created = callback.onCreateActionMode(this, menu);
            if (null != currentTitle) {
                setTitle(currentTitle);
            }
            flattenSubMenus(menu);
            styleItems(menu);
            return created;
        }

        /**
         * Lifts submenu entries (e.g. Edit: rename, copy, cut...) into the bar itself and puts the
         * actions in a fixed order, with Delete last so it sits apart from the others.
         */
        private void flattenSubMenus(Menu menu) {
            final List<MenuItem> items = new ArrayList<>();
            for (int i = 0; i < menu.size(); i++) {
                final MenuItem item = menu.getItem(i);
                if (item.hasSubMenu()) {
                    for (int j = 0; j < item.getSubMenu().size(); j++) {
                        items.add(item.getSubMenu().getItem(j));
                    }
                } else {
                    items.add(item);
                }
            }
            final List<Object[]> entries = new ArrayList<>();
            for (MenuItem item : items) {
                entries.add(new Object[]{item.getItemId(), item.getTitle(), item.getIcon(), item.isVisible()});
            }
            // Stable sort: actions not listed in ACTION_ORDER keep their place, after the listed ones
            Collections.sort(entries, (a, b) -> actionRank((Integer) a[0]) - actionRank((Integer) b[0]));
            menu.clear();
            for (Object[] entry : entries) {
                menu.add(Menu.NONE, (Integer) entry[0], Menu.NONE, (CharSequence) entry[1])
                        .setIcon((Drawable) entry[2])
                        .setVisible((Boolean) entry[3]);
            }
        }

        private int actionRank(int id) {
            for (int i = 0; i < ACTION_ORDER.length; i++) {
                if (ACTION_ORDER[i] == id) {
                    return i;
                }
            }
            return id == R.id.menu_delete ? Integer.MAX_VALUE : ACTION_ORDER.length;
        }

        boolean onPrepareMenu(Menu menu) {
            final boolean prepared = callback.onPrepareActionMode(this, menu);
            styleItems(menu);
            return prepared;
        }

        boolean onItemClicked(MenuItem item) {
            return callback.onActionItemClicked(this, item);
        }

        private void styleItems(Menu menu) {
            final ColorStateList tint = ColorStateList.valueOf(onColor);
            final ColorStateList deleteTint = ColorStateList.valueOf(
                    ContextCompat.getColor(DocumentsActivity.this, R.color.action_delete));
            for (int i = 0; i < menu.size(); i++) {
                final MenuItem item = menu.getItem(i);
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                MenuItemCompat.setIconTintList(item, item.getItemId() == R.id.menu_delete ? deleteTint : tint);
            }
        }

        @Override
        public void setTitle(CharSequence title) {
            this.title = title;
            mToolbar.setTitle(title);
        }

        @Override
        public void setTitle(int resId) {
            setTitle(getString(resId));
        }

        @Override
        public void setSubtitle(CharSequence subtitle) {
            this.subtitle = subtitle;
            mToolbar.setSubtitle(subtitle);
        }

        @Override
        public void setSubtitle(int resId) {
            setSubtitle(getString(resId));
        }

        @Override
        public void setCustomView(View view) {
            customView = view;
        }

        @Override
        public void invalidate() {
            supportInvalidateOptionsMenu();
        }

        @Override
        public void finish() {
            if (mToolbarActionMode != this) {
                return;
            }
            mToolbarActionMode = null;
            callback.onDestroyActionMode(this);
            mToolbar.setSubtitle(null);
            mToolbar.setNavigationIcon(null);
            mToolbar.setTitleTextAppearance(DocumentsActivity.this,
                    R.style.TextAppearance_AppCompat_Widget_ActionBar_Title);
            applyTvTitleColor();
            mToolbar.setBackground(toolbarBackground);
            updateActionBar();
            supportInvalidateOptionsMenu();
        }

        @Override
        public Menu getMenu() {
            return mToolbar.getMenu();
        }

        @Override
        public CharSequence getTitle() {
            return title;
        }

        @Override
        public CharSequence getSubtitle() {
            return subtitle;
        }

        @Override
        public View getCustomView() {
            return customView;
        }

        @Override
        public MenuInflater getMenuInflater() {
            return DocumentsActivity.this.getMenuInflater();
        }
    }

    @Override
    public void invalidateMenu(){
        supportInvalidateOptionsMenu();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void setUpStatusBar() {
        ThemeColors.setUpStatusBar(this);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void setUpDefaultStatusBar() {
        int color = ContextCompat.getColor(this, R.color.alertColor);
        if(Utils.hasLollipop()){
            getWindow().setStatusBarColor(color);
        }
        else if(Utils.hasKitKat()){
            SystemBarTintManager systemBarTintManager = new SystemBarTintManager(this);
            systemBarTintManager.setTintColor(Utils.getStatusBarColor(color));
            systemBarTintManager.setStatusBarTintEnabled(true);
        }
    }

    public static int getStatusBarHeight(Context context) {
        int result = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = context.getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    private boolean showActionMenu() {
        final RootInfo root = getCurrentRoot();
        // Network shares that can make documents (WebDAV) say so on their folders
        return (!RootInfo.isOtherRoot(root) || null != root && root.isNetworkStorage()) &&
                isCreateSupported() &&
                null != root
                && !root.isStarred()
                && mState.currentSearch == null;
    }

}