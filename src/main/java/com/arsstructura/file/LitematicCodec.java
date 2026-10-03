package com.arsstructura.file;

import com.arsstructura.config.ModConfig;
import com.arsstructura.data.SafeNbt;
import com.arsstructura.data.StructureData;
import com.arsstructura.data.StructureData.BlockEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Litematica's version 5/6 NBT format; no Litematica/Fabric runtime dependency. */
public final class LitematicCodec {
    private static final int MAX_REGIONS = 1024;

    private LitematicCodec() {}

    public static StructureData decode(CompoundTag root) {
        SafeNbt.validateTree(root);
        StructureData.require(root, "Version", Tag.TAG_INT);
        int version = root.getInt("Version");
        if (version != 5 && version != 6) throw new IllegalArgumentException("Only Litematica format 5 and 6 are supported");
        if (root.contains("SubVersion") && (!root.contains("SubVersion", Tag.TAG_INT)
                || root.getInt("SubVersion") < 0 || root.getInt("SubVersion") > 1))
            throw new IllegalArgumentException("Unsupported Litematica sub-version");
        StructureData.require(root, "MinecraftDataVersion", Tag.TAG_INT);
        if (root.getInt("MinecraftDataVersion") != StructureData.MINECRAFT_DATA_VERSION)
            throw new IllegalArgumentException("Litematic must be saved for Minecraft 1.20.1 (data version 3465); convert it in Litematica first");
        StructureData.require(root, "Regions", Tag.TAG_COMPOUND);
        CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty() || regions.size() > MAX_REGIONS) throw new IllegalArgumentException("Invalid number of schematic regions");
        List<String> names = new ArrayList<>(regions.getAllKeys());
        Collections.sort(names);
        List<Region> parsed = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long scannedVolume = 0;
        for (String name : names) {
            StructureData.require(regions, name, Tag.TAG_COMPOUND);
            CompoundTag nbt = regions.getCompound(name);
            BlockPos position = vector(nbt, "Position"), signedSize = vector(nbt, "Size");
            int x = absoluteSize(signedSize.getX()), y = absoluteSize(signedSize.getY()), z = absoluteSize(signedSize.getZ());
            StructureData.validateSize(x, y, z);
            int loX = regionMin(position.getX(), signedSize.getX());
            int loY = regionMin(position.getY(), signedSize.getY());
            int loZ = regionMin(position.getZ(), signedSize.getZ());
            int hiX = checkedAdd(loX, x - 1), hiY = checkedAdd(loY, y - 1), hiZ = checkedAdd(loZ, z - 1);
            minX = Math.min(minX, loX); minY = Math.min(minY, loY); minZ = Math.min(minZ, loZ);
            maxX = Math.max(maxX, hiX); maxY = Math.max(maxY, hiY); maxZ = Math.max(maxZ, hiZ);
            scannedVolume += (long) x * y * z;
            validateDenseVolume(scannedVolume);
            parsed.add(new Region(nbt, new BlockPos(loX, loY, loZ), x, y, z));
        }
        int sx = checkedDimension(minX, maxX), sy = checkedDimension(minY, maxY), sz = checkedDimension(minZ, maxZ);
        StructureData.validateSize(sx, sy, sz);
        BlockPos globalMin = new BlockPos(minX, minY, minZ);
        Map<BlockPos, BlockEntry> blocks = new LinkedHashMap<>();
        for (Region region : parsed) readRegion(region, globalMin, blocks);
        CompoundTag metadata = root.getCompound("Metadata");
        CompoundTag extension = root.getCompound("ArsStructura");
        StructureData data = new StructureData(sx, sy, sz, new ArrayList<>(blocks.values()),
                metadata.contains("Name", Tag.TAG_STRING) ? metadata.getString("Name") : "Imported structure",
                metadata.contains("Author", Tag.TAG_STRING) ? metadata.getString("Author") : "Unknown",
                metadata.contains("TimeCreated", Tag.TAG_LONG) ? metadata.getLong("TimeCreated") : 0,
                extension.contains("ModVersion", Tag.TAG_STRING) ? extension.getString("ModVersion") : "unknown");
        data.validate();
        return data;
    }

    private static void readRegion(Region region, BlockPos globalMin, Map<BlockPos, BlockEntry> result) {
        CompoundTag nbt = region.nbt;
        StructureData.require(nbt, "BlockStatePalette", Tag.TAG_LIST);
        ListTag paletteTag = nbt.getList("BlockStatePalette", Tag.TAG_COMPOUND);
        if (paletteTag.isEmpty() || paletteTag.size() > ModConfig.MAX_BLOCKS.get() + 1L)
            throw new IllegalArgumentException("Invalid schematic palette size");
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (Tag entry : paletteTag) palette.add(StructureData.readBlockState((CompoundTag) entry));
        int bits = bitsForPalette(palette.size());
        long volume = (long) region.x * region.y * region.z;
        StructureData.require(nbt, "BlockStates", Tag.TAG_LONG_ARRAY);
        long[] words = nbt.getLongArray("BlockStates");
        if (words.length != wordCount(volume, bits)) throw new IllegalArgumentException("Truncated or invalid packed block array");
        Map<BlockPos, CompoundTag> entities = new HashMap<>();
        if (nbt.contains("TileEntities")) {
            StructureData.require(nbt, "TileEntities", Tag.TAG_LIST);
            ListTag rawTiles = (ListTag) nbt.get("TileEntities");
            if (!rawTiles.isEmpty() && rawTiles.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Invalid block entity list element type");
            ListTag tiles = nbt.getList("TileEntities", Tag.TAG_COMPOUND);
            if (tiles.size() > ModConfig.MAX_BLOCKS.get()) throw new IllegalArgumentException("Too many block entities");
            for (Tag value : tiles) {
                CompoundTag entity = (CompoundTag) value;
                BlockPos local = vector(entity);
                if (!inside(local, region.x, region.y, region.z) || entities.put(local, entity) != null)
                    throw new IllegalArgumentException("Invalid or duplicate block entity position");
            }
        }
        for (int y = 0; y < region.y; y++) {
            for (int z = 0; z < region.z; z++) {
                for (int x = 0; x < region.x; x++) {
                    long index = ((long) y * region.z + z) * region.x + x;
                    int id = unpack(words, bits, index);
                    if (id >= palette.size()) throw new IllegalArgumentException("Packed palette index is out of range");
                    BlockState state = palette.get(id);
                    BlockPos local = new BlockPos(x, y, z);
                    CompoundTag be = entities.get(local);
                    if (state.isAir() && be != null) throw new IllegalArgumentException("Air cannot contain a block entity");
                    if (state.isAir() && !ModConfig.SAVE_AIR.get()) continue;
                    BlockPos relative = region.min.offset(x, y, z).subtract(globalMin);
                    BlockEntry entry = new BlockEntry(relative, state, be);
                    BlockEntry previous = result.putIfAbsent(relative, entry);
                    if (previous != null && (!previous.state().equals(entry.state())
                            || !Objects.equals(previous.blockEntity(), entry.blockEntity())))
                        throw new IllegalArgumentException("Schematic regions contain conflicting overlapping blocks");
                    if (result.size() > ModConfig.MAX_BLOCKS.get()) throw new IllegalArgumentException("Schematic contains too many saved blocks");
                }
            }
        }
    }

    public static CompoundTag encode(StructureData data) {
        data.validate();
        long volume = (long) data.sizeX() * data.sizeY() * data.sizeZ();
        validateDenseVolume(volume);
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        palette.put(Blocks.AIR.defaultBlockState(), 0);
        for (BlockEntry block : data.blocks()) palette.computeIfAbsent(block.state(), ignored -> palette.size());
        int bits = bitsForPalette(palette.size());
        long[] words = new long[wordCount(volume, bits)];
        ListTag tiles = new ListTag();
        for (BlockEntry block : data.blocks()) {
            BlockPos p = block.pos();
            long index = ((long) p.getY() * data.sizeZ() + p.getZ()) * data.sizeX() + p.getX();
            pack(words, bits, index, palette.get(block.state()));
            CompoundTag be = block.blockEntity();
            if (be != null) {
                be.putInt("x", p.getX()); be.putInt("y", p.getY()); be.putInt("z", p.getZ());
                tiles.add(be);
            }
        }
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette.keySet()) paletteTag.add(NbtUtils.writeBlockState(state));
        CompoundTag region = new CompoundTag();
        region.put("Position", vectorTag(0, 0, 0));
        region.put("Size", vectorTag(data.sizeX(), data.sizeY(), data.sizeZ()));
        region.put("BlockStatePalette", paletteTag);
        region.putLongArray("BlockStates", words);
        region.put("TileEntities", tiles);
        region.put("Entities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put("Construction", region);
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", data.name());
        metadata.putString("Author", data.author());
        metadata.putString("Description", "Exported by Ars Structura; blocks and permitted block entity data only");
        metadata.putInt("RegionCount", 1);
        metadata.putInt("TotalVolume", (int) volume);
        metadata.putInt("TotalBlocks", data.nonAirCount());
        metadata.putLong("TimeCreated", data.createdAt());
        metadata.putLong("TimeModified", data.createdAt());
        metadata.put("EnclosingSize", vectorTag(data.sizeX(), data.sizeY(), data.sizeZ()));
        CompoundTag extension = new CompoundTag();
        extension.putString("ModVersion", data.modVersion());
        extension.putInt("FormatVersion", StructureData.FORMAT_VERSION);
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 6);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("Metadata", metadata);
        root.put("Regions", regions);
        root.put("ArsStructura", extension);
        return root;
    }

    /** Dense external files have a separate work budget, even if almost all cells are air. */
    private static void validateDenseVolume(long volume) {
        long limit = Math.min(ModConfig.MAX_VOLUME.get(), ModConfig.MAX_NBT_BYTES.get() / 8L);
        if (volume < 1 || volume > limit) throw new IllegalArgumentException("Litematic dense volume exceeds the file processing limit (" + limit + ")");
    }

    static int bitsForPalette(int paletteSize) {
        if (paletteSize < 1) throw new IllegalArgumentException("Empty palette");
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    static int wordCount(long volume, int bits) {
        long count = (volume * bits + 63) / 64;
        if (volume < 1 || bits < 2 || bits > 32 || count > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid bit-array dimensions");
        return (int) count;
    }

    static int unpack(long[] words, int bits, long index) {
        long bit = index * bits;
        int word = (int) (bit >>> 6), offset = (int) (bit & 63);
        long value = words[word] >>> offset;
        if (offset + bits > 64) value |= words[word + 1] << (64 - offset);
        return (int) (value & ((1L << bits) - 1));
    }

    static void pack(long[] words, int bits, long index, int value) {
        long bit = index * bits, mask = (1L << bits) - 1;
        int word = (int) (bit >>> 6), offset = (int) (bit & 63);
        if (value < 0 || (value & ~mask) != 0) throw new IllegalArgumentException("Palette value exceeds bit width");
        words[word] = (words[word] & ~(mask << offset)) | (((long) value & mask) << offset);
        if (offset + bits > 64) {
            int remaining = offset + bits - 64;
            words[word + 1] = (words[word + 1] & ~((1L << remaining) - 1)) | ((long) value >>> (64 - offset));
        }
    }

    static int regionMin(int position, int signedSize) {
        absoluteSize(signedSize);
        return signedSize < 0 ? checkedAdd(position, signedSize + 1) : position;
    }

    private static int absoluteSize(int value) {
        if (value == 0 || value == Integer.MIN_VALUE) throw new IllegalArgumentException("Invalid signed region size");
        return Math.abs(value);
    }

    private static int checkedAdd(int a, int b) {
        long result = (long) a + b;
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) throw new IllegalArgumentException("Region coordinate overflow");
        return (int) result;
    }

    private static int checkedDimension(int min, int max) {
        long size = (long) max - min + 1;
        if (size < 1 || size > ModConfig.MAX_EDGE.get()) throw new IllegalArgumentException("Combined region dimensions exceed limits");
        return (int) size;
    }

    private static BlockPos vector(CompoundTag parent, String key) {
        StructureData.require(parent, key, Tag.TAG_COMPOUND);
        return vector(parent.getCompound(key));
    }

    private static BlockPos vector(CompoundTag tag) {
        for (String key : List.of("x", "y", "z")) StructureData.require(tag, key, Tag.TAG_INT);
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    private static CompoundTag vectorTag(int x, int y, int z) {
        CompoundTag result = new CompoundTag();
        result.putInt("x", x); result.putInt("y", y); result.putInt("z", z);
        return result;
    }

    private static boolean inside(BlockPos p, int x, int y, int z) {
        return p.getX() >= 0 && p.getY() >= 0 && p.getZ() >= 0 && p.getX() < x && p.getY() < y && p.getZ() < z;
    }

    private record Region(CompoundTag nbt, BlockPos min, int x, int y, int z) {}
}
