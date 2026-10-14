package org.nhllegacy.port;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

/** Adapts a community Xbox roster ZIP to one replaceable game roster slot. */
final class RosterImporter {
    static final String TITLE = "454109EC";
    static final int MAX_ZIP_BYTES = 12 * 1024 * 1024;
    static final int MAX_ROSTER_BYTES = 8 * 1024 * 1024;
    static final int MAX_DATABASE_BYTES = 24 * 1024 * 1024;
    private static final String[] TABLE_IDS = {
        "ttOk", "caBZ", "ulGe", "Iwiq", "FxFG", "OEtS", "ajmx", "yvSd", "lVMf", "yuHm",
        "Vzjq", "cPbu", "RBQQ", "sozv", "DlJk", "Njxh", "ihmS", "wgjx", "inlv", "RzQW",
        "FSzD", "byED", "Jckz", "QEoV", "AJKN", "nAUy", "LScw", "vuqu", "LmeT", "kTZD",
        "uiEj", "ySfc", "Roup", "vbHh", "oYeE", "NDEo", "cTuP", "xieT", "vaHq"
    };

    static final class Prepared {
        final byte[] roster;
        final String sha256, source, label, date;
        Prepared(byte[] roster, String sha256, String source, String label, String date) {
            this.roster = roster; this.sha256 = sha256; this.source = source;
            this.label = label; this.date = date;
        }
    }
    private static String hash(byte[] bytes) throws Exception {
        return GameDataImporter.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] limited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() > limit - n) throw new IOException("Файл состава слишком велик");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
    private static void validName(String name) throws IOException {
        if (name.isEmpty() || name.length() > 64 || name.contains("/") || name.contains("\\")
            || name.contains(":") || name.contains("..") || name.startsWith("."))
            throw new IOException("Недопустимое имя в ZIP");
    }
    static Prepared prepare(InputStream source) throws Exception {
        byte[] archive = limited(source, MAX_ZIP_BYTES);
        byte[] roster = null, manifest = null; String rosterEntry = null;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 16) throw new IOException("В ZIP слишком много файлов");
                String name = entry.getName();
                if (name.startsWith("__MACOSX/") || name.equals(".DS_Store")) {
                    limited(zip, 64 * 1024); zip.closeEntry(); continue;
                }
                validName(name);
                if (entry.isDirectory()) throw new IOException("В ZIP не должно быть папок");
                if (name.equals("roster.properties")) {
                    if (manifest != null) throw new IOException("Повторная таблица пакета");
                    manifest = limited(zip, 16 * 1024);
                } else {
                    if (roster != null) throw new IOException("В ZIP должен быть один состав");
                    roster = limited(zip, MAX_ROSTER_BYTES); rosterEntry = name;
                }
                zip.closeEntry();
            }
        }
        if (roster == null) throw new IOException("В ZIP нет файла состава");
        validateRoster(roster);
        String digest = hash(roster);
        String label = rosterEntry, date = "не указана";
        if (manifest != null) {
            Properties properties = new Properties();
            properties.load(new InputStreamReader(new ByteArrayInputStream(manifest), StandardCharsets.UTF_8));
            if (!"NHL-LEGACY-ROSTER-1".equals(properties.getProperty("format")))
                throw new IOException("Неизвестный формат пакета состава");
            if (!rosterEntry.equals(properties.getProperty("payload")))
                throw new IOException("Имя файла состава не совпадает с описанием пакета");
            if (!digest.equalsIgnoreCase(properties.getProperty("sha256", "")))
                throw new IOException("Контрольная сумма состава не совпадает");
            label = properties.getProperty("label", rosterEntry).trim();
            date = properties.getProperty("rosterDate", "не указана").trim();
            if (label.isEmpty() || label.length() > 80 || (!date.equals("не указана") && !date.matches("20[0-9]{2}-[01][0-9]-[0-3][0-9]")))
                throw new IOException("Неверное имя или дата состава");
        }
        return new Prepared(roster, digest, rosterEntry, label, date);
    }
    static void validateRoster(byte[] data) throws IOException {
        if (data.length < 64 || data.length > MAX_ROSTER_BYTES) throw new IOException("Неверный размер состава");
        byte[] magic = "RosterFile".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < magic.length; i++) if (data[i] != magic[i])
            throw new IOException("Поддерживается только Xbox RosterFile; PS3 SYS-DATA не устанавливается");
        int total = 0; byte[] prefix = new byte[336];
        try (InflaterInputStream input = new InflaterInputStream(
            new ByteArrayInputStream(data, 48, data.length - 48))) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (total < prefix.length) System.arraycopy(buffer, 0, prefix, total,
                    Math.min(n, prefix.length - total));
                total += n;
                if (total > MAX_DATABASE_BYTES) throw new IOException("Распакованный состав слишком велик");
            }
        } catch (ZipException error) { throw new IOException("Повреждён сжатый состав", error); }
        if (total < 1024 || prefix[0] != 'D' || prefix[1] != 'B')
            throw new IOException("Неверная база состава");
        if (be32(prefix, 8) != total - 4) throw new IOException("Размер внутренней базы не совпадает");
        int previous = -1;
        Set<String> found = new HashSet<>();
        for (int i = 0; i < TABLE_IDS.length; i++) {
            String code = new String(prefix, 24 + 8*i, 4, StandardCharsets.US_ASCII);
            int offset = be32(prefix, 28 + 8*i);
            // Editors may reorder the seven tail tables when saving an otherwise
            // compatible database. Preserve strict order for the first 32, then
            // require exactly the known tail IDs without assuming their order.
            if ((i < 32 && !TABLE_IDS[i].equals(code)) ||
                (i >= 32 && !Arrays.asList(TABLE_IDS).subList(32, TABLE_IDS.length).contains(code)) ||
                !found.add(code) || offset <= previous || offset >= total - 336)
                throw new IOException("Неверная структура таблиц состава");
            previous = offset;
        }
    }
    private static int be32(byte[] bytes, int at) {
        return ((bytes[at]&255)<<24) | ((bytes[at+1]&255)<<16) | ((bytes[at+2]&255)<<8) | (bytes[at+3]&255);
    }
    static List<String> profiles(File root) {
        File user = new File(root, "user"); File[] dirs = user.listFiles();
        List<String> result = new ArrayList<>();
        if (dirs != null) for (File dir : dirs)
            if (dir.isDirectory() && dir.getName().matches("[0-9A-Fa-f]{16}")
                && new File(dir, TITLE).isDirectory()) result.add(dir.getName());
        Collections.sort(result); return result;
    }
    private static void validProfile(File root, String profile) throws IOException {
        if (profile == null || !profile.matches("[0-9A-Fa-f]{16}") || !profiles(root).contains(profile))
            throw new IOException("Профиль игры не найден");
    }
    private static File saves(File root, String profile) { return new File(root, "user/" + profile + "/" + TITLE + "/00000001"); }
    private static File headers(File root, String profile) { return new File(root, "user/" + profile + "/" + TITLE + "/Headers/00000001"); }
    private static File records(File root, String profile) { return new File(root, "roster-imports/" + profile); }
    private static File archived(File root, String profile) { return new File(root, "roster-imports/archive/" + profile); }
    private static void mkdir(File dir) throws IOException { if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Не удалось создать папку состава"); }
    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
    }
    private static byte[] header(String profile, String name, String label) throws IOException {
        byte[] result = new byte[328]; result[3] = 1; result[7] = 1;
        byte[] title = label.getBytes("UTF-16BE"), filename = name.getBytes(StandardCharsets.US_ASCII);
        if (title.length > 254 || filename.length > 47) throw new IOException("Название состава слишком длинное");
        System.arraycopy(title, 0, result, 8, title.length);
        System.arraycopy(filename, 0, result, 264, filename.length);
        for (int i = 0; i < 8; i++) result[312+i] = (byte)Integer.parseInt(profile.substring(2*i, 2*i+2), 16);
        byte[] game = {0x45, 0x41, 0x09, (byte)0xEC}; System.arraycopy(game, 0, result, 320, 4);
        return result;
    }
    private static Map<String, Properties> managedRecords(File root, String profile) throws IOException {
        Map<String, Properties> result = new TreeMap<>();
        File[] files = records(root, profile).listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.isFile() || !file.getName().matches("ROSTER [0-9]{14}(-[0-9]+)?\\.properties")) continue;
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(file)) { p.load(in); }
            String name = p.getProperty("name", "");
            if (file.getName().equals(name + ".properties")
                && profile.equals(p.getProperty("profile"))
                && p.getProperty("sha256", "").matches("[0-9a-f]{64}")) result.put(name, p);
        }
        return result;
    }
    private static String slot(File root, String profile, Prepared prepared,
                               Map<String, Properties> existing) {
        String selected = null; long newest = Long.MIN_VALUE;
        for (Map.Entry<String, Properties> entry : existing.entrySet()) {
            String name = entry.getKey();
            if (!new File(saves(root, profile), name + "/" + name).isFile()) continue;
            long installed;
            try { installed = Long.parseLong(entry.getValue().getProperty("installedAtMs", "0")); }
            catch (NumberFormatException error) { installed = 0; }
            if (installed > newest || (installed == newest && (selected == null || name.compareTo(selected) > 0))) {
                selected = name; newest = installed;
            }
        }
        if (selected != null) return selected;
        String base = prepared.source.matches("ROSTER [0-9]{14}") ? prepared.source
            : "ROSTER " + new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
        String name = base;
        for (int suffix = 1;
             new File(saves(root, profile), name).exists()
                 || new File(headers(root, profile), name + ".header").exists()
                 || new File(records(root, profile), name + ".properties").exists();
             suffix++) name = base + "-" + suffix;
        return name;
    }
    private static void moveReplace(File from, File to) throws IOException {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static void removable(File directory, String name) throws IOException {
        if (!directory.exists()) return;
        File[] members = directory.listFiles();
        if (members == null || members.length != 1 || !members[0].isFile() || !members[0].getName().equals(name))
            throw new IOException("Старый состав содержит дополнительные файлы: " + name);
    }
    private static void deleteSlot(File directory, String name) throws IOException {
        if (!directory.exists()) return;
        File payload = new File(directory, name);
        if (!payload.delete() || !directory.delete()) throw new IOException("Не удалось удалить прежний состав: " + name);
    }
    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Не удалось удалить: " + file.getName());
    }
    /** Replaces only launcher-managed roster saves; profile and season saves remain untouched. */
    static String replace(File root, String profile, Prepared prepared) throws Exception {
        validProfile(root, profile);
        File lockFile = new File(root, ".game-import.lock");
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw"); FileChannel channel = raf.getChannel()) {
            FileLock lock = channel.tryLock(); if (lock == null) throw new IOException("Идёт другая операция с файлами игры");
            try {
                mkdir(saves(root, profile)); mkdir(headers(root, profile)); mkdir(records(root, profile));
                Map<String, Properties> old = managedRecords(root, profile);
                String name = slot(root, profile, prepared, old);
                // Refuse to remove unexpected user files before committing the new roster.
                for (String previous : old.keySet()) if (!previous.equals(name)) {
                    removable(new File(saves(root, profile), previous), previous);
                    removable(new File(archived(root, profile), previous), previous);
                }
                removable(new File(archived(root, profile), name), name);
                File targetDir = new File(saves(root, profile), name);
                File payload = new File(targetDir, name);
                File titleHeader = new File(headers(root, profile), name + ".header");
                File record = new File(records(root, profile), name + ".properties");
                String temporaryName = name + "." + Long.toHexString(System.nanoTime());
                File tempPayload = new File(records(root, profile), temporaryName + ".payload.tmp");
                File tempHeader = new File(records(root, profile), temporaryName + ".header.tmp");
                File tempRecord = new File(records(root, profile), temporaryName + ".properties.tmp");
                try {
                    write(tempPayload, prepared.roster);
                    if (!GameDataImporter.hash(tempPayload).equals(prepared.sha256))
                        throw new IOException("Ошибка проверки записанного состава");
                    write(tempHeader, header(profile, name, prepared.label));
                    Properties p = new Properties();
                    p.setProperty("profile", profile); p.setProperty("name", name);
                    p.setProperty("sha256", prepared.sha256); p.setProperty("source", prepared.source);
                    p.setProperty("label", prepared.label); p.setProperty("rosterDate", prepared.date);
                    p.setProperty("installedAtMs", Long.toString(System.currentTimeMillis()));
                    try (FileOutputStream out = new FileOutputStream(tempRecord)) {
                        p.store(out, "NHL Legacy roster import"); out.getFD().sync();
                    }
                    mkdir(targetDir);
                    moveReplace(tempHeader, titleHeader);
                    moveReplace(tempPayload, payload);
                    moveReplace(tempRecord, record);
                    if (!GameDataImporter.hash(payload).equals(prepared.sha256))
                        throw new IOException("Ошибка проверки установленного состава");
                } finally {
                    tempPayload.delete(); tempHeader.delete(); tempRecord.delete();
                }
                // Legacy RC153-155 archives are removed after the new save is readable.
                // There is no persistent backup and no rollback action in the launcher.
                for (String previous : old.keySet()) if (!previous.equals(name)) {
                    deleteSlot(new File(saves(root, profile), previous), previous);
                    deleteFile(new File(headers(root, profile), previous + ".header"));
                    deleteSlot(new File(archived(root, profile), previous), previous);
                    deleteFile(new File(archived(root, profile), previous + ".header"));
                    deleteFile(new File(records(root, profile), previous + ".properties"));
                }
                deleteSlot(new File(archived(root, profile), name), name);
                deleteFile(new File(archived(root, profile), name + ".header"));
                return name;
            } finally { lock.release(); }
        }
    }
}
