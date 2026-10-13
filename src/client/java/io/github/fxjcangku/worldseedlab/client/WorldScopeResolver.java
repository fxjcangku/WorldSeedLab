package io.github.fxjcangku.worldseedlab.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/** Derive a stable, local-only discriminator; never reads server Seed. */
final class WorldScopeResolver {
    private WorldScopeResolver() {}

    static String discriminator(Minecraft client) {
        ServerData server = client.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "remote:" + server.ip.trim().toLowerCase(java.util.Locale.ROOT);
        }
        if (client.getSingleplayerServer() != null) {
            String name = client.getSingleplayerServer().getWorldData().getLevelName();
            if (name != null && !name.isBlank()) {
                return "integrated:" + name;
            }
        }
        return null;
    }
}
