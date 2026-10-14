package org.nhllegacy.port;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only Xbox 360 XDVDFS reader. The selected ISO stays in the document provider. */
final class XdvdfsIso {
    private static final int SECTOR = 2048;
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    private static final long[] BASES = {0, 0x2080000L, 0x18300000L};
    private final FileChannel channel;
    private final long length;
    private final long base;
    private final Entry root;
    private final Map<String, List<Entry>> directories = new HashMap<>();

    static final class Entry {
        final String name;
        final long sector;
        final long size;
        final int attributes;
        Entry(String name, long sector, long size, int attributes) {
            this.name = name; this.sector = sector; this.size = size; this.attributes = attributes;
        }
        boolean isDirectory() { return (attributes & 0x10) != 0; }
    }

    XdvdfsIso(FileChannel channel) throws IOException {
        this.channel = channel;
        length = channel.size();
        if (length < 40L * SECTOR)
            throw new IOException("ISO недоступен для произвольного чтения или слишком мал. Выберите локальный файл.");
        long chosen = -1;
        for (long candidate : BASES) {
            if (hasDescriptor(candidate)) { chosen = candidate; break; }
        }
        if (chosen < 0) {
            byte[] buffer = new byte[1024 * 1024];
            long limit = Math.min(length, 64L * 1024 * 1024);
            for (long offset = 0; offset < limit && chosen < 0; offset += buffer.length) {
                int size = (int)Math.min(buffer.length, limit - offset);
                readAt(offset, buffer, size);
                for (int i = 0; i + MAGIC.length <= size; i += SECTOR) {
                    if (offset + i < 32L * SECTOR || !matches(buffer, i)) continue;
                    long candidate = offset + i - 32L * SECTOR;
                    if (hasDescriptor(candidate)) { chosen = candidate; break; }
                }
            }
        }
        if (chosen < 0) throw new IOException("Не найдена игровая раздела XDVDFS в ISO Xbox 360.");
        base = chosen;
        byte[] descriptor = new byte[28];
        readAt(base + 32L * SECTOR, descriptor, descriptor.length);
        long sector = u32(descriptor, 20), size = u32(descriptor, 24);
        root = new Entry("", sector, size, 0x10);
        directory(root); // Verify the directory before changing installed game files.
    }

    long imageBytes() { return length; }
    long partitionBase() { return base; }

    private boolean hasDescriptor(long candidate) throws IOException {
        long offset = candidate + 32L * SECTOR;
        if (candidate < 0 || offset < 0 || offset + MAGIC.length > length) return false;
        byte[] data = new byte[MAGIC.length];
        readAt(offset, data, data.length);
        return matches(data, 0);
    }

    private static boolean matches(byte[] data, int at) {
        for (int i = 0; i < MAGIC.length; i++) if (data[at + i] != MAGIC[i]) return false;
        return true;
    }

    private static int u16(byte[] data, int at) {
        return (data[at] & 255) | ((data[at + 1] & 255) << 8);
    }

    private static long u32(byte[] data, int at) {
        return Integer.toUnsignedLong((data[at] & 255) | ((data[at + 1] & 255) << 8)
            | ((data[at + 2] & 255) << 16) | ((data[at + 3] & 255) << 24));
    }

    private long offset(Entry entry) throws IOException {
        long at;
        try { at = Math.addExact(base, Math.multiplyExact(entry.sector, (long)SECTOR)); }
        catch (ArithmeticException error) { throw new IOException("Неверный адрес файла в ISO", error); }
        if (at < 0 || at > length || entry.size > length - at)
            throw new IOException("Файл выходит за пределы ISO: " + entry.name);
        return at;
    }

    private List<Entry> directory(Entry entry) throws IOException {
        if (!entry.isDirectory() || entry.size <= 0 || entry.size > 16L * 1024 * 1024)
            throw new IOException("Неверный каталог XDVDFS: " + entry.name);
        String key = entry.sector + ":" + entry.size;
        List<Entry> cached = directories.get(key);
        if (cached != null) return cached;
        byte[] data = new byte[(int)entry.size];
        readAt(offset(entry), data, data.length);
        List<Entry> found = new ArrayList<>();
        for (int block = 0; block < data.length; block += SECTOR) {
            int end = Math.min(data.length, block + SECTOR), at = block;
            while (at + 14 <= end) {
                int left = u16(data, at), right = u16(data, at + 2);
                int nameLength = data[at + 13] & 255;
                if ((left == 0xffff && right == 0xffff) || nameLength == 0) break;
                if (at + 14 + nameLength > end) throw new IOException("Повреждён каталог XDVDFS");
                for (int i = at + 14; i < at + 14 + nameLength; i++)
                    if (data[i] < 32 || data[i] > 126) throw new IOException("Недопустимое имя в ISO");
                String name = new String(data, at + 14, nameLength, StandardCharsets.US_ASCII);
                Entry child = new Entry(name, u32(data, at + 4), u32(data, at + 8), data[at + 12] & 255);
                offset(child);
                found.add(child);
                at += (14 + nameLength + 3) & ~3;
            }
        }
        directories.put(key, found);
        return found;
    }

    Entry requireFile(String path) throws IOException {
        Entry parent = root;
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].equals(".") || parts[i].equals(".."))
                throw new IOException("Недопустимый путь в таблице файлов: " + path);
            Entry match = null;
            for (Entry entry : directory(parent)) {
                if (!entry.name.equalsIgnoreCase(parts[i])) continue;
                if (match != null) throw new IOException("Неоднозначное имя в ISO: " + path);
                match = entry;
            }
            if (match == null) throw new IOException("В ISO нет игрового файла: " + path);
            if (i + 1 < parts.length && !match.isDirectory())
                throw new IOException("Ожидался каталог в ISO: " + path);
            parent = match;
        }
        if (parent.isDirectory()) throw new IOException("Ожидался файл в ISO: " + path);
        return parent;
    }

    void readFile(Entry entry, long fileOffset, byte[] buffer, int size) throws IOException {
        if (fileOffset < 0 || size < 0 || fileOffset > entry.size || size > entry.size - fileOffset)
            throw new IOException("Чтение за пределами игрового файла: " + entry.name);
        readAt(offset(entry) + fileOffset, buffer, size);
    }

    private void readAt(long at, byte[] buffer, int size) throws IOException {
        if (at < 0 || size < 0 || at > length || size > length - at)
            throw new IOException("Чтение за пределами ISO");
        ByteBuffer target = ByteBuffer.wrap(buffer, 0, size);
        while (target.hasRemaining()) {
            int count = channel.read(target, at + target.position());
            if (count <= 0) throw new IOException("Не удалось дочитать ISO из выбранного файла");
        }
    }
}
