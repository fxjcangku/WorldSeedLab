package io.github.fxjcangku.worldseedlab.client.scope;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * 获取用于本地隔离的世界标识源，仅用于 HMAC，绝不可写入可分享的日志。
 * 此逻辑不读取服务器 Seed；对代理服与同名存档仍需谨慎识别。
 */
public final class WorldScopeResolver {
    private WorldScopeResolver() {}

    public static String discriminator(Minecraft client) {
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
