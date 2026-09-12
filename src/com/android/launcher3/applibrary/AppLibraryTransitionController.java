
package com.android.launcher3.applibrary;

import static com.android.app.animation.Interpolators.FINAL_FRAME;
import static com.android.app.animation.Interpolators.INSTANT;
import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.app.animation.Interpolators.scrollInterpolatorForVelocity;
import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.NORMAL;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.touch.BaseSwipeDetector;

public class AppLibraryTransitionController {

    private static final long SCRUB_DURATION_MS = 400;

    private final Launcher mLauncher;

    private AnimatorPlaybackController mAnimation;
    private LauncherState mOrigin;
    private LauncherState mDestination;

    private final AnimatorListenerAdapter mClearOnCancel = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationCancel(Animator animation) {
            mAnimation = null;
        }
    };

    public AppLibraryTransitionController(Launcher launcher) {
        mLauncher = launcher;
    }

    public void onOpenPull(float progress) {
        pull(NORMAL, ALL_APPS, progress);
    }

    public void onOpenReleased(boolean open, float velocity) {
        release(open, velocity);
    }

    public void onDismissPull(float progress) {
        pull(ALL_APPS, NORMAL, progress);
    }

    public void onDismissReleased(boolean dismiss, float velocity) {
        release(dismiss, velocity);
    }

    private void pull(LauncherState origin, LauncherState destination, float progress) {
        if (mAnimation == null) {
            if (!mLauncher.isInState(origin)) {
                    return;
            }
            mOrigin = origin;
            mDestination = destination;
            StateAnimationConfig config = new StateAnimationConfig();
            config.duration = SCRUB_DURATION_MS;
            config.setInterpolator(StateAnimationConfig.ANIM_VERTICAL_PROGRESS, LINEAR);
            config.setInterpolator(StateAnimationConfig.ANIM_WORKSPACE_TRANSLATE, LINEAR);
            config.setInterpolator(StateAnimationConfig.ANIM_WORKSPACE_PAGE_TRANSLATE_X, LINEAR);
            config.setInterpolator(StateAnimationConfig.ANIM_WORKSPACE_SCALE, LINEAR);
            config.setInterpolator(StateAnimationConfig.ANIM_HOTSEAT_TRANSLATE, LINEAR);
            config.setInterpolator(StateAnimationConfig.ANIM_HOTSEAT_SCALE, LINEAR);
            config.setInterpolator(StateAnimationConfig.ANIM_SCRIM_FADE, LINEAR);
            config.setInterpolator(StateAnimationConfig.ANIM_DEPTH, LINEAR);
            config.setInterpolator(StateAnimationConfig.ANIM_ALL_APPS_FADE,
                    destination == ALL_APPS ? INSTANT : FINAL_FRAME);
            mAnimation = mLauncher.getStateManager()
                    .createAnimationToNewWorkspace(destination, config);
            mAnimation.getTarget().addListener(mClearOnCancel);
            mAnimation.dispatchOnStart();
        }
        mAnimation.setPlayFraction(progress);
    }

    private void release(boolean commit, float velocity) {
        if (mAnimation == null) {
            return;
        }
        AnimatorPlaybackController anim = mAnimation;
        mAnimation = null;
        anim.getTarget().removeListener(mClearOnCancel);

        float from = anim.getProgressFraction();
        float to = commit ? 1f : 0f;
        if (!commit) {
            anim.dispatchOnCancel();
        }

        LauncherState target = commit ? mDestination : mOrigin;
        anim.setEndAction(() -> {
            if (!mLauncher.isInState(target)) {
                mLauncher.getStateManager().goToState(target, false /* animated */);
            }
        });

        float velocityPxPerMs = velocity / 1000f;
        ValueAnimator player = anim.getAnimationPlayer();
        player.setFloatValues(from, to);
        player.setDuration(
                BaseSwipeDetector.calculateDuration(velocityPxPerMs, Math.abs(to - from)));
        player.setInterpolator(scrollInterpolatorForVelocity(velocityPxPerMs));
        anim.dispatchOnStart();
        player.start();
    }
}
