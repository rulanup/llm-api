package com.example.llmapi;

import com.example.llmapi.config.ApiConfig;
import com.example.llmapi.http.ApiHttpServer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

@Mod(LlmApiMod.MODID)
public class LlmApiMod {

    public static final String MODID = "llmapi";
    public static final String VERSION = "0.1";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final ApiHttpServer HTTP = new ApiHttpServer();
    private static volatile MinecraftServer SERVER;

    public LlmApiMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ApiConfig.SERVER_SPEC, "llmapi-server.toml");
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("[llmapi] mod loaded (version {})", VERSION);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        SERVER = event.getServer();
        HTTP.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        HTTP.stop();
        SERVER = null;
    }

    public static MinecraftServer runtimeServer() {
        return SERVER;
    }
}
