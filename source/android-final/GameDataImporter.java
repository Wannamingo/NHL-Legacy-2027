package org.nhllegacy.port;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Authenticated, staged game replacement. User saves remain outside game/. */
final class GameDataImporter {
    static final class Expected {
        final long size;
        final String sha256;
        Expected(long size, String sha256) { this.size = size; this.sha256 = sha256; }
    }
    interface Progress { void update(long bytes, int files); }
    static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[2*i] = alphabet[(bytes[i] & 255) >>> 4];
            result[2*i+1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }
    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
    private static void within(File root, File file) throws IOException {
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Недопустимый путь к файлу");
    }
    private static void remove(File root, File file) throws IOException {
        within(root, file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Не удалось прочитать временную папку");
            for (File child : children) remove(root, child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Не удалось удалить временный файл");
    }
    static void recover(File root) throws IOException {
        File game = new File(root, "game"), backup = new File(root, "game.previous-import");
        within(root, game); within(root, backup);
        if (!game.exists() && backup.exists() && !backup.renameTo(game))
            throw new IOException("Не удалось восстановить прежние файлы игры");
    }
    private static long treeBytes(File root, File file) throws IOException {
        within(root, file);
        if (!file.exists()) return 0;
        if (!file.isDirectory()) return file.length();
        File[] children = file.listFiles();
        if (children == null) throw new IOException("Не удалось прочитать кеш игры");
        long size = 0;
        for (File child : children) size = Math.addExact(size, treeBytes(root, child));
        return size;
    }
    private static void checkCancel(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("Импорт отменён. Прежние файлы сохранены.");
    }
    private static void copyCache(File root, File from, File to, BooleanSupplier cancelled) throws IOException {
        within(root, from); within(root, to); checkCancel(cancelled);
        if (!from.exists()) return;
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Не удалось сохранить кеш игры");
            File[] children = from.listFiles();
            if (children == null) throw new IOException("Не удалось прочитать кеш игры");
            for (File child : children) copyCache(root, child, new File(to, child.getName()), cancelled);
        } else {
            try (InputStream input = new FileInputStream(from); FileOutputStream output = new FileOutputStream(to)) {
                byte[] buffer = new byte[1024 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) { checkCancel(cancelled); output.write(buffer, 0, n); }
                output.getFD().sync();
            }
        }
    }
    /** Import the exact game payload directly from a seekable Xbox 360 ISO. */
    static void installIso(File root, FileChannel channel, Map<String, Expected> expected,
                           String manifestSha, Progress progress, BooleanSupplier cancelled) throws Exception {
        if (!expected.containsKey("default.xex") || !manifestSha.matches("[0-9a-f]{64}"))
            throw new IOException("Неверный комплект игровых файлов");
        long expectedTotal = 0;
        for (Map.Entry<String, Expected> row : expected.entrySet()) {
            String name = row.getKey();
            if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                    || Arrays.asList(name.split("/", -1)).contains("..")
                    || Arrays.asList(name.split("/", -1)).contains(".")
                    || Arrays.asList(name.split("/", -1)).contains("")
                    || row.getValue().size < 0 || !row.getValue().sha256.matches("[0-9a-f]{64}"))
                throw new IOException("Неверная таблица игровых файлов");
            expectedTotal = Math.addExact(expectedTotal, row.getValue().size);
        }
        XdvdfsIso iso = new XdvdfsIso(channel);
        Map<String, XdvdfsIso.Entry> indexed = new HashMap<>();
        for (Map.Entry<String, Expected> row : expected.entrySet()) {
            XdvdfsIso.Entry entry = iso.requireFile(row.getKey());
            if (entry.size != row.getValue().size)
                throw new IOException("Неверный размер файла в ISO: " + row.getKey());
            indexed.put(row.getKey(), entry);
        }
        File lockPath = new File(root, ".game-import.lock");
        within(root, lockPath);
        try (FileOutputStream lockStream = new FileOutputStream(lockPath, true);
             FileLock lock = lockStream.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Другой импорт ещё выполняется");
            recover(root);
            File game = new File(root, "game"), staging = new File(root, "game.importing"),
                 backup = new File(root, "game.previous-import");
            remove(root, staging);
            long cacheBytes = treeBytes(root, new File(game, "_android_cache"));
            if (root.getUsableSpace() < Math.addExact(expectedTotal, cacheBytes) + 128L*1024*1024)
                throw new IOException("Недостаточно места: нужно ещё "
                    + ((expectedTotal + cacheBytes + 1024*1024-1)/(1024*1024)) + " МБ для установки");
            if (!staging.mkdirs()) throw new IOException("Не удалось создать папку импорта");
            boolean promoted = false;
            try {
                List<String> names = new ArrayList<>(expected.keySet());
                Collections.sort(names);
                byte[] buffer = new byte[1024 * 1024];
                long total = 0, lastProgress = 0;
                int done = 0;
                for (String name : names) {
                    checkCancel(cancelled);
                    File destination = new File(staging, name);
                    within(staging, destination);
                    File parent = destination.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs())
                        throw new IOException("Не удалось создать папку игрового файла");
                    Expected identity = expected.get(name);
                    XdvdfsIso.Entry entry = indexed.get(name);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (FileOutputStream output = new FileOutputStream(destination)) {
                        for (long offset = 0; offset < identity.size;) {
                            checkCancel(cancelled);
                            int size = (int)Math.min(buffer.length, identity.size - offset);
                            iso.readFile(entry, offset, buffer, size);
                            digest.update(buffer, 0, size);
                            output.write(buffer, 0, size);
                            offset += size;
                            total += size;
                            if (total - lastProgress >= 16L*1024*1024) {
                                progress.update(total, done); lastProgress = total;
                            }
                        }
                        output.getFD().sync();
                    }
                    if (!hex(digest.digest()).equals(identity.sha256))
                        throw new IOException("Повреждён игровой файл в ISO: " + name);
                    done++;
                    progress.update(total, done);
                }
                if (total != expectedTotal) throw new IOException("Неполный импорт ISO");
                copyCache(root, new File(game, "_android_cache"), new File(staging, "_android_cache"), cancelled);
                checkCancel(cancelled);
                try (FileOutputStream marker = new FileOutputStream(new File(staging, ".verified-20261010"))) {
                    marker.write(manifestSha.getBytes("UTF-8")); marker.getFD().sync();
                }
                remove(root, backup);
                if (game.exists() && !game.renameTo(backup))
                    throw new IOException("Не удалось подготовить замену игры");
                if (!staging.renameTo(game)) {
                    recover(root);
                    throw new IOException("Не удалось завершить импорт; прежняя игра восстановлена");
                }
                promoted = true;
                try { remove(root, backup); } catch (IOException ignored) { }
                progress.update(total, done);
            } finally {
                if (!promoted) remove(root, staging);
            }
        }
    }
}
