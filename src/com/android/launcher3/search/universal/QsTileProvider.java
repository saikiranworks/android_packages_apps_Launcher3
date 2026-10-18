package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.launcher3.LauncherPrefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QsTileProvider implements SearchProvider {

    private static final String TILES_SETTING = "sysui_qs_tiles";

    private static final Map<String, String[]> TILES = new LinkedHashMap<>();

    static {
        TILES.put("internet", new String[]{"Internet", Settings.ACTION_WIFI_SETTINGS});
        TILES.put("wifi", new String[]{"Wi-Fi", Settings.ACTION_WIFI_SETTINGS});
        TILES.put("cell", new String[]{"Mobile data", Settings.ACTION_DATA_USAGE_SETTINGS});
        TILES.put("bt", new String[]{"Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS});
        TILES.put("flashlight", new String[]{"Flashlight", null});
        TILES.put("dnd", new String[]{"Do Not Disturb", Settings.ACTION_ZEN_MODE_SETTINGS});
        TILES.put("rotation", new String[]{"Auto-rotate", Settings.ACTION_DISPLAY_SETTINGS});
        TILES.put("battery", new String[]{"Battery Saver",
                Settings.ACTION_BATTERY_SAVER_SETTINGS});
        TILES.put("location", new String[]{"Location",
                Settings.ACTION_LOCATION_SOURCE_SETTINGS});
        TILES.put("airplane", new String[]{"Airplane mode",
                Settings.ACTION_AIRPLANE_MODE_SETTINGS});
        TILES.put("night", new String[]{"Night Light",
                Settings.ACTION_NIGHT_DISPLAY_SETTINGS});
        TILES.put("dark", new String[]{"Dark theme", Settings.ACTION_DISPLAY_SETTINGS});
        TILES.put("nfc", new String[]{"NFC", Settings.ACTION_NFC_SETTINGS});
        TILES.put("cast", new String[]{"Screen cast", Settings.ACTION_CAST_SETTINGS});
        TILES.put("hotspot", new String[]{"Hotspot", Settings.ACTION_WIRELESS_SETTINGS});
        TILES.put("saver", new String[]{"Data Saver", Settings.ACTION_DATA_USAGE_SETTINGS});
        TILES.put("screenrecord", new String[]{"Screen record", null});
        TILES.put("onehanded", new String[]{"One-handed mode", Settings.ACTION_SETTINGS});
        TILES.put("colorinversion", new String[]{"Colour inversion",
                Settings.ACTION_ACCESSIBILITY_SETTINGS});
        TILES.put("color_correction", new String[]{"Colour correction",
                Settings.ACTION_ACCESSIBILITY_SETTINGS});
        TILES.put("reduce_brightness", new String[]{"Extra dim",
                Settings.ACTION_ACCESSIBILITY_SETTINGS});
        TILES.put("controls", new String[]{"Device controls", null});
        TILES.put("wallet", new String[]{"Wallet", null});
        TILES.put("qr_code_scanner", new String[]{"QR scanner", null});
        TILES.put("alarm", new String[]{"Alarm", null});
        TILES.put("caffeine", new String[]{"Caffeine", null});
        TILES.put("heads_up", new String[]{"Heads-up notifications", null});
        TILES.put("sync", new String[]{"Sync", Settings.ACTION_SYNC_SETTINGS});
        TILES.put("sound", new String[]{"Sound mode", Settings.ACTION_SOUND_SETTINGS});
    }

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_QS_TILE;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_QS_TILES.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.isEmpty()) {
            return out;
        }
        String specs = Settings.Secure.getString(
                context.getContentResolver(), TILES_SETTING);
        String lower = query.toLowerCase();
        for (String spec : activeSpecs(specs)) {
            if (out.size() >= max) {
                break;
            }
            String[] tile = TILES.get(spec);
            int score = tile == null ? 0 : FuzzyMatcher.score(lower, tile[0]);
            if (score == 0) {
                continue;
            }
            Intent intent = TextUtils.isEmpty(tile[1]) ? null
                    : new Intent(tile[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Boolean state = TileToggles.state(context, spec);
            if (intent == null && state == null) {
                continue;
            }
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_QS_TILE,
                    spec,
                    tile[0],
                    context.getString(com.android.launcher3.R.string.search_section_qs_tiles),
                    intent,
                    Process.myUserHandle(),
                    score);
            if (state != null) {
                result.checked = state;
                result.toggle = (c, on) -> TileToggles.set(c, spec, on);
            }
            out.add(result);
        }
        return out;
    }

    private static List<String> activeSpecs(String specs) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(specs)) {
            out.addAll(TILES.keySet());
            return out;
        }
        for (String spec : specs.split(",")) {
            String trimmed = spec.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
