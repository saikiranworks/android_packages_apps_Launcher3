package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.text.format.DateFormat;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuickActionProvider implements SearchProvider {

    private static final Pattern TIMER = Pattern.compile(
            "^(?:set (?:a )?)?timer (?:for )?(\\d+) ?([a-z]*)$"
                    + "|^(\\d+) ?([a-z]*) timer$");
    private static final Pattern ALARM = Pattern.compile(
            "^(?:set (?:an )?)?(?:alarm|wake me)(?: up)?(?: (?:for|at))? "
                    + "(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm|a|p)?$");
    private static final Pattern CONTACT_ACTION = Pattern.compile(
            "^(call|dial|ring|text|message|msg|sms|email|mail) (.+)$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 ()\\-]{3,}[0-9]$");
    private static final Pattern URL = Pattern.compile(
            "^(https?://)?([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(:\\d+)?(/\\S*)?$");
    private static final Pattern REMINDER = Pattern.compile(
            "^(?:remind me(?: to)?|reminder|add event|new event) (.+)$");

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_QUICK_ACTION;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_QUICK_ACTIONS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        String trimmed = query.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.length() < 3) {
            return out;
        }
        addTimer(context, lower, out);
        addAlarm(context, lower, out);
        addContactAction(context, trimmed, lower, out);
        addPhoneNumber(context, trimmed, out);
        addUrl(context, lower, out);
        addReminder(context, trimmed, lower, out);
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static void addTimer(Context context, String query, List<UniversalSearchResult> out) {
        Matcher m = TIMER.matcher(query);
        if (!m.matches()) {
            return;
        }
        boolean suffix = m.group(1) == null;
        int amount;
        try {
            amount = Integer.parseInt(suffix ? m.group(3) : m.group(1));
        } catch (NumberFormatException e) {
            return;
        }
        String unit = suffix ? m.group(4) : m.group(2);
        int seconds;
        String label;
        if (unit.isEmpty() || unit.startsWith("m")) {
            seconds = amount * 60;
            label = context.getResources().getQuantityString(
                    R.plurals.search_duration_minutes, amount, amount);
        } else if (unit.startsWith("s")) {
            seconds = amount;
            label = context.getResources().getQuantityString(
                    R.plurals.search_duration_seconds, amount, amount);
        } else if (unit.startsWith("h")) {
            seconds = amount * 3600;
            label = context.getResources().getQuantityString(
                    R.plurals.search_duration_hours, amount, amount);
        } else {
            return;
        }
        if (seconds <= 0 || seconds > 24 * 3600) {
            return;
        }
        // The status bar island shows the running timer, so there is no need to open Clock.
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        UniversalSearchResult result = action(context, "timer",
                context.getString(R.string.search_action_timer, label), null, intent,
                R.drawable.ic_search_timer);
        result.confirmation = context.getString(R.string.search_timer_set, label);
        out.add(result);
    }

    private static void addAlarm(Context context, String query, List<UniversalSearchResult> out) {
        Matcher m = ALARM.matcher(query);
        if (!m.matches()) {
            return;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        String half = m.group(3);
        if (half != null) {
            if (hour < 1 || hour > 12) {
                return;
            }
            hour = hour % 12 + (half.startsWith("p") ? 12 : 0);
        }
        if (hour > 23 || minute > 59) {
            return;
        }
        Calendar time = Calendar.getInstance();
        time.set(Calendar.HOUR_OF_DAY, hour);
        time.set(Calendar.MINUTE, minute);
        String label = DateFormat.getTimeFormat(context).format(time.getTime());
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        UniversalSearchResult result = action(context, "alarm",
                context.getString(R.string.search_action_alarm, label), null, intent,
                R.drawable.ic_search_alarm);
        result.confirmation = context.getString(R.string.search_alarm_set, label);
        out.add(result);
    }

    private static void addContactAction(Context context, String query, String lower,
            List<UniversalSearchResult> out) {
        Matcher m = CONTACT_ACTION.matcher(lower);
        if (!m.matches()) {
            return;
        }
        String verb = m.group(1);
        String target = query.substring(verb.length()).trim();
        boolean email = verb.endsWith("mail");
        boolean call = verb.equals("call") || verb.equals("dial") || verb.equals("ring");
        String name = null;
        String address;
        if (!email && PHONE.matcher(target).matches()) {
            address = target;
        } else if (email && target.contains("@")) {
            address = target;
        } else {
            if (!ContactProvider.hasPermission(context)) {
                return;
            }
            String[] match = lookup(context, target, email);
            if (match == null) {
                return;
            }
            name = match[0];
            address = match[1];
        }
        String shown = name == null ? address : name;
        Intent intent;
        int title;
        int icon;
        if (email) {
            intent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null));
            title = R.string.search_action_email;
            icon = R.drawable.ic_search_email;
        } else if (call) {
            intent = new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null));
            title = R.string.search_action_call;
            icon = R.drawable.ic_search_call;
        } else {
            intent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", address, null));
            title = R.string.search_action_text;
            icon = R.drawable.ic_search_conversation;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(action(context, verb + ":" + address, context.getString(title, shown),
                name == null ? null : address, intent, icon));
    }

    /** Returns the best matching contact's {name, number or email}, or null. */
    private static String[] lookup(Context context, String name, boolean email) {
        Uri filter = email ? ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI
                : ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI;
        String column = email ? ContactsContract.CommonDataKinds.Email.ADDRESS
                : ContactsContract.CommonDataKinds.Phone.NUMBER;
        String[] projection = {ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, column};
        try (Cursor c = context.getContentResolver().query(
                Uri.withAppendedPath(filter, Uri.encode(name)), projection, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            return new String[]{c.getString(0), c.getString(1)};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void addPhoneNumber(Context context, String query,
            List<UniversalSearchResult> out) {
        if (!PHONE.matcher(query).matches() || digits(query) < 5) {
            return;
        }
        String shown = PhoneNumberUtils.formatNumber(query, Locale.getDefault().getCountry());
        if (shown == null) {
            shown = query;
        }
        out.add(action(context, "dial", context.getString(R.string.search_action_call, shown),
                null, new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", query, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                R.drawable.ic_search_call));
        out.add(action(context, "sms", context.getString(R.string.search_action_text, shown),
                null, new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", query, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                R.drawable.ic_search_conversation));
        out.add(action(context, "add_contact",
                context.getString(R.string.search_action_add_contact), shown,
                new Intent(ContactsContract.Intents.Insert.ACTION)
                        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, query)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                R.drawable.ic_search_person_add));
    }

    private static int digits(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static void addUrl(Context context, String query, List<UniversalSearchResult> out) {
        if (!URL.matcher(query).matches()) {
            return;
        }
        String url = query.startsWith("http") ? query : "https://" + query;
        out.add(action(context, "url", context.getString(R.string.search_action_open, query),
                null, new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                R.drawable.ic_search_link));
    }

    private static void addReminder(Context context, String query, String lower,
            List<UniversalSearchResult> out) {
        Matcher m = REMINDER.matcher(lower);
        if (!m.matches()) {
            return;
        }
        String title = query.substring(query.length() - m.group(1).length()).trim();
        Intent intent = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(action(context, "reminder",
                context.getString(R.string.search_action_reminder, title), null, intent,
                R.drawable.ic_search_event));
    }

    private static UniversalSearchResult action(Context context, String id, String title,
            String subtitle, Intent intent, int icon) {
        UniversalSearchResult result = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_QUICK_ACTION, id, title, subtitle, intent,
                Process.myUserHandle(), 900);
        result.iconRes = icon;
        return result;
    }
}
