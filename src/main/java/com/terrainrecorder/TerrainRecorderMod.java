package com.terrainrecorder;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 常量与工具方法。本 mod 为纯客户端 mod，只加载在客户端，
 * 单人游戏（整合服务器在同一进程内）即可使用全部功能。
 */
public final class TerrainRecorderMod {
    public static final String MOD_ID = "terrainrecorder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private TerrainRecorderMod() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
