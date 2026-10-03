package com.arsstructura.common;

import com.arsstructura.ArsStructura;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRegistry {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ArsStructura.MOD_ID);
    public static final RegistryObject<Item> CONSTRUCTION_TABLET = ITEMS.register("construction_tablet", ConstructionTabletItem::new);
    public static final RegistryObject<Item> PREFAB_CONSTRUCTION_TABLET = ITEMS.register("prefab_construction_tablet", PrefabConstructionTabletItem::new);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ArsStructura.MOD_ID);
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("tablets", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.arsstructura"))
            .icon(() -> CONSTRUCTION_TABLET.get().getDefaultInstance())
            .displayItems((parameters, output) -> { output.accept(CONSTRUCTION_TABLET.get()); output.accept(PREFAB_CONSTRUCTION_TABLET.get()); }).build());

    public static void register(IEventBus bus) { ITEMS.register(bus); TABS.register(bus); }
    private ModRegistry() {}
}
