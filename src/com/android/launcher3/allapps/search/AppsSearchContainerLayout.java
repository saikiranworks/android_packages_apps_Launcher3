/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.allapps.search;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getSize;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.Utilities.prefixTextWithIcon;
import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup.MarginLayoutParams;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Insettable;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.allapps.AllAppsStore;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.PrivateProfileManager;
import com.android.launcher3.allapps.SearchUiManager;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.search.universal.UniversalSearchAlgorithm;
import com.android.launcher3.statemanager.StateManager.StateListener;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;

/**
 * Layout to contain the All-apps search UI.
 */
public class AppsSearchContainerLayout extends ExtendedEditText
        implements SearchUiManager, SearchCallback<AdapterItem>,
        AllAppsStore.OnUpdateListener, Insettable {

    private final ActivityContext mLauncher;
    private final AllAppsSearchBarController mSearchBarController;
    private final SpannableStringBuilder mSearchQueryBuilder;

    private ActivityAllAppsContainerView<?> mAppsView;

    // The amount of pixels to shift down and overlap with the rest of the content.
    private final int mContentOverlap;

    private int mSystemBottomInset;
    private int mImeInset;

    private final StateListener<LauncherState> mDrawerOpenListener = new StateListener<>() {
        @Override
        public void onStateTransitionComplete(LauncherState finalState) {
            if (finalState == LauncherState.ALL_APPS
                    && LauncherPrefs.SEARCH_AUTO_KEYBOARD.get(getContext())) {
                showKeyboard();
            }
        }
    };

    public AppsSearchContainerLayout(Context context) {
        this(context, null);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mLauncher = ActivityContext.lookupContext(context);
        mSearchBarController = new AllAppsSearchBarController();

        mSearchQueryBuilder = new SpannableStringBuilder();
        Selection.setSelection(mSearchQueryBuilder, 0);

        mContentOverlap =
                getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_content_overlap);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if(mAppsView != null)
            mAppsView.getAppsStore().addUpdateListener(this);
        if (mLauncher instanceof Launcher launcher) {
            launcher.getStateManager().addStateListener(mDrawerOpenListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if(mAppsView != null)
            mAppsView.getAppsStore().removeUpdateListener(this);
        if (mLauncher instanceof Launcher launcher) {
            launcher.getStateManager().removeStateListener(mDrawerOpenListener);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Update the width to match the grid padding
        DeviceProfile dp = mLauncher.getDeviceProfile();
        int myRequestedWidth = getSize(widthMeasureSpec);
        int rowWidth = myRequestedWidth - mAppsView.getActiveRecyclerView().getPaddingLeft()
                - mAppsView.getActiveRecyclerView().getPaddingRight();

        int cellWidth = DeviceProfile.calculateCellWidth(rowWidth,
                dp.getWorkspaceProfile().getCellLayoutBorderSpacePx().x,
                dp.getHotseatProfile().getNumShownIcons());
        int iconVisibleSize =
                Math.round(ICON_VISIBLE_AREA_FACTOR * dp.getWorkspaceProfile().getIconSizePx());
        int iconPadding = cellWidth - iconVisibleSize;

        int myWidth = rowWidth - iconPadding + getPaddingLeft() + getPaddingRight()
                - mAppsView.getSearchCancelWidth()
                - 2 * getResources().getDimensionPixelSize(R.dimen.all_apps_search_side_gap);
        if (isFloatingPill()) {
            myWidth = Math.min(myWidth,
                    getResources().getDimensionPixelSize(R.dimen.all_apps_floating_search_width));
        }
        super.onMeasure(makeMeasureSpec(myWidth, EXACTLY), heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Shift the widget horizontally so that its centered in the parent (b/63428078)
        View parent = (View) getParent();
        int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int myWidth = right - left + mAppsView.getSearchCancelWidth();
        int expectedLeft = parent.getPaddingLeft() + (availableWidth - myWidth) / 2;
        setTranslationX(expectedLeft - left);

        if (!mAppsView.isAppLibrary()) {
            offsetTopAndBottom(mContentOverlap);
        }
    }

    /** At the bottom of the drawer the bar floats as a pill, as on One UI. */
    private boolean isFloatingPill() {
        return !mAppsView.isSearchBarAtTop();
    }

    @Override
    public void initializeSearch(ActivityAllAppsContainerView<?> appsView) {
        mAppsView = appsView;
        if (isFloatingPill()) {
            setMinHeight(getResources().getDimensionPixelSize(
                    R.dimen.all_apps_floating_search_height));
        }
        // Keep the same providers in the normal drawer, taskbar drawer and App Library.
        UniversalSearchAlgorithm algorithm =
                new UniversalSearchAlgorithm(getContext(), mLauncher.getUiExecutor());
        algorithm.setQueryHandler(query -> {
            setText(query);
            setSelection(query.length());
        });
        mSearchBarController.initialize(algorithm, this, mLauncher, this);
        android.graphics.drawable.PaintDrawable background =
                new android.graphics.drawable.PaintDrawable(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(
                                getCurrentTextColor(), 0x33));
        background.setCornerRadius(getResources().getDimension(
                R.dimen.rounded_button_radius));
        setBackground(background);
    }

    @Override
    public void onAppsUpdated() {
        mSearchBarController.refreshSearchResult();
    }

    @Override
    public void resetSearch() {
        mSearchBarController.reset();
    }

    @Override
    public boolean isSearchQueryEmpty() {
        String query = Utilities.trim(getEditableText().toString());
        return query.isEmpty();
    }

    @Override
    public void preDispatchKeyEvent(KeyEvent event) {
        // Determine if the key event was actual text, if so, focus the search bar and then dispatch
        // the key normally so that it can process this key event
        if (!mSearchBarController.isSearchFieldFocused() &&
                event.getAction() == KeyEvent.ACTION_DOWN) {
            final int unicodeChar = event.getUnicodeChar();
            final boolean isKeyNotWhitespace = unicodeChar > 0 &&
                    !Character.isWhitespace(unicodeChar) && !Character.isSpaceChar(unicodeChar);
            if (isKeyNotWhitespace) {
                boolean gotKey = TextKeyListener.getInstance().onKeyDown(this, mSearchQueryBuilder,
                        event.getKeyCode(), event);
                if (gotKey && mSearchQueryBuilder.length() > 0) {
                    mSearchBarController.focusSearchField();
                }
            }
        }
    }

    @Override
    public void onSearchResult(String query, ArrayList<AdapterItem> items) {
        if (query.equalsIgnoreCase(getContext().getString(R.string.private_space_label))) {
            privateSpaceQuery();
            return;
        }
        if (items != null) {
            mAppsView.setSearchResults(items);
        }
    }

    @Override
    public void clearSearchResult() {
        // Clear the search query
        mSearchQueryBuilder.clear();
        mSearchQueryBuilder.clearSpans();
        Selection.setSelection(mSearchQueryBuilder, 0);
        mAppsView.onClearSearchResult();
    }

    @Override
    public void setInsets(Rect insets) {
        MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        mSystemBottomInset = insets.bottom + bottomGap();
        mlp.topMargin = 0;
        mlp.bottomMargin = mAppsView.isSearchBarAtTop() ? 0 : mSystemBottomInset + mImeInset;
        requestLayout();
    }

    private int bottomGap() {
        return mAppsView.isAppLibrary() ? 0 : getResources().getDimensionPixelSize(
                R.dimen.all_apps_search_bar_bottom_padding);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
        if (ime != mImeInset) {
            mImeInset = ime;
            MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
            mlp.bottomMargin = mAppsView.isSearchBarAtTop() ? 0
                    : mSystemBottomInset + mImeInset;
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    public ExtendedEditText getEditText() {
        return this;
    }

    private void privateSpaceQuery() {
        PrivateProfileManager privateProfileManager = mAppsView.getPrivateProfileManager();
        if (privateProfileManager.isPrivateSpaceHidden()) {
            privateProfileManager.setQuietMode(false);
        } else if (!mAppsView.hasPrivateProfile()) {
            final Intent privateSpaceSettingsIntent =
                    ApiWrapper.INSTANCE.get(getContext()).getPrivateSpaceSettingsIntent();
            if (privateSpaceSettingsIntent != null) {
                mLauncher.startActivitySafely(mAppsView, privateSpaceSettingsIntent, null);
            }
        }
    }
}
