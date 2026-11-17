package com.backtap.screenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

public class TapDetectionService extends Service implements SensorEventListener {
    private static final String TAG = "BackTapScreenshot";
    private static final String CHANNEL_ID = "back_tap_channel";
    private static final int NOTIFICATION_ID = 41;
    private static final String PREFS = "back_tap_preferences";
    private static final String PREF_RUNNING = "listener_running";
    private static final String PREF_THRESHOLD = "threshold";

    private SensorManager sensorManager;
    private Sensor activeSensor;
    private boolean useLinearAcceleration;
    private final float[] gravity = new float[3];
    private boolean gravityInitialized = false;
    private long lastPeakAt = 0L;
    private long firstTapAt = 0L;
    private long lastScreenshotAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            stopBecauseNoSensor();
            return;
        }

        activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        useLinearAcceleration = activeSensor != null;
        if (activeSensor == null) {
            activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            useLinearAcceleration = false;
        }

        if (activeSensor == null || !sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_GAME)) {
            stopBecauseNoSensor();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, true).apply();
    }

    private void stopBecauseNoSensor() {
        Log.w(TAG, "No suitable accelerometer found or sensor registration failed");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values == null || event.values.length < 3) return;

        float x;
        float y;
        float z;
        if (useLinearAcceleration) {
            x = event.values[0];
            y = event.values[1];
            z = event.values[2];
        } else {
            // Estimate gravity and subtract it, leaving short sharp movements such as taps.
            final float alpha = 0.82f;
            if (!gravityInitialized) {
                gravity[0] = event.values[0];
                gravity[1] = event.values[1];
                gravity[2] = event.values[2];
                gravityInitialized = true;
                return;
            }
            gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0];
            gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1];
            gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2];
            x = event.values[0] - gravity[0];
            y = event.values[1] - gravity[1];
            z = event.values[2] - gravity[2];
        }

        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        float threshold = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(PREF_THRESHOLD, 3.5f);
        long now = SystemClock.elapsedRealtime();

        // One physical tap can produce several sensor peaks. Ignore peaks closer than 120 ms.
        if (magnitude < threshold || now - lastPeakAt < 120L) return;
        lastPeakAt = now;

        if (firstTapAt > 0L && now - firstTapAt >= 100L && now - firstTapAt <= 650L) {
            firstTapAt = 0L;
            if (now - lastScreenshotAt > 1100L) {
                lastScreenshotAt = now;
                boolean success = ScreenshotAccessibilityService.takeScreenshotNow();
                Log.i(TAG, "Double tap detected; screenshot action accepted=" + success);
            }
        } else {
            firstTapAt = now;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No calibration UI needed.
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when back-tap screenshot detection is active.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
