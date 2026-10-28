package dev.dworks.apps.anexplorer.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.dworks.apps.anexplorer.R;

/**
 * Shows a menu's visible items in a panel beside the anchor instead of dropping down below it,
 * so a long menu fits on a TV screen without scrolling.
 */
public class SideMenuPopup {

    public interface OnMenuItemClickListener {
        boolean onMenuItemClick(MenuItem item);
    }

    private SideMenuPopup() {
    }

    public static PopupWindow show(final View anchor, Menu menu, final OnMenuItemClickListener listener) {
        return show(anchor, null, menu, listener, false);
    }

    /** Drops the panel under the anchor, lined up with its start (for a title that opens a list) */
    public static PopupWindow showBelowStart(final View anchor, Menu menu,
                                             final OnMenuItemClickListener listener) {
        return show(anchor, null, menu, listener, true, true);
    }

    /**
     * @param title  optional heading above the items
     * @param below  drop the panel just under the anchor, lined up with its end (for toolbar
     *               buttons), instead of beside it
     */
    public static PopupWindow show(final View anchor, CharSequence title, Menu menu,
                                   final OnMenuItemClickListener listener, boolean below) {
        return show(anchor, title, menu, listener, below, false);
    }

    private static PopupWindow show(final View anchor, CharSequence title, Menu menu,
                                    final OnMenuItemClickListener listener, boolean below,
                                    boolean alignStart) {
        final Context context = anchor.getContext();
        final List<MenuItem> items = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem item = menu.getItem(i);
            if (item.isVisible()) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return null;
        }

        final ListView list = new ListView(context);
        TextView header = null;
        if (null != title) {
            header = (TextView) LayoutInflater.from(context).inflate(R.layout.item_side_menu_title, list, false);
            header.setText(title);
            list.addHeaderView(header, null, false);
        }
        final MenuAdapter adapter = new MenuAdapter(items);
        list.setAdapter(adapter);
        list.setDivider(null);
        // Rows draw their own focus state (drawable/item_root), like the drawer
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        final int verticalPadding = dp(context, 8);
        list.setPadding(0, verticalPadding, 0, verticalPadding);
        list.setClipToPadding(false);

        // Size the panel to its content
        int width = dp(context, 220);
        int height = 2 * verticalPadding;
        final int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        if (null != header) {
            header.measure(unspecified, unspecified);
            width = Math.max(width, header.getMeasuredWidth());
            height += header.getMeasuredHeight();
        }
        View row = null;
        for (int i = 0; i < adapter.getCount(); i++) {
            row = adapter.getView(i, row, list);
            row.measure(unspecified, unspecified);
            width = Math.max(width, row.getMeasuredWidth());
            height += row.getMeasuredHeight();
        }

        final View root = anchor.getRootView();
        final int margin = dp(context, 16);
        final int gap = dp(context, 8);
        height = Math.min(height, root.getHeight() - 2 * margin);

        final int[] location = new int[2];
        anchor.getLocationInWindow(location);
        final int anchorLeft = location[0];
        final int anchorTop = location[1];
        final int anchorRight = anchorLeft + anchor.getWidth();
        int x;
        int y = anchorTop;
        if (below) {
            x = Math.max(margin, Math.min(alignStart ? anchorLeft : anchorRight - width,
                    root.getWidth() - margin - width));
            y = anchorTop + anchor.getHeight() + gap;
        } else if (anchorRight + gap + width <= root.getWidth() - margin) {
            x = anchorRight + gap;
        } else if (anchorLeft - gap - width >= margin && anchor.getWidth() < root.getWidth() / 2) {
            x = anchorLeft - gap - width;
        } else {
            // Wide list rows: line up with the row's end, just below it
            x = Math.max(margin, anchorRight - width);
            y = anchorTop + anchor.getHeight();
        }
        y = Math.max(margin, Math.min(y, root.getHeight() - margin - height));

        final MaterialShapeDrawable background = new MaterialShapeDrawable(
                ShapeAppearanceModel.builder()
                        .setAllCornerSizes(context.getResources().getDimension(R.dimen.card_corner_radius))
                        .build());
        background.setFillColor(ColorStateList.valueOf(MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorSurfaceContainerHigh)));

        final PopupWindow popup = new PopupWindow(list, width, height, true);
        popup.setBackgroundDrawable(background);
        popup.setElevation(dp(context, 8));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                popup.dismiss();
                listener.onMenuItemClick((MenuItem) parent.getItemAtPosition(position));
            }
        });
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        // Start on the current choice, if the menu has one
        int selection = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isChecked()) {
                selection = i;
                break;
            }
        }
        list.requestFocus();
        list.setSelection(selection + list.getHeaderViewsCount());
        return popup;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static class MenuAdapter extends BaseAdapter {
        private final List<MenuItem> items;

        MenuAdapter(List<MenuItem> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public MenuItem getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).getItemId();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_side_menu, parent, false);
            }
            final MenuItem item = items.get(position);
            final TextView text = (TextView) convertView;
            text.setText(item.getTitle());
            final Context context = parent.getContext();
            final boolean checked = item.isCheckable() && item.isChecked();
            final int iconColor = MaterialColors.getColor(text, checked
                    ? androidx.appcompat.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            Drawable icon = item.getIcon();
            if (null != icon) {
                icon = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(icon, iconColor);
            }
            Drawable check = null;
            if (checked) {
                check = DrawableCompat.wrap(ContextCompat.getDrawable(context, R.drawable.checkmark_white).mutate());
                DrawableCompat.setTint(check, iconColor);
            }
            // Left/right rather than start/end: rows are measured before they are attached, when
            // relative drawables are not resolved yet and would leave the panel too narrow
            final boolean rtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
                    == View.LAYOUT_DIRECTION_RTL;
            text.setCompoundDrawablesWithIntrinsicBounds(rtl ? check : icon, null, rtl ? icon : check, null);
            if (checked) {
                text.setTextColor(iconColor);
            } else {
                text.setTextColor(MaterialColors.getColor(text, android.R.attr.textColorPrimary));
            }
            return convertView;
        }
    }
}
