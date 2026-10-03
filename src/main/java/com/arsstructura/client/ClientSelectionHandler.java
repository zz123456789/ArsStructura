package com.arsstructura.client;

import com.arsstructura.ArsStructura;
import com.arsstructura.common.ConstructionTabletItem;
import com.arsstructura.config.ModConfig;
import com.arsstructura.data.ConstructionTabletData;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.OptionalDouble;

/** Selection outlines only read the server-synchronized item summary, including before a scan finishes. */
@Mod.EventBusSubscriber(modid = ArsStructura.MOD_ID, value = Dist.CLIENT)
public final class ClientSelectionHandler {
    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(8192));

    private ClientSelectionHandler() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;
        ItemStack tablet = mc.player.getMainHandItem();
        if (!(tablet.getItem() instanceof ConstructionTabletItem)) tablet = mc.player.getOffhandItem();
        if (!(tablet.getItem() instanceof ConstructionTabletItem)
                || !mc.level.dimension().location().toString().equals(ConstructionTabletData.selectionDimension(tablet))) return;

        // These accessors read only three small metadata fields; no structure/NBT payload is decoded per frame.
        BlockPos first = ConstructionTabletData.point(tablet, true);
        BlockPos second = ConstructionTabletData.point(tablet, false);
        if (first == null && second == null) return;
        Vec3 camera = event.getCamera().getPosition();
        double distance = ModConfig.PREVIEW_DISTANCE.get();
        double distanceSq = distance * distance;
        AABB firstBox = visible(first == null ? null : new AABB(first).inflate(0.01), event, camera, distanceSq);
        // Keep both colors visible when a one-block selection binds both corners to the same block.
        AABB secondBox = visible(second == null ? null : new AABB(second).inflate(second.equals(first) ? 0.075 : 0.01),
                event, camera, distanceSq);
        AABB region = null;
        if (first != null && second != null && !first.equals(second)) {
            region = visible(new AABB(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ()), Math.max(first.getX(), second.getX()) + 1.0,
                    Math.max(first.getY(), second.getY()) + 1.0, Math.max(first.getZ(), second.getZ()) + 1.0).inflate(0.003),
                    event, camera, distanceSq);
        }
        if (firstBox == null && secondBox == null && region == null) return;

        // A faint pass keeps hidden corners locatable; the depth-tested pass makes exposed edges bright.
        draw(event.getPoseStack(), SelectionRenderTypes.HIDDEN, camera, firstBox, secondBox, region, 0.20f);
        draw(event.getPoseStack(), SelectionRenderTypes.VISIBLE, camera, firstBox, secondBox, region, 0.95f);
    }

    @Nullable
    private static AABB visible(@Nullable AABB box, RenderLevelStageEvent event, Vec3 camera, double distanceSq) {
        if (box == null) return null;
        double dx = Math.max(box.minX - camera.x, Math.max(0.0, camera.x - box.maxX));
        double dy = Math.max(box.minY - camera.y, Math.max(0.0, camera.y - box.maxY));
        double dz = Math.max(box.minZ - camera.z, Math.max(0.0, camera.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz <= distanceSq && event.getFrustum().isVisible(box) ? box : null;
    }

    private static void draw(PoseStack poses, RenderType type, Vec3 camera, @Nullable AABB first,
                             @Nullable AABB second, @Nullable AABB region, float alpha) {
        VertexConsumer lines = BUFFERS.getBuffer(type);
        if (region != null) lineBox(poses, lines, region, camera, 1.0f, 0.85f, 0.45f, alpha * 0.65f);
        if (first != null) lineBox(poses, lines, first, camera, 0.20f, 0.90f, 1.0f, alpha);
        if (second != null) lineBox(poses, lines, second, camera, 1.0f, 0.45f, 0.15f, alpha);
        BUFFERS.endBatch(type);
    }

    private static void lineBox(PoseStack poses, VertexConsumer lines, AABB box, Vec3 camera,
                                float red, float green, float blue, float alpha) {
        // Subtract the camera before the vanilla helper converts coordinates to floats (world-border precision).
        LevelRenderer.renderLineBox(poses, lines, box.move(-camera.x, -camera.y, -camera.z), red, green, blue, alpha);
    }

    /** Vanilla line vertex/shader format, adapted to Forge's AFTER_PARTICLES target and color-only writes. */
    private static final class SelectionRenderTypes extends RenderType {
        private static final RenderType HIDDEN = lines("arsstructura_selection_hidden", false);
        private static final RenderType VISIBLE = lines("arsstructura_selection_visible", true);

        private SelectionRenderTypes() {
            super("arsstructura_selection", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES,
                    8192, false, false, () -> {}, () -> {});
        }

        private static RenderType lines(String name, boolean depthTest) {
            return create(name, DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 8192, false, false,
                    CompositeState.builder().setShaderState(RENDERTYPE_LINES_SHADER)
                            .setLineState(new LineStateShard(OptionalDouble.of(2.0)))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                            .setDepthTestState(depthTest ? LEQUAL_DEPTH_TEST : NO_DEPTH_TEST)
                            .setOutputState(PARTICLES_TARGET).setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        }
    }
}
