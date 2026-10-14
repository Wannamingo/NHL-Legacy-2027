package org.nhllegacy.port;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;

/** Host-only end-to-end check of the exact reader included in the Android APK. */
public final class IsoReaderProbe {
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 255));
        return text.toString();
    }

    public static void main(String[] args) throws Exception {
        List<String> rows = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
        try (FileInputStream stream = new FileInputStream(args[0])) {
            XdvdfsIso iso = new XdvdfsIso(stream.getChannel());
            if (iso.partitionBase() != 0x2080000L) throw new AssertionError("Unexpected partition base");
            long total = 0;
            for (String row : rows) {
                String[] columns = row.split("\t");
                XdvdfsIso.Entry entry = iso.requireFile(columns[0]);
                if (entry.size != Long.parseLong(columns[1])) throw new AssertionError("Size " + columns[0]);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[1024 * 1024];
                for (long offset = 0; offset < entry.size;) {
                    int count = (int)Math.min(buffer.length, entry.size - offset);
                    iso.readFile(entry, offset, buffer, count);
                    digest.update(buffer, 0, count);
                    offset += count;
                }
                if (!hex(digest.digest()).equals(columns[2])) throw new AssertionError("Hash " + columns[0]);
                total += entry.size;
            }
            System.out.println("PASS: " + rows.size() + " files, " + total + " bytes, partition 0x" +
                Long.toHexString(iso.partitionBase()));
        }
    }
}
