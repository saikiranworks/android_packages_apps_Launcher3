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
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
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
    private static final String SPOTLIGHT_OPEN_SETTING = "penguin_spotlight_open";
    // The card blurs a quarter-size snapshot of what's behind it, which also keeps the blur cheap.
    private static final int BACKDROP_SCALE = 4;
    private static final float BACKDROP_BLUR_RADIUS = 18f;
    private static final float HOME_BLUR_RADIUS = 10f;
    private static final PathInterpolator EASE = new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    private Launcher mLauncher;
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
        if (atTop) {
            // Out of the way before the field reaches the top, taking the island with it.
            setTakingIslandsPlace(launcher, true);
        }
        // The screen as it stands, live wallpaper and all, before Spotlight covers any of it.
        View root = launcher.getDragLayer();
        Executors.UI_HELPER_EXECUTOR.execute(() -> {
            Bitmap capture = ApiWrapper.INSTANCE.get(launcher)
                    .captureHomeWithWallpaper(root, 1f / BACKDROP_SCALE);
            Executors.MAIN_EXECUTOR.execute(() -> {
                SpotlightView view = (SpotlightView) LayoutInflater.from(launcher)
                        .inflate(R.layout.spotlight_view, launcher.getDragLayer(), false);
                view.setup(launcher, atTop, from);
                view.setBackdrop(capture != null ? capture : snapshotHome(launcher));
                launcher.getDragLayer().addView(view);
                view.mIsOpen = true;
                view.post(() -> view.animateOpen(from));
            });
        });
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
        root.draw(canvas);
        picture.endRecording();
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
        int[] location = new int[2];
        mCard.getLocationInWindow(location);
        Matrix matrix = new Matrix();
        matrix.setScale(BACKDROP_SCALE, BACKDROP_SCALE);
        matrix.postTranslate(-location[0] - mCard.getTranslationX(),
                -location[1] - mCard.getTranslationY());
        mBackdrop.setImageMatrix(matrix);
    }

    private void setup(Launcher launcher, boolean atTop, RectF from) {
        mLauncher = launcher;
        mAtTop = atTop;
        mIsland.set(from);
        if (atTop) {
            launcher.islandBounds(mIslandAtRest);
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
                mLauncher.startActivity(new Intent(RecognizerIntent.ACTION_WEB_SEARCH)
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

    private void animateOpen(RectF from) {
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
                setTakingIslandsPlace(mLauncher, false);
            }
            mLauncher.getDragLayer().removeView(this);
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
            boolean islandShowing = mLauncher.spotlightAnchor(mIsland);
            mField.setElevation(0);
            morphField(false, () -> {
                setTakingIslandsPlace(mLauncher, false);
                if (islandShowing) {
                    // Held under the real island while it fades back in over it.
                    postDelayed(() -> mLauncher.getDragLayer().removeView(this), 250);
                } else {
                    mLauncher.getDragLayer().removeView(this);
                }
            });
            return;
        }
        animate().alpha(0f).setDuration(180)
                .withEndAction(() -> mLauncher.getDragLayer().removeView(this)).start();
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
        if (!hasWindowFocus && mIsOpen) {
            // Not while the window is still telling its views about the focus change.
            post(() -> {
                if (mIsOpen) close(false);
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
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            close(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean launchFirst() {
        for (int i = 0; i < mAdapter.getItemCount(); i++) {
            Row row = mAdapter.mRows.get(i);
            if (row.result != null) {
                return UniversalSearchResults.launch(mLauncher, mInput, row.result);
            }
            if (row.apps != null && !row.apps.isEmpty()) {
                AppInfo app = row.apps.get(0);
                return mLauncher.startActivitySafely(mInput, app.getIntent(), app) != null;
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
        mAdapter.setRows(query.trim().isEmpty() ? suggestions(items) : rows(items));
    }

    @Override
    public void clearSearchResult() {
        mAdapter.setRows(new ArrayList<>());
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
        if (!apps.isEmpty()) rows.add(Row.apps(apps));
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
                    if (launch != null) mLauncher.startActivity(launch);
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
        private List<Row> mRows = new ArrayList<>();

        void setRows(List<Row> rows) {
            mRows = rows;
            notifyDataSetChanged();
            mCard.setVisibility(rows.isEmpty() ? INVISIBLE : VISIBLE);
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
                    row.setPadding(0, dp(8), 0, dp(8));
                    for (int i = 0; i < APPS_PER_ROW; i++) {
                        BubbleTextView icon = (BubbleTextView) inflater.inflate(
                                R.layout.all_apps_icon, row, false);
                        // Icons centre themselves in the drawer's cell height, and clip without it.
                        row.addView(icon, new LinearLayout.LayoutParams(0, mLauncher
                                .getDeviceProfile().getAllAppsProfile().getCellHeightPx(), 1f));
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
                    for (int i = 0; i < group.getChildCount(); i++) {
                        BubbleTextView icon = (BubbleTextView) group.getChildAt(i);
                        icon.reset();
                        if (i >= row.apps.size()) {
                            icon.setVisibility(INVISIBLE);
                            continue;
                        }
                        AppInfo app = row.apps.get(i);
                        icon.setVisibility(VISIBLE);
                        icon.applyFromApplicationInfo(app);
                        icon.setTextColor(0xFFFFFFFF);
                        icon.setOnClickListener(v -> {
                            SearchHistory.recordLaunch(v.getContext(), SearchHistory.appKey(
                                    app.getTargetComponent(), app.user));
                            mLauncher.startActivitySafely(v, app.getIntent(), app);
                        });
                    }
                }
                case Row.HEADER -> ((TextView) holder.itemView.findViewWithTag("title"))
                        .setText(row.title);
                case Row.RESULT -> {
                    UniversalSearchResults.bind(mLauncher, holder.itemView, row.result);
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
                        mLauncher, holder.itemView, row.item.searchThumbnails);
            }
        }
    }
}
