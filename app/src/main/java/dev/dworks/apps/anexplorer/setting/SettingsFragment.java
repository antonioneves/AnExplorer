package dev.dworks.apps.anexplorer.setting;

import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.common.RootsCommonFragment;
import dev.dworks.apps.anexplorer.misc.PinViewHelper;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.provider.ExternalStorageProvider;

import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_ADVANCED_DEVICES;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_FILE_HIDDEN;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_RECYCLE_BIN;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_FILE_SIZE;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_FILE_THUMBNAIL;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_FOLDER_ANIMATIONS;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_FOLDER_SIZE;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_LOCKED_FOLDER;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_PIN_ENABLED;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_PIN_SET;
import static dev.dworks.apps.anexplorer.setting.AppSettings.KEY_RECENT_MEDIA;

/**
 * Settings, shown in place of the file list (a tab of the main screen rather than a screen of its own):
 * one card per section, each row a D-pad target that flips its switch or opens a picker.
 */
public class SettingsFragment extends Fragment {
    public static final String TAG = "SettingsFragment";

    private ViewGroup mList;
    private final Map<String, MaterialSwitch> mSwitches = new HashMap<>();
    private final Map<String, Boolean> mSwitchDefaults = new HashMap<>();
    private View mPinSetRow;

    public static void show(FragmentManager fm) {
        // Kept when already there, so a recreated activity keeps its scroll position and focus
        if (get(fm) != null) {
            return;
        }
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, new SettingsFragment(), TAG);
        ft.commitAllowingStateLoss();
    }

    public static SettingsFragment get(FragmentManager fm) {
        return (SettingsFragment) fm.findFragmentByTag(TAG);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_settings, container, false);
        mList = (ViewGroup) view.findViewById(R.id.settings_list);
        buildRows(inflater);
        return view;
    }

    @Override
    public void onViewStateRestored(Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        // Every row's switch has the same id, so when the activity is rebuilt (e.g. for a new
        // theme) they all get one switch's saved state; the preferences hold the real values
        for (Map.Entry<String, MaterialSwitch> entry : mSwitches.entrySet()) {
            entry.getValue().setChecked(prefs().getBoolean(entry.getKey(), mSwitchDefaults.get(entry.getKey())));
        }
    }

    private void buildRows(LayoutInflater inflater) {
        final Context context = getActivity();
        mList.removeAllViews();

        ViewGroup section = addSection(inflater, R.string.pref_header_general);
        addSwitch(inflater, section, KEY_FILE_SIZE, R.drawable.ic_settings_file, R.string.pref_file_size,
                R.string.settings_file_size_summary, AppSettings.getDisplayFileSize(context));
        addSwitch(inflater, section, KEY_FOLDER_SIZE, R.drawable.ic_root_folder, R.string.pref_folder_size,
                R.string.settings_folder_size_summary, AppSettings.getDisplayFolderSize(context));
        addSwitch(inflater, section, KEY_FILE_THUMBNAIL, R.drawable.ic_root_image, R.string.pref_thumbnail,
                R.string.settings_thumbnail_summary, AppSettings.getDisplayFileThumbnail(context));
        addSwitch(inflater, section, KEY_FILE_HIDDEN, R.drawable.ic_settings_visibility, R.string.pref_hidden,
                R.string.settings_hidden_summary, AppSettings.getDisplayFileHidden(context));
        addSwitch(inflater, section, KEY_RECENT_MEDIA, R.drawable.ic_root_recent, R.string.pref_recent_media,
                R.string.settings_recent_media_summary, AppSettings.getDisplayRecentMedia());
        addSwitch(inflater, section, KEY_RECYCLE_BIN, R.drawable.ic_root_recycle_bin, R.string.pref_recycle_bin,
                R.string.settings_recycle_bin_summary, AppSettings.isRecycleBinEnabled(context));

        section = addSection(inflater, R.string.pref_header_theme);
        addThemeStyle(inflater, section);
        addColor(inflater, section, ColorPickerDialog.TYPE_PRIMARY, R.string.pref_foreground_color_title,
                R.string.settings_primary_color_summary, AppSettings.getPrimaryColor(context));
        addColor(inflater, section, ColorPickerDialog.TYPE_ACCENT, R.string.pref_accent_color_title,
                R.string.settings_accent_color_summary, AppSettings.getAccentColor());

        section = addSection(inflater, R.string.pref_header_security);
        addSwitch(inflater, section, KEY_PIN_ENABLED, R.drawable.ic_settings_lock, R.string.pref_title_pin_enable,
                R.string.pref_description_pin_enable,
                prefs().getBoolean(KEY_PIN_ENABLED, false));
        addSwitch(inflater, section, KEY_LOCKED_FOLDER, R.drawable.ic_root_locked_folder, R.string.pref_locked_folder,
                R.string.settings_locked_folder_summary, AppSettings.isLockedFolderEnabled(context));
        mPinSetRow = addRow(inflater, section, R.drawable.ic_settings_pin, R.string.pref_title_pin_set, 0);
        mPinSetRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppSettings.logSettingEvent(KEY_PIN_SET);
                checkPin();
            }
        });
        updatePinSetRow();

        section = addSection(inflater, R.string.pref_header_quickshare);
        final View folderRow = addRow(inflater, section, R.drawable.ic_root_quickshare,
                R.string.pref_quickshare_folder, 0);
        final TextView folderSummary = (TextView) folderRow.findViewById(android.R.id.summary);
        folderSummary.setText(AppSettings.getFolderLabel(context, AppSettings.getQuickShareFolder(context)));
        folderRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppSettings.logSettingEvent(AppSettings.KEY_QUICKSHARE_FOLDER);
                FolderPickerDialog.show(getActivity(), AppSettings.getQuickShareFolder(getActivity()),
                        folder -> {
                            AppSettings.setQuickShareFolder(getActivity(), folder);
                            folderSummary.setText(AppSettings.getFolderLabel(getActivity(),
                                    AppSettings.getQuickShareFolder(getActivity())));
                        });
            }
        });

        section = addSection(inflater, R.string.pref_header_advanced);
        addSwitch(inflater, section, KEY_ADVANCED_DEVICES, R.drawable.ic_root_hdd, R.string.pref_advanced_devices,
                R.string.settings_advanced_devices_summary, AppSettings.getDisplayAdvancedDevices(context));
        addSwitch(inflater, section, KEY_FOLDER_ANIMATIONS, R.drawable.ic_settings_animation,
                R.string.pref_folder_animations, R.string.settings_folder_animations_summary,
                AppSettings.getFolderAnimation(context));

        // The D-pad stops at the ends of the list instead of jumping into the sidebar
        final ViewGroup first = (ViewGroup) mList.getChildAt(0).findViewById(R.id.settings_rows);
        final View firstRow = first.getChildAt(0);
        stopFocus(firstRow, View.FOCUS_UP);
        // Back at the first row, show the top of the page too, with the first section's title
        firstRow.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    ((View) mList.getParent()).scrollTo(0, 0);
                }
            }
        });
        stopFocus(section.getChildAt(section.getChildCount() - 1), View.FOCUS_DOWN);
    }

    private static void stopFocus(View row, int direction) {
        row.setId(View.generateViewId());
        if (direction == View.FOCUS_UP) {
            row.setNextFocusUpId(row.getId());
        } else {
            row.setNextFocusDownId(row.getId());
        }
    }

    private ViewGroup addSection(LayoutInflater inflater, int titleId) {
        final View section = inflater.inflate(R.layout.item_settings_section, mList, false);
        ((TextView) section.findViewById(android.R.id.title)).setText(titleId);
        mList.addView(section);
        return (ViewGroup) section.findViewById(R.id.settings_rows);
    }

    private View addRow(LayoutInflater inflater, ViewGroup section, int iconId, int titleId, int summaryId) {
        final View row = inflater.inflate(R.layout.item_setting, section, false);
        ((ImageView) row.findViewById(android.R.id.icon)).setImageResource(iconId);
        ((TextView) row.findViewById(android.R.id.title)).setText(titleId);
        if (summaryId != 0) {
            ((TextView) row.findViewById(android.R.id.summary)).setText(summaryId);
        }
        section.addView(row);
        return row;
    }

    private void addSwitch(LayoutInflater inflater, ViewGroup section, final String key, int iconId,
                           int titleId, int summaryId, boolean checked) {
        final View row = addRow(inflater, section, iconId, titleId, summaryId);
        final MaterialSwitch toggle = (MaterialSwitch) row.findViewById(R.id.toggle);
        toggle.setVisibility(View.VISIBLE);
        toggle.setChecked(checked);
        mSwitches.put(key, toggle);
        mSwitchDefaults.put(key, checked);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean value = !toggle.isChecked();
                toggle.setChecked(value);
                prefs().edit().putBoolean(key, value).apply();
                AppSettings.logSettingEvent(key);
                onSwitchChanged(key, value);
            }
        });
    }

    private void addColor(LayoutInflater inflater, ViewGroup section, final int colorType, int titleId,
                          int summaryId, int color) {
        final View row = addRow(inflater, section, R.drawable.ic_settings_palette, titleId, summaryId);
        final ImageView swatch = (ImageView) row.findViewById(R.id.swatch);
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        circle.setStroke(Utils.dpToPx(2), MaterialColors.getColor(swatch, R.attr.colorOutline));
        swatch.setImageDrawable(circle);
        swatch.setVisibility(View.VISIBLE);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppSettings.logSettingEvent(colorType == ColorPickerDialog.TYPE_PRIMARY
                        ? AppSettings.KEY_PRIMARY_COLOR : AppSettings.KEY_ACCENT_COLOR);
                ColorPickerDialog.show(getFragmentManager(), colorType);
            }
        });
    }

    private void addThemeStyle(LayoutInflater inflater, ViewGroup section) {
        final View row = addRow(inflater, section, R.drawable.ic_settings_theme, R.string.pref_theme_style_title, 0);
        final String[] names = getResources().getStringArray(R.array.theme_style_names);
        final String[] values = getResources().getStringArray(R.array.theme_style_values);
        final int current = Math.max(0, Arrays.asList(values).indexOf(AppSettings.getThemeStyle()));
        ((TextView) row.findViewById(android.R.id.summary)).setText(names[current]);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppSettings.logSettingEvent(AppSettings.KEY_THEME_STYLE);
                new MaterialAlertDialogBuilder(getActivity())
                        .setTitle(R.string.pref_theme_style_title)
                        .setSingleChoiceItems(names, current, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                if (which != current) {
                                    AppSettings.setThemeStyle(Integer.parseInt(values[which]));
                                    // Like the colors, the theme applies to the whole app
                                    getActivity().recreate();
                                }
                            }
                        })
                        .show();
            }
        });
    }

    private void onSwitchChanged(String key, boolean value) {
        final DocumentsActivity activity = (DocumentsActivity) getActivity();
        switch (key) {
            case KEY_FILE_SIZE:
            case KEY_FOLDER_SIZE:
            case KEY_FILE_THUMBNAIL:
            case KEY_FILE_HIDDEN:
                activity.loadDisplaySettings();
                break;
            case KEY_RECYCLE_BIN:
                // Shows or hides the bin in the sidebar
                RootsCache.updateRoots(activity, ExternalStorageProvider.AUTHORITY);
                break;
            case KEY_ADVANCED_DEVICES:
                // Changes which storage the sidebar lists
                final RootsCommonFragment roots = RootsCommonFragment.get(getFragmentManager());
                if (roots != null) {
                    roots.reloadRoots();
                }
                break;
            case KEY_PIN_ENABLED:
                updatePinSetRow();
                // Turning protection on without a PIN would protect nothing; ask for one right away,
                // and leave it off when that's cancelled
                if (value && !AppSettings.isPinProtected(activity)) {
                    setPin(new Runnable() {
                        @Override
                        public void run() {
                            turnOff(KEY_PIN_ENABLED);
                        }
                    });
                }
                break;
            case KEY_LOCKED_FOLDER:
                updatePinSetRow();
                if (value && !AppSettings.isPinProtected(activity)) {
                    setPin(new Runnable() {
                        @Override
                        public void run() {
                            turnOff(KEY_LOCKED_FOLDER);
                        }
                    }, R.string.locked_folder_set_pin);
                }
                // Shows or hides it in the sidebar and on Home; its files stay where they are
                RootsCache.updateRoots(activity, ExternalStorageProvider.AUTHORITY);
                break;
        }
    }

    /** The colors theme the whole app, so the activity is rebuilt; it reopens on this tab. */
    void onColorPicked(int colorType) {
        getActivity().recreate();
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(getActivity());
    }

    private void turnOff(String key) {
        mSwitches.get(key).setChecked(false);
        prefs().edit().putBoolean(key, false).apply();
        onSwitchChanged(key, false);
    }

    private void updatePinSetRow() {
        // The locked folder uses the same PIN
        final boolean enabled = prefs().getBoolean(KEY_PIN_ENABLED, false)
                || AppSettings.isLockedFolderEnabled(getActivity());
        mPinSetRow.setEnabled(enabled);
        mPinSetRow.setFocusable(enabled);
        mPinSetRow.setAlpha(enabled ? 1f : 0.38f);
        ((TextView) mPinSetRow.findViewById(android.R.id.summary)).setText(
                AppSettings.isPinProtected(getActivity()) ? R.string.pin_set : R.string.pin_disabled);
    }

    /** Cancelling either step runs onCancelled, when given, unless a PIN was already set */
    private void confirmPin(final String pin, final Runnable onCancelled) {
        final Dialog d = new Dialog(getActivity(), R.style.DocumentsTheme_DailogPIN);
        d.getWindow().setWindowAnimations(R.style.DialogEnterNoAnimation);
        PinViewHelper pinViewHelper = new PinViewHelper(LayoutInflater.from(getActivity()), null, null) {
            public void onEnter(String password) {
                super.onEnter(password);
                if (pin.equals(password)) {
                    AppSettings.setPin(getActivity(), password);
                    updatePinSetRow();
                    if (password != null && password.length() > 0) {
                        showSnackbar(R.string.pin_set);
                        setInstruction(R.string.pin_set);
                    }
                    d.dismiss();
                    return;
                }
                showSnackbar(R.string.pin_mismatch);
                setInstruction(R.string.pin_mismatch);
            }

            public void onCancel() {
                super.onCancel();
                d.dismiss();
                onPinCancelled(onCancelled);
            }
        };
        View view = pinViewHelper.getView();
        view.findViewById(R.id.logo).setVisibility(View.GONE);
        pinViewHelper.setInstruction(R.string.confirm_pin);
        d.setContentView(view);
        d.show();
    }

    private void onPinCancelled(Runnable onCancelled) {
        if (null != onCancelled && Utils.isActivityAlive(getActivity())
                && !AppSettings.isPinProtected(getActivity())) {
            onCancelled.run();
        }
    }

    private void setPin(final Runnable onCancelled) {
        setPin(onCancelled, R.string.enter_pin);
    }

    private void setPin(final Runnable onCancelled, int instructionId) {
        final Dialog d = new Dialog(getActivity(), R.style.DocumentsTheme_DailogPIN);
        d.getWindow().setWindowAnimations(R.style.DialogExitNoAnimation);
        final PinViewHelper pinViewHelper = new PinViewHelper(LayoutInflater.from(getActivity()), null, null) {
            public void onEnter(String password) {
                super.onEnter(password);
                confirmPin(password, onCancelled);
                d.dismiss();
            }

            public void onCancel() {
                super.onCancel();
                d.dismiss();
                onPinCancelled(onCancelled);
            }
        };
        pinViewHelper.setInstruction(instructionId);
        final View view = pinViewHelper.getView();
        view.findViewById(R.id.logo).setVisibility(View.GONE);
        d.setContentView(view);
        d.show();
    }

    /**
     * With a PIN set, entering it removes it, or changes it while the locked folder needs one;
     * without one, sets a new one.
     */
    private void checkPin() {
        if (AppSettings.isPinProtected(getActivity())) {
            final Dialog d = new Dialog(getActivity(), R.style.DocumentsTheme_DailogPIN);
            View view = new PinViewHelper(LayoutInflater.from(getActivity()), null, null) {
                public void onEnter(String password) {
                    super.onEnter(password);
                    if (AppSettings.checkPin(getActivity(), password)
                            && AppSettings.isLockedFolderEnabled(getActivity())) {
                        d.dismiss();
                        setPin(null);
                        return;
                    }
                    if (AppSettings.checkPin(getActivity(), password)) {
                        AppSettings.setPin(getActivity(), "");
                        updatePinSetRow();
                        showSnackbar(R.string.pin_disabled);
                        setInstruction(R.string.pin_disabled);
                        d.dismiss();
                        return;
                    }
                    showSnackbar(R.string.incorrect_pin);
                    setInstruction(R.string.incorrect_pin);
                }

                public void onCancel() {
                    super.onCancel();
                    d.dismiss();
                }
            }.getView();
            view.findViewById(R.id.logo).setVisibility(View.GONE);
            d.setContentView(view);
            d.show();
        } else {
            setPin(null);
        }
    }

    private void showSnackbar(int msg) {
        if (!Utils.isActivityAlive(getActivity())) {
            return;
        }
        Snackbar.make(getActivity().findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
    }
}
