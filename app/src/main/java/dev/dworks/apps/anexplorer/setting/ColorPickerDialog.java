/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.dworks.apps.anexplorer.setting;

import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.ColorPalette;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.ui.LineColorPicker;

import static dev.dworks.apps.anexplorer.common.DialogFragment.tintButtons;

/** Picks the primary color (a hue, then a shade) or the accent color, then tells {@link SettingsFragment}. */
public class ColorPickerDialog extends DialogFragment {
    public static final int TYPE_PRIMARY = 0;
    public static final int TYPE_ACCENT = 1;

    private static final String TAG = "ColorPickerDialog";
    private static final String EXTRA_TYPE = "color_type";

    private LineColorPicker mColorPicker;
    private LineColorPicker mShadePicker;
    private TextView mTitle;

    public static void show(FragmentManager fm, int colorType) {
        final ColorPickerDialog dialog = new ColorPickerDialog();
        final Bundle args = new Bundle();
        args.putInt(EXTRA_TYPE, colorType);
        dialog.setArguments(args);
        dialog.show(fm, TAG);
    }

    private int getColorType() {
        return getArguments().getInt(EXTRA_TYPE, TYPE_PRIMARY);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getDialog().setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                try {
                    tintButtons(getDialog());
                } catch (Exception e) {
                    CrashReportingManager.logException(e);
                }
            }
        });
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();
        final int colorType = getColorType();

        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        final View rootView = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.layout_color_preference, null, false);

        mColorPicker = (LineColorPicker) rootView.findViewById(R.id.color_picker);
        mShadePicker = (LineColorPicker) rootView.findViewById(R.id.shade_picker);
        mTitle = (TextView) rootView.findViewById(R.id.title);

        int color = AppSettings.getPrimaryColor();
        if (colorType == TYPE_PRIMARY) {
            mTitle.setText(R.string.primary_color);
            mColorPicker.setColors(ColorPalette.getBaseColors(context));
            for (int i : mColorPicker.getColors()) {
                for (int j : ColorPalette.getColors(context, i)) {
                    if (j == color) {
                        mColorPicker.setSelectedColor(i);
                        mShadePicker.setColors(ColorPalette.getColors(context, i));
                        mShadePicker.setSelectedColor(j);
                        break;
                    }
                }
            }
        } else {
            mTitle.setText(R.string.accent_color);
            mColorPicker.setColors(ColorPalette.getAccentColors(context));
            mShadePicker.setVisibility(View.GONE);
            color = AppSettings.getAccentColor();
            mColorPicker.setSelectedColor(color);
        }

        mTitle.setBackgroundColor(color);
        mColorPicker.setOnColorChangedListener(new LineColorPicker.OnColorChangedListener() {
            @Override
            public void onColorChanged(int c) {
                mTitle.setBackgroundColor(c);
                if (colorType == TYPE_PRIMARY) {
                    mShadePicker.setColors(ColorPalette.getColors(context, mColorPicker.getColor()));
                    mShadePicker.setSelectedColor(mColorPicker.getColor());
                }
            }
        });
        mShadePicker.setOnColorChangedListener(new LineColorPicker.OnColorChangedListener() {
            @Override
            public void onColorChanged(int c) {
                mTitle.setBackgroundColor(c);
            }
        });

        builder.setView(rootView);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (colorType == TYPE_PRIMARY) {
                    AppSettings.setPrimaryColor(mShadePicker.getColor());
                    AppSettings.setAccentColor(ColorPalette.getAccentColor(context, mColorPicker.getColor()));
                } else {
                    AppSettings.setAccentColor(mColorPicker.getColor());
                }
                // Gone before the activity is rebuilt for the new colors, or it would come back with it
                final FragmentManager fm = getFragmentManager();
                dismiss();
                fm.executePendingTransactions();
                final SettingsFragment settings = SettingsFragment.get(fm);
                if (settings != null) {
                    settings.onColorPicked(colorType);
                }
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        return builder.create();
    }
}
