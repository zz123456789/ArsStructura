package com.arsstructura.data;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructureDataTest {
    @BeforeAll static void bootstrap() { TestEnvironment.bootstrap(); }

    @Test void unknownRegistryAndInvalidPropertiesNeverBecomeAirOrDefaults() {
        CompoundTag state = new CompoundTag();
        state.putString("Name", "missing_mod:missing_block");
        assertThrows(IllegalArgumentException.class, () -> StructureData.readBlockState(state));
        state.putString("Name", "minecraft:oak_stairs");
        CompoundTag properties = new CompoundTag(); properties.putString("facing", "diagonal");
        state.put("Properties", properties);
        assertThrows(IllegalArgumentException.class, () -> StructureData.readBlockState(state));
        properties.remove("facing"); properties.putString("invented_property", "true");
        assertThrows(IllegalArgumentException.class, () -> StructureData.readBlockState(state));
    }

    @Test void forbiddenBlocksAndMismatchedBlockEntitiesAreRejected() {
        CompoundTag forbidden = new CompoundTag(); forbidden.putString("Name", "minecraft:command_block");
        assertThrows(IllegalArgumentException.class, () -> StructureData.readBlockState(forbidden));
        CompoundTag chest = new CompoundTag(); chest.putString("id", "minecraft:chest");
        StructureData wrong = structure(new StructureData.BlockEntry(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState(), chest));
        assertThrows(IllegalArgumentException.class, wrong::validate);
        CompoundTag unknown = new CompoundTag(); unknown.putString("id", "missing_mod:machine");
        assertThrows(IllegalArgumentException.class,
                () -> structure(new StructureData.BlockEntry(BlockPos.ZERO, Blocks.CHEST.defaultBlockState(), unknown)).validate());
    }

    @Test void inventoryAndLootAreStrippedButNormalTextIsPreserved() {
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        ListTag items = new ListTag();
        CompoundTag diamond = new CompoundTag(); diamond.putString("id", "minecraft:diamond"); diamond.putByte("Count", (byte) 64);
        items.add(diamond); chest.put("Items", items);
        chest.putString("LootTable", "minecraft:chests/end_city_treasure");
        chest.put("ForgeCaps", new CompoundTag());
        chest.putString("CustomName", "\"Example chest\"");
        var entry = new StructureData.BlockEntry(BlockPos.ZERO, Blocks.CHEST.defaultBlockState(), chest);
        structure(entry).validate();
        assertFalse(entry.blockEntity().contains("Items"));
        assertFalse(entry.blockEntity().contains("LootTable"));
        assertFalse(entry.blockEntity().contains("ForgeCaps"));
        assertEquals("\"Example chest\"", entry.blockEntity().getString("CustomName"));
        assertTrue(chest.contains("Items")); // Sanitization must not modify the source world tag.
        entry.blockEntity().putString("CustomName", "changed");
        assertEquals("\"Example chest\"", entry.blockEntity().getString("CustomName"));
    }

    @Test void escapedExecutableSignActionsAndDeepJsonAreRejected() {
        CompoundTag sign = new CompoundTag(); sign.putString("id", "minecraft:sign");
        CompoundTag front = new CompoundTag(); ListTag lines = new ListTag();
        lines.add(StringTag.valueOf("{\"text\":\"Run\",\"clickEvent\":{\"action\":\"run_\\u0063ommand\",\"value\":\"/op somebody\"}}"));
        front.put("messages", lines); sign.put("front_text", front);
        assertThrows(IllegalArgumentException.class,
                () -> new StructureData.BlockEntry(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState(), sign));
        sign.remove("front_text");
        sign.putString("Text1", "[".repeat(100) + "\"x\"" + "]".repeat(100));
        assertThrows(IllegalArgumentException.class,
                () -> new StructureData.BlockEntry(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState(), sign));
    }

    private static StructureData structure(StructureData.BlockEntry entry) {
        return new StructureData(1, 1, 1, List.of(entry), "Test", "Tester", 0, "1.0.0");
    }
}
