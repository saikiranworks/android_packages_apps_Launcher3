/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.launcher3.folder;

import static com.android.launcher3.LauncherSettings.Favorites.DESKTOP_ICON_FLAG;
import static com.android.launcher3.folder.ClippedFolderIconLayoutRule.MAX_NUM_ITEMS_IN_PREVIEW;
import static com.android.launcher3.icons.BitmapInfo.FLAG_THEMED;
import static com.android.launcher3.model.data.FolderInfo.MAX_FOLDER_SPAN;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.WorkspaceItemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the preview of a "big" (2x2 or 3x3) {@link FolderIcon}: an app as a large, individually
 * launchable icon in every cell but the last, plus a mini 2x2 cluster of the remaining apps in the
 * last cell that opens the folder when tapped.
 *
 * <p>This class owns the big-folder layout math, drawing, and the per-quadrant hit rectangles used
 * to route taps. {@link FolderIcon} delegates to it only while the folder is big; normal folders
 * keep using {@link PreviewItemManager}.
 */
public class LargeFolderPreview {

    // Fraction of the available (above-label) area used by the square preview.
    private static final float PREVIEW_SIZE_FRACTION = 0.94f;
    // Density-aware maximum for a 2x2 preview; 3x3 workspace previews scale proportionally.
    private static final float MAX_PREVIEW_SIZE_DP = 136f;
    // Drawer category tiles sit three to a row in a wide sheet, so they get a larger cap.
    private static final float MAX_DRAWER_PREVIEW_SIZE_DP = 168f;
    /** Content height for a compact category tile before its label and row gap. */
    public static int getDrawerTileSize(Context context) {
        return Math.round(MAX_DRAWER_PREVIEW_SIZE_DP
                * context.getResources().getDisplayMetrics().density / PREVIEW_SIZE_FRACTION);
    }

    // Gap between the four quadrants, as a fraction of the preview side.
    private static final float QUADRANT_GAP_FRACTION = 0.06f;
    // Large icon size within its quadrant.
    private static final float LARGE_ICON_FRACTION = 0.82f;
    // Mini icon size, as a fraction of the quadrant side. Together with the spacing below this
    // makes the 2x2 mini block a centred island inside its quadrant rather than something that
    // fills it: measured against the reference launcher, the large icon is ~2.9x a mini icon and
    // the gap between minis is ~0.47x a mini. Sizing the minis off the quadrant directly (a half
    // quadrant each, less a hairline gap) made them ~2.3x, so they read as too big and sat on a
    // different rhythm from the large icons beside them.
    private static final float CLUSTER_ICON_FRACTION = 0.30f;
    // Centre-to-centre distance between adjacent mini icons, as a fraction of the quadrant side.
    private static final float CLUSTER_SPACING_FRACTION = 0.45f;

    // Aurora burnt-orange accent, used for the drag-over "drop here" ring.
    private static final int ACCENT = 0xFFC8783E;

    private final FolderIcon mIcon;
    private Object mLastShape;
    private boolean mLastThemed;
    // The frosted "fake blur" panel behind the whole preview, and its drag-over highlight.
    private final Paint mPanelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPanelStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAcceptFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAcceptStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private int mHorizontalShift;
    private int mVerticalShift;
    private int mLastTop;
    private int mLastAvailableHeight;
    private int mLastSide;
    private int mLastPad;

    // The apps drawn as large, launchable icons (index-aligned with mLargeRects).
    private final List<ItemInfo> mLargeItems = new ArrayList<>();
    private final List<Drawable> mLargeDrawables = new ArrayList<>();
    // The apps collapsed into the mini-cluster quadrant (mClusterItems index-aligned with drawables).
    private final List<ItemInfo> mClusterItems = new ArrayList<>();
    private final List<Drawable> mClusterDrawables = new ArrayList<>();

    // Hit rectangles in FolderIcon-local coordinates. mLargeRects[i] launches mLargeItems.get(i);
    // mClusterRect opens the folder.
    private final Rect[] mLargeRects = new Rect[MAX_FOLDER_SPAN * MAX_FOLDER_SPAN - 1];
    private final Rect mClusterRect = new Rect();
    private final Rect mTmpRect = new Rect();
    // The frosted panel rect (the whole 2x2 preview square, slightly inset).
    private final RectF mPanelRect = new RectF();

    // Bottom of the preview square (FolderIcon-local px); the label is placed just below it.
    private int mPreviewBottom;
    // True while an app is being dragged over this folder, to show the accept highlight.
    private boolean mAccepting;
    private boolean mIconsHidden;

    /** Corner radius of the frosted tile panel, as a fraction of the panel width. */
    public static final float PANEL_RADIUS_FRACTION = 0.16f;

    /**
     * Frosted-glass fill of the big-folder tile: a translucent light panel over the wallpaper. Cheap
     * (no RenderEffect), so it never introduces lag. Exposed so the open folder can paint the same
     * surface -- the open folder used to be an opaque themed panel, which read as a different
     * background from the tile it expanded out of.
     */
    public static int getPanelColor(Context context) {
        return isNight(context) ? 0x30FFFFFF : 0x3AFFFFFF;
    }

    /** Hairline outline of the frosted tile panel. See {@link #getPanelColor}. */
    public static int getPanelStrokeColor(Context context) {
        return isNight(context) ? 0x22FFFFFF : 0x26FFFFFF;
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public LargeFolderPreview(FolderIcon icon) {
        mIcon = icon;
        mDensity = icon.getResources().getDisplayMetrics().density;
        for (int i = 0; i < mLargeRects.length; i++) {
            mLargeRects[i] = new Rect();
        }
        mPanelPaint.setColor(getPanelColor(icon.getContext()));
        mPanelStrokePaint.setStyle(Paint.Style.STROKE);
        mPanelStrokePaint.setStrokeWidth(mDensity);
        mPanelStrokePaint.setColor(getPanelStrokeColor(icon.getContext()));
        // Drag-over highlight: brighter fill + accent ring.
        mAcceptFillPaint.setColor(0x33FFFFFF);
        mAcceptStrokePaint.setStyle(Paint.Style.STROKE);
        mAcceptStrokePaint.setStrokeWidth(2f * mDensity);
        mAcceptStrokePaint.setColor(ACCENT);
    }

    /** Cells along each side of the preview: 2 for a large folder, 3 for an extra-large one. */
    private int grid() {
        if (mIcon.mInfo == null || mIcon.mInfo.forceBigPreview) {
            return 2;
        }
        return Math.max(2, mIcon.mInfo.getFolderSpan());
    }

    /** Apps shown as large icons: every cell but the last, which holds the rest as a cluster. */
    private int largeCount() {
        int n = grid();
        return n * n - 1;
    }

    /**
     * Rebuilds the icon drawables from the folder's ordered contents. Should be called whenever the
     * folder contents change.
     */
    public void onItemsChanged(List<ItemInfo> orderedContents) {
        mLargeItems.clear();
        mLargeDrawables.clear();
        mClusterItems.clear();
        mClusterDrawables.clear();

        for (int i = 0; i < orderedContents.size(); i++) {
            ItemInfo item = orderedContents.get(i);
            if (i < largeCount()) {
                mLargeItems.add(item);
                mLargeDrawables.add(newIcon(item));
                requestHighResIfNeeded(item);
            } else if (mClusterItems.size() < MAX_NUM_ITEMS_IN_PREVIEW) {
                Drawable d = newIcon(item);
                if (d != null) {
                    mClusterItems.add(item);
                    mClusterDrawables.add(d);
                    requestHighResIfNeeded(item);
                }
            }
        }
    }

    @Nullable
    private Drawable newIcon(ItemInfo item) {
        if (item instanceof WorkspaceItemInfo wii) {
            // Only ask for a themed (mono) icon when icon theming is actually on. Passing
            // FLAG_THEMED unconditionally made the drawer's category tiles draw mono icons even
            // with "Nothing OS icons" off: newIcon() strips the flag when the global theme is
            // disabled, but ThemeManager also force-enables mono while the NOS setting is on, so
            // the two states could not be told apart from here. BubbleTextView gates the same flag
            // on shouldUseTheme(); this is the drawing-side equivalent.
            int flags = ThemeManager.INSTANCE.get(mIcon.getContext()).isIconThemeEnabled()
                    ? FLAG_THEMED : 0;
            return wii.newIcon(mIcon.getContext(), flags);
        }
        return null;
    }

    /**
     * The loader only fetches high-res icons for the first {@code MAX_NUM_ITEMS_IN_PREVIEW} folder
     * items, but a big folder shows more (3 large + up to 4 cluster). Any still-low-res icon renders
     * as a plain coloured circle, so request a high-res load and rebuild that icon when it arrives.
     */
    private void requestHighResIfNeeded(ItemInfo item) {
        if (item instanceof ItemInfoWithIcon info
                && info.getMatchingLookupFlag().isVisuallyLessThan(DESKTOP_ICON_FLAG)) {
            LauncherAppState.getInstance(mIcon.getContext()).getIconCache().updateIconInBackground(
                    mIcon.getContext().getMainExecutor(), this::onHighResIconLoaded,
                    info, DESKTOP_ICON_FLAG);
        }
    }

    private void onHighResIconLoaded(ItemInfoWithIcon newInfo) {
        boolean changed = false;
        for (int i = 0; i < mLargeItems.size(); i++) {
            if (mLargeItems.get(i) == newInfo) {
                mLargeDrawables.set(i, newIcon(newInfo));
                changed = true;
            }
        }
        for (int i = 0; i < mClusterItems.size(); i++) {
            if (mClusterItems.get(i) == newInfo) {
                mClusterDrawables.set(i, newIcon(newInfo));
                changed = true;
            }
        }
        if (changed) {
            mIcon.invalidate();
        }
    }

    /**
     * Recomputes the quadrant rectangles and preview bounds for the given tile size. Returns the
     * bottom of the preview square, which the caller uses to place the folder label just below it.
     */
    public int updateGeometry(int width, int height, int top, int labelHeight) {
        int availableHeight = height - top - labelHeight;
        int side = Math.round(Math.min(width, availableHeight) * PREVIEW_SIZE_FRACTION);
        // Bound the preview in dp rather than a fraction of the physical screen width.
        // A density change must scale both workspace and drawer previews. The allocated
        // cell still limits the result so a larger display size never overflows the grid.
        float spanScale = mIcon.mInfo.forceBigPreview ? 1f : grid() / 2f;
        float maxDp = mIcon.mInfo.forceBigPreview ? MAX_DRAWER_PREVIEW_SIZE_DP : MAX_PREVIEW_SIZE_DP;
        side = Math.min(side, Math.round(maxDp * mDensity * spanScale));
        if (side <= 0 || width <= 0) {
            mPreviewBottom = top;
            return mPreviewBottom;
        }

        int n = grid();
        int gap = Math.round(side * QUADRANT_GAP_FRACTION / (n - 1) * (n == 2 ? 1f : 1.4f));
        int centeredLeft = (width - side) / 2;
        mLastSide = side;
        mLastPad = Math.round(gap * 0.5f);
        int left = edgeAlignedLeft(width, side, mLastPad, centeredLeft, mIcon.mInfo == null
                ? -1 : mIcon.mInfo.cellX);
        mHorizontalShift = left - centeredLeft;
        mLastTop = top;
        mLastAvailableHeight = availableHeight;
        int previewTop = edgeAlignedTop(top, availableHeight, side, mLastPad,
                mIcon.mInfo == null ? -1 : mIcon.mInfo.cellY);
        mVerticalShift = previewTop - (top + Math.max(0, (availableHeight - side) / 2));
        int cell = (side - gap * (n - 1)) / n;

        // Cells in reading order; the last one holds the cluster.
        for (int i = 0; i < n * n; i++) {
            int x = left + (i % n) * (cell + gap);
            int y = previewTop + (i / n) * (cell + gap);
            if (i < largeCount()) {
                mLargeRects[i].set(x, y, x + cell, y + cell);
            } else {
                mClusterRect.set(x, y, x + cell, y + cell);
            }
        }

        // The frosted panel frames the whole preview, expanded a touch past the icons.
        float pad = gap * 0.5f;
        mPanelRect.set(left - pad, previewTop - pad, left + side + pad, previewTop + side + pad);

        mPreviewBottom = previewTop + side;
        return mPreviewBottom;
    }

    /**
     * A home-screen folder touching the left or right edge column sits flush with that edge,
     * instead of floating centred in its wide 2x2/3x3 block.
     */
    private int edgeAlignedLeft(int width, int side, int pad, int centeredLeft, int cellX) {
        if (mIcon.mInfo == null || mIcon.mInfo.forceBigPreview || mIcon.mActivity == null
                || mIcon.mInfo.container != LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            return centeredLeft;
        }
        DeviceProfile dp = mIcon.mActivity.getDeviceProfile();
        int columns = dp.inv.numColumns;
        // Flush with the cell edge, as a widget in that column sits.
        int inset = 0;
        int left = centeredLeft;
        if (cellX <= 0) {
            left = inset + pad;
        } else if (cellX + mIcon.mInfo.spanX >= columns) {
            left = width - inset - pad - side;
        }
        // Never let the panel leave the icon's own bounds.
        return Math.max(pad, Math.min(left, width - side - pad));
    }

    /** Same idea as {@link #edgeAlignedLeft} for the top and bottom rows. */
    private int edgeAlignedTop(int top, int availableHeight, int side, int pad, int cellY) {
        int centeredTop = top + Math.max(0, (availableHeight - side) / 2);
        if (mIcon.mInfo == null || mIcon.mInfo.forceBigPreview || mIcon.mActivity == null
                || mIcon.mInfo.container != LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            return centeredTop;
        }
        int rows = mIcon.mActivity.getDeviceProfile().inv.numRows;
        int previewTop = centeredTop;
        if (cellY <= 0) {
            previewTop = top + pad;
        } else if (cellY + mIcon.mInfo.spanY >= rows) {
            // Preview and its label as low as the block allows, keeping room for the gap
            // between them so the label isn't pushed out of the icon and clipped.
            previewTop = top + Math.max(0, availableHeight - side) - pad
                    - mIcon.getBigFolderLabelGap();
        }
        int lowest = top + Math.max(0, availableHeight - side - mIcon.getBigFolderLabelGap());
        return Math.max(top + pad, Math.min(previewTop, lowest));
    }

    /**
     * The frosted panel's bounds (icon-local) if this folder were dropped with its top-left cell
     * at ({@code cellX}, {@code cellY}); used for the drop outline while dragging.
     */
    public void getPanelRectAt(int cellX, int cellY, Rect out) {
        getPreviewRect(out);
        int width = mIcon.getWidth();
        int centeredLeft = (width - mLastSide) / 2;
        int target = edgeAlignedLeft(width, mLastSide, mLastPad, centeredLeft, cellX)
                - centeredLeft;
        int centeredTop = mLastTop + Math.max(0, (mLastAvailableHeight - mLastSide) / 2);
        int targetY = edgeAlignedTop(mLastTop, mLastAvailableHeight, mLastSide, mLastPad, cellY)
                - centeredTop;
        out.offset(target - mHorizontalShift, targetY - mVerticalShift);
    }

    /** How far the preview is shifted from centre (the label follows it). */
    public int getHorizontalShift() {
        return mHorizontalShift;
    }

    /** Draws the big-folder preview using the geometry from the icon's current size. */
    public void draw(Canvas canvas) {
        ThemeManager theme = ThemeManager.INSTANCE.get(mIcon.getContext());
        Object shape = theme.getIconShapeData().getValue();
        boolean themed = theme.isIconThemeEnabled();
        if (shape != mLastShape || themed != mLastThemed) {
            mLastShape = shape;
            mLastThemed = themed;
            // Shape updates need fresh drawables even if the cached bitmap is unchanged.
            for (int i = 0; i < mLargeItems.size(); i++) {
                mLargeDrawables.set(i, newIcon(mLargeItems.get(i)));
            }
            for (int i = 0; i < mClusterItems.size(); i++) {
                mClusterDrawables.set(i, newIcon(mClusterItems.get(i)));
            }
        }
        updateGeometry(mIcon.getWidth(), mIcon.getHeight(), mIcon.getPaddingTop(),
                mIcon.getBigFolderLabelHeight());
        if (mPreviewBottom <= mIcon.getPaddingTop()) {
            return;
        }

        // Frosted "fake blur" panel behind everything.
        float radius = mPanelRect.width() * 0.16f;
        // Real blur under the tint, as the open folder has, so closing it doesn't drop the blur.
        mIcon.mActivity.getActivityComponent().getBlurBackgroundHelper()
                .drawTileBlur(canvas, mIcon, mPanelRect, radius);
        canvas.drawRoundRect(mPanelRect, radius, radius, mPanelPaint);
        canvas.drawRoundRect(mPanelRect, radius, radius, mPanelStrokePaint);
        if (mAccepting) {
            canvas.drawRoundRect(mPanelRect, radius, radius, mAcceptFillPaint);
            canvas.drawRoundRect(mPanelRect, radius, radius, mAcceptStrokePaint);
        }

        if (mIconsHidden) {
            return;
        }
        for (int i = 0; i < largeCount() && i < mLargeDrawables.size(); i++) {
            drawCentered(canvas, mLargeDrawables.get(i), mLargeRects[i], LARGE_ICON_FRACTION);
        }
        drawCluster(canvas, mClusterRect);
    }

    public void setIconsHidden(boolean hidden) {
        if (mIconsHidden != hidden) {
            mIconsHidden = hidden;
            mIcon.invalidate();
        }
    }

    /** Toggles the drag-over "drop here" highlight. Returns true if the state changed. */
    public boolean setAccepting(boolean accepting) {
        if (mAccepting == accepting) {
            return false;
        }
        mAccepting = accepting;
        return true;
    }

    /**
     * Fills {@code out} with the frosted panel rect (FolderIcon-local px) for the current size, so
     * callers (drag bounds, open/close animation) use the big footprint instead of the small
     * circular preview.
     */
    public void getPreviewRect(Rect out) {
        updateGeometry(mIcon.getWidth(), mIcon.getHeight(), mIcon.getPaddingTop(),
                mIcon.getBigFolderLabelHeight());
        mPanelRect.roundOut(out);
    }

    public void getItemRect(int index, Rect out) {
        updateGeometry(mIcon.getWidth(), mIcon.getHeight(), mIcon.getPaddingTop(),
                mIcon.getBigFolderLabelHeight());
        if (index < largeCount()) {
            Rect cell = mLargeRects[index];
            int size = Math.round(Math.min(cell.width(), cell.height()) * LARGE_ICON_FRACTION);
            out.set(cell.centerX() - size / 2, cell.centerY() - size / 2,
                    cell.centerX() + size / 2, cell.centerY() + size / 2);
            return;
        }
        int cx = mClusterRect.centerX();
        int cy = mClusterRect.centerY();
        int mini = index - largeCount();
        if (mini >= Math.min(mClusterDrawables.size(), 4)) {
            out.set(cx, cy, cx, cy);
            return;
        }
        int side = mClusterRect.width();
        int half = Math.round(side * CLUSTER_SPACING_FRACTION) / 2;
        int size = Math.round(side * CLUSTER_ICON_FRACTION);
        int x = cx + (mini % 2 == 0 ? -half : half);
        int y = cy + (mini < 2 ? -half : half);
        out.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
    }

    private void drawCluster(Canvas canvas, Rect quadrant) {
        // The mini-cluster reads as "the folder" from its grid of small icons; it needs no separate
        // dark backing on top of the frosted panel (that extra square looked out of place).
        if (mClusterDrawables.isEmpty()) {
            return;
        }
        // Lay the minis out from their centres so the block stays centred in the quadrant however
        // the two fractions are tuned, and so the icons line up with the large ones on the row
        // above: the whole cluster is one spacing wide, centred on the quadrant centre.
        int side = quadrant.width();
        int spacing = Math.round(side * CLUSTER_SPACING_FRACTION);
        int mini = Math.round(side * CLUSTER_ICON_FRACTION);
        int cx = quadrant.centerX();
        int cy = quadrant.centerY();
        int[] centreX = {cx - spacing / 2, cx + spacing / 2, cx - spacing / 2, cx + spacing / 2};
        int[] centreY = {cy - spacing / 2, cy - spacing / 2, cy + spacing / 2, cy + spacing / 2};
        int count = Math.min(mClusterDrawables.size(), 4);
        for (int i = 0; i < count; i++) {
            mTmpRect.set(centreX[i] - mini / 2, centreY[i] - mini / 2,
                    centreX[i] + mini / 2, centreY[i] + mini / 2);
            // Fraction 1f: mTmpRect is already the exact icon box.
            drawCentered(canvas, mClusterDrawables.get(i), mTmpRect, 1f);
        }
    }

    private void drawCentered(Canvas canvas, @Nullable Drawable d, Rect bounds, float fraction) {
        if (d == null) {
            return;
        }
        int size = Math.round(Math.min(bounds.width(), bounds.height()) * fraction);
        int cx = bounds.centerX();
        int cy = bounds.centerY();
        d.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        d.draw(canvas);
    }

    /**
     * Returns the app to launch for a tap at ({@code x}, {@code y}) in FolderIcon-local
     * coordinates, or {@code null} if the tap should instead open the folder (the mini-cluster
     * quadrant, the label, or empty space).
     */
    @Nullable
    public WorkspaceItemInfo getLaunchTargetForPoint(float x, float y) {
        int ix = Math.round(x);
        int iy = Math.round(y);
        for (int i = 0; i < largeCount() && i < mLargeItems.size(); i++) {
            if (mLargeRects[i].contains(ix, iy)
                    && mLargeItems.get(i) instanceof WorkspaceItemInfo wii) {
                return wii;
            }
        }
        return null;
    }
}
