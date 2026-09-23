package com.android.launcher3.applibrary;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import com.android.launcher3.R;

/**
 * The App Library sits on the blurred wallpaper, so it keeps the dark theme's light text even when
 * the device is in light theme. Launcher's themes follow the system night mode, so the dark style
 * alone isn't enough; the configuration has to say night too.
 */
public final class AppLibraryTheme {

    private AppLibraryTheme() {}

    public static Context wrap(Context context) {
        Configuration config = new Configuration();
        config.uiMode = (context.getResources().getConfiguration().uiMode
                & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        ContextThemeWrapper themed = new ContextThemeWrapper(context, R.style.AppTheme_Dark);
        themed.applyOverrideConfiguration(config);
        return themed;
    }
}
