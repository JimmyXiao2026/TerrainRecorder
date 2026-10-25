package com.terrainrecorder;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量多语言文本表。
 *
 * <p>本 mod 需要把"相近语言"归到同一份文本：en_gb / en_au / en_ca 都用英文，
 * zh_hk / zh_mo 都用繁体中文。Minecraft 原生只按语言代码精确取文件（缺失时回退到
 * en_us），做不到 zh_hk → zh_tw，所以这里自己读 {@code assets/terrainrecorder/lang/*.json}，
 * 先按 {@link #group(String)} 归组再查表。
 *
 * <p>约定：文本里用 {@code %s} / {@code %d} 做占位符；只有带参数的条目才会走 String.format，
 * 所以含字面量 {@code %} 的短句只要不带参数就可以直接写（带参数又要输出 % 时需写成 {@code %%}）。
 */
public final class Lang {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final String PREFIX = "terrainrecorder.";
    private static final Map<String, Map<String, String>> TABLES = new HashMap<>();

    private Lang() {}

    /** 取当前语言的文本；缺失时回退到英文，再缺失则返回 key 本身（便于发现漏翻）。 */
    public static String tr(String key, Object... args) {
        String s = table(group(selected())).get(PREFIX + key);
        if (s == null) s = table("en_us").get(PREFIX + key);
        if (s == null) return key;
        if (args == null || args.length == 0) return s;
        return String.format(Locale.ROOT, s, args);
    }

    /**
     * 语言代码 → 文本分组：
     * en_* → en_us（美式/英式/澳洲/加拿大英语等共用）；
     * zh_tw / zh_hk / zh_mo / zh_hant_* → zh_tw（繁中台湾/香港/澳门共用）；
     * 其余 zh_* → zh_cn；
     * 其它语言 → en_us（国际默认）。
     */
    public static String group(String code) {
        if (code == null) return "en_us";
        String c = code.toLowerCase(Locale.ROOT).replace('-', '_');
        if (c.startsWith("zh")) {
            if (c.startsWith("zh_tw") || c.startsWith("zh_hk")
                    || c.startsWith("zh_mo") || c.startsWith("zh_hant")) {
                return "zh_tw";
            }
            return "zh_cn";
        }
        return "en_us";
    }

    private static String selected() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getLanguageManager() == null) return "en_us";
        return mc.getLanguageManager().getSelected();
    }

    private static Map<String, String> table(String code) {
        Map<String, String> t = TABLES.get(code);
        if (t != null) return t;
        t = load(code);
        TABLES.put(code, t);
        return t;
    }

    private static Map<String, String> load(String code) {
        String path = "/assets/terrainrecorder/lang/" + code + ".json";
        try (InputStream in = Lang.class.getResourceAsStream(path)) {
            if (in == null) return Map.of();
            Map<String, String> m = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), MAP_TYPE);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            TerrainRecorderMod.LOGGER.warn("读取语言文件失败：{}", path, e);
            return Map.of();
        }
    }
}
