package com.arsstructura.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

import java.util.IdentityHashMap;
import java.util.Map;

/** Matches Forge's translucent particle target, with color-only writes for an overlay. */
final class GhostRenderTypes extends RenderType {
    static final RenderType MODEL = create("arsstructura_ghost_model", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 262144, false, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER).setTextureState(BLOCK_SHEET_MIPPED)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY).setOutputState(PARTICLES_TARGET)
                    .setWriteMaskState(COLOR_WRITE).createCompositeState(false));
    static final RenderType FLUID = create("arsstructura_ghost_fluid", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 262144, false, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER).setTextureState(BLOCK_SHEET_MIPPED)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP).setOutputState(PARTICLES_TARGET)
                    .setWriteMaskState(COLOR_WRITE).createCompositeState(false));
    private static final Map<RenderType, RenderType> SPECIAL = new IdentityHashMap<>();

    private GhostRenderTypes(RenderType original) {
        super("arsstructura_ghost_" + original, original.format(), original.mode(), original.bufferSize(),
                false, true, () -> {
                    original.setupRenderState();
                    PARTICLES_TARGET.setupRenderState();
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.depthMask(false);
                }, () -> {
                    original.clearRenderState();
                    PARTICLES_TARGET.clearRenderState();
                    RenderSystem.depthMask(true);
                    RenderSystem.disableBlend();
                });
    }

    static RenderType special(RenderType original) {
        return SPECIAL.computeIfAbsent(original, GhostRenderTypes::new);
    }

    static void clear() { SPECIAL.clear(); }
}
