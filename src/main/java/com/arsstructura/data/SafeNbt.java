package com.arsstructura.data;

import com.arsstructura.config.ModConfig;
import net.minecraft.nbt.*;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Bounded gzip/NBT transport. Never uses the vanilla unlimited compressed reader. */
public final class SafeNbt {
    public static final int HARD_COMPRESSED_LIMIT = 1024 * 1024;

    private SafeNbt() {}

    public static int compressedLimit() {
        return Math.min(HARD_COMPRESSED_LIMIT, ModConfig.MAX_COMPRESSED_BYTES.get());
    }

    public static byte[] compress(CompoundTag tag) {
        validateTree(tag);
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new LimitedOutput(raw, ModConfig.MAX_NBT_BYTES.get()))) {
                NbtIo.write(tag, out);
            }
            byte[] bytes = raw.toByteArray();
            // Apply exactly the same allocation quota to locally generated and received data.
            NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), new NbtAccounter(ModConfig.MAX_NBT_BYTES.get()));
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(new LimitedOutput(compressed, compressedLimit()))) {
                gzip.write(bytes);
            }
            return compressed.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("NBT exceeds limits or cannot be encoded", e);
        }
    }

    public static CompoundTag decompress(byte[] compressed) {
        if (compressed == null || compressed.length == 0 || compressed.length > compressedLimit()) {
            throw new IllegalArgumentException("Compressed file exceeds the allowed size");
        }
        try {
            int limit = ModConfig.MAX_NBT_BYTES.get();
            byte[] raw;
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                raw = gzip.readNBytes(limit + 1);
                if (raw.length > limit) throw new IOException("Uncompressed NBT exceeds limit");
            }
            // Inspect the wire tree before Minecraft allocates lists/arrays or recursively loads it.
            preflight(raw, ModConfig.MAX_NBT_DEPTH.get());
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)), new NbtAccounter(limit));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Corrupt, oversized, or excessively nested NBT", e);
        }
    }

    public static void validateTree(Tag tag) {
        estimatedBytes(tag);
    }

    /** Conservative allocation estimate, performed before copying or serializing captured mod data. */
    public static long estimatedBytes(Tag tag) {
        long[] used = {0};
        estimate(tag, 0, used);
        return used[0];
    }

    private static void estimate(Tag tag, int depth, long[] used) {
        if (tag == null || depth > ModConfig.MAX_NBT_DEPTH.get()) {
            throw new IllegalArgumentException("NBT tree exceeds configured limits");
        }
        if (tag instanceof CompoundTag compound) {
            account(64L + 48L * compound.size(), used);
            for (String key : compound.getAllKeys()) {
                account(2L * key.length(), used);
                estimate(compound.get(key), depth + 1, used);
            }
        } else if (tag instanceof ListTag list) {
            account(48L + 8L * list.size(), used);
            for (Tag entry : list) estimate(entry, depth + 1, used);
        } else if (tag instanceof ByteArrayTag array) account(32L + array.size(), used);
        else if (tag instanceof IntArrayTag array) account(32L + 4L * array.size(), used);
        else if (tag instanceof LongArrayTag array) account(32L + 8L * array.size(), used);
        else if (tag instanceof StringTag string) account(48L + 2L * string.getAsString().length(), used);
        else account(32L, used);
    }

    private static void account(long bytes, long[] used) {
        used[0] += bytes;
        if (used[0] > ModConfig.MAX_NBT_BYTES.get()) throw new IllegalArgumentException("NBT allocation limit exceeded");
    }

    static void preflight(byte[] raw, int maxDepth) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        if (in.readUnsignedByte() != Tag.TAG_COMPOUND) throw new IOException("Root must be a compound");
        skipString(in);
        // Byte-valued lists legitimately use only one byte per element.
        scan(in, Tag.TAG_COMPOUND, 0, maxDepth, new long[]{raw.length + 1L});
        if (in.available() != 0) throw new IOException("Trailing NBT data");
    }

    private static void scan(DataInputStream in, int type, int depth, int maxDepth, long[] budget) throws IOException {
        if (depth > maxDepth || --budget[0] < 0) throw new IOException("NBT nesting/node limit exceeded");
        switch (type) {
            case Tag.TAG_BYTE -> skip(in, 1);
            case Tag.TAG_SHORT -> skip(in, 2);
            case Tag.TAG_INT, Tag.TAG_FLOAT -> skip(in, 4);
            case Tag.TAG_LONG, Tag.TAG_DOUBLE -> skip(in, 8);
            case Tag.TAG_STRING -> skipString(in);
            case Tag.TAG_BYTE_ARRAY, Tag.TAG_INT_ARRAY, Tag.TAG_LONG_ARRAY -> {
                int length = in.readInt();
                int width = type == Tag.TAG_BYTE_ARRAY ? 1 : type == Tag.TAG_INT_ARRAY ? 4 : 8;
                if (length < 0) throw new IOException("Negative array length");
                skip(in, (long) length * width);
            }
            case Tag.TAG_LIST -> {
                int childType = in.readUnsignedByte();
                int length = in.readInt();
                if (length < 0 || childType > Tag.TAG_LONG_ARRAY || (childType == 0 && length != 0)
                        || length > budget[0]) throw new IOException("Invalid list");
                for (int i = 0; i < length; i++) scan(in, childType, depth + 1, maxDepth, budget);
            }
            case Tag.TAG_COMPOUND -> {
                int childType;
                while ((childType = in.readUnsignedByte()) != Tag.TAG_END) {
                    skipString(in);
                    scan(in, childType, depth + 1, maxDepth, budget);
                }
            }
            default -> throw new IOException("Unknown NBT type " + type);
        }
    }

    private static void skipString(DataInputStream in) throws IOException {
        skip(in, in.readUnsignedShort());
    }

    private static void skip(DataInputStream in, long length) throws IOException {
        if (length < 0 || length > in.available()) throw new EOFException("Truncated NBT payload");
        in.skipNBytes(length);
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private final long limit;
        private long written;

        private LimitedOutput(OutputStream out, long limit) { super(out); this.limit = limit; }

        private void reserve(int count) throws IOException {
            if (count < 0 || written + count > limit) throw new IOException("NBT byte limit exceeded");
            written += count;
        }

        @Override public void write(int b) throws IOException { reserve(1); out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { reserve(len); out.write(b, off, len); }
    }
}
