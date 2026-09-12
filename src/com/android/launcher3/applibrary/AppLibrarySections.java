
package com.android.launcher3.applibrary;

import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_ICON_ROW;

import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.model.data.AppInfo;

public class AppLibrarySections {

    private AppLibrarySections() {}

    public static String of(AdapterItem item) {
        if (item == null || item.viewType != VIEW_TYPE_ICON_ROW
                || !(item.itemInfo instanceof AppInfo app)) {
            return null;
        }
        CharSequence title = app.title;
        if (title == null || title.length() == 0) {
            return null;
        }
        char first = Character.toUpperCase(title.charAt(0));
        return Character.isLetter(first) ? String.valueOf(first) : "#";
    }
}
