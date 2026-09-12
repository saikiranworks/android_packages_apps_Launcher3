/*
 * Copyright (C) 2026 PenguinOS
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

package com.android.launcher3.applibrary;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.animation.Interpolator;

import com.android.launcher3.Utilities;
import com.android.launcher3.util.EdgeEffectCompat;
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks;

/**
 * Drives the App Library from an overscroll past the last home screen, the way
 * {@link com.android.launcher3.util.OverlayEdgeEffect} drives the feed page off the first one.
 *
 * PagedView hands the pull in as a fraction of a page, so the accumulated distance doubles as the
 * open progress and no extra scaling is needed.
 */
public class AppLibraryEdgeEffect extends EdgeEffectCompat {

    private static final int SETTLE_DURATION_MS = 300;
    /** Past this much of a page the release settles open instead of falling back. */
    private static final float OPEN_THRESHOLD = 0.35f;
    /** A fling at least this fast (px/s) opens the library whatever the distance was. */
    private static final int FLING_VELOCITY = 1000;

    private final LauncherOverlayCallbacks mCallbacks;
    private final Interpolator mSettleInterpolator;

    private float mDistance;
    private boolean mIsScrolling;
    private ValueAnimator mSettleAnimator;

    public AppLibraryEdgeEffect(Context context, LauncherOverlayCallbacks callbacks,
            Interpolator settleInterpolator) {
        super(context);
        mCallbacks = callbacks;
        mSettleInterpolator = settleInterpolator;
    }

    @Override
    public float getDistance() {
        return mDistance;
    }

    @Override
    public float onPullDistance(float deltaDistance, float displacement) {
        return onPullDistance(deltaDistance, displacement, null);
    }

    @Override
    public float onPullDistance(float deltaDistance, float displacement, MotionEvent ev) {
        cancelSettle();
        mIsScrolling = true;
        float wanted = mDistance + deltaDistance;
        mDistance = Utilities.boundToRange(wanted, 0f, 1f);
        mCallbacks.onOverlayScrollChanged(mDistance);
        // Consume only what actually moved the library, so the rest still overscrolls the page.
        return mDistance == wanted ? deltaDistance : 0f;
    }

    @Override
    public void onAbsorb(int velocity) {
        // A fling into the edge opens it outright rather than snapping back.
        if (mIsScrolling && velocity > FLING_VELOCITY) {
            settleTo(1f);
        }
    }

    @Override
    public boolean isFinished() {
        return mDistance <= 0;
    }

    @Override
    public void onRelease() {
        onRelease(null);
    }

    @Override
    public void onRelease(MotionEvent ev) {
        if (!mIsScrolling) {
            return;
        }
        mIsScrolling = false;
        settleTo(mDistance >= OPEN_THRESHOLD ? 1f : 0f);
    }

    /** Closes the library, animating unless the caller is tearing the state down. */
    public void close(boolean animate) {
        mIsScrolling = false;
        if (animate) {
            settleTo(0f);
        } else {
            cancelSettle();
            mDistance = 0f;
            mCallbacks.onOverlayScrollChanged(0f);
        }
    }

    private void settleTo(float target) {
        cancelSettle();
        if (mDistance == target) {
            mCallbacks.onOverlayScrollChanged(target);
            return;
        }
        mSettleAnimator = ValueAnimator.ofFloat(mDistance, target);
        mSettleAnimator.setDuration(
                (long) (SETTLE_DURATION_MS * Math.abs(target - mDistance)));
        mSettleAnimator.setInterpolator(mSettleInterpolator);
        mSettleAnimator.addUpdateListener(a -> {
            mDistance = (float) a.getAnimatedValue();
            mCallbacks.onOverlayScrollChanged(mDistance);
        });
        mSettleAnimator.start();
    }

    private void cancelSettle() {
        if (mSettleAnimator != null) {
            mSettleAnimator.cancel();
            mSettleAnimator = null;
        }
    }

    @Override
    public boolean draw(Canvas canvas) {
        return false;
    }

    @Override
    public void finish() {
        cancelSettle();
        mDistance = 0f;
    }
}
