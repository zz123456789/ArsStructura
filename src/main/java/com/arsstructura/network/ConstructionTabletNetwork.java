package com.arsstructura.network;

import com.arsstructura.ArsStructura;
import com.arsstructura.client.ClientTabletController;
import com.arsstructura.data.ConstructionTabletData;
import com.arsstructura.data.SafeNbt;
import com.arsstructura.data.StructureData;
import com.arsstructura.server.TabletServer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Supplier;

/** Independent, versioned channel. All gameplay mutations happen on the logical server. */
public final class ConstructionTabletNetwork {
    public enum Action { BIND_ONE, BIND_TWO, ANCHOR, OPEN_HUD, SAVE_NBT, SAVE_LITEMATIC, PREVIEW, CLEAR, CANCEL }
    public enum Purpose { SYNC, OPEN, SAVE_NBT, SAVE_LITEMATIC }
    public static final int CHUNK_BYTES = 24576;
    public static final int MAX_TRANSFER_BYTES = 1048576;
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath(ArsStructura.MOD_ID, "tablets"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static final Map<UUID, ArrayDeque<SnapshotChunk>> OUTGOING = new HashMap<>();

    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder(Request::encode).decoder(Request::decode).consumerMainThread(Request::handle).add();
        CHANNEL.messageBuilder(ImportChunk.class, 1, NetworkDirection.PLAY_TO_SERVER).encoder(ImportChunk::encode).decoder(ImportChunk::decode).consumerMainThread(ImportChunk::handle).add();
        CHANNEL.messageBuilder(SnapshotChunk.class, 2, NetworkDirection.PLAY_TO_CLIENT).encoder(SnapshotChunk::encode).decoder(SnapshotChunk::decode).consumerMainThread(SnapshotChunk::handle).add();
    }

    /** Called exclusively on the client; referencing the client in a method body is dedicated-server safe. */
    public static void request(Action action, InteractionHand hand, BlockPos pos, Direction face, String name) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack stack = player.getItemInHand(hand);
        CHANNEL.sendToServer(new Request(action, hand, ConstructionTabletData.id(stack), ConstructionTabletData.revision(stack), pos, face == null ? Direction.UP : face, limitText(name)));
    }

    public static void importStructure(InteractionHand hand, UUID id, long revision, StructureData data) {
        byte[] bytes = SafeNbt.compress(data.toNbt());
        importCompressed(hand, id, revision, bytes);
    }

    public static void importCompressed(InteractionHand hand, UUID id, long revision, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_TRANSFER_BYTES || id == null) throw new IllegalArgumentException("Invalid import payload.");
        UUID transfer = UUID.randomUUID();
        int count = Math.max(1, (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        for (int i = 0; i < count; i++) CHANNEL.sendToServer(new ImportChunk(transfer, id, revision, hand, i, count, bytes.length, Arrays.copyOfRange(bytes, i * CHUNK_BYTES, Math.min(bytes.length, (i + 1) * CHUNK_BYTES))));
    }

    public static void sync(ServerPlayer player, InteractionHand hand, Purpose purpose, String name) {
        ItemStack stack = player.getItemInHand(hand);
        UUID id = ConstructionTabletData.ensureId(stack);
        byte[] stored = ConstructionTabletData.encodedStructure(stack);
        byte[] bytes = stored == null ? new byte[0] : stored;
        var queue = OUTGOING.computeIfAbsent(player.getUUID(), key -> new ArrayDeque<>());
        // Bound per-player retained payloads; discard stale passive previews, never accumulate unbounded requests.
        if (purpose == Purpose.SYNC) queue.removeIf(p -> p.purpose == Purpose.SYNC);
        if (queue.size() > 150) throw new IllegalArgumentException("Too many pending downloads; please wait.");
        UUID transfer = UUID.randomUUID();
        int count = Math.max(1, (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        CompoundTag meta = ConstructionTabletData.metadata(stack);
        for (int i = 0; i < count; i++) queue.add(new SnapshotChunk(transfer, id, ConstructionTabletData.revision(stack), hand, purpose, limitText(name), meta, i, count, bytes.length, Arrays.copyOfRange(bytes, i * CHUNK_BYTES, Math.min(bytes.length, (i + 1) * CHUNK_BYTES))));
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    public static void flush(ServerPlayer player) {
        var queue = OUTGOING.get(player.getUUID());
        if (queue == null) return;
        for (int i = 0; i < 2 && !queue.isEmpty(); i++) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), queue.remove());
        if (queue.isEmpty()) OUTGOING.remove(player.getUUID());
    }
    public static void removePlayer(UUID id) { OUTGOING.remove(id); }
    public static void clearServer() { OUTGOING.clear(); }
    public static void clearClientTransfers() { ClientTransfers.PENDING.clear(); }
    private static String limitText(String text) { return text == null ? "" : text.substring(0, Math.min(96, text.length())); }
    private static UUID readNullableUuid(FriendlyByteBuf buf) { return buf.readBoolean() ? buf.readUUID() : null; }
    private static void writeNullableUuid(FriendlyByteBuf buf, UUID id) { buf.writeBoolean(id != null); if (id != null) buf.writeUUID(id); }

    public record Request(Action action, InteractionHand hand, UUID expectedId, long revision, BlockPos pos, Direction face, String name) {
        void encode(FriendlyByteBuf buf) { buf.writeEnum(action); buf.writeEnum(hand); writeNullableUuid(buf, expectedId); buf.writeLong(revision); buf.writeBlockPos(pos); buf.writeEnum(face); buf.writeUtf(name, 96); }
        static Request decode(FriendlyByteBuf buf) { return new Request(buf.readEnum(Action.class), buf.readEnum(InteractionHand.class), readNullableUuid(buf), buf.readLong(), buf.readBlockPos(), buf.readEnum(Direction.class), buf.readUtf(96)); }
        void handle(Supplier<NetworkEvent.Context> ctx) { ServerPlayer player = ctx.get().getSender(); if (player != null) TabletServer.handle(player, this); ctx.get().setPacketHandled(true); }
    }

    public record ImportChunk(UUID transfer, UUID tablet, long revision, InteractionHand hand, int index, int count, int total, byte[] bytes) {
        void encode(FriendlyByteBuf buf) { buf.writeUUID(transfer); buf.writeUUID(tablet); buf.writeLong(revision); buf.writeEnum(hand); buf.writeVarInt(index); buf.writeVarInt(count); buf.writeVarInt(total); buf.writeByteArray(bytes); }
        static ImportChunk decode(FriendlyByteBuf buf) { return new ImportChunk(buf.readUUID(), buf.readUUID(), buf.readLong(), buf.readEnum(InteractionHand.class), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK_BYTES)); }
        void handle(Supplier<NetworkEvent.Context> ctx) { ServerPlayer player = ctx.get().getSender(); if (player != null) TabletServer.importChunk(player, this); ctx.get().setPacketHandled(true); }
    }

    public record SnapshotChunk(UUID transfer, UUID tablet, long revision, InteractionHand hand, Purpose purpose, String name, CompoundTag metadata, int index, int count, int total, byte[] bytes) {
        void encode(FriendlyByteBuf buf) { buf.writeUUID(transfer); buf.writeUUID(tablet); buf.writeLong(revision); buf.writeEnum(hand); buf.writeEnum(purpose); buf.writeUtf(name, 96); buf.writeNbt(metadata); buf.writeVarInt(index); buf.writeVarInt(count); buf.writeVarInt(total); buf.writeByteArray(bytes); }
        static SnapshotChunk decode(FriendlyByteBuf buf) { return new SnapshotChunk(buf.readUUID(), buf.readUUID(), buf.readLong(), buf.readEnum(InteractionHand.class), buf.readEnum(Purpose.class), buf.readUtf(96), Objects.requireNonNull(buf.readNbt()), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK_BYTES)); }
        void handle(Supplier<NetworkEvent.Context> ctx) { DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientTransfers.accept(this)); ctx.get().setPacketHandled(true); }
    }

    public static boolean validChunk(int index, int count, int total, int length) {
        if (total < 0 || total > MAX_TRANSFER_BYTES || count != Math.max(1, (total + CHUNK_BYTES - 1) / CHUNK_BYTES) || index < 0 || index >= count) return false;
        return length == Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES);
    }

    /** Bounded and strictly ordered; the network transport already guarantees ordering. */
    public static final class Assembly {
        public final UUID transfer;
        public final int count, total;
        public final long created = System.currentTimeMillis();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int next;
        public Assembly(UUID transfer, int count, int total) { this.transfer = transfer; this.count = count; this.total = total; }
        public boolean add(int index, int count, int total, byte[] bytes) {
            if (System.currentTimeMillis() - created > 30000 || !validChunk(index, count, total, bytes.length) || this.count != count || this.total != total || index != next) throw new IllegalArgumentException("Invalid or expired transfer.");
            buffer.writeBytes(bytes); next++;
            return next == count;
        }
        public byte[] bytes() { return buffer.toByteArray(); }
    }

    private static final class ClientTransfers {
        private static final Map<UUID, Assembly> PENDING = new LinkedHashMap<>();
        static void accept(SnapshotChunk chunk) {
            try {
                PENDING.values().removeIf(a -> System.currentTimeMillis() - a.created > 30000);
                if (!validChunk(chunk.index, chunk.count, chunk.total, chunk.bytes.length)) throw new IllegalArgumentException("Invalid structure download.");
                if (chunk.index == 0) { if (PENDING.size() >= 4) PENDING.clear(); PENDING.put(chunk.transfer, new Assembly(chunk.transfer, chunk.count, chunk.total)); }
                Assembly assembly = PENDING.get(chunk.transfer);
                if (assembly == null) return;
                if (assembly.add(chunk.index, chunk.count, chunk.total, chunk.bytes)) {
                    PENDING.remove(chunk.transfer);
                    StructureData data = chunk.total == 0 ? null : StructureData.fromNbt(SafeNbt.decompress(assembly.bytes()));
                    ClientTabletController.receive(chunk.tablet, chunk.revision, chunk.hand, chunk.metadata, data, chunk.purpose, chunk.name);
                }
            } catch (RuntimeException e) {
                PENDING.remove(chunk.transfer);
                ArsStructura.LOGGER.warn("Rejected structure download: {}", e.getMessage());
                if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.arsstructura.error", e.getMessage()), false);
            }
        }
    }
}
