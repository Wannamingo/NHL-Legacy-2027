package org.nhllegacy.port;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.File;

/** Non-exported, single-use driver worker, independent of the game process. */
public final class GpuDriverCheckService extends Service {
    private boolean used;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable exit = () -> {
        String process = android.app.Application.getProcessName();
        if ((getPackageName()+":drivercheck").equals(process)) android.os.Process.killProcess(android.os.Process.myPid());
    };
    private static native String probe(String directory, String library, String hooks, String files);
    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), request -> {
        if (request.what != 1 || request.replyTo == null) return true;
        final Messenger target = request.replyTo;
        final String id = request.getData().getString("driver", DriverStore.SYSTEM);
        if (used) { send(target, GpuDriverCheck.error("Проверка уже завершена; повторите через секунду.").toString()); return true; }
        used = true;
        new Thread(() -> {
            String result;
            try {
                String directory = "", library = "";
                if (!DriverStore.SYSTEM.equals(id)) {
                    DriverStore.Driver driver = new DriverStore(getFilesDir(), Build.VERSION.SDK_INT).find(id);
                    directory = driver.directory.getAbsolutePath(); library = driver.library;
                }
                File scratch = new File(getCacheDir(), "gpu-driver-check");
                if (!scratch.isDirectory() && !scratch.mkdirs()) throw new java.io.IOException("Не удалось подготовить проверку.");
                System.loadLibrary("nhlgpucheck");
                result = probe(directory, library, getApplicationInfo().nativeLibraryDir, scratch.getAbsolutePath()+"/");
            } catch (Throwable error) {
                result = GpuDriverCheck.error("Проверка драйвера не выполнена: "+error.getClass().getSimpleName()+". "+String.valueOf(error.getMessage())).toString();
            }
            send(target, result);
        }, "Vulkan driver check").start();
        return true;
    }));
    private void send(Messenger target, String result) {
        try { Message reply = Message.obtain(null, 2); Bundle data = new Bundle(); data.putString("result", result); reply.setData(data); target.send(reply); }
        catch (RemoteException ignored) { main.post(exit); }
    }
    @Override public IBinder onBind(Intent intent) { return incoming.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { main.postDelayed(exit, 200); return false; }
}
