
package com.android.launcher3.applibrary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.R;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.launcher3.allapps.AlphabeticalAppsList;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.util.Themes;

import java.util.List;
import java.util.function.BooleanSupplier;

public class AppLibraryRowDecoration extends RecyclerView.ItemDecoration {

    private final AlphabeticalAppsList mApps;
    private final BooleanSupplier mTransitionRunning;
    private final Paint mDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mIconBounds = new Rect();
    private final int mRowPadding;
    private final int mRowEndPadding;
    private final int mSectionHeight;

    public AppLibraryRowDecoration(Context context, AlphabeticalAppsList apps,
            BooleanSupplier transitionRunning) {
        mApps = apps;
        mTransitionRunning = transitionRunning;
        mRowPadding = context.getResources()
                .getDimensionPixelSize(R.dimen.all_apps_row_horizontal_padding);
        mRowEndPadding = context.getResources()
                .getDimensionPixelSize(R.dimen.app_library_row_end_padding);
        mSectionHeight = context.getResources()
                .getDimensionPixelSize(R.dimen.all_apps_row_section_height);

        int textColor = Themes.getAttrColor(context, android.R.attr.textColorPrimary);
        mDividerPaint.setColor(GraphicsUtils.setColorAlphaBound(textColor, 40));
        mDividerPaint.setStrokeWidth(context.getResources()
                .getDimensionPixelSize(R.dimen.all_apps_row_divider_height));
        mSectionPaint.setColor(textColor);
        mSectionPaint.setTextSize(context.getResources()
                .getDimensionPixelSize(R.dimen.all_apps_row_section_text_size));
        mSectionPaint.setFakeBoldText(true);
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
            RecyclerView.State state) {
        int position = parent.getChildLayoutPosition(view);
        if (startsSection(position)) {
            outRect.top = mSectionHeight;
        }
    }

    @Override
    public void onDraw(Canvas canvas, RecyclerView parent, RecyclerView.State state) {
        if (mTransitionRunning.getAsBoolean()) {
            return;
        }
        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) {
                continue;
            }
            String section = sectionAt(position);
            if (section == null) {
                continue;
            }
            float top = child.getY();
            if (startsSection(position)) {
                canvas.drawText(section, parent.getPaddingLeft() + mRowPadding,
                        top - mSectionHeight / 4f, mSectionPaint);
            } else {
                canvas.drawLine(dividerStart(child), top,
                        parent.getWidth() - parent.getPaddingRight() - mRowEndPadding,
                        top, mDividerPaint);
            }
        }
    }

    private float dividerStart(View child) {
        if (child instanceof BubbleTextView icon) {
            icon.getIconBounds(mIconBounds);
            return child.getLeft() + mIconBounds.right + icon.getCompoundDrawablePadding();
        }
        return child.getLeft() + mRowPadding;
    }

    private String sectionAt(int position) {
        List<AdapterItem> items = mApps.getAdapterItems();
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return AppLibrarySections.of(items.get(position));
    }

    private boolean startsSection(int position) {
        String section = sectionAt(position);
        if (section == null) {
            return false;
        }
        String previous = sectionAt(position - 1);
        return !section.equals(previous);
    }
}
