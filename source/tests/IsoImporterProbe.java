package org.nhllegacy.port;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Host-only transactional import checks with a small synthetic XDVDFS ISO. */
public final class IsoImporterProbe {
    private static final int SECTOR = 2048;
    private static void u32(byte[] data, int at, int value) {
        for (int i = 0; i < 4; i++) data[at + i] = (byte)(value >>> (i * 8));
    }
    private static void put(File image, byte[] payload) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(image, "rw")) {
            file.setLength(42L * SECTOR);
            byte[] descriptor = new byte[SECTOR];
            byte[] magic = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(magic, 0, descriptor, 0, magic.length);
            u32(descriptor, 20, 40); u32(descriptor, 24, SECTOR);
            file.seek(32L * SECTOR); file.write(descriptor);
            byte[] directory = new byte[SECTOR];
            byte[] name = "default.xex".getBytes(StandardCharsets.US_ASCII);
            u32(directory, 4, 41); u32(directory, 8, payload.length);
            directory[13] = (byte)name.length;
            System.arraycopy(name, 0, directory, 14, name.length);
            file.seek(40L * SECTOR); file.write(directory);
            file.seek(41L * SECTOR); file.write(payload);
        }
    }
    private static Map<String, GameDataImporter.Expected> table(byte[] payload, boolean wrongHash) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        Map<String, GameDataImporter.Expected> expected = new HashMap<>();
        expected.put("default.xex", new GameDataImporter.Expected(payload.length,
            wrongHash ? "0000000000000000000000000000000000000000000000000000000000000000"
                      : GameDataImporter.hex(digest)));
        return expected;
    }
    private static void run(File root, File image, Map<String, GameDataImporter.Expected> expected,
                            boolean cancelled) throws Exception {
        try (FileInputStream source = new FileInputStream(image)) {
            GameDataImporter.installIso(root, source.getChannel(), expected,
                "0000000000000000000000000000000000000000000000000000000000000000",
                (bytes, files) -> {}, () -> cancelled);
        }
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        File game = new File(root, "game"); game.mkdirs();
        File original = new File(game, "default.xex");
        Files.write(original.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        File cache = new File(game, "_android_cache"); cache.mkdirs();
        Files.write(new File(cache, "keep").toPath(), new byte[]{42});
        File save = new File(root, "user-save"); Files.write(save.toPath(), new byte[]{7});
        byte[] payload = "iso-payload".getBytes(StandardCharsets.UTF_8);
        File image = new File(root, "test.iso"); put(image, payload);
        try {
            run(root, image, table(payload, true), false);
            throw new AssertionError("Corrupt manifest was accepted");
        } catch (java.io.IOException expected) { }
        if (!new String(Files.readAllBytes(original.toPath()), StandardCharsets.UTF_8).equals("original"))
            throw new AssertionError("Original game changed on failed import");
        if (new File(root, "game.importing").exists()) throw new AssertionError("Staging leaked");
        try {
            run(root, image, table(payload, false), true);
            throw new AssertionError("Cancelled import was accepted");
        } catch (java.io.IOException expected) { }
        if (!new String(Files.readAllBytes(original.toPath()), StandardCharsets.UTF_8).equals("original"))
            throw new AssertionError("Original game changed on cancellation");
        run(root, image, table(payload, false), false);
        if (!java.util.Arrays.equals(Files.readAllBytes(original.toPath()), payload))
            throw new AssertionError("New game missing");
        if (Files.readAllBytes(new File(cache, "keep").toPath())[0] != 42)
            throw new AssertionError("Cache lost");
        if (Files.readAllBytes(save.toPath())[0] != 7) throw new AssertionError("Save lost");
        if (new File(root, "game.previous-import").exists()) throw new AssertionError("Backup leaked");
        System.out.println("PASS: failed hash, cancellation, commit, cache and save preservation");
    }
}
