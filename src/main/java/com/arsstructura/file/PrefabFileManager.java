package com.arsstructura.file;

import com.arsstructura.data.SafeNbt;
import com.arsstructura.data.StructureData;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Client-local files only. Never load this class on the dedicated server. */
public final class PrefabFileManager {
    public enum Format {
        NBT(".nbt"), LITEMATIC(".litematic");
        public final String extension;
        Format(String extension) { this.extension = extension; }
    }

    public record Entry(String fileName, String name, int sizeX, int sizeY, int sizeZ,
                        long createdAt, String author, String modVersion, String error) {
        public boolean valid() { return error == null; }
    }

    // A second task is rejected rather than accumulating arbitrarily many file/structure buffers.
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Ars Structura prefab files");
        thread.setDaemon(true);
        return thread;
    });
    private static final int LIST_LIMIT = 512;

    private PrefabFileManager() {}

    public static boolean busy() { return BUSY.get(); }

    public static <T> boolean async(Callable<T> task, Consumer<T> success, Consumer<Throwable> failure) {
        if (!BUSY.compareAndSet(false, true)) return false;
        IO.execute(() -> {
            T result = null;
            Throwable problem = null;
            try { result = task.call(); }
            catch (Exception e) { problem = e; }
            finally { BUSY.set(false); }
            T value = result;
            Throwable error = problem;
            Minecraft.getInstance().execute(() -> {
                if (error == null) success.accept(value);
                else failure.accept(error);
            });
        });
        return true;
    }

    public static List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory())) {
            int visited = 0;
            for (Path file : files) {
                if (++visited > 4096) throw new IOException("prefab_list_limit");
                String fileName = file.getFileName().toString();
                if (!hasFormat(fileName)) continue;
                if (entries.size() >= LIST_LIMIT) throw new IOException("prefab_list_limit");
                try {
                    StructureData data = read(fileName);
                    entries.add(new Entry(fileName, data.name(), data.sizeX(), data.sizeY(), data.sizeZ(),
                            data.createdAt(), data.author(), data.modVersion(), null));
                } catch (Exception ex) {
                    entries.add(new Entry(fileName, fileName, 0, 0, 0, 0, "", "", safeError(ex)));
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::fileName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    public static StructureData read(String fileName) throws Exception {
        Path file = existingFile(fileName);
        int limit = SafeNbt.compressedLimit();
        long size = Files.size(file);
        if (size <= 0 || size > limit) throw new IOException("prefab_file_size");
        byte[] bytes;
        try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(limit + 1);
        }
        if (bytes.length == 0 || bytes.length > limit) throw new IOException("prefab_file_size");
        var tag = SafeNbt.decompress(bytes);
        StructureData data = format(fileName) == Format.LITEMATIC ? LitematicCodec.decode(tag) : StructureData.fromNbt(tag);
        data.validate();
        return data;
    }

    public static String save(String name, Format format, StructureData data) throws Exception {
        String checkedName = validateName(name);
        data.validate();
        StructureData named = new StructureData(data.sizeX(), data.sizeY(), data.sizeZ(), data.blocks(),
                checkedName, data.author(), data.createdAt(), data.modVersion());
        byte[] bytes = SafeNbt.compress(format == Format.LITEMATIC ? LitematicCodec.encode(named) : named.toNbt());
        if (bytes.length == 0 || bytes.length > SafeNbt.compressedLimit()) throw new IOException("prefab_file_size");
        Path root = directory();
        Path target = root.resolve(checkedName + format.extension);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("prefab_file_exists");
        Path temporary = Files.createTempFile(root, "tablet-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            // The no-replace move also protects an existing file when a name is typed twice.
            Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target.getFileName().toString();
    }

    public static String rename(String fileName, String name) throws Exception {
        Path source = existingFile(fileName);
        Format format = format(fileName);
        String destination = validateName(name) + format.extension;
        if (destination.equals(fileName)) return destination;
        StructureData data = read(fileName);
        // Save first: a failed encode/write never destroys the original prefab.
        String saved = save(name, format, data);
        Files.delete(source);
        return saved;
    }

    public static void delete(String fileName) throws IOException {
        Files.delete(existingFile(fileName));
    }

    public static String validateName(String name) throws IOException {
        String value = name.strip();
        if (value.isEmpty() || value.length() > 64 || !value.matches("[\\p{L}\\p{N}_ -]+")
                || value.endsWith(" ") || value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
            throw new IOException("prefab_name_invalid");
        return value;
    }

    public static String safeError(Throwable error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        value = value.replaceAll("[\\p{Cntrl}]", " ");
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    private static boolean hasFormat(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".nbt") || lower.endsWith(".litematic");
    }

    private static Format format(String fileName) throws IOException {
        if (!hasFormat(fileName)) throw new IOException("prefab_file_type");
        return fileName.toLowerCase(Locale.ROOT).endsWith(".litematic") ? Format.LITEMATIC : Format.NBT;
    }

    private static Path existingFile(String fileName) throws IOException {
        format(fileName);
        // External Litematica names may contain punctuation, but never separators/alternate streams.
        if (fileName.isBlank() || fileName.length() > 255 || fileName.contains("/")
                || fileName.contains("\\") || fileName.contains(":") || fileName.indexOf('\0') >= 0)
            throw new IOException("prefab_name_invalid");
        Path root = directory();
        Path file = root.resolve(fileName).normalize();
        if (!file.getParent().equals(root) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("prefab_path_invalid");
        if (!file.toRealPath().getParent().equals(root.toRealPath())) throw new IOException("prefab_path_invalid");
        return file;
    }

    private static Path directory() throws IOException {
        Path root = FMLPaths.CONFIGDIR.get().resolve("ars_construction_tablet").resolve("prefabs")
                .toAbsolutePath().normalize();
        for (Path path = root; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("prefab_path_invalid");
        }
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("prefab_path_invalid");
        return root;
    }
}
