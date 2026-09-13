/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static com.android.launcher3.UtilitiesKt.drawWorkspaceItemSelectionHighlight;
import static com.android.launcher3.folder.ClippedFolderIconLayoutRule.ICON_OVERLAP_FACTOR;
import static com.android.launcher3.folder.ClippedFolderIconLayoutRule.MAX_NUM_ITEMS_IN_PREVIEW;
import static com.android.launcher3.folder.FolderGridOrganizer.createFolderGridOrganizer;
import static com.android.launcher3.folder.PreviewItemManager.INITIAL_ITEM_ANIMATION_DURATION;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_FOLDER_AUTO_LABELED;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_FOLDER_AUTO_LABELING_SKIPPED_EMPTY_PRIMARY;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_FOLDER_AUTO_LABELING_SKIPPED_EMPTY_SUGGESTIONS;
import static com.android.launcher3.model.data.FolderInfo.willAcceptItemType;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Property;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.app.animation.Interpolators;
import com.android.launcher3.Alarm;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.CellLayout;
import com.android.launcher3.CheckLongPressHelper;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DropTarget.DragObject;
import com.android.launcher3.Flags;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.OnAlarmListener;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.dot.FolderDotInfo;
import com.android.launcher3.dragndrop.BaseItemDragListener;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.dragndrop.DragView;
import com.android.launcher3.dragndrop.DraggableView;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.homescreenfiles.HomeScreenFilesUtils;
import com.android.launcher3.icons.DotRenderer;
import com.android.launcher3.logger.LauncherAtom.FromState;
import com.android.launcher3.logger.LauncherAtom.ToState;
import com.android.launcher3.logging.InstanceId;
import com.android.launcher3.logging.StatsLogManager;
import com.android.launcher3.model.data.AppPairInfo;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.FolderInfo.LabelState;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemFactory;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.IconViewController;
import com.android.launcher3.popup.Poppable;
import com.android.launcher3.popup.PoppableType;
import com.android.launcher3.touch.CustomActionsListener;
import com.android.launcher3.touch.CustomEventsTouchHandler;
import com.android.launcher3.touch.CustomTouchDelegate;
import com.android.launcher3.touch.ItemClickHandler;
import com.android.launcher3.touch.WorkspaceItemCustomActionsListener;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;
import com.android.launcher3.util.Thunk;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.FloatingIconViewCompanion;
import com.android.launcher3.widget.PendingAddShortcutInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * An icon that can appear on in the workspace representing an {@link Folder}.
 */
public class FolderIcon extends FrameLayout implements FloatingIconViewCompanion,
        DraggableView, Reorderable, Poppable, IconViewController, CustomTouchDelegate {

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    @Thunk ActivityContext mActivity;
    @Thunk Folder mFolder;
    public FolderInfo mInfo;

    private final CheckLongPressHelper mLongPressHelper;
    // TODO(b/465247812): Remove this and overridden functions in favor of Kotlin interface
    //  delegation, upon file conversion to Kotlin.
    private final CustomEventsTouchHandler mCustomEventsTouchHandler;

    static final int DROP_IN_ANIMATION_DURATION = 400;

    // Flag whether the folder should open itself when an item is dragged over is enabled.
    public static final boolean SPRING_LOADING_ENABLED = true;

    // Delay when drag enters until the folder opens, in miliseconds.
    private static final int ON_OPEN_DELAY = 800;

    @Thunk BubbleTextView mFolderName;

    PreviewBackground mBackground = new PreviewBackground(getContext());
    private boolean mBackgroundIsVisible = true;
    // Opacity of the big (2x2) preview only, so the open/close animation can cross-fade the tile
    // against the folder content instead of cutting between them. Ignored by normal folders.
    private float mBigPreviewAlpha = 1f;

    FolderGridOrganizer mPreviewVerifier;
    final ClippedFolderIconLayoutRule mPreviewLayoutRule;
    private final PreviewItemManager mPreviewItemManager;
    // Renders the "big" (2x2) folder preview and owns its per-quadrant tap hit-testing. Only used
    // while mInfo.isBigFolder() is true; normal folders keep using mPreviewItemManager.
    private final LargeFolderPreview mLargeFolderPreview;
    private PreviewItemDrawingParams mTmpParams = new PreviewItemDrawingParams(0, 0, 0);
    private final List<ItemInfo> mCurrentPreviewItems = new ArrayList<>();

    // App picked out by an ACTION_DOWN on a big folder's large icon quadrant; if non-null when the
    // click fires, that app is launched directly instead of opening the folder.
    @Nullable private WorkspaceItemInfo mPendingLaunchTarget;

    boolean mAnimating = false;

    private final Alarm mOpenAlarm = new Alarm(getContext().getMainLooper());

    private boolean mForceHideDot;
    @ViewDebug.ExportedProperty(category = "launcher", deepExport = true)
    private final FolderDotInfo mDotInfo = new FolderDotInfo();
    private DotRenderer mDotRenderer;
    @ViewDebug.ExportedProperty(category = "launcher", deepExport = true)
    private final DotRenderer.DrawParams mDotParams;
    private float mDotScale;
    private Animator mDotScaleAnim;

    private Rect mTouchArea = new Rect();

    private float mScaleForReorderBounce = 1f;

    private static final Property<FolderIcon, Float> DOT_SCALE_PROPERTY
            = new Property<FolderIcon, Float>(Float.TYPE, "dotScale") {
        @Override
        public Float get(FolderIcon folderIcon) {
            return folderIcon.mDotScale;
        }

        @Override
        public void set(FolderIcon folderIcon, Float value) {
            folderIcon.mDotScale = value;
            folderIcon.invalidate();
        }
    };

    /** Fades the big (2x2) preview panel in/out; see {@link #setBigPreviewAlpha}. */
    public static final FloatProperty<FolderIcon> BIG_PREVIEW_ALPHA
            = new FloatProperty<FolderIcon>("bigPreviewAlpha") {
        @Override
        public Float get(FolderIcon folderIcon) {
            return folderIcon.mBigPreviewAlpha;
        }

        @Override
        public void setValue(FolderIcon folderIcon, float value) {
            folderIcon.setBigPreviewAlpha(value);
        }
    };


    public FolderIcon(Context context) {
        this(context, null);
    }

    public FolderIcon(Context context, AttributeSet attrs) {
        super(context, attrs);

        mLongPressHelper = new CheckLongPressHelper(this);
        mCustomEventsTouchHandler = new CustomEventsTouchHandler(this, (event) -> {
            // Call the superclass onTouchEvent first, because sometimes it changes the state to
            // isPressed() on an ACTION_UP
            super.onTouchEvent(event);
            mLongPressHelper.onTouchEvent(event);
            // Keep receiving the rest of the events
            return true;
        }, this::shouldIgnoreTouchDown);
        mPreviewLayoutRule = new ClippedFolderIconLayoutRule();
        mPreviewItemManager = new PreviewItemManager(this);
        mLargeFolderPreview = new LargeFolderPreview(this);
        mDotParams = new DotRenderer.DrawParams();
        mDotParams.setDotColor(Themes.getAttrColor(context, R.attr.notificationDotColor));
        mDotParams.shapeInfo = ThemeManager.INSTANCE.get(context).getIconState().getIconShapeInfo();
    }

    public static <T extends Context & ActivityContext> FolderIcon inflateFolderAndIcon(int resId,
            T activityContext, ViewGroup group, FolderInfo folderInfo) {
        Folder folder = Folder.fromXml(activityContext);

        FolderIcon icon = inflateIcon(resId, activityContext, group, folderInfo);
        folder.setFolderIcon(icon);
        folder.bind(folderInfo);

        icon.setFolder(folder);
        return icon;
    }

    /**
     * Builds a FolderIcon to be added to the activity.
     * This method doesn't add any listeners to the FolderInfo, and hence any changes to the info
     * will not be reflected in the folder.
     */
    public static FolderIcon inflateIcon(int resId, ActivityContext activity,
            @Nullable ViewGroup group, FolderInfo folderInfo) {
        @SuppressWarnings("all") // suppress dead code warning
        final boolean error = INITIAL_ITEM_ANIMATION_DURATION >= DROP_IN_ANIMATION_DURATION;
        if (error) {
            throw new IllegalStateException("DROP_IN_ANIMATION_DURATION must be greater than " +
                    "INITIAL_ITEM_ANIMATION_DURATION, as sequencing of adding first two items " +
                    "is dependent on this");
        }

        DeviceProfile grid = activity.getDeviceProfile();
        LayoutInflater inflater = (group != null)
                ? LayoutInflater.from(group.getContext())
                : activity.getLayoutInflater();
        FolderIcon icon = (FolderIcon) inflater.inflate(resId, group, false);

        icon.setClipToPadding(false);
        icon.mFolderName = icon.findViewById(R.id.folder_icon_name);
        icon.mFolderName.applyLabel(folderInfo.title);
        icon.mFolderName.setCompoundDrawablePadding(0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) icon.mFolderName.getLayoutParams();
        lp.topMargin = grid.getWorkspaceProfile().getIconSizePx()
                + grid.getWorkspaceProfile().getIconDrawablePaddingPx();

        icon.setTag(folderInfo);
        if (folderInfo.forceBigPreview) {
            // Caddy drawer category tiles are inflated without a workspace Folder (see
            // BaseAllAppsAdapter), so the default item click handler -- which calls animateOpen()
            // on FolderIcon#getFolder() -- has nothing to open. Open the in-drawer category page.
            icon.setOnClickListener(v ->
                    com.android.launcher3.allapps.CaddyCategoryView.show((FolderIcon) v));
        } else {
            icon.setOnClickListener(activity.getItemOnClickListener());
        }
        icon.setCustomActionsListener(WorkspaceItemCustomActionsListener.INSTANCE);
        icon.mInfo = folderInfo;
        icon.mActivity = activity;
        icon.mDotRenderer = new DotRenderer(
                grid.getWorkspaceProfile().getIconSizePx()
        );

        icon.updateDotInfo();
        icon.setContentDescription(icon.getAccessiblityTitle(folderInfo.title));

        icon.setAccessibilityDelegate(activity.getAccessibilityDelegate());

        icon.mPreviewVerifier = createFolderGridOrganizer(activity.getDeviceProfile());
        icon.mPreviewVerifier.setFolderInfo(folderInfo);
        icon.updatePreviewItems(false);

        return icon;
    }

    public void animateBgShadowAndStroke() {
        mBackground.fadeInBackgroundShadow();
        mBackground.animateBackgroundStroke();
    }

    public BubbleTextView getFolderName() {
        return mFolderName;
    }

    public LargeFolderPreview getLargeFolderPreview() {
        return mLargeFolderPreview;
    }

    public void getPreviewBounds(Rect outBounds) {
        if (isBigFolder()) {
            // Big folders occupy the whole 2x2 preview panel, not the small circular preview; using
            // its bounds keeps drag/animation geometry (and the open/close shrink) on the big area.
            mLargeFolderPreview.getPreviewRect(outBounds);
            return;
        }
        mPreviewItemManager.recomputePreviewDrawingParams();
        mBackground.getBounds(outBounds);
        // The preview items go outside of the bounds of the background.
        Utilities.scaleRectAboutCenter(outBounds, ICON_OVERLAP_FACTOR);
    }

    public float getBackgroundStrokeWidth() {
        return mBackground.getStrokeWidth();
    }

    public Folder getFolder() {
        return mFolder;
    }

    private void setFolder(Folder folder) {
        mFolder = folder;
    }

    private boolean willAcceptItem(ItemInfo item) {
        // Caddy drawer tiles are inflated without a Folder (see BaseAllAppsAdapter): they are not
        // drop targets, so refuse everything rather than dereference a null Folder.
        return mFolder != null
                && (willAcceptItemType(item.itemType) && item != mInfo && !mFolder.isOpen());
    }

    public boolean acceptDrop(ItemInfo dragInfo) {
        return mFolder != null && !mFolder.isDestroyed() && willAcceptItem(dragInfo);
    }

    public void onDragEnter(ItemInfo dragInfo) {
        if (mFolder == null || mFolder.isDestroyed() || !willAcceptItem(dragInfo)) return;
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) getLayoutParams();
        CellLayout cl = (CellLayout) getParent().getParent();

        mBackground.animateToAccept(cl, lp.getCellX(), lp.getCellY());
        // Big folders don't draw mBackground, so light up the big panel instead for clear feedback.
        if (isBigFolder() && mLargeFolderPreview.setAccepting(true)) {
            invalidate();
        }
        mOpenAlarm.setOnAlarmListener(mOnOpenListener);
        if (SPRING_LOADING_ENABLED &&
                ((dragInfo instanceof WorkspaceItemFactory)
                        || (dragInfo instanceof PendingAddShortcutInfo)
                        || Folder.willAccept(dragInfo))) {
            mOpenAlarm.setAlarm(ON_OPEN_DELAY);
        }
    }

    OnAlarmListener mOnOpenListener = new OnAlarmListener() {
        public void onAlarm(Alarm alarm) {
            mFolder.beginExternalDrag();
        }
    };

    public Drawable prepareCreateAnimation(final View destView) {
        return mPreviewItemManager.prepareCreateAnimation(destView);
    }

    public void performCreateAnimation(final ItemInfo destInfo, final View destView,
            final ItemInfo srcInfo, final DragObject d, Rect dstRect,
            float scaleRelativeToDragLayer) {
        prepareCreateAnimation(destView);
        getFolder().addFolderContent(destInfo);
        // This will animate the first item from it's position as an icon into its
        // position as the first item in the preview
        mPreviewItemManager.createFirstItemAnimation(false /* reverse */, null)
                .start();

        // This will animate the dragView (srcView) into the new folder
        onDrop(srcInfo, d, dstRect, scaleRelativeToDragLayer, 1,
                false /* itemReturnedOnFailedDrop */);
    }

    public void performDestroyAnimation(Runnable onCompleteRunnable) {
        // This will animate the final item in the preview to be full size.
        mPreviewItemManager.createFirstItemAnimation(true /* reverse */, onCompleteRunnable)
                .start();
    }

    public void onDragExit() {
        mBackground.animateToRest();
        if (mLargeFolderPreview.setAccepting(false)) {
            invalidate();
        }
        mOpenAlarm.cancelAlarm();
    }

    private void onDrop(final ItemInfo item, DragObject d, Rect finalRect,
            float scaleRelativeToDragLayer, int index, boolean itemReturnedOnFailedDrop) {
        item.cellX = -1;
        item.cellY = -1;
        DragView animateView = d.dragView;
        // Typically, the animateView corresponds to the DragView; however, if this is being done
        // after a configuration activity (ie. for a Shortcut being dragged from AllApps) we
        // will not have a view to animate
        if (animateView != null && mActivity instanceof Launcher) {
            final Launcher launcher = (Launcher) mActivity;
            DragLayer dragLayer = launcher.getDragLayer();
            Rect to = finalRect;
            if (to == null) {
                to = new Rect();
                Workspace<?> workspace = launcher.getWorkspace();
                // Set cellLayout and this to it's final state to compute final animation locations
                workspace.setFinalTransitionTransform();
                float scaleX = getScaleX();
                float scaleY = getScaleY();
                setScaleX(1.0f);
                setScaleY(1.0f);
                scaleRelativeToDragLayer = dragLayer.getDescendantRectRelativeToSelf(this, to);
                // Finished computing final animation locations, restore current state
                setScaleX(scaleX);
                setScaleY(scaleY);
                workspace.resetTransitionTransform();
            }

            int numItemsInPreview = Math.min(MAX_NUM_ITEMS_IN_PREVIEW, index + 1);
            boolean itemAdded = false;
            if (itemReturnedOnFailedDrop || index >= MAX_NUM_ITEMS_IN_PREVIEW) {
                List<ItemInfo> oldPreviewItems = new ArrayList<>(mCurrentPreviewItems);
                getFolder().addFolderContent(item, index, false);
                mCurrentPreviewItems.clear();
                mCurrentPreviewItems.addAll(getPreviewItemsOnPage(0));

                if (!oldPreviewItems.equals(mCurrentPreviewItems)) {
                    int newIndex = mCurrentPreviewItems.indexOf(item);
                    if (newIndex >= 0) {
                        // If the item dropped is going to be in the preview, we update the
                        // index here to reflect its position in the preview.
                        index = newIndex;
                    }

                    mPreviewItemManager.hidePreviewItem(index, true);
                    mPreviewItemManager.onDrop(oldPreviewItems, mCurrentPreviewItems, item);
                    itemAdded = true;
                } else {
                    getFolder().removeFolderContent(false, item);
                }
            }

            if (!itemAdded) {
                getFolder().addFolderContent(item, index, true);
            }

            int[] center = new int[2];
            float scale = getLocalCenterForIndex(index, numItemsInPreview, center);
            center[0] = Math.round(scaleRelativeToDragLayer * center[0]);
            center[1] = Math.round(scaleRelativeToDragLayer * center[1]);

            to.offset(center[0] - animateView.getMeasuredWidth() / 2,
                    center[1] - animateView.getMeasuredHeight() / 2);

            float finalAlpha = index < MAX_NUM_ITEMS_IN_PREVIEW ? 1f : 0f;

            float finalScale = scale * scaleRelativeToDragLayer;

            // Account for potentially different icon sizes with non-default grid settings
            if (d.dragSource instanceof ActivityAllAppsContainerView) {
                DeviceProfile grid = mActivity.getDeviceProfile();
                float containerScale = (1f * grid.getWorkspaceProfile().getIconSizePx()
                        / grid.getAllAppsProfile().getIconSizePx());
                finalScale *= containerScale;
            }

            final int finalIndex = index;
            dragLayer.animateView(animateView, to, finalAlpha,
                    finalScale, finalScale, DROP_IN_ANIMATION_DURATION,
                    Interpolators.DECELERATE_2,
                    () -> {
                        mPreviewItemManager.hidePreviewItem(finalIndex, false);
                        mFolder.showItem(item);
                    },
                    DragLayer.ANIMATION_END_DISAPPEAR, null);

            mFolder.hideItem(item);

            if (!itemAdded) mPreviewItemManager.hidePreviewItem(index, true);
            d.folderNameSuggestionLoader.getSuggestedFolderName(mInfo.getAppContents(),
                    folderNameInfos -> postDelayed(() -> {
                        setLabelSuggestion(folderNameInfos, d.logInstanceId);
                        invalidate();
                    }, DROP_IN_ANIMATION_DURATION));

        } else {
            getFolder().addFolderContent(item);
        }
    }

    /**
     * Set the suggested folder name.
     */
    public void setLabelSuggestion(FolderNameInfos nameInfos, InstanceId instanceId) {
        if (!mInfo.getLabelState().equals(LabelState.UNLABELED)) {
            return;
        }
        if (nameInfos == null || !nameInfos.hasSuggestions()) {
            StatsLogManager.newInstance(getContext()).logger()
                    .withInstanceId(instanceId)
                    .withItemInfo(mInfo)
                    .log(LAUNCHER_FOLDER_AUTO_LABELING_SKIPPED_EMPTY_SUGGESTIONS);
            return;
        }
        if (!nameInfos.hasPrimary()) {
            StatsLogManager.newInstance(getContext()).logger()
                    .withInstanceId(instanceId)
                    .withItemInfo(mInfo)
                    .log(LAUNCHER_FOLDER_AUTO_LABELING_SKIPPED_EMPTY_PRIMARY);
            return;
        }
        CharSequence newTitle = nameInfos.getLabels()[0];
        FromState fromState = mInfo.getFromLabelState();

        mInfo.setTitle(newTitle, mActivity.getModelWriter());
        onTitleChanged(mInfo.title);
        if (mFolder != null) {
            mFolder.getFolderName().setText(mInfo.title);
        }

        // Logging for folder creation flow
        StatsLogManager.newInstance(getContext()).logger()
                .withInstanceId(instanceId)
                .withItemInfo(mInfo)
                .withFromState(fromState)
                .withToState(ToState.TO_SUGGESTION0)
                // When LAUNCHER_FOLDER_LABEL_UPDATED event.edit_text does not have delimiter,
                // event is assumed to be folder creation on the server side.
                .withEditText(newTitle.toString())
                .log(LAUNCHER_FOLDER_AUTO_LABELED);
    }


    public void onDrop(DragObject d, boolean itemReturnedOnFailedDrop) {
        ItemInfo item;
        if (d.dragInfo instanceof WorkspaceItemFactory) {
            // Came from all apps -- make a copy
            item = ((WorkspaceItemFactory) d.dragInfo).makeWorkspaceItem(getContext());
        } else if (d.dragSource instanceof BaseItemDragListener){
            // Came from a different window -- make a copy
            if (d.dragInfo instanceof AppPairInfo) {
                // dragged item is app pair
                item = new AppPairInfo((AppPairInfo) d.dragInfo);
            } else {
                // dragged item is WorkspaceItemInfo
                item = new WorkspaceItemInfo((WorkspaceItemInfo) d.dragInfo);
            }
        } else {
            item = d.dragInfo;
        }
        mFolder.notifyDrop();
        onDrop(item, d, null, 1.0f,
                itemReturnedOnFailedDrop ? item.rank : mInfo.getContents().size(),
                itemReturnedOnFailedDrop
        );
    }

    /** Keep the notification dot up to date with the sum of all the content's dots. */
    public void updateDotInfo() {
        boolean hadDot = mDotInfo.hasDot();
        mDotInfo.reset();
        for (ItemInfo si : mInfo.getContents()) {
            mDotInfo.addDotInfo(mActivity.getDotInfoForItem(si));
        }
        boolean isDotted = mDotInfo.hasDot();
        float newDotScale = isDotted ? 1f : 0f;
        // Animate when a dot is first added or when it is removed.
        if ((hadDot ^ isDotted) && isShown()) {
            animateDotScale(newDotScale);
        } else {
            cancelDotScaleAnim();
            mDotScale = newDotScale;
            invalidate();
        }
    }

    public ClippedFolderIconLayoutRule getLayoutRule() {
        if (Flags.enableExpressiveFolderExpansion() && mPreviewLayoutRule.getIconSize() == 0) {
            // Make sure the layout rule is initialized
            mPreviewItemManager.recomputePreviewDrawingParams();
        }
        return mPreviewLayoutRule;
    }

    @Override
    public void setForceHideDot(boolean forceHideDot) {
        if (mForceHideDot == forceHideDot) {
            return;
        }
        mForceHideDot = forceHideDot;

        if (forceHideDot) {
            invalidate();
        } else if (hasDot()) {
            animateDotScale(0, 1);
        }
    }

    private void cancelDotScaleAnim() {
        if (mDotScaleAnim != null) {
            mDotScaleAnim.cancel();
        }
    }

    public void animateDotScale(float... dotScales) {
        cancelDotScaleAnim();
        mDotScaleAnim = ObjectAnimator.ofFloat(this, DOT_SCALE_PROPERTY, dotScales);
        mDotScaleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mDotScaleAnim = null;
            }
        });
        mDotScaleAnim.start();
    }

    public boolean hasDot() {
        return mDotInfo != null && mDotInfo.hasDot();
    }

    private float getLocalCenterForIndex(int index, int curNumItems, int[] center) {
        mTmpParams = mPreviewItemManager.computePreviewItemDrawingParams(
                Math.min(MAX_NUM_ITEMS_IN_PREVIEW, index), curNumItems, mTmpParams);

        mTmpParams.transX += mBackground.basePreviewOffsetX;
        mTmpParams.transY += mBackground.basePreviewOffsetY;

        float intrinsicIconSize = mPreviewItemManager.getIntrinsicIconSize();
        float offsetX = mTmpParams.transX + (mTmpParams.scale * intrinsicIconSize) / 2;
        float offsetY = mTmpParams.transY + (mTmpParams.scale * intrinsicIconSize) / 2;

        center[0] = Math.round(offsetX);
        center[1] = Math.round(offsetY);
        return mTmpParams.scale;
    }

    public void setFolderBackground(PreviewBackground bg) {
        mBackground = bg;
        mBackground.setInvalidateDelegate(this);
    }

    @Override
    public void setIconVisible(boolean visible) {
        mBackgroundIsVisible = visible;
        invalidate();
    }

    public boolean getIconVisible() {
        return mBackgroundIsVisible;
    }

    /**
     * Sets the opacity of the big (2x2) preview panel. The open/close animation cross-fades this
     * against the folder's own content: while the folder is open the tile underneath it is fully
     * transparent, and it fades back in as the folder shrinks onto it. Without this the tile was
     * drawn at full opacity beneath the translucent folder panel for the whole animation, so both
     * were visible at once (double image).
     */
    public void setBigPreviewAlpha(float alpha) {
        if (mBigPreviewAlpha != alpha) {
            mBigPreviewAlpha = alpha;
            invalidate();
        }
    }

    public PreviewBackground getFolderBackground() {
        return mBackground;
    }

    public PreviewItemManager getPreviewItemManager() {
        return mPreviewItemManager;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Draw selection highlight before super.dispatchDraw() so that it appears behind the title
        // text.
        if (isSelected()) {
            drawWorkspaceItemSelectionHighlight(canvas, this);
        }
        super.dispatchDraw(canvas);

        if (!mBackgroundIsVisible) return;

        if (isBigFolder()) {
            // Big folders draw 3 large launchable icons + a mini-cluster instead of the small
            // clipped 2x2 preview. (Notification dot is intentionally omitted here for now.)
            if (mBigPreviewAlpha <= 0f) {
                return;
            }
            if (mBigPreviewAlpha < 1f) {
                // One layer for the whole panel: fading the paints and the icon drawables
                // individually would let the icons show through the panel behind them.
                int layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(),
                        Math.round(mBigPreviewAlpha * 255));
                mLargeFolderPreview.draw(canvas);
                canvas.restoreToCount(layer);
            } else {
                mLargeFolderPreview.draw(canvas);
            }
            return;
        }

        mPreviewItemManager.recomputePreviewDrawingParams();

        if (!mBackground.drawingDelegated()) {
            mBackground.drawBackground(canvas);
        }

        if (mCurrentPreviewItems.isEmpty() && !mAnimating) return;

        mPreviewItemManager.draw(canvas);

        if (!mBackground.drawingDelegated()) {
            mBackground.drawBackgroundStroke(canvas);
        }

        drawDot(canvas);
    }

    public void drawDot(Canvas canvas) {
        if (!mForceHideDot && ((mDotInfo != null && mDotInfo.hasDot()) || mDotScale > 0)) {
            Rect iconBounds = mDotParams.iconBounds;
            // FolderIcon draws the icon to be top-aligned (with padding) & horizontally-centered
            int iconSize = mActivity.getDeviceProfile().getWorkspaceProfile().getIconSizePx();
            iconBounds.left = (getWidth() - iconSize) / 2;
            iconBounds.right = iconBounds.left + iconSize;
            iconBounds.top = getPaddingTop();
            iconBounds.bottom = iconBounds.top + iconSize;

            float iconScale = (float) mBackground.previewSize / iconSize;
            Utilities.scaleRectAboutCenter(iconBounds, iconScale);

            // If we are animating to the accepting state, animate the dot out.
            mDotParams.scale = Math.max(0, mDotScale - mBackground.getAcceptScaleProgress());
            mDotRenderer.draw(canvas, mDotParams);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (isBigFolder()) {
            // Big folders fill the 2x2 cell; skip the 1x1 vertical-centering and place the label
            // just below the enlarged preview instead. The label is positioned *before* measuring so
            // the child is measured with its final top margin (it is match_parent tall, so measuring
            // it with a stale margin left it overhanging the tile and clipped away).
            positionBigFolderLabel(MeasureSpec.getSize(widthMeasureSpec),
                    MeasureSpec.getSize(heightMeasureSpec));
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        boolean shouldCenterIcon = mActivity.getDeviceProfile().getWorkspaceProfile()
                .getIconCenterVertically();
        if (shouldCenterIcon) {
            int iconSize = mActivity.getDeviceProfile().getWorkspaceProfile().getIconSizePx();
            Paint.FontMetrics fm = mFolderName.getPaint().getFontMetrics();
            int cellHeightPx = iconSize + mFolderName.getCompoundDrawablePadding()
                    + (int) Math.ceil(fm.bottom - fm.top);
            setPadding(getPaddingLeft(), (MeasureSpec.getSize(heightMeasureSpec)
                    - cellHeightPx) / 2, getPaddingRight(), getPaddingBottom());
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Places the folder label just below the enlarged preview of a big folder. Only adjusts the
     * child's top margin in place (no requestLayout), so it is safe to call from onMeasure.
     */
    private void positionBigFolderLabel(int width, int height) {
        int previewBottom = mLargeFolderPreview.updateGeometry(
                width, height, getPaddingTop(), getBigFolderLabelHeight());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mFolderName.getLayoutParams();
        lp.topMargin = previewBottom + getBigFolderLabelGap();
    }

    /** Sets the visibility of the icon's title text */
    public void setTextVisible(boolean visible) {
        if (visible) {
            mFolderName.setVisibility(VISIBLE);
        } else {
            mFolderName.setVisibility(INVISIBLE);
        }
    }

    @Override
    public int getIconHeight() {
        Rect rect = new Rect();
        getPreviewBounds(rect);
        return rect.height();
    }

    /**
     * Returns the list of items which should be visible in the preview
     */
    public List<ItemInfo> getPreviewItemsOnPage(int page) {
        return mPreviewVerifier.setFolderInfo(mInfo).previewItemsForPage(page, mInfo.getContents());
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return mPreviewItemManager.verifyDrawable(who) || super.verifyDrawable(who);
    }

    private void updatePreviewItems(boolean animate) {
        mPreviewItemManager.updatePreviewItems(animate);
        mCurrentPreviewItems.clear();
        mCurrentPreviewItems.addAll(getPreviewItemsOnPage(0));
        // Only maintain the big-preview drawables for folders that are (or are about to become)
        // big, so normal small folders don't build extra icons.
        if (mInfo != null
                && (mInfo.isBigFolder() || mInfo.qualifiesAsBigFolder() || mInfo.forceBigPreview)) {
            mLargeFolderPreview.onItemsChanged(getOrderedContents());
        }
    }

    /**
     * Whether this folder should be rendered as a big (2x2) folder. This is content-based (a
     * workspace folder with enough apps) rather than span-based on purpose: a transient span or
     * footprint change while the folder opens/closes must not flip the icon back to the small 2x2
     * preview for a frame -- that was the "small folder flash" on close. The physical 2x2 footprint
     * is managed separately (the loader's applyBigFolderFootprint + {@link
     * #updateBigFolderFootprint()}).
     */
    public boolean isBigFolder() {
        if (mInfo == null) {
            return false;
        }
        // Caddy drawer folders always use the big tile (see FolderInfo#forceBigPreview).
        if (mInfo.forceBigPreview) {
            return true;
        }
        return mInfo.container == LauncherSettings.Favorites.CONTAINER_DESKTOP
                && (mInfo.isBigFolder() || mInfo.qualifiesAsBigFolder());
    }

    /** Folder contents in rank order, used to pick the big folder's large icons vs. cluster. */
    private List<ItemInfo> getOrderedContents() {
        List<ItemInfo> ordered = new ArrayList<>(mInfo.getContents());
        ordered.sort(Comparator.comparingInt(item -> item.rank));
        return ordered;
    }

    /**
     * Height reserved below a big folder's preview for its label, including the gap above the text
     * (0 if the label is hidden).
     *
     * <p>This is derived from the text's own line height on purpose. It must not use
     * {@code mFolderName.getMeasuredHeight()}: the label view is {@code layout_height="match_parent"}
     * (see folder_icon.xml), so its measured height is the whole tile below its top margin. Feeding
     * that back in as "label height" left almost no room for the preview, so the big tile drew as a
     * sliver until repeated layout passes crept it larger, and the label itself landed underneath the
     * frosted panel instead of below it.
     */
    int getBigFolderLabelHeight() {
        if (mFolderName == null || mFolderName.getVisibility() == GONE) {
            return 0;
        }
        Paint.FontMetrics fm = mFolderName.getPaint().getFontMetrics();
        return (int) Math.ceil(fm.bottom - fm.top) + mFolderName.getPaddingTop()
                + mFolderName.getPaddingBottom() + getBigFolderLabelGap();
    }

    /** Gap between a big folder's preview and the label below it. */
    private int getBigFolderLabelGap() {
        return Math.round(4 * getResources().getDisplayMetrics().density);
    }

    /**
     * Updates the preview items which match the provided condition
     */
    public void updatePreviewItems(Predicate<ItemInfo> itemCheck) {
        mPreviewItemManager.updatePreviewItems(itemCheck);
    }

    public void onItemsChanged(boolean animate) {
        updatePreviewItems(false);
        updateDotInfo();
        setContentDescription(getAccessiblityTitle(mInfo.title));
        updatePreviewItems(animate);
        // Grow/shrink the footprint when a folder crosses the big-folder threshold (e.g. the user
        // drags a 4th app in). Posted so it runs after the change/drag settles.
        post(this::updateBigFolderFootprint);
        invalidate();
        requestLayout();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Size the footprint once the folder is bound and the grid has settled. Posted so it runs
        // after the current bind pass rather than mutating occupancy mid-bind.
        post(this::updateBigFolderFootprint);
    }

    /**
     * Sizes this workspace folder to a 2x2 footprint when it qualifies as a big folder, or back to
     * 1x1 otherwise. When growing it keeps its position if the 2x2 block is free, else relocates to
     * the nearest free 2x2 on the same screen -- so it never overlaps neighbours or straddles a
     * page. The new footprint/position are persisted so it stays big after a reload.
     *
     * <p>The layout params' span (not the model's) is treated as the source of truth for "currently
     * big", so this self-corrects even if the loaded span didn't reach the view.
     */
    private void updateBigFolderFootprint() {
        if (mInfo == null || mInfo.container != LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            // Only workspace folders are enlarged (not the hotseat, all-apps, or taskbar).
            return;
        }
        if (getParent() == null
                || !(getParent().getParent() instanceof CellLayout cellLayout)
                || !(getLayoutParams() instanceof CellLayoutLayoutParams lp)) {
            return;
        }
        final int span = FolderInfo.BIG_FOLDER_SPAN;
        boolean wantBig = mInfo.qualifiesAsBigFolder();
        boolean isBig = lp.cellHSpan >= span && lp.cellVSpan >= span;
        android.util.Log.d("BigFolder", "footprint '" + mInfo.title + "' contents="
                + mInfo.getContents().size() + " wantBig=" + wantBig + " isBig=" + isBig
                + " lp=" + lp.cellHSpan + "x" + lp.cellVSpan
                + " cell=" + lp.getCellX() + "," + lp.getCellY());
        // GROW-ONLY: never shrink a folder here. Shrinking + persisting spanX=1 on a transient/racy
        // post-bind signal is what made big folders come back small after a reboot (and it can free
        // cells that then block the loader from re-growing). The loader
        // (LoaderCursor.applyBigFolderFootprint) is the authoritative sizer at load; this path only
        // grows a folder live when its 4th app is dropped in.
        if (isBig || !wantBig) {
            // Keep the model's span consistent with the actual layout.
            mInfo.spanX = lp.cellHSpan;
            mInfo.spanY = lp.cellVSpan;
            return;
        }
        if (cellLayout.getCountX() < span || cellLayout.getCountY() < span) {
            return; // grid too small to ever hold a 2x2 folder
        }
        cellLayout.markCellsAsUnoccupiedForView(this);
        // Prefer our current spot (clamped in-grid); otherwise the nearest free 2x2.
        int cellX = Math.max(0, Math.min(lp.getCellX(), cellLayout.getCountX() - span));
        int cellY = Math.max(0, Math.min(lp.getCellY(), cellLayout.getCountY() - span));
        if (!cellLayout.isRegionVacant(cellX, cellY, span, span)) {
            int[] vacant = new int[2];
            if (cellLayout.findCellForSpan(vacant, span, span)) {
                cellX = vacant[0];
                cellY = vacant[1];
            } else {
                // No room on this screen for a 2x2; stay a normal 1x1 folder.
                cellLayout.markCellsAsOccupiedForView(this);
                android.util.Log.d("BigFolder", "no free 2x2 for '" + mInfo.title + "', staying 1x1");
                return;
            }
        }
        applyFootprint(lp, cellX, cellY, span);
        cellLayout.markCellsAsOccupiedForView(this);
        mActivity.getModelWriter().updateItemInDatabase(mInfo);
        requestLayout();
        invalidate();
    }

    private void applyFootprint(CellLayoutLayoutParams lp, int cellX, int cellY, int span) {
        lp.setCellX(cellX);
        lp.setCellY(cellY);
        lp.cellHSpan = span;
        lp.cellVSpan = span;
        mInfo.cellX = cellX;
        mInfo.cellY = cellY;
        mInfo.spanX = span;
        mInfo.spanY = span;
    }

    public void onTitleChanged(CharSequence title) {
        if (mFolderName.shouldShowLabel()) {
            mFolderName.applyLabel(title);
        }
        setContentDescription(getAccessiblityTitle(title));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // Remember whether the press landed on one of the big folder's large icons, so the
            // click (fired on ACTION_UP) can launch that app instead of opening the folder.
            mPendingLaunchTarget = isBigFolder()
                    ? mLargeFolderPreview.getLaunchTargetForPoint(event.getX(), event.getY())
                    : null;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mPendingLaunchTarget = null;
        }
        return onDelegateTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        WorkspaceItemInfo target = mPendingLaunchTarget;
        mPendingLaunchTarget = null;
        // A tap on a big folder's large icon launches that app directly. Everything else (the
        // mini-cluster quadrant, the label, or a non-touch/accessibility click) opens the folder.
        // Pass a null source view so the launch/return uses the default window transition instead of
        // a FloatingIconView, which for a FolderIcon renders the small folder preview -- that was the
        // "small folder" flashing when an app opened from / closed back to the big folder.
        if (target != null && isBigFolder() && mActivity instanceof Launcher launcher) {
            ItemClickHandler.onClickAppShortcut(null, target, launcher);
            return true;
        }
        // Caddy drawer folders open an in-drawer category page (CaddyCategoryView) instead of the
        // workspace Folder; that is wired as this icon's OnClickListener in #inflateIcon, so just
        // fall through to super.performClick() to invoke it.
        return super.performClick();
    }

    /**
     * Returns true if the touch down at the provided position be ignored
     */
    protected boolean shouldIgnoreTouchDown(MotionEvent event) {
        mTouchArea.set(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(),
                getHeight() - getPaddingBottom());
        return !mTouchArea.contains((int) event.getX(), (int) event.getY());
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mLongPressHelper.cancelLongPress();
    }

    private boolean isInHotseat() {
        return mInfo.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT;
    }

    public void clearLeaveBehindIfExists() {
        if (getParent() instanceof FolderIconParent) {
            ((FolderIconParent) getParent()).clearFolderLeaveBehind(this);
        }
    }

    public void drawLeaveBehindIfExists() {
        if (getParent() instanceof FolderIconParent) {
            ((FolderIconParent) getParent()).drawFolderLeaveBehindForIcon(this);
        }
    }

    public void onFolderClose(int currentPage) {
        // Big folders don't use the small clipped preview, so skip its slide-in-first-page
        // animation on close - it's what caused a small folder to flash before the big one.
        if (isBigFolder()) {
            return;
        }
        mPreviewItemManager.onFolderClose(currentPage);
    }

    @Override
    public MultiTranslateDelegate getTranslateDelegate() {
        return mTranslateDelegate;
    }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override
    public float getReorderBounceScale() {
        return mScaleForReorderBounce;
    }

    @Override
    public int getViewType() {
        return DRAGGABLE_ICON;
    }

    @Override
    public void getWorkspaceVisualDragBounds(Rect bounds) {
        getPreviewBounds(bounds);
    }

    /**
     * Returns a formatted accessibility title for folder
     */
    public String getAccessiblityTitle(CharSequence title) {
        if (title == null) {
            // Avoids "Talkback -> Folder: null" announcement.
            title = getContext().getString(R.string.unnamed_folder);
        }
        int size = mInfo.getContents().size();
        String folder_type = getContext().getString(
                HomeScreenFilesUtils.isFeatureEnabled() ? R.string.app_folder_type_name
                        : R.string.folder_type_name);
        if (size < MAX_NUM_ITEMS_IN_PREVIEW) {
            return getContext().getString(hasDot()
                            ? R.string.apps_folder_name_format_exact_with_dot
                            : R.string.apps_folder_name_format_exact,
                    folder_type, title, size);
        } else {
            return getContext().getString(hasDot()
                            ? R.string.apps_folder_name_format_overflow_with_dot
                            : R.string.apps_folder_name_format_overflow,
                    folder_type, title, MAX_NUM_ITEMS_IN_PREVIEW);
        }
    }

    @Override
    public void onHoverChanged(boolean hovered) {
        super.onHoverChanged(hovered);
        mBackground.setHovered(hovered);
    }

    @NonNull
    @Override
    public PoppableType getPoppableType() {
        return PoppableType.FOLDER;
    }

    @Override
    public MultiPropertyFactory<AnimatedFloat>.MultiProperty getFloatingViewTextAlpha() {
        return mFolderName.getFloatingViewTextAlpha();
    }

    @Override
    public boolean onDelegateTouchEvent(@NonNull MotionEvent event) {
        return mCustomEventsTouchHandler.onDelegateTouchEvent(event);
    }

    @Nullable
    @Override
    public CustomActionsListener getCustomActionsListener() {
        return mCustomEventsTouchHandler.getCustomActionsListener();
    }

    @Override
    public void setCustomActionsListener(@Nullable CustomActionsListener listener) {
        mCustomEventsTouchHandler.setCustomActionsListener(listener);
    }

    /**
     * Interface that provides callbacks to a parent ViewGroup that hosts this FolderIcon.
     */
    public interface FolderIconParent {
        /**
         * Tells the FolderIconParent to draw a "leave-behind" when the Folder is open and leaving a
         * gap where the FolderIcon would be when the Folder is closed.
         */
        void drawFolderLeaveBehindForIcon(FolderIcon child);
        /**
         * Tells the FolderIconParent to stop drawing the "leave-behind" as the Folder is closed.
         */
        void clearFolderLeaveBehind(FolderIcon child);
    }
}
