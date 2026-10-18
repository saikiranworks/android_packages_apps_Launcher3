package com.android.launcher3.search.universal;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.android.launcher3.LauncherPrefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class CalendarProvider implements SearchProvider {

    private static final String[] PROJECTION = {
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
    };

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_EVENT;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_EVENTS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.length() < 2) {
            return out;
        }
        if (!hasPermission(context)) {
            return SearchProvider.permissionRequest(context, getSource(),
                    com.android.launcher3.R.string.search_permission_calendar);
        }
        long now = System.currentTimeMillis();
        long start = now - TimeUnit.DAYS.toMillis(30);
        long end = now + TimeUnit.DAYS.toMillis(180);
        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, start);
        ContentUris.appendId(builder, end);
        String lower = query.toLowerCase();
        try (Cursor c = context.getContentResolver().query(builder.build(), PROJECTION,
                null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) {
                return out;
            }
            int idIndex = c.getColumnIndex(CalendarContract.Instances.EVENT_ID);
            int titleIndex = c.getColumnIndex(CalendarContract.Instances.TITLE);
            int beginIndex = c.getColumnIndex(CalendarContract.Instances.BEGIN);
            Set<String> seen = new HashSet<>();
            while (c.moveToNext() && out.size() < max) {
                String title = titleIndex < 0 ? null : c.getString(titleIndex);
                int score = TextUtils.isEmpty(title) ? 0 : FuzzyMatcher.score(lower, title);
                if (score == 0) {
                    continue;
                }
                long begin = beginIndex < 0 ? now : c.getLong(beginIndex);
                if (!seen.add(title + "@" + begin / DateUtils.DAY_IN_MILLIS)) {
                    continue;
                }
                Uri eventUri = ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI, c.getLong(idIndex));
                Intent intent = new Intent(Intent.ACTION_VIEW, eventUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                out.add(new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_EVENT, eventUri.toString(), title,
                        DateUtils.getRelativeTimeSpanString(context, begin, true),
                        intent, Process.myUserHandle(),
                        score));
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }
}
