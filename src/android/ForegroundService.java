/*
    Copyright 2013-2017 appPlant GmbH

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */

package de.appplant.cordova.plugin.background;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import static android.os.PowerManager.PARTIAL_WAKE_LOCK;

/**
 * Puts the service in a foreground state, where the system considers it to be
 * something the user is actively aware of and thus not a candidate for killing
 * when low on memory.
 */
public class ForegroundService extends Service {

    // Fixed ID for the 'foreground' notification
    public static final int NOTIFICATION_ID = -574543954;

    // Default title of the background notification
    private static final String NOTIFICATION_TITLE =
            "App is running in background";

    // Default text of the background notification
    private static final String NOTIFICATION_TEXT =
            "Doing heavy tasks.";

    // Default icon of the background notification
    private static final String NOTIFICATION_ICON = "icon";

    // Notification channel
    private static final String NOTIFICATION_CHANNEL_ID =
            "cordova-plugin-background-mode-channel";
    private static final String NOTIFICATION_CHANNEL_NAME =
            "Background notification";

    // Binder given to clients
    private final IBinder mBinder = new ForegroundBinder();

    // Partial wake lock to prevent the app from going to sleep when locked
    private PowerManager.WakeLock wakeLock;
    private Notification.Builder notification;

    /**
     * Allow clients to call on to the service.
     */
    @Override
    public IBinder onBind (Intent intent) {
        return mBinder;
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class ForegroundBinder extends Binder {
        ForegroundService getService() {
            // Return this instance of ForegroundService
            // so clients can call public methods
            return ForegroundService.this;
        }
    }

    /**
     * Put the service in a foreground state to prevent app from being killed
     * by the OS.
     */
    @Override
    public void onCreate () {
        super.onCreate();
        keepAwake();
    }

    /**
     * No need to run headless on destroy.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        sleepWell();
    }

    /**
     * Prevent Android from stopping the background service automatically
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    /**
     * Put the service in a foreground state to prevent app from being killed
     * by the OS.
     */
    private void keepAwake() {
        JSONObject settings = BackgroundMode.getSettings();
        boolean isSilent    = settings.optBoolean("silent", false);

        if (!isSilent) {
            startForeground(NOTIFICATION_ID, makeNotification());
        }

        PowerManager pm = (PowerManager)
                getSystemService(POWER_SERVICE);

        wakeLock = pm.newWakeLock(
                PARTIAL_WAKE_LOCK, "BackgroundMode");

        wakeLock.acquire();
    }

    /**
     * Stop background mode.
     */
    private void sleepWell() {
        stopForeground(true);
        getNotificationManager().cancel(NOTIFICATION_ID);

        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    /**
     * Create a notification as the visible part to be able to put the service
     * in a foreground state by using the default settings.
     */
    private Notification makeNotification() {
        return makeNotification(BackgroundMode.getSettings());
    }

    /**
     * Create a notification as the visible part to be able to put the service
     * in a foreground state.
     *
     * @param settings The config settings
     */
    private Notification makeNotification(JSONObject settings) {
        return makeNotification(settings, false);
    }

    /**
     * Create a notification as the visible part to be able to put the service
     * in a foreground state.
     *
     * @param settings The config settings
     * @param isUpdate Whether the notifaction is being updated or not
     */
    private Notification makeNotification(JSONObject settings, boolean isUpdate) {
        // Android 8.0 notification fix.
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                getNotificationManager().IMPORTANCE_HIGH
            );

            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            getNotificationManager().createNotificationChannel(notificationChannel);
        }

        String title    = settings.optString("title", NOTIFICATION_TITLE);
        String text     = settings.optString("text", NOTIFICATION_TEXT);
        boolean bigText = settings.optBoolean("bigText", false);

        Context context = getApplicationContext();
        String pkgName  = context.getPackageName();
        Intent intent   = context.getPackageManager()
                .getLaunchIntentForPackage(pkgName);

        if (!isUpdate || notification == null) {
            notification = new Notification.Builder(context).setOngoing(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.setChannelId(NOTIFICATION_CHANNEL_ID)
                        .setAutoCancel(true);
        }

        notification.setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setSmallIcon(getIconResId(settings));

        if (settings.optBoolean("hidden", true)) {
            notification.setPriority(Notification.PRIORITY_MIN);
        }

        if (bigText || text.contains("\n")) {
            notification.setStyle(
                    new Notification.BigTextStyle().bigText(text));
        }

        setColor(notification, settings);

        if (intent != null && settings.optBoolean("resume")) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    context, NOTIFICATION_ID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);


            notification.setContentIntent(contentIntent);
        }

        return notification.build();
    }

    /**
     * Update the notification.
     *
     * @param settings The config settings
     */
    protected void updateNotification(JSONObject settings) {
        boolean isSilent = settings.optBoolean("silent", false);

        if (isSilent) {
            stopForeground(true);
            return;
        }

        Notification notification = makeNotification(settings, true);
        getNotificationManager().notify(NOTIFICATION_ID, notification);
    }

    /**
     * Retrieves the resource ID of the app icon.
     *
     * @param settings A JSON dict containing the icon name.
     */
    private int getIconResId(JSONObject settings) {
        String icon = settings.optString("icon", NOTIFICATION_ICON);

        // cordova-android 6 uses mipmaps
        int resId = getIconResId(icon, "mipmap");

        if (resId == 0) {
            resId = getIconResId(icon, "drawable");
        }

        return resId;
    }

    /**
     * Retrieve resource id of the specified icon.
     *
     * @param icon The name of the icon.
     * @param type The resource type where to look for.
     *
     * @return The resource id or 0 if not found.
     */
    private int getIconResId(String icon, String type) {
        Resources res  = getResources();
        String pkgName = getPackageName();

        int resId = res.getIdentifier(icon, type, pkgName);

        if (resId == 0) {
            resId = res.getIdentifier("icon", type, pkgName);
        }

        return resId;
    }

    /**
     * Set notification color if its supported by the SDK.
     *
     * @param notification A Notification.Builder instance
     * @param settings A JSON dict containing the color definition (red: FF0000)
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setColor(Notification.Builder notification,
                          JSONObject settings) {

        String hex = settings.optString("color", null);

        if (Build.VERSION.SDK_INT < 21 || hex == null)
            return;

        try {
            int aRGB = Integer.parseInt(hex, 16) + 0xFF000000;
            notification.setColor(aRGB);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Shared manager for the notification service.
     */
    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }
}
