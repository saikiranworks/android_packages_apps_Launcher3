package com.android.launcher3.search.universal;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;

import com.android.launcher3.search.StringMatcherUtility;

import java.util.Locale;

public final class FuzzyMatcher {

    public static final int EXACT = 100;
    public static final int PREFIX = 80;
    public static final int WORD = 50;
    private static final int INITIALS = 45;
    private static final int TYPO = 35;
    public static final int SUBSEQUENCE = 30;

    private FuzzyMatcher() {}

    /** @param query already lower-cased */
    public static int score(String query, String title) {
        if (TextUtils.isEmpty(query) || TextUtils.isEmpty(title)) {
            return 0;
        }
        String lower = title.toLowerCase(Locale.getDefault());
        if (lower.equals(query)) {
            return EXACT;
        }
        if (lower.startsWith(query)) {
            return PREFIX;
        }
        if (StringMatcherUtility.matches(query, title,
                StringMatcherUtility.StringMatcher.getInstance())) {
            return WORD;
        }
        if (query.indexOf(' ') >= 0) {
            return 0;
        }
        if (query.length() >= 2 && initials(lower).startsWith(query)) {
            return INITIALS;
        }
        if (query.length() >= 4 && typoPrefix(query, lower)) {
            return TYPO;
        }
        // Shorter than this, letters-in-order matches nearly everything ("cat" in Calculator).
        if (query.length() >= 4 && query.charAt(0) == lower.charAt(0)
                && subsequence(query, lower)) {
            return SUBSEQUENCE;
        }
        return 0;
    }

    private static String initials(String lower) {
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean letter = Character.isLetterOrDigit(c);
            if (letter && start) {
                out.append(c);
            }
            start = !letter;
        }
        return out.toString();
    }

    private static boolean typoPrefix(String query, String lower) {
        for (String word : lower.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= query.length() - 1
                    && withinOneEdit(query, word.substring(0,
                            Math.min(word.length(), query.length())))) {
                return true;
            }
            if (word.length() >= query.length() + 1
                    && withinOneEdit(query, word.substring(0, query.length() + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean withinOneEdit(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        boolean edited = false;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (edited) {
                return false;
            }
            edited = true;
            if (la > lb) {
                i++;
            } else if (lb > la) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return !edited || (i == la && j == lb);
    }

    private static boolean subsequence(String query, String lower) {
        int j = 0;
        for (int i = 0; i < lower.length() && j < query.length(); i++) {
            if (lower.charAt(i) == query.charAt(j)) {
                j++;
            }
        }
        return j == query.length();
    }

    public static CharSequence highlight(String query, CharSequence title) {
        if (TextUtils.isEmpty(query) || TextUtils.isEmpty(title)) {
            return title;
        }
        String q = query.trim().toLowerCase(Locale.getDefault());
        String lower = title.toString().toLowerCase(Locale.getDefault());
        if (q.isEmpty() || lower.length() != title.length()) {
            return title;
        }
        SpannableString out = new SpannableString(title);
        int at = wordStart(lower, q);
        if (at >= 0) {
            bold(out, at, at + q.length());
            return out;
        }
        if (q.indexOf(' ') >= 0) {
            return title;
        }
        int j = 0;
        for (int i = 0; i < lower.length() && j < q.length(); i++) {
            if (lower.charAt(i) == q.charAt(j)) {
                bold(out, i, i + 1);
                j++;
            }
        }
        return j == q.length() ? out : title;
    }

    private static int wordStart(String lower, String q) {
        int first = -1;
        int from = 0;
        while (true) {
            int at = lower.indexOf(q, from);
            if (at < 0) {
                return first;
            }
            if (at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1))) {
                return at;
            }
            if (first < 0) {
                first = at;
            }
            from = at + 1;
        }
    }

    private static void bold(SpannableString text, int start, int end) {
        text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
