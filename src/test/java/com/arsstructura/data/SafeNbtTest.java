package com.arsstructura.data;

import com.arsstructura.config.ModConfig;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SafeNbtTest {
    @BeforeAll static void configure() { TestEnvironment.config(); }

    @Test void compressedRoundTrip() {
        CompoundTag original = new CompoundTag();
        original.putString("Name", "测试预制"); original.putLongArray("Bits", new long[]{Long.MIN_VALUE, 3});
        assertEquals(original, SafeNbt.decompress(SafeNbt.compress(original)));
    }

    @Test void preflightRejectsArrayAllocationBombBeforeVanillaParse() throws IOException {
        byte[] payload = raw(out -> { out.writeByte(7); out.writeUTF("bomb"); out.writeInt(Integer.MAX_VALUE); out.writeByte(0); });
        assertThrows(IOException.class, () -> SafeNbt.preflight(payload, 32));
        assertThrows(IllegalArgumentException.class, () -> SafeNbt.decompress(gzip(payload)));
    }

    @Test void preflightEnforcesDepthAndTrailingData() throws IOException {
        byte[] deep = raw(out -> {
            for (int i = 0; i < 40; i++) { out.writeByte(10); out.writeUTF("n"); }
            for (int i = 0; i < 41; i++) out.writeByte(0);
        });
        assertThrows(IOException.class, () -> SafeNbt.preflight(deep, 32));
        byte[] trailing = raw(out -> { out.writeByte(0); out.writeByte(1); });
        assertThrows(IOException.class, () -> SafeNbt.preflight(trailing, 32));
    }

    @Test void preflightRejectsNonEmptyEndTagList() throws IOException {
        byte[] raw = raw(out -> { out.writeByte(9); out.writeUTF("bad"); out.writeByte(0); out.writeInt(100); out.writeByte(0); });
        assertThrows(IOException.class, () -> SafeNbt.preflight(raw, 32));
    }

    @Test void byteValuedListsUseOnlyOneWireBytePerEntry() throws IOException {
        byte[] payload = raw(out -> {
            out.writeByte(9); out.writeUTF("bytes"); out.writeByte(1); out.writeInt(128);
            out.write(new byte[128]); out.writeByte(0);
        });
        assertDoesNotThrow(() -> SafeNbt.preflight(payload, 32));
    }

    @Test void compressedAndAllocationQuotasAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> SafeNbt.decompress(new byte[SafeNbt.HARD_COMPRESSED_LIMIT + 1]));
        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("large", new byte[ModConfig.MAX_NBT_BYTES.get()]);
        assertThrows(IllegalArgumentException.class, () -> SafeNbt.estimatedBytes(oversized));
        assertThrows(IllegalArgumentException.class, () -> SafeNbt.compress(oversized));
    }

    private static byte[] raw(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeByte(10); out.writeUTF(""); writer.write(out); }
        return bytes.toByteArray();
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(raw); }
        return bytes.toByteArray();
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
}
