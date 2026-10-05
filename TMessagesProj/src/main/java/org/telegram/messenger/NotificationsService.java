/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

/**
 * Keeps the connection to Telegram alive while the app is in the background.
 *
 * This has to be a foreground service. A plain background Service is killed
 * within minutes on Android 8+, and startService() from the background throws
 * outright — which is what used to happen here: the throw was swallowed by the
 * caller's catch, the service never really ran, and users only received messages
 * when they opened the app. That was the single most common complaint, including
 * a public Play review.
 *
 * A fork cannot use push instead. Telegram's servers deliver push through their
 * own Firebase project and can only reach tokens issued for it; ours belong to
 * a different project, so no push from Telegram can ever arrive. Holding the
 * connection open is the only delivery mechanism available to us.
 *
 * The notification the OS requires is posted on an IMPORTANCE_MIN channel, which
 * keeps it out of the status bar — it sits silently at the bottom of the shade
 * instead. Android does not allow hiding it entirely, by design: a foreground
 * service is meant to be discoverable.
 */
public class NotificationsService extends Service {

    private static final String CHANNEL_ID = "askan_background_connection";
    private static final int NOTIFICATION_ID = 38291;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSilently();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Also called here: when the system restarts a START_STICKY service it
        // delivers onStartCommand, and Android requires startForeground within a
        // few seconds of the start or it kills the process with a ForegroundServiceDidNotStartInTimeException.
        startForegroundSilently();
        return START_STICKY;
    }

    private void startForegroundSilently() {
        try {
            createChannel();

            Intent open = new Intent(this, LaunchActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(LocaleController.getString(R.string.AppName))
                    .setContentText("מחובר — כדי לקבל הודעות גם כשהאפליקציה סגורה")
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setSilent(true)
                    .setContentIntent(contentIntent)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ refuses startForeground without the type the manifest declares.
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            // Never let this kill the process: a failure here costs background
            // delivery, which is no worse than the behaviour it replaces.
            FileLog.e("NotificationsService: startForeground failed", e);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;

        // IMPORTANCE_MIN is what keeps the icon out of the status bar. Anything
        // higher and the user carries a permanent icon around, which is the part
        // people actually object to.
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "חיבור ברקע", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("מאפשר קבלת הודעות כשהאפליקציה סגורה. אינו מציג התראות.");
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
