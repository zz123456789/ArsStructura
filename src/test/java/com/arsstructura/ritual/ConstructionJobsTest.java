package com.arsstructura.ritual;

import com.arsstructura.data.StructureData;
import com.arsstructura.data.TestEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionJobsTest {
    @BeforeAll static void bootstrap() {
        TestEnvironment.bootstrap();
    }

    @Test void persistedProgressRetainsOnlyActuallyPlacedEntries() throws Exception {
        ConstructionJobs.Job original = job();
        original.cursor = 2;
        original.skipped = 1;
        original.placed.set(1);
        original.finishCursor = 1;
        ConstructionJobs.Job loaded = ConstructionJobs.Job.read(original.write());
        assertEquals(original.id, loaded.id);
        assertEquals(original.owner, loaded.owner);
        assertEquals(original.anchor, loaded.anchor);
        assertEquals(original.structure, loaded.structure);
        assertEquals(2, loaded.cursor);
        assertEquals(1, loaded.finishCursor);
        assertEquals(1, loaded.skipped);
        assertFalse(loaded.placed.get(0));
        assertTrue(loaded.placed.get(1));
        assertTrue(loaded.activated);
        assertEquals(0, loaded.warmupRemaining);
    }

    @Test void waitingAndActiveWarmupSurviveReloadWithExactRemainingTime() throws Exception {
        ConstructionJobs.Job waiting = job();
        waiting.activated = false;
        waiting.warmupRemaining = 100;
        ConstructionJobs.Job loadedWaiting = ConstructionJobs.Job.read(waiting.write());
        assertFalse(loadedWaiting.activated);
        assertEquals(100, loadedWaiting.warmupRemaining);
        assertEquals(0, loadedWaiting.cursor);

        ConstructionJobs.Job warming = job();
        warming.warmupRemaining = 37;
        ConstructionJobs.Job loadedWarming = ConstructionJobs.Job.read(warming.write());
        assertTrue(loadedWarming.activated);
        assertEquals(37, loadedWarming.warmupRemaining);
        assertEquals(0, loadedWarming.cursor);
    }

    @Test void legacyRunningJobsResumeWithoutAddingAnotherWarmup() throws Exception {
        ConstructionJobs.Job original = job();
        original.cursor = 1;
        original.placed.set(0);
        CompoundTag legacy = original.write();
        legacy.remove("Activated");
        legacy.remove("WarmupRemaining");
        ConstructionJobs.Job loaded = ConstructionJobs.Job.read(legacy);
        assertTrue(loaded.activated);
        assertEquals(0, loaded.warmupRemaining);
        assertEquals(1, loaded.cursor);
        assertTrue(loaded.placed.get(0));
    }

    @Test void invalidWarmupOrConstructionBeforeActivationIsRejected() {
        CompoundTag negative = job().write();
        negative.putInt("WarmupRemaining", -1);
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(negative));
        CompoundTag excessive = job().write();
        excessive.putInt("WarmupRemaining", Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(excessive));
        CompoundTag waitingButPlaced = job().write();
        waitingButPlaced.putBoolean("Activated", false);
        waitingButPlaced.putInt("Cursor", 1);
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(waitingButPlaced));
        CompoundTag warmingButPlaced = job().write();
        warmingButPlaced.putInt("WarmupRemaining", 1);
        warmingButPlaced.putInt("Cursor", 1);
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(warmingButPlaced));
    }

    @Test void invalidCursorOrFinishingBeforePlacementIsRejected() {
        CompoundTag negative = job().write();
        negative.putInt("Cursor", -1);
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(negative));
        CompoundTag tooFar = job().write();
        tooFar.putInt("Cursor", 3);
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(tooFar));
        CompoundTag wrongPhase = job().write();
        wrongPhase.putInt("Cursor", 1);
        wrongPhase.putInt("FinishCursor", 1);
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(wrongPhase));
    }

    @Test void forgedPlacementBitmapCannotRestoreUnplacedBlockEntities() {
        CompoundTag tag = job().write();
        tag.putInt("Cursor", 1);
        tag.putLongArray("Placed", new long[]{2L});
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(tag));
        tag.putLongArray("Placed", new long[]{0L, 1L});
        assertThrows(IllegalArgumentException.class, () -> ConstructionJobs.Job.read(tag));
    }

    @Test void worldStoreDropsCorruptJobWithoutLosingValidJob() {
        ConstructionJobs original = new ConstructionJobs();
        ConstructionJobs.Job valid = job();
        original.jobs.put(valid.id, valid);
        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag bad = job().write();
        bad.putInt("Cursor", Integer.MAX_VALUE);
        saved.getList("Jobs", 10).add(bad);
        ConstructionJobs loaded = ConstructionJobs.load(saved);
        assertEquals(1, loaded.jobs.size());
        assertTrue(loaded.jobs.containsKey(valid.id));
    }

    private static ConstructionJobs.Job job() {
        var stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        StructureData structure = new StructureData(2, 1, 1, List.of(
                new StructureData.BlockEntry(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null),
                new StructureData.BlockEntry(new BlockPos(1, 0, 0), stairs, null)), "Test", "Tester", 10L, "1.0.0");
        ConstructionJobs.Job job = new ConstructionJobs.Job(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld",
                new BlockPos(1, 64, 1), new BlockPos(-6, 70, 8), structure);
        job.activated = true;
        job.warmupRemaining = 0;
        return job;
    }
}
