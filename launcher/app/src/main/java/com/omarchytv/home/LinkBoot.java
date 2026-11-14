package com.omarchytv.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

/**
 * Starting {@link OmarchyLink.KeepAlive} as a foreground service, at boot and from the launcher. Every call
 * here is wrapped so a refused start can never throw out of the boot receiver or the activity (a crash at
 * boot would count towards {@link Watchdog}). No wakelock, no timer, no network.
 */
final class LinkBoot {
    private static final String TAG = "OmarchyLink";
    static final String CHANNEL = "omarchy_link";
    static final int NOTIFICATION_ID = 47800;

    private LinkBoot() {
    }

    /** BOOT_COMPLETED: start Link when it is enabled, whatever the default home is. */
    static void startAtBoot(Context c) {
        try {
            if (!LauncherConfig.get(c).linkEnabled) return;
        } catch (RuntimeException e) {
            Log.w(TAG, "boot: config unreadable, Link not started", e);
            return;
        }
        startService(c);
    }

    /** startForegroundService on API 26+, startService below. Never throws. */
    static void startService(Context c) {
        try {
            Intent i = new Intent(c, OmarchyLink.KeepAlive.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {   // start refused (background limits, SecurityException): the in-process server still runs
            Log.w(TAG, "foreground start refused", e);
        }
    }

    /** Calls startForeground with the low-key notification. Returns false when the system refused. */
    static boolean promote(Service s) {
        try {
            Notification n = notification(s);
            if (Build.VERSION.SDK_INT >= 29) {
                s.startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                s.startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground refused", e);
            return false;
        }
    }

    private static Notification notification(Context c) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        c.getString(R.string.link_notification_channel), NotificationManager.IMPORTANCE_MIN));
            }
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_MIN);
        }
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(c.getString(R.string.link_notification))
                .setOngoing(true)
                .build();
    }
}
