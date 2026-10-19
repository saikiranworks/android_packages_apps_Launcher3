/*
 * SPDX-FileCopyrightText: The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.search.universal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.WindowManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.BaseActivity;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.views.BaseDragLayer;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.BaseAllAppsAdapter;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.util.Executors;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * iOS's Spotlight, opened from the home screen's Search pill: a field over the blurred home
 * screen and a glass card under it, with suggested apps, a link on the clipboard and what's
 * playing before anything is typed, and universal search results once something is.
 */
public class SpotlightView extends AbstractFloatingView
        implements SearchCallback<AdapterItem>, TextWatcher {

    private static final int APPS_PER_ROW = 4;
    private static final int APP_CELL_WIDTH_DP = 104;
    private static final String SPOTLIGHT_OPEN_SETTING = "penguin_spotlight_open";
    // The card blurs a quarter-size snapshot of what's behind it, which also keeps the blur cheap.
    private static final int BACKDROP_SCALE = 4;
    private static final float BACKDROP_BLUR_RADIUS = 18f;
    private static final float HOME_BLUR_RADIUS = 10f;
    private static final PathInterpolator EASE = new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    private BaseActivity mHost;
    private UniversalSearchAlgorithm mSearch;
    private EditText mInput;
    private View mField;
    private View mScrim;
    private RecyclerView mResults;
    private View mCard;
    private ImageView mBackdrop;
    // In the island's place, all of home blurs behind Spotlight as on iOS, not just the card.
    @Nullable
    private ImageView mHomeBlur;
    private final Adapter mAdapter = new Adapter();
    private boolean mAtTop;
    private boolean mHadWindowFocus;
    // The Dynamic Island the field grows out of and shrinks back into.
    private final RectF mIsland = new RectF();
    // The island at rest around the camera, which places the field.
    private final RectF mIslandAtRest = new RectF();
    // The field's visible shape while it morphs, in its own coordinates.
    private final RectF mFieldShape = new RectF();

    public SpotlightView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Opens Spotlight over the home screen. With [atTop] the field grows out of [from] at the top
     * of the screen, as out of the Dynamic Island; otherwise it grows out of the Search pill in
     * place, just above the keyboard.
     */
    public static void show(Launcher launcher, RectF from, boolean atTop) {
        // Capture before attaching, on the UI thread. Attaching synchronously prevents two
        // keyboard presses from queuing two independent captures and two search views.
        attach(launcher, from, atTop, snapshotHome(launcher));
    }

    private static void attach(BaseActivity host, RectF from, boolean atTop, Bitmap capture) {
        BaseDragLayer<?> layer = host.getDragLayer();
        for (int i = layer.getChildCount() - 1; i >= 0; i--) {
            if (layer.getChildAt(i) instanceof SpotlightView old) {
                if (old.isOpen()) return;
                old.removeFromHost();
            }
        }
        if (atTop) setTakingIslandsPlace(host, true);
        SpotlightView view = (SpotlightView) LayoutInflater.from(host)
                .inflate(R.layout.spotlight_view, layer, false);
        view.setup(host, atTop, from);
        view.setBackdrop(capture);
        layer.addView(view);
        view.mIsOpen = true;
        view.requestApplyInsets();
        view.post(() -> view.animateOpen(from));
    }

    public static void toggleFromKeyboard(Launcher launcher) {
        if (closeFromKeyboard(launcher)) return;
        View anchor = launcher.getHotseat().getQsb();
        launcher.openSearchFromPill(anchor != null ? anchor : launcher.getHotseat());
    }

    private static boolean closeFromKeyboard(BaseActivity host) {
        BaseDragLayer<?> layer = host.getDragLayer();
        for (int i = layer.getChildCount() - 1; i >= 0; i--) {
            if (layer.getChildAt(i) instanceof SpotlightView view) {
                boolean wasOpen = view.isOpen();
                if (wasOpen) view.close(false);
                else view.removeFromHost();
                if (wasOpen) return true;
            }
        }
        return false;
    }

    /**
     * The home screen drawn by hand, with the static wallpaper under it, where the screen can't
     * be captured.
     */
    private static Bitmap snapshotHome(Launcher launcher) {
        View root = launcher.getDragLayer();
        if (root.getWidth() == 0 || root.getHeight() == 0) {
            return null;
        }
        int width = root.getWidth() / BACKDROP_SCALE;
        int height = root.getHeight() / BACKDROP_SCALE;
        try {
            return snapshotHome(launcher, root, width, height, true);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Mid app-close animation the workspace is drawn through a software drawing cache,
            // which can't draw the icons' hardware bitmaps. Blur the wallpaper alone this time.
            return snapshotHome(launcher, root, width, height, false);
        }
    }

    private static Bitmap snapshotHome(Launcher launcher, View root, int width, int height,
            boolean withViews) {
        // Recorded and rendered on the GPU: the icons are hardware bitmaps a software canvas
        // can't draw.
        Picture picture = new Picture();
        Canvas canvas = picture.beginRecording(width, height);
        canvas.scale(1f / BACKDROP_SCALE, 1f / BACKDROP_SCALE);
        try {
            Drawable wallpaper = WallpaperManager.getInstance(launcher).getDrawable();
            if (wallpaper != null && wallpaper.getIntrinsicWidth() > 0
                    && wallpaper.getIntrinsicHeight() > 0) {
                // Centre cropped to the screen, as the system shows it.
                float scale = Math.max(
                        (float) root.getWidth() / wallpaper.getIntrinsicWidth(),
                        (float) root.getHeight() / wallpaper.getIntrinsicHeight());
                int w = Math.round(wallpaper.getIntrinsicWidth() * scale);
                int h = Math.round(wallpaper.getIntrinsicHeight() * scale);
                int left = (root.getWidth() - w) / 2;
                int top = (root.getHeight() - h) / 2;
                wallpaper.setBounds(left, top, left + w, top + h);
                wallpaper.draw(canvas);
            }
        } catch (RuntimeException e) {
            // A live wallpaper, or none readable: the card shows the icons alone.
        }
        try {
            if (withViews) {
                root.draw(canvas);
            }
        } finally {
            picture.endRecording();
        }
        return Bitmap.createBitmap(picture, width, height, Bitmap.Config.HARDWARE);
    }

    private void setBackdrop(Bitmap backdrop) {
        if (backdrop == null) {
            return;
        }
        mBackdrop.setImageBitmap(backdrop);
        mBackdrop.setRenderEffect(RenderEffect.createBlurEffect(
                BACKDROP_BLUR_RADIUS, BACKDROP_BLUR_RADIUS, Shader.TileMode.CLAMP));
        // Keep the snapshot lined up with the screen under the card as the card moves and grows.
        mCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> alignBackdrop());
        if (mAtTop) {
            mHomeBlur = new ImageView(getContext());
            mHomeBlur.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mHomeBlur.setScaleType(ImageView.ScaleType.MATRIX);
            Matrix matrix = new Matrix();
            matrix.setScale(BACKDROP_SCALE, BACKDROP_SCALE);
            mHomeBlur.setImageMatrix(matrix);
            mHomeBlur.setImageBitmap(backdrop);
            mHomeBlur.setRenderEffect(RenderEffect.createBlurEffect(
                    HOME_BLUR_RADIUS, HOME_BLUR_RADIUS, Shader.TileMode.CLAMP));
            ((ViewGroup) findViewById(R.id.spotlight_frame)).addView(mHomeBlur, 0,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void alignBackdrop() {
        // The card's laid-out position, ignoring its open animation's scale/translation: a
        // transformed location shifted the snapshot and left an empty dark strip at one edge.
        int x = 0;
        int y = 0;
        for (View v = mCard; v != null; v = v.getParent() instanceof View p ? p : null) {
            x += v.getLeft() - v.getScrollX();
            y += v.getTop() - v.getScrollY();
        }
        Matrix matrix = new Matrix();
        matrix.setScale(BACKDROP_SCALE, BACKDROP_SCALE);
        matrix.postTranslate(-x, -y);
        mBackdrop.setImageMatrix(matrix);
    }

    private void setup(BaseActivity launcher, boolean atTop, RectF from) {
        mHost = launcher;
        mAtTop = atTop;
        mIsland.set(from);
        if (atTop) {
            ((Launcher) launcher).islandBounds(mIslandAtRest);
        }
        mScrim = findViewById(R.id.spotlight_scrim);
        mField = findViewById(R.id.spotlight_field);
        mInput = findViewById(R.id.spotlight_input);
        mResults = findViewById(R.id.spotlight_results);
        mCard = findViewById(R.id.spotlight_card);
        mBackdrop = findViewById(R.id.spotlight_backdrop);
        mCard.setClipToOutline(true);
        findViewById(R.id.spotlight_mic).setOnClickListener(v -> {
            try {
                mHost.startActivity(new Intent(RecognizerIntent.ACTION_WEB_SEARCH)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                // No voice search installed.
            }
        });
        mResults.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            ViewGroup.LayoutParams lp = mBackdrop.getLayoutParams();
            if (lp.height != b - t) {
                lp.height = b - t;
                mBackdrop.post(() -> mBackdrop.setLayoutParams(lp));
            }
        });
        mResults.setLayoutManager(new LinearLayoutManager(launcher));
        mResults.setAdapter(mAdapter);

        LinearLayout content = findViewById(R.id.spotlight_content);
        if (atTop) {
            // As on iOS the field becomes the island: it keeps the island's top edge around the
            // camera, tall enough that its centred text row clears the lens, with the status bar,
            // island and all, out of the way.
            mField.getLayoutParams().height =
                    Math.max(0, Math.round(mIslandAtRest.height()) - dp(8)) + dp(56);
            mField.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(Math.round(mFieldShape.left), Math.round(mFieldShape.top),
                            Math.round(mFieldShape.right), Math.round(mFieldShape.bottom),
                            Math.min(mFieldShape.width(), mFieldShape.height()) / 2f);
                }
            });
            mField.setClipToOutline(true);
            mScrim.setBackgroundColor(0x40000000);
        } else {
            // Over a desktop window there is no home snapshot to blur, so darken the scrim.
            // SearchActivity also blurs the windows behind this one.
            mScrim.setBackgroundColor(0xB3000000);
            // Search stays by the thumb: the card stacks above the field, the field above the
            // keyboard.
            content.removeView(mField);
            content.addView(mField);
            content.setGravity(android.view.Gravity.BOTTOM);
            ((ViewGroup.MarginLayoutParams) mCard.getLayoutParams()).topMargin = 0;
            ((ViewGroup.MarginLayoutParams) mCard.getLayoutParams()).bottomMargin =
                    getResources().getDimensionPixelSize(R.dimen.spotlight_margin);
        }

        mScrim.setOnClickListener(v -> close(true));
        mInput.addTextChangedListener(this);
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                return launchFirst();
            }
            return false;
        });

        mSearch = new UniversalSearchAlgorithm(launcher, launcher.getUiExecutor());
        mSearch.setSpotlight(true);
        mSearch.setQueryHandler(query -> {
            mInput.setText(query);
            mInput.setSelection(query.length());
        });
        mSearch.doSearch("", this);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        View content = findViewById(R.id.spotlight_content);
        int top = insets.getInsets(WindowInsets.Type.systemBars()).top;
        int bottom = Math.max(insets.getInsets(WindowInsets.Type.ime()).bottom,
                insets.getInsets(WindowInsets.Type.systemBars()).bottom);
        int gap = getResources().getDimensionPixelSize(R.dimen.spotlight_margin);
        content.setPadding(content.getPaddingLeft(), mAtTop ? Math.round(mIslandAtRest.top) : top + gap,
                content.getPaddingRight(), bottom + gap);
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mCard == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        View content = findViewById(R.id.spotlight_content);
        ViewGroup.MarginLayoutParams cardParams =
                (ViewGroup.MarginLayoutParams) mCard.getLayoutParams();
        cardParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // The bottom field must fit alongside a scrollable card, even with a landscape IME.
        // LinearLayout otherwise measures the card before the field and can push it off screen.
        int available = Math.max(0, getMeasuredHeight() - content.getPaddingTop()
                - content.getPaddingBottom() - mField.getMeasuredHeight()
                - cardParams.topMargin - cardParams.bottomMargin);
        if (mCard.getMeasuredHeight() > available) {
            cardParams.height = available;
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private void animateOpen(RectF from) {
        if (!mIsOpen || !isAttachedToWindow()) return;
        int[] location = new int[2];
        mField.getLocationInWindow(location);
        if (mAtTop) {
            morphField(true, null);
        } else if (mField.getWidth() > 0 && mField.getHeight() > 0) {
            mField.setPivotX(0);
            mField.setPivotY(0);
            mField.setScaleX(Math.min(1f, from.width() / mField.getWidth()));
            mField.setScaleY(Math.min(1f, from.height() / mField.getHeight()));
            mField.setTranslationX(from.left - location[0]);
            mField.setTranslationY(from.top - location[1]);
            mField.animate().scaleX(1).scaleY(1).translationX(0).translationY(0)
                    .setDuration(420).setInterpolator(EASE).start();
        }
        mScrim.setAlpha(0f);
        mScrim.animate().alpha(1f).setDuration(250).start();
        if (mHomeBlur != null) {
            mHomeBlur.setAlpha(0f);
            mHomeBlur.animate().alpha(1f).setDuration(250).start();
        }
        mCard.setAlpha(0f);
        mCard.setScaleX(0.94f);
        mCard.setScaleY(0.94f);
        mCard.animate().alpha(1f).scaleX(1).scaleY(1).setStartDelay(120).setDuration(320)
                .setInterpolator(EASE).start();

        mInput.requestFocus();
        getContext().getSystemService(InputMethodManager.class)
                .showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    protected void handleClose(boolean animate) {
        mIsOpen = false;
        mSearch.cancel(true);
        getContext().getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(getWindowToken(), 0);
        if (!animate) {
            if (mAtTop) {
                setTakingIslandsPlace(mHost, false);
            }
            removeFromHost();
            return;
        }
        if (mAtTop) {
            mScrim.animate().alpha(0f).setDuration(200).start();
            if (mHomeBlur != null) {
                mHomeBlur.animate().alpha(0f).setDuration(240).start();
            }
            mCard.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setStartDelay(0)
                    .setDuration(160).start();
            // Back into the island where it is now, or into the camera when it shows nothing.
            boolean islandShowing = mHost instanceof Launcher launcher
                    && launcher.spotlightAnchor(mIsland);
            mField.setElevation(0);
            morphField(false, () -> {
                setTakingIslandsPlace(mHost, false);
                if (islandShowing) {
                    // Held under the real island while it fades back in over it.
                    postDelayed(() -> removeFromHost(), 250);
                } else {
                    removeFromHost();
                }
            });
            return;
        }
        animate().alpha(0f).setDuration(180)
                .withEndAction(() -> removeFromHost()).start();
    }

    private void removeFromHost() {
        animate().cancel();
        mField.animate().cancel();
        mCard.animate().cancel();
        mInput.clearFocus();
        BaseDragLayer<?> layer = mHost.getDragLayer();
        layer.removeView(this);
        if (mHost instanceof SearchActivity activity) {
            activity.finish();
        } else if (mHost.hasWindowFocus()) {
            layer.setFocusableInTouchMode(true);
            layer.requestFocus();
        }
    }

    /**
     * Tells SystemUI that Spotlight stands in for the Dynamic Island, so it fades the status bar
     * out and back in itself; hiding it through window insets lagged, and came back late enough to
     * leave a gap where the island should be.
     */
    public static void setTakingIslandsPlace(Context context, boolean taking) {
        Settings.Secure.putInt(context.getContentResolver(), SPOTLIGHT_OPEN_SETTING, taking ? 1 : 0);
    }

    /**
     * Grows the field out of the island, or shrinks it back in, by reshaping its rounded outline
     * rather than stretching it, so it stays a pill all the way as the island does on iOS.
     */
    private void morphField(boolean open, @Nullable Runnable onEnd) {
        int[] location = new int[2];
        mField.getLocationInWindow(location);
        RectF island = new RectF(mIsland);
        island.offset(-location[0], -location[1]);
        RectF field = new RectF(0, 0, mField.getWidth(), mField.getHeight());
        ViewGroup row = (ViewGroup) mField;
        ValueAnimator morph = ValueAnimator.ofFloat(open ? 0f : 1f, open ? 1f : 0f);
        morph.setDuration(open ? 420 : 300);
        morph.setInterpolator(EASE);
        morph.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            mFieldShape.set(island.left + (field.left - island.left) * t,
                    island.top + (field.top - island.top) * t,
                    island.right + (field.right - island.right) * t,
                    island.bottom + (field.bottom - island.bottom) * t);
            mField.invalidateOutline();
            // The field's contents come in once it has room for them, and go before it shrinks.
            float contentAlpha = Math.max(0f, Math.min(1f, (t - 0.45f) / 0.4f));
            for (int i = 0; i < row.getChildCount(); i++) {
                row.getChildAt(i).setAlpha(contentAlpha);
            }
        });
        if (onEnd != null) {
            morph.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onEnd.run();
                }
            });
        }
        morph.start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // Something opened from here took over the screen.
        if (hasWindowFocus) mHadWindowFocus = true;
        if (!hasWindowFocus && mHadWindowFocus && mIsOpen) {
            // Not while the window is still telling its views about the focus change.
            post(() -> {
                if (mIsOpen && !hasWindowFocus()) close(false);
            });
        }
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_ON_BOARD_POPUP) != 0;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)
                && event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
            close(true);
            return true;
        }
        // Up and Down move a single highlight through the results. The text cursor stays in
        // the field, and Left and Right still move it unless an app row is being browsed.
        if (event.getAction() == KeyEvent.ACTION_DOWN && mAdapter.handleKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean launchFirst() {
        for (int i = 0; i < mAdapter.getItemCount(); i++) {
            Row row = mAdapter.mRows.get(i);
            if (row.result != null) {
                return UniversalSearchResults.launch(mHost, mInput, row.result);
            }
            if (row.apps != null && !row.apps.isEmpty()) {
                AppInfo app = row.apps.get(0);
                return mHost.startActivitySafely(mInput, app.getIntent(), app) != null;
            }
        }
        return false;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) { }

    @Override
    public void afterTextChanged(Editable s) {
        mSearch.cancel(false);
        mSearch.doSearch(s.toString(), this);
    }

    @Override
    public void onSearchResult(String query, ArrayList<AdapterItem> items) {
        if (!query.equals(mInput.getText().toString()) || !mIsOpen) {
            return;
        }
        mAdapter.setRows(query, query.trim().isEmpty() ? suggestions(items) : rows(items));
    }

    @Override
    public void clearSearchResult() {
        mAdapter.setRows("", new ArrayList<>());
    }

    /**
     * Before anything is typed: four suggested apps, then a link on the clipboard and what's
     * playing, then recent searches, as iOS arranges its Siri Suggestions.
     */
    private List<Row> suggestions(List<AdapterItem> items) {
        List<AppInfo> apps = new ArrayList<>();
        List<AdapterItem> rest = new ArrayList<>();
        for (AdapterItem item : items) {
            if (item.itemInfo != null && BaseAllAppsAdapter.isIconViewType(item.viewType)) {
                if (apps.size() < APPS_PER_ROW) apps.add(item.itemInfo);
            } else {
                rest.add(item);
            }
        }
        List<Row> rows = new ArrayList<>();
        if (!LauncherPrefs.SPOTLIGHT_SUGGESTIONS.get(getContext())) {
            // Suggestions off: only what the user asked for (e.g. recent searches) remains.
            rows.addAll(rows(rest));
            return rows;
        }
        if (!apps.isEmpty()) {
            rows.add(Row.header(getContext().getString(R.string.spotlight_suggestions)));
            rows.add(Row.apps(apps));
        }
        UniversalSearchResult link = clipboardLink();
        if (link != null) {
            rows.add(Row.header(getContext().getString(R.string.spotlight_clipboard_link)));
            rows.add(Row.result(link));
        }
        UniversalSearchResult playing = nowPlaying();
        if (playing != null) {
            rows.add(Row.header(getContext().getString(R.string.spotlight_now_playing)));
            rows.add(Row.result(playing));
        }
        rows.addAll(rows(rest));
        return rows;
    }

    private List<Row> rows(List<AdapterItem> items) {
        List<Row> rows = new ArrayList<>();
        List<AppInfo> apps = null;
        for (AdapterItem item : items) {
            if (item.itemInfo != null && BaseAllAppsAdapter.isIconViewType(item.viewType)) {
                if (apps == null || apps.size() == APPS_PER_ROW) {
                    apps = new ArrayList<>();
                    rows.add(Row.apps(apps));
                }
                apps.add(item.itemInfo);
                continue;
            }
            apps = null;
            if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_SEARCH_SECTION_HEADER) {
                rows.add(Row.header(UniversalSearchResults.getSectionTitle(
                        getContext(), item.searchSection)));
            } else if (item.searchResult != null) {
                rows.add(Row.result(item.searchResult));
            } else if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_SEARCH_FILTERS
                    || item.viewType == BaseAllAppsAdapter.VIEW_TYPE_SEARCH_THUMBNAILS) {
                rows.add(Row.item(item));
            }
        }
        return rows;
    }

    /** A web link the user copied, offered as iOS does, opening in the default browser. */
    private UniversalSearchResult clipboardLink() {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
            return null;
        }
        Matcher matcher = Patterns.WEB_URL.matcher(clip.getItemAt(0).getText());
        if (!matcher.find()) {
            return null;
        }
        String url = matcher.group();
        Uri uri = Uri.parse(url.contains("://") ? url : "https://" + url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        UniversalSearchResult result = new UniversalSearchResult(UniversalSearchResult.SOURCE_WEB,
                "clipboard", getContext().getString(R.string.spotlight_open_link),
                uri.getHost() + uri.getPath(), intent, null, 0);
        PackageManager pm = getContext().getPackageManager();
        ResolveInfo browser = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (browser != null) {
            result.icon = browser.loadIcon(pm);
        }
        return result;
    }

    /** What's playing, or paused most recently, to go back to in one tap. */
    private UniversalSearchResult nowPlaying() {
        MediaSessionManager sessions = getContext().getSystemService(MediaSessionManager.class);
        if (sessions == null) {
            return null;
        }
        List<MediaController> controllers;
        try {
            controllers = sessions.getActiveSessions(null);
        } catch (SecurityException e) {
            return null;
        }
        for (MediaController controller : controllers) {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            if (metadata == null || state == null
                    || metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == null) {
                continue;
            }
            boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_QUICK_ACTION, "now_playing",
                    playing ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                            : getContext().getString(R.string.spotlight_play,
                                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE)),
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST), null, null, 0);
            try {
                result.icon = getContext().getPackageManager()
                        .getApplicationIcon(controller.getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) {
                // No icon; the row still works.
            }
            MediaController.TransportControls controls = controller.getTransportControls();
            result.onTap = () -> {
                if (playing) {
                    Intent launch = getContext().getPackageManager()
                            .getLaunchIntentForPackage(controller.getPackageName());
                    if (launch != null) mHost.startActivity(launch);
                } else {
                    controls.play();
                    close(true);
                }
            };
            return result;
        }
        return null;
    }

    /** One line of the card: a row of apps, a section title, a result, or a drawer item. */
    private static final class Row {
        static final int APPS = 0;
        static final int HEADER = 1;
        static final int RESULT = 2;
        static final int FILTERS = 3;
        static final int THUMBNAILS = 4;

        int type;
        List<AppInfo> apps;
        CharSequence title;
        UniversalSearchResult result;
        AdapterItem item;

        static Row apps(List<AppInfo> apps) {
            Row row = new Row();
            row.type = APPS;
            row.apps = apps;
            return row;
        }

        static Row header(CharSequence title) {
            Row row = new Row();
            row.type = HEADER;
            row.title = title;
            return row;
        }

        static Row result(UniversalSearchResult result) {
            Row row = new Row();
            row.type = RESULT;
            row.result = result;
            return row;
        }

        static Row item(AdapterItem item) {
            Row row = new Row();
            row.type = item.viewType == BaseAllAppsAdapter.VIEW_TYPE_SEARCH_FILTERS
                    ? FILTERS : THUMBNAILS;
            row.item = item;
            return row;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A section title as iOS sets it: bold, with a hairline under it. */
    private View headerView(ViewGroup parent) {
        LinearLayout header = new LinearLayout(parent.getContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setPadding(dp(16), dp(14), dp(16), dp(6));
        TextView title = new TextView(parent.getContext());
        title.setTag("title");
        title.setTextColor(0xE6FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.addView(title);
        View divider = new View(parent.getContext());
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.topMargin = dp(10);
        header.addView(divider, lp);
        return header;
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int HIGHLIGHT = 0x33FFFFFF;

        private List<Row> mRows = new ArrayList<>();
        private String mQuery = "";
        /** Index into {@link #selectable()}, not into {@link #mRows}. */
        private int mSelected = 0;
        private int mSelectedApp = 0;
        private boolean mBrowsing;

        void setRows(String query, List<Row> rows) {
            String selectedKey = selectionKey();
            String selectedApp = selectionAppKey();
            boolean sameQuery = query.equals(mQuery);
            boolean browsing = mBrowsing;
            mQuery = query;
            mRows = rows;
            if (!sameQuery || !restoreSelection(selectedKey, selectedApp)) {
                mSelected = 0;
                mSelectedApp = 0;
                mBrowsing = false;
            } else {
                mBrowsing = browsing;
            }
            notifyDataSetChanged();
            mCard.setVisibility(rows.isEmpty() ? INVISIBLE : VISIBLE);
            if (mResults != null && !sameQuery) {
                mResults.scrollToPosition(0);
            } else if (mResults != null && mBrowsing) {
                List<Integer> selectable = selectable();
                if (!selectable.isEmpty()) {
                    mResults.scrollToPosition(
                            selectable.get(Math.min(mSelected, selectable.size() - 1)));
                }
            }
        }

        /** Keeps the highlight on the same result when a later batch arrives for this query. */
        private boolean restoreSelection(String key, String appKey) {
            if (key == null) {
                return false;
            }
            List<Integer> rows = selectable();
            for (int i = 0; i < rows.size(); i++) {
                Row row = mRows.get(rows.get(i));
                if (row.type == Row.RESULT && row.result != null
                        && key.equals(resultKey(row.result))) {
                    mSelected = i;
                    mSelectedApp = 0;
                    return true;
                }
                if (row.type == Row.APPS && "apps".equals(key) && row.apps != null) {
                    if (appKey == null) {
                        mSelected = i;
                        mSelectedApp = 0;
                        return true;
                    }
                    for (int a = 0; a < row.apps.size(); a++) {
                        if (appKey.equals(SearchHistory.appKey(
                                row.apps.get(a).getTargetComponent(), row.apps.get(a).user))) {
                            mSelected = i;
                            mSelectedApp = a;
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private String selectionKey() {
            List<Integer> rows = selectable();
            if (rows.isEmpty()) {
                return null;
            }
            Row row = mRows.get(rows.get(Math.min(mSelected, rows.size() - 1)));
            if (row.type == Row.RESULT && row.result != null) {
                return resultKey(row.result);
            }
            if (row.type == Row.APPS) {
                return "apps";
            }
            return null;
        }

        private String selectionAppKey() {
            List<Integer> rows = selectable();
            if (rows.isEmpty()) {
                return null;
            }
            Row row = mRows.get(rows.get(Math.min(mSelected, rows.size() - 1)));
            if (row.type != Row.APPS || row.apps == null || row.apps.isEmpty()) {
                return null;
            }
            AppInfo app = row.apps.get(Math.min(mSelectedApp, row.apps.size() - 1));
            return SearchHistory.appKey(app.getTargetComponent(), app.user);
        }

        private static String resultKey(UniversalSearchResult result) {
            return result.source + ":" + result.id;
        }

        /**
         * Desktop movement: Up and Down walk results, Left and Right walk the suggested apps
         * once that row is reached, and Enter opens the highlighted one.
         */
        boolean handleKey(KeyEvent event) {
            int code = event.getKeyCode();
            List<Integer> rows = selectable();
            if (rows.isEmpty()) {
                return false;
            }
            boolean ctrl = event.isCtrlPressed();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN
                    || (code == KeyEvent.KEYCODE_TAB && !event.isShiftPressed())
                    || (ctrl && (code == KeyEvent.KEYCODE_N || code == KeyEvent.KEYCODE_J))) {
                move(1, rows);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP
                    || (code == KeyEvent.KEYCODE_TAB && event.isShiftPressed())
                    || (ctrl && (code == KeyEvent.KEYCODE_P || code == KeyEvent.KEYCODE_K))) {
                move(-1, rows);
                return true;
            }
            if (code == KeyEvent.KEYCODE_PAGE_DOWN) {
                move(5, rows);
                return true;
            }
            if (code == KeyEvent.KEYCODE_PAGE_UP) {
                move(-5, rows);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return moveApp(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, rows);
            }
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                // A held Enter must not open the result again and again.
                return event.getRepeatCount() > 0 || launchSelected(rows);
            }
            return false;
        }

        private void move(int delta, List<Integer> rows) {
            int next = Math.max(0, Math.min(rows.size() - 1, mSelected + delta));
            if (delta < 0 && mSelected == 0 && mBrowsing) {
                // Up from the first row hands the keys back to the text field.
                mBrowsing = false;
                notifyDataSetChanged();
                return;
            }
            mBrowsing = true;
            if (next != mSelected) {
                // Keep the column when stepping between rows of suggested apps.
                Row row = mRows.get(rows.get(next));
                mSelectedApp = row.type == Row.APPS && row.apps != null && !row.apps.isEmpty()
                        ? Math.min(mSelectedApp, row.apps.size() - 1) : 0;
            }
            mSelected = next;
            notifyDataSetChanged();
            mResults.scrollToPosition(rows.get(mSelected));
        }

        /** @return true when this key should not also move the text cursor */
        private boolean moveApp(int delta, List<Integer> rows) {
            Row row = mRows.get(rows.get(Math.min(mSelected, rows.size() - 1)));
            if (row.type != Row.APPS || row.apps == null || row.apps.isEmpty()) {
                return false;
            }
            // Once browsing, or with nothing typed, the arrows walk the apps. While typing,
            // Right at the end of the text also steps into them so a query need not be left.
            int len = mInput.length();
            boolean atEnd = mInput.getSelectionStart() == len && mInput.getSelectionEnd() == len;
            if (!mBrowsing && len > 0 && !(delta > 0 && atEnd)) {
                return false;
            }
            int next = Math.max(0, Math.min(row.apps.size() - 1, mSelectedApp + delta));
            mBrowsing = true;
            mSelectedApp = next;
            notifyDataSetChanged();
            return true;
        }

        private boolean launchSelected(List<Integer> rows) {
            Row row = mRows.get(rows.get(Math.min(mSelected, rows.size() - 1)));
            if (row.type == Row.APPS && row.apps != null && !row.apps.isEmpty()) {
                AppInfo app = row.apps.get(Math.min(mSelectedApp, row.apps.size() - 1));
                SearchHistory.recordLaunch(getContext(), SearchHistory.appKey(
                        app.getTargetComponent(), app.user));
                return mHost.startActivitySafely(mInput, app.getIntent(), app) != null;
            }
            if (row.result != null) {
                return UniversalSearchResults.launch(mHost, mInput, row.result);
            }
            return false;
        }

        private List<Integer> selectable() {
            List<Integer> rows = new ArrayList<>();
            for (int i = 0; i < mRows.size(); i++) {
                Row row = mRows.get(i);
                if (row.type == Row.APPS || row.type == Row.RESULT) {
                    rows.add(i);
                }
            }
            return rows;
        }

        private boolean isHighlighted(int position) {
            List<Integer> rows = selectable();
            return !rows.isEmpty() && rows.get(Math.min(mSelected, rows.size() - 1)) == position;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            // The card is dark glass whatever the home screen's theme, so its rows are too.
            LayoutInflater inflater = LayoutInflater.from(new ContextThemeWrapper(
                    parent.getContext(), android.R.style.Theme_DeviceDefault));
            View view = switch (type) {
                case Row.APPS -> {
                    LinearLayout row = new LinearLayout(parent.getContext());
                    row.setLayoutParams(new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    // A compact, left-aligned row of fixed-width cells lined up with the
                    // section titles, instead of four icons stretched across the whole card.
                    row.setGravity(android.view.Gravity.START);
                    row.setPadding(dp(4), dp(4), dp(4), dp(4));
                    int cellWidth = dp(APP_CELL_WIDTH_DP);
                    // Icon + label + a little air: the drawer's cell height leaves a big gap
                    // above the icons here, since they centre themselves vertically.
                    var allApps = mHost.getDeviceProfile().getAllAppsProfile();
                    int cellHeight = Math.min(allApps.getCellHeightPx(),
                            allApps.getIconSizePx() + allApps.getIconDrawablePaddingPx()
                                    + Utilities.calculateTextHeight(allApps.getIconTextSizePx())
                                    + dp(20));
                    for (int i = 0; i < APPS_PER_ROW; i++) {
                        BubbleTextView icon = (BubbleTextView) inflater.inflate(
                                R.layout.all_apps_icon, row, false);
                        row.addView(icon, new LinearLayout.LayoutParams(cellWidth, cellHeight));
                    }
                    yield row;
                }
                case Row.HEADER -> headerView(parent);
                case Row.FILTERS -> inflater.inflate(R.layout.search_filters, parent, false);
                case Row.THUMBNAILS -> inflater.inflate(R.layout.search_thumbnails, parent, false);
                default -> inflater.inflate(R.layout.search_result_row, parent, false);
            };
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            switch (row.type) {
                case Row.APPS -> {
                    ViewGroup group = (ViewGroup) holder.itemView;
                    boolean rowHighlighted = isHighlighted(position);
                    for (int i = 0; i < group.getChildCount(); i++) {
                        BubbleTextView icon = (BubbleTextView) group.getChildAt(i);
                        icon.reset();
                        icon.setFocusable(false);
                        if (i >= row.apps.size()) {
                            icon.setVisibility(INVISIBLE);
                            icon.setForeground(null);
                            continue;
                        }
                        AppInfo app = row.apps.get(i);
                        icon.setVisibility(VISIBLE);
                        icon.applyFromApplicationInfo(app);
                        icon.setTextColor(0xFFFFFFFF);
                        icon.setForeground(rowHighlighted && i == mSelectedApp
                                ? new android.graphics.drawable.ColorDrawable(HIGHLIGHT) : null);
                        icon.setOnClickListener(v -> {
                            SearchHistory.recordLaunch(v.getContext(), SearchHistory.appKey(
                                    app.getTargetComponent(), app.user));
                            mHost.startActivitySafely(v, app.getIntent(), app);
                        });
                    }
                }
                case Row.HEADER -> ((TextView) holder.itemView.findViewWithTag("title"))
                        .setText(row.title);
                case Row.RESULT -> {
                    UniversalSearchResults.bind(mHost, holder.itemView, row.result);
                    holder.itemView.setFocusable(false);
                    holder.itemView.setForeground(isHighlighted(position)
                            ? new android.graphics.drawable.ColorDrawable(HIGHLIGHT) : null);
                    // A touch larger than the drawer's rows, at the system's body size.
                    TextView title = holder.itemView.findViewById(R.id.search_result_title);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    View icon = holder.itemView.findViewById(R.id.search_result_icon);
                    int size = dp(40);
                    icon.getLayoutParams().width = size;
                    icon.getLayoutParams().height = size;
                }
                case Row.FILTERS -> UniversalSearchResults.bindFilters(
                        holder.itemView, row.item.searchFilters);
                case Row.THUMBNAILS -> UniversalSearchResults.bindThumbnails(
                        mHost, holder.itemView, row.item.searchThumbnails);
            }
        }
    }
    /** Spotlight over the current app; finishing restores that app's task. */
    public static class SearchActivity extends BaseActivity {
        private SearchDragLayer mLayer;
        private static java.lang.ref.WeakReference<SearchActivity> sCurrent =
                new java.lang.ref.WeakReference<>(null);

        @Override
        protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            mDeviceProfile = InvariantDeviceProfile.INSTANCE.get(this).getDeviceProfile(this);
            getWindow().setDecorFitsSystemWindows(false);
            // Blur the desktop windows behind this translucent activity so result text stays
            // readable. The scrim still covers the case where window blurs are disabled.
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            attrs.setBlurBehindRadius(80);
            getWindow().setAttributes(attrs);
            mLayer = new SearchDragLayer(this);
            setContentView(mLayer);
            sCurrent = new java.lang.ref.WeakReference<>(this);
            mLayer.post(() -> {
                if (isFinishing()) return;
                float bottom = mLayer.getHeight() - 32 * getResources().getDisplayMetrics().density;
                attach(this, new RectF(0, bottom, mLayer.getWidth(), bottom), false, null);
            });
        }

        @Override
        protected void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            onBackPressed();
        }

        @Override
        public BaseDragLayer<?> getDragLayer() { return mLayer; }

        @Override
        public void onBackPressed() {
            if (!closeFromKeyboard(this)) finish();
        }

        @Override
        protected void onPause() {
            super.onPause();
            if (!isFinishing()) finish();
        }

        @Override
        protected void onDestroy() {
            if (mLayer != null) closeFromKeyboard(this);
            if (sCurrent.get() == this) sCurrent.clear();
            super.onDestroy();
        }
    }

    private static class SearchDragLayer extends BaseDragLayer<SearchActivity> {
        SearchDragLayer(SearchActivity activity) {
            super(activity, null, 1);
            mControllers = new com.android.launcher3.util.TouchController[0];
        }
    }

    private static com.android.internal.policy.IShortcutService sShortcut;

    /** Register once per process; WindowManager drops the registration when its binder dies. */
    public static void registerGlobalShortcut(Context context) {
        if (sShortcut != null) return;
        // Older framework versions notify on key down, on every key repeat and on key up.
        // There, count only the first notification of each press (see onShortcutNotified).
        // The updated framework notifies once per press, so no filtering is needed.
        try {
            sLegacyNotify = android.view.WindowManagerGlobal.class
                    .getField("SHORTCUT_CALLBACK_VERSION").getInt(null) < 1;
        } catch (ReflectiveOperationException e) {
            sLegacyNotify = true;
        }
        Context app = context.getApplicationContext();
        long code = KeyEvent.KEYCODE_SPACE | ((long) KeyEvent.META_ALT_ON << 32);
        com.android.internal.policy.IShortcutService receiver =
                new com.android.internal.policy.IShortcutService.Stub() {
            @Override
            public void notifyShortcutKeyPressed(long shortcutCode) {
                if (onShortcutNotified(android.os.SystemClock.uptimeMillis())) {
                    Executors.MAIN_EXECUTOR.execute(() -> toggleGlobal(app));
                }
            }
        };
        try {
            android.view.WindowManagerGlobal.getWindowManagerService()
                    .registerShortcutKey(code, receiver);
            sShortcut = receiver;
        } catch (android.os.RemoteException | SecurityException e) {
            android.util.Log.w("Spotlight", "Unable to register Alt+Space", e);
        }
    }

    /**
     * The desktop-mode taskbar's Search button: opens Spotlight over the desktop in its own
     * translucent activity, or closes it if it is already showing.
     */
    public static void toggleFromTaskbar(Context context) {
        SearchActivity activity = SearchActivity.sCurrent.get();
        if (activity != null && !activity.isFinishing()) {
            if (!closeFromKeyboard(activity)) activity.finish();
            return;
        }
        context.startActivity(new Intent(context, SearchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    private static boolean sLegacyNotify;
    private static long sLastNotifyTime = -1;

    /**
     * Whether this shortcut notification starts a new press. On the older framework one press
     * notifies on down, then repeats while held, then on release; all arrive within the key
     * repeat delay of each other, so a gap longer than that marks the next press.
     */
    private static synchronized boolean onShortcutNotified(long now) {
        if (!sLegacyNotify) return true;
        long gap = now - sLastNotifyTime;
        sLastNotifyTime = now;
        return gap > android.view.ViewConfiguration.getKeyRepeatTimeout() + LEGACY_PRESS_GAP_MS;
    }

    private static final long LEGACY_PRESS_GAP_MS = 150;

    private static void toggleGlobal(Context context) {
        android.app.KeyguardManager keyguard =
                context.getSystemService(android.app.KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return;
        boolean keyboard = false;
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device != null && !device.isVirtual()
                    && device.getKeyboardType() == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                keyboard = true;
                break;
            }
        }
        if (!keyboard) return;
        SearchActivity activity = SearchActivity.sCurrent.get();
        if (activity != null && !activity.isFinishing()) {
            if (!closeFromKeyboard(activity)) activity.finish();
            return;
        }
        Launcher launcher = Launcher.ACTIVITY_TRACKER.getCreatedContext();
        if (launcher != null && launcher.hasBeenResumed() && launcher.hasWindowFocus()) {
            toggleFromKeyboard(launcher);
        } else {
            context.startActivity(new Intent(context, SearchActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        }
    }

}
