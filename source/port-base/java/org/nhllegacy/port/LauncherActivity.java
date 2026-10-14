package org.nhllegacy.port;

import android.app.Activity;
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
import java.io.File;

public final class LauncherActivity extends Activity {
    static final String ASPECT = "display_aspect";
    static final String RESOLUTION = "internal_resolution";
    static final String SHADOWS = "shadow_quality";
    static final String CROWD = "show_crowd";
    static final String TOUCH = "touch_controls";
    private static final String[] TOUCH_MODES = {"auto", "always", "off"};
    private static final String[] RESOLUTIONS = {"720p", "540p", "360p"};
    private static final String[] SHADOW_MODES = {"native", "filtered", "soft"};
    private TextView status;
    private Button launch;
    static File gameRoot(Activity activity) {
        return new File(activity.getFilesDir(), "game");
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(28 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("NHL Legacy"); title.setTextSize(30);
        content.addView(title);
        TextView info = new TextView(this);
        info.setText("Нативный порт для Android\nТестовая сборка — режимы качества");
        info.setTextSize(18); content.addView(info);
        TextView formatTitle = new TextView(this);
        formatTitle.setText("Формат экрана"); formatTitle.setTextSize(18);
        formatTitle.setPadding(0, padding, 0, 0); content.addView(formatTitle);
        RadioGroup formats = new RadioGroup(this);
        formats.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton original = new RadioButton(this);
        original.setId(android.view.View.generateViewId()); original.setText("Исходные пропорции");
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
        Spinner resolution = addSetting(content, "Внутреннее разрешение", RESOLUTION,
            RESOLUTIONS, new String[]{"720p — исходное (1280 × 720)",
                "540p — временно недоступно", "360p — временно недоступно"});
        Spinner shadows = addSetting(content, "Качество теней", SHADOWS,
            SHADOW_MODES, new String[]{"Исходные", "Сглаженные", "Мягкие"});
        CheckBox crowd = new CheckBox(this);
        crowd.setText("Зрители"); crowd.setTextSize(18);
        crowd.setChecked(getPreferences(MODE_PRIVATE).getBoolean(CROWD, true));
        crowd.setOnCheckedChangeListener((button, enabled) -> getPreferences(MODE_PRIVATE)
            .edit().putBoolean(CROWD, enabled).apply());
        content.addView(crowd);
        Spinner touch = addSetting(content, "Экранное управление", TOUCH,
            TOUCH_MODES, new String[]{"Авто — без геймпада", "Всегда показывать", "Выключено"});
        TextView hint = new TextView(this);
        hint.setText("Настройки применяются при новом запуске игры.\n"
            + "Низкие разрешения отключены до исправления искажений изображения.");
        hint.setTextSize(16); content.addView(hint);
        status = new TextView(this); status.setTextSize(16);
        status.setPadding(0, padding, 0, padding); content.addView(status);
        launch = new Button(this); launch.setText("Запустить игру");
        launch.setOnClickListener(view -> startActivity(new Intent(this, GameActivity.class)
            .putExtra(ASPECT, formats.getCheckedRadioButtonId() == standard.getId() ? "4:3"
                : formats.getCheckedRadioButtonId() == wide.getId() ? "16:9" : "original")
            .putExtra(RESOLUTION, RESOLUTIONS[resolution.getSelectedItemPosition()])
            .putExtra(SHADOWS, SHADOW_MODES[shadows.getSelectedItemPosition()])
            .putExtra(CROWD, crowd.isChecked())
            .putExtra(TOUCH, TOUCH_MODES[touch.getSelectedItemPosition()])));
        content.addView(launch);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(content); setContentView(scroll);
    }
    private Spinner addSetting(LinearLayout content, String label, String key,
                               String[] values, String[] labels) {
        TextView title = new TextView(this); title.setText(label); title.setTextSize(18);
        title.setPadding(0, 20, 0, 4); content.addView(title);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, labels) {
            @Override public boolean isEnabled(int position) {
                return !RESOLUTION.equals(key) || position == 0;
            }
            @Override public boolean areAllItemsEnabled() {
                return !RESOLUTION.equals(key);
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String saved = getPreferences(MODE_PRIVATE).getString(key, values[0]);
        if (RESOLUTION.equals(key) && !values[0].equals(saved)) {
            saved = values[0];
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
        boolean exists = new File(gameRoot(this), "default.xex").isFile();
        status.setText(exists ? "Файлы игры установлены. Можно использовать экранное управление или геймпад."
            : "Файлы игры ещё не установлены. Для этой тестовой сборки их переносит установщик на компьютере.");
        launch.setEnabled(exists);
        if (exists) launch.requestFocus();
    }
}
