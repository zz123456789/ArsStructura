package com.arsstructura.data;

import com.arsstructura.config.ModConfig;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/** Canonical sparse, min-corner-relative structure. Only server callers may store it in an item. */
public record StructureData(int sizeX, int sizeY, int sizeZ, List<BlockEntry> blocks,
                            String name, String author, long createdAt, String modVersion) {
    public static final int FORMAT_VERSION = 1;
    public static final int MINECRAFT_DATA_VERSION = 3465;

    public StructureData {
        if (blocks == null || name == null || author == null || modVersion == null)
            throw new IllegalArgumentException("Missing structure data");
        blocks = List.copyOf(blocks);
    }

    public record BlockEntry(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntity) {
        public BlockEntry {
            if (pos == null || state == null) throw new IllegalArgumentException("Missing block data");
            pos = pos.immutable();
            if (blockEntity != null) {
                SafeNbt.validateTree(blockEntity);
                blockEntity = sanitizeBlockEntity(blockEntity);
                blockEntity.putInt("x", pos.getX());
                blockEntity.putInt("y", pos.getY());
                blockEntity.putInt("z", pos.getZ());
            }
        }

        @Override public @Nullable CompoundTag blockEntity() {
            return blockEntity == null ? null : blockEntity.copy();
        }
    }

    public static void validateSize(int x, int y, int z) {
        int edge = ModConfig.MAX_EDGE.get();
        if (x < 1 || y < 1 || z < 1 || x > edge || y > edge || z > edge
                || (long) x * y * z > ModConfig.MAX_VOLUME.get()) {
            throw new IllegalArgumentException("Structure dimensions exceed configured limits");
        }
    }

    public void validate() {
        validateSize(sizeX, sizeY, sizeZ);
        if (name.length() > 128 || author.length() > 128 || modVersion.length() > 64 || createdAt < 0)
            throw new IllegalArgumentException("Invalid structure metadata");
        if (blocks.isEmpty() || blocks.size() > ModConfig.MAX_BLOCKS.get())
            throw new IllegalArgumentException("Structure is empty or contains too many blocks");
        Set<BlockPos> seen = new HashSet<>();
        boolean nonAir = false;
        for (BlockEntry entry : blocks) {
            BlockPos p = entry.pos;
            if (p.getX() < 0 || p.getY() < 0 || p.getZ() < 0 || p.getX() >= sizeX || p.getY() >= sizeY || p.getZ() >= sizeZ)
                throw new IllegalArgumentException("Block position is outside the structure");
            if (!seen.add(p)) throw new IllegalArgumentException("Duplicate block position");
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id) || ModConfig.isBlocked(entry.state))
                throw new IllegalArgumentException("Forbidden or unregistered block: " + id);
            nonAir |= !entry.state.isAir();
            if (entry.blockEntity != null) validateBlockEntity(entry.state, entry.blockEntity);
        }
        if (!nonAir) throw new IllegalArgumentException("Structure contains no non-air blocks");
    }

    public int nonAirCount() { return (int) blocks.stream().filter(b -> !b.state.isAir()).count(); }

    public CompoundTag toNbt() {
        validate();
        CompoundTag root = new CompoundTag();
        root.putInt("FormatVersion", FORMAT_VERSION);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.putString("ModVersion", modVersion);
        root.putString("Name", name);
        root.putString("Author", author);
        root.putLong("CreatedAt", createdAt);
        root.putInt("SizeX", sizeX);
        root.putInt("SizeY", sizeY);
        root.putInt("SizeZ", sizeZ);
        ListTag list = new ListTag();
        for (BlockEntry entry : blocks) {
            CompoundTag block = new CompoundTag();
            block.putIntArray("Pos", new int[]{entry.pos.getX(), entry.pos.getY(), entry.pos.getZ()});
            block.put("State", NbtUtils.writeBlockState(entry.state));
            if (entry.blockEntity != null) block.put("BlockEntity", entry.blockEntity.copy());
            list.add(block);
        }
        root.put("Blocks", list);
        return root;
    }

    public static StructureData fromNbt(CompoundTag root) {
        SafeNbt.validateTree(root);
        require(root, "FormatVersion", Tag.TAG_INT);
        if (root.getInt("FormatVersion") != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported prefab format version");
        require(root, "MinecraftDataVersion", Tag.TAG_INT);
        if (root.getInt("MinecraftDataVersion") != MINECRAFT_DATA_VERSION)
            throw new IllegalArgumentException("Prefab Minecraft version differs from 1.20.1; data conversion is required");
        for (String field : List.of("SizeX", "SizeY", "SizeZ")) require(root, field, Tag.TAG_INT);
        int x = root.getInt("SizeX"), y = root.getInt("SizeY"), z = root.getInt("SizeZ");
        validateSize(x, y, z);
        require(root, "Blocks", Tag.TAG_LIST);
        ListTag list = root.getList("Blocks", Tag.TAG_COMPOUND);
        if (list.isEmpty() || list.size() > ModConfig.MAX_BLOCKS.get()) throw new IllegalArgumentException("Invalid block count");
        List<BlockEntry> entries = new ArrayList<>(list.size());
        Set<BlockPos> positions = new HashSet<>();
        for (Tag value : list) {
            CompoundTag block = (CompoundTag) value;
            require(block, "Pos", Tag.TAG_INT_ARRAY);
            int[] p = block.getIntArray("Pos");
            if (p.length != 3) throw new IllegalArgumentException("Invalid block position");
            BlockPos position = new BlockPos(p[0], p[1], p[2]);
            if (p[0] < 0 || p[1] < 0 || p[2] < 0 || p[0] >= x || p[1] >= y || p[2] >= z || !positions.add(position))
                throw new IllegalArgumentException("Out-of-bounds or duplicate block position");
            require(block, "State", Tag.TAG_COMPOUND);
            if (block.contains("BlockEntity")) require(block, "BlockEntity", Tag.TAG_COMPOUND);
            BlockState state = readBlockState(block.getCompound("State"));
            CompoundTag blockEntity = block.contains("BlockEntity") ? block.getCompound("BlockEntity") : null;
            if (state.isAir() && blockEntity != null) throw new IllegalArgumentException("Air cannot contain a block entity");
            if (!state.isAir() || ModConfig.SAVE_AIR.get())
                entries.add(new BlockEntry(position, state, blockEntity));
        }
        for (String field : List.of("Name", "Author", "ModVersion")) require(root, field, Tag.TAG_STRING);
        require(root, "CreatedAt", Tag.TAG_LONG);
        StructureData result = new StructureData(x, y, z, entries, root.getString("Name"), root.getString("Author"),
                root.getLong("CreatedAt"), root.getString("ModVersion"));
        result.validate();
        return result;
    }

    /** Vanilla's NbtUtils reader silently defaults unknown registry names/properties; imports must not. */
    public static BlockState readBlockState(CompoundTag nbt) {
        require(nbt, "Name", Tag.TAG_STRING);
        ResourceLocation id = ResourceLocation.tryParse(nbt.getString("Name"));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown block: " + nbt.getString("Name"));
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockState state = block.defaultBlockState();
        if (nbt.contains("Properties")) {
            require(nbt, "Properties", Tag.TAG_COMPOUND);
            CompoundTag properties = nbt.getCompound("Properties");
            for (String key : properties.getAllKeys()) {
                require(properties, key, Tag.TAG_STRING);
                Property<?> property = block.getStateDefinition().getProperty(key);
                if (property == null) throw new IllegalArgumentException("Unknown block property: " + id + "." + key);
                state = withProperty(state, property, properties.getString(key));
            }
        }
        if (ModConfig.isBlocked(state)) throw new IllegalArgumentException("Forbidden block: " + id);
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid " + property.getName() + " value: " + value));
        return state.setValue(property, parsed);
    }

    public static void validateBlockEntity(BlockState state, CompoundTag tag) {
        require(tag, "id", Tag.TAG_STRING);
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("id"));
        if (id == null || !BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id) || !ModConfig.isAllowedBlockEntity(id.toString()))
            throw new IllegalArgumentException("Block entity is not allowed: " + tag.getString("id"));
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
        if (!state.hasBlockEntity() || type == null || !type.isValid(state))
            throw new IllegalArgumentException("Block entity type does not match block: " + id);
        SafeNbt.validateTree(tag);
    }

    public static CompoundTag sanitizeBlockEntity(CompoundTag original) {
        SafeNbt.validateTree(original);
        CompoundTag copy = original.copy();
        sanitize(copy);
        return copy;
    }

    private static void sanitize(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            if (!ModConfig.KEEP_INVENTORIES.get()) {
                for (String key : List.of("Items", "Item", "Inventory", "inventory", "Book", "RecordItem", "Bees", "LootTable", "LootTableSeed", "ForgeCaps"))
                    compound.remove(key);
            }
            for (String key : compound.getAllKeys()) sanitize(compound.get(key));
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) sanitize(child);
        } else if (tag instanceof StringTag text) {
            String value = text.getAsString().trim();
            if (value.startsWith("{") || value.startsWith("[")) {
                rejectCommands(value);
            }
        }
    }

    private static void rejectCommands(String json) {
        // Streaming traversal also bounds malicious JSON nested inside a shallow NBT string.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            int depth = 0;
            boolean action = false;
            JsonToken token;
            while ((token = reader.peek()) != JsonToken.END_DOCUMENT) {
                switch (token) {
                    case BEGIN_OBJECT -> { reader.beginObject(); depth++; action = false; }
                    case BEGIN_ARRAY -> { reader.beginArray(); depth++; action = false; }
                    case END_OBJECT -> { reader.endObject(); depth--; action = false; }
                    case END_ARRAY -> { reader.endArray(); depth--; action = false; }
                    case NAME -> action = "action".equals(reader.nextName());
                    case STRING, NUMBER -> {
                        String value = reader.nextString();
                        if (action && "run_command".equals(value))
                            throw new IllegalArgumentException("Executable sign/text click commands are forbidden");
                        action = false;
                    }
                    case BOOLEAN -> { reader.nextBoolean(); action = false; }
                    case NULL -> { reader.nextNull(); action = false; }
                    default -> throw new IllegalArgumentException("Invalid block entity text JSON");
                }
                if (depth > ModConfig.MAX_NBT_DEPTH.get()) throw new IllegalArgumentException("Text JSON is too deeply nested");
            }
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("Invalid block entity text JSON", e);
        }
    }

    public static void require(CompoundTag nbt, String field, int type) {
        if (!nbt.contains(field, type)) throw new IllegalArgumentException("Missing or invalid field: " + field);
    }
}
