package com.arsstructura.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionTabletDataTest {
    @BeforeAll static void bootstrap() { TestEnvironment.bootstrap(); }

    @Test void itemPersistenceMetadataAndEncodedPayloadAreIndependentCopies() {
        ItemStack stack = new ItemStack(Items.PAPER);
        assertNull(ConstructionTabletData.id(stack));
        assertNull(stack.getTag());
        var id = ConstructionTabletData.ensureId(stack);
        var structure = new StructureData(1, 1, 1,
                List.of(new StructureData.BlockEntry(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)),
                "House", "Tester", 123, "1.0.0");
        ConstructionTabletData.setStructure(stack, structure);
        ConstructionTabletData.anchor(stack, new BlockPos(9, 70, -3), "minecraft:overworld");
        ConstructionTabletData.setPreview(stack, true);
        long revision = ConstructionTabletData.revision(stack);
        CompoundTag metadata = ConstructionTabletData.metadata(stack);
        assertFalse(metadata.contains("Structure"));
        assertTrue(metadata.getBoolean("HasStructure"));
        metadata.putString("Name", "changed");
        byte[] encoded = ConstructionTabletData.encodedStructure(stack);
        encoded[0] = 0;
        assertEquals(structure, ConstructionTabletData.structure(stack));
        assertEquals("House", ConstructionTabletData.metadata(stack).getString("Name"));
        ItemStack reloaded = ItemStack.of(stack.save(new CompoundTag()));
        assertEquals(id, ConstructionTabletData.id(reloaded));
        assertEquals(structure, ConstructionTabletData.structure(reloaded));
        assertEquals(revision, ConstructionTabletData.revision(reloaded));
        assertTrue(ConstructionTabletData.preview(reloaded));
        assertEquals(new BlockPos(9, 70, -3), ConstructionTabletData.anchor(reloaded));
    }

    @Test void crossDimensionBindingIsAtomicAndClearRetainsIdentity() {
        ItemStack stack = new ItemStack(Items.PAPER);
        ConstructionTabletData.bind(stack, true, new BlockPos(1, 2, 3), "minecraft:overworld");
        var id = ConstructionTabletData.id(stack);
        long revision = ConstructionTabletData.revision(stack);
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionTabletData.bind(stack, false, BlockPos.ZERO, "minecraft:the_nether"));
        assertNull(ConstructionTabletData.point(stack, false));
        assertEquals(revision, ConstructionTabletData.revision(stack));
        ConstructionTabletData.clear(stack);
        assertEquals(id, ConstructionTabletData.id(stack));
        assertTrue(ConstructionTabletData.revision(stack) > revision);
        assertNull(ConstructionTabletData.point(stack, true));
        assertFalse(ConstructionTabletData.preview(stack));
    }
}
