package com.arsstructura.file;

import com.arsstructura.data.StructureData;
import com.arsstructura.data.TestEnvironment;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LitematicCodecTest {
    @BeforeAll static void bootstrap() {
        TestEnvironment.bootstrap();
    }

    @Test void goldenCrossLongVectorsAndAxisOrder() {
        assertEquals(5, LitematicCodec.bitsForPalette(17));
        assertEquals(16, LitematicCodec.unpack(new long[]{0, 1}, 5, 12));
        assertEquals(7, LitematicCodec.unpack(new long[]{Long.MIN_VALUE, 3}, 3, 21));
        long[] cube = {0xFAC688L};
        for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            int index = (y * 2 + z) * 2 + x;
            assertEquals(index, LitematicCodec.unpack(cube, 3, index));
        }
        long[] encoded = new long[2];
        LitematicCodec.pack(encoded, 3, 21, 7);
        assertArrayEquals(new long[]{Long.MIN_VALUE, 3}, encoded);
    }

    @Test void packedRoundTripAcrossEveryRelevantWidthAndOverwrite() {
        Random random = new Random(73911);
        for (int bits = 2; bits <= 19; bits++) {
            long[] words = new long[LitematicCodec.wordCount(257, bits)];
            int[] values = new int[257];
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextInt(1 << bits);
                LitematicCodec.pack(words, bits, i, values[i]);
            }
            for (int i = 0; i < values.length; i++) assertEquals(values[i], LitematicCodec.unpack(words, bits, i));
            for (int i = 0; i < values.length; i += 3) {
                values[i] = 0;
                LitematicCodec.pack(words, bits, i, 0);
            }
            for (int i = 0; i < values.length; i++) assertEquals(values[i], LitematicCodec.unpack(words, bits, i));
        }
    }

    @Test void signedRegionCoordinatesIncludeBothEnds() {
        assertEquals(8, LitematicCodec.regionMin(10, -3));
        assertEquals(10, LitematicCodec.regionMin(10, 3));
        assertEquals(10, LitematicCodec.regionMin(10, -1));
        assertThrows(IllegalArgumentException.class, () -> LitematicCodec.regionMin(0, 0));
        assertThrows(IllegalArgumentException.class, () -> LitematicCodec.regionMin(0, Integer.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> LitematicCodec.regionMin(Integer.MIN_VALUE, -2));
    }

    @Test void negativeAndMultipleRegionsPreserveGapsAndBlockEntityLocalCoordinates() {
        CompoundTag root = header();
        CompoundTag regions = new CompoundTag();
        CompoundTag chest = region(3, -3, Blocks.CHEST.defaultBlockState());
        ListTag tiles = new ListTag();
        CompoundTag tile = xyz(0, 0, 0);
        tile.putString("id", "minecraft:chest");
        tile.putString("CustomName", "\"Example chest\"");
        tiles.add(tile);
        chest.put("TileEntities", tiles);
        regions.put("negative", chest);
        regions.put("distant", region(10, 1, Blocks.STONE.defaultBlockState()));
        root.put("Regions", regions);
        StructureData decoded = LitematicCodec.decode(root);
        assertEquals(10, decoded.sizeX());
        assertEquals(2, decoded.blocks().size());
        var first = decoded.blocks().stream().filter(b -> b.state().is(Blocks.CHEST)).findFirst().orElseThrow();
        assertEquals(BlockPos.ZERO, first.pos());
        assertEquals(0, first.blockEntity().getInt("x"));
        assertTrue(decoded.blocks().stream().anyMatch(b -> b.pos().equals(new BlockPos(9, 0, 0))));
    }

    @Test void exportImportPreservesBlockStateAndMetadata() {
        StructureData original = new StructureData(3, 2, 2,
                List.of(new StructureData.BlockEntry(new BlockPos(2, 1, 1), Blocks.OAK_STAIRS.defaultBlockState(), null)),
                "Stairs", "Tester", 1234, "1.0.0");
        StructureData restored = LitematicCodec.decode(LitematicCodec.encode(original));
        assertEquals(original, restored);
    }

    @Test void futureVersionAndPackedCorruptionAreRejected() {
        CompoundTag root = header();
        CompoundTag regions = new CompoundTag();
        CompoundTag region = region(0, 1, Blocks.STONE.defaultBlockState());
        regions.put("main", region);
        root.put("Regions", regions);
        root.putInt("MinecraftDataVersion", 3955);
        assertThrows(IllegalArgumentException.class, () -> LitematicCodec.decode(root));
        root.putInt("MinecraftDataVersion", 3465);
        region.putLongArray("BlockStates", new long[0]);
        assertThrows(IllegalArgumentException.class, () -> LitematicCodec.decode(root));
        region.putLongArray("BlockStates", new long[]{3});
        assertThrows(IllegalArgumentException.class, () -> LitematicCodec.decode(root));
    }

    private static CompoundTag header() {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 6); root.putInt("SubVersion", 1); root.putInt("MinecraftDataVersion", 3465);
        return root;
    }

    private static CompoundTag region(int positionX, int signedX, net.minecraft.world.level.block.state.BlockState state) {
        CompoundTag region = new CompoundTag();
        region.put("Position", xyz(positionX, 0, 0));
        region.put("Size", xyz(signedX, 1, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(state));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[]{1});
        return region;
    }

    private static CompoundTag xyz(int x, int y, int z) {
        CompoundTag p = new CompoundTag(); p.putInt("x", x); p.putInt("y", y); p.putInt("z", z); return p;
    }
}
