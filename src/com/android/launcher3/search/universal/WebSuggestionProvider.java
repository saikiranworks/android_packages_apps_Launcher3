package com.android.launcher3.search.universal;

import android.content.Context;
import android.net.Uri;
import android.os.Process;

import com.android.launcher3.LauncherPrefs;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Off by default: every keystroke past the debounce is sent to the search engine. */
public class WebSuggestionProvider implements SearchProvider {

    private static final int TIMEOUT_MS = 1500;

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_WEB_SUGGESTION;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_WEB_SUGGESTIONS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        String trimmed = query.trim();
        if (trimmed.length() < 2) {
            return out;
        }
        List<String> suggestions = fetch(endpoint(LauncherPrefs.SEARCH_ENGINE.get(context))
                + Uri.encode(trimmed));
        int score = 20;
        for (String suggestion : suggestions) {
            if (out.size() >= max) {
                break;
            }
            if (suggestion.equalsIgnoreCase(trimmed)) {
                continue;
            }
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_WEB_SUGGESTION, suggestion, suggestion, null,
                    ActionProvider.webSearch(context, suggestion), Process.myUserHandle(),
                    score--);
            ActionProvider.applyEngineIcon(context, result);
            out.add(result);
        }
        return out;
    }

    private static String endpoint(String engine) {
        switch (engine) {
            case "google":
                return "https://suggestqueries.google.com/complete/search?client=firefox&q=";
            case "bing":
                return "https://api.bing.com/osjson.aspx?query=";
            case "brave":
                return "https://search.brave.com/api/suggest?q=";
            default:
                return "https://duckduckgo.com/ac/?type=list&q=";
        }
    }

    /** Every endpoint above answers in the OpenSearch format: ["query", ["s1", "s2", ...]]. */
    private static List<String> fetch(String url) {
        List<String> out = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return out;
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JSONArray list = new JSONArray(body).getJSONArray(1);
            for (int i = 0; i < list.length(); i++) {
                out.add(list.getString(i));
            }
        } catch (IOException | JSONException | RuntimeException e) {
            return out;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return out;
    }
}
