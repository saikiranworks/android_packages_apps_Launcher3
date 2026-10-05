/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.launcher3.Launcher
import com.android.launcher3.dagger.LauncherComponentProvider
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.views.ActivityContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A stack of same-sized widgets in one cell block, flipped through with a vertical swipe.
 *
 * The widget on the workspace is the stack's first page and keeps its place in the launcher's
 * database; the widgets stacked onto it are kept bound to the launcher's host and listed here, by
 * the first page's app widget id.
 */
class WidgetStack(private val host: LauncherAppWidgetHostView) {

    private val context = host.context
    private val density = context.resources.displayMetrics.density
    private val pages = ArrayList<LauncherAppWidgetHostView>()
    private var hostId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** The page shown, fractional mid-swipe: 0 is the host's own widget. */
    private var position = 0f
    private var indicator = 0f
    private val positionHolder = FloatValueHolder()
    private val spring =
        SpringAnimation(positionHolder).apply {
            spring =
                SpringForce().setDampingRatio(0.78f).setStiffness(SpringForce.STIFFNESS_LOW * 1.6f)
            addUpdateListener { _, value, _ -> setPosition(value) }
            addEndListener { _, _, _, _ -> fadeIndicatorSoon() }
        }
    private val fadeIndicator = Runnable {
        android.animation.ValueAnimator.ofFloat(indicator, 0f).apply {
            duration = 260
            addUpdateListener {
                indicator = it.animatedValue as Float
                host.invalidate()
            }
            start()
        }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startPosition = 0f
    private var swiping = false
    private var velocity: VelocityTracker? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    val pageCount
        get() = pages.size + 1

    val isStacked
        get() = pages.isNotEmpty()

    /** Builds the stacked pages listed for the host's widget, once it is bound. */
    fun bind() {
        val info = host.tag as? LauncherAppWidgetInfo ?: return
        if (info.appWidgetId == hostId && pages.size == idsOf(context, info.appWidgetId).size) return
        clearPages()
        hostId = info.appWidgetId
        val ids = idsOf(context, hostId)
        if (ids.isEmpty()) return
        val activity: ActivityContext = ActivityContext.lookupContextNoThrow(context) ?: return
        val holder = activity.appWidgetHolder ?: return
        val manager = WidgetManagerHelper(context)
        for (id in ids) {
            val provider = manager.getLauncherAppWidgetInfo(id, null) ?: continue
            val page = holder.createView(id, provider) as? LauncherAppWidgetHostView ?: continue
            page.isStackPage = true
            pages += page
            host.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val saved = prefs(context).getInt(PAGE_PREFIX + hostId, 0).coerceIn(0, pages.size)
        positionHolder.value = saved.toFloat()
        setPosition(saved.toFloat())
        updateSizes()
    }

    private fun clearPages() {
        pages.forEach { host.removeView(it) }
        pages.clear()
        position = 0f
    }

    /** Tells the stacked widgets the size they're shown at, which is the host's. */
    fun updateSizes() {
        val info = host.tag as? LauncherAppWidgetInfo ?: return
        if (pages.isEmpty()) return
        val sizes = LauncherComponentProvider.get(context).widgetSizeHandler
        pages.forEach { sizes.updateSizeRangesAsync(it.appWidgetId, info.spanX, info.spanY) }
    }

    /** Lays the pages out around [value], the page shown, sliding up into place like a deck. */
    private fun setPosition(value: Float) {
        position = value
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            val index = pages.indexOf(child) + 1
            apply(child, index - value)
        }
        host.invalidate()
    }

    /** Places a newly added view of the host's own widget where page 0 belongs. */
    fun onViewAdded(child: View) {
        if (isStacked && child !in pages) apply(child, -position)
    }

    private fun apply(view: View, offset: Float) {
        val h = host.height.toFloat()
        val away = abs(offset).coerceAtMost(1f)
        view.translationY = offset * h
        view.scaleX = 1f - 0.1f * away
        view.scaleY = 1f - 0.1f * away
        view.alpha = 1f - 0.6f * away
        view.visibility = if (abs(offset) >= 1f) View.INVISIBLE else View.VISIBLE
    }

    fun onLayout() {
        if (isStacked) setPosition(position)
    }

    /** True once a vertical swipe on the stack is under way, which the stack then handles. */
    fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isStacked) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dy = ev.y - downY
                if (!swiping && abs(dy) > touchSlop && abs(dy) > abs(ev.x - downX) * 1.3f) {
                    swiping = true
                    spring.cancel()
                    startPosition = position
                    downY = ev.y
                    showIndicator()
                }
            }
        }
        return swiping
    }

    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swiping) return false
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                var p = startPosition - (ev.y - downY) / host.height.coerceAtLeast(1)
                // Past either end the deck only gives a little.
                val max = pages.size.toFloat()
                if (p < 0f) p = -rubber(-p)
                if (p > max) p = max + rubber(p - max)
                setPosition(p)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val v = velocity?.run {
                    computeCurrentVelocity(1000)
                    yVelocity
                } ?: 0f
                velocity?.recycle()
                velocity = null
                swiping = false
                // A short flick or a quarter of the way is enough to move on, as on iOS.
                val start = startPosition.roundToInt()
                val moved = position - start
                val flick = 300 * density
                var target = start
                if (v < -flick || moved > 0.25f) target = start + 1
                else if (v > flick || moved < -0.25f) target = start - 1
                settle(target.coerceIn(0, pages.size), -v / host.height.coerceAtLeast(1))
            }
        }
        return true
    }

    private fun rubber(over: Float) = 0.25f * over / (1f + over)

    /** Springs to [page], starting at [velocity] pages per second. */
    fun settle(page: Int, velocity: Float = 0f) {
        positionHolder.value = position
        spring.setStartVelocity(velocity)
        spring.animateToFinalPosition(page.toFloat())
        prefs(context).edit().putInt(PAGE_PREFIX + hostId, page).apply()
        showIndicator()
    }

    private fun showIndicator() {
        host.removeCallbacks(fadeIndicator)
        indicator = 1f
        host.invalidate()
    }

    private fun fadeIndicatorSoon() {
        host.removeCallbacks(fadeIndicator)
        host.postDelayed(fadeIndicator, 900)
    }

    private val clip = Path()

    /** Keeps the pages sliding past inside the stack's rounded bounds. */
    fun clip(canvas: Canvas) {
        if (!isStacked) return
        val r = RoundedCornerEnforcement.computeEnforcedRadius(context)
        clip.reset()
        clip.addRoundRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), r, r, Path.Direction.CW)
        canvas.clipPath(clip)
    }

    /** The column of dots on the stack's right edge, shown while it's being flipped. */
    fun drawIndicator(canvas: Canvas) {
        if (!isStacked || indicator <= 0f) return
        val r = 2.6f * density
        val gap = 8f * density
        val x = host.width - 7f * density
        val total = (pageCount - 1) * gap
        var y = host.height / 2f - total / 2f
        for (i in 0 until pageCount) {
            val near = (1f - abs(i - position)).coerceIn(0f, 1f)
            dotPaint.color = 0xFFFFFF or (((0.4f + 0.55f * near) * indicator * 255).toInt() shl 24)
            canvas.drawCircle(x, y, r * (1f + 0.35f * near), dotPaint)
            y += gap
        }
    }

    /**
     * Takes the page on top out of the stack and unbinds its widget. When that is the first page,
     * the next one takes its place on the workspace.
     */
    fun removeShownPage(): Boolean {
        val info = host.tag as? LauncherAppWidgetInfo ?: return false
        val activity: ActivityContext = ActivityContext.lookupContextNoThrow(context) ?: return false
        val holder = activity.appWidgetHolder ?: return false
        val shown = position.roundToInt().coerceIn(0, pages.size)
        if (shown > 0) {
            val id = pages[shown - 1].appWidgetId
            removePage(context, info.appWidgetId, id)
            holder.deleteAppWidgetId(id)
            bind()
            return true
        }
        val ids = idsOf(context, info.appWidgetId)
        val next = ids.firstOrNull() ?: return false
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(next) ?: return false
        val launcher = Launcher.getLauncher(context) ?: return false
        remove(context, info.appWidgetId)
        if (ids.size > 1) stack(context, next, ids.drop(1), shown = 0)
        holder.deleteAppWidgetId(info.appWidgetId)
        info.appWidgetId = next
        info.providerName = provider.provider
        launcher.modelWriter.updateItemInDatabase(info)
        launcher.model.forceReload("widget stack's first page removed")
        return true
    }

    companion object {
        private const val PREFS = "widget_stacks"

        @Volatile private var appContext: Context? = null

        /** Whether the widget [hostId] has others stacked onto it. */
        @JvmStatic
        fun isStack(hostId: Int): Boolean =
            appContext?.let { idsOf(it, hostId).isNotEmpty() } ?: false
        private const val PAGE_PREFIX = "page_"

        private fun prefs(context: Context): SharedPreferences {
            appContext = context.applicationContext
            return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        /** The app widget ids stacked onto the widget [hostId], in page order. */
        @JvmStatic
        fun idsOf(context: Context, hostId: Int): List<Int> =
            prefs(context).getString(hostId.toString(), null)
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull() }
                .orEmpty()

        /** Every stacked widget id, which the launcher must keep bound though it isn't in its db. */
        @JvmStatic
        fun allStackedIds(context: Context): Set<Int> =
            prefs(context).all.filterKeys { it.toIntOrNull() != null }.keys.flatMapTo(HashSet()) {
                idsOf(context, it.toInt())
            }

        /** Stacks whose first page is gone, with their pages' ids for the caller to unbind. */
        @JvmStatic
        fun pruneOrphans(context: Context, liveHostIds: Set<Int>): List<Int> {
            val orphans = ArrayList<Int>()
            val editor = prefs(context).edit()
            for (key in prefs(context).all.keys) {
                val id = key.toIntOrNull() ?: continue
                if (id !in liveHostIds) {
                    orphans += idsOf(context, id)
                    editor.remove(key).remove(PAGE_PREFIX + key)
                }
            }
            editor.apply()
            return orphans
        }

        /**
         * Stacks [added] (a widget and whatever was stacked on it) onto [hostId]'s stack, and
         * shows it on top: the newly stacked widget becomes the page shown.
         */
        @JvmStatic
        @JvmOverloads
        fun stack(
            context: Context,
            hostId: Int,
            added: List<Int>,
            shown: Int = idsOf(context, hostId).size + 1,
        ): Int {
            val ids = idsOf(context, hostId) + added
            prefs(context)
                .edit()
                .putString(hostId.toString(), ids.joinToString(","))
                .putInt(PAGE_PREFIX + hostId, shown)
                .commit()
            return ids.size
        }

        /** Forgets [hostId]'s stack, returning its pages' ids for the caller to unbind. */
        @JvmStatic
        fun remove(context: Context, hostId: Int): List<Int> {
            val ids = idsOf(context, hostId)
            prefs(context)
                .edit()
                .remove(hostId.toString())
                .remove(PAGE_PREFIX + hostId)
                .apply()
            return ids
        }

        /** Takes [pageId] out of [hostId]'s stack. */
        @JvmStatic
        fun removePage(context: Context, hostId: Int, pageId: Int) {
            val ids = idsOf(context, hostId) - pageId
            prefs(context)
                .edit()
                .apply {
                    if (ids.isEmpty()) remove(hostId.toString())
                    else putString(hostId.toString(), ids.joinToString(","))
                }
                .putInt(PAGE_PREFIX + hostId, 0)
                .apply()
        }

        /** Whether [a] can be stacked onto [b]: two placed widgets of the same size. */
        @JvmStatic
        fun canStack(a: Any?, b: Any?): Boolean =
            a is LauncherAppWidgetInfo &&
                b is LauncherAppWidgetInfo &&
                a !== b &&
                a.appWidgetId > 0 &&
                b.appWidgetId > 0 &&
                a.spanX == b.spanX &&
                a.spanY == b.spanY &&
                !a.isCustomWidget &&
                !b.isCustomWidget
    }
}

