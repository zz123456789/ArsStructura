package com.arsstructura.data;

import com.arsstructura.config.ModConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;

/** Minimal real registry/config setup for codec tests without starting a game server. */
public final class TestEnvironment {
    private static boolean configured;
    private static boolean bootstrapped;

    private TestEnvironment() {}

    public static synchronized void config() {
        if (configured) return;
        CommentedConfig server = CommentedConfig.inMemory();
        ModConfig.SERVER_SPEC.correct(server);
        ModConfig.SERVER_SPEC.setConfig(server);
        CommentedConfig client = CommentedConfig.inMemory();
        ModConfig.CLIENT_SPEC.correct(client);
        ModConfig.CLIENT_SPEC.setConfig(client);
        configured = true;
    }

    public static synchronized void bootstrap() {
        config();
        if (bootstrapped) return;
        SharedConstants.tryDetectVersion();
        // ModLauncher normally supplies event no-arg constructors. Plain JUnit does not run
        // its transformer, so seed the same listener lists through real event instances.
        new NetworkEvent(() -> null).getListenerList();
        new NetworkEvent.GatherLoginPayloadsEvent(new ArrayList<>(), true).getListenerList();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }
}
