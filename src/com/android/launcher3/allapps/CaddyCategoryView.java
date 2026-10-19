/*
 * Copyright (C) 2026 The PenguinOS Project
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

package com.android.launcher3.allapps;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DropTarget;
import com.android.launcher3.R;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A compact folder popup for transient drawer categories, without workspace database writes. */
public class CaddyCategoryView extends AbstractFloatingView {
    private final ActivityContext mActivityContext;
    private final Rect mBounds = new Rect();
    private final Rect mTileRect = new Rect();
    private LinearLayout mPanel;
    private FolderIcon mTile;
    private int mColumns;
    private int mRows;
    private final DragController.DragListener mDragListener = new DragController.DragListener() {
        @Override
        public void onDragStart(DropTarget.DragObject dragObject, DragOptions options) {
            close(true);
        }

        @Override
        public void onDragEnd() {
            DragController dc = mActivityContext.getDragController();
            if (dc != null) dc.removeDragListener(this);
        }
    };
    // Closes the popup if its drawer goes away underneath it (e.g. taskbar drawer dismissed).
    private final OnAttachStateChangeListener mTileDetachListener =
            new OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) { }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    // Deferred: the host may be mid-way through detaching its children.
                    MAIN_EXECUTOR.post(() -> {
                        if (!mIsOpen) return;
                        if (isAttachedToWindow()) {
                            close(false);
                        } else {
                            // The whole window is gone; just reset state.
                            mIsOpen = false;
                            restoreTile();
                        }
                    });
                }
            };

    public CaddyCategoryView(Context context) { this(context, null); }

    public CaddyCategoryView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mActivityContext = ActivityContext.lookupContext(context);
    }

    public static CaddyCategoryView show(FolderIcon tile) {
        ActivityContext activity = ActivityContext.lookupContext(tile.getContext());
        // Keep the taskbar drawer open: it is itself a floating view in the taskbar overlay
        // window, and closing it there destroys the whole overlay window before we can add to it.
        AbstractFloatingView.closeAllOpenViewsExcept(activity, false, TYPE_TASKBAR_ALL_APPS);
        CaddyCategoryView view = new CaddyCategoryView(tile.getContext());
        view.populate(tile);
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.ignoreInsets = true;
        activity.getDragLayer().addView(view, lp);
        view.mIsOpen = true;
        // Like a real folder, hide the source tile while open so it doesn't show through.
        tile.setVisibility(INVISIBLE);
        tile.addOnAttachStateChangeListener(view.mTileDetachListener);
        view.mPanel.setAlpha(0f);
        view.mPanel.setScaleX(.9f);
        view.mPanel.setScaleY(.9f);
        view.mPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
        return view;
    }

    private void populate(FolderIcon tile) {
        mTile = tile;
        FolderInfo info = (FolderInfo) tile.getTag();
        List<ItemInfo> contents = new ArrayList<>(info.getContents());
        contents.sort(Comparator.comparingInt(item -> item.rank));
        mColumns = Math.max(1, Math.min(4, contents.size()));
        mRows = Math.max(1, (contents.size() + mColumns - 1) / mColumns);
        setBackgroundColor(0x59000000);
        mPanel = new LinearLayout(getContext());
        mPanel.setOrientation(VERTICAL);
        mPanel.setPadding(dp(16), dp(12), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        int panelColor = Themes.getAttrColor(getContext(), R.attr.folderBackgroundColor);
        if (Color.alpha(panelColor) < 255) {
            // The blur style tint is translucent; without a blur behind it, the drawer content
            // ghosts through, so lay it over the opaque theme background.
            int base = Themes.getAttrColor(getContext(), android.R.attr.colorBackground);
            panelColor = ColorUtils.compositeColors(panelColor,
                    ColorUtils.setAlphaComponent(base, 255));
        }
        background.setColor(panelColor);
        background.setCornerRadius(dp(24));
        mPanel.setBackground(background);
        mPanel.setElevation(dp(12));
        mPanel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        mPanel.setAccessibilityPaneTitle(info.title);
        TextView title = new TextView(getContext());
        title.setText(info.title);
        title.setTextColor(Themes.getAttrColor(getContext(), R.attr.folderTextColor));
        title.setTextSize(20);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        mPanel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroller = new ScrollView(getContext());
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(mColumns);
        int cellHeight = Math.max(dp(88), Math.min(dp(112),
                mActivityContext.getDeviceProfile().getAllAppsProfile().getCellHeightPx()));
        for (ItemInfo item : contents) {
            if (!(item instanceof WorkspaceItemInfo app)) continue;
            BubbleTextView icon = (BubbleTextView) LayoutInflater.from(getContext())
                    .inflate(R.layout.all_apps_icon, grid, false);
            icon.applyFromWorkspaceItem(app);
            icon.verifyHighRes();
            icon.setOnClickListener(mActivityContext.getItemOnClickListener());
            View.OnLongClickListener longClick = mActivityContext.getAllAppsItemLongClickListener();
            icon.setOnLongClickListener(v -> {
                // Stay open while the long-press menu shows (it is anchored to this icon); close
                // only once the icon is actually dragged, as a folder does.
                DragController dc = mActivityContext.getDragController();
                if (dc != null) dc.addDragListener(mDragListener);
                return longClick != null && longClick.onLongClick(v);
            });
            icon.setCustomActionsListener(mActivityContext.getAllAppsItemCustomActionsListener());
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = 0;
            glp.height = cellHeight;
            glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            grid.addView(icon, glp);
        }
        scroller.addView(grid, new ScrollView.LayoutParams(-1, -2));
        mPanel.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));
        addView(mPanel);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        mBounds.set(mActivityContext.getFolderBoundingBox());
        // Clamp to the drawer sheet that owns the tile; works for the home drawer and the
        // taskbar drawer (TaskbarAllAppsContainerView extends ActivityAllAppsContainerView).
        View sheet = findSheet();
        if (sheet != null && sheet.getWidth() > 0) {
            Rect rect = new Rect();
            mActivityContext.getDragLayer().getDescendantRectRelativeToSelf(sheet, rect);
            if (!mBounds.intersect(rect)) mBounds.set(rect);
        }
        if (!mBounds.intersect(0, 0, width, height)) mBounds.set(0, 0, width, height);
        int margin = Math.min(dp(12), Math.min(mBounds.width(), mBounds.height()) / 4);
        mBounds.inset(margin, margin);
        int panelWidth = Math.max(1, Math.min(mBounds.width(), dp(32 + mColumns * 96)));
        int cellHeight = Math.max(dp(88), Math.min(dp(112),
                mActivityContext.getDeviceProfile().getAllAppsProfile().getCellHeightPx()));
        int panelHeight = Math.max(1, Math.min(mBounds.height(), dp(80) + mRows * cellHeight));
        mPanel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mActivityContext.getDragLayer().getDescendantRectRelativeToSelf(mTile, mTileRect);
        int width = mPanel.getMeasuredWidth(), height = mPanel.getMeasuredHeight();
        int x = Math.max(mBounds.left, Math.min(mTileRect.centerX() - width / 2,
                mBounds.right - width));
        int y = Math.max(mBounds.top, Math.min(mTileRect.centerY() - height / 2,
                mBounds.bottom - height));
        mPanel.layout(x, y, x + width, y + height);
        mPanel.setPivotX(Math.max(0, Math.min(width, mTileRect.centerX() - x)));
        mPanel.setPivotY(Math.max(0, Math.min(height, mTileRect.centerY() - y)));
    }

    @Override
    protected void handleClose(boolean animate) {
        if (!mIsOpen) return;
        mIsOpen = false;
        mPanel.animate().cancel();
        if (animate) {
            mPanel.animate().alpha(0f).scaleX(.9f).scaleY(.9f).setDuration(150)
                    .withEndAction(this::removeFromHost).start();
        } else removeFromHost();
    }

    private void removeFromHost() {
        restoreTile();
        if (getParent() instanceof ViewGroup parent) parent.removeView(this);
        if (mTile.isAttachedToWindow()) mTile.requestFocus();
    }

    private void restoreTile() {
        DragController dc = mActivityContext.getDragController();
        if (dc != null) dc.removeDragListener(mDragListener);
        mTile.removeOnAttachStateChangeListener(mTileDetachListener);
        mTile.setVisibility(VISIBLE);
    }

    @Nullable
    private View findSheet() {
        for (View v = mTile; v != null;
                v = v.getParent() instanceof View parent ? parent : null) {
            if (v instanceof ActivityAllAppsContainerView<?> apps) {
                return apps.getVisibleContainerView();
            }
        }
        return null;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && !mActivityContext.getDragLayer().isEventOverView(mPanel, event)) {
            close(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent event) { return false; }

    @Override
    protected boolean isOfType(int type) { return (type & TYPE_CADDY_CATEGORY) != 0; }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
