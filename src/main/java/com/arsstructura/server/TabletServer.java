package com.arsstructura.server;

import com.arsstructura.ArsStructura;
import com.arsstructura.common.ConstructionTabletItem;
import com.arsstructura.common.PrefabConstructionTabletItem;
import com.arsstructura.config.ModConfig;
import com.arsstructura.data.ConstructionTabletData;
import com.arsstructura.data.SafeNbt;
import com.arsstructura.data.StructureData;
import com.arsstructura.network.ConstructionTabletNetwork;
import com.arsstructura.network.ConstructionTabletNetwork.*;
import com.arsstructura.ritual.RitualConstructionHandler;
import com.hollingsworth.arsnouveau.common.block.tile.RitualBrazierTile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.util.concurrent.*;

@Mod.EventBusSubscriber(modid = ArsStructura.MOD_ID)
public final class TabletServer {
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
    private static final Map<UUID, Long> LAST_IMPORT = new HashMap<>();
    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Scan> SCANS = new LinkedHashMap<>();
    private static final Set<UUID> DECODING = new HashSet<>();
    private record CachedStructure(byte[] bytes, int policy, StructureData data) {}
    private static final LinkedHashMap<UUID, CachedStructure> STRUCTURES = new LinkedHashMap<>(4, 0.75F, true);
    private static final ThreadPoolExecutor IMPORT_WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> { Thread thread = new Thread(task, "ArsStructura import validation"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());

    private static ItemStack held(ServerPlayer player, InteractionHand hand, UUID expected) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isAlive() || player.isSpectator() || !(stack.getItem() instanceof ConstructionTabletItem)) throw new IllegalArgumentException("Hold the construction tablet used for this request.");
        TabletStructureBackups.recover(stack);
        if (expected != null && !expected.equals(ConstructionTabletData.id(stack))) throw new IllegalArgumentException("The held tablet changed; reopen the screen.");
        return stack;
    }
    private static void requirePrefab(ItemStack stack) { if (!(stack.getItem() instanceof PrefabConstructionTabletItem)) throw new IllegalArgumentException("This operation requires a prefab construction tablet."); }
    private static void requireRevision(ItemStack stack, long revision) { if (ConstructionTabletData.revision(stack) != revision) throw new IllegalArgumentException("The tablet changed; refresh and try again."); }

    public static void handle(ServerPlayer player, Request request) {
        long tick = player.serverLevel().getGameTime();
        Long last = LAST_ACTION.get(player.getUUID());
        if (last != null && tick - last < 3) return;
        LAST_ACTION.put(player.getUUID(), tick);
        try {
            ItemStack stack = held(player, request.hand(), request.expectedId());
            ConstructionTabletData.ensureId(stack);
            switch (request.action()) {
                case BIND_ONE, BIND_TWO -> {
                    if (!player.isShiftKeyDown()) throw new IllegalArgumentException("Sneak while selecting corners.");
                    validateHit(player, request);
                    String dimension = player.level().dimension().location().toString();
                    if (!ConstructionTabletData.selectionDimension(stack).isEmpty() && !dimension.equals(ConstructionTabletData.selectionDimension(stack))) {
                        message(player, "dimension_mismatch"); return;
                    }
                    SCANS.remove(player.getUUID());
                    ConstructionTabletData.bind(stack, request.action() == Action.BIND_ONE, request.pos(), dimension);
                    ConstructionTabletData.setPreview(stack, false);
                    message(player, request.action() == Action.BIND_ONE ? "point_one" : "point_two", request.pos().toShortString());
                    BlockPos a = ConstructionTabletData.point(stack, true), b = ConstructionTabletData.point(stack, false);
                    if (a != null && b != null) {
                        Scan scan = new Scan(player, stack, request.hand(), a, b);
                        SCANS.put(player.getUUID(), scan);
                        message(player, "scan_started", scan.total);
                    }
                    sync(player, request.hand(), Purpose.SYNC, "");
                }
                case ANCHOR -> {
                    if (player.isShiftKeyDown()) throw new IllegalArgumentException("Release sneak to set the target.");
                    validateHit(player, request);
                    if (player.serverLevel().getBlockEntity(request.pos()) instanceof RitualBrazierTile brazier) {
                        SCANS.remove(player.getUUID());
                        RitualConstructionHandler.start(player, stack, brazier);
                        if (!stack.isEmpty()) sync(player, request.hand(), Purpose.SYNC, "");
                        return;
                    }
                    requireStructure(stack);
                    BlockPos anchor = request.pos().relative(request.face());
                    if (!player.serverLevel().getWorldBorder().isWithinBounds(anchor) || player.serverLevel().isOutsideBuildHeight(anchor)) throw new IllegalArgumentException("Target lies outside world bounds.");
                    ConstructionTabletData.anchor(stack, anchor, player.level().dimension().location().toString());
                    ConstructionTabletData.setPreview(stack, true);
                    message(player, "anchor", anchor.toShortString());
                    sync(player, request.hand(), Purpose.SYNC, "");
                }
                case OPEN_HUD -> { requirePrefab(stack); sync(player, request.hand(), Purpose.OPEN, ""); }
                case SAVE_NBT, SAVE_LITEMATIC -> {
                    requirePrefab(stack); requireRevision(stack, request.revision()); requireStructure(stack);
                    sync(player, request.hand(), request.action() == Action.SAVE_NBT ? Purpose.SAVE_NBT : Purpose.SAVE_LITEMATIC, request.name());
                }
                case PREVIEW -> {
                    requireStructure(stack);
                    if (ConstructionTabletData.anchor(stack) == null) { message(player, "missing_anchor"); return; }
                    if (!player.level().dimension().location().toString().equals(ConstructionTabletData.anchorDimension(stack))) { message(player, "dimension_mismatch"); return; }
                    if (!ConstructionTabletData.preview(stack)) ConstructionTabletData.setPreview(stack, true);
                    sync(player, request.hand(), Purpose.SYNC, "");
                }
                case CLEAR -> {
                    requirePrefab(stack); requireRevision(stack, request.revision()); SCANS.remove(player.getUUID()); UPLOADS.remove(player.getUUID());
                    ConstructionTabletData.clear(stack); message(player, "cleared"); sync(player, request.hand(), Purpose.SYNC, "");
                }
                case CANCEL -> {
                    ConstructionTabletData.setPreview(stack, false); sync(player, request.hand(), Purpose.SYNC, "");
                }
            }
        } catch (Exception e) { error(player, e); }
    }

    private static StructureData requireStructure(ItemStack stack) {
        byte[] bytes = ConstructionTabletData.encodedStructure(stack);
        UUID id = ConstructionTabletData.id(stack);
        int policy = Objects.hash(ModConfig.MAX_EDGE.get(), ModConfig.MAX_VOLUME.get(), ModConfig.MAX_BLOCKS.get(),
                ModConfig.MAX_NBT_BYTES.get(), ModConfig.MAX_NBT_DEPTH.get(), ModConfig.KEEP_INVENTORIES.get(),
                ModConfig.SAVE_AIR.get(), ModConfig.BLOCK_BLACKLIST.get(), ModConfig.ALLOWED_BLOCK_ENTITIES.get());
        CachedStructure cache = STRUCTURES.get(id);
        StructureData data;
        if (cache != null && cache.policy == policy && Arrays.equals(bytes, cache.bytes)) data = cache.data;
        else {
            data = bytes == null ? null : StructureData.fromNbt(SafeNbt.decompress(bytes));
            if (data != null) {
                if (STRUCTURES.size() >= 4) STRUCTURES.remove(STRUCTURES.keySet().iterator().next());
                STRUCTURES.put(id, new CachedStructure(bytes, policy, data));
            }
        }
        if (data == null || data.nonAirCount() == 0) throw new IllegalArgumentException("No saved non-air blocks. Select both corners or import a prefab first.");
        return data;
    }

    private static void validateHit(ServerPlayer player, Request request) {
        double reach = Math.min(32, player.getAttributeValue(ForgeMod.BLOCK_REACH.get()) + (player.isCreative() ? 0.5 : 0));
        Vec3 eyes = player.getEyePosition();
        BlockHitResult actual = player.level().clip(new ClipContext(eyes, eyes.add(player.getViewVector(1).scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (actual.getType() != HitResult.Type.BLOCK || !actual.getBlockPos().equals(request.pos()) || actual.getDirection() != request.face()
                || !player.serverLevel().hasChunkAt(request.pos()) || !player.serverLevel().mayInteract(player, request.pos())) throw new IllegalArgumentException("Selection must be a reachable, permitted block under your crosshair.");
    }

    public static void importChunk(ServerPlayer player, ImportChunk packet) {
        try {
            if (!ConstructionTabletNetwork.validChunk(packet.index(), packet.count(), packet.total(), packet.bytes().length) || packet.total() == 0 || packet.total() > ModConfig.MAX_COMPRESSED_BYTES.get()) throw new IllegalArgumentException("Invalid upload size or sequence.");
            ItemStack stack = held(player, packet.hand(), packet.tablet()); requirePrefab(stack); requireRevision(stack, packet.revision());
            if (!ModConfig.ALLOW_SURVIVAL_IMPORT.get() && !player.isCreative() && !player.hasPermissions(2)) throw new IllegalArgumentException("The server restricts prefab imports to creative players/operators.");
            if (packet.index() == 0) {
                long now = System.currentTimeMillis();
                if (DECODING.contains(player.getUUID()) || now - LAST_IMPORT.getOrDefault(player.getUUID(), 0L) < 3000) throw new IllegalArgumentException("Wait before importing another prefab.");
                LAST_IMPORT.put(player.getUUID(), now);
                if (UPLOADS.size() >= 8 && !UPLOADS.containsKey(player.getUUID())) throw new IllegalArgumentException("The import service is busy; try again shortly.");
                UPLOADS.put(player.getUUID(), new Upload(packet));
            }
            Upload upload = UPLOADS.get(player.getUUID());
            if (upload == null || !upload.matches(packet)) throw new IllegalArgumentException("Upload no longer belongs to the held tablet.");
            if (upload.assembly.add(packet.index(), packet.count(), packet.total(), packet.bytes())) {
                UPLOADS.remove(player.getUUID());
                byte[] bytes = upload.assembly.bytes();
                DECODING.add(player.getUUID());
                var server = player.getServer();
                try {
                    IMPORT_WORKER.execute(() -> {
                        try {
                            StructureData data = StructureData.fromNbt(SafeNbt.decompress(bytes)); data.validate();
                            if (data.nonAirCount() == 0) throw new IllegalArgumentException("The prefab contains no non-air blocks.");
                            server.execute(() -> {
                                DECODING.remove(player.getUUID());
                                if (player.hasDisconnected()) return;
                                try {
                                    ItemStack target = held(player, packet.hand(), packet.tablet()); requirePrefab(target); requireRevision(target, packet.revision());
                                    if (!ModConfig.ALLOW_SURVIVAL_IMPORT.get() && !player.isCreative() && !player.hasPermissions(2)) throw new IllegalArgumentException("The server restricts prefab imports to creative players/operators.");
                                    SCANS.remove(player.getUUID());
                                    ConstructionTabletData.setStructure(target, data); ConstructionTabletData.setPreview(target, false);
                                    message(player, "imported", data.nonAirCount()); sync(player, packet.hand(), Purpose.SYNC, "");
                                } catch (Exception e) { error(player, e); }
                            });
                        } catch (Exception e) { server.execute(() -> { DECODING.remove(player.getUUID()); if (!player.hasDisconnected()) error(player, e); }); }
                    });
                } catch (RejectedExecutionException e) { DECODING.remove(player.getUUID()); throw new IllegalArgumentException("The import service is busy; try again shortly."); }
            }
        } catch (Exception e) { UPLOADS.remove(player.getUUID()); error(player, e); }
    }

    private static void sync(ServerPlayer player, InteractionHand hand, Purpose purpose, String name) { ConstructionTabletNetwork.sync(player, hand, purpose, name); }
    private static void message(ServerPlayer player, String key, Object... args) { player.sendSystemMessage(Component.translatable("message.arsstructura." + key, args)); }
    private static void error(ServerPlayer player, Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        player.sendSystemMessage(Component.translatable("message.arsstructura.error", detail.substring(0, Math.min(detail.length(), 240))));
        ArsStructura.LOGGER.debug("Tablet request rejected: {}", detail);
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int budget = Math.max(1, ModConfig.SCAN_PER_TICK.get() / Math.max(1, SCANS.size()));
        Iterator<Scan> it = SCANS.values().iterator();
        while (it.hasNext()) {
            Scan scan = it.next();
            try { if (scan.step(budget)) it.remove(); }
            catch (Exception e) { it.remove(); if (!scan.player.hasDisconnected()) error(scan.player, e); }
        }
        UPLOADS.values().removeIf(upload -> System.currentTimeMillis() - upload.assembly.created > 30000);
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) ConstructionTabletNetwork.flush(player);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID(); SCANS.remove(id); UPLOADS.remove(id); LAST_ACTION.remove(id); LAST_IMPORT.remove(id); DECODING.remove(id); ConstructionTabletNetwork.removePlayer(id);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        SCANS.clear(); UPLOADS.clear(); LAST_ACTION.clear(); LAST_IMPORT.clear(); DECODING.clear(); STRUCTURES.clear(); ConstructionTabletNetwork.clearServer(); IMPORT_WORKER.getQueue().clear();
    }

    private static final class Upload {
        final ImportChunk first;
        final Assembly assembly;
        Upload(ImportChunk packet) { first = packet; assembly = new Assembly(packet.transfer(), packet.count(), packet.total()); }
        boolean matches(ImportChunk packet) { return first.transfer().equals(packet.transfer()) && first.tablet().equals(packet.tablet()) && first.revision() == packet.revision() && first.hand() == packet.hand(); }
    }

    private static final class Scan {
        final ServerPlayer player;
        final InteractionHand hand;
        final UUID tablet;
        final long revision;
        final String dimension;
        final BlockPos min;
        final int sx, sy, sz;
        final long total;
        final List<StructureData.BlockEntry> entries = new ArrayList<>();
        long cursor;
        long lastStatus;
        long retainedBytes;
        Scan(ServerPlayer player, ItemStack stack, InteractionHand hand, BlockPos a, BlockPos b) {
            this.player = player; this.hand = hand; this.tablet = ConstructionTabletData.id(stack); this.revision = ConstructionTabletData.revision(stack);
            dimension = player.level().dimension().location().toString();
            sx = Math.toIntExact(Math.abs((long)a.getX() - b.getX()) + 1); sy = Math.toIntExact(Math.abs((long)a.getY() - b.getY()) + 1); sz = Math.toIntExact(Math.abs((long)a.getZ() - b.getZ()) + 1);
            StructureData.validateSize(sx, sy, sz);
            min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            if (player.level().isOutsideBuildHeight(min) || player.level().isOutsideBuildHeight(min.offset(sx-1,sy-1,sz-1))) throw new IllegalArgumentException("Selection exceeds build height.");
            total = (long)sx * sy * sz;
        }
        boolean step(int budget) {
            if (player.hasDisconnected() || !player.isAlive() || !dimension.equals(player.level().dimension().location().toString())) return true;
            ItemStack stack = held(player, hand, tablet); requireRevision(stack, revision);
            for (int work = 0; work < budget && cursor < total; work++, cursor++) {
                int x = (int)(cursor % sx), z = (int)((cursor / sx) % sz), y = (int)(cursor / ((long)sx * sz));
                BlockPos relative = new BlockPos(x, y, z), pos = min.offset(relative);
                if (!player.serverLevel().hasChunkAt(pos)) throw new IllegalArgumentException("Selection contains an unloaded chunk; keep the whole region loaded.");
                if (!player.serverLevel().getWorldBorder().isWithinBounds(pos) || !player.serverLevel().mayInteract(player, pos)) throw new IllegalArgumentException("Selection contains protected or out-of-bounds blocks.");
                var state = player.level().getBlockState(pos);
                if (state.isAir() && !ModConfig.SAVE_AIR.get()) continue;
                if (ModConfig.isBlocked(state)) throw new IllegalArgumentException("Blocked block in selection: " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                if (entries.size() >= ModConfig.MAX_BLOCKS.get()) throw new IllegalArgumentException("Selection contains too many saved blocks.");
                CompoundTag nbt = null;
                if (ModConfig.SAVE_BLOCK_ENTITIES.get() && player.level().getBlockEntity(pos) != null) {
                    nbt = player.level().getBlockEntity(pos).saveWithFullMetadata();
                }
                var entry = new StructureData.BlockEntry(relative, state, nbt);
                CompoundTag sanitized = entry.blockEntity();
                if (sanitized != null) StructureData.validateBlockEntity(state, sanitized);
                retainedBytes += 256 + (sanitized == null ? 0 : SafeNbt.estimatedBytes(sanitized));
                if (retainedBytes > ModConfig.MAX_NBT_BYTES.get()) throw new IllegalArgumentException("Captured block entity data exceeds the memory limit.");
                entries.add(entry);
            }
            if (cursor == total) {
                StructureData data = new StructureData(sx, sy, sz, entries, "Construction", player.getGameProfile().getName(), System.currentTimeMillis(), ArsStructura.VERSION);
                data.validate();
                if (data.nonAirCount() == 0) throw new IllegalArgumentException("Selection contains no non-air blocks.");
                ConstructionTabletData.setStructure(stack, data);
                message(player, "saved", data.nonAirCount(), sx, sy, sz); sync(player, hand, Purpose.SYNC, "");
                return true;
            }
            if (player.serverLevel().getGameTime() - lastStatus >= 100) { lastStatus = player.serverLevel().getGameTime(); player.displayClientMessage(Component.translatable("message.arsstructura.scan_progress", cursor * 100 / total), true); }
            return false;
        }
    }
}
