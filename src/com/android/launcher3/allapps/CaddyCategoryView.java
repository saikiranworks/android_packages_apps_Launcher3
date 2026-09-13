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

import static com.android.launcher3.Flags.blurOnMoreSurfaces;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.BlurBackgroundHelper;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;

/**
 * The in-drawer "Caddy" category page: tapping a category tile in the app drawer opens this over the
 * drawer, showing the category's apps in a grid. It expands from the tile's bounds (a container
 * transform: the panel scales + translates + fades from the tile to full size) and closes back the
 * same way, so it never animates against / drops to the home screen the way the workspace Folder
 * does. It reuses the tile's frosted rounded-square panel look so the open surface matches the
 * closed tile.
 *
 * <p>First cut of the OnePlus-style categorized drawer open; sizes/colors are tuned on-device.
 */
public class CaddyCategoryView extends AbstractFloatingView {

    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int PANEL_MARGIN_DP = 16;
    /** Matches {@link com.android.launcher3.folder.Folder}'s background alpha. */
    private static final int PANEL_ALPHA = 0xCC;
    private static final int SCRIM_ALPHA = 0x4D; // light dim; the blur does the heavy lifting
    private static final int OPEN_DURATION = 300;

    private final ActivityContext mActivityContext;
    private final BlurBackgroundHelper mBlurBackgroundHelper;
    private final Rect mTileRect = new Rect();

    private BlurPanel mPanel;

    public CaddyCategoryView(Context context) {
        this(context, null);
    }

    public CaddyCategoryView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mActivityContext = ActivityContext.lookupContext(context);
        mBlurBackgroundHelper =
                mActivityContext.getActivityComponent().getBlurBackgroundHelper();
    }

    /** Opens the category page for a drawer category tile. */
    public static CaddyCategoryView show(FolderIcon tile) {
        ActivityContext activityContext = ActivityContext.lookupContext(tile.getContext());
        BaseDragLayer<?> dragLayer = activityContext.getDragLayer();

        CaddyCategoryView view = new CaddyCategoryView(tile.getContext());
        view.populate(tile);
        // Snapshot the drawer for the blur *before* attaching, or the snapshot would contain the
        // panel itself. Same capture the workspace folder does in Folder#animateOpen.
        if (blurOnMoreSurfaces()) {
            view.mBlurBackgroundHelper.prepareToOpenBlurSurface();
        }
        dragLayer.addView(view, new BaseDragLayer.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        view.mIsOpen = true;
        view.animateOpenFrom(tile);
        return view;
    }

    /**
     * The category panel. Draws the same blurred backdrop the workspace folder does, under the same
     * translucent rounded-rect fill, so opening a drawer category looks like opening a folder rather
     * than dropping a black card over the drawer.
     */
    private class BlurPanel extends LinearLayout {

        private final GradientDrawable mPanelBackground = new GradientDrawable();

        BlurPanel(Context context) {
            super(context);
            // Same fill the workspace folder uses (?attr/folderBackgroundColor at the folder's
            // translucency) so the drawer category reads as a glass panel over the blurred drawer
            // instead of an opaque black card. The corner radius matches the radius the blur helper
            // clips its render node to, so fill and blur share one edge.
            final float radius = Themes.getDialogCornerRadius(context);
            mPanelBackground.setCornerRadius(radius);
            mPanelBackground.setColor(Themes.getAttrColor(context, R.attr.folderBackgroundColor));
            mPanelBackground.setAlpha(PANEL_ALPHA);
            // Drawn by hand in dispatchDraw (below), not set as the View background: the blur has to
            // land *under* the fill, and a View background is always painted before dispatchDraw.
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            setClipToOutline(true);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mPanelBackground.setBounds(0, 0, w, h);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            mBlurBackgroundHelper.drawFolderBlur(canvas, null, this);
            mPanelBackground.draw(canvas);
            super.dispatchDraw(canvas);
        }
    }

    private void populate(FolderIcon tile) {
        // The FolderInfo is stored as the tile's tag (FolderIcon.inflateIcon -> setTag(folderInfo)).
        FolderInfo info = (tile.getTag() instanceof FolderInfo) ? (FolderInfo) tile.getTag() : null;
        Context context = getContext();

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setScrimAlpha(0);

        // The category panel (frosted rounded square, matching the tile).
        mPanel = new BlurPanel(context);
        mPanel.setOrientation(VERTICAL);
        int pad = dp(16);
        mPanel.setPadding(pad, pad, pad, pad);

        // Title.
        TextView title = new TextView(context);
        title.setText(info != null ? info.title : "");
        title.setTextColor(Themes.getAttrColor(context, R.attr.folderTextColor));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setPadding(dp(4), dp(4), dp(4), dp(12));
        mPanel.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        // App grid.
        DeviceProfile dp = mActivityContext.getDeviceProfile();
        int columns = Math.max(4, dp.getAllAppsProfile().getNumShownAllAppsColumns());
        int cellH = dp.getAllAppsProfile().getCellHeightPx();
        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(columns);
        if (info != null) {
            for (ItemInfo item : info.getContents()) {
                if (!(item instanceof WorkspaceItemInfo)) {
                    continue;
                }
                BubbleTextView icon = (BubbleTextView) LayoutInflater.from(context)
                        .inflate(R.layout.all_apps_icon, grid, false);
                icon.reset();
                icon.applyFromWorkspaceItem((WorkspaceItemInfo) item);
                icon.setOnClickListener(mActivityContext.getItemOnClickListener());
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = 0;
                glp.height = cellH;
                glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
                icon.setLayoutParams(glp);
                grid.addView(icon);
            }
        }
        scroller.addView(grid, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        mPanel.addView(scroller, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        int margin = dp(PANEL_MARGIN_DP);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        panelLp.setMargins(margin, margin * 4, margin, margin * 4);
        addView(mPanel, panelLp);
    }

    private void animateOpenFrom(FolderIcon tile) {
        startTransform(true /* opening */, null);
    }

    /** A simple, always-visible center fade + scale. (Expand-from-tile can be layered on later.) */
    private void startTransform(boolean opening, @Nullable Runnable onEnd) {
        float fromScale = opening ? 0.9f : 1f;
        float toScale = opening ? 1f : 0.9f;
        mPanel.setPivotX(mPanel.getWidth() / 2f);
        mPanel.setPivotY(mPanel.getHeight() / 2f);
        mPanel.setScaleX(fromScale);
        mPanel.setScaleY(fromScale);
        mPanel.setAlpha(opening ? 0f : 1f);

        mPanel.animate()
                .scaleX(toScale).scaleY(toScale)
                .alpha(opening ? 1f : 0f)
                .setDuration(OPEN_DURATION)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (onEnd != null) onEnd.run();
                    }
                })
                .start();

        ValueAnimator scrim = ValueAnimator.ofInt(
                opening ? 0 : SCRIM_ALPHA, opening ? SCRIM_ALPHA : 0);
        scrim.setDuration(OPEN_DURATION);
        scrim.addUpdateListener(a -> setScrimAlpha((int) a.getAnimatedValue()));
        scrim.start();
    }

    private void setScrimAlpha(int alpha) {
        setBackgroundColor(Color.argb(Math.max(0, Math.min(255, alpha)), 0, 0, 0));
    }

    @Override
    protected void handleClose(boolean animate) {
        if (!mIsOpen) {
            return;
        }
        if (!animate) {
            closeComplete();
            return;
        }
        startTransform(false /* closing */, this::closeComplete);
    }

    private void closeComplete() {
        mIsOpen = false;
        // Release the backdrop snapshot / cross-window blur drawable, same as Folder#closeComplete.
        mBlurBackgroundHelper.folderCloseComplete();
        if (getParent() instanceof ViewGroup parent) {
            parent.removeView(this);
        }
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN
                && !mActivityContext.getDragLayer().isEventOverView(mPanel, ev)) {
            // Tap outside the panel closes the category page.
            close(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_CADDY_CATEGORY) != 0;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
