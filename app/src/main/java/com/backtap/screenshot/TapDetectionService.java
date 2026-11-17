package com.backtap.screenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.ByteBuffer;

public class TapDetectionService extends Service implements SensorEventListener {
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";

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
    private boolean gravityInitialized;
    private long lastPeakAt;
    private long firstTapAt;
    private long lastScreenshotAt;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private volatile Bitmap latestFrame;
    private final Object frameLock = new Object();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startAsForeground();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) throw new IllegalStateException("MediaProjection ვერ შეიქმნა");
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    Log.i(TAG, "Screen capture session stopped by Android/user");
                    stopSelf();
                }
            }, new Handler(getMainLooper()));
            startScreenCapture();
            startTapSensor();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, true).apply();
        } catch (Exception exception) {
            Log.e(TAG, "Unable to start screen capture", exception);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, false).apply();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startScreenCapture() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.Display display = getDisplay();
            if (display == null) display = windowManager.getDefaultDisplay();
            display.getRealMetrics(metrics);
        } else {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        }
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        imageThread = new HandlerThread("BackTapImageReader");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int pixelStride = plane.getPixelStride();
                int rowStride = plane.getRowStride();
                int rowPadding = rowStride - pixelStride * screenWidth;
                Bitmap padded = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride,
                        screenHeight, Bitmap.Config.ARGB_8888);
                buffer.rewind();
                padded.copyPixelsFromBuffer(buffer);
                Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight);
                if (cropped != padded) padded.recycle();
                synchronized (frameLock) {
                    if (latestFrame != null) latestFrame.recycle();
                    latestFrame = cropped;
                }
            } catch (Exception exception) {
                Log.e(TAG, "Could not read screen frame", exception);
            } finally {
                if (image != null) image.close();
            }
        }, imageHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "BackTapScreenshotDisplay", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, imageHandler);
    }

    private void startTapSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) throw new IllegalStateException("სენსორის მენეჯერი მიუწვდომელია");
        activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        useLinearAcceleration = activeSensor != null;
        if (activeSensor == null) activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (activeSensor == null || !sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_GAME)) {
            throw new IllegalStateException("აქსელერომეტრი მიუწვდომელია");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values == null || event.values.length < 3) return;
        float x, y, z;
        if (useLinearAcceleration) {
            x = event.values[0]; y = event.values[1]; z = event.values[2];
        } else {
            final float alpha = 0.82f;
            if (!gravityInitialized) {
                gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2];
                gravityInitialized = true;
                return;
            }
            gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0];
            gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1];
            gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2];
            x = event.values[0] - gravity[0]; y = event.values[1] - gravity[1]; z = event.values[2] - gravity[2];
        }
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        float threshold = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(PREF_THRESHOLD, 3.5f);
        long now = SystemClock.elapsedRealtime();
        if (magnitude < threshold || now - lastPeakAt < 120L) return;
        lastPeakAt = now;
        if (firstTapAt > 0L && now - firstTapAt >= 100L && now - firstTapAt <= 650L) {
            firstTapAt = 0L;
            if (now - lastScreenshotAt > 1100L) {
                lastScreenshotAt = now;
                saveLatestScreenshot();
            }
        } else {
            firstTapAt = now;
        }
    }

    private void saveLatestScreenshot() {
        Bitmap frame;
        synchronized (frameLock) {
            if (latestFrame == null || latestFrame.isRecycled()) {
                Toast.makeText(this, "ეკრანის კადრი ჯერ მზად არ არის — სცადე ხელახლა", Toast.LENGTH_SHORT).show();
                return;
            }
            frame = latestFrame.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (frame == null) return;
        String fileName = "BackTap_" + System.currentTimeMillis() + ".png";
        Uri imageUri = null;
        OutputStream output = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BackTapScreenshot");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (imageUri == null) throw new IllegalStateException("ფაილის შექმნა ვერ მოხერხდა");
            output = getContentResolver().openOutputStream(imageUri);
            if (output == null) throw new IllegalStateException("ფაილის ჩაწერა ვერ მოხერხდა");
            frame.compress(Bitmap.CompressFormat.PNG, 100, output);
            output.flush();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(imageUri, done, null, null);
            }
            Log.i(TAG, "Screenshot saved: " + imageUri);
        } catch (Exception exception) {
            Log.e(TAG, "Screenshot save failed", exception);
            if (imageUri != null) getContentResolver().delete(imageUri, null, null);
        } finally {
            try { if (output != null) output.close(); } catch (Exception ignored) { }
            frame.recycle();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when back-tap screenshot capture is active.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(pendingIntent).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE).build();
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
        if (imageThread != null) imageThread.quitSafely();
        synchronized (frameLock) {
            if (latestFrame != null) { latestFrame.recycle(); latestFrame = null; }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
