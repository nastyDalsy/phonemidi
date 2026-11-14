package com.dalsy.phonemidi;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service that keeps PhoneMIDI playing while the screen is off or another app
 * is open: keeps the process and CPU alive and tells Android we use Bluetooth, the
 * microphone and the camera in the background. Stopped from its notification.
 */
public class PlayService extends Service {
    public static final String ACTION_STOP = "com.dalsy.phonemidi.STOP";
    private static final String CHANNEL = "play";
    private static final int ID = 1;

    private PowerManager.WakeLock wake;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Tocando en segundo plano",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneMIDI:play");
            wake.setReferenceCounted(false);
            wake.acquire();
        }
    }

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, PlayService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("PhoneMIDI sigue tocando")
                .setContentText("Funciona con la pantalla apagada y en otras apps")
                .setContentIntent(pOpen)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Detener", pStop).build())
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("phonemidi", Context.MODE_PRIVATE).edit().putBoolean("bg", false).apply();
            Core.get(this).stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = 0;
            if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                if (granted(Manifest.permission.RECORD_AUDIO)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                if (granted(Manifest.permission.CAMERA)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            try {
                startForeground(ID, n, type);
            } catch (Exception e) {
                // e.g. not allowed to use the camera/mic from here: keep at least MIDI going
                try {
                    startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } catch (Exception e2) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
        } else {
            startForeground(ID, n);
        }
        Core.get(this).start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
