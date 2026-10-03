package com.arsstructura.client;

import com.arsstructura.common.ConstructionTabletItem;
import com.arsstructura.data.ConstructionTabletData;
import com.arsstructura.data.StructureData;
import com.arsstructura.data.SafeNbt;
import com.arsstructura.file.PrefabFileManager;
import com.arsstructura.gui.PrefabHudScreen;
import com.arsstructura.network.ConstructionTabletNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/** Client copies are render/UI state only; never mutate the held ItemStack. */
@Mod.EventBusSubscriber(modid = "arsstructura", value = Dist.CLIENT)
public final class ClientTabletController {
    @Nullable private static UUID tablet;
    private static long revision = -1;
    private static InteractionHand hand = InteractionHand.MAIN_HAND;
    private static CompoundTag metadata = new CompoundTag();
    @Nullable private static StructureData structure;
    @Nullable private static UUID requested;
    private static long requestedRevision = -1;
    private static long ticks;
    private static long lastAutoRequest = -60;
    private static long lastUserAction = -20;
    private static int observedSlot = -1;
    @Nullable private static UUID observedMain;
    @Nullable private static UUID observedOff;
    private static int requestAttempts;
    @Nullable private static Object world;

    private ClientTabletController() {}

    public static void receive(UUID id, long receivedRevision, InteractionHand receivedHand, CompoundTag meta,
                               @Nullable StructureData data, ConstructionTabletNetwork.Purpose purpose, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ItemStack held = mc.player.getItemInHand(receivedHand);
        if (!id.equals(ConstructionTabletData.id(held))) return;
        if (purpose == ConstructionTabletNetwork.Purpose.SAVE_NBT || purpose == ConstructionTabletNetwork.Purpose.SAVE_LITEMATIC) {
            if (data == null) { status(Component.translatable("gui.arsstructura.empty"), true); return; }
            PrefabFileManager.Format format = purpose == ConstructionTabletNetwork.Purpose.SAVE_LITEMATIC
                    ? PrefabFileManager.Format.LITEMATIC : PrefabFileManager.Format.NBT;
            String fileName = name.isBlank() ? "prefab_" + System.currentTimeMillis() : name;
            boolean started = PrefabFileManager.async(() -> PrefabFileManager.save(fileName, format, data), result -> {
                status(Component.translatable("gui.arsstructura.saved", result), false);
                if (mc.screen instanceof PrefabHudScreen screen) screen.refreshFiles();
            }, ClientTabletController::fileError);
            if (!started) status(Component.translatable("gui.arsstructura.busy"), true);
        }
        if (id.equals(tablet) && receivedRevision < revision) return;
        if (receivedRevision < ConstructionTabletData.revision(held)) return;
        tablet = id;
        revision = receivedRevision;
        hand = receivedHand;
        metadata = meta.copy();
        structure = data;
        world = mc.level;
        requested = id;
        requestedRevision = receivedRevision;
        requestAttempts = 0;
        updatePreview();
        if (purpose == ConstructionTabletNetwork.Purpose.OPEN) {
            mc.setScreen(new PrefabHudScreen(id, receivedHand));
        } else if (mc.screen instanceof PrefabHudScreen screen && screen.tabletId().equals(id)) {
            screen.snapshotUpdated();
        }
    }

    public static CompoundTag metadata(UUID id) { return id.equals(tablet) ? metadata.copy() : new CompoundTag(); }
    public static long revision(UUID id) { return id.equals(tablet) ? revision : -1; }
    public static boolean hasStructure(UUID id) { return id.equals(tablet) && structure != null; }

    public static boolean holding(UUID id, InteractionHand inHand) {
        var player = Minecraft.getInstance().player;
        return player != null && id.equals(ConstructionTabletData.id(player.getItemInHand(inHand)));
    }

    public static void action(ConstructionTabletNetwork.Action action, InteractionHand inHand, String name) {
        lastUserAction = ticks;
        ConstructionTabletNetwork.request(action, inHand, BlockPos.ZERO, Direction.UP, name);
    }

    public static void importFile(String fileName, UUID id, InteractionHand inHand) {
        if (!holding(id, inHand)) { status(Component.translatable("gui.arsstructura.changed_item"), true); return; }
        Minecraft mc = Minecraft.getInstance();
        long expected = ConstructionTabletData.revision(mc.player.getItemInHand(inHand));
        var connection = mc.getConnection();
        // Files and compression stay off the render thread. The main-thread callback binds the
        // upload to the original connection and item before the server performs its own validation.
        boolean started = PrefabFileManager.async(() -> {
            StructureData imported = PrefabFileManager.read(fileName);
            return SafeNbt.compress(imported.toNbt());
        }, bytes -> {
            if (mc.getConnection() != connection || !holding(id, inHand)
                    || ConstructionTabletData.revision(mc.player.getItemInHand(inHand)) != expected) {
                status(Component.translatable("gui.arsstructura.changed_item"), true);
                return;
            }
            ConstructionTabletNetwork.importCompressed(inHand, id, expected, bytes);
            status(Component.translatable("gui.arsstructura.import_sent", fileName), false);
        }, ClientTabletController::fileError);
        if (!started) status(Component.translatable("gui.arsstructura.busy"), true);
    }

    private static void updatePreview() {
        ClientPreviewHandler.set(structure, BlockPos.of(metadata.getLong("Anchor")), metadata.getString("AnchorDimension"),
                metadata.getBoolean("Preview") && metadata.contains("Anchor"));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ++ticks;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (world != null) ConstructionTabletNetwork.clearClientTransfers();
            reset();
            return;
        }
        if (world != null && world != mc.level) {
            ConstructionTabletNetwork.clearClientTransfers();
            reset();
        }
        if (tablet != null && !holding(tablet, hand)) {
            reset();
            if (mc.screen instanceof PrefabHudScreen screen) screen.setStatus(Component.translatable("gui.arsstructura.changed_item"), true);
        }
        world = mc.level;
        int selectedSlot = mc.player.getInventory().selected;
        UUID mainId = ConstructionTabletData.id(mc.player.getMainHandItem());
        UUID offId = ConstructionTabletData.id(mc.player.getOffhandItem());
        if (observedSlot != selectedSlot || !Objects.equals(observedMain, mainId)
                || !Objects.equals(observedOff, offId)) {
            observedSlot = selectedSlot;
            observedMain = mainId;
            observedOff = offId;
            // Client END ticks can precede vanilla's selected-slot packet. Wait for a
            // stable held item before asking the server for a passive preview snapshot.
            lastUserAction = ticks;
        }
        if (mc.options.keyUse.isDown() || mc.options.keyAttack.isDown()) lastUserAction = ticks;
        // Let direct player actions reach the server first. Missing passive downloads get at most
        // three attempts, separated by 60 ticks, rather than flooding a throttled server.
        if (ticks - lastUserAction < 20 || ticks - lastAutoRequest < 60 || mc.screen instanceof PrefabHudScreen) return;
        for (InteractionHand candidate : InteractionHand.values()) {
            ItemStack held = mc.player.getItemInHand(candidate);
            if (!(held.getItem() instanceof ConstructionTabletItem)) continue;
            UUID id = ConstructionTabletData.id(held);
            if (id == null || !ConstructionTabletData.preview(held)) continue;
            if (!mc.level.dimension().location().toString().equals(ConstructionTabletData.anchorDimension(held))) continue;
            long currentRevision = ConstructionTabletData.revision(held);
            if (Objects.equals(tablet, id) && revision >= currentRevision) return;
            if (!Objects.equals(requested, id) || requestedRevision != currentRevision) {
                requested = id;
                requestedRevision = currentRevision;
                requestAttempts = 0;
            }
            if (requestAttempts >= 3) return;
            ++requestAttempts;
            lastAutoRequest = ticks;
            ConstructionTabletNetwork.request(ConstructionTabletNetwork.Action.PREVIEW, candidate, BlockPos.ZERO, Direction.UP, "");
            return;
        }
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ConstructionTabletNetwork.clearClientTransfers();
        reset();
    }

    @SubscribeEvent public static void serverMessage(ClientChatReceivedEvent.System event) {
        if (!(Minecraft.getInstance().screen instanceof PrefabHudScreen screen)) return;
        if (event.getMessage().getContents() instanceof TranslatableContents translated) {
            String key = translated.getKey();
            if (key.equals("message.arsstructura.error") || key.equals("message.arsstructura.missing_anchor")
                    || key.equals("message.arsstructura.dimension_mismatch")) screen.setStatus(event.getMessage(), true);
        }
    }

    private static void reset() {
        tablet = null;
        revision = -1;
        metadata = new CompoundTag();
        structure = null;
        requested = null;
        requestedRevision = -1;
        requestAttempts = 0;
        world = null;
        observedSlot = -1;
        observedMain = null;
        observedOff = null;
        ClientPreviewHandler.clear();
    }

    public static void status(Component text, boolean error) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PrefabHudScreen screen) screen.setStatus(text, error);
        else if (mc.player != null) mc.player.displayClientMessage(text, false);
    }

    public static void fileError(Throwable error) {
        String detail = PrefabFileManager.safeError(error);
        Component reason = detail.matches("prefab_[a-z_]+") ? Component.translatable("gui.arsstructura.error." + detail) : Component.literal(detail);
        status(Component.translatable("gui.arsstructura.file_error", reason), true);
    }
}
