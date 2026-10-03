package com.arsstructura.ritual;

import com.arsstructura.ArsStructura;
import com.hollingsworth.arsnouveau.api.ritual.AbstractRitual;
import com.hollingsworth.arsnouveau.client.particle.ParticleColor;
import com.hollingsworth.arsnouveau.common.block.tile.RitualBrazierTile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Public no-argument constructor is required by Ars Nouveau's RitualRegistry reflection. */
public final class ConstructionRitual extends AbstractRitual {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ArsStructura.MOD_ID, "construction");
    @Nullable UUID jobId;
    long lastStartNotice = -100;

    public ConstructionRitual() {}

    @Override
    public ResourceLocation getRegistryName() { return ID; }

    @Override
    public boolean canStart(@Nullable Player player) {
        if (jobId == null || player == null || isRunning() || isDone()) return false;
        if (getWorld() instanceof ServerLevel) return player instanceof ServerPlayer serverPlayer
                && RitualConstructionHandler.canActivate(this, serverPlayer);
        return tile != null && !tile.isOff;
    }

    @Override
    public void onStart(@Nullable Player player) {
        // RitualBrazierBlock performs the native empty-hand activation. Repeat the validation
        // here because addons can also invoke this callback directly; the server owns the state.
        if (!(player instanceof ServerPlayer serverPlayer)
                || !RitualConstructionHandler.activate(this, serverPlayer)) return;
        super.onStart(player);
        tile.updateBlock();
    }

    @Override
    public void tryTick(RitualBrazierTile tickingTile) {
        // Ars may pass a Brazier Relay here. The job always belongs to its original brazier.
        if (!(tickingTile.getLevel() instanceof ServerLevel level) || jobId == null || isDone()) return;
        ConstructionJobs store = ConstructionJobs.get(level.getServer());
        ConstructionJobs.Job job = store.jobs.get(jobId);
        if (job == null) { setFinished(); return; }
        if (!job.dimension.equals(level.dimension().location().toString())) { setFinished(); return; }
        if (!level.hasChunkAt(job.brazier)) return;
        if (!(level.getBlockEntity(job.brazier) instanceof RitualBrazierTile original) || original.ritual != this) {
            store.remove(jobId);
            setFinished();
            return;
        }
        this.tile = original;
        // SavedData is authoritative for activation and charging progress. Reconcile a stale
        // tile save without charging again or letting a waiting ritual begin building.
        if (getContext().isStarted != job.activated) {
            getContext().isStarted = job.activated;
            original.updateBlock();
        }
        if (!job.activated) return;
        tick();
    }

    @Override
    protected void tick() { RitualConstructionHandler.tick(this); }

    @Override
    public void onDestroy() {
        // Mark finished BEFORE Ars checks whether to drop a newly-created ritual tablet.
        setFinished();
        removeJob();
    }

    @Override
    public void onEnd() {
        super.onEnd();
        removeJob();
    }

    private void removeJob() {
        if (jobId != null && getWorld() instanceof ServerLevel level) {
            ConstructionJobs.get(level.getServer()).remove(jobId);
        }
    }

    @Override
    public void write(CompoundTag tag) {
        super.write(tag);
        if (jobId != null) tag.putUUID("ArsStructuraJob", jobId);
    }

    @Override
    public void read(CompoundTag tag) {
        super.read(tag);
        jobId = tag.hasUUID("ArsStructuraJob") ? tag.getUUID("ArsStructuraJob") : null;
        if (jobId == null) setFinished();
    }

    @Override
    public ParticleColor getCenterColor() { return new ParticleColor(94, 218, 245); }

    @Override
    public ParticleColor getOuterColor() { return new ParticleColor(173, 108, 238); }

    @Override
    public String getLangName() { return "Construction"; }
}
