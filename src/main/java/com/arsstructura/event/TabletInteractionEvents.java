package com.arsstructura.event;

import com.arsstructura.ArsStructura;
import com.arsstructura.common.ConstructionTabletItem;
import com.arsstructura.network.ConstructionTabletNetwork;
import com.arsstructura.ritual.ConstructionRitual;
import com.hollingsworth.arsnouveau.common.block.tile.RitualBrazierTile;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ArsStructura.MOD_ID)
public final class TabletInteractionEvents {
    @SubscribeEvent public static void leftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof ConstructionTabletItem) || !event.getEntity().isShiftKeyDown()) return;
        event.setCanceled(true);
        if (event.getLevel().isClientSide && event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) {
            ConstructionTabletNetwork.request(ConstructionTabletNetwork.Action.BIND_ONE, event.getHand(), event.getPos(), event.getFace(), "");
        }
    }
    @SubscribeEvent public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof ConstructionTabletItem)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        // Ars' empty-main-hand activation returns PASS. Do not turn the same click's
        // offhand fallback into a second installation when the tablet was moved offhand.
        if (event.getHand() == InteractionHand.OFF_HAND && event.getEntity().getMainHandItem().isEmpty()
                && !event.getEntity().isShiftKeyDown()
                && event.getLevel().getBlockEntity(event.getPos()) instanceof RitualBrazierTile brazier
                && brazier.ritual instanceof ConstructionRitual) return;
        if (event.getLevel().isClientSide) ConstructionTabletNetwork.request(event.getEntity().isShiftKeyDown()
                ? ConstructionTabletNetwork.Action.BIND_TWO : ConstructionTabletNetwork.Action.ANCHOR,
                event.getHand(), event.getPos(), event.getFace(), "");
    }
}
