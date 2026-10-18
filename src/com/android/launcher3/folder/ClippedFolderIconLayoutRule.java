package com.android.launcher3.folder;

public class ClippedFolderIconLayoutRule {

    public static final int MAX_NUM_ITEMS_IN_PREVIEW = 9;
    // Preview icons remain entirely within the rounded-square surface.
    public static final float ICON_OVERLAP_FACTOR = 1f;

    public static final int EXIT_INDEX = -2;
    public static final int ENTER_INDEX = -3;

    private float[] mTmpPoint = new float[2];

    private float mAvailableSpace;
    private float mIconSize;
    private boolean mIsRtl;
    private float mBaselineIconScale;

    /**
     * initialize the layout rule
     */
    public void init(int availableSpace, float intrinsicIconSize, boolean rtl,
            int numFolderColumns) {
        mAvailableSpace = availableSpace;
        mIconSize = intrinsicIconSize;
        mIsRtl = rtl;
        mBaselineIconScale = availableSpace / intrinsicIconSize;
    }

    /**
     * Computes positions for icons in Preview.
     *
     * @param index       index of icon in folder
     * @param curNumItems current number of preview items
     * @param params      params to update for icon
     */
    public PreviewItemDrawingParams computePreviewItemDrawingParams(int index, int curNumItems,
            PreviewItemDrawingParams params) {
        float totalScale = scaleForItem(curNumItems, 0);
        float transX;
        float transY;

        if (index == EXIT_INDEX) {
            // Exit just beyond the first row of the 3x3 preview.
            getGridPosition(0, 3, mTmpPoint);
        } else if (index == ENTER_INDEX) {
            // Enter just beyond the second row of the 3x3 preview.
            getGridPosition(1, 3, mTmpPoint);
        } else if (index >= MAX_NUM_ITEMS_IN_PREVIEW) {
            // Items beyond those displayed in the preview are animated to the center
            mTmpPoint[0] = mTmpPoint[1] = mAvailableSpace / 2 - (mIconSize * totalScale) / 2;
        } else {
            getPosition(index, curNumItems, mTmpPoint);
        }

        transX = mTmpPoint[0];
        transY = mTmpPoint[1];

        if (params == null) {
            params = new PreviewItemDrawingParams(transX, transY, totalScale);
        } else {
            params.update(transX, transY, totalScale);
        }
        return params;
    }

    /**
     * Computes positions for icons in folder as part of spring animation. Here both preview icons
     * and the rest of the content icons are animated along the same grid.
     *
     * @param index          index of icon in folder
     * @param numItemsInPage current number of items in page
     * @param params         params to update for icon
     */
    public PreviewItemDrawingParams computeSpringAnimationItemParams(int index, int numItemsInPage,
            int page, int numFolderColumns, PreviewItemDrawingParams params) {
        float totalScale = scaleForItem(numItemsInPage, page);
        float transX;
        float transY;

        if (numItemsInPage <= MAX_NUM_ITEMS_IN_PREVIEW && page == 0) {
            getPosition(index, numItemsInPage, mTmpPoint);
        } else {
            getGridPosition(index / numFolderColumns, index % numFolderColumns, mTmpPoint);
        }

        transX = mTmpPoint[0];
        transY = mTmpPoint[1];

        if (params == null) {
            params = new PreviewItemDrawingParams(transX, transY, totalScale);
        } else {
            params.update(transX, transY, totalScale);
        }
        return params;
    }


    /** Positions in a fixed 3x3 preview, mirrored in RTL. */
    private void getGridPosition(int row, int col, float[] result) {
        float step = mAvailableSpace * 0.28f;
        result[0] = mAvailableSpace * 0.10f + (mIsRtl ? 2 - col : col) * step;
        result[1] = mAvailableSpace * 0.10f + row * step;
    }

    private void getPosition(int index, int curNumItems, float[] result) {
        getGridPosition(index / 3, index % 3, result);
    }

    /**
     * Calculate Scale for Preview Icons based on current page and number of items in page.
     * @param numItems number of items in page
     * @param page current page of Folder
     * @return scale for icons in Folder
     */
    public float scaleForItem(int numItems, int page) {
        return 0.24f * mBaselineIconScale;
    }

    public float getIconSize() {
        return mIconSize;
    }
}
