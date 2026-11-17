package com.backtap.screenshot;

import android.app.Activity;
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
    public static final String ACTION_TEST_SCREENSHOT = "com.backtap.screenshot.TEST_SCREENSHOT";
    public static final String PREF_RUNNING = "listener_running";
    public static final String PREF_ERROR = "last_error";
    public static final String PREF_SENSOR = "sensor_status";
    public static final String PREF_FRAME_READY = "frame_ready";
    public static final String PREF_TAP_COUNT = "tap_peak_count";
    public static final String PREF_DOUBLE_COUNT = "double_tap_count";

    private static final String TAG = "BackTapScreenshot";
    private static final String CHANNEL_ID = "back_tap_channel";
    private static final int NOTIFICATION_ID = 41;
    private static final String PREFS = "back_tap_preferences";
    private static final String PREF_THRESHOLD = "threshold";

    private SensorManager sensorManager;
    private Sensor activeSensor;
    private boolean useLinearAcceleration;
    private final float[] gravity = new float[3];
    private boolean gravityInitialized;
    private boolean thresholdArmed = true;
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
    private Bitmap latestFrame;
    private final Object frameLock = new Object();
    private final Handler mainHandler = new Handler();
    private boolean started;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TEST_SCREENSHOT.equals(intent.getAction())) {
            if (!started) {
                Toast.makeText(this, "ჯერ ჩართე ეკრანის გადაღების ნებართვა", Toast.LENGTH_SHORT).show();
                stopSelf(startId);
            } else {
                captureTestWithRetry(0);
            }
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            setFailure("MediaProjection-ის ნებართვის მონაცემები ვერ მოიძებნა");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        try {
            startAsForeground();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = getProjectionIntent(intent);
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                throw new IllegalStateException("Android-ის ეკრანის ნებართვა არასწორია");
            }

            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("MediaProjectionManager მიუწვდომელია");
            mediaProjection = manager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) throw new IllegalStateException("MediaProjection ვერ შეიქმნა");

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    Log.i(TAG, "MediaProjection stopped by Android or user");
                    if (started) {
                        setFailure("ეკრანის გაზიარების სესია შეწყდა. ხელახლა დააჭირე ნებართვის ღილაკს.");
                        stopSelf();
                    }
                }
            }, mainHandler);

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_FRAME_READY, false)
                    .putInt(PREF_TAP_COUNT, 0)
                    .putInt(PREF_DOUBLE_COUNT, 0)
                    .apply();
            startScreenCapture();
            startTapSensor();
            started = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_RUNNING, true)
                    .putString(PREF_ERROR, "")
                    .apply();
            Log.i(TAG, "Back tap listener started successfully");
        } catch (Exception exception) {
            Log.e(TAG, "Unable to start capture service", exception);
            setFailure(exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private Intent getProjectionIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        //noinspection deprecation
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "უცნობი შეცდომა" : exception.getMessage();
    }

    private void setFailure(String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_RUNNING, false)
                .putString(PREF_ERROR, message)
                .apply();
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
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) throw new IllegalStateException("WindowManager მიუწვდომელია");
        DisplayMetrics metrics = new DisplayMetrics();
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
        if (screenWidth <= 0 || screenHeight <= 0 || screenDensity <= 0) {
            throw new IllegalStateException("ეკრანის ზომის ამოცნობა ვერ მოხერხდა");
        }

        imageThread = new HandlerThread("BackTapImageReader");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            Bitmap padded = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int pixelStride = plane.getPixelStride();
                int rowStride = plane.getRowStride();
                int paddedWidth = rowStride / pixelStride;
                padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888);
                buffer.rewind();
                padded.copyPixelsFromBuffer(buffer);
                Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight);
                if (cropped == padded) padded = null;
                synchronized (frameLock) {
                    if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
                    latestFrame = cropped;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_FRAME_READY, true).apply();
            } catch (Exception exception) {
                Log.e(TAG, "Could not read screen frame", exception);
            } finally {
                if (image != null) image.close();
                if (padded != null && !padded.isRecycled()) padded.recycle();
            }
        }, imageHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "BackTapScreenshotDisplay", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, imageHandler);
        if (virtualDisplay == null) throw new IllegalStateException("ვირტუალური ეკრანის შექმნა ვერ მოხერხდა");
    }

    private void startTapSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) throw new IllegalStateException("სენსორის მენეჯერი მიუწვდომელია");
        activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        useLinearAcceleration = activeSensor != null;
        if (activeSensor == null) activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (activeSensor == null) throw new IllegalStateException("ამ ტელეფონზე აქსელერომეტრი ვერ მოიძებნა");
        boolean registered = sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_GAME);
        if (!registered) throw new IllegalStateException("აქსელერომეტრის ჩართვა ვერ მოხერხდა");
        String sensorName = activeSensor.getName() == null ? "აქსელერომეტრი" : activeSensor.getName();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_SENSOR, "სენსორი: " + sensorName).apply();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values == null || event.values.length < 3) return;
        float x, y, z;
        if (useLinearAcceleration) {
            x = event.values[0]; y = event.values[1]; z = event.values[2];
        } else {
            final float alpha = 0.80f;
            if (!gravityInitialized) {
                gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2];
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
        float threshold = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(PREF_THRESHOLD, 2.2f);
        long now = SystemClock.elapsedRealtime();

        if (magnitude < threshold * 0.55f) thresholdArmed = true;
        if (!thresholdArmed || magnitude < threshold || now - lastPeakAt < 160L) return;
        thresholdArmed = false;
        lastPeakAt = now;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putInt(PREF_TAP_COUNT, prefs.getInt(PREF_TAP_COUNT, 0) + 1).apply();

        if (firstTapAt == 0L) {
            firstTapAt = now;
            return;
        }
        long interval = now - firstTapAt;
        if (interval >= 120L && interval <= 700L) {
            firstTapAt = 0L;
            prefs.edit().putInt(PREF_DOUBLE_COUNT, prefs.getInt(PREF_DOUBLE_COUNT, 0) + 1).apply();
            if (now - lastScreenshotAt > 1200L) {
                lastScreenshotAt = now;
                saveLatestScreenshot(false);
            }
        } else if (interval > 700L) {
            firstTapAt = now;
        }
    }

    private void captureTestWithRetry(int attempt) {
        if (attempt > 5) {
            Toast.makeText(this, "ეკრანის კადრი ვერ მივიღე. შეამოწმე, რომ გაზიარების სესია აქტიურია.", Toast.LENGTH_LONG).show();
            return;
        }
        synchronized (frameLock) {
            if (latestFrame != null && !latestFrame.isRecycled()) {
                saveLatestScreenshot(true);
                return;
            }
        }
        mainHandler.postDelayed(() -> captureTestWithRetry(attempt + 1), 350L);
    }

    private void saveLatestScreenshot(boolean test) {
        Bitmap frame;
        synchronized (frameLock) {
            if (latestFrame == null || latestFrame.isRecycled()) {
                if (test) Toast.makeText(this, "ეკრანის კადრი ჯერ მზად არ არის", Toast.LENGTH_SHORT).show();
                Log.w(TAG, "Screenshot requested before first frame arrived");
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
            if (imageUri == null) throw new IllegalStateException("ფოტოს ფაილის შექმნა ვერ მოხერხდა");
            output = getContentResolver().openOutputStream(imageUri);
            if (output == null) throw new IllegalStateException("ფაილის ჩაწერა ვერ მოხერხდა");
            boolean compressed = frame.compress(Bitmap.CompressFormat.PNG, 100, output);
            if (!compressed) throw new IllegalStateException("PNG ფაილის შენახვა ვერ მოხერხდა");
            output.flush();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(imageUri, done, null, null);
            }
            Log.i(TAG, "Screenshot saved: " + imageUri);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_screenshot", fileName).apply();
            if (test) Toast.makeText(this, "ტესტური სქრინშოტი შენახულია: Pictures/BackTapScreenshot", Toast.LENGTH_LONG).show();
        } catch (Exception exception) {
            Log.e(TAG, "Screenshot save failed", exception);
            if (imageUri != null) getContentResolver().delete(imageUri, null, null);
            if (test) Toast.makeText(this, "შენახვის შეცდომა: " + safeMessage(exception), Toast.LENGTH_LONG).show();
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
            channel.setDescription("Back tap screenshot is active while this notification is shown.");
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

    @Override public void onDestroy() {
        started = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) { }
        }
        if (imageThread != null) imageThread.quitSafely();
        synchronized (frameLock) {
            if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
            latestFrame = null;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
