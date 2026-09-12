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

import static com.android.app.animation.Interpolators.EMPHASIZED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.folder.LargeFolderPreview;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CaddyCategoryView extends AbstractFloatingView {

    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;

    /** Matches {@link com.android.launcher3.folder.Folder}'s background alpha. */
    private static final int SIDE_MARGIN_DP = 20;
    private static final int TITLE_TOP_DP = 28;
    private static final int COLUMNS = 4;
    private static final int SCRIM_ALPHA = 0x59;
    private static final long OPEN_DURATION = 420;
    private static final long CLOSE_DURATION = 360;
    private static final float BEHIND_SCALE = 0.92f;
    private static final float LATE_FADE_START = 0.55f;
    private static final float BEHIND_GONE_BY = 0.45f;
    private static final float BEHIND_RETURNS_BELOW = 0.4f;

    private final ActivityContext mActivityContext;
    private final Rect mTileRect = new Rect();
    private final List<BubbleTextView> mIcons = new ArrayList<>();
    private final Rect mTmpRect = new Rect();

    private TextView mTitle;
    private ScrollView mScroller;
    private FolderIcon mTile;
    private View mGridBehind;
    private ValueAnimator mAnimator;
    private boolean mClosing;

    private float[] mFromX;
    private float[] mFromY;
    private float[] mFromScale;
    private boolean[] mDrawnInTile;

    public CaddyCategoryView(Context context) {
        this(context, null);
    }

    public CaddyCategoryView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mActivityContext = ActivityContext.lookupContext(context);
    }

    public static CaddyCategoryView show(FolderIcon tile) {
        ActivityContext activityContext = ActivityContext.lookupContext(tile.getContext());
        BaseDragLayer<?> dragLayer = activityContext.getDragLayer();

        CaddyCategoryView view = new CaddyCategoryView(tile.getContext());
        view.populate(tile);
        dragLayer.addView(view, new BaseDragLayer.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        view.mIsOpen = true;
        view.animateOpenFrom(tile);
        return view;
    }

    private void populate(FolderIcon tile) {
        FolderInfo info = (tile.getTag() instanceof FolderInfo) ? (FolderInfo) tile.getTag() : null;
        Context context = getContext();
        mTile = tile;

        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setScrimAlpha(0);
        int side = dp(SIDE_MARGIN_DP);
        Rect insets = mActivityContext.getDeviceProfile().getInsets();
        setPadding(side, insets.top + dp(TITLE_TOP_DP), side, insets.bottom);

        // The category panel (frosted rounded square, matching the tile).
        mTitle = new TextView(context);
        mTitle.setText(info != null ? info.title : "");
        mTitle.setTextColor(Themes.getAttrColor(context, R.attr.folderTextColor));
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        mTitle.setTypeface(mTitle.getTypeface(), android.graphics.Typeface.BOLD);
        mTitle.setPadding(dp(4), 0, dp(4), dp(16));
        addView(mTitle, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        DeviceProfile dp = mActivityContext.getDeviceProfile();
        int cellH = dp.getAllAppsProfile().getCellHeightPx();
        mScroller = new ScrollView(context);
        mScroller.setClipChildren(false);
        mScroller.setClipToPadding(false);
        mScroller.setVerticalScrollBarEnabled(false);
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(COLUMNS);
        grid.setClipChildren(false);
        if (info != null) {
            List<ItemInfo> contents = new ArrayList<>(info.getContents());
            contents.sort(Comparator.comparingInt(item -> item.rank));
            for (ItemInfo item : contents) {
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
                mIcons.add(icon);
            }
        }

        mScroller.addView(grid, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        addView(mScroller, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
    }

    private void animateOpenFrom(FolderIcon tile) {
        mActivityContext.getDragLayer().getDescendantRectRelativeToSelf(tile, mTileRect);
        mGridBehind = gridHolding(tile);
        setVisibility(INVISIBLE);
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                measureFlights();
                setVisibility(VISIBLE);
                setTileIconsHidden(true);
                if (mGridBehind != null) {
                    mGridBehind.setPivotX(mTileRect.centerX());
                    mGridBehind.setPivotY(mTileRect.centerY());
                    mGridBehind.setLayerType(LAYER_TYPE_HARDWARE, null);
                }
                for (BubbleTextView icon : mIcons) {
                    icon.setLayerType(LAYER_TYPE_HARDWARE, null);
                }
                applyProgress(0f);
                post(() -> runTo(1f, null));
                return false;
            }
        });
    }

    /** A simple, always-visible center fade + scale. (Expand-from-tile can be layered on later.) */
    private void measureFlights() {
        int count = mIcons.size();
        mFromX = new float[count];
        mFromY = new float[count];
        mFromScale = new float[count];
        mDrawnInTile = new boolean[count];
        LargeFolderPreview preview = mTile.getLargeFolderPreview();
        BaseDragLayer<?> dragLayer = mActivityContext.getDragLayer();
        Rect dest = new Rect();
        for (int i = 0; i < count; i++) {
            BubbleTextView icon = mIcons.get(i);
            icon.getIconBounds(mTmpRect);
            icon.setPivotX(mTmpRect.exactCenterX());
            icon.setPivotY(mTmpRect.exactCenterY());
            dragLayer.getDescendantRectRelativeToSelf(icon, dest);
            float destX = dest.left + mTmpRect.exactCenterX();
            float destY = dest.top + mTmpRect.exactCenterY();
            int destSize = Math.max(1, mTmpRect.width());

            if (preview != null) {
                preview.getItemRect(i, mTmpRect);
            } else {
                mTmpRect.setEmpty();
            }
            mDrawnInTile[i] = !mTmpRect.isEmpty();
            float srcX = mTileRect.left + mTmpRect.exactCenterX();
            float srcY = mTileRect.top + mTmpRect.exactCenterY();
            mFromX[i] = srcX - destX;
            mFromY[i] = srcY - destY;
            mFromScale[i] = mDrawnInTile[i] ? (float) mTmpRect.width() / destSize : 0.2f;
        }
    }

    @Nullable
    private static View gridHolding(FolderIcon tile) {
        for (ViewParent p = tile.getParent(); p instanceof View; p = p.getParent()) {
            if (p instanceof ActivityAllAppsContainerView) {
                return (View) p;
            }
        }
        return null;
    }

    private void runTo(float target, @Nullable Runnable onEnd) {
        float from = 0f;
        if (mAnimator != null) {
            from = (float) mAnimator.getAnimatedValue();
            mAnimator.removeAllListeners();
            mAnimator.cancel();
        } else if (target == 0f) {
            from = 1f;
        }
        mClosing = target == 0f;
        mAnimator = ValueAnimator.ofFloat(from, target);
        mAnimator.setDuration((long) ((target == 1f ? OPEN_DURATION : CLOSE_DURATION)
                * Math.abs(target - from)));
        mAnimator.setInterpolator(EMPHASIZED);
        mAnimator.addUpdateListener(a -> applyProgress((float) a.getAnimatedValue()));
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (target == 1f) {
                    for (BubbleTextView icon : mIcons) {
                        icon.setLayerType(LAYER_TYPE_NONE, null);
                    }
                }
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        mAnimator.start();
    }

    private void applyProgress(float progress) {
        float late = Utilities.boundToRange(
                (progress - LATE_FADE_START) / (1f - LATE_FADE_START), 0f, 1f);
        for (int i = 0; i < mIcons.size(); i++) {
            BubbleTextView icon = mIcons.get(i);
            float remaining = 1f - progress;
            icon.setTranslationX(mFromX[i] * remaining);
            icon.setTranslationY(mFromY[i] * remaining);
            float scale = Utilities.mapRange(progress, mFromScale[i], 1f);
            icon.setScaleX(scale);
            icon.setScaleY(scale);
            icon.setAlpha(mDrawnInTile[i] ? 1f : late);
            icon.getFloatingViewTextAlpha().setValue(late);
        }
        mTitle.setAlpha(late);
        mTitle.setTranslationY(dp(12) * (1f - progress));
        if (mGridBehind != null) {
            float away = mClosing
                    ? Utilities.boundToRange(progress / BEHIND_RETURNS_BELOW, 0f, 1f)
                    : Utilities.boundToRange(progress / BEHIND_GONE_BY, 0f, 1f);
            mGridBehind.setAlpha(1f - away);
            float behind = Utilities.mapRange(away, 1f, BEHIND_SCALE);
            mGridBehind.setScaleX(behind);
            mGridBehind.setScaleY(behind);
        }
        setScrimAlpha(Math.round(SCRIM_ALPHA * progress));
    }

    private void setTileIconsHidden(boolean hidden) {
        LargeFolderPreview preview = mTile != null ? mTile.getLargeFolderPreview() : null;
        if (preview != null) {
            preview.setIconsHidden(hidden);
        }
    }

    private void setScrimAlpha(int alpha) {
        setBackgroundColor(Color.argb(Math.max(0, Math.min(255, alpha)), 0, 0, 0));
    }

    @Override
    protected void handleClose(boolean animate) {
        if (!mIsOpen) {
            return;
        }
        mIsOpen = false;
        if (!animate || mFromX == null) {
            closeComplete();
            return;
        }
        mScroller.scrollTo(0, 0);
        for (BubbleTextView icon : mIcons) {
            icon.setLayerType(LAYER_TYPE_HARDWARE, null);
        }
        runTo(0f, this::closeComplete);
    }

    private void closeComplete() {
        // Release the backdrop snapshot / cross-window blur drawable, same as Folder#closeComplete.
        if (mAnimator != null) {
            mAnimator.removeAllListeners();
            mAnimator.cancel();
            mAnimator = null;
        }
        setTileIconsHidden(false);
        if (mGridBehind != null) {
            mGridBehind.setLayerType(LAYER_TYPE_NONE, null);
            mGridBehind.setAlpha(1f);
            mGridBehind.setScaleX(1f);
            mGridBehind.setScaleY(1f);
            mGridBehind = null;
        }
        if (getParent() instanceof ViewGroup parent) {
            parent.removeView(this);
        }
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
            // Tap outside the panel closes the category page.
        if (ev.getAction() != MotionEvent.ACTION_DOWN) {
            return false;
        }
        BaseDragLayer<?> dragLayer = mActivityContext.getDragLayer();
        for (BubbleTextView icon : mIcons) {
            if (dragLayer.isEventOverView(icon, ev)) {
                return false;
            }
        }
        close(true);
        return true;
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
