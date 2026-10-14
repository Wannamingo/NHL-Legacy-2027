package org.nhllegacy.port;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import org.libsdl.app.SDLActivity;

public final class GameActivity extends SDLActivity {
    private TouchPadView touchPad;
    private android.hardware.input.InputManager inputManager;
    static native void nativeTouchPad(int buttons, float lx, float ly, float rx,
                                      float ry, float lt, float rt, boolean connected);
    static native boolean nativeDiagnosticsEnabled();
    static native void nativeHardwareSample(float batteryTemperature, int thermalStatus);
    private float batteryTemperature = -1;
    private boolean batteryReceiverRegistered;
    private final android.content.BroadcastReceiver batteryReceiver =
        new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
                int value = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                batteryTemperature = value == Integer.MIN_VALUE ? -1 : value / 10f;
            }
        };
    private final Runnable pollHardware = new Runnable() {
        @Override public void run() {
            if (nativeDiagnosticsEnabled()) {
                android.os.PowerManager power = (android.os.PowerManager)getSystemService(POWER_SERVICE);
                int status = android.os.Build.VERSION.SDK_INT >= 29 && power != null
                    ? power.getCurrentThermalStatus() : -1;
                nativeHardwareSample(batteryTemperature, status);
            }
            startupHandler.postDelayed(this, 1000);
        }
    };
    private final android.hardware.input.InputManager.InputDeviceListener deviceListener =
        new android.hardware.input.InputManager.InputDeviceListener() {
            public void onInputDeviceAdded(int id) { refreshTouchControls(); }
            public void onInputDeviceRemoved(int id) { refreshTouchControls(); }
            public void onInputDeviceChanged(int id) { refreshTouchControls(); }
        };
    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private File shaderProgress;
    private LinearLayout startupOverlay;
    private ProgressBar startupBar;
    private TextView startupCount;
    private final Runnable pollStartup = new Runnable() {
        @Override public void run() {
            try (BufferedReader reader = new BufferedReader(new FileReader(shaderProgress))) {
                String line = reader.readLine();
                String[] parts = line == null ? new String[0] : line.trim().split(" +");
                if (parts.length == 3 && "ready".equals(parts[0])) {
                    mLayout.removeView(startupOverlay);
                    startupOverlay = null;
                    return;
                }
                if (parts.length == 3 && "preparing".equals(parts[0])) {
                    int done = Integer.parseInt(parts[1]), total = Integer.parseInt(parts[2]);
                    if (total > 0 && done >= 0 && done <= total) {
                        startupBar.setIndeterminate(false);
                        startupBar.setMax(total); startupBar.setProgress(done);
                        startupCount.setText(done + " / " + total);
                    }
                }
            } catch (java.io.IOException | NumberFormatException ignored) { }
            startupHandler.postDelayed(this, 200);
        }
    };
    @Override protected String[] getLibraries() { return new String[]{"rexruntime", "main"}; }
    @Override protected String[] getArguments() { return new String[0]; }
    @Override protected void onCreate(Bundle state) {
        File user = new File(getFilesDir(), "user");
        user.mkdirs();
        shaderProgress = new File(user, "shader-startup.progress");
        shaderProgress.delete();
        try {
            Os.setenv("XDG_DATA_HOME", getFilesDir().getAbsolutePath(), true);
            Os.setenv("NHL_GAME_ROOT", LauncherActivity.gameRoot(this).getAbsolutePath(), true);
            Os.setenv("NHL_USER_ROOT", user.getAbsolutePath(), true);
            Os.setenv("NHL_SHADER_PROGRESS", shaderProgress.getAbsolutePath(), true);
            Os.setenv("NHL_CROWD_ENABLED", getIntent().getBooleanExtra(LauncherActivity.CROWD, true)
                ? "true" : "false", true);
            String aspect = getIntent().getStringExtra(LauncherActivity.ASPECT);
            Os.setenv("NHL_DISPLAY_ASPECT", "4:3".equals(aspect) ? "4:3"
                : "16:9".equals(aspect) ? "16:9" : "original", true);
            String resolution = getIntent().getStringExtra(LauncherActivity.RESOLUTION);
            Os.setenv("NHL_INTERNAL_RESOLUTION", "540p".equals(resolution) ? "540p"
                : "360p".equals(resolution) ? "360p" : "720p", true);
            String shadows = getIntent().getStringExtra(LauncherActivity.SHADOWS);
            Os.setenv("NHL_SHADOW_QUALITY", "filtered".equals(shadows) ? "filtered"
                : "soft".equals(shadows) ? "soft" : "native", true);
        } catch (android.system.ErrnoException error) {
            throw new IllegalStateException("Could not configure game directories", error);
        }
        super.onCreate(state);
        if (mBrokenLibraries) return;
        registerReceiver(batteryReceiver, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        batteryReceiverRegistered = true;
        startupHandler.post(pollHardware);
        touchPad = new TouchPadView(this);
        mLayout.addView(touchPad, new RelativeLayout.LayoutParams(-1, -1));
        inputManager = (android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(deviceListener, startupHandler);
        refreshTouchControls();
        showStartupProgress();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // RG477M supports 60 Hz. Keep pacing consistent with the title's 60 Hz UI.
        android.view.Display.Mode[] modes = getWindowManager().getDefaultDisplay().getSupportedModes();
        for (android.view.Display.Mode mode : modes) {
            if (Math.abs(mode.getRefreshRate() - 60f) < 1f) {
                WindowManager.LayoutParams attributes = getWindow().getAttributes();
                attributes.preferredDisplayModeId = mode.getModeId();
                getWindow().setAttributes(attributes); break;
            }
        }
    }
    private void showStartupProgress() {
        startupOverlay = new LinearLayout(this);
        startupOverlay.setOrientation(LinearLayout.VERTICAL);
        startupOverlay.setGravity(Gravity.CENTER);
        startupOverlay.setBackgroundColor(Color.rgb(17, 21, 27));
        TextView heading = new TextView(this);
        heading.setText("Подготовка графики"); heading.setTextSize(26); heading.setTextColor(Color.WHITE);
        heading.setGravity(Gravity.CENTER);
        startupOverlay.addView(heading);
        startupBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        startupBar.setIndeterminate(true);
        int width = Math.round(320 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams barLayout = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLayout.topMargin = 24; barLayout.bottomMargin = 12;
        startupOverlay.addView(startupBar, barLayout);
        startupCount = new TextView(this); startupCount.setTextColor(Color.WHITE); startupCount.setTextSize(18);
        startupCount.setGravity(Gravity.CENTER);
        startupOverlay.addView(startupCount);
        TextView note = new TextView(this);
        note.setText("Шейдеры сохраняются для следующих запусков."); note.setTextSize(16);
        note.setTextColor(Color.LTGRAY); note.setPadding(16, 20, 16, 0);
        note.setGravity(Gravity.CENTER);
        startupOverlay.addView(note);
        mLayout.addView(startupOverlay, new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        startupHandler.post(pollStartup);
    }
    @Override protected void onDestroy() {
        startupHandler.removeCallbacks(pollStartup);
        startupHandler.removeCallbacks(pollHardware);
        if (batteryReceiverRegistered) unregisterReceiver(batteryReceiver);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(deviceListener);
        if (touchPad != null) touchPad.setConnected(false);
        super.onDestroy();
    }
    @Override protected void onPause() {
        if (touchPad != null) touchPad.clear();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); refreshTouchControls();
    }
    private void refreshTouchControls() {
        if (touchPad == null) return;
        boolean physical = false;
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device != null && (((device.getSources() & android.view.InputDevice.SOURCE_GAMEPAD)
                    == android.view.InputDevice.SOURCE_GAMEPAD)
                || ((device.getSources() & android.view.InputDevice.SOURCE_JOYSTICK)
                    == android.view.InputDevice.SOURCE_JOYSTICK))) { physical = true; break; }
        }
        String mode = getIntent().getStringExtra(LauncherActivity.TOUCH);
        boolean enabled = "always".equals(mode) || (!"off".equals(mode) && !physical);
        touchPad.setConnected(enabled);
        touchPad.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    @Override public void onBackPressed() {
        showGameMenu();
    }
    void showGameMenu() {
        if (touchPad != null) touchPad.clear();
        new AlertDialog.Builder(this).setTitle("NHL Legacy")
            .setItems(new String[]{"Продолжить игру", "Выйти в меню приложения"}, (dialog, index) -> {
                if (index == 1) {
                    // The guest owns long-lived threads. Isolating it in :game
                    // avoids a teardown race and permits clean subsequent starts.
                    finish(); android.os.Process.killProcess(android.os.Process.myPid());
                }
            }).show();
    }
}
