package org.nhllegacy.port;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;

/** NHL Legacy 2027 home screen, using the user-provided artwork. */
final class LauncherPresentation152 {
    private static final int WHITE = Color.rgb(241, 249, 252);
    private static final int MUTED = Color.rgb(190, 215, 227);
    private static final int AQUA = Color.rgb(132, 232, 218);
    private static final int NAVY = Color.rgb(11, 43, 61);

    private LauncherPresentation152() { }

    static View create(Activity activity, ScrollView settings, Button play, Button importData,
                       TextView status, ProgressBar progress, Button cancelImport) {
        activity.getWindow().setStatusBarColor(Color.BLACK);
        activity.getWindow().setNavigationBarColor(Color.BLACK);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.rgb(15, 32, 46));
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            java.io.InputStream input = activity.getAssets().open("launcher/nhl-legacy-2027.png");
            try { image.setImageBitmap(BitmapFactory.decodeStream(input)); }
            finally { input.close(); }
        } catch (IOException ignored) { }
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x80102237, 0x48091d2c, 0xb8031d2b}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout frame = new LinearLayout(activity);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setPadding(dp(activity, 24), dp(activity, 15), dp(activity, 24), dp(activity, 12));
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label(activity, "◉   NHL LEGACY 2027", 18, WHITE);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setLetterSpacing(0.16f);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button help = new Button(activity);
        help.setText("?"); styleOutline(activity, help);
        top.addView(help, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 42)));
        TextView language = label(activity, "  RU  ", 15, WHITE);
        language.setGravity(Gravity.CENTER);
        language.setBackground(panelBackground(activity, 0x9c0b2b3d, dp(activity, 21)));
        LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(dp(activity, 60), dp(activity, 42));
        languageParams.leftMargin = dp(activity, 8);
        top.addView(language, languageParams);
        frame.addView(top);

        FrameLayout center = new FrameLayout(activity);
        frame.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(activity, 22), dp(activity, 17), dp(activity, 22), dp(activity, 17));
        card.setBackground(panelBackground(activity, 0xbf0a2d41, dp(activity, 18)));
        int available = activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 48);
        int width = Math.min(dp(activity, 490), available);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(width, -2,
            Gravity.CENTER);
        center.addView(card, cardParams);
        center.addOnLayoutChangeListener((view, left, topY, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            int fitted = Math.min(dp(activity, 490), Math.max(dp(activity, 260), right - left - dp(activity, 16)));
            FrameLayout.LayoutParams current = (FrameLayout.LayoutParams)card.getLayoutParams();
            if (current.width != fitted) { current.width = fitted; card.setLayoutParams(current); }
        });
        TextView edition = label(activity, "NHL LEGACY 2027", 16, WHITE);
        edition.setTypeface(null, Typeface.BOLD);
        edition.setLetterSpacing(0.18f);
        edition.setGravity(Gravity.CENTER);
        card.addView(edition, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = label(activity, "ХОККЕЙ НА ТВОЁМ УСТРОЙСТВЕ", 10, MUTED);
        subtitle.setLetterSpacing(0.12f); subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(activity, 4); subParams.bottomMargin = dp(activity, 12);
        card.addView(subtitle, subParams);

        play.setText("▶   ИГРАТЬ");
        play.setTextSize(20); play.setTypeface(null, Typeface.BOLD); play.setLetterSpacing(0.14f);
        play.setAllCaps(false); play.setTextColor(Color.rgb(8, 57, 67));
        play.setBackground(gradient(activity, new int[]{0xffb4ffea, 0xff5edbcf}, dp(activity, 10), 0xffc8fff1));
        card.addView(play, new LinearLayout.LayoutParams(-1, dp(activity, 58)));

        LinearLayout actions = new LinearLayout(activity);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(activity, 48));
        actionsParams.topMargin = dp(activity, 10);
        card.addView(actions, actionsParams);
        Button settingsButton = new Button(activity);
        settingsButton.setText("⚙  Настройки"); styleOutline(activity, settingsButton);
        actions.addView(settingsButton, new LinearLayout.LayoutParams(0, -1, 1));
        importData.setText("⇩  Импорт ISO"); styleOutline(activity, importData);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, -1, 1);
        importParams.leftMargin = dp(activity, 9);
        actions.addView(importData, importParams);

        status.setTextColor(MUTED); status.setTextSize(13); status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(activity, 11), 0, dp(activity, 2));
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(activity, 6)));
        styleOutline(activity, cancelImport);
        card.addView(cancelImport, new LinearLayout.LayoutParams(-1, dp(activity, 42)));

        TextView footer = label(activity,
            "NHL LEGACY 2027                                      FINAL  ·  ANDROID", 10, WHITE);
        footer.setLetterSpacing(0.10f);
        frame.addView(footer);

        Dialog dialog = settingsDialog(activity, settings);
        settingsButton.setOnClickListener(v -> dialog.show());
        help.setOnClickListener(v -> new android.app.AlertDialog.Builder(activity)
            .setTitle("NHL Legacy 2027")
            .setMessage("Импортируйте NHL-Legacy-game-data.zip для первого запуска. Выберите качество матча и видеодрайвер в настройках. Изменения применятся после нового запуска игры. Графический кеш можно очистить там же; сохранения не затрагиваются.")
            .setPositiveButton("Понятно", null).show());
        return root;
    }

    private static Dialog settingsDialog(Activity activity, ScrollView settings) {
        tintSettings(activity, settings);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(panelBackground(activity, 0xff11364b, dp(activity, 17)));
        LinearLayout header = new LinearLayout(activity);
        header.setPadding(dp(activity, 22), dp(activity, 12), dp(activity, 12), dp(activity, 8));
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(activity, "Настройки", 24, WHITE);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(activity); close.setText("×"); styleOutline(activity, close);
        header.addView(close, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)));
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(header);
        panel.addView(settings, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setPadding(dp(activity, 22), dp(activity, 8), dp(activity, 22), dp(activity, 14));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView saved = label(activity, "✓  Настройки сохранены", 12, MUTED);
        bottom.addView(saved, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = new Button(activity); done.setText("Готово");
        done.setAllCaps(false); done.setTextColor(NAVY);
        done.setBackground(gradient(activity, new int[]{AQUA, AQUA}, dp(activity, 8), AQUA));
        bottom.addView(done, new LinearLayout.LayoutParams(dp(activity, 110), dp(activity, 42)));
        done.setOnClickListener(v -> dialog.dismiss());
        panel.addView(bottom);
        dialog.setContentView(panel);
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                int width = Math.min(dp(activity, 660), activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 32));
                int height = Math.min(dp(activity, 670), activity.getResources().getDisplayMetrics().heightPixels - dp(activity, 30));
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams params = window.getAttributes();
                params.dimAmount = 0.77f;
                window.setAttributes(params);
                window.setLayout(width, height);
            }
        });
        return dialog;
    }

    static ArrayAdapter<String> spinnerAdapter(Activity activity, String[] labels) {
        return new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView text = (TextView)super.getView(position, convertView, parent);
                text.setTextColor(WHITE); text.setTextSize(16);
                return text;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView text = (TextView)super.getDropDownView(position, convertView, parent);
                text.setTextColor(WHITE); text.setTextSize(16);
                text.setBackgroundColor(NAVY);
                return text;
            }
        };
    }

    private static void tintSettings(Activity activity, View view) {
        if (view instanceof TextView && !(view instanceof Button)) ((TextView)view).setTextColor(WHITE);
        if (view instanceof CompoundButton) {
            ((CompoundButton)view).setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{AQUA, WHITE}));
        }
        if (view instanceof Button && !(view instanceof RadioButton) && !(view instanceof CheckBox))
            styleOutline(activity, (Button)view);
        if (view instanceof Spinner) view.setBackground(panelBackground(activity, 0xff0a293b, dp(activity, 7)));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) tintSettings(activity, group.getChildAt(i));
        }
    }

    private static void styleOutline(Activity activity, Button button) {
        button.setAllCaps(false); button.setTextColor(WHITE); button.setTextSize(14);
        button.setBackground(panelBackground(activity, 0xb6174053, dp(activity, 9)));
    }

    private static TextView label(Activity activity, String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        return text;
    }

    private static GradientDrawable panelBackground(Activity activity, int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(radius);
        background.setStroke(dp(activity, 1), 0x55d7f4ff);
        return background;
    }

    private static GradientDrawable gradient(Activity activity, int[] colors, int radius, int border) {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        background.setCornerRadius(radius); background.setStroke(dp(activity, 1), border);
        return background;
    }

    private static int dp(Activity activity, float value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }
}
