package com.terrainrecorder.snapshot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.terrainrecorder.TerrainRecorderMod;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 一张地形快照：BlockPos -> 方块ID。
 * 只记录普通方块信息（方块类型ID，不记录详细属性），用于快速比对与扩充。
 * 文件为 NDJSON，每行 {"x","y","z","b"}。
 *
 * <p>读写都是流式的（一行一行写/读），避免大范围快照一次性拼出巨大的 String / List 导致 OOM；
 * 读取时单行损坏只跳过该行并记日志，不会让 /find、/recmode 直接抛异常。
 *
 * <p>加载时顺手记录"哪些区块有数据"（chunkIndex），/find 只比对这些区块，
 * 不需要再遍历几百万个方块单独算一遍覆盖范围。
 */
public final class Snapshot {
    private static final Gson GSON = new Gson();
    /** 读文件用的缓冲区（大文件时比默认 8KB 快不少）。 */
    private static final int IO_BUFFER = 1 << 20;

    private final Map<BlockPos, String> blocks = new HashMap<>();
    /** 有方块的区块（ChunkPos.asLong，快照自身坐标）。 */
    private final LongSet chunkIndex = new LongOpenHashSet();
    private boolean chunkIndexKnown;

    public Snapshot() {}

    public Snapshot(Map<BlockPos, String> blocks) { this.blocks.putAll(blocks); }

    public Map<BlockPos, String> blocks() { return blocks; }
    public int size() { return blocks.size(); }
    public boolean containsPos(BlockPos p) { return blocks.containsKey(p); }

    public boolean contains(BlockPos p, String id) {
        String cur = blocks.get(p);
        return cur != null && cur.equals(id);
    }

    private void putBlock(int x, int y, int z, String id) {
        blocks.put(new BlockPos(x, y, z), id);
        chunkIndex.add(ChunkPos.asLong(x >> 4, z >> 4));
        chunkIndexKnown = true;
    }

    /**
     * 有数据的区块集合（快照自身坐标）。正常情况下加载/拍摄时已经建好，
     * 只有通过 {@link #blocks()} 直接改过内容才需要重建一次。
     */
    public LongSet chunks() {
        if (!chunkIndexKnown) {
            chunkIndex.clear();
            for (BlockPos p : blocks.keySet())
                chunkIndex.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
            chunkIndexKnown = true;
        }
        return chunkIndex;
    }

    /** 只加没有的方块，不减少、不改写已有的。 */
    public void mergeAdd(Map<BlockPos, String> add) {
        for (Map.Entry<BlockPos, String> e : add.entrySet())
            if (!blocks.containsKey(e.getKey())) putBlock(e.getKey().getX(), e.getKey().getY(), e.getKey().getZ(), e.getValue());
    }

    /**
     * 方块 → 注册名 的缓存。1.21.11 的 Identifier.toString() 每次都会新建字符串，
     * 而扫描时每个非空气方块都要取一次 ID，缓存后可以省掉大量临时字符串（扫描速度提升明显）。
     * 只在客户端线程使用。
     */
    private static final Map<Block, String> ID_CACHE = new HashMap<>();

    public static String blockId(BlockState state) {
        Block block = state.getBlock();
        String id = ID_CACHE.get(block);
        if (id == null) {
            id = BuiltInRegistries.BLOCK.getKey(block).toString();
            ID_CACHE.put(block, id);
        }
        return id;
    }

    /**
     * 方块遍历回调。坐标以 int 传入，避免给每个非空气方块都分配一个 BlockPos；
     * 需要存进集合/映射时由调用方自己 new BlockPos（或改用可复用对象做查询）。
     */
    public interface BlockVisitor { void visit(int x, int y, int z, String id); }

    /** 遍历某个客户端区块内所有非空气方块（普通方块信息）。 */
    public static void forEachBlock(ClientLevel level, LevelChunk chunk, BlockVisitor visitor) {
        forEachBlock(level, chunk, 1, visitor);
    }

    /**
     * 按步长遍历区块内的方块：只读取每轴 1/step 的位置（总读取量 1/step³），
     * 用于对齐投票这类"只要采样就够"的场景，比全量遍历快几十倍。
     */
    public static void forEachBlockSampled(ClientLevel level, LevelChunk chunk, int step, BlockVisitor visitor) {
        forEachBlock(level, chunk, Math.max(1, step), visitor);
    }

    private static void forEachBlock(ClientLevel level, LevelChunk chunk, int step, BlockVisitor visitor) {
        int minY = level.getMinY();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) continue;
            int yBase = minY + si * 16;
            for (int lx = 0; lx < 16; lx += step)
                for (int ly = 0; ly < 16; ly += step)
                    for (int lz = 0; lz < 16; lz += step) {
                        BlockState st = sec.getBlockState(lx, ly, lz);
                        if (st.isAir()) continue;
                        visitor.visit(baseX + lx, yBase + ly, baseZ + lz, blockId(st));
                    }
        }
    }

    /** 扫描客户端内存中已加载区块，生成快照。 */
    public static Snapshot capture(ClientLevel level, Collection<ChunkPos> chunks) {
        Snapshot s = new Snapshot();
        for (ChunkPos cp : chunks) {
            LevelChunk chunk = level.getChunkSource().getChunk(cp.x, cp.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            forEachBlock(level, chunk, (x, y, z, id) -> s.putBlock(x, y, z, id));
        }
        s.chunkIndexKnown = true;
        return s;
    }

    /**
     * 流式写入 NDJSON：先写同目录的 .tmp，再原子替换目标文件。
     * 这样保存中途被中断也不会留下半截快照覆盖掉旧文件。
     */
    public void save(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8), IO_BUFFER)) {
                for (Map.Entry<BlockPos, String> e : blocks.entrySet()) {
                    BlockPos p = e.getKey();
                    w.write("{\"x\":");
                    w.write(Integer.toString(p.getX()));
                    w.write(",\"y\":");
                    w.write(Integer.toString(p.getY()));
                    w.write(",\"z\":");
                    w.write(Integer.toString(p.getZ()));
                    w.write(",\"b\":\"");
                    w.write(e.getValue()); // 方块 ID 只会是 [a-z0-9_.-/] 与命名空间冒号，无需转义
                    w.write("\"}\n");
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            throw e;
        }
    }

    /**
     * 流式读取 NDJSON。单行格式不对（文件被截断、手工改坏）只跳过该行并计数，
     * 不影响其它行，也不会把异常抛给命令执行线程。
     *
     * <p>绝大多数行都是本 mod 自己写出的固定格式，走手写快速解析（不建 JsonObject）；
     * 只有格式对不上时才退回 Gson，兼顾速度和手工改过的文件。
     */
    public static Snapshot load(Path file) throws IOException {
        Snapshot s = new Snapshot();
        int bad = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), IO_BUFFER)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                char c0 = line.charAt(0);
                if (c0 == ' ' || c0 == '\t') {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                }
                if (parseFast(line, s)) continue;
                if (parseJson(line, s)) continue;
                bad++;
            }
        }
        if (bad > 0) TerrainRecorderMod.LOGGER.warn("快照 {} 中有 {} 行无法解析，已跳过", file, bad);
        s.chunkIndexKnown = true;
        return s;
    }

    /**
     * 快速解析一行 {"x":1,"y":2,"z":3,"b":"minecraft:stone"}。
     * 返回 false 表示格式不符合预期（交给 Gson 兜底）。
     */
    private static boolean parseFast(String line, Snapshot s) {
        final int n = line.length();
        if (n < 30 || !line.startsWith("{\"x\":")) return false;
        int i = 5;
        int x;
        boolean neg = false;
        if (line.charAt(i) == '-') { neg = true; i++; }
        int st = i;
        long v = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            i++;
        }
        if (i == st || v > Integer.MAX_VALUE) return false;
        x = (int) (neg ? -v : v);

        if (!line.startsWith(",\"y\":", i)) return false;
        i += 5;
        neg = false;
        if (line.charAt(i) == '-') { neg = true; i++; }
        st = i;
        v = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            i++;
        }
        if (i == st || v > Integer.MAX_VALUE) return false;
        int y = (int) (neg ? -v : v);

        if (!line.startsWith(",\"z\":", i)) return false;
        i += 5;
        neg = false;
        if (line.charAt(i) == '-') { neg = true; i++; }
        st = i;
        v = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            i++;
        }
        if (i == st || v > Integer.MAX_VALUE) return false;
        int z = (int) (neg ? -v : v);

        if (!line.startsWith(",\"b\":\"", i)) return false;
        i += 6; // ,"b":" 共 6 个字符
        int end = n - 2;
        if (end <= i || line.charAt(n - 2) != '"' || line.charAt(n - 1) != '}') return false;
        s.putBlock(x, y, z, line.substring(i, end));
        return true;
    }

    /** 通用（较慢）的 JSON 解析兜底，兼容手工改过 / 字段带空格的旧文件。 */
    private static boolean parseJson(String line, Snapshot s) {
        try {
            JsonObject o = GSON.fromJson(line, JsonObject.class);
            if (o == null || !o.has("x") || !o.has("y") || !o.has("z") || !o.has("b")) return false;
            s.putBlock(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("b").getAsString());
            return true;
        } catch (RuntimeException ex) {
            return false; // JsonSyntaxException / NPE / NumberFormatException 等
        }
    }
}
