package org.nhllegacy.port;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Removes only the runtime's disposable Vulkan/shader cache. */
final class GraphicsCache {
    static final class Result {
        final long files;
        final long bytes;
        Result(long files, long bytes) { this.files = files; this.bytes = bytes; }
    }

    private GraphicsCache() { }

    static Result clear(File filesDir) throws IOException {
        Path root = filesDir.toPath().toAbsolutePath().normalize();
        Path game = root.resolve("game");
        Path androidCache = game.resolve("_android_cache");
        Path shaders = androidCache.resolve("shaders");
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root))
            throw new IOException("Папка приложения недоступна");
        try (FileOutputStream stream = new FileOutputStream(root.resolve(".game-import.lock").toFile(), true)) {
            FileLock lock;
            try { lock = stream.getChannel().tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Импорт файлов ещё выполняется", busy); }
            if (lock == null) throw new IOException("Импорт файлов ещё выполняется");
            try (FileLock held = lock) {
                if (Files.isSymbolicLink(game) || Files.isSymbolicLink(androidCache)
                        || Files.isSymbolicLink(shaders))
                    throw new IOException("Путь графического кеша недостоверен");
                if (!Files.exists(shaders)) return new Result(0, 0);
                if (!Files.isDirectory(shaders)) throw new IOException("Путь графического кеша недостоверен");
                Path realRoot = root.toRealPath();
                Path realTarget = shaders.toRealPath();
                if (!realTarget.startsWith(realRoot.resolve("game").resolve("_android_cache")))
                    throw new IOException("Графический кеш вне каталога приложения");
                final long[] counts = new long[2];
                Files.walkFileTree(shaders, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        counts[0]++;
                        counts[1] += attrs.size();
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
                return new Result(counts[0], counts[1]);
            }
        }
    }
}
