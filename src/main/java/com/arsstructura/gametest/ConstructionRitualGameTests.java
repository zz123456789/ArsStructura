package com.arsstructura.gametest;

import com.arsstructura.ArsStructura;
import com.arsstructura.common.ModRegistry;
import com.arsstructura.config.ModConfig;
import com.arsstructura.data.ConstructionTabletData;
import com.arsstructura.data.StructureData;
import com.arsstructura.ritual.ConstructionJobs;
import com.arsstructura.ritual.ConstructionRitual;
import com.arsstructura.ritual.RitualConstructionHandler;
import com.arsstructura.server.TabletStructureBackups;
import com.hollingsworth.arsnouveau.common.block.RitualBrazierBlock;
import com.hollingsworth.arsnouveau.common.block.tile.RitualBrazierTile;
import com.hollingsworth.arsnouveau.common.block.tile.SourceJarTile;
import com.hollingsworth.arsnouveau.setup.registry.BlockRegistry;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Runs only in Forge's opt-in, disposable GameTest world, using the real Ars jar and block entities. */
@GameTestHolder(ArsStructura.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionRitualGameTests {
    private static final BlockPos BRAZIER = new BlockPos(2, 1, 2);
    private static final BlockPos JAR = new BlockPos(3, 1, 2);
    private static final BlockPos ANCHOR = new BlockPos(6, 1, 2);

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingSourcePreservesTabletAndFuel(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, simpleStructure());
        try {
            requireDefaults(helper);
            ItemStack before = fixture.tablet.copy();
            RitualConstructionHandler.start(fixture.player, fixture.tablet, fixture.brazier);
            helper.assertTrue(fixture.brazier.ritual == null, "An unpaid ritual was installed");
            helper.assertTrue(ItemStack.matches(before, fixture.tablet), "Rejected ritual changed the tablet");
            helper.assertBlockPresent(Blocks.AIR, ANCHOR);
            helper.succeed();
        } finally { fixture.close(); }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sourceGatesEachBlockAndSavedJobResumes(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, simpleStructure());
        try {
            requireDefaults(helper);
            int startup = ModConfig.SOURCE_START_COST.get(), each = ModConfig.SOURCE_PER_BLOCK.get();
            int warmup = ModConfig.RITUAL_WARMUP_TICKS.get();
            fixture.jar.setSource(startup + each);
            RitualConstructionHandler.start(fixture.player, fixture.tablet, fixture.brazier);
            helper.assertTrue(fixture.brazier.ritual instanceof ConstructionRitual, "Custom ritual was not installed");
            helper.assertTrue(!fixture.brazier.ritual.isRunning(), "Inserting a tablet lit the brazier without an empty-hand click");
            fixture.tick(warmup + 2);
            helper.assertBlockPresent(Blocks.AIR, ANCHOR);
            helper.assertTrue(fixture.jar.getSource() == each, "Waiting charged Source beyond the startup payment");
            helper.assertTrue(!fixture.savedJob().getBoolean("Activated")
                            && fixture.savedJob().getInt("WarmupRemaining") == warmup,
                    "The waiting ritual began consuming its warmup before activation");

            fixture.reload();
            helper.assertTrue(!fixture.brazier.ritual.isRunning() && !fixture.brazier.ritual.isDone(),
                    "Reload discarded the waiting ritual");
            fixture.activate();
            helper.assertTrue(fixture.brazier.ritual.isRunning(), "Ars's empty-hand brazier interaction did not activate the saved ritual");
            helper.assertTrue(fixture.brazier.getBlockState().getValue(RitualBrazierBlock.LIT), "Ars did not light the activated brazier");
            int elapsedWarmup = warmup / 2;
            fixture.tick(elapsedWarmup);
            helper.assertBlockPresent(Blocks.AIR, ANCHOR);
            helper.assertTrue(fixture.savedJob().getBoolean("Activated")
                            && fixture.savedJob().getInt("WarmupRemaining") == warmup - elapsedWarmup,
                    "Active warmup progress was not saved");
            fixture.reload();
            fixture.tick(warmup - elapsedWarmup);
            helper.assertBlockPresent(Blocks.AIR, ANCHOR);
            helper.assertTrue(fixture.jar.getSource() == each, "Activation/reload charged startup twice or built during warmup");
            helper.assertTrue(fixture.savedJob().getInt("WarmupRemaining") == 0, "Reload restarted the warmup countdown");
            fixture.brazier.ritual.tryTick(fixture.brazier);
            helper.assertBlockPresent(Blocks.STONE, ANCHOR);
            helper.assertBlockPresent(Blocks.AIR, ANCHOR.east());
            helper.assertTrue(fixture.jar.getSource() == 0, "First block cost was not paid exactly once");

            fixture.reload();
            fixture.brazier.ritual.tryTick(fixture.brazier);
            helper.assertBlockPresent(Blocks.AIR, ANCHOR.east());
            fixture.jar.setSource(each);
            fixture.brazier.ritual.tryTick(fixture.brazier);
            fixture.brazier.ritual.tryTick(fixture.brazier);
            helper.assertBlockPresent(Blocks.STONE, ANCHOR.east());
            helper.assertTrue(fixture.jar.getSource() == 0, "Resuming repeated startup payment or gave a free block");
            helper.assertTrue(fixture.brazier.ritual.isDone(), "Resumed ritual did not finish");
            helper.assertTrue(fixture.tablet.getCount() == 1 && fixture.tablet.getDamageValue() == 0, "Default ritual consumed its tablet");
            helper.succeed();
        } finally { fixture.close(); }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void protectionAndCollisionSkipWhileBlockEntityRestores(GameTestHelper helper) {
        CompoundTag chestNbt = new CompoundTag();
        chestNbt.putString("id", "minecraft:chest");
        chestNbt.putString("CustomName", "\"Preserved chest\"");
        var chestState = Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        StructureData structure = new StructureData(3, 1, 1, List.of(
                new StructureData.BlockEntry(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null),
                new StructureData.BlockEntry(BlockPos.ZERO.east(), chestState, chestNbt),
                new StructureData.BlockEntry(BlockPos.ZERO.east(2), Blocks.STONE.defaultBlockState(), null)),
                "Protection", "GameTest", 1L, ArsStructura.VERSION);
        Fixture fixture = new Fixture(helper, structure);
        PlacementDenial denial = new PlacementDenial(helper.absolutePos(ANCHOR));
        MinecraftForge.EVENT_BUS.register(denial);
        try {
            requireDefaults(helper);
            helper.setBlock(ANCHOR.east(2), Blocks.IRON_BLOCK);
            fixture.jar.setSource(ModConfig.SOURCE_START_COST.get() + ModConfig.SOURCE_PER_BLOCK.get());
            RitualConstructionHandler.start(fixture.player, fixture.tablet, fixture.brazier);
            helper.assertTrue(fixture.brazier.ritual instanceof ConstructionRitual, "Custom ritual was not installed");
            fixture.activate();
            fixture.tick(ModConfig.RITUAL_WARMUP_TICKS.get());
            fixture.brazier.ritual.tryTick(fixture.brazier);
            fixture.brazier.ritual.tryTick(fixture.brazier);
            helper.assertBlockPresent(Blocks.AIR, ANCHOR);
            helper.assertBlockPresent(Blocks.IRON_BLOCK, ANCHOR.east(2));
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(ANCHOR.east())).equals(chestState), "Chest BlockState did not restore");
            CompoundTag restored = helper.getLevel().getBlockEntity(helper.absolutePos(ANCHOR.east())).saveWithFullMetadata();
            helper.assertTrue(restored.getString("CustomName").contains("Preserved chest"), "Allowed block-entity NBT was lost");
            helper.assertTrue(restored.getInt("x") == helper.absolutePos(ANCHOR.east()).getX(), "Block-entity coordinates were not relocated");
            helper.assertTrue(fixture.jar.getSource() == 0, "Skipped blocks were charged or restored block was free");
            helper.succeed();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(denial);
            fixture.close();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void destroyingWaitingOrWarmingBrazierDoesNotDuplicateTablet(GameTestHelper helper) {
        requireDefaults(helper);
        for (boolean activate : List.of(false, true)) {
            try (Fixture fixture = new Fixture(helper, simpleStructure())) {
                fixture.jar.setSource(ModConfig.SOURCE_START_COST.get());
                RitualConstructionHandler.start(fixture.player, fixture.tablet, fixture.brazier);
                helper.assertTrue(fixture.brazier.ritual instanceof ConstructionRitual, "Custom ritual was not installed");
                if (activate) {
                    fixture.activate();
                    fixture.tick(1);
                }
                BlockPos position = helper.absolutePos(BRAZIER);
                BlockRegistry.RITUAL_BLOCK.get().playerWillDestroy(helper.getLevel(), position,
                        helper.getLevel().getBlockState(position), fixture.player);
                helper.assertTrue(fixture.brazier.ritual.isDone(), "Destroyed ritual was not cancelled");
                helper.assertTrue(fixture.tablet.getCount() == 1, "Default tablet was lost on cancellation");
                helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(2))
                                .stream().noneMatch(entity -> entity.getItem().is(ModRegistry.CONSTRUCTION_TABLET.get())),
                        "Breaking a waiting/warming brazier duplicated a construction tablet");
                helper.assertBlockPresent(Blocks.AIR, ANCHOR);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void onlyOwnerCanActivateAndRedstonePausesWarmup(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper, simpleStructure()); TestPlayer other = new TestPlayer(helper)) {
            requireDefaults(helper);
            int each = ModConfig.SOURCE_PER_BLOCK.get();
            fixture.jar.setSource(ModConfig.SOURCE_START_COST.get() + 2 * each);
            RitualConstructionHandler.start(fixture.player, fixture.tablet, fixture.brazier);
            helper.assertTrue(fixture.brazier.ritual instanceof ConstructionRitual, "Custom ritual was not installed");
            fixture.useBrazier(other.player);
            helper.assertTrue(!fixture.brazier.ritual.isRunning() && !fixture.savedJob().getBoolean("Activated"),
                    "A different player activated the owner's pending ritual");
            fixture.power(true);
            fixture.activate();
            helper.assertTrue(!fixture.brazier.ritual.isRunning(), "A powered brazier accepted activation");
            fixture.tick(5);
            helper.assertTrue(fixture.savedJob().getInt("WarmupRemaining") == ModConfig.RITUAL_WARMUP_TICKS.get(),
                    "Rejected activation advanced warmup");
            fixture.power(false);
            fixture.activate();
            helper.assertTrue(fixture.brazier.ritual.isRunning(), "Owner could not activate after removing redstone power");
            fixture.tick(1);
            int remaining = fixture.savedJob().getInt("WarmupRemaining");
            fixture.power(true);
            fixture.tick(5);
            helper.assertTrue(fixture.savedJob().getInt("WarmupRemaining") == remaining, "Redstone failed to pause active warmup");
            helper.assertTrue(fixture.jar.getSource() == 2 * each, "Blocked activation or paused warmup consumed Source");
            helper.assertBlockPresent(Blocks.AIR, ANCHOR);
            fixture.power(false);
            fixture.tick(remaining);
            helper.assertBlockPresent(Blocks.AIR, ANCHOR);
            fixture.tick(2);
            helper.assertBlockPresent(Blocks.STONE, ANCHOR);
            helper.assertBlockPresent(Blocks.STONE, ANCHOR.east());
            helper.assertTrue(fixture.brazier.ritual.isDone(), "Unpowered ritual did not resume and complete");
            helper.assertTrue(fixture.jar.getSource() == 0, "Resumed ritual charged an incorrect Source amount");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void creativeMetadataRoundTripRecoversAfterBackupReload(GameTestHelper helper) {
        CompoundTag chestNbt = new CompoundTag();
        chestNbt.putString("id", "minecraft:chest");
        chestNbt.putString("CustomName", "\"Creative recovery\"");
        StructureData structure = new StructureData(1, 1, 1,
                List.of(new StructureData.BlockEntry(BlockPos.ZERO, Blocks.CHEST.defaultBlockState(), chestNbt)),
                "Creative backup", "GameTest", 2L, ArsStructura.VERSION);
        ItemStack original = new ItemStack(ModRegistry.PREFAB_CONSTRUCTION_TABLET.get());
        ConstructionTabletData.setStructure(original, structure);
        long revision = ConstructionTabletData.revision(original);
        CompoundTag shareTag = original.getItem().getShareTag(original);
        helper.assertTrue(!shareTag.getCompound("ArsStructura").contains("Structure"), "Vanilla item packet exposed the large payload");
        ItemStack returned = new ItemStack(original.getItem());
        returned.getItem().readShareTag(returned, shareTag.copy());
        helper.assertTrue(ConstructionTabletData.structure(returned) == null, "Fixture should imitate a metadata-only creative packet");
        helper.assertTrue(TabletStructureBackups.recover(returned), "Trusted backup did not restore the creative item");
        helper.assertTrue(structure.equals(ConstructionTabletData.structure(returned)), "Recovered BlockState or block-entity data differs");
        helper.assertTrue(ConstructionTabletData.revision(returned) == revision, "Hidden payload recovery changed the visible revision");
        helper.assertTrue(!TabletStructureBackups.recover(returned), "Complete items should need no further copying");

        var server = helper.getLevel().getServer();
        CompoundTag saved = TabletStructureBackups.get(server).save(new CompoundTag());
        server.overworld().getDataStorage().set(TabletStructureBackups.STORAGE_NAME, TabletStructureBackups.load(saved));
        ItemStack afterReload = new ItemStack(original.getItem());
        afterReload.getItem().readShareTag(afterReload, shareTag.copy());
        helper.assertTrue(TabletStructureBackups.recover(afterReload), "Persisted backup was unavailable after world-data reload");
        helper.assertTrue(structure.equals(ConstructionTabletData.structure(afterReload)), "Persisted backup changed the structure");
        helper.succeed();
    }

    private static void requireDefaults(GameTestHelper helper) {
        helper.assertTrue(ModConfig.SOURCE_START_COST.get() > 0 && ModConfig.SOURCE_PER_BLOCK.get() > 0,
                "GameTests require positive default Source costs");
        helper.assertTrue(ModConfig.RITUAL_WARMUP_TICKS.get() >= 2, "GameTests require at least two warmup ticks");
        helper.assertTrue(!ModConfig.FORCE_REPLACE.get() && !ModConfig.CONSUME_TABLET.get()
                        && ModConfig.TABLET_DAMAGE.get() == 0 && ModConfig.FUEL_COUNT.get() == 0,
                "GameTests require default replacement and tablet/fuel policies");
    }

    private static StructureData simpleStructure() {
        return new StructureData(2, 1, 1, List.of(
                new StructureData.BlockEntry(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null),
                new StructureData.BlockEntry(BlockPos.ZERO.east(), Blocks.STONE.defaultBlockState(), null)),
                "Source test", "GameTest", 1L, ArsStructura.VERSION);
    }

    public static final class PlacementDenial {
        private final BlockPos target;
        PlacementDenial(BlockPos target) { this.target = target; }
        @SubscribeEvent public void deny(BlockEvent.EntityPlaceEvent event) {
            if (event.getPos().equals(target)) event.setCanceled(true);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerPlayer player;
        final RitualBrazierTile brazier;
        final SourceJarTile jar;
        final ItemStack tablet;
        final TestPlayer connectedPlayer;

        Fixture(GameTestHelper helper, StructureData structure) {
            this.helper = helper;
            helper.setBlock(BRAZIER, Blocks.AIR);
            helper.setBlock(BRAZIER, BlockRegistry.RITUAL_BLOCK.get());
            helper.setBlock(JAR, BlockRegistry.SOURCE_JAR.get());
            for (int i = 0; i < 3; i++) helper.setBlock(ANCHOR.east(i), Blocks.AIR);
            brazier = (RitualBrazierTile) helper.getLevel().getBlockEntity(helper.absolutePos(BRAZIER));
            jar = (SourceJarTile) helper.getLevel().getBlockEntity(helper.absolutePos(JAR));
            jar.setSource(0);
            connectedPlayer = new TestPlayer(helper);
            player = connectedPlayer.player;
            tablet = new ItemStack(ModRegistry.CONSTRUCTION_TABLET.get());
            ConstructionTabletData.setStructure(tablet, structure);
            ConstructionTabletData.anchor(tablet, helper.absolutePos(ANCHOR), helper.getLevel().dimension().location().toString());
            player.setItemInHand(InteractionHand.MAIN_HAND, tablet);
        }

        void activate() {
            // Keep the tablet in slot 0 and select an empty hand, just as a player does after loading the brazier.
            player.getInventory().selected = 1;
            helper.assertTrue(player.getMainHandItem().isEmpty(), "Activation fixture did not select an empty hand");
            useBrazier(player);
        }

        void useBrazier(ServerPlayer actor) {
            BlockPos pos = helper.absolutePos(BRAZIER);
            BlockRegistry.RITUAL_BLOCK.get().use(helper.getLevel().getBlockState(pos), helper.getLevel(), pos, actor,
                    InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        }

        void tick(int count) {
            for (int i = 0; i < count; i++) brazier.ritual.tryTick(brazier);
        }

        CompoundTag savedJob() {
            var jobs = ConstructionJobs.get(helper.getLevel().getServer()).save(new CompoundTag()).getList("Jobs", 10);
            for (int i = 0; i < jobs.size(); i++) {
                CompoundTag job = jobs.getCompound(i);
                if (job.getUUID("Owner").equals(player.getUUID()) && job.getLong("Brazier") == brazier.getBlockPos().asLong()) return job;
            }
            throw new AssertionError("Expected persisted construction job was missing");
        }

        void reload() {
            CompoundTag savedTile = brazier.saveWithFullMetadata();
            helper.assertTrue(savedTile.toString().length() < 4096, "Brazier update data contains the structure payload");
            var server = helper.getLevel().getServer();
            CompoundTag savedJobs = ConstructionJobs.get(server).save(new CompoundTag());
            server.overworld().getDataStorage().set("arsstructura_construction_jobs", ConstructionJobs.load(savedJobs));
            brazier.load(savedTile);
        }

        void power(boolean powered) {
            BlockPos signal = BRAZIER.west();
            helper.setBlock(signal, powered ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
            BlockPos pos = helper.absolutePos(BRAZIER);
            BlockRegistry.RITUAL_BLOCK.get().neighborChanged(helper.getLevel().getBlockState(pos), helper.getLevel(), pos,
                    Blocks.REDSTONE_BLOCK, helper.absolutePos(signal), false);
            helper.assertTrue(brazier.isOff == powered, "Ars brazier did not receive the redstone state");
        }

        @Override public void close() {
            if (brazier.ritual != null) brazier.ritual.onDestroy();
            connectedPlayer.close();
        }
    }

    private static final class TestPlayer implements AutoCloseable {
        final GameTestHelper helper;
        final ServerPlayer player;
        final EmbeddedChannel channel;

        TestPlayer(GameTestHelper helper) {
            this.helper = helper;
            // Vanilla's helper passes an inactive Connection, but Forge's login hooks require a channel pipeline.
            Connection connection = new Connection(PacketFlow.SERVERBOUND) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, PacketSendListener listener) {}
                @Override public boolean isMemoryConnection() { return true; }
            };
            channel = new EmbeddedChannel(connection);
            connection.setProtocol(ConnectionProtocol.PLAY);
            UUID id = UUID.randomUUID();
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(id, "str-" + id.toString().substring(0, 8)));
            try {
                helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player);
            } catch (RuntimeException exception) {
                if (helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player) {
                    helper.getLevel().getServer().getPlayerList().remove(player);
                }
                channel.finishAndReleaseAll();
                throw exception;
            }
            BlockPos position = helper.absolutePos(BRAZIER.south());
            player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        }

        @Override public void close() {
            helper.getLevel().getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
    }
}
