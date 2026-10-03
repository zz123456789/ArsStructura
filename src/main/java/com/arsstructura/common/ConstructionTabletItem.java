package com.arsstructura.common;

import com.arsstructura.data.ConstructionTabletData;
import com.arsstructura.network.ConstructionTabletNetwork;
import com.arsstructura.server.TabletStructureBackups;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import java.util.List;

public class ConstructionTabletItem extends Item {
    public ConstructionTabletItem() { super(new Properties().durability(256)); }

    @Override public @Nullable CompoundTag getShareTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;
        CompoundTag summary = new CompoundTag();
        for (String key : tag.getAllKeys()) if (!key.equals("ArsStructura")) summary.put(key, tag.get(key).copy());
        if (tag.contains("ArsStructura")) summary.put("ArsStructura", ConstructionTabletData.metadata(stack));
        return summary;
    }

    @Override public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return !player.isShiftKeyDown();
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!level.isClientSide && TabletStructureBackups.recover(stack) && entity instanceof Player player) {
            player.getInventory().setChanged();
        }
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) ConstructionTabletNetwork.request(ConstructionTabletNetwork.Action.CANCEL, hand, BlockPos.ZERO, Direction.UP, "");
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.arsstructura.bind").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.arsstructura.anchor").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.arsstructura.ritual").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("tooltip.arsstructura.activate").withStyle(ChatFormatting.GRAY));
        var meta = ConstructionTabletData.metadata(stack);
        if (meta.getBoolean("HasStructure")) lines.add(Component.translatable("tooltip.arsstructura.size", meta.getInt("SizeX"), meta.getInt("SizeY"), meta.getInt("SizeZ"), meta.getInt("BlockCount")).withStyle(ChatFormatting.GOLD));
    }
}
