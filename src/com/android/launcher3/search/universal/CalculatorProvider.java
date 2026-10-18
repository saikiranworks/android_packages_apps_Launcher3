package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import android.provider.AlarmClock;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CalculatorProvider implements SearchProvider {

    private static final Pattern CONVERSION = Pattern.compile(
            "^\\s*(-?[0-9.]+)\\s*([a-z°]+)\\s+(?:in|to)\\s+([a-z°]+)\\s*$");
    private static final DecimalFormat FORMAT = new DecimalFormat("#,##0.######");
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final Pattern CURRENCY = Pattern.compile(
            "^\\s*([0-9][0-9.,]*)\\s*([a-z]{3})(?:\\s+(?:in|to)\\s+([a-z]{3}))?\\s*$");
    private static final Pattern TIME_IN = Pattern.compile(
            "^(?:what time is it in|current time in|time in|time at) (.+)$|^(.+) time$");
    private static final Pattern DATE_MATH = Pattern.compile(
            "^(in )?(\\d+) (day|week|month|year)s?( from today| from now| later| ago)?$");

    // Currencies in the ECB reference feed.
    private static final Set<String> CURRENCIES = Set.of("eur", "usd", "jpy", "bgn", "czk",
            "dkk", "gbp", "huf", "pln", "ron", "sek", "chf", "isk", "nok", "try", "aud", "brl",
            "cad", "cny", "hkd", "idr", "ils", "inr", "krw", "mxn", "myr", "nzd", "php", "sgd",
            "thb", "zar");
    private static final Map<String, String> SYMBOLS = Map.of(
            "$", "usd", "€", "eur", "£", "gbp", "₹", "inr", "¥", "jpy");
    private static final Map<String, String> ZONE_ALIASES = Map.ofEntries(
            Map.entry("utc", "UTC"), Map.entry("gmt", "GMT"),
            Map.entry("est", "America/New_York"), Map.entry("edt", "America/New_York"),
            Map.entry("cst", "America/Chicago"), Map.entry("cdt", "America/Chicago"),
            Map.entry("mst", "America/Denver"), Map.entry("mdt", "America/Denver"),
            Map.entry("pst", "America/Los_Angeles"), Map.entry("pdt", "America/Los_Angeles"),
            Map.entry("ist", "Asia/Kolkata"), Map.entry("india", "Asia/Kolkata"),
            Map.entry("delhi", "Asia/Kolkata"), Map.entry("new delhi", "Asia/Kolkata"),
            Map.entry("mumbai", "Asia/Kolkata"), Map.entry("bangalore", "Asia/Kolkata"),
            Map.entry("bengaluru", "Asia/Kolkata"), Map.entry("chennai", "Asia/Kolkata"),
            Map.entry("hyderabad", "Asia/Kolkata"), Map.entry("jst", "Asia/Tokyo"),
            Map.entry("japan", "Asia/Tokyo"), Map.entry("cet", "Europe/Paris"),
            Map.entry("cest", "Europe/Paris"), Map.entry("bst", "Europe/London"),
            Map.entry("uk", "Europe/London"), Map.entry("aest", "Australia/Sydney"),
            Map.entry("san francisco", "America/Los_Angeles"),
            Map.entry("seattle", "America/Los_Angeles"), Map.entry("boston", "America/New_York"),
            Map.entry("washington", "America/New_York"), Map.entry("beijing", "Asia/Shanghai"),
            Map.entry("china", "Asia/Shanghai"), Map.entry("dubai", "Asia/Dubai"),
            Map.entry("uae", "Asia/Dubai"), Map.entry("sf", "America/Los_Angeles"),
            Map.entry("nyc", "America/New_York"));
    private static Map<String, String> sCities;

    private static final Map<String, Double> LENGTH = Map.ofEntries(
            Map.entry("mm", 0.001), Map.entry("cm", 0.01), Map.entry("m", 1.0),
            Map.entry("km", 1000.0), Map.entry("in", 0.0254), Map.entry("inch", 0.0254),
            Map.entry("ft", 0.3048), Map.entry("feet", 0.3048), Map.entry("yd", 0.9144),
            Map.entry("mi", 1609.344), Map.entry("mile", 1609.344),
            Map.entry("miles", 1609.344), Map.entry("nmi", 1852.0));

    private static final Map<String, Double> MASS = Map.of(
            "mg", 0.000001, "g", 0.001, "kg", 1.0, "t", 1000.0,
            "oz", 0.028349523125, "lb", 0.45359237, "lbs", 0.45359237, "st", 6.35029318);

    private static final Map<String, Double> DATA = Map.of(
            "b", 1.0, "kb", 1024.0, "mb", 1048576.0, "gb", 1073741824.0,
            "tb", 1099511627776.0);

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_CALCULATOR;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_CALCULATOR.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return out;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        UniversalSearchResult special = timeIn(context, lower);
        if (special == null) {
            special = dateMath(context, lower);
        }
        if (special != null) {
            out.add(special);
            return out;
        }
        String answer = convert(lower);
        String subtitle = trimmed;
        if (answer == null) {
            answer = convertCurrency(context, lower);
            if (answer != null) {
                subtitle = currencySource(context, trimmed);
            }
        }
        if (answer == null) {
            answer = evaluate(trimmed);
        }
        if (answer == null) {
            return out;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APP_CALCULATOR")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        UniversalSearchResult result = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_CALCULATOR, "calc", answer, subtitle,
                intent, Process.myUserHandle(), 1000);
        result.copyText = answer;
        out.add(result);
        return out;
    }

    private static String convertCurrency(Context context, String query) {
        Matcher m = CURRENCY.matcher(normalizeSymbols(query));
        if (!m.matches()) {
            return null;
        }
        String from = m.group(2);
        String to = m.group(3);
        if (to == null) {
            try {
                to = java.util.Currency.getInstance(Locale.getDefault())
                        .getCurrencyCode().toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (!CURRENCIES.contains(from) || !CURRENCIES.contains(to) || from.equals(to)) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        Map<String, Double> rates = CurrencyRates.get(context);
        if (rates == null || !rates.containsKey(from) || !rates.containsKey(to)) {
            return null;
        }
        double result = value / rates.get(from) * rates.get(to);
        return MONEY.format(result) + " " + to.toUpperCase(Locale.ROOT);
    }

    private static String currencySource(Context context, String query) {
        long fetchedAt = CurrencyRates.fetchedAt();
        String date = DateUtils.formatDateTime(context, fetchedAt,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
        boolean stale = System.currentTimeMillis() - fetchedAt > DateUtils.DAY_IN_MILLIS;
        return context.getString(stale ? R.string.search_currency_source_stale
                : R.string.search_currency_source, query, date);
    }

    private static String normalizeSymbols(String query) {
        String out = query;
        for (Map.Entry<String, String> symbol : SYMBOLS.entrySet()) {
            String quoted = Pattern.quote(symbol.getKey());
            out = out.replaceAll("^" + quoted + "\\s*([0-9.,]+)", "$1 " + symbol.getValue())
                    .replaceAll("(in|to)\\s*" + quoted + "$", "$1 " + symbol.getValue());
        }
        return out;
    }

    private static UniversalSearchResult timeIn(Context context, String query) {
        Matcher m = TIME_IN.matcher(query);
        if (!m.matches()) {
            return null;
        }
        String place = (m.group(1) != null ? m.group(1) : m.group(2)).trim();
        ZoneId zone = zoneFor(place);
        if (zone == null) {
            return null;
        }
        TimeZone timeZone = TimeZone.getTimeZone(zone);
        java.text.DateFormat time = DateFormat.getTimeFormat(context);
        time.setTimeZone(timeZone);
        SimpleDateFormat day = new SimpleDateFormat("EEEE", Locale.getDefault());
        day.setTimeZone(timeZone);
        Date now = new Date();
        String offset = ZonedDateTime.now(zone).getOffset().getId().replace("Z", "+00:00");
        String subtitle = context.getString(R.string.search_time_in,
                capitalize(place), day.format(now), "GMT" + offset);
        UniversalSearchResult result = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_CALCULATOR, "time:" + zone.getId(),
                time.format(now), subtitle,
                new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Process.myUserHandle(), 1000);
        result.iconRes = R.drawable.ic_search_alarm;
        return result;
    }

    private static ZoneId zoneFor(String place) {
        String alias = ZONE_ALIASES.get(place);
        if (alias != null) {
            return ZoneId.of(alias);
        }
        synchronized (CalculatorProvider.class) {
            if (sCities == null) {
                sCities = new HashMap<>();
                for (String id : ZoneId.getAvailableZoneIds()) {
                    int slash = id.lastIndexOf('/');
                    if (slash < 0 || id.startsWith("Etc/") || id.startsWith("SystemV/")) {
                        continue;
                    }
                    sCities.putIfAbsent(
                            id.substring(slash + 1).replace('_', ' ').toLowerCase(Locale.ROOT),
                            id);
                }
            }
        }
        String id = sCities.get(place);
        return id == null ? null : ZoneId.of(id);
    }

    private static UniversalSearchResult dateMath(Context context, String query) {
        Matcher m = DATE_MATH.matcher(query);
        if (!m.matches()) {
            return null;
        }
        boolean in = m.group(1) != null;
        String direction = m.group(4);
        if (!in && direction == null) {
            return null;
        }
        int amount;
        try {
            amount = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount > 100_000) {
            return null;
        }
        if (" ago".equals(direction)) {
            amount = -amount;
        }
        LocalDate date = LocalDate.now();
        switch (m.group(3)) {
            case "day": date = date.plusDays(amount); break;
            case "week": date = date.plusWeeks(amount); break;
            case "month": date = date.plusMonths(amount); break;
            default: date = date.plusYears(amount); break;
        }
        long millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String title = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_YEAR);
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("content://com.android.calendar/time/" + millis))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        UniversalSearchResult result = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_CALCULATOR, "date", title, query, intent,
                Process.myUserHandle(), 1000);
        result.iconRes = R.drawable.ic_search_event;
        result.copyText = title;
        return result;
    }

    private static String capitalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean start = true;
        for (char c : text.toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ';
        }
        return out.toString();
    }

    private static String convert(String query) {
        Matcher m = CONVERSION.matcher(query);
        if (!m.matches()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String from = m.group(2);
        String to = m.group(3);
        Double result = temperature(value, from, to);
        if (result == null) {
            result = scale(value, from, to, LENGTH);
        }
        if (result == null) {
            result = scale(value, from, to, MASS);
        }
        if (result == null) {
            result = scale(value, from, to, DATA);
        }
        return result == null ? null : FORMAT.format(result) + " " + to;
    }

    private static Double scale(double value, String from, String to, Map<String, Double> units) {
        Double a = units.get(from);
        Double b = units.get(to);
        return a == null || b == null ? null : value * a / b;
    }

    private static Double temperature(double value, String from, String to) {
        double celsius;
        switch (from) {
            case "c": case "°c": case "celsius": celsius = value; break;
            case "f": case "°f": case "fahrenheit": celsius = (value - 32) * 5 / 9; break;
            case "k": case "kelvin": celsius = value - 273.15; break;
            default: return null;
        }
        switch (to) {
            case "c": case "°c": case "celsius": return celsius;
            case "f": case "°f": case "fahrenheit": return celsius * 9 / 5 + 32;
            case "k": case "kelvin": return celsius + 273.15;
            default: return null;
        }
    }

    private static String evaluate(String expression) {
        if (!expression.matches("^[-+*/%^(). 0-9]+$") || !expression.matches(".*[-+*/%^].*")) {
            return null;
        }
        try {
            Parser parser = new Parser(expression.replace(" ", ""));
            double value = parser.expression();
            if (!parser.done() || Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            return FORMAT.format(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static class Parser {
        private final String mText;
        private int mPos;

        Parser(String text) {
            mText = text;
        }

        boolean done() {
            return mPos >= mText.length();
        }

        double expression() {
            double value = term();
            while (!done() && (peek() == '+' || peek() == '-')) {
                char op = mText.charAt(mPos++);
                double rhs = term();
                value = op == '+' ? value + rhs : value - rhs;
            }
            return value;
        }

        private double term() {
            double value = power();
            while (!done() && (peek() == '*' || peek() == '/' || peek() == '%')) {
                char op = mText.charAt(mPos++);
                double rhs = power();
                if (op == '*') {
                    value *= rhs;
                } else if (op == '/') {
                    value /= rhs;
                } else {
                    value %= rhs;
                }
            }
            return value;
        }

        private double power() {
            double value = unary();
            if (!done() && peek() == '^') {
                mPos++;
                return Math.pow(value, power());
            }
            return value;
        }

        private double unary() {
            if (!done() && peek() == '-') {
                mPos++;
                return -unary();
            }
            if (!done() && peek() == '+') {
                mPos++;
                return unary();
            }
            return atom();
        }

        private double atom() {
            if (done()) {
                throw new IllegalStateException();
            }
            if (peek() == '(') {
                mPos++;
                double value = expression();
                if (done() || peek() != ')') {
                    throw new IllegalStateException();
                }
                mPos++;
                return value;
            }
            int start = mPos;
            while (!done() && (Character.isDigit(peek()) || peek() == '.')) {
                mPos++;
            }
            if (start == mPos) {
                throw new IllegalStateException();
            }
            return Double.parseDouble(mText.substring(start, mPos));
        }

        private char peek() {
            return mText.charAt(mPos);
        }
    }
}
