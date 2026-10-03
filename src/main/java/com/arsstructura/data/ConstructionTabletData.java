package com.arsstructura.data;

import com.arsstructura.server.TabletStructureBackups;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/** All mutations are called by server handlers; read methods never create item tags. */
public final class ConstructionTabletData {
    private static final String ROOT = "ArsStructura";

    private ConstructionTabletData() {}

    private static CompoundTag read(ItemStack stack) {
        CompoundTag item = stack.getTag();
        return item != null && item.contains(ROOT, Tag.TAG_COMPOUND) ? item.getCompound(ROOT) : new CompoundTag();
    }

    private static void write(ItemStack stack, CompoundTag data) {
        if (!data.hasUUID("Id")) data.putUUID("Id", UUID.randomUUID());
        data.putLong("Revision", Math.max(0L, data.getLong("Revision")) + 1L);
        stack.getOrCreateTag().put(ROOT, data);
    }

    public static CompoundTag metadata(ItemStack stack) {
        CompoundTag data = read(stack);
        CompoundTag metadata = new CompoundTag();
        for (String key : data.getAllKeys()) {
            if (!"Structure".equals(key)) metadata.put(key, data.get(key).copy());
        }
        metadata.putBoolean("HasStructure", data.contains("Structure", Tag.TAG_BYTE_ARRAY) || data.getBoolean("HasStructure"));
        return metadata;
    }

    public static @Nullable UUID id(ItemStack stack) {
        CompoundTag data = read(stack);
        return data.hasUUID("Id") ? data.getUUID("Id") : null;
    }

    public static UUID ensureId(ItemStack stack) {
        UUID existing = id(stack);
        if (existing != null) return existing;
        CompoundTag data = read(stack).copy();
        write(stack, data);
        return data.getUUID("Id");
    }

    public static long revision(ItemStack stack) { return read(stack).getLong("Revision"); }

    /** Copy for bounded network forwarding; the stored server payload is already canonical. */
    public static @Nullable byte[] encodedStructure(ItemStack stack) {
        CompoundTag data = read(stack);
        if (!data.contains("Structure", Tag.TAG_BYTE_ARRAY)) return null;
        byte[] bytes = data.getByteArray("Structure");
        if (bytes.length == 0 || bytes.length > SafeNbt.compressedLimit())
            throw new IllegalArgumentException("Stored structure exceeds compressed byte limit");
        return bytes.clone();
    }

    public static @Nullable StructureData structure(ItemStack stack) {
        CompoundTag data = read(stack);
        return data.contains("Structure", Tag.TAG_BYTE_ARRAY)
                ? StructureData.fromNbt(SafeNbt.decompress(data.getByteArray("Structure"))) : null;
    }

    public static void setStructure(ItemStack stack, StructureData structure) {
        structure.validate();
        byte[] encoded = SafeNbt.compress(structure.toNbt());
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) throw new IllegalStateException("Item structures must be written on the server thread");
        // Archive first: quota failure must leave the existing item and its revision unchanged.
        String key = server == null ? "" : TabletStructureBackups.get(server).archive(encoded);
        CompoundTag data = read(stack).copy();
        data.putByteArray("Structure", encoded);
        if (!key.isEmpty()) data.putString("StructureKey", key);
        else data.remove("StructureKey");
        data.putInt("SizeX", structure.sizeX());
        data.putInt("SizeY", structure.sizeY());
        data.putInt("SizeZ", structure.sizeZ());
        data.putInt("BlockCount", structure.nonAirCount());
        data.putString("Name", structure.name());
        data.putString("Author", structure.author());
        data.putLong("CreatedAt", structure.createdAt());
        data.putString("ModVersion", structure.modVersion());
        data.putBoolean("HasStructure", true);
        data.putBoolean("Preview", false);
        write(stack, data);
    }

    /** Restores hidden data after a creative inventory round trip; visible metadata/revision are unchanged. */
    public static void restoreEncodedFromBackup(ItemStack stack, byte[] encoded) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Backups can only be restored by the server");
        if (encoded.length == 0 || encoded.length > SafeNbt.compressedLimit()) throw new IllegalArgumentException("Invalid backup size");
        CompoundTag data = read(stack);
        if (!data.contains("Structure", Tag.TAG_BYTE_ARRAY)) data.putByteArray("Structure", encoded);
    }

    public static void clear(ItemStack stack) {
        CompoundTag old = read(stack);
        CompoundTag data = new CompoundTag();
        if (old.hasUUID("Id")) data.putUUID("Id", old.getUUID("Id"));
        data.putLong("Revision", old.getLong("Revision"));
        write(stack, data);
    }

    public static void bind(ItemStack stack, boolean first, BlockPos pos, String dimension) {
        validateDimension(dimension);
        CompoundTag data = read(stack).copy();
        String previous = data.getString("SelectionDimension");
        if (!previous.isEmpty() && !previous.equals(dimension)
                && data.contains(first ? "Point2" : "Point1", Tag.TAG_LONG))
            throw new IllegalArgumentException("Selection points must be in the same dimension; clear the tablet first");
        data.putLong(first ? "Point1" : "Point2", pos.asLong());
        data.putString("SelectionDimension", dimension);
        data.putBoolean("Preview", false);
        write(stack, data);
    }

    public static @Nullable BlockPos point(ItemStack stack, boolean first) {
        CompoundTag data = read(stack);
        String key = first ? "Point1" : "Point2";
        return data.contains(key, Tag.TAG_LONG) ? BlockPos.of(data.getLong(key)) : null;
    }

    public static String selectionDimension(ItemStack stack) { return read(stack).getString("SelectionDimension"); }

    public static void anchor(ItemStack stack, BlockPos pos, String dimension) {
        validateDimension(dimension);
        CompoundTag data = read(stack).copy();
        data.putLong("Anchor", pos.asLong());
        data.putString("AnchorDimension", dimension);
        write(stack, data);
    }

    public static @Nullable BlockPos anchor(ItemStack stack) {
        CompoundTag data = read(stack);
        return data.contains("Anchor", Tag.TAG_LONG) ? BlockPos.of(data.getLong("Anchor")) : null;
    }

    public static String anchorDimension(ItemStack stack) { return read(stack).getString("AnchorDimension"); }

    public static void setPreview(ItemStack stack, boolean enabled) {
        CompoundTag data = read(stack).copy();
        if (enabled && (!data.contains("Structure", Tag.TAG_BYTE_ARRAY) || !data.contains("Anchor", Tag.TAG_LONG)))
            throw new IllegalArgumentException("A saved structure and target anchor are required");
        data.putBoolean("Preview", enabled);
        write(stack, data);
    }

    public static boolean preview(ItemStack stack) { return read(stack).getBoolean("Preview"); }

    private static void validateDimension(String dimension) {
        if (dimension == null || dimension.length() > 256 || ResourceLocation.tryParse(dimension) == null)
            throw new IllegalArgumentException("Invalid dimension identifier");
    }
}
