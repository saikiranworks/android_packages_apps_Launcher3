package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import android.provider.Settings;

import java.util.List;

public interface SearchProvider {

    int getSource();

    boolean isEnabled(Context context);

    List<UniversalSearchResult> query(Context context, String query, int max);

    /**
     * A row standing in for a source whose permission was revoked after it was switched on, so
     * the section does not just silently go empty.
     */
    static List<UniversalSearchResult> permissionRequest(Context context, int source,
            int titleRes) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return List.of(new UniversalSearchResult(source, "permission",
                context.getString(titleRes),
                context.getString(com.android.launcher3.R.string.search_permission_summary),
                intent, Process.myUserHandle(), 0));
    }
}
