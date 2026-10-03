package com.arsstructura.config;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeConfigSpec;

public final class ModConfig {
    private static final ForgeConfigSpec.Builder S = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue MAX_EDGE = S.comment("Maximum selection length on each axis; also bounded by volume, block count and NBT limits.").defineInRange("maxEdge", 640, 1, 640);
    public static final ForgeConfigSpec.LongValue MAX_VOLUME = S.defineInRange("maxVolume", 262144000L, 1L, 262144000L);
    public static final ForgeConfigSpec.IntValue MAX_BLOCKS = S.defineInRange("maxBlocks", 65536, 1, 262144);
    public static final ForgeConfigSpec.IntValue MAX_COMPRESSED_BYTES = S.comment("Hard capped below vanilla item NBT packet quota.").defineInRange("maxCompressedBytes", 1048576, 1024, 1048576);
    public static final ForgeConfigSpec.IntValue MAX_NBT_BYTES = S.defineInRange("maxNbtBytes", 33554432, 65536, 67108864);
    public static final ForgeConfigSpec.IntValue MAX_NBT_DEPTH = S.defineInRange("maxNbtDepth", 32, 4, 64);
    public static final ForgeConfigSpec.LongValue MAX_BACKUP_BYTES = S.comment("Persistent compressed structure archive used to restore metadata-only creative inventory packets.").defineInRange("maxBackupBytes", 67108864L, 8388608L, 536870912L);
    public static final ForgeConfigSpec.IntValue MAX_BACKUP_STRUCTURES = S.defineInRange("maxBackupStructures", 8192, 16, 65536);
    public static final ForgeConfigSpec.IntValue SCAN_PER_TICK = S.defineInRange("scanPerTick", 4096, 64, 16384);
    public static final ForgeConfigSpec.IntValue BUILD_PER_TICK = S.defineInRange("buildPerTick", 64, 1, 512);
    public static final ForgeConfigSpec.IntValue RITUAL_WARMUP_TICKS = S.comment("Ticks spent channeling after the owner activates the loaded brazier with an empty main hand. Saved per ritual; 20 ticks = 1 second.").defineInRange("ritualWarmupTicks", 100, 0, 72000);
    public static final ForgeConfigSpec.BooleanValue SAVE_AIR = S.define("saveAir", false);
    public static final ForgeConfigSpec.BooleanValue SAVE_BLOCK_ENTITIES = S.define("saveBlockEntities", true);
    public static final ForgeConfigSpec.BooleanValue KEEP_INVENTORIES = S.comment("Enabling permits duplication of container contents and loot tables through blueprints.").define("keepInventories", false);
    public static final ForgeConfigSpec.BooleanValue FORCE_REPLACE = S.comment("Still honors blacklists, world limits and placement protection.").define("forceReplace", false);
    public static final ForgeConfigSpec.IntValue SOURCE_START_COST = S.defineInRange("sourceStartCost", 100, 0, 10000);
    public static final ForgeConfigSpec.IntValue SOURCE_PER_BLOCK = S.defineInRange("sourcePerBlock", 10, 0, 10000);
    public static final ForgeConfigSpec.BooleanValue CONSUME_TABLET = S.define("consumeTablet", false);
    public static final ForgeConfigSpec.IntValue TABLET_DAMAGE = S.defineInRange("tabletDamage", 0, 0, 256);
    public static final ForgeConfigSpec.ConfigValue<String> FUEL_ITEM = S.define("fuelItem", "minecraft:amethyst_shard");
    public static final ForgeConfigSpec.IntValue FUEL_COUNT = S.defineInRange("fuelCount", 0, 0, 64);
    public static final ForgeConfigSpec.IntValue MAX_ANCHOR_DISTANCE = S.defineInRange("maxAnchorDistance", 256, 1, 4096);
    public static final ForgeConfigSpec.BooleanValue ALLOW_SURVIVAL_IMPORT = S.comment("Structure construction creates blocks using Source. Disable to restrict external imports to operators/creative players.").define("allowSurvivalImport", true);
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCK_BLACKLIST = S.defineListAllowEmpty("blockBlacklist", List.of(
            "minecraft:bedrock", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:barrier", "minecraft:light",
            "minecraft:end_portal", "minecraft:end_portal_frame", "minecraft:nether_portal", "minecraft:end_gateway",
            "minecraft:spawner", "minecraft:moving_piston", "minecraft:tnt", "ars_nouveau:ritual_brazier"), o -> o instanceof String);
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_BLOCK_ENTITIES = S.comment("Only these block entity types may be copied; add trusted mod types explicitly.").defineListAllowEmpty("allowedBlockEntities", List.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:sign", "minecraft:hanging_sign", "minecraft:banner", "minecraft:bed", "minecraft:skull", "minecraft:shulker_box",
            "minecraft:brewing_stand", "minecraft:campfire", "minecraft:decorated_pot", "minecraft:lectern", "minecraft:enchanting_table",
            "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper", "minecraft:chiseled_bookshelf", "minecraft:comparator", "minecraft:daylight_detector"), o -> o instanceof String);
    public static final ForgeConfigSpec SERVER_SPEC = S.build();
    private static final ForgeConfigSpec.Builder C = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue PREVIEW_DISTANCE = C.defineInRange("previewDistance", 96, 8, 256);
    public static final ForgeConfigSpec.IntValue MAX_PREVIEW_BLOCKS = C.comment("Maximum visible ghost blocks per frame; lower this on slower clients. A message is shown when the preview is truncated.").defineInRange("maxPreviewBlocks", 65536, 256, 262144);
    public static final ForgeConfigSpec.DoubleValue GHOST_ALPHA = C.defineInRange("ghostAlpha", 0.35, 0.05, 0.85);
    public static final ForgeConfigSpec.BooleanValue PAUSE_HUD = C.define("pauseHud", false);
    public static final ForgeConfigSpec CLIENT_SPEC = C.build();

    public static boolean isBlocked(BlockState state) { return BLOCK_BLACKLIST.get().contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); }
    public static boolean isAllowedBlockEntity(String id) { return ALLOWED_BLOCK_ENTITIES.get().contains(id); }
    private ModConfig() {}
}
