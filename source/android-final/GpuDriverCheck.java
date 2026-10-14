package org.nhllegacy.port;

import android.content.*;
import android.os.*;
import org.json.JSONObject;

/** One disposable process per check; Vulkan never loads into the launcher. */
public final class GpuDriverCheck {
    public interface Listener { void completed(JSONObject result); }
    private final Context context;
    private final Listener listener;
    private final String driver;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean bound, finished;
    private final Runnable timeout = () -> finish(error("Проверка драйвера не ответила за 20 секунд."));
    private final Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        try { finish(new JSONObject(message.getData().getString("result", "{}"))); }
        catch (Exception e) { finish(error("Драйвер вернул неполный результат проверки.")); }
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (finished) return;
            try {
                Message message = Message.obtain(null, 1);
                Bundle data = new Bundle(); data.putString("driver", driver);
                message.setData(data); message.replyTo = reply;
                new Messenger(service).send(message);
            } catch (Exception e) { finish(error("Не удалось связаться с проверкой драйвера.")); }
        }
        public void onServiceDisconnected(ComponentName name) {
            finish(error("Процесс проверки завершился аварийно. Попробуйте системный драйвер."));
        }
        public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };
    public GpuDriverCheck(Context context, String driver, Listener listener) {
        this.context = context.getApplicationContext(); this.driver = driver; this.listener = listener;
    }
    public void start() {
        try {
            bound = context.bindService(new Intent(context, GpuDriverCheckService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) { finish(error("Не удалось запустить проверку драйвера.")); return; }
            handler.postDelayed(timeout, 20000);
        } catch (Exception e) { finish(error("Не удалось запустить проверку драйвера.")); }
    }
    public void cancel() { finish(error("Проверка отменена.")); }
    private void finish(JSONObject result) {
        if (finished) return;
        finished = true; handler.removeCallbacks(timeout);
        if (bound) { try { context.unbindService(connection); } catch (Exception ignored) {} bound = false; }
        // The service process exits after unbind; allow its hooks to disappear
        // before the next driver can be checked. No game process is touched.
        handler.postDelayed(() -> listener.completed(result), 600);
    }
    static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try { result.put("ok", false); result.put("message", message); } catch (Exception ignored) {}
        return result;
    }
    static String describe(JSONObject result) {
        String details = result.optString("gpu", "");
        if (!details.isEmpty()) details += "\nVulkan " + result.optString("api", "?")
            + " · " + result.optString("driver", "")
            + "\nBDA: " + (result.optBoolean("bda") ? "да" : "нет")
            + " · shaderInt64: " + (result.optBoolean("int64") ? "да" : "нет")
            + "\nDynamic rendering: " + (result.optBoolean("dynamicRendering") ? "да" : "нет");
        return result.optString("message", "Проверка не завершена.")
            + (details.isEmpty() ? "" : "\n" + details)
            + (result.optBoolean("ok") ? "\nБазовая проверка пройдена. Изображение и FPS проверяются в матче." : "");
    }
}
