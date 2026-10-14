package org.nhllegacy.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.AdapterView;
import android.widget.CheckBox;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import android.net.Uri;
import android.app.ActivityManager;
import android.widget.ProgressBar;
import android.view.WindowManager;
import org.json.JSONObject;

public final class LauncherActivity extends Activity {
    static final String ASPECT = "display_aspect";
    static final String RESOLUTION = "internal_resolution";
    static final String SHADOWS = "shadow_quality";
    static final String CROWD = "arena_show_crowd";
    static final String STANDS = "arena_show_stands";
    static final String BENCH = "arena_show_bench";
    static final String TOUCH = "touch_controls_157";
    private static final String[] TOUCH_MODES = {"off", "auto", "always"};
    static final String MATCH_QUALITY = QualitySettings151.KEY;
    private Spinner matchQuality;
    private static final String[] SHADOW_MODES = {"native", "filtered", "soft"};
    static final String DRIVER = "vulkan_driver_id";
    private static final int IMPORT_DRIVER = 132;
    private static final int IMPORT_ROSTER = 153;
    private DriverStore driverStore;
    private Spinner driverSpinner;
    private TextView driverStatus;
    private Button importDriver, removeDriver, checkDriver;
    private GpuDriverCheck driverCheck;
    private boolean checkingDriver;
    private Runnable gameLaunch150;
    private java.util.List<DriverStore.Driver> drivers = new java.util.ArrayList<>();
    private boolean driverListLoading;
    private TextView status;
    private Button launch, importData, cancelImport;
    private Button clearGraphicsCache;
    private ProgressBar importProgress;
    private boolean importing;
    private boolean clearingCache;
    private boolean workingRoster;
    private Button importRoster;
    private TextView rosterStatus;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private String tr(String russian) { return LauncherLanguage.text(this, russian); }

    void switchLanguage() {
        if (importing || clearingCache || workingRoster || checkingDriver) return;
        LauncherLanguage.toggle(this);
        recreate();
    }

    static File gameRoot(Activity activity) {
        return new File(activity.getFilesDir(), "game");
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // The user requested full-arena release defaults, including this upgrade.
        // Apply once; later individual choices remain persistent.
        if (!getPreferences(MODE_PRIVATE).getBoolean("full_arena_defaults150", false))
            getPreferences(MODE_PRIVATE).edit().putBoolean(CROWD, true).putBoolean(STANDS, true)
                .putBoolean(BENCH, true).putBoolean("full_arena_defaults150", true).commit();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(28 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("NHL Legacy 2027"); title.setTextSize(30);
        content.addView(title);
        TextView info = new TextView(this);
        info.setText(tr("Нативный порт для Android\nРелиз 1.0.2"));
        info.setTextSize(18); content.addView(info);
        TextView formatTitle = new TextView(this);
        formatTitle.setText(tr("Формат экрана")); formatTitle.setTextSize(18);
        formatTitle.setPadding(0, padding, 0, 0); content.addView(formatTitle);
        RadioGroup formats = new RadioGroup(this);
        formats.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton original = new RadioButton(this);
        original.setId(android.view.View.generateViewId()); original.setText(tr("Исходные пропорции"));
        RadioButton standard = new RadioButton(this);
        standard.setId(android.view.View.generateViewId()); standard.setText("4:3");
        RadioButton wide = new RadioButton(this);
        wide.setId(android.view.View.generateViewId()); wide.setText("16:9");
        formats.addView(original); formats.addView(standard); formats.addView(wide); content.addView(formats);
        String saved = getPreferences(MODE_PRIVATE).getString(ASPECT, "original");
        formats.check("4:3".equals(saved) ? standard.getId()
            : "16:9".equals(saved) ? wide.getId() : original.getId());
        formats.setOnCheckedChangeListener((group, checked) -> getPreferences(MODE_PRIVATE)
            .edit().putString(ASPECT, checked == standard.getId() ? "4:3"
                : checked == wide.getId() ? "16:9" : "original").apply());
        TextView renderInfo = new TextView(this);
        matchQuality = addSetting(content, tr("Качество графики"), MATCH_QUALITY,
            QualitySettings151.VALUES, new String[]{tr("Производительность — матч 960 × 540"), tr("Чёткость — матч 1280 × 720")});
        renderInfo.setText(tr("Интерфейс в обоих режимах: 1280 × 720"));
        renderInfo.setTextSize(18); content.addView(renderInfo);
        Spinner shadows = addSetting(content, tr("Качество теней"), SHADOWS,
            SHADOW_MODES, new String[]{tr("Исходные"), tr("Сглаженные"), tr("Мягкие")});
        TextView arenaTitle = new TextView(this);
        arenaTitle.setText(tr("Арена")); arenaTitle.setTextSize(20);
        arenaTitle.setPadding(0, padding, 0, 4); content.addView(arenaTitle);
        CheckBox crowd = addToggle(content, tr("Зрители"), CROWD);
        CheckBox stands = addToggle(content, tr("Трибуны и кресла"), STANDS);
        CheckBox bench = addToggle(content, tr("Запасные на скамейке"), BENCH);
        Spinner touch = addSetting(content, tr("Экранное управление"), TOUCH,
            TOUCH_MODES, new String[]{tr("Выключено"), tr("Авто — без геймпада"), tr("Всегда показывать")});
        addDriverSettings(content);
        addRosterSettings(content);
        clearGraphicsCache = new Button(this);
        clearGraphicsCache.setText(tr("Очистить графический кеш"));
        clearGraphicsCache.setOnClickListener(view -> confirmGraphicsCacheClear());
        content.addView(clearGraphicsCache);
        TextView cacheHint = new TextView(this);
        cacheHint.setText(tr("Удаляет только скомпилированные шейдеры и кеш Vulkan для этой игры. Сохранения, игровые файлы и установленные драйверы останутся."));
        cacheHint.setTextSize(14); content.addView(cacheHint);
        TextView hint = new TextView(this);
        hint.setText(tr("Настройки применяются при новом запуске игры.\n")
            + tr("Полная арена включена по умолчанию. Качество игроков сохраняется."));
        hint.setTextSize(16); content.addView(hint);
        status = new TextView(this); status.setTextSize(16);
        status.setPadding(0, padding, 0, padding);
        importData = new Button(this); importData.setText(tr("Импортировать игровые файлы из ISO"));
        importData.setOnClickListener(view -> {
            if (gameRunning() || importing || clearingCache || workingRoster) { status.setText(tr("Сначала полностью закройте игру и дождитесь завершения текущей операции.")); return; }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
            try { startActivityForResult(intent, 10); }
            catch (android.content.ActivityNotFoundException error) { status.setText(tr("На устройстве недоступен выбор файлов.")); }
        });
        importProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importProgress.setMax(10000); importProgress.setVisibility(android.view.View.GONE);
        cancelImport = new Button(this); cancelImport.setText(tr("Отменить импорт"));
        cancelImport.setVisibility(android.view.View.GONE);
        cancelImport.setOnClickListener(view -> { cancelled.set(true); cancelImport.setEnabled(false); status.setText(tr("Отмена импорта…")); });
        launch = new Button(this); launch.setText(tr("Запустить игру"));
        gameLaunch150 = () -> startActivity(new Intent(this, GameActivity.class)
            .putExtra(DRIVER, getPreferences(MODE_PRIVATE).getString(DRIVER, DriverStore.SYSTEM))
            .putExtra(ASPECT, formats.getCheckedRadioButtonId() == standard.getId() ? "4:3"
                : formats.getCheckedRadioButtonId() == wide.getId() ? "16:9" : "original")
            .putExtra(RESOLUTION, "720p")
            .putExtra(MATCH_QUALITY, QualitySettings151.VALUES[matchQuality.getSelectedItemPosition()])
            .putExtra(SHADOWS, SHADOW_MODES[shadows.getSelectedItemPosition()])
            .putExtra(CROWD, crowd.isChecked())
            .putExtra(STANDS, stands.isChecked())
            .putExtra(BENCH, bench.isChecked())
            .putExtra(TOUCH, TOUCH_MODES[touch.getSelectedItemPosition()]));
        launch.setOnClickListener(view -> {
            if (gameRunning() || importing || checkingDriver || clearingCache || workingRoster) return;
            String selected = getPreferences(MODE_PRIVATE).getString(DRIVER, DriverStore.SYSTEM);
            if (DriverStore.SYSTEM.equals(selected)) gameLaunch150.run();
            else runDriverCheck(true);
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(content);
        setContentView(LauncherPresentation152.create(this, scroll, launch, importData,
            status, importProgress, cancelImport));
    }
    private boolean gameRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo p : processes)
            if ((getPackageName()+":game").equals(p.processName)) return true;
        return false;
    }
    private void addRosterSettings(LinearLayout content) {
        TextView heading = new TextView(this); heading.setText(tr("Составы команд")); heading.setTextSize(20);
        heading.setPadding(0, 24, 0, 4); content.addView(heading);
        Button rosterProfile = new Button(this); rosterProfile.setText(tr("Выбрать профиль составов"));
        rosterProfile.setOnClickListener(view -> {
            if (gameRunning() || importing || clearingCache || checkingDriver || workingRoster) return;
            java.util.List<String> profiles = RosterImporter.profiles(getFilesDir());
            if (profiles.isEmpty()) { rosterStatus.setText(tr("Профиль игры не найден.")); return; }
            new AlertDialog.Builder(this).setTitle(tr("Профиль для обновления составов"))
                .setItems(profiles.toArray(new String[0]), (dialog, index) -> {
                    String chosen = profiles.get(index);
                    getPreferences(MODE_PRIVATE).edit().putString("roster_profile", chosen).commit();
                    rosterStatus.setText(tr("Профиль составов: ") + chosen + tr(". Теперь выберите ZIP для обновления."));
                }).setNegativeButton(tr("Отмена"), null).show();
        });
        content.addView(rosterProfile);
        importRoster = new Button(this); importRoster.setText(tr("Обновить состав из ZIP"));
        importRoster.setOnClickListener(view -> {
            if (gameRunning() || importing || clearingCache || checkingDriver || workingRoster) {
                rosterStatus.setText(tr("Для импорта полностью закройте игру и дождитесь завершения другой операции.")); return;
            }
            java.util.List<String> profiles = RosterImporter.profiles(getFilesDir());
            if (profiles.isEmpty()) {
                rosterStatus.setText(tr("Профиль не найден. Один раз запустите игру и создайте профиль.")); return;
            }
            String selected = getPreferences(MODE_PRIVATE).getString("roster_profile", "");
            if (profiles.size() == 1) openRosterZip(profiles.get(0));
            else if (profiles.contains(selected)) openRosterZip(selected);
            else new AlertDialog.Builder(this).setTitle(tr("Профиль для обновления составов"))
                .setItems(profiles.toArray(new String[0]), (dialog, index) -> openRosterZip(profiles.get(index)))
                .setNegativeButton(tr("Отмена"), null).show();
        });
        content.addView(importRoster);
        rosterStatus = new TextView(this); rosterStatus.setTextSize(15);
        rosterStatus.setText(tr("Выбор ZIP сразу начинает обновление. Следующий ZIP заменит предыдущий импорт лаунчера для этого профиля; отката нет. Сезоны и другие сохранения не меняются."));
        content.addView(rosterStatus);
    }
    private void openRosterZip(String profile) {
        getPreferences(MODE_PRIVATE).edit().putString("roster_profile", profile).commit();
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE); picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES,
            new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { startActivityForResult(picker, IMPORT_ROSTER); }
        catch (android.content.ActivityNotFoundException error) { rosterStatus.setText(tr("На устройстве недоступен выбор файлов.")); }
    }
    private void addDriverSettings(LinearLayout content) {
        TextView heading = new TextView(this);
        heading.setText(tr("Видеодрайвер Vulkan")); heading.setTextSize(20);
        heading.setPadding(0, 24, 0, 4); content.addView(heading);
        driverSpinner = new Spinner(this); content.addView(driverSpinner);
        driverStatus = new TextView(this); driverStatus.setTextSize(16); content.addView(driverStatus);
        importDriver = new Button(this); importDriver.setText(tr("Установить драйвер из ZIP"));
        importDriver.setOnClickListener(view -> {
            if (gameRunning() || importing || clearingCache || workingRoster) { driverStatus.setText(tr("Сначала завершите текущую операцию и полностью закройте игру.")); return; }
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE); picker.setType("*/*");
            try { startActivityForResult(picker, IMPORT_DRIVER); }
            catch (android.content.ActivityNotFoundException error) { driverStatus.setText(tr("На устройстве недоступен выбор файлов.")); }
        });
        content.addView(importDriver);
        removeDriver = new Button(this); removeDriver.setText(tr("Удалить выбранный драйвер"));
        removeDriver.setOnClickListener(view -> {
            if (gameRunning() || importing || clearingCache || workingRoster) { driverStatus.setText(tr("Сначала завершите текущую операцию и полностью закройте игру.")); return; }
            String selected = getPreferences(MODE_PRIVATE).getString(DRIVER, DriverStore.SYSTEM);
            if (!DriverStore.SYSTEM.equals(selected)) {
                try { driverStore.remove(selected); getPreferences(MODE_PRIVATE).edit().putString(DRIVER, DriverStore.SYSTEM).commit(); refreshDrivers(); }
                catch (Exception error) { driverStatus.setText(LauncherLanguage.detail(this, error.getMessage())); }
            }
        });
        content.addView(removeDriver);
        checkDriver = new Button(this); checkDriver.setText(tr("Проверить выбранный драйвер"));
        checkDriver.setOnClickListener(view -> runDriverCheck(false)); content.addView(checkDriver);
        TextView hint = new TextView(this);
        hint.setText(tr("ZIP AdrenoTools для Android ARM64. Выбирайте версию с поддержкой вашего GPU.\n")
            + tr("Драйвер используется только этой игрой. Изменение применяется при новом запуске."));
        hint.setTextSize(16); content.addView(hint);
        driverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (driverListLoading) return;
                String selected = position == 0 ? DriverStore.SYSTEM : drivers.get(position-1).id;
                getPreferences(MODE_PRIVATE).edit().putString(DRIVER, selected).apply(); updateDriverDetails(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        try { driverStore = new DriverStore(getFilesDir(), android.os.Build.VERSION.SDK_INT); refreshDrivers(); }
        catch (Exception error) { driverStatus.setText(LauncherLanguage.detail(this, error.getMessage())); importDriver.setEnabled(false); removeDriver.setEnabled(false); }
    }
    private void updateDriverDetails(int position) {
        removeDriver.setEnabled(position > 0 && !gameRunning());
        if (position == 0) driverStatus.setText(tr("Системный драйвер из прошивки устройства."));
        else { DriverStore.Driver driver = drivers.get(position-1); driverStatus.setText(driver.toString()
            + (driver.author.isEmpty() ? "" : tr("\nАвтор: ")+driver.author)); }
    }
    private void refreshDrivers() {
        driverListLoading = true; drivers = driverStore.list();
        java.util.List<String> names = new java.util.ArrayList<>(); names.add(tr("Системный (по умолчанию)"));
        String selected = getPreferences(MODE_PRIVATE).getString(DRIVER, DriverStore.SYSTEM); int position = 0;
        for (int i = 0; i < drivers.size(); i++) { names.add(drivers.get(i).toString()); if (drivers.get(i).id.equals(selected)) position = i+1; }
        if (position == 0) getPreferences(MODE_PRIVATE).edit().putString(DRIVER, DriverStore.SYSTEM).apply();
        ArrayAdapter<String> adapter = LauncherPresentation152.spinnerAdapter(this, names.toArray(new String[0]));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); driverSpinner.setAdapter(adapter);
        driverSpinner.setSelection(position); updateDriverDetails(position); driverListLoading = false;
    }
    private void handleDriverResult(int request, int result, Intent data) {
        if (request != IMPORT_DRIVER || result != RESULT_OK || data == null || data.getData() == null) return;
        if (gameRunning() || importing || clearingCache || workingRoster) { driverStatus.setText(tr("Сначала завершите текущую операцию и полностью закройте игру.")); return; }
        final android.net.Uri uri = data.getData(); importDriver.setEnabled(false); driverStatus.setText(tr("Импорт драйвера…"));
        new Thread(() -> {
            String message; DriverStore.Driver imported = null;
            try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException(tr("Не удалось открыть ZIP"));
                imported = driverStore.install(input); message = tr("Драйвер установлен. Выберите его в списке.");
            } catch (Exception error) { message = tr("Импорт не выполнен: ")+LauncherLanguage.detail(this, error.getMessage()); }
            final String feedback = message;
            runOnUiThread(() -> { if (isFinishing() || isDestroyed()) return; importDriver.setEnabled(true); refreshDrivers(); driverStatus.setText(feedback); });
        }, "Driver ZIP import").start();
    }
    private CheckBox addToggle(LinearLayout content, String label, String key) {
        CheckBox toggle = new CheckBox(this);
        toggle.setText(label); toggle.setTextSize(18);
        toggle.setChecked(getPreferences(MODE_PRIVATE).getBoolean(key, true));
        toggle.setOnCheckedChangeListener((button, enabled) -> getPreferences(MODE_PRIVATE)
            .edit().putBoolean(key, enabled).apply());
        content.addView(toggle); return toggle;
    }
    private Spinner addSetting(LinearLayout content, String label, String key,
                               String[] values, String[] labels) {
        TextView title = new TextView(this); title.setText(label); title.setTextSize(18);
        title.setPadding(0, 20, 0, 4); content.addView(title);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = LauncherPresentation152.spinnerAdapter(this, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String saved = getPreferences(MODE_PRIVATE).getString(key, values[0]);
        if (MATCH_QUALITY.equals(key)) {
            saved = QualitySettings151.normalize(saved);
            getPreferences(MODE_PRIVATE).edit().putString(key, saved).apply();
        }
        for (int i = 0; i < values.length; ++i) if (values[i].equals(saved)) spinner.setSelection(i);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                                 int position, long id) {
                getPreferences(MODE_PRIVATE).edit().putString(key, values[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        content.addView(spinner); return spinner;
    }
    @Override protected void onResume() {
        super.onResume();
        if (driverStore != null && !gameRunning()) {
            File pending = new File(getFilesDir(), "driver-startup.pending");
            if (pending.isFile()) {
                getPreferences(MODE_PRIVATE).edit().putString(DRIVER, DriverStore.SYSTEM).commit(); pending.delete();
                refreshDrivers(); driverStatus.setText(tr("Предыдущий запуск с внешним драйвером не завершил подготовку. Выбран системный драйвер."));
            }
        }
        refresh();
    }
    private void ensureDefaults() throws Exception {
        File user = new File(getFilesDir(), "user");
        if (!user.isDirectory() && !user.mkdirs()) throw new IOException(tr("Не удалось создать папку настроек"));
        File target = new File(user, "android-renderer.toml");
        if (target.isFile()) return;
        File temporary = new File(user, "android-renderer.toml.installing");
        try (InputStream input = getAssets().open("ordinary-renderer.toml"); FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new IOException(tr("Не удалось установить настройки игры"));
    }
    private void refresh() {
        if (importing || checkingDriver || clearingCache || workingRoster || status == null) return;
        if (gameRunning()) {
            launch.setEnabled(false); importData.setEnabled(false);
            clearGraphicsCache.setEnabled(false);
            importRoster.setEnabled(false);
            status.setText(tr("Игра уже запущена. Вернитесь в неё или выйдите через BACK → «Выйти в меню приложения»."));
            return;
        }
        importData.setEnabled(true);
        clearGraphicsCache.setEnabled(true);
        importRoster.setEnabled(true);
        boolean exists = false;
        String message;
        try {
            GameDataImporter.recover(getFilesDir()); ensureDefaults();
            File root = gameRoot(this);
            exists = new File(root, "default.xex").isFile() && new File(root, "nocache.big").isFile()
                && BuildInfo.XEX_SHA.equals(GameDataImporter.hash(new File(root, "default.xex")));
            message = exists ? tr("Файлы игры установлены. Можно использовать экранное управление или геймпад.")
                : tr("Выберите ISO NHL Legacy Edition кнопкой импорта. Извлекать файлы вручную не нужно.");
        } catch (Exception error) { message = tr("Не удалось подготовить игру: ") + LauncherLanguage.detail(this, error.getMessage()); }
        status.setText(message); launch.setEnabled(exists);
        if (exists) launch.requestFocus();
    }
    private void requireGameClosed() throws IOException {
        ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
            if ((getPackageName() + ":game").equals(process.processName))
                throw new IOException(tr("Сначала выйдите из игры через BACK → «Выйти в меню приложения»"));
    }
    private void confirmGraphicsCacheClear() {
        if (gameRunning() || importing || checkingDriver || clearingCache || workingRoster) {
            driverStatus.setText(tr("Для очистки кеша сначала полностью закройте игру и завершите текущую операцию."));
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(tr("Очистить графический кеш?"))
            .setMessage(tr("Будут удалены только скомпилированные шейдеры и кеш Vulkan NHL Legacy, включая кеши системного и импортированных драйверов. Сохранения, игровые файлы, настройки и установленные драйверы сохранятся. Первый запуск после очистки может быть медленнее."))
            .setNegativeButton(tr("Отмена"), null)
            .setPositiveButton(tr("Очистить"), (dialog, which) -> clearGraphicsCache())
            .show();
    }
    private void clearGraphicsCache() {
        if (gameRunning() || importing || checkingDriver || clearingCache || workingRoster) return;
        clearingCache = true;
        launch.setEnabled(false); importData.setEnabled(false); clearGraphicsCache.setEnabled(false);
        importDriver.setEnabled(false); removeDriver.setEnabled(false); checkDriver.setEnabled(false);
        driverSpinner.setEnabled(false);
        status.setText(tr("Очистка графического кеша…"));
        new Thread(() -> {
            String message;
            try {
                requireGameClosed();
                GraphicsCache.Result result = GraphicsCache.clear(getFilesDir());
                message = result.files == 0 ? tr("Графический кеш уже пуст.")
                    : tr("Графический кеш очищен: ") + result.files + tr(" файлов, ")
                        + (result.bytes / (1024 * 1024)) + tr(" МБ. Первый запуск может быть медленнее.");
            } catch (Exception error) { message = tr("Кеш не очищен: ") + LauncherLanguage.detail(this, error.getMessage()); }
            final String feedback = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                clearingCache = false;
                driverSpinner.setEnabled(true); checkDriver.setEnabled(true);
                importDriver.setEnabled(driverStore != null);
                if (driverStore != null) refreshDrivers();
                refresh(); status.setText(feedback); driverStatus.setText(feedback);
            });
        }, "nhl-graphics-cache-clear").start();
    }
    private void requireGameIso(Uri uri) throws IOException {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (SecurityException error) {
            throw new IOException(tr("Нет доступа к выбранному файлу"), error);
        }
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".iso")) return;
        String type = getContentResolver().getType(uri);
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("iso")) return;
        throw new IOException(tr("Выберите файл NHL Legacy Edition .iso"));
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == IMPORT_ROSTER) {
            if (result == RESULT_OK && data != null && data.getData() != null) handleRosterZip(data.getData());
            return;
        }
        if (request == IMPORT_DRIVER) { handleDriverResult(request, result, data); return; }
        if (request != 10 || result != RESULT_OK || data == null || data.getData() == null || importing || clearingCache) return;
        Uri uri = data.getData();
        if (gameRunning()) { status.setText(tr("Сначала полностью закройте игру.")); return; }
        importing = true; cancelled.set(false); launch.setEnabled(false); importData.setEnabled(false);
        clearGraphicsCache.setEnabled(false);
        importDriver.setEnabled(false); removeDriver.setEnabled(false); driverSpinner.setEnabled(false);
        importProgress.setProgress(0); importProgress.setVisibility(android.view.View.VISIBLE);
        cancelImport.setEnabled(true); cancelImport.setVisibility(android.view.View.VISIBLE);
        status.setText(tr("Подготовка импорта… Оставьте приложение открытым."));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String message;
            try {
                requireGameClosed();
                Map<String, GameDataImporter.Expected> expected = new HashMap<>();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream identity = getAssets().open("game-identity.json")) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = identity.read(buffer)) != -1) { bytes.write(buffer, 0, n); if (bytes.size() > 16*1024*1024) throw new IOException(tr("Неверная таблица файлов")); }
                }
                JSONObject files = new JSONObject(bytes.toString("UTF-8"));
                Iterator<String> names = files.keys();
                while (names.hasNext()) {
                    String name = names.next(); JSONObject file = files.getJSONObject(name);
                    expected.put(name, new GameDataImporter.Expected(file.getLong("size"), file.getString("sha256")));
                }
                if (expected.size() != BuildInfo.GAME_FILES) throw new IOException(tr("Неверное число игровых файлов"));
                requireGameIso(uri);
                GameDataImporter.Progress reporter = (count, done) -> runOnUiThread(() -> {
                    importProgress.setProgress((int)(count*10000/BuildInfo.GAME_BYTES));
                    status.setText(tr("Проверено и установлено ") + (count/(1024*1024)) + " / "
                        + (BuildInfo.GAME_BYTES/(1024*1024)) + tr(" МБ · ") + done + " / "
                        + BuildInfo.GAME_FILES + tr(" файлов\nОставьте приложение открытым."));
                });
                android.os.ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r");
                if (descriptor == null) throw new IOException(tr("Не удалось открыть ISO"));
                try (android.os.ParcelFileDescriptor handle = descriptor;
                     FileInputStream input = new FileInputStream(handle.getFileDescriptor())) {
                    GameDataImporter.installIso(getFilesDir(), input.getChannel(), expected,
                        BuildInfo.MANIFEST_SHA, reporter, cancelled::get);
                }
                message = tr("Импорт завершён. Можно запускать игру.");
            } catch (Exception error) { message = tr("Импорт не завершён: ") + LauncherLanguage.detail(this, error.getMessage()); }
            final String resultMessage = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                importing = false; importData.setEnabled(true); clearGraphicsCache.setEnabled(true);
                driverSpinner.setEnabled(true); importDriver.setEnabled(driverStore != null);
                if (driverStore != null) refreshDrivers();
                importProgress.setVisibility(android.view.View.GONE); cancelImport.setVisibility(android.view.View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                refresh(); status.setText(resultMessage);
            });
        }, "nhl-game-import").start();
    }
    private void handleRosterZip(Uri uri) {
        if (gameRunning() || importing || clearingCache || checkingDriver || workingRoster) return;
        final String profile = getPreferences(MODE_PRIVATE).getString("roster_profile", "");
        workingRoster = true; launch.setEnabled(false); importRoster.setEnabled(false);
        rosterStatus.setText(tr("Проверка и установка ZIP состава… Оставьте лаунчер открытым."));
        new Thread(() -> {
            String message;
            try {
                requireGameClosed();
                if (!RosterImporter.profiles(getFilesDir()).contains(profile))
                    throw new IOException(tr("Выбранный профиль больше не найден"));
                RosterImporter.Prepared prepared;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException(tr("Не удалось открыть ZIP"));
                    prepared = RosterImporter.prepare(input);
                }
                String name = RosterImporter.replace(getFilesDir(), profile, prepared);
                message = tr("Состав обновлён: ") + name + tr(". Он доступен для новых матчей и сезонов после выбора в меню игры. Текущие сохранения не изменены; отката нет.");
            } catch (Exception ex) { message = tr("Обновление не выполнено: ") + LauncherLanguage.detail(this, ex.getMessage()); }
            final String feedback = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                workingRoster = false; importRoster.setEnabled(true);
                refresh(); rosterStatus.setText(feedback);
            });
        }, "nhl-roster-zip-install").start();
    }
    @Override public void onBackPressed() {
        if (importing) { cancelled.set(true); cancelImport.setEnabled(false); status.setText(tr("Отмена импорта…")); }
        else super.onBackPressed();
    }
    @Override protected void onDestroy() { cancelled.set(true); if (driverCheck != null) driverCheck.cancel(); super.onDestroy(); }
    private void runDriverCheck(boolean launchAfter) {
        if (gameRunning() || importing || checkingDriver || clearingCache || workingRoster) { driverStatus.setText(tr("Сначала завершите импорт и полностью закройте игру.")); return; }
        checkingDriver = true;
        launch.setEnabled(false); importData.setEnabled(false); importDriver.setEnabled(false); clearGraphicsCache.setEnabled(false);
        removeDriver.setEnabled(false); driverSpinner.setEnabled(false); checkDriver.setEnabled(false);
        final String selected = getPreferences(MODE_PRIVATE).getString(DRIVER, DriverStore.SYSTEM);
        driverStatus.setText(tr("Проверяем выбранный видеодрайвер…"));
        driverCheck = new GpuDriverCheck(this, selected, result -> {
            if (isFinishing() || isDestroyed()) return;
            checkingDriver = false; driverCheck = null;
            driverSpinner.setEnabled(true); importDriver.setEnabled(driverStore != null); checkDriver.setEnabled(true); clearGraphicsCache.setEnabled(true);
            refreshDrivers(); refresh();
            driverStatus.setText(LauncherLanguage.detail(this, GpuDriverCheck.describe(result)));
            try {
                result.put("selectedDriver", selected); result.put("model", android.os.Build.MODEL);
                result.put("androidApi", android.os.Build.VERSION.SDK_INT); result.put("buildFingerprint", android.os.Build.FINGERPRINT);
                result.put("checkedAtMs", System.currentTimeMillis()); result.put("build", "1.0.2-final-20261014");
                File user = new File(getFilesDir(), "user"); if (!user.isDirectory()) user.mkdirs();
                File temporary = new File(user, "gpu-compatibility.json.tmp");
                try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(result.toString(2).getBytes("UTF-8")); output.getFD().sync(); }
                temporary.renameTo(new File(user, "gpu-compatibility.json"));
            } catch (Exception ignored) { }
            if (launchAfter && result.optBoolean("ok") && !gameRunning()) gameLaunch150.run();
        });
        driverCheck.start();
    }

}
