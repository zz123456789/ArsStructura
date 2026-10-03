package com.arsstructura.ritual;

import com.arsstructura.ArsStructura;
import com.arsstructura.config.ModConfig;
import com.arsstructura.data.ConstructionTabletData;
import com.arsstructura.data.StructureData;
import com.hollingsworth.arsnouveau.api.registry.RitualRegistry;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import com.hollingsworth.arsnouveau.api.source.ISpecialSourceProvider;
import com.hollingsworth.arsnouveau.api.util.SourceUtil;
import com.hollingsworth.arsnouveau.client.particle.ParticleColor;
import com.hollingsworth.arsnouveau.client.particle.ParticleUtil;
import com.hollingsworth.arsnouveau.common.block.RitualBrazierBlock;
import com.hollingsworth.arsnouveau.common.block.tile.RitualBrazierTile;
import com.hollingsworth.arsnouveau.setup.registry.SoundRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** All entry points and world changes are authoritative server operations. */
public final class RitualConstructionHandler {
    private static final int SOURCE_RANGE = 6;

    private RitualConstructionHandler() {}

    /** Called in common setup, after Ars has registered its ordinary tablet items. */
    public static void register() {
        RitualRegistry.registerRitual(new ConstructionRitual());
    }

    /** Load the brazier like an Ars ritual tablet; its native empty-hand interaction activates it later. */
    public static void start(ServerPlayer player, ItemStack stack, RitualBrazierTile brazier) {
        if (!(brazier.getLevel() instanceof ServerLevel level) || player.serverLevel() != level) return;
        UUID installedJob = null;
        try {
            if (!player.mayBuild() || !level.mayInteract(player, brazier.getBlockPos())
                    || player.distanceToSqr(brazier.getBlockPos().getCenter()) > 64) {
                message(player, "ritual_protected");
                return;
            }
            if (brazier.ritual != null) { message(player, "ritual_busy"); return; }
            if (brazier.isOff || level.hasNeighborSignal(brazier.getBlockPos())) { message(player, "ritual_redstone"); return; }
            StructureData original = ConstructionTabletData.structure(stack);
            BlockPos anchor = ConstructionTabletData.anchor(stack);
            if (original == null || original.nonAirCount() == 0) { message(player, "ritual_empty"); return; }
            if (anchor == null) { message(player, "ritual_no_anchor"); return; }
            if (!level.dimension().location().toString().equals(ConstructionTabletData.anchorDimension(stack))) {
                message(player, "ritual_dimension");
                return;
            }
            original.validate();
            int distance = ModConfig.MAX_ANCHOR_DISTANCE.get();
            if (anchor.distSqr(brazier.getBlockPos()) > (double) distance * distance) {
                message(player, "ritual_too_far", distance);
                return;
            }
            if (!boundsValid(level, original, anchor)) { message(player, "ritual_bounds"); return; }
            ConstructionJobs store = ConstructionJobs.get(level.getServer());
            if (store.jobs.size() >= ConstructionJobs.MAX_JOBS
                    || store.jobs.values().stream().anyMatch(j -> j.owner.equals(player.getUUID()))) {
                message(player, "ritual_job_limit");
                return;
            }
            Item fuel = fuelItem();
            int fuelCount = ModConfig.FUEL_COUNT.get();
            if (fuelCount > 0 && (fuel == null || inventoryCount(player, fuel) < fuelCount)) {
                message(player, "ritual_fuel", fuelCount, ModConfig.FUEL_ITEM.get());
                return;
            }
            SourceBudget source = new SourceBudget(level, brazier.getBlockPos());
            int startupCost = ModConfig.SOURCE_START_COST.get();
            if (!source.canPay(startupCost)) { message(player, "ritual_source", startupCost); return; }

            // Bottom-up ordering gives blocks with gravity and attached blocks their best chance to settle safely.
            var blocks = new ArrayList<>(original.blocks());
            blocks.sort(Comparator.comparingInt((StructureData.BlockEntry b) -> b.pos().getY())
                    .thenComparingInt(b -> b.pos().getX()).thenComparingInt(b -> b.pos().getZ()));
            StructureData structure = new StructureData(original.sizeX(), original.sizeY(), original.sizeZ(),
                    List.copyOf(blocks), original.name(), original.author(), original.createdAt(), original.modVersion());
            UUID id = UUID.randomUUID();
            ConstructionJobs.Job job = new ConstructionJobs.Job(id, player.getUUID(), level.dimension().location().toString(),
                    brazier.getBlockPos(), anchor, structure);
            job.warmupRemaining = ModConfig.RITUAL_WARMUP_TICKS.get();
            store.jobs.put(id, job);
            store.setDirty();
            installedJob = id;
            brazier.setRitual(ConstructionRitual.ID);
            if (!(brazier.ritual instanceof ConstructionRitual ritual)) {
                store.remove(id);
                resetBrazier(brazier);
                message(player, "ritual_invalid");
                return;
            }
            ritual.jobId = id;
            // Loading pays for the prepared ritual exactly once. Native activation only changes
            // the persisted phase, so waiting/reloading/empty-hand retries cannot charge again.
            if (!source.pay(startupCost)) {
                store.remove(id);
                resetBrazier(brazier);
                message(player, "ritual_invalid");
                return;
            }
            if (fuelCount > 0) consumeFuel(player, fuel, fuelCount);
            if (ModConfig.CONSUME_TABLET.get()) stack.shrink(1);
            else if (ModConfig.TABLET_DAMAGE.get() > 0 && stack.isDamageableItem()) {
                stack.hurtAndBreak(ModConfig.TABLET_DAMAGE.get(), player, p ->
                        p.broadcastBreakEvent(p.getOffhandItem() == stack ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND));
            }
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            brazier.updateBlock();
            message(player, "ritual_loaded", structure.nonAirCount());
        } catch (Exception exception) {
            if (installedJob != null) {
                ConstructionJobs.get(level.getServer()).remove(installedJob);
                if (brazier.ritual instanceof ConstructionRitual ritual && installedJob.equals(ritual.jobId)) resetBrazier(brazier);
            }
            ArsStructura.LOGGER.debug("Construction ritual could not be started", exception);
            message(player, "ritual_invalid");
        }
    }

    static boolean canActivate(ConstructionRitual ritual, ServerPlayer player) {
        if (!(ritual.getWorld() instanceof ServerLevel level) || ritual.jobId == null
                || ritual.isDone() || ritual.isRunning()) return false;
        ConstructionJobs.Job job = ConstructionJobs.get(level.getServer()).jobs.get(ritual.jobId);
        if (job == null || job.activated || ritual.tile == null
                || ritual.tile.ritual != ritual || !job.brazier.equals(ritual.getPos())
                || level.getBlockEntity(job.brazier) != ritual.tile) {
            activationFailure(level, ritual, player, "ritual_invalid");
            return false;
        }
        if (!job.owner.equals(player.getUUID())) {
            activationFailure(level, ritual, player, "ritual_not_owner");
            return false;
        }
        if (player.serverLevel() != level || !job.dimension.equals(level.dimension().location().toString())) {
            activationFailure(level, ritual, player, "ritual_dimension");
            return false;
        }
        if (ritual.tile.isOff || level.hasNeighborSignal(job.brazier)) {
            activationFailure(level, ritual, player, "ritual_redstone");
            return false;
        }
        if (!player.getMainHandItem().isEmpty()) return false;
        if (!player.mayBuild() || player.isSpectator() || !level.mayInteract(player, job.brazier)
                || player.distanceToSqr(job.brazier.getCenter()) > 64) {
            activationFailure(level, ritual, player, "ritual_protected");
            return false;
        }
        if (!boundsValid(level, job.structure, job.anchor)) {
            activationFailure(level, ritual, player, "ritual_bounds");
            return false;
        }
        int distance = ModConfig.MAX_ANCHOR_DISTANCE.get();
        if (job.anchor.distSqr(job.brazier) > (double) distance * distance) {
            activationFailure(level, ritual, player, "ritual_too_far", distance);
            return false;
        }
        return true;
    }

    static boolean activate(ConstructionRitual ritual, ServerPlayer player) {
        if (!canActivate(ritual, player)) return false;
        ServerLevel level = player.serverLevel();
        ConstructionJobs store = ConstructionJobs.get(level.getServer());
        ConstructionJobs.Job job = store.jobs.get(ritual.jobId);
        job.activated = true;
        store.setDirty();
        ParticleUtil.spawnTouchPacket(level, job.brazier.above(), ritual.getCenterColor());
        message(player, "ritual_started", job.structure.nonAirCount());
        return true;
    }

    private static void activationFailure(ServerLevel level, ConstructionRitual ritual,
                                          ServerPlayer player, String key, Object... args) {
        if (level.getGameTime() - ritual.lastStartNotice >= 20) {
            message(player, key, args);
            ritual.lastStartNotice = level.getGameTime();
        }
    }

    static void tick(ConstructionRitual ritual) {
        if (!(ritual.getWorld() instanceof ServerLevel level) || ritual.jobId == null) return;
        ConstructionJobs store = ConstructionJobs.get(level.getServer());
        ConstructionJobs.Job job = store.jobs.get(ritual.jobId);
        if (job == null) { ritual.setFinished(); return; }
        if (!job.activated || ritual.tile.isOff || level.hasNeighborSignal(job.brazier)) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.owner);
        // Offline owners, owners in another dimension, and unloaded destination chunks never cause forced loading.
        if (player == null || player.serverLevel() != level) return;
        if (!player.mayBuild() || !boundsValid(level, job.structure, job.anchor)) {
            message(player, "ritual_cancelled");
            finish(ritual, store, job);
            return;
        }
        try {
            if (job.warmupRemaining > 0) {
                // Keep the delay in SavedData alongside activation. Relogging never resets it,
                // and a powered brazier/offline owner pauses it just like the building phase.
                job.warmupRemaining--;
                store.setDirty();
                if (job.warmupRemaining > 0 && job.warmupRemaining % 20 == 0) {
                    ParticleUtil.spawnTouchPacket(level, job.brazier.above(), ritual.getOuterColor());
                }
                return;
            }
            if (job.cursor < job.structure.blocks().size()) placeBatch(level, player, ritual, store, job);
            else finishBatch(level, player, ritual, store, job);
        } catch (Exception exception) {
            ArsStructura.LOGGER.warn("Construction job {} stopped safely", job.id, exception);
            message(player, "ritual_cancelled");
            finish(ritual, store, job);
        }
    }

    private static void placeBatch(ServerLevel level, ServerPlayer player, ConstructionRitual ritual,
                                   ConstructionJobs store, ConstructionJobs.Job job) {
        SourceBudget source = new SourceBudget(level, job.brazier);
        int perBlock = ModConfig.SOURCE_PER_BLOCK.get();
        int budget = ModConfig.BUILD_PER_TICK.get();
        for (int work = 0; work < budget && job.cursor < job.structure.blocks().size(); work++) {
            var entry = job.structure.blocks().get(job.cursor);
            BlockPos pos = job.anchor.offset(entry.pos());
            if (!level.hasChunkAt(pos)) { notice(level, player, job, "ritual_wait_chunk"); break; }
            BlockState old = level.getBlockState(pos);
            BlockState proposed = entry.state();
            if (!canReplace(level, player, job, pos, old, proposed)) {
                job.skipped++;
                job.cursor++;
                store.setDirty();
                continue;
            }
            // A protection event sees the requested state before any world mutation. The snapshot retains the old state.
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
            BlockEvent.EntityPlaceEvent event = new ProposedPlacement(snapshot, old, player, proposed);
            boolean denied = MinecraftForge.EVENT_BUS.post(event);
            if (!denied && !old.canBeReplaced() && !old.isAir()) {
                denied = MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, old, player));
            }
            if (denied || !level.getBlockState(pos).equals(old)
                    || !canReplace(level, player, job, pos, old, proposed)) {
                job.skipped++;
                job.cursor++;
                store.setDirty();
                continue;
            }
            if (!source.canPay(perBlock) || !source.pay(perBlock)) {
                notice(level, player, job, "ritual_wait_source");
                break;
            }
            // Suppress intermediate neighbour shape updates until the complete structure exists.
            if (level.setBlock(pos, proposed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) {
                job.placed.set(job.cursor);
                if ((job.placed.cardinality() & 7) == 1) {
                    ParticleUtil.spawnTouchPacket(level, pos, new ParticleColor(94, 218, 245));
                }
            } else job.skipped++;
            job.cursor++;
            store.setDirty();
        }
        ritual.getContext().progress = job.cursor;
        ritual.tile.setChanged();
    }

    private static boolean canReplace(ServerLevel level, ServerPlayer player, ConstructionJobs.Job job,
                                      BlockPos pos, BlockState old, BlockState proposed) {
        if (pos.equals(job.brazier) || ModConfig.isBlocked(proposed) || ModConfig.isBlocked(old)
                || old.hasBlockEntity() || level.getBlockEntity(pos) != null || old.getDestroySpeed(level, pos) < 0
                || !level.mayInteract(player, pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        if (old.equals(proposed)) return false;
        return old.canBeReplaced() || ModConfig.FORCE_REPLACE.get();
    }

    private static void finishBatch(ServerLevel level, ServerPlayer player, ConstructionRitual ritual,
                                    ConstructionJobs store, ConstructionJobs.Job job) {
        int budget = ModConfig.BUILD_PER_TICK.get();
        for (int work = 0; work < budget && job.finishCursor < job.structure.blocks().size(); work++) {
            int index = job.finishCursor;
            var entry = job.structure.blocks().get(index);
            BlockPos pos = job.anchor.offset(entry.pos());
            if (job.placed.get(index)) {
                if (!level.hasChunkAt(pos)) { notice(level, player, job, "ritual_wait_chunk"); return; }
                BlockState current = level.getBlockState(pos);
                if (current.equals(entry.state()) && !ModConfig.isBlocked(current) && level.mayInteract(player, pos)) {
                    CompoundTag nbt = entry.blockEntity();
                    if (nbt != null) {
                        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
                        if (MinecraftForge.EVENT_BUS.post(new ProposedPlacement(snapshot, current, player, current))) job.restoreFailed++;
                        else restoreBlockEntity(level, pos, current, nbt, job);
                    }
                    level.updateNeighborsAt(pos, current.getBlock());
                    level.sendBlockUpdated(pos, current, current, Block.UPDATE_CLIENTS);
                } else if (entry.blockEntity() != null) job.restoreFailed++;
            }
            job.finishCursor++;
            store.setDirty();
        }
        if (job.finishCursor >= job.structure.blocks().size()) {
            message(player, "ritual_complete", job.placed.cardinality(), job.skipped, job.restored, job.restoreFailed);
            level.playSound(null, job.brazier, SoundRegistry.APPARATUS_FINISH.get(), SoundSource.BLOCKS, 0.8F, 1.0F);
            finish(ritual, store, job);
        }
    }

    private static void restoreBlockEntity(ServerLevel level, BlockPos pos, BlockState state,
                                            CompoundTag input, ConstructionJobs.Job job) {
        try {
            if (!level.getBlockState(pos).equals(state)) { job.restoreFailed++; return; }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            ResourceLocation actual = blockEntity == null ? null : ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(blockEntity.getType());
            if (actual == null || !ModConfig.isAllowedBlockEntity(actual.toString())
                    || (!input.getString("id").isEmpty() && !actual.toString().equals(input.getString("id")))) {
                job.restoreFailed++;
                return;
            }
            // Players or automation may fill a newly placed container before the later finishing pass.
            // Never wipe those new contents while applying the blueprint's metadata.
            if (blockEntity instanceof Container container && !container.isEmpty()) {
                job.restoreFailed++;
                return;
            }
            CompoundTag existing = blockEntity.saveWithoutMetadata();
            for (String key : List.of("Items", "Item", "Inventory", "inventory", "Book", "RecordItem", "Bees")) {
                var value = existing.get(key);
                if (value instanceof net.minecraft.nbt.CompoundTag compound && !compound.isEmpty()
                        || value instanceof net.minecraft.nbt.ListTag list && !list.isEmpty()) {
                    job.restoreFailed++;
                    return;
                }
            }
            CompoundTag nbt = StructureData.sanitizeBlockEntity(input);
            nbt.putString("id", actual.toString());
            nbt.putInt("x", pos.getX());
            nbt.putInt("y", pos.getY());
            nbt.putInt("z", pos.getZ());
            StructureData.validateBlockEntity(state, nbt);
            blockEntity.load(nbt);
            blockEntity.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
            job.restored++;
        } catch (Exception exception) {
            job.restoreFailed++;
        }
    }

    private static void finish(ConstructionRitual ritual, ConstructionJobs store, ConstructionJobs.Job job) {
        store.remove(job.id);
        ritual.setFinished();
        if (ritual.tile != null) ritual.tile.updateBlock();
    }

    private static boolean boundsValid(ServerLevel level, StructureData structure, BlockPos anchor) {
        BlockPos max = anchor.offset(structure.sizeX() - 1, structure.sizeY() - 1, structure.sizeZ() - 1);
        return !level.isOutsideBuildHeight(anchor) && !level.isOutsideBuildHeight(max)
                && level.getWorldBorder().isWithinBounds(anchor) && level.getWorldBorder().isWithinBounds(max);
    }

    private static void resetBrazier(RitualBrazierTile brazier) {
        brazier.ritual = null;
        if (brazier.getLevel() != null) {
            BlockState state = brazier.getBlockState();
            if (state.hasProperty(RitualBrazierBlock.LIT)) {
                brazier.getLevel().setBlock(brazier.getBlockPos(), state.setValue(RitualBrazierBlock.LIT, false), Block.UPDATE_ALL);
            }
            brazier.updateBlock();
        }
    }

    private static Item fuelItem() {
        ResourceLocation id = ResourceLocation.tryParse(ModConfig.FUEL_ITEM.get());
        return id != null && ForgeRegistries.ITEMS.containsKey(id) ? ForgeRegistries.ITEMS.getValue(id) : null;
    }

    private static int inventoryCount(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (candidate.is(item)) count += candidate.getCount();
        }
        return count;
    }

    private static void consumeFuel(ServerPlayer player, Item fuel, int count) {
        for (int slot = 0; slot < player.getInventory().getContainerSize() && count > 0; slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (candidate.is(fuel)) {
                int take = Math.min(count, candidate.getCount());
                candidate.shrink(take);
                count -= take;
            }
        }
    }

    private static void message(ServerPlayer player, String suffix, Object... args) {
        player.sendSystemMessage(Component.translatable("message.arsstructura." + suffix, args));
    }

    private static void notice(ServerLevel level, ServerPlayer player, ConstructionJobs.Job job, String key) {
        if (level.getGameTime() - job.lastNotice >= 100) {
            message(player, key);
            job.lastNotice = level.getGameTime();
        }
    }

    private static final class ProposedPlacement extends BlockEvent.EntityPlaceEvent {
        private final BlockState proposed;

        ProposedPlacement(BlockSnapshot snapshot, BlockState against, ServerPlayer player, BlockState proposed) {
            super(snapshot, against, player);
            this.proposed = proposed;
        }

        @Override public BlockState getPlacedBlock() { return proposed; }
        @Override public BlockState getState() { return proposed; }
    }

    /** One proximity scan per batch, with de-duplication of providers that expose the same source store. */
    private static final class SourceBudget {
        final ServerLevel level;
        final BlockPos brazier;
        final List<ISpecialSourceProvider> providers = new ArrayList<>();
        final Set<ISourceTile> animated = Collections.newSetFromMap(new IdentityHashMap<>());

        SourceBudget(ServerLevel level, BlockPos brazier) {
            this.level = level;
            this.brazier = brazier;
            Set<ISourceTile> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ISpecialSourceProvider provider : SourceUtil.canTakeSource(brazier, level, SOURCE_RANGE)) {
                if (provider.isValid() && provider.getSource() != null && seen.add(provider.getSource())) providers.add(provider);
            }
        }

        boolean canPay(int amount) {
            long available = 0;
            for (ISpecialSourceProvider provider : providers) {
                if (provider.isValid()) available += Math.max(0, provider.getSource().getSource());
                if (available >= amount) return true;
            }
            return amount == 0;
        }

        boolean pay(int amount) {
            if (!canPay(amount)) return false;
            for (ISpecialSourceProvider provider : providers) {
                if (amount <= 0) return true;
                if (!provider.isValid()) continue;
                ISourceTile source = provider.getSource();
                int before = source.getSource();
                int take = Math.min(amount, Math.max(0, before));
                if (take == 0) continue;
                // Follow Ars's SourceUtil contract. CreativeSourceJarTile intentionally keeps getSource()
                // at capacity after removal, so testing for a decreasing getter would reject that real provider.
                source.removeSource(take);
                amount -= take;
                if (animated.add(source)) ParticleUtil.spawnFollowProjectile(level, provider.getCurrentPos(), brazier);
            }
            return amount == 0;
        }
    }
}
