package com.backtap.screenshot;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** Performs Android's built-in global screenshot action after the user enables this service. */
public class ScreenshotAccessibilityService extends AccessibilityService {
    private static final String TAG = "BackTapScreenshot";
    private static volatile ScreenshotAccessibilityService activeService;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No screen content is read or inspected.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public void onDestroy() {
        if (activeService == this) {
            activeService = null;
        }
        super.onDestroy();
    }

    public static boolean takeScreenshotNow() {
        ScreenshotAccessibilityService service = activeService;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        try {
            return service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Screenshot action failed", exception);
            return false;
        }
    }
}
