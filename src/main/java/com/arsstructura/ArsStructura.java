package com.arsstructura;

import com.arsstructura.common.ModRegistry;
import com.arsstructura.config.ModConfig;
import com.arsstructura.network.ConstructionTabletNetwork;
import com.arsstructura.ritual.RitualConstructionHandler;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ArsStructura.MOD_ID)
public final class ArsStructura {
    public static final String MOD_ID = "arsstructura";
    public static final String VERSION = "1.0.1";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ArsStructura(FMLJavaModLoadingContext context) {
        ModRegistry.register(context.getModEventBus());
        context.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, ModConfig.SERVER_SPEC);
        context.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, ModConfig.CLIENT_SPEC);
        context.getModEventBus().addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) ->
                event.enqueueWork(() -> { ConstructionTabletNetwork.register(); RitualConstructionHandler.register(); }));
    }
}
