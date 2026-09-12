
package com.android.launcher3.applibrary;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;
import com.android.launcher3.util.TouchController;

public class AppLibraryTouchController implements TouchController {

    private static final float COMMIT_THRESHOLD = 0.25f;

    private final Launcher mLauncher;
    private final AppLibraryTransitionController mTransition;
    private final boolean mIsRtl;
    private final int mTouchSlop;
    private final int mMinFlingVelocity;

    private VelocityTracker mVelocityTracker;
    private float mDownRawX;
    private float mDownRawY;
    private int mDirection;
    private boolean mOpening;
    private boolean mDragging;

    public AppLibraryTouchController(Launcher launcher) {
        mLauncher = launcher;
        mTransition = new AppLibraryTransitionController(launcher);
        mIsRtl = Utilities.isRtl(launcher.getResources());
        mTouchSlop = ViewConfiguration.get(launcher).getScaledTouchSlop();
        mMinFlingVelocity =
                launcher.getResources().getDimensionPixelSize(R.dimen.min_fling_velocity);
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDragging = false;
            mDirection = 0;
            mDownRawX = ev.getRawX();
            mDownRawY = ev.getRawY();
            if (isLibraryShowing()) {
                mOpening = false;
                mDirection = mIsRtl ? -1 : 1;
            } else if (isAtLibraryEdge()) {
                mOpening = true;
                mDirection = mIsRtl ? 1 : -1;
            }
            return false;
        }
        if (mDirection == 0 || mDragging
                || ev.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        float travelled = travelled(ev);
        if (travelled <= mTouchSlop || travelled <= Math.abs(ev.getRawY() - mDownRawY)) {
            return false;
        }
        mDragging = true;
        mVelocityTracker = VelocityTracker.obtain();
        mVelocityTracker.addMovement(ev);
        return true;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent ev) {
        if (!mDragging) {
            return false;
        }
        mVelocityTracker.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pull(progress(ev));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mVelocityTracker.computeCurrentVelocity(1000);
                float velocity = mDirection * mVelocityTracker.getXVelocity();
                boolean commit = ev.getActionMasked() == MotionEvent.ACTION_UP
                        && (progress(ev) >= COMMIT_THRESHOLD || velocity > mMinFlingVelocity);
                release(commit, Math.abs(velocity));
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                mDragging = false;
                mDirection = 0;
                return true;
            default:
                return true;
        }
    }

    private void pull(float progress) {
        if (mOpening) {
            mTransition.onOpenPull(progress);
        } else {
            mTransition.onDismissPull(progress);
        }
    }

    private void release(boolean commit, float velocity) {
        if (mOpening) {
            mTransition.onOpenReleased(commit, velocity);
        } else {
            mTransition.onDismissReleased(commit, velocity);
        }
    }

    private float travelled(MotionEvent ev) {
        return mDirection * (ev.getRawX() - mDownRawX);
    }

    private float progress(MotionEvent ev) {
        int width = mLauncher.getDeviceProfile().getDeviceProperties().getWidthPx();
        if (width <= 0) {
            return 0f;
        }
        return Utilities.boundToRange(travelled(ev) / width, 0f, 1f);
    }

    private boolean isLibraryShowing() {
        return mLauncher.isInState(LauncherState.ALL_APPS)
                && mLauncher.getWorkspace() != null
                && mLauncher.getWorkspace().hasAppLibrary();
    }

    private boolean isAtLibraryEdge() {
        Workspace<?> workspace = mLauncher.getWorkspace();
        return mLauncher.isInState(LauncherState.NORMAL)
                && workspace != null
                && workspace.isAtAppLibraryEdge();
    }
}
