package com.backtap.screenshot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "back_tap_preferences";
    private static final String PREF_THRESHOLD = "threshold";
    private static final int REQUEST_MEDIA_PROJECTION = 701;
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int TEXT = Color.rgb(238, 242, 255);
    private static final int MUTED = Color.rgb(178, 190, 210);

    private TextView projectionStatus;
    private TextView serviceStatus;
    private TextView sensitivityValue;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createScreen());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        new Handler().postDelayed(this::refreshStatus, 900L);
        new Handler().postDelayed(this::refreshStatus, 2200L);
    }

    private View createScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(16, 24, 39));

        TextView title = new TextView(this);
        title.setText("Back Tap Screenshot");
        title.setTextColor(TEXT);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("ტელეფონის უკანა მხარეს ორჯერ მსუბუქად დააკაკუნე და გადაიღე სქრინშოტი.");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(10), 0, dp(18));
        root.addView(subtitle);

        addSectionTitle(root, "1. ეკრანის გადაღების ნებართვა");
        projectionStatus = addStatusText(root);
        Button startButton = makeButton("ნებართვის მიცემა და დაკაკუნების ჩართვა");
        startButton.setOnClickListener(v -> requestCapturePermission());
        root.addView(startButton, buttonParams());

        addSectionTitle(root, "2. დაკაკუნების ამოცნობა");
        serviceStatus = addStatusText(root);

        Button testButton = makeButton("ტესტური სქრინშოტი");
        testButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(14, 116, 144)));
        testButton.setOnClickListener(v -> {
            boolean running = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(TapDetectionService.PREF_RUNNING, false);
            if (!running) {
                Toast.makeText(this, "ჯერ ჩართე ეკრანის გადაღების ნებართვა", Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(this, TapDetectionService.class);
            intent.setAction(TapDetectionService.ACTION_TEST_SCREENSHOT);
            try {
                startService(intent);
            } catch (RuntimeException e) {
                Toast.makeText(this, "სერვისი აღარ მუშაობს. ხელახლა ჩართე ნებართვა.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(testButton, buttonParams());

        Button stopButton = makeButton("გამორთვა");
        stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(75, 85, 99)));
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, TapDetectionService.class));
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(TapDetectionService.PREF_RUNNING, false)
                    .putString(TapDetectionService.PREF_ERROR, "")
                    .apply();
            refreshStatus();
            Toast.makeText(this, "სერვისი გამორთულია", Toast.LENGTH_SHORT).show();
        });
        root.addView(stopButton, buttonParams());

        addSectionTitle(root, "მგრძნობელობა");
        sensitivityValue = new TextView(this);
        sensitivityValue.setTextColor(MUTED);
        sensitivityValue.setTextSize(14);
        root.addView(sensitivityValue);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(20);
        float saved = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(PREF_THRESHOLD, 2.2f);
        int initial = Math.max(0, Math.min(20, Math.round((saved - 1f) * 4f)));
        seekBar.setProgress(initial);
        updateSensitivityLabel(initial);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateSensitivityLabel(progress);
                float threshold = 1f + progress * 0.25f;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(PREF_THRESHOLD, threshold).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView help = new TextView(this);
        help.setText("გამოცდა: ჯერ ჩართე ეკრანის გადაღება. შემდეგ დააჭირე „ტესტური სქრინშოტი“ — თუ სურათი შეინახა, გადაღება მუშაობს. ამის შემდეგ სცადე უკანა მხარეს ორჯერ დაკაკუნება. აირჩიე „მთელი ეკრანი“ და არა მხოლოდ ერთი აპი.");
        help.setTextColor(MUTED);
        help.setTextSize(13);
        help.setPadding(0, dp(16), 0, 0);
        root.addView(help);

        TextView privacy = new TextView(this);
        privacy.setText("ეკრანის გადაღება მუშაობს მხოლოდ მანამ, სანამ Android-ის ეკრანის გაზიარების სესია აქტიურია. Android შეიძლება პერიოდულად ხელახლა ითხოვდეს თანხმობას. სურათები ინახება Pictures/BackTapScreenshot საქაღალდეში.");
        privacy.setTextColor(MUTED);
        privacy.setTextSize(12);
        privacy.setPadding(0, dp(12), 0, 0);
        root.addView(privacy);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(root);
        return scrollView;
    }

    private void requestCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "ეკრანის გადაღების სერვისი მიუწვდომელია", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MEDIA_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "ნებართვა არ მიგიცია — სერვისი არ ჩაირთო", Toast.LENGTH_LONG).show();
            return;
        }
        Intent serviceIntent = new Intent(this, TapDetectionService.class);
        serviceIntent.putExtra(TapDetectionService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(TapDetectionService.EXTRA_RESULT_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
            else startService(serviceIntent);
            Toast.makeText(this, "ნებართვა მიღებულია. ვრთავ სენსორს…", Toast.LENGTH_LONG).show();
        } catch (RuntimeException exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(TapDetectionService.PREF_RUNNING, false)
                    .putString(TapDetectionService.PREF_ERROR, exception.getClass().getSimpleName() + ": " + exception.getMessage())
                    .apply();
            Toast.makeText(this, "ვერ ჩაირთო: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshStatus();
        new Handler().postDelayed(this::refreshStatus, 900L);
        new Handler().postDelayed(this::refreshStatus, 2200L);
    }

    private void addSectionTitle(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(TEXT);
        label.setTextSize(17);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, dp(16), 0, dp(8));
        root.addView(label);
    }

    private TextView addStatusText(LinearLayout root) {
        TextView status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);
        return status;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.setMargins(0, dp(4), 0, dp(4));
        return params;
    }

    private void refreshStatus() {
        if (projectionStatus == null || serviceStatus == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean running = prefs.getBoolean(TapDetectionService.PREF_RUNNING, false);
        String error = prefs.getString(TapDetectionService.PREF_ERROR, "");
        projectionStatus.setText("აირჩიე „მთელი ეკრანი“ და დაადასტურე Android-ის ფანჯარაში.");
        if (running) {
            int peaks = prefs.getInt(TapDetectionService.PREF_TAP_COUNT, 0);
            int doubles = prefs.getInt(TapDetectionService.PREF_DOUBLE_COUNT, 0);
            boolean frameReady = prefs.getBoolean(TapDetectionService.PREF_FRAME_READY, false);
            serviceStatus.setText("ჩართულია — სენსორი უსმენს.\n" +
                    prefs.getString(TapDetectionService.PREF_SENSOR, "სენსორი: მზად არის") +
                    "\nსენსორის დარტყმები: " + peaks + " | ორმაგი დაკაკუნება: " + doubles +
                    "\nეკრანის კადრი: " + (frameReady ? "მზად არის" : "იტვირთება…"));
        } else if (error != null && !error.isEmpty()) {
            serviceStatus.setText("ვერ ჩაირთო: " + error);
        } else {
            serviceStatus.setText("გამორთულია — ჯერ ჩართე ეკრანის გადაღების ნებართვა.");
        }
    }

    private void updateSensitivityLabel(int progress) {
        float threshold = 1f + progress * 0.25f;
        if (sensitivityValue != null) sensitivityValue.setText(String.format(Locale.US,
                "ზღვარი: %.2f m/s² (მარცხნივ = უფრო მგრძნობიარე)", threshold));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
