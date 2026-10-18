package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only the ECB rate table is downloaded; the query never leaves the device. */
final class CurrencyRates {

    private static final String FEED = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    private static final Pattern CUBE =
            Pattern.compile("currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]");
    private static final String PREFS = "universal_search_rates";
    private static final long MAX_AGE_MS = TimeUnit.HOURS.toMillis(12);
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int TIMEOUT_MS = 2500;

    private static Map<String, Double> sRates;
    private static long sFetchedAt;
    private static long sLastAttempt;

    private CurrencyRates() {}

    static synchronized long fetchedAt() {
        return sRates == null ? 0 : sFetchedAt;
    }

    /** Units per euro, keyed by lower-case ISO code, or null when unavailable. */
    static synchronized Map<String, Double> get(Context context) {
        long now = System.currentTimeMillis();
        if (sRates == null) {
            load(context);
        }
        if ((sRates == null || now - sFetchedAt > MAX_AGE_MS) && now - sLastAttempt > RETRY_MS) {
            sLastAttempt = now;
            Map<String, Double> fresh = fetch();
            if (fresh != null) {
                sRates = fresh;
                sFetchedAt = now;
                save(context);
            }
        }
        return sRates;
    }

    private static Map<String, Double> fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(FEED).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, Double> rates = new HashMap<>();
            rates.put("eur", 1.0);
            Matcher m = CUBE.matcher(body);
            while (m.find()) {
                rates.put(m.group(1).toLowerCase(), Double.parseDouble(m.group(2)));
            }
            return rates.size() > 1 ? rates : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void load(Context context) {
        SharedPreferences prefs = prefs(context);
        String json = prefs.getString("rates", null);
        if (json == null) {
            return;
        }
        try {
            JSONObject object = new JSONObject(json);
            Map<String, Double> rates = new HashMap<>();
            for (Iterator<String> it = object.keys(); it.hasNext(); ) {
                String key = it.next();
                rates.put(key, object.getDouble(key));
            }
            sRates = rates;
            sFetchedAt = prefs.getLong("fetched_at", 0);
        } catch (JSONException e) {
            sRates = null;
        }
    }

    private static void save(Context context) {
        prefs(context).edit()
                .putString("rates", new JSONObject(sRates).toString())
                .putLong("fetched_at", sFetchedAt)
                .apply();
    }
}
