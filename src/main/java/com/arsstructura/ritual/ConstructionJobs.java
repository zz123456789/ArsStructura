package com.arsstructura.ritual;

import com.arsstructura.data.StructureData;
import com.arsstructura.ArsStructura;
import com.hollingsworth.arsnouveau.common.block.tile.RitualBrazierTile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

/** Server-only building payloads; never included in the brazier's vanilla update packet. */
@Mod.EventBusSubscriber(modid = ArsStructura.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ConstructionJobs extends SavedData {
    private static final String STORAGE_NAME = "arsstructura_construction_jobs";
    static final int MAX_JOBS = 32;
    final Map<UUID, Job> jobs = new HashMap<>();

    public static ConstructionJobs get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ConstructionJobs::load,
                ConstructionJobs::new, STORAGE_NAME);
    }

    public static ConstructionJobs load(CompoundTag tag) {
        ConstructionJobs store = new ConstructionJobs();
        ListTag list = tag.getList("Jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(list.size(), MAX_JOBS); i++) {
            try {
                Job job = Job.read(list.getCompound(i));
                store.jobs.put(job.id, job);
            } catch (Exception exception) {
                // Reject damaged or newly disallowed saved jobs without preventing world loading.
                ArsStructura.LOGGER.warn("Discarding invalid saved construction job at index {}", i, exception);
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Job job : jobs.values()) list.add(job.write());
        tag.put("Jobs", list);
        return tag;
    }

    void remove(UUID id) {
        if (jobs.remove(id) != null) setDirty();
    }

    @SubscribeEvent
    public static void cleanDetachedJobs(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % 100 != 0) return;
        ConstructionJobs store = get(server);
        // Another Ars ritual tablet can replace a running ritual. Reclaim that job without touching unloaded chunks.
        for (Job job : new ArrayList<>(store.jobs.values())) {
            ResourceLocation dimension = ResourceLocation.tryParse(job.dimension);
            ServerLevel level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level != null && !level.hasChunkAt(job.brazier)) continue;
            if (level != null && level.getBlockEntity(job.brazier) instanceof RitualBrazierTile brazier
                    && brazier.ritual instanceof ConstructionRitual ritual && job.id.equals(ritual.jobId) && !ritual.isDone()) continue;
            store.remove(job.id);
            var player = server.getPlayerList().getPlayer(job.owner);
            if (player != null) player.sendSystemMessage(Component.translatable("message.arsstructura.ritual_cancelled"));
        }
    }

    static final class Job {
        final UUID id;
        final UUID owner;
        final String dimension;
        final BlockPos brazier;
        final BlockPos anchor;
        final StructureData structure;
        final CompoundTag structureNbt;
        final BitSet placed = new BitSet();
        boolean activated;
        int warmupRemaining;
        int cursor;
        int finishCursor;
        int skipped;
        int restored;
        int restoreFailed;
        long lastNotice = -100;

        Job(UUID id, UUID owner, String dimension, BlockPos brazier, BlockPos anchor, StructureData structure) {
            this.id = id;
            this.owner = owner;
            this.dimension = dimension;
            this.brazier = brazier.immutable();
            this.anchor = anchor.immutable();
            this.structure = structure;
            this.structureNbt = structure.toNbt();
        }

        CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putUUID("Owner", owner);
            tag.putString("Dimension", dimension);
            tag.putLong("Brazier", brazier.asLong());
            tag.putLong("Anchor", anchor.asLong());
            tag.put("Structure", structureNbt.copy());
            tag.putBoolean("Activated", activated);
            tag.putInt("WarmupRemaining", warmupRemaining);
            tag.putInt("Cursor", cursor);
            tag.putInt("FinishCursor", finishCursor);
            tag.putInt("Skipped", skipped);
            tag.putInt("Restored", restored);
            tag.putInt("RestoreFailed", restoreFailed);
            tag.putLongArray("Placed", placed.toLongArray());
            return tag;
        }

        static Job read(CompoundTag tag) throws Exception {
            StructureData structure = StructureData.fromNbt(tag.getCompound("Structure"));
            structure.validate();
            Job job = new Job(tag.getUUID("Id"), tag.getUUID("Owner"), tag.getString("Dimension"),
                    BlockPos.of(tag.getLong("Brazier")), BlockPos.of(tag.getLong("Anchor")), structure);
            int size = structure.blocks().size();
            // Jobs written before the two-stage ritual flow were already running and paid for.
            job.activated = !tag.contains("Activated", Tag.TAG_BYTE) || tag.getBoolean("Activated");
            job.warmupRemaining = bounded(tag.getInt("WarmupRemaining"), 72000);
            job.cursor = bounded(tag.getInt("Cursor"), size);
            job.finishCursor = bounded(tag.getInt("FinishCursor"), size);
            if (job.finishCursor > 0 && job.cursor != size) throw new IllegalArgumentException("Invalid job phase");
            job.skipped = bounded(tag.getInt("Skipped"), size);
            job.restored = bounded(tag.getInt("Restored"), size);
            job.restoreFailed = bounded(tag.getInt("RestoreFailed"), size);
            long[] placed = tag.getLongArray("Placed");
            if (placed.length > (size + 63) / 64) throw new IllegalArgumentException("Invalid placement bitmap");
            job.placed.or(BitSet.valueOf(placed));
            if (job.placed.length() > job.cursor) throw new IllegalArgumentException("Invalid placement progress");
            if ((!job.activated || job.warmupRemaining > 0) && job.cursor > 0) throw new IllegalArgumentException("Invalid activation progress");
            return job;
        }

        private static int bounded(int value, int maximum) {
            if (value < 0 || value > maximum) throw new IllegalArgumentException("Invalid job progress");
            return value;
        }
    }
}
