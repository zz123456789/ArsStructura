package com.arsstructura.server;

import com.arsstructura.ArsStructura;
import com.arsstructura.common.ConstructionTabletItem;
import com.arsstructura.config.ModConfig;
import com.arsstructura.data.ConstructionTabletData;
import com.arsstructura.data.SafeNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, immutable server backups for creative inventory packets that contain only item metadata. */
public final class TabletStructureBackups extends SavedData {
    public static final String STORAGE_NAME = "arsstructura_tablet_structures";
    private static final long HARD_BYTES = 512L * 1024 * 1024;
    private static final int HARD_COUNT = 65536;
    private final Map<String, byte[]> blobs = new LinkedHashMap<>();
    private long usedBytes;

    public static TabletStructureBackups get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TabletStructureBackups::load,
                TabletStructureBackups::new, STORAGE_NAME);
    }

    /** Caller has already parsed/validated canonical structure data, before writing the item. */
    public String archive(byte[] encoded) {
        requireBlobSize(encoded);
        String key = keyOf(encoded);
        if (blobs.containsKey(key)) return key;
        if (blobs.size() >= countLimit() || encoded.length > byteLimit() - usedBytes) {
            throw new IllegalArgumentException("Structure backup archive is full; increase maxBackupBytes/maxBackupStructures or reuse an existing prefab.");
        }
        blobs.put(key, encoded.clone());
        usedBytes += encoded.length;
        setDirty();
        return key;
    }

    /** Defensive copies prevent ItemStack mutation from corrupting a shared immutable backup. */
    public @Nullable byte[] copy(String key) {
        if (!validKey(key)) return null;
        byte[] value = blobs.get(key);
        if (value == null || value.length > SafeNbt.compressedLimit()) return null;
        return value.clone();
    }

    /** May run each inventory tick: an already complete item is checked without copying its large byte array. */
    public static boolean recover(ItemStack stack) {
        if (!(stack.getItem() instanceof ConstructionTabletItem)) return false;
        CompoundTag item = stack.getTag();
        if (item == null || !item.contains("ArsStructura", Tag.TAG_COMPOUND)) return false;
        CompoundTag data = item.getCompound("ArsStructura");
        if (data.contains("Structure", Tag.TAG_BYTE_ARRAY)) return false;
        String key = data.getString("StructureKey");
        if (!validKey(key)) return false;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return false;
        try {
            byte[] encoded = get(server).copy(key);
            if (encoded == null) return false;
            ConstructionTabletData.restoreEncodedFromBackup(stack, encoded);
            return true;
        } catch (RuntimeException invalidBackup) {
            // Corrupt metadata/config changes must not escape an inventory tick or spam chat every tick.
            // Gameplay requests report an unavailable structure through their existing error handling.
            return false;
        }
    }

    public static TabletStructureBackups load(CompoundTag tag) {
        TabletStructureBackups backups = new TabletStructureBackups();
        CompoundTag entries = tag.getCompound("Blobs");
        int rejected = 0;
        // Deterministic retention if an administrator reduces a quota on an existing world.
        for (String key : entries.getAllKeys().stream().sorted().toList()) {
            if (!validKey(key) || !entries.contains(key, Tag.TAG_BYTE_ARRAY)) { rejected++; continue; }
            byte[] encoded = entries.getByteArray(key);
            if (encoded.length == 0 || encoded.length > SafeNbt.compressedLimit()
                    || backups.blobs.size() >= countLimit() || encoded.length > byteLimit() - backups.usedBytes
                    || !key.equals(keyOf(encoded))) {
                rejected++;
                continue;
            }
            backups.blobs.put(key, encoded.clone());
            backups.usedBytes += encoded.length;
        }
        if (rejected > 0) {
            ArsStructura.LOGGER.warn("Ignored {} corrupt or over-quota tablet structure backups; complete item payloads remain intact", rejected);
        }
        return backups;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag entries = new CompoundTag();
        blobs.forEach((key, bytes) -> entries.putByteArray(key, bytes.clone()));
        tag.put("Blobs", entries);
        return tag;
    }

    static String keyOf(byte[] encoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean validKey(@Nullable String key) {
        if (key == null || key.length() != 64) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    private static void requireBlobSize(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > SafeNbt.compressedLimit()) {
            throw new IllegalArgumentException("Structure backup exceeds the compressed byte limit");
        }
    }

    private static long byteLimit() { return Math.min(HARD_BYTES, ModConfig.MAX_BACKUP_BYTES.get()); }
    private static int countLimit() { return Math.min(HARD_COUNT, ModConfig.MAX_BACKUP_STRUCTURES.get()); }
    long usedBytes() { return usedBytes; }
    int size() { return blobs.size(); }
}
