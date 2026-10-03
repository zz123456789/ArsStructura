package com.arsstructura.client;

import com.arsstructura.config.ModConfig;
import com.arsstructura.data.StructureData;
import com.arsstructura.data.StructureData.BlockEntry;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(modid = "arsstructura", value = Dist.CLIENT)
public final class ClientPreviewHandler {
    private record Cell(BlockPos origin, List<BlockEntry> blocks) {}
    private static final List<Cell> CELLS = new ArrayList<>();
    private static final Map<Long, BlockState> STATES = new HashMap<>();
    private static final Set<BlockState> FAILED_MODELS = new HashSet<>();
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new BufferBuilder(262144));
    private static final RandomSource RANDOM = RandomSource.create();
    private static BlockPos anchor = BlockPos.ZERO;
    private static String dimension = "";
    private static boolean active;
    private static boolean limitNotified;

    private ClientPreviewHandler() {}

    /** Called only when a validated server snapshot arrives, never from the frame loop. */
    public static void set(@Nullable StructureData structure, BlockPos target, String targetDimension, boolean enabled) {
        clear();
        if (!enabled || structure == null || structure.blocks().isEmpty()) return;
        Map<Long, Cell> cells = new HashMap<>();
        for (BlockEntry entry : structure.blocks()) {
            if (entry.state().isAir()) continue;
            STATES.put(entry.pos().asLong(), entry.state());
            BlockPos origin = new BlockPos((entry.pos().getX() >> 4) << 4,
                    (entry.pos().getY() >> 4) << 4, (entry.pos().getZ() >> 4) << 4);
            cells.computeIfAbsent(origin.asLong(), key -> new Cell(origin, new ArrayList<>())).blocks().add(entry);
        }
        CELLS.addAll(cells.values());
        anchor = target.immutable();
        dimension = targetDimension;
        active = true;
    }

    public static void clear() {
        active = false;
        CELLS.clear();
        STATES.clear();
        FAILED_MODELS.clear();
        GhostRenderTypes.clear();
        limitNotified = false;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !active || mc.level == null
                || mc.player == null || !mc.level.dimension().location().toString().equals(dimension)) return;
        Vec3 camera = event.getCamera().getPosition();
        double distance = ModConfig.PREVIEW_DISTANCE.get();
        double maxDistanceSq = distance * distance;
        int budget = ModConfig.MAX_PREVIEW_BLOCKS.get();
        float alpha = ModConfig.GHOST_ALPHA.get().floatValue();
        PreviewWorld world = new PreviewWorld();
        PoseStack poses = event.getPoseStack();
        // Chunk-sized batches reject distant structures before looking at individual blocks.
        List<Cell> visible = new ArrayList<>();
        for (Cell cell : CELLS) {
            BlockPos origin = anchor.offset(cell.origin());
            AABB box = new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16, origin.getY() + 16, origin.getZ() + 16);
            if (box.inflate(distance).contains(camera) && event.getFrustum().isVisible(box)) visible.add(cell);
        }
        visible.sort(Comparator.comparingDouble(c -> -camera.distanceToSqr(Vec3.atCenterOf(anchor.offset(c.origin())))));
        int rendered = 0;
        boolean limited = false;
        outer: for (Cell cell : visible) {
            for (BlockEntry entry : cell.blocks()) {
                BlockPos pos = anchor.offset(entry.pos());
                if (camera.distanceToSqr(Vec3.atCenterOf(pos)) > maxDistanceSq || !event.getFrustum().isVisible(new AABB(pos))) continue;
                if (rendered >= budget) { limited = true; break outer; }
                if (FAILED_MODELS.contains(entry.state())) continue;
                poses.pushPose();
                try {
                    poses.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
                    drawModel(mc, poses, entry.state(), pos, alpha, world);
                    if (!entry.state().getFluidState().isEmpty()) {
                        poses.popPose();
                        poses.pushPose();
                        poses.translate((pos.getX() & ~15) - camera.x, (pos.getY() & ~15) - camera.y,
                                (pos.getZ() & ~15) - camera.z);
                        mc.getBlockRenderer().renderLiquid(pos, world, new GhostVertexConsumer(BUFFERS.getBuffer(GhostRenderTypes.FLUID),
                                alpha, poses.last().pose(), poses.last().normal()), entry.state(), entry.state().getFluidState());
                    }
                    ++rendered;
                } catch (RuntimeException ex) {
                    FAILED_MODELS.add(entry.state());
                    mc.player.displayClientMessage(Component.translatable("message.arsstructura.preview_model_failed", entry.state().getBlock().getName()), false);
                } finally {
                    poses.popPose();
                }
            }
        }
        BUFFERS.endBatch();
        if (limited && !limitNotified) {
            mc.player.displayClientMessage(Component.translatable("message.arsstructura.preview_limited", budget), false);
            limitNotified = true;
        }
    }

    private static void drawModel(Minecraft mc, PoseStack poses, BlockState state, BlockPos pos, float alpha, PreviewWorld world) {
        if (state.getRenderShape() == RenderShape.MODEL) {
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            VertexConsumer vertices = new GhostVertexConsumer(BUFFERS.getBuffer(GhostRenderTypes.MODEL), alpha);
            RANDOM.setSeed(state.getSeed(pos));
            for (RenderType layer : model.getRenderTypes(state, RANDOM, ModelData.EMPTY)) {
                for (Direction face : Direction.values()) {
                    if (!Block.shouldRenderFace(state, world, pos, face, pos.relative(face))) continue;
                    RANDOM.setSeed(state.getSeed(pos));
                    drawQuads(mc, poses, vertices, state, pos, model.getQuads(state, face, RANDOM, ModelData.EMPTY, layer));
                }
                RANDOM.setSeed(state.getSeed(pos));
                drawQuads(mc, poses, vertices, state, pos, model.getQuads(state, null, RANDOM, ModelData.EMPTY, layer));
            }
        } else if (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
            // Preserve custom renderer textures (chests, beds, etc.) while making their vertices translucent.
            mc.getBlockRenderer().renderSingleBlock(state, poses,
                    original -> new GhostVertexConsumer(BUFFERS.getBuffer(GhostRenderTypes.special(original)), alpha),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        }
    }

    private static void drawQuads(Minecraft mc, PoseStack poses, VertexConsumer vertices, BlockState state,
                                  BlockPos pos, List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            int color = quad.isTinted() ? mc.getBlockColors().getColor(state, mc.level, pos, quad.getTintIndex()) : 0xFFFFFF;
            float shade = quad.isShade() ? mc.level.getShade(quad.getDirection(), true) : 1;
            vertices.putBulkData(poses.last(), quad, ((color >> 16) & 255) / 255f * shade,
                    ((color >> 8) & 255) / 255f * shade, (color & 255) / 255f * shade,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
    }

    /** Read-only synthetic block view for fluid neighbour shapes; it never touches world blocks. */
    private static final class PreviewWorld implements BlockAndTintGetter {
        @Override public float getShade(Direction direction, boolean shade) { return Minecraft.getInstance().level.getShade(direction, shade); }
        @Override public LevelLightEngine getLightEngine() { return Minecraft.getInstance().level.getLightEngine(); }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return Minecraft.getInstance().level.getBlockTint(pos, resolver); }
        @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return STATES.getOrDefault(pos.subtract(anchor).asLong(), Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return Minecraft.getInstance().level.getHeight(); }
        @Override public int getMinBuildHeight() { return Minecraft.getInstance().level.getMinBuildHeight(); }
    }
}
