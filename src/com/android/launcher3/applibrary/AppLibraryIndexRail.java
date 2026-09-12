
package com.android.launcher3.applibrary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;
import com.android.launcher3.allapps.AlphabeticalAppsList;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.launcher3.util.Themes;

import java.util.ArrayList;
import java.util.List;

public class AppLibraryIndexRail extends View {

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> mSections = new ArrayList<>();
    private final List<Integer> mPositions = new ArrayList<>();
    private final int mLineHeight;

    private RecyclerView mRecyclerView;
    private AlphabeticalAppsList mApps;
    private int mLastTouchedIndex = -1;

    public AppLibraryIndexRail(Context context) {
        this(context, null);
    }

    public AppLibraryIndexRail(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppLibraryIndexRail(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setFakeBoldText(true);
        mPaint.setTextSize(
                getResources().getDimensionPixelSize(R.dimen.app_library_index_text_size));
        mLineHeight = getResources().getDimensionPixelSize(R.dimen.app_library_index_line_height);
        mPaint.setColor(GraphicsUtils.setColorAlphaBound(
                Themes.getAttrColor(context, android.R.attr.textColorPrimary), 190));
    }

    public void setup(RecyclerView recyclerView, AlphabeticalAppsList apps) {
        mRecyclerView = recyclerView;
        mApps = apps;
    }

    public void refresh() {
        mSections.clear();
        mPositions.clear();
        if (mApps == null) {
            return;
        }
        List<AdapterItem> items = mApps.getAdapterItems();
        String previous = null;
        for (int i = 0; i < items.size(); i++) {
            String section = AppLibrarySections.of(items.get(i));
            if (section != null && !section.equals(previous)) {
                mSections.add(section);
                mPositions.add(i);
                previous = section;
            }
        }
        setVisibility(mSections.size() > 1 ? VISIBLE : GONE);
        invalidate();

    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSections.isEmpty()) {
            return;
        }
        float step = rowHeight();
        float x = getWidth() / 2f;
        float centerOffset = (mPaint.descent() + mPaint.ascent()) / 2f;
        float top = railTop();
        for (int i = 0; i < LETTERS.length(); i++) {
            float center = top + step * (i + 0.5f);
            canvas.drawText(String.valueOf(LETTERS.charAt(i)), x, center - centerOffset, mPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mSections.isEmpty() || mRecyclerView == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
            case MotionEvent.ACTION_MOVE:
                jumpTo(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLastTouchedIndex = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void jumpTo(float y) {
        int index = (int) ((y - railTop()) / rowHeight());
        index = Math.max(0, Math.min(LETTERS.length() - 1, index));
        if (index == mLastTouchedIndex) {
            return;
        }
        mLastTouchedIndex = index;

        String letter = String.valueOf(LETTERS.charAt(index));
        int position = mPositions.get(mPositions.size() - 1);
        for (int i = 0; i < mSections.size(); i++) {
            if (mSections.get(i).compareTo(letter) >= 0) {
                position = mPositions.get(i);
                break;
            }
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (mRecyclerView.getLayoutManager() instanceof LinearLayoutManager lm) {
            lm.scrollToPositionWithOffset(position, 0);
        } else {
            mRecyclerView.scrollToPosition(position);
        }
    }

    private float rowHeight() {
        return Math.min(mLineHeight, (float) available() / LETTERS.length());
    }

    private float railTop() {
        return getPaddingTop() + (available() - rowHeight() * LETTERS.length()) / 2f;
    }

    private int available() {
        return Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
    }
}
