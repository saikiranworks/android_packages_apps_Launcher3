/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.quickstep.RecentsModel
import com.android.systemui.shared.recents.model.Task
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * Samsung style "Task changer" layouts for the overview: a grid, a vertical list, a slim list and
 * a one handed mini row, drawn over [RecentsView] once the overview has settled.
 *
 * The carousel stays underneath and keeps doing the work: the swipe up lands in it, launches
 * animate from it, and dismissing goes through it, so gestures behave as they always have.
 */
class TaskChangerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private class Entry(val taskView: TaskView, val task: Task) {
        var icon: Drawable? = task.icon
        var title: String? = task.title
        var thumbnail: Bitmap? = task.thumbnail?.thumbnail
    }

    private val density = resources.displayMetrics.density
    private val night =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    private val surface =
        context.getColor(
            if (night) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50
        )
    private val onSurface =
        context.getColor(
            if (night) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900
        )
    private val screenAspect =
        resources.displayMetrics.heightPixels.toFloat() / resources.displayMetrics.widthPixels

    private val header: LinearLayout
    private val count: TextView
    private val search: ImageView
    private val list: RecyclerView
    private val closeAll: TextView
    private val adapter = Adapter()
    private var entries = mutableListOf<Entry>()
    private var recentsView: RecentsView<*, *>? = null
    private var layout = LAYOUT_CAROUSEL
    private var showLabel = true
    private var circular = false
    private var shown = false
    private var shownLayout: String? = null

    init {
        visibility = GONE
        clipChildren = false

        header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        count =
            TextView(context).apply {
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = pill(surface, 0.7f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
        search =
            ImageView(context).apply {
                setImageResource(R.drawable.ic_allapps_search)
                imageTintList = android.content.res.ColorStateList.valueOf(onSurface)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = pill(surface, 0.7f)
                contentDescription = context.getString(R.string.all_apps_search_bar_hint)
                setOnClickListener { openSearch() }
            }
        header.addView(count)
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(search, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                setMargins(dp(20), dp(8), dp(20), 0)
            },
        )

        list =
            RecyclerView(context).apply {
                clipToPadding = false
                clipChildren = false
                overScrollMode = OVER_SCROLL_NEVER
                adapter = this@TaskChangerView.adapter
                addOnScrollListener(
                    object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = curve()
                    }
                )
            }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ItemTouchHelper(Dismisser()).attachToRecyclerView(list)
        // The list fills the screen, so the header goes over it to get its taps
        header.bringToFront()

        closeAll =
            TextView(context).apply {
                text = context.getString(R.string.recents_clear_all)
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                background = pill(surface, 0.8f)
                setPadding(dp(28), dp(12), dp(28), dp(12))
                setOnClickListener { recentsView?.dismissAllTasks() }
            }
        addView(
            closeAll,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(24) },
        )
    }

    /** Whether the chosen layout replaces the carousel, rather than only adding the header. */
    private fun replacesCarousel() =
        layout == LAYOUT_GRID ||
            layout == LAYOUT_VERTICAL ||
            layout == LAYOUT_SLIM ||
            LauncherPrefs.RECENTS_MINI.get(context)

    /**
     * Lays the chosen layout over [recents]. Called as soon as the overview starts to open, so the
     * carousel never shows first, and again once it has [settled].
     */
    fun show(recents: RecentsView<*, *>, settled: Boolean) {
        recentsView = recents
        layout = LauncherPrefs.RECENTS_LAYOUT.get(context)
        if ((layout == LAYOUT_CAROUSEL || layout == LAYOUT_TILT) &&
            LauncherPrefs.RECENTS_MINI.get(context)
        ) {
            layout = LAYOUT_MINI
        }
        showLabel = LauncherPrefs.RECENTS_APP_LABEL.get(context)
        circular = LauncherPrefs.RECENTS_CIRCULAR.get(context)
        val searchButton = LauncherPrefs.RECENTS_SEARCH.get(context)
        val overlay = replacesCarousel()
        if (!overlay && !searchButton) {
            hide()
            return
        }
        // Hidden from the very start, before the tasks even load, so it never shows first
        recents.setContentForcedHidden(overlay)

        val next =
            recents.taskViews
                .mapNotNull { tv -> tv.firstTaskContainer?.task?.let { Entry(tv, it) } }
                .toMutableList()
        if (next.isEmpty()) {
            // The tasks may not be loaded yet when the overview only starts to open
            if (settled) hide()
            return
        }
        // Already showing these tasks in this layout: only finish settling, without a redraw
        val same =
            shown &&
                layout == shownLayout &&
                next.map { it.task.key.id } == entries.map { it.task.key.id }
        if (!same) entries = next
        shownLayout = layout
        count.text =
            resources.getQuantityString(R.plurals.task_changer_active_apps, entries.size, entries.size)
        search.visibility = if (searchButton) VISIBLE else GONE
        header.visibility = VISIBLE

        if (overlay) {
            if (!same) {
                configureList()
                adapter.notifyDataSetChanged()
                loadMissing()
            }
            list.visibility = VISIBLE
            if (settled) {
                // The app just left is still its live window under the carousel, which no view
                // alpha reaches; settle it into a screenshot like the rest
                recents.switchToScreenshot {
                    recents.finishRecentsAnimation(true /* toHome */, false /* shouldPip */, null)
                }
            }
            closeAll.visibility = VISIBLE
        } else {
            list.visibility = GONE
            closeAll.visibility = GONE
        }

        if (!shown) {
            shown = true
            alpha = 0f
            visibility = VISIBLE
            animate().alpha(1f).setDuration(FADE_MS).setListener(null).start()
        }
    }

    /**
     * Fades the overlay out as the overview closes. The carousel stays hidden until the
     * transition ends, or it would pan out in its place.
     */
    fun leave() {
        if (!shown) return
        animate().alpha(0f).setDuration(FADE_MS).setListener(null).start()
    }

    /** Takes the overlay down and gives the carousel back. */
    fun hide() {
        recentsView?.setContentForcedHidden(false)
        if (!shown) return
        shown = false
        animate().cancel()
        visibility = GONE
        alpha = 1f
        entries.clear()
        shownLayout = null
        adapter.notifyDataSetChanged()
    }

    private fun configureList() {
        // Drops the items built for the previous layout
        list.adapter = null
        list.recycledViewPool.clear()
        list.adapter = adapter
        // The list ends above the close all pill, under the header
        val bottom = dp(24 + 48 + 16)
        val top = dp(72)
        list.clipToPadding = true
        when (layout) {
            LAYOUT_GRID -> {
                list.layoutManager = GridLayoutManager(context, 3)
                list.setPadding(dp(14), top, dp(14), bottom)
            }
            LAYOUT_MINI -> {
                list.layoutManager =
                    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                // One handed: the row sits low, just above the actions
                // Measured off the parent: this view is still gone, and so unmeasured, the first time
                val total = (parent as? View)?.height?.takeIf { it > 0 }
                    ?: resources.displayMetrics.heightPixels
                val rowHeight = (total * 0.34f).toInt()
                list.setPadding(dp(16), (total - bottom - rowHeight).coerceAtLeast(top), dp(16), bottom)
            }
            else -> {
                // Lists stack from the bottom, nearest the thumb, newest last
                list.layoutManager =
                    LinearLayoutManager(context, LinearLayoutManager.VERTICAL, true)
                list.setPadding(dp(16), top, dp(16), bottom)
            }
        }
        list.post { curve() }
    }

    /** Loads thumbnails and icons the carousel hasn't, since it only loads what it shows. */
    private fun loadMissing() {
        val model = RecentsModel.INSTANCE.get(context)
        entries.forEachIndexed { index, e ->
            if (e.thumbnail == null) {
                model.thumbnailCache.getThumbnailInBackground(e.task) { data ->
                    e.thumbnail = data.thumbnail
                    notifyEntry(e)
                }
            }
            if (e.icon == null || e.title == null) {
                model.iconCache.getIconInBackground(e.task, MAIN_EXECUTOR) { icon, _, title ->
                    e.icon = icon
                    e.title = title
                    notifyEntry(e)
                }
            }
        }
    }

    private fun notifyEntry(e: Entry) {
        val index = entries.indexOf(e)
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun launch(e: Entry) {
        val recents = recentsView ?: return
        val index = recents.indexOfChild(e.taskView)
        if (index >= 0) recents.setCurrentPage(index)
        animate().alpha(0f).setDuration(FADE_MS).start()
        e.taskView.launchWithAnimation()
    }

    private fun dismiss(position: Int) {
        val e = entries.getOrNull(position) ?: return
        entries.removeAt(position)
        adapter.notifyItemRemoved(position)
        recentsView?.dismissTaskView(e.taskView, true)
        if (entries.isEmpty()) {
            hide()
        } else {
            count.text =
                resources.getQuantityString(
                    R.plurals.task_changer_active_apps, entries.size, entries.size
                )
        }
    }

    private fun openSearch() {
        val launcher = Launcher.getLauncher(context)
        launcher.stateManager.goToState(
            LauncherState.ALL_APPS,
            true,
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    launcher.appsView.searchUiManager.editText?.showKeyboard()
                }
            },
        )
    }

    /** Bends the lists round a drum when the circular option is on. */
    private fun curve() {
        val bend = circular && (layout == LAYOUT_VERTICAL || layout == LAYOUT_SLIM)
        val centre = (list.paddingTop + list.height - list.paddingBottom) / 2f
        val half = ((list.height - list.paddingTop - list.paddingBottom) / 2f).coerceAtLeast(1f)
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (!bend) {
                child.translationX = 0f
                child.pivotX = child.width / 2f
                child.rotationX = 0f
                child.scaleX = 1f
                child.scaleY = 1f
                child.alpha = 1f
                continue
            }
            val d = ((child.top + child.height / 2f - centre) / half).coerceIn(-1f, 1f)
            // Shrinks from the right edge as it bends, so the curve never runs off screen
            child.pivotX = child.width.toFloat()
            child.translationX = (1f - cos(d * PI / 2).toFloat()) * list.width * 0.08f
            child.rotationX = -d * 28f
            child.cameraDistance = 8000 * density
            val scale = 1f - 0.14f * abs(d)
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = 1f - 0.45f * abs(d)
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun pill(color: Int, alpha: Float) =
        GradientDrawable().apply {
            cornerRadius = 999f
            setColor(color)
            this.alpha = (alpha * 255).toInt()
        }

    private fun rounded(view: View, radiusDp: Int) {
        val radius = radiusDp * density
        view.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        view.clipToOutline = true
    }

    private inner class Holder(val root: ViewGroup) : RecyclerView.ViewHolder(root) {
        val icon: ImageView = root.findViewWithTag("icon")
        val title: TextView? = root.findViewWithTag("title")
        val thumb: ImageView? = root.findViewWithTag("thumb")
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = entries.size

        // Each layout builds its own items, so none are recycled into another layout
        override fun getItemViewType(position: Int) = LAYOUTS.indexOf(layout)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                when (layout) {
                    LAYOUT_GRID -> gridItem(parent)
                    LAYOUT_VERTICAL -> verticalItem(parent)
                    LAYOUT_MINI -> miniItem(parent)
                    else -> slimItem()
                }
            )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = entries[position]
            holder.icon.setImageDrawable(e.icon)
            holder.title?.text = e.title
            holder.title?.visibility =
                if (showLabel || layout == LAYOUT_SLIM || layout == LAYOUT_VERTICAL) VISIBLE
                else GONE
            holder.thumb?.setImageBitmap(e.thumbnail)
            holder.root.setOnClickListener { launch(e) }
            holder.root.post { curve() }
        }

        private fun icon(sizeDp: Int) =
            ImageView(context).apply {
                tag = "icon"
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            }

        private fun title(sp: Float) =
            TextView(context).apply {
                tag = "title"
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

        private fun thumb(widthPx: Int) =
            ImageView(context).apply {
                tag = "thumb"
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
                layoutParams = LinearLayout.LayoutParams(widthPx, (widthPx * screenAspect).toInt())
                rounded(this, 20)
            }

        private fun gridItem(parent: ViewGroup): ViewGroup {
            val width = (parent.width - parent.paddingLeft - parent.paddingRight) / 3 - dp(12)
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams =
                    RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                    )
                val row =
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(icon(28))
                        addView(
                            title(12f).apply { setPadding(dp(6), 0, 0, 0) },
                            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                addView(row, LinearLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT))
                addView(
                    thumb(width).apply {
                        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
                    }
                )
            }
        }

        private fun verticalItem(parent: ViewGroup): ViewGroup {
            val width = (parent.width * 0.46f).toInt()
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                layoutParams =
                    RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                    )
                val label =
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(icon(32))
                        addView(title(15f).apply { setPadding(dp(10), 0, dp(8), 0) })
                    }
                addView(label, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(thumb(width))
            }
        }

        private fun slimItem(): ViewGroup =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pill(surface, 0.92f)
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams =
                    RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, dp(64)).apply {
                        setMargins(0, dp(5), 0, dp(5))
                    }
                addView(icon(34))
                addView(title(16f).apply { setPadding(dp(14), 0, 0, 0) })
            }

        private fun miniItem(parent: ViewGroup): ViewGroup {
            val rowHeight = parent.height - parent.paddingTop - parent.paddingBottom
            val width = min((rowHeight / screenAspect).toInt(), parent.width / 3)
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams =
                    RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                        RecyclerView.LayoutParams.MATCH_PARENT,
                    )
                addView(icon(26).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6) })
                addView(thumb(width - dp(12)))
            }
        }
    }

    /** Swipes a task away: sideways in the lists, upwards in the grid and the mini row. */
    private inner class Dismisser : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            val swipe =
                when (layout) {
                    LAYOUT_GRID, LAYOUT_MINI -> ItemTouchHelper.UP
                    else -> ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                }
            return makeMovementFlags(0, swipe)
        }

        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) =
            dismiss(vh.bindingAdapterPosition)
    }

    companion object {
        const val LAYOUT_CAROUSEL = "carousel"
        const val LAYOUT_TILT = "tilt"
        const val LAYOUT_GRID = "grid"
        const val LAYOUT_VERTICAL = "vertical"
        const val LAYOUT_SLIM = "slim"
        private const val LAYOUT_MINI = "mini"
        private val LAYOUTS = listOf(LAYOUT_GRID, LAYOUT_VERTICAL, LAYOUT_SLIM, LAYOUT_MINI)
        private const val FADE_MS = 180L

        /** How far, in degrees, a card one page from the centre tilts in the tilt stack. */
        const val TILT_DEGREES = 28f

        @JvmStatic
        fun isTiltStack(context: Context) =
            LauncherPrefs.RECENTS_LAYOUT.get(context) == LAYOUT_TILT &&
                !LauncherPrefs.RECENTS_MINI.get(context)
    }
}
