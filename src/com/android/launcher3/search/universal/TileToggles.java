package com.android.launcher3.search.universal;

import android.app.NotificationManager;
import android.app.UiModeManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.ColorDisplayManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkPolicyManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import com.android.launcher3.util.Executors;

/**
 * Flips quick settings tiles in place. The launcher is platform signed, so it holds the same
 * permissions SystemUI uses for these; anything that still refuses falls back to the tile's
 * settings page.
 */
final class TileToggles {

    private static volatile String sTorchId;
    private static volatile boolean sTorchOn;
    private static boolean sTorchRegistered;

    private TileToggles() {}

    /** Returns the tile's current state, or null when it cannot be toggled from here. */
    static Boolean state(Context context, String spec) {
        try {
            switch (spec) {
                case "internet":
                case "wifi":
                    return context.getSystemService(WifiManager.class).isWifiEnabled();
                case "bt": {
                    BluetoothAdapter adapter = bluetooth(context);
                    return adapter == null ? null : adapter.isEnabled();
                }
                case "cell":
                    return context.getSystemService(TelephonyManager.class)
                            .isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER);
                case "flashlight":
                    return registerTorch(context) ? sTorchOn : null;
                case "dnd":
                    return context.getSystemService(NotificationManager.class).getZenMode()
                            != Settings.Global.ZEN_MODE_OFF;
                case "rotation":
                    return Settings.System.getInt(context.getContentResolver(),
                            Settings.System.ACCELEROMETER_ROTATION, 0) != 0;
                case "battery":
                    return context.getSystemService(PowerManager.class).isPowerSaveMode();
                case "location":
                    return context.getSystemService(LocationManager.class).isLocationEnabled();
                case "airplane":
                    return Settings.Global.getInt(context.getContentResolver(),
                            Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
                case "night":
                    return context.getSystemService(ColorDisplayManager.class)
                            .isNightDisplayActivated();
                case "reduce_brightness":
                    return context.getSystemService(ColorDisplayManager.class)
                            .isReduceBrightColorsActivated();
                case "dark":
                    return (context.getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK)
                            == Configuration.UI_MODE_NIGHT_YES;
                case "saver":
                    return NetworkPolicyManager.from(context).getRestrictBackground();
                case "colorinversion":
                    return secureFlag(context,
                            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
                case "color_correction":
                    return secureFlag(context,
                            Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED);
                case "heads_up":
                    return Settings.Global.getInt(context.getContentResolver(),
                            Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) != 0;
                case "sync":
                    return ContentResolver.getMasterSyncAutomatically();
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean set(Context context, String spec, boolean on) {
        try {
            switch (spec) {
                case "internet":
                case "wifi":
                    return context.getSystemService(WifiManager.class).setWifiEnabled(on);
                case "bt": {
                    BluetoothAdapter adapter = bluetooth(context);
                    return adapter != null && (on ? adapter.enable() : adapter.disable());
                }
                case "cell":
                    context.getSystemService(TelephonyManager.class).setDataEnabledForReason(
                            TelephonyManager.DATA_ENABLED_REASON_USER, on);
                    return true;
                case "flashlight":
                    if (sTorchId == null) {
                        return false;
                    }
                    context.getSystemService(CameraManager.class).setTorchMode(sTorchId, on);
                    sTorchOn = on;
                    return true;
                case "dnd":
                    context.getSystemService(NotificationManager.class).setZenMode(
                            on ? Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                                    : Settings.Global.ZEN_MODE_OFF,
                            null, "launcher search");
                    return true;
                case "rotation":
                    return Settings.System.putInt(context.getContentResolver(),
                            Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
                case "battery":
                    return context.getSystemService(PowerManager.class)
                            .setPowerSaveModeEnabled(on);
                case "location":
                    context.getSystemService(LocationManager.class)
                            .setLocationEnabledForUser(on, Process.myUserHandle());
                    return true;
                case "airplane":
                    context.getSystemService(ConnectivityManager.class).setAirplaneMode(on);
                    return true;
                case "night":
                    return context.getSystemService(ColorDisplayManager.class)
                            .setNightDisplayActivated(on);
                case "reduce_brightness":
                    return context.getSystemService(ColorDisplayManager.class)
                            .setReduceBrightColorsActivated(on);
                case "dark":
                    return context.getSystemService(UiModeManager.class)
                            .setNightModeActivated(on);
                case "saver":
                    NetworkPolicyManager.from(context).setRestrictBackground(on);
                    return true;
                case "colorinversion":
                    return Settings.Secure.putInt(context.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, on ? 1 : 0);
                case "color_correction":
                    return Settings.Secure.putInt(context.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, on ? 1 : 0);
                case "heads_up":
                    return Settings.Global.putInt(context.getContentResolver(),
                            Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, on ? 1 : 0);
                case "sync":
                    ContentResolver.setMasterSyncAutomatically(on);
                    return true;
                default:
                    return false;
            }
        } catch (RuntimeException | CameraAccessException e) {
            return false;
        }
    }

    private static boolean secureFlag(Context context, String key) {
        return Settings.Secure.getInt(context.getContentResolver(), key, 0) != 0;
    }

    private static BluetoothAdapter bluetooth(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    /**
     * The torch state is only reported through a callback, so keep one registered for the life
     * of the process. Returns false on devices without a usable flash.
     */
    private static synchronized boolean registerTorch(Context context) {
        if (sTorchRegistered) {
            return sTorchId != null;
        }
        sTorchRegistered = true;
        CameraManager camera = context.getApplicationContext()
                .getSystemService(CameraManager.class);
        try {
            for (String id : camera.getCameraIdList()) {
                CameraCharacteristics c = camera.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    sTorchId = id;
                    break;
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            return false;
        }
        if (sTorchId == null) {
            return false;
        }
        camera.registerTorchCallback(Executors.UI_HELPER_EXECUTOR,
                new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        if (cameraId.equals(sTorchId)) {
                            sTorchOn = enabled;
                        }
                    }
                });
        return true;
    }
}
