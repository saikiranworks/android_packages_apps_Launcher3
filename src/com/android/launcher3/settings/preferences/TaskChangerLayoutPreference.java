/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.settings.preferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.launcher3.R;

/**
 * Picks the recents layout from a list, beside a drawing of the screen in that layout, as
 * Samsung's Task changer does.
 */
public class TaskChangerLayoutPreference extends Preference {

    public static final String[] LAYOUTS = {"carousel", "tilt", "grid", "vertical", "slim"};
    private static final int[] LABELS = {
            R.string.task_changer_layout_carousel, R.string.task_changer_layout_tilt,
            R.string.task_changer_layout_grid, R.string.task_changer_layout_vertical,
            R.string.task_changer_layout_slim};

    public TaskChangerLayoutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_task_changer_layout);
        setPersistent(true);
    }

    public String getLayout() {
        return getPersistedString(LAYOUTS[0]);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        RadioGroup group = (RadioGroup) holder.findViewById(R.id.task_changer_layouts);
        Preview preview = (Preview) holder.findViewById(R.id.task_changer_preview);
        String current = getLayout();
        preview.setLayout(current);

        group.setOnCheckedChangeListener(null);
        group.removeAllViews();
        for (int i = 0; i < LAYOUTS.length; i++) {
            RadioButton button = new RadioButton(getContext());
            button.setId(View.generateViewId());
            button.setText(LABELS[i]);
            button.setTag(LAYOUTS[i]);
            button.setChecked(LAYOUTS[i].equals(current));
            group.addView(button);
        }
        group.setOnCheckedChangeListener((g, id) -> {
            String layout = (String) g.findViewById(id).getTag();
            if (layout.equals(getLayout()) || !callChangeListener(layout)) return;
            persistString(layout);
            preview.setLayout(layout);
        });
    }

    /** A small phone showing the chosen layout. */
    public static class Preview extends View {

        private final Paint mFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCard = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Path mPath = new Path();
        private final float mDensity;
        private String mLayout = LAYOUTS[0];

        public Preview(Context context, AttributeSet attrs) {
            super(context, attrs);
            mDensity = getResources().getDisplayMetrics().density;
            int onSurface = color(context, android.R.attr.textColorPrimary);
            int accent = color(context, android.R.attr.colorAccent);
            mFrame.setStyle(Paint.Style.STROKE);
            mFrame.setStrokeWidth(2 * mDensity);
            mFrame.setColor(onSurface);
            mFrame.setAlpha(90);
            mCard.setColor(onSurface);
            mCard.setAlpha(170);
            mDim.setColor(onSurface);
            mDim.setAlpha(70);
            mAccent.setColor(accent);
        }

        private static int color(Context context, int attr) {
            ColorStateList list = context.obtainStyledAttributes(new int[] {attr})
                    .getColorStateList(0);
            return list != null ? list.getDefaultColor() : 0xFF888888;
        }

        void setLayout(String layout) {
            mLayout = layout;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight() - 4 * mDensity;
            float w = Math.min(getWidth() - 4 * mDensity, h * 0.48f);
            float left = (getWidth() - w) / 2f;
            float top = 2 * mDensity;
            mRect.set(left, top, left + w, top + h);
            canvas.drawRoundRect(mRect, 10 * mDensity, 10 * mDensity, mFrame);
            float r = 3 * mDensity;
            switch (mLayout) {
                case "grid":
                    for (int row = 0; row < 2; row++) {
                        for (int col = 0; col < 3; col++) {
                            float cw = (w - 16 * mDensity) / 3f;
                            float x = left + 5 * mDensity + col * (cw + 3 * mDensity);
                            float y = top + 14 * mDensity + row * (cw * 1.9f + 10 * mDensity);
                            canvas.drawCircle(x + cw / 2f, y, 2.5f * mDensity, mAccent);
                            mRect.set(x, y + 5 * mDensity, x + cw, y + 5 * mDensity + cw * 1.7f);
                            canvas.drawRoundRect(mRect, r, r, mCard);
                        }
                    }
                    break;
                case "vertical":
                    for (int i = 0; i < 3; i++) {
                        float y = top + h - (i + 1) * (h * 0.27f);
                        canvas.drawCircle(left + 10 * mDensity, y + h * 0.1f, 3 * mDensity,
                                mAccent);
                        mRect.set(left + 16 * mDensity, y + h * 0.09f, left + w * 0.45f,
                                y + h * 0.11f);
                        canvas.drawRoundRect(mRect, r, r, mDim);
                        mRect.set(left + w * 0.52f, y, left + w - 5 * mDensity, y + h * 0.23f);
                        canvas.drawRoundRect(mRect, r, r, mCard);
                    }
                    break;
                case "slim":
                    for (int i = 0; i < 5; i++) {
                        float y = top + h - 10 * mDensity - (i + 1) * (h * 0.13f);
                        mRect.set(left + 5 * mDensity, y, left + w - 5 * mDensity,
                                y + h * 0.1f);
                        canvas.drawRoundRect(mRect, h * 0.05f, h * 0.05f, mDim);
                        canvas.drawCircle(left + 12 * mDensity, y + h * 0.05f, 3 * mDensity,
                                mAccent);
                    }
                    break;
                default: {
                    boolean tilt = "tilt".equals(mLayout);
                    float cw = w * 0.55f;
                    float ch = h * 0.62f;
                    float cx = left + w / 2f;
                    float cy = top + h * 0.45f;
                    // Side cards, leaning away when tilted
                    for (int side = -1; side <= 1; side += 2) {
                        float inner = cx + side * (cw / 2f + 4 * mDensity);
                        float outer = inner + side * cw * 0.6f;
                        float lean = tilt ? ch * 0.09f : 0f;
                        mPath.reset();
                        mPath.moveTo(inner, cy - ch / 2f);
                        mPath.lineTo(outer, cy - ch / 2f + lean);
                        mPath.lineTo(outer, cy + ch / 2f - lean);
                        mPath.lineTo(inner, cy + ch / 2f);
                        mPath.close();
                        canvas.save();
                        canvas.clipRect(left + 2 * mDensity, top, left + w - 2 * mDensity,
                                top + h);
                        canvas.drawPath(mPath, mDim);
                        canvas.restore();
                    }
                    mRect.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f);
                    canvas.drawRoundRect(mRect, r, r, mCard);
                    canvas.drawCircle(cx, cy - ch / 2f - 6 * mDensity, 3 * mDensity, mAccent);
                }
            }
        }
    }
}
