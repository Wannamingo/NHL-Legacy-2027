package org.nhllegacy.port;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.libsdl.app.SDLActivity;

public final class GameActivity extends SDLActivity {
    private TouchPadView touchPad;
    private boolean overlayPaused,settingsOpen,menuOpen,activityResumed;
    private AlertDialog menuDialog,settingsDialog;
    private int[] stagedBindings;
    private float[] initialX,initialY;
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
                    new File(getFilesDir(), "driver-startup.pending").delete();
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
    private static void hideDetailedFrameHud(File settings) throws IOException {
        if (!settings.isFile()) return;
        String original = new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8);
        Matcher hud = Pattern.compile("(?m)^[ \\t]*nhl_frame_time_hud[ \\t]*=[^\\r\\n]*").matcher(original);
        String updated = hud.find() ? hud.replaceAll("nhl_frame_time_hud = false")
            : original + (original.endsWith("\n") ? "" : "\n") + "nhl_frame_time_hud = false\n";
        if (original.equals(updated)) return;
        File temporary = new File(settings.getParentFile(), settings.getName() + ".hud157");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(updated.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), settings.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), settings.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    @Override protected void onCreate(Bundle state) {
        File user = new File(getFilesDir(), "user");
        user.mkdirs();
        shaderProgress = new File(user, "shader-startup.progress");
        shaderProgress.delete();
        try {
            hideDetailedFrameHud(new File(user, "android-renderer.toml"));
            Os.unsetenv("NHL_CUSTOM_DRIVER_ID");
            Os.unsetenv("SKATE3_VULKAN_DRIVER_DIR");
            Os.unsetenv("SKATE3_VULKAN_DRIVER_NAME");
            Os.setenv("SKATE3_NATIVE_LIBRARY_DIR", getApplicationInfo().nativeLibraryDir, true);
            Os.setenv("SKATE3_INTERNAL_FILES_DIR", getFilesDir().getAbsolutePath()+"/", true);
            String selectedDriver = getIntent().getStringExtra(LauncherActivity.DRIVER);
            if (selectedDriver != null && !DriverStore.SYSTEM.equals(selectedDriver)) {
                try {
                    DriverStore.Driver driver = new DriverStore(getFilesDir(), android.os.Build.VERSION.SDK_INT).find(selectedDriver);
                    Os.setenv("SKATE3_VULKAN_DRIVER_DIR", driver.directory.getAbsolutePath()+"/", true);
                    Os.setenv("SKATE3_VULKAN_DRIVER_NAME", driver.library, true);
                    Os.setenv("NHL_CUSTOM_DRIVER_ID", driver.id, true);
                    try (java.io.FileOutputStream marker = new java.io.FileOutputStream(new File(getFilesDir(), "driver-startup.pending"))) {
                        marker.write(selectedDriver.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Exception error) {
                    Os.unsetenv("SKATE3_VULKAN_DRIVER_DIR"); Os.unsetenv("SKATE3_VULKAN_DRIVER_NAME");
                    Os.unsetenv("NHL_CUSTOM_DRIVER_ID");
                    android.util.Log.e("NHLDriver", "Custom driver unavailable; using system driver", error);
                }
            }
            Os.setenv("XDG_DATA_HOME", getFilesDir().getAbsolutePath(), true);
            Os.setenv("NHL_GAME_ROOT", LauncherActivity.gameRoot(this).getAbsolutePath(), true);
            Os.setenv("NHL_USER_ROOT", user.getAbsolutePath(), true);
            Os.setenv("NHL_SHADER_PROGRESS", shaderProgress.getAbsolutePath(), true);
            Os.setenv("NHL_CROWD_ENABLED", getIntent().getBooleanExtra(LauncherActivity.CROWD, true)
                ? "true" : "false", true);
            Os.setenv("NHL_STANDS_ENABLED", getIntent().getBooleanExtra(LauncherActivity.STANDS, true)
                ? "true" : "false", true);
            Os.setenv("NHL_BENCH_ENABLED", getIntent().getBooleanExtra(LauncherActivity.BENCH, true)
                ? "true" : "false", true);
            String aspect = getIntent().getStringExtra(LauncherActivity.ASPECT);
            Os.setenv("NHL_DISPLAY_ASPECT", "4:3".equals(aspect) ? "4:3"
                : "16:9".equals(aspect) ? "16:9" : "original", true);
            QualitySettings151.apply(getIntent().getStringExtra(LauncherActivity.MATCH_QUALITY));
            String shadows = getIntent().getStringExtra(LauncherActivity.SHADOWS);
            Os.setenv("NHL_SHADOW_QUALITY", "filtered".equals(shadows) ? "filtered"
                : "soft".equals(shadows) ? "soft" : "native", true);
        } catch (android.system.ErrnoException | IOException error) {
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
        if(settingsDialog!=null)settingsDialog.dismiss();
        if(menuDialog!=null)menuDialog.dismiss();
        startupHandler.removeCallbacks(pollStartup);
        startupHandler.removeCallbacks(pollHardware);
        if (batteryReceiverRegistered) unregisterReceiver(batteryReceiver);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(deviceListener);
        if (touchPad != null) touchPad.setConnected(false);
        super.onDestroy();
    }
    @Override protected void onPause() {
        if (touchPad != null) touchPad.clear();
        activityResumed=false;
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();activityResumed=true;refreshTouchControls();
        if(overlayPaused)pauseNativeThread();
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
        touchPad.setControlsVisible(enabled);
        touchPad.setVisibility(android.view.View.VISIBLE);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.KEYCODE_BUTTON_SELECT){
            if(event.getAction()==KeyEvent.ACTION_UP)showGameMenu();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        showGameMenu();
    }
    void showGameMenu() {
        if(settingsOpen||menuOpen||touchPad!=null&&touchPad.isEditing())return;
        pauseForOverlay();menuOpen=true;
        menuDialog=new AlertDialog.Builder(this).setTitle("NHL Legacy 2027")
            .setItems(new String[]{"Продолжить игру","Настройки","Выход в лаунчер"},(dialog,index)->{
                if(index==1){menuOpen=false;openGameSettings();}
                else if(index==2){finish();android.os.Process.killProcess(android.os.Process.myPid());}
            }).setOnDismissListener(dialog->{menuOpen=false;resumeAfterOverlay();}).create();
        menuDialog.show();
    }
    private void pauseForOverlay(){
        if(touchPad!=null)touchPad.clear();
        if(!overlayPaused){overlayPaused=true;pauseNativeThread();}
    }
    private void resumeAfterOverlay(){
        if(overlayPaused&&!menuOpen&&!settingsOpen&&touchPad!=null&&!touchPad.isEditing()){
            overlayPaused=false;if(activityResumed)resumeNativeThread();
        }
    }
    void openGameSettings(){
        if(settingsOpen||touchPad==null)return;
        pauseForOverlay();settingsOpen=true;
        stagedBindings=touchPad.bindings();initialX=touchPad.positionsX();initialY=touchPad.positionsY();
        showSettingsDialog();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView settingLabel(String value){
        TextView label=new TextView(this);label.setText(value);label.setTextSize(18);
        label.setTextColor(0xffffffff);label.setPadding(0,dp(12),0,dp(4));return label;
    }
    private Button settingsButton(String value){
        Button button=new Button(this);button.setText(value);button.setTextColor(0xffffffff);return button;
    }
    private Spinner spinner(String[] labels,int selected){
        Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item,labels){
            @Override public View getView(int position,View convertView,ViewGroup parent){
                TextView row=(TextView)super.getView(position,convertView,parent);
                row.setTextColor(0xffffffff);row.setBackgroundColor(0xff263847);
                return row;
            }
            @Override public View getDropDownView(int position,View convertView,ViewGroup parent){
                TextView row=(TextView)super.getDropDownView(position,convertView,parent);
                row.setTextColor(0xffffffff);row.setBackgroundColor(0xff263847);
                return row;
            }
        };
        spinner.setAdapter(adapter);spinner.setSelection(selected);return spinner;
    }
    private void showSettingsDialog(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24),dp(12),dp(24),dp(12));
        content.setBackgroundColor(0xf01b2935);
        Button bindings=settingsButton("Назначение кнопок");content.addView(bindings);
        Button layout=settingsButton("Расположение кнопок");content.addView(layout);
        TextView note=settingLabel("Игра приостановлена. Настройки кнопок применятся после «Продолжить игру».");
        note.setTextSize(14);content.addView(note);
        Button resume=settingsButton("Продолжить игру");content.addView(resume);
        Button cancel=settingsButton("Отмена");content.addView(cancel);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        settingsDialog=new AlertDialog.Builder(this).setTitle("Настройки игры")
            .setView(scroll).setCancelable(false).create();
        bindings.setOnClickListener(view->showBindingDialog());
        layout.setOnClickListener(view->{settingsDialog.dismiss();touchPad.beginEditing();});
        resume.setOnClickListener(view->{
            touchPad.setBindings(stagedBindings);touchPad.persist();
            settingsOpen=false;settingsDialog.dismiss();resumeAfterOverlay();
        });
        cancel.setOnClickListener(view->{
            touchPad.setPositions(initialX,initialY);settingsOpen=false;
            settingsDialog.dismiss();resumeAfterOverlay();
        });
        settingsDialog.show();
        settingsDialog.getWindow().setLayout(Math.min(dp(620),getResources().getDisplayMetrics().widthPixels-dp(40)),
            ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private void showBindingDialog(){
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(20),dp(8),dp(20),dp(8));
        final int[] working=stagedBindings.clone();
        for(int i=2;i<TouchPadView.IDS.length;i++){
            if(i==13)continue; // Select always opens the pause menu.
            final int index=i;
            rows.addView(settingLabel(TouchPadView.IDS[i].toUpperCase(java.util.Locale.ROOT)));
            Spinner selector=spinner(TouchPadView.ACTIONS,working[i]);
            selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(android.widget.AdapterView<?> parent){}
                public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){working[index]=position;}
            });
            rows.addView(selector);
        }
        ScrollView list=new ScrollView(this);list.addView(rows);
        new AlertDialog.Builder(this).setTitle("Назначение экранных кнопок")
            .setView(list).setPositiveButton("Готово",(dialog,which)->stagedBindings=working)
            .setNegativeButton("Отмена",null).show();
    }
    void onTouchLayoutEdited(boolean saved){
        if(settingsOpen)showSettingsDialog();else resumeAfterOverlay();
    }
    int surfaceRight(){return mSurface!=null&&mSurface.getRight()>0?mSurface.getRight():mLayout.getWidth();}
}
