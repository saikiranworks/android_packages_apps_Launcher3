/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.view.View.GONE;

import static com.android.launcher3.allapps.SectionDecorationInfo.ROUND_BOTTOM_LEFT;
import static com.android.launcher3.allapps.SectionDecorationInfo.ROUND_BOTTOM_RIGHT;
import static com.android.launcher3.allapps.SectionDecorationInfo.ROUND_NOTHING;
import static com.android.launcher3.allapps.SectionDecorationInfo.ROUND_TOP_LEFT;
import static com.android.launcher3.allapps.SectionDecorationInfo.ROUND_TOP_RIGHT;
import static com.android.launcher3.allapps.UserProfileManager.STATE_DISABLED;
import static com.android.launcher3.allapps.UserProfileManager.STATE_ENABLED;

import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.allapps.search.SearchAdapterProvider;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.popup.PopupContainerWithArrow;
import com.android.launcher3.search.universal.FuzzyMatcher;
import com.android.launcher3.search.universal.SearchHistory;
import com.android.launcher3.search.universal.UniversalSearchResult;
import com.android.launcher3.search.universal.UniversalSearchResults;
import com.android.launcher3.touch.CustomActionsListener;
import com.android.launcher3.views.ActivityContext;

import java.util.List;

/**
 * Adapter for all the apps.
 */
public abstract class BaseAllAppsAdapter
        extends RecyclerView.Adapter<BaseAllAppsAdapter.ViewHolder> {

    public static final String TAG = "BaseAllAppsAdapter";

    // A normal icon
    public static final int VIEW_TYPE_ICON = 1 << 1;
    // The message shown when there are no filtered results
    public static final int VIEW_TYPE_EMPTY_SEARCH = 1 << 2;
    // A divider that separates the apps list and the search market button
    public static final int VIEW_TYPE_ALL_APPS_DIVIDER = 1 << 3;

    public static final int VIEW_TYPE_WORK_EDU_CARD = 1 << 4;
    public static final int VIEW_TYPE_WORK_DISABLED_CARD = 1 << 5;
    public static final int VIEW_TYPE_PRIVATE_SPACE_HEADER = 1 << 6;
    public static final int VIEW_TYPE_PRIVATE_SPACE_SYS_APPS_DIVIDER = 1 << 7;
    public static final int VIEW_TYPE_BOTTOM_VIEW_TO_SCROLL_TO = 1 << 8;
    public static final int VIEW_TYPE_PRIVATE_SPACE_APP_ICON = 1 << 9;
    // Universal search rows (ported from PenguinOS Launcher3).
    public static final int VIEW_TYPE_SEARCH_RESULT_ROW = 1 << 10;
    public static final int VIEW_TYPE_SEARCH_SECTION_HEADER = 1 << 11;
    public static final int VIEW_TYPE_SEARCH_TOP_RESULT = 1 << 12;
    public static final int VIEW_TYPE_SEARCH_FILTERS = 1 << 13;
    public static final int VIEW_TYPE_SEARCH_THUMBNAILS = 1 << 14;
    public static final int NEXT_ID = 15;

    // Common view type masks
    public static final int VIEW_TYPE_MASK_DIVIDER = VIEW_TYPE_ALL_APPS_DIVIDER;
    public static final int VIEW_TYPE_MASK_ICON = VIEW_TYPE_ICON | VIEW_TYPE_PRIVATE_SPACE_APP_ICON;
    public static final int VIEW_TYPE_FOLDER = 1 << 10;

    public static final int VIEW_TYPE_ICON_ROW = 1 << 11;
    public static final int NEXT_ID = 11;

    // Common view type masks
    public static final int VIEW_TYPE_MASK_DIVIDER = VIEW_TYPE_ALL_APPS_DIVIDER;
    public static final int VIEW_TYPE_MASK_ICON =
            VIEW_TYPE_ICON | VIEW_TYPE_PRIVATE_SPACE_APP_ICON | VIEW_TYPE_FOLDER
                    | VIEW_TYPE_ICON_ROW;

    public static final int VIEW_TYPE_MASK_PRIVATE_SPACE_HEADER =
            VIEW_TYPE_PRIVATE_SPACE_HEADER;
    public static final int VIEW_TYPE_MASK_PRIVATE_SPACE_SYS_APPS_DIVIDER =
            VIEW_TYPE_PRIVATE_SPACE_SYS_APPS_DIVIDER;

    protected final SearchAdapterProvider<?> mAdapterProvider;

    /**
     * ViewHolder for each icon.
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {

        public ViewHolder(View v) {
            super(v);
        }
    }

    /** Sets the number of apps to be displayed in one row of the all apps screen. */
    public abstract void setAppsPerRow(int appsPerRow);

    /**
     * Info about a particular adapter item (can be either section or app)
     */
    public static class AdapterItem {
        /** Common properties */
        // The type of this item
        public final int viewType;

        // The row that this item shows up on
        public int rowIndex;
        // The index of this app in the row
        public int rowAppIndex;
        // The associated ItemInfoWithIcon for the item
        public AppInfo itemInfo = null;
        // Private App Decorator
        public SectionDecorationInfo decorationInfo = null;
        // Result from a non-app universal search source
        public UniversalSearchResult searchResult = null;
        // Source id for a universal search section header
        public int searchSection = -1;
        public UniversalSearchResults.Filters searchFilters = null;
        public List<UniversalSearchResult> searchThumbnails = null;
        public AdapterItem(int viewType) {
            this.viewType = viewType;
        }

        public static AdapterItem asSearchResult(UniversalSearchResult result) {
            AdapterItem item = new AdapterItem(VIEW_TYPE_SEARCH_RESULT_ROW);
            item.searchResult = result;
            return item;
        }

        public static AdapterItem asSearchTopResult(UniversalSearchResult result) {
            AdapterItem item = new AdapterItem(VIEW_TYPE_SEARCH_TOP_RESULT);
            item.searchResult = result;
            return item;
        }

        public static AdapterItem asSearchThumbnails(List<UniversalSearchResult> results) {
            AdapterItem item = new AdapterItem(VIEW_TYPE_SEARCH_THUMBNAILS);
            item.searchThumbnails = results;
            return item;
        }

        public static AdapterItem asSearchFilters(UniversalSearchResults.Filters filters) {
            AdapterItem item = new AdapterItem(VIEW_TYPE_SEARCH_FILTERS);
            item.searchFilters = filters;
            return item;
        }

        public static AdapterItem asSearchSection(int source) {
            AdapterItem item = new AdapterItem(VIEW_TYPE_SEARCH_SECTION_HEADER);
            item.searchSection = source;
            return item;
        }

        /**
         * Factory method for AppIcon AdapterItem
         */
        public static AdapterItem asApp(AppInfo appInfo) {
            AdapterItem item = new AdapterItem(VIEW_TYPE_ICON);
            item.itemInfo = appInfo;
            return item;
        }

        public static AdapterItem asAppRow(AppInfo appInfo) {
            AdapterItem item = new AdapterItem(VIEW_TYPE_ICON_ROW);
            item.itemInfo = appInfo;
            return item;
        }

        /** Factory method for an app-drawer (Caddy) category folder tile. */
        public static AdapterItem asFolder(FolderInfo folderInfo) {
            AdapterItem item = new AdapterItem(VIEW_TYPE_FOLDER);
            item.folderInfo = folderInfo;
            return item;
        }

        public static AdapterItem asAppWithDecorationInfo(AppInfo appInfo,
                SectionDecorationInfo decorationInfo, boolean isPrivateSpaceApp) {
            AdapterItem item = new AdapterItem(isPrivateSpaceApp ? VIEW_TYPE_PRIVATE_SPACE_APP_ICON
                    : VIEW_TYPE_ICON);
            item.itemInfo = appInfo;
            item.decorationInfo = decorationInfo;
            return item;
        }

        protected boolean isCountedForAccessibility() {
            return viewType == VIEW_TYPE_ICON;
        }

        /**
         * Returns true if the items represent the same object
         */
        public boolean isSameAs(AdapterItem other) {
            if (other == null || other.viewType != viewType || other.getClass() != getClass()) {
                return false;
            }
            if (viewType == VIEW_TYPE_ICON || viewType == VIEW_TYPE_PRIVATE_SPACE_APP_ICON) {
                if (itemInfo == null || other.itemInfo == null) {
                    return itemInfo == other.itemInfo;
                }
                // Both itemInfo and other.itemInfo are non-null here
                return java.util.Objects.equals(itemInfo.user, other.itemInfo.user)
                        && java.util.Objects.equals(itemInfo.getTargetComponent(),
                                other.itemInfo.getTargetComponent());
            }
            if (viewType == VIEW_TYPE_SEARCH_SECTION_HEADER) {
                return searchSection == other.searchSection;
            }
            if (viewType == VIEW_TYPE_SEARCH_RESULT_ROW
                    || viewType == VIEW_TYPE_SEARCH_TOP_RESULT) {
                return searchResult != null && other.searchResult != null
                        && searchResult.source == other.searchResult.source
                        && java.util.Objects.equals(searchResult.id, other.searchResult.id);
            }
            return true;
        }

        /**
         * This is called only if {@link #isSameAs} returns true to check if the contents are same
         * as well. Returning true will prevent redrawing of thee item.
         */
        public boolean isContentSame(AdapterItem other) {
            if (viewType == VIEW_TYPE_SEARCH_RESULT_ROW || viewType == VIEW_TYPE_SEARCH_TOP_RESULT
                    || viewType == VIEW_TYPE_SEARCH_FILTERS
                    || viewType == VIEW_TYPE_SEARCH_THUMBNAILS) {
                // Titles, subtitles and live state change per query even for the same id.
                return false;
            }
            return itemInfo == null && other.itemInfo == null;
        }

        @Nullable
        public SectionDecorationInfo getDecorationInfo() {
            return decorationInfo;
        }

        /** Sets the alpha of the decorator for this item. */
        protected void setDecorationFillAlpha(int alpha) {
            if (decorationInfo == null || decorationInfo.getDecorationHandler() == null) {
                return;
            }
            decorationInfo.getDecorationHandler().setFillAlpha(alpha);
        }
    }

    protected final ActivityContext mActivityContext;
    protected final AlphabeticalAppsList mApps;
    // The text to show when there are no search results and no market search handler.
    protected int mAppsPerRow;

    protected final LayoutInflater mLayoutInflater;
    protected final OnClickListener mOnIconClickListener;
    protected final OnLongClickListener mOnIconLongClickListener;
    protected final CustomActionsListener mIconCustomActionsListener;
    protected final int mTextColor;
    protected OnFocusChangeListener mIconFocusListener;

    public BaseAllAppsAdapter(ActivityContext activityContext, LayoutInflater inflater,
            AlphabeticalAppsList apps, SearchAdapterProvider<?> adapterProvider) {
        mActivityContext = activityContext;
        mApps = apps;
        mLayoutInflater = inflater;

        mTextColor = AppDrawerStyle.getContentColor(activityContext.asContext());

        mOnIconClickListener = mActivityContext.getItemOnClickListener();
        mOnIconLongClickListener = mActivityContext.getAllAppsItemLongClickListener();
        mIconCustomActionsListener = mActivityContext.getAllAppsItemCustomActionsListener();

        mAdapterProvider = adapterProvider;
    }

    /** Checks if the passed viewType represents all apps divider. */
    public static boolean isDividerViewType(int viewType) {
        return isViewType(viewType, VIEW_TYPE_MASK_DIVIDER);
    }

    /** Checks if the passed viewType represents all apps icon. */
    public static boolean isIconViewType(int viewType) {
        return isViewType(viewType, VIEW_TYPE_MASK_ICON);
    }

    /** Checks if the passed viewType represents private space header. */
    public static boolean isPrivateSpaceHeaderView(int viewType) {
        return isViewType(viewType, VIEW_TYPE_MASK_PRIVATE_SPACE_HEADER);
    }

    /** Checks if the passed viewType represents private space system apps divider. */
    public static boolean isPrivateSpaceSysAppsDividerView(int viewType) {
        return isViewType(viewType, VIEW_TYPE_MASK_PRIVATE_SPACE_SYS_APPS_DIVIDER);
    }

    public void setIconFocusListener(OnFocusChangeListener focusListener) {
        mIconFocusListener = focusListener;
    }

    /**
     * Returns the layout manager.
     */
    public abstract RecyclerView.LayoutManager getLayoutManager();

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        switch (viewType) {
            case VIEW_TYPE_ICON:
                return new ViewHolder(getIconOnCreateSetup(parent));
            case VIEW_TYPE_ICON_ROW:
                return new ViewHolder(getIconRowOnCreateSetup(parent));
            case VIEW_TYPE_PRIVATE_SPACE_APP_ICON:
                BubbleTextView icon = getIconOnCreateSetup(parent);
                icon.setOnClickListener(v ->
                        PopupContainerWithArrow.showForPrivateSpaceApp(icon));
                icon.setOnLongClickListener(v -> {
                    PopupContainerWithArrow.showForPrivateSpaceApp(icon);
                    return true;
                });
                return new ViewHolder(icon);
            case VIEW_TYPE_EMPTY_SEARCH:
                return new ViewHolder(mLayoutInflater.inflate(R.layout.all_apps_empty_search,
                        parent, false));
            case VIEW_TYPE_ALL_APPS_DIVIDER, VIEW_TYPE_PRIVATE_SPACE_SYS_APPS_DIVIDER:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.private_space_divider, parent, false));
            case VIEW_TYPE_WORK_EDU_CARD:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.work_apps_edu, parent, false));
            case VIEW_TYPE_WORK_DISABLED_CARD:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.work_apps_paused, parent, false));
            case VIEW_TYPE_PRIVATE_SPACE_HEADER:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.private_space_header, parent, false));
            case VIEW_TYPE_BOTTOM_VIEW_TO_SCROLL_TO:
                return new ViewHolder(new View(mActivityContext.asContext()));
            case VIEW_TYPE_SEARCH_RESULT_ROW:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.search_result_row, parent, false));
            case VIEW_TYPE_SEARCH_SECTION_HEADER:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.search_section_header, parent, false));
            case VIEW_TYPE_SEARCH_TOP_RESULT:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.search_top_result, parent, false));
            case VIEW_TYPE_SEARCH_FILTERS:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.search_filters, parent, false));
            case VIEW_TYPE_SEARCH_THUMBNAILS:
                return new ViewHolder(mLayoutInflater.inflate(
                        R.layout.search_thumbnails, parent, false));
            case VIEW_TYPE_FOLDER: {
                // Caddy: an auto-categorized folder rendered as a big iOS-style tile. It takes half
                // the grid width (see GridSpanSizer) so two sit side by side, and is square: the
                // preview inside is a 2x2 arrangement, so a tile sized off the icon-row height
                // instead came out wider than it is tall and the cluster floated in dead space.
                // The FolderIcon is inflated/attached on bind.
                int belowPanel = parent.getResources().getDimensionPixelSize(
                        R.dimen.app_library_tile_label_room)
                        + parent.getResources().getDimensionPixelSize(
                                R.dimen.app_library_tile_row_gap);
                FrameLayout tile = new FrameLayout(parent.getContext()) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(
                                MeasureSpec.getSize(widthSpec) + belowPanel,
                                MeasureSpec.EXACTLY));
                    }
                };
                tile.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                // Inset each tile by half the gap we want between them, so the gap down the middle
                // of the row matches the recycler's own left/right margin at the screen edges.
                int sidePad = parent.getResources().getDimensionPixelSize(
                        R.dimen.app_library_tile_side_padding);
                tile.setPadding(sidePad, 0, sidePad, 0);
                return new ViewHolder(tile);
            }
            default:
                if (mAdapterProvider.isViewSupported(viewType)) {
                    return mAdapterProvider.onCreateViewHolder(mLayoutInflater, parent, viewType);
                }
                throw new RuntimeException("Unexpected view type" + viewType);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        holder.itemView.setVisibility(View.VISIBLE);
        switch (holder.getItemViewType()) {
            case VIEW_TYPE_PRIVATE_SPACE_APP_ICON:
            case VIEW_TYPE_ICON_ROW:
            case VIEW_TYPE_ICON: {
                AdapterItem adapterItem = mApps.getAdapterItems().get(position);
                BubbleTextView icon = (BubbleTextView) holder.itemView;
                icon.reset();
                icon.setTextColor(mTextColor);
                if (AppDrawerStyle.isHorizontalList(getDrawerStyle())) {
                    // Guard against views that were inflated for the grid (e.g. recycled).
                    applyHorizontalListLayout(icon);
                }
                icon.applyFromApplicationInfo(adapterItem.itemInfo);
                icon.setOnFocusChangeListener(mIconFocusListener);
                icon.configureMinimalPopup(
                        holder.getItemViewType() == VIEW_TYPE_PRIVATE_SPACE_APP_ICON);
                if (holder.getItemViewType() != VIEW_TYPE_PRIVATE_SPACE_APP_ICON) {
                    // Universal search: highlight the match and remember launches for ranking.
                    AppInfo app = adapterItem.itemInfo;
                    String query = SearchHistory.currentQuery();
                    if (mApps.hasSearchResults() && !query.isEmpty() && app.title != null) {
                        icon.applyLabel(FuzzyMatcher.highlight(query, app.title));
                    }
                    icon.setOnClickListener(!mApps.hasSearchResults() ? mOnIconClickListener
                            : v -> {
                                SearchHistory.recordLaunch(v.getContext(), SearchHistory.appKey(
                                        app.getTargetComponent(), app.user));
                                mOnIconClickListener.onClick(v);
                            });
                }
                PrivateProfileManager privateProfileManager = mApps.getPrivateProfileManager();
                if (privateProfileManager != null) {
                    // Set the alpha of the private space icon to 0 upon expanding the header so the
                    // alpha can animate -> 1. This should only be in effect when doing a
                    // transitioning between Locked/Unlocked state.
                    boolean isPrivateSpaceItem =
                            privateProfileManager.isPrivateSpaceItem(adapterItem);
                    if (icon.getAlpha() == 0 || icon.getAlpha() == 1) {
                        icon.setAlpha(isPrivateSpaceItem
                                && privateProfileManager.isStateTransitioning()
                                && (privateProfileManager.isScrolling() ||
                                    privateProfileManager.getReadyToAnimate())
                                && privateProfileManager.getCurrentState() == STATE_ENABLED
                                ? 0 : 1);
                    }
                    // Views can still be bounded before the app list is updated hence showing icons
                    // after collapsing.
                    if (privateProfileManager.getCurrentState() == STATE_DISABLED
                            && isPrivateSpaceItem) {
                        adapterItem.decorationInfo = null;
                        icon.setVisibility(GONE);
                    }
                }
                break;
            }
            case VIEW_TYPE_SEARCH_SECTION_HEADER: {
                AdapterItem item = mApps.getAdapterItems().get(position);
                ((TextView) holder.itemView).setText(
                        UniversalSearchResults.getSectionTitle(
                                mActivityContext.asContext(), item.searchSection));
                break;
            }
            case VIEW_TYPE_SEARCH_RESULT_ROW:
            case VIEW_TYPE_SEARCH_TOP_RESULT: {
                AdapterItem item = mApps.getAdapterItems().get(position);
                UniversalSearchResults.bind(mActivityContext, holder.itemView, item.searchResult);
                break;
            }
            case VIEW_TYPE_SEARCH_FILTERS: {
                AdapterItem item = mApps.getAdapterItems().get(position);
                UniversalSearchResults.bindFilters(holder.itemView, item.searchFilters);
                break;
            }
            case VIEW_TYPE_SEARCH_THUMBNAILS: {
                AdapterItem item = mApps.getAdapterItems().get(position);
                UniversalSearchResults.bindThumbnails(
                        mActivityContext, holder.itemView, item.searchThumbnails);
                break;
            }
            case VIEW_TYPE_EMPTY_SEARCH: {
                AppInfo info = mApps.getAdapterItems().get(position).itemInfo;
                if (info != null) {
                    ((TextView) holder.itemView).setText(mActivityContext.asContext().getString(
                            R.string.all_apps_no_search_results, info.title));
                }
                break;
            }
            case VIEW_TYPE_PRIVATE_SPACE_HEADER:
                RelativeLayout psHeaderLayout = holder.itemView.findViewById(
                        R.id.ps_header_layout);
                mApps.getPrivateProfileManager().bindPrivateSpaceHeaderViewElements(psHeaderLayout);
                AdapterItem adapterItem = mApps.getAdapterItems().get(position);
                int roundRegions = ROUND_TOP_LEFT | ROUND_TOP_RIGHT;
                if (mApps.getPrivateProfileManager().getCurrentState() == STATE_DISABLED) {
                    roundRegions |= (ROUND_BOTTOM_LEFT | ROUND_BOTTOM_RIGHT);
                }
                adapterItem.decorationInfo =
                        new SectionDecorationInfo(mActivityContext.asContext(), roundRegions);
                break;
            case VIEW_TYPE_PRIVATE_SPACE_SYS_APPS_DIVIDER:
                adapterItem = mApps.getAdapterItems().get(position);
                adapterItem.decorationInfo =
                        mApps.getPrivateProfileManager().getCurrentState() == STATE_DISABLED
                                ? null
                                : new SectionDecorationInfo(
                                        mActivityContext.asContext(), ROUND_NOTHING);
                break;
            case VIEW_TYPE_BOTTOM_VIEW_TO_SCROLL_TO:
            case VIEW_TYPE_ALL_APPS_DIVIDER:
            case VIEW_TYPE_WORK_DISABLED_CARD:
                // nothing to do
                break;
            case VIEW_TYPE_WORK_EDU_CARD:
                ((WorkEduCard) holder.itemView).setPosition(position);
                break;
            case VIEW_TYPE_FOLDER: {
                // Caddy: inflate/attach the category FolderIcon (big preview) into its tile.
                FolderInfo folderInfo = mApps.getAdapterItems().get(position).folderInfo;
                ViewGroup container = (ViewGroup) holder.itemView;
                container.removeAllViews();
                if (folderInfo != null) {
                    FolderIcon folderIcon = FolderIcon.inflateIcon(
                            R.layout.all_apps_big_folder_icon,
                            ActivityContext.lookupContext(container.getContext()),
                            container, folderInfo);
                    container.addView(folderIcon, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                }
                break;
            }
            default:
                if (mAdapterProvider.isViewSupported(holder.getItemViewType())) {
                    mAdapterProvider.onBindView(holder, position);
                }
        }
    }

    /**
     * Returns the {@link AppDrawerStyle} used to inflate app icons. Defaults to the stock grid.
     */
    protected String getDrawerStyle() {
        return AppDrawerStyle.NORMAL;
    private BubbleTextView getIconRowOnCreateSetup(ViewGroup parent) {
        BubbleTextView icon = (BubbleTextView) mLayoutInflater.inflate(
                R.layout.all_apps_icon_row, parent, false);
        icon.setLongPressTimeoutFactor(1f);
        icon.setOnFocusChangeListener(mIconFocusListener);
        icon.setOnClickListener(mOnIconClickListener);
        icon.setOnLongClickListener(mOnIconLongClickListener);
        icon.setCustomActionsListener(mIconCustomActionsListener);
        return icon;
    }

    private BubbleTextView getIconOnCreateSetup(ViewGroup parent) {
        final boolean horizontalList = AppDrawerStyle.isHorizontalList(getDrawerStyle());
        final int layout;
        if (horizontalList) {
            layout = R.layout.all_apps_icon_horizontal_list;
        } else {
            layout = LauncherPrefs.ENABLE_TWOLINE_ALLAPPS_TOGGLE.get(mActivityContext.asContext())
                    ? R.layout.all_apps_icon_twoline : R.layout.all_apps_icon;
        }
        BubbleTextView icon = (BubbleTextView) mLayoutInflater.inflate(
                layout, parent, false);
        icon.setLongPressTimeoutFactor(1f);
        icon.setOnFocusChangeListener(mIconFocusListener);
        icon.setOnClickListener(mOnIconClickListener);
        icon.setOnLongClickListener(mOnIconLongClickListener);
        icon.setCustomActionsListener(mIconCustomActionsListener);
        if (horizontalList) {
            applyHorizontalListLayout(icon);
        } else {
            // Ensure the all apps icon height matches the workspace icons in portrait mode.
            icon.getLayoutParams().height =
                    mActivityContext.getDeviceProfile().getAllAppsProfile().getCellHeightPx();
        }
        return icon;
    }

    /** Icon on the start side with the label right after it, spanning the full row. */
    private static void applyHorizontalListLayout(BubbleTextView icon) {
        icon.setLayoutHorizontal(true);
        icon.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                || lp.height != ViewGroup.LayoutParams.WRAP_CONTENT)) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            icon.setLayoutParams(lp);
        }
    }

    @Override
    public boolean onFailedToRecycleView(ViewHolder holder) {
        // Always recycle and we will reset the view when it is bound
        return true;
    }

    @Override
    public int getItemCount() {
        return mApps.getAdapterItems().size();
    }

    @Override
    public int getItemViewType(int position) {
        AdapterItem item = mApps.getAdapterItems().get(position);
        return item.viewType;
    }

    protected static boolean isViewType(int viewType, int viewTypeMask) {
        return (viewType & viewTypeMask) != 0;
    }

}
