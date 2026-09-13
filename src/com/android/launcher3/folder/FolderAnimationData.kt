/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.folder

import android.graphics.Rect
import android.view.View
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.folder.FolderAnimationSpringBuilderManager.Companion.getBubbleTextView
import com.android.launcher3.folder.FolderAnimationSpringBuilderManager.Companion.getPreviewIconsOnPage
import com.android.launcher3.views.BaseDragLayer

/** Position and Scale values for animating opened/closed [Folder] */
data class FolderAnimationData(
    /** is folder opening or closing */
    val isOpening: Boolean,
    /** scale to set folder to */
    val startScale: Float,
    /** ratio of folder scale to drag layer scale */
    val folderScale: Float,
    /** x distance to translate folder */
    val xDistance: Float,
    /** y distance to translate folder */
    val yDistance: Float,
    /** change in content area height from scaling */
    val contentHeightDifference: Float,
    /** change in preview background radius from scaling */
    val folderRadiusDifference: Int,
    /** initial scale of folder icon before animation */
    val initialFolderScale: Float,
    /** initial size of preview background before animation */
    val initialFolderSize: Float,
    /** initial x offset of folder content */
    val previewOffsetX: Int,
    /** scaled offset X for folder preview */
    val scaledPreviewOffsetX: Int,
    /** initial y padding of folder content */
    val contentOffsetY: Int,
    /** default duration */
    val defaultDuration: Int,
) {

    companion object Factory {
        fun Folder.getAnimationData(isOpening: Boolean): FolderAnimationData {
            /** Calculates all values required for Folder Animators. */
            // Position and Scale values
            val layoutParams = layoutParams as BaseDragLayer.LayoutParams
            val previewBackground = folderIcon.mBackground

            // Get items in Preview and their scaling
            val itemsInPreview: List<View> = getPreviewIconsOnPage(this, 0)
            val previewScale: Float = folderIcon.layoutRule.scaleForItem(itemsInPreview.size, 0)
            val previewSize: Float = folderIcon.layoutRule.iconSize * previewScale

            // Get scale and position of FolderIcon relative to DragLayer
            val folderIconWorkspacePosition = Rect()
            val scaleRelativeToDragLayer: Float =
                mActivityContext.dragLayer.getDescendantRectRelativeToSelf(
                    folderIcon,
                    folderIconWorkspacePosition,
                )
            val scaledFolderRadius: Int = previewBackground.scaledRadius
            val baseIconSize: Float = getBubbleTextView(itemsInPreview[0]).iconSize.toFloat()

            // Big ("caddy") folders zoom as one panel from their large 2x2 preview tile, not from
            // the small circular preview. Anchoring the container scale/position to the big preview
            // bounds (and zeroing the per-icon fly in IconAnimationData) removes the fan-out.
            val bigFolder = folderIcon.isBigFolder
            val bigPreviewRect = Rect()
            if (bigFolder) folderIcon.getPreviewBounds(bigPreviewRect)

            val initialFolderSize =
                if (bigFolder) bigPreviewRect.width() * scaleRelativeToDragLayer
                else (scaledFolderRadius * 2) * scaleRelativeToDragLayer
            val initialFolderScale =
                if (bigFolder)
                    (if (layoutParams.width > 0)
                        bigPreviewRect.width() / layoutParams.width.toFloat() *
                            scaleRelativeToDragLayer
                    else 1f)
                else previewSize / baseIconSize * scaleRelativeToDragLayer

            // Get offsets for Previews and Content
            val initialPreviewItemOffsetX =
                if (Utilities.isRtl(context.resources)) {
                    (layoutParams.width * initialFolderScale - initialFolderSize).toInt()
                } else 0
            val contentOffsetX = (content.paddingLeft * initialFolderScale).toInt()
            val contentOffsetY = (content.paddingTop * initialFolderScale).toInt()

            // Get initial position of folder
            // Big folders anchor to their large preview tile; normal folders to the small preview.
            val previewOffX =
                if (bigFolder) bigPreviewRect.left.toFloat() else previewBackground.offsetX.toFloat()
            val previewOffY =
                if (bigFolder) bigPreviewRect.top.toFloat() else previewBackground.offsetY.toFloat()
            val initialX =
                ((folderIconWorkspacePosition.left +
                    paddingLeft +
                    Math.round(previewOffX * scaleRelativeToDragLayer)) -
                    contentOffsetX -
                    initialPreviewItemOffsetX)
            val initialY =
                ((folderIconWorkspacePosition.top +
                    paddingTop +
                    Math.round(previewOffY * scaleRelativeToDragLayer)) -
                    contentOffsetY)

            // Get scaled height of content and radius of background
            val scaledContentHeight = contentAreaHeight.toFloat() * initialFolderScale
            val contentHeightDifference = contentAreaHeight.toFloat() - scaledContentHeight
            val folderRadiusDifference = previewBackground.scaledRadius - previewBackground.radius
            /**
             * Background can have a scaled radius in drag and drop mode, so we need to add the
             * difference to keep the preview items centered.
             */
            return FolderAnimationData(
                isOpening = isOpening,
                startScale = if (isOpening) initialFolderScale else 1f,
                folderScale = initialFolderScale / scaleRelativeToDragLayer,
                xDistance = (initialX - layoutParams.x).toFloat(),
                yDistance = (initialY - layoutParams.y).toFloat(),
                contentHeightDifference = contentHeightDifference,
                folderRadiusDifference = folderRadiusDifference,
                initialFolderScale = initialFolderScale,
                initialFolderSize = initialFolderSize,
                previewOffsetX = initialPreviewItemOffsetX + contentOffsetX,
                scaledPreviewOffsetX =
                    (initialPreviewItemOffsetX / scaleRelativeToDragLayer).toInt() +
                        folderRadiusDifference,
                contentOffsetY = contentOffsetY,
                defaultDuration =
                    content.resources.getInteger(R.integer.config_materialFolderExpandDuration),
            )
        }
    }
}
