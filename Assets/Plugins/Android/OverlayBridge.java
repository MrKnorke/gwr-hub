package com.gwrhub.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * Statische Einstiegspunkte, die aus Unity (C#) per AndroidJavaClass aufgerufen werden.
 */
public final class OverlayBridge {

    private OverlayBridge() {}

    public static boolean canDrawOverlays(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** Öffnet die System-Einstellung "Über anderen Apps einblenden" für diese App. */
    public static void requestOverlayPermission(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    public static boolean startOverlay(Activity activity) {
        if (!canDrawOverlays(activity)) return false;
        Intent intent = new Intent(activity, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            activity.startForegroundService(intent);
        } else {
            activity.startService(intent);
        }
        return true;
    }

    public static void stopOverlay(Activity activity) {
        activity.stopService(new Intent(activity, OverlayService.class));
    }

    public static boolean isOverlayRunning() {
        return OverlayService.isRunning;
    }

    /** Schickt den Hub in den Hintergrund, damit man direkt ins Spiel wechseln kann. */
    public static void moveToBack(Activity activity) {
        activity.moveTaskToBack(true);
    }

    public static int sdkInt() {
        return Build.VERSION.SDK_INT;
    }
}
