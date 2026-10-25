package com.terrainrecorder;

import com.terrainrecorder.snapshot.Snapshot;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.awt.Desktop;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 地形记录器核心（纯客户端）。
 * 从客户端内存（ClientLevel）读取服务器下发给客户端、已加载区块的方块；
 * 只记录普通方块ID。快照按服务器 IP 分目录存放。
 *
 * 对齐：读取比对时，先看原坐标是否已有足够重叠（阈值随区域/快照规模自适应，约 8~100 块），
 * 不足时按"同 ID 方块对"投票求平移偏移，再用对齐后的快照做 recmode add/min 和 find。
 *
 * 命令：
 *  /rec            —— 把当前已加载区块的非空气方块保存成时间戳快照（按服务器 IP 分目录）。
 *  /recmode add    —— 对齐后，边走边把新看到的、地形图里没有的方块加进去。
 *  /recmode min    —— 对齐后，边走边把已消失的方块从地形图移除。
 *  /recmode stop   —— 结束并把结果保存成新的时间戳快照文件。
 *  /find           —— 自动匹配一张与当前地形贴合的快照，实时把当前地形里快照没有的方块打红框。
 *  /find <id>      —— 指定对比某张快照（id 为时间戳，带补全），找出自那张以来新增/多余的方块。
 *  /find off       —— 退出查找模式。
 *  /delrec <id>    —— 删除指定快照（id 为文件名时间戳）。
 * 切换服务器 / 换世界 会自动结束 find 和 recmode。
 */
public final class TerrainManager {

    public enum RecMode { OFF, ADD, MIN }

    /**
     * 匹配判据：以玩家为中心的周围区域（绝对坐标换算：水平 ±REGION_XZ，垂直 y+REGION_Y_LO..y+REGION_Y_HI）内，
     * 非空气方块与对齐后快照同 ID 的比例 ≥REGION_MATCH_RATIO 才算匹配。
     */
    private static final int REGION_XZ = 15;      // 水平半径（方块）
    private static final int REGION_Y_LO = -2;    // 垂直下界（相对玩家 y）
    private static final int REGION_Y_HI = 2;     // 垂直上界（相对玩家 y）
    private static final float REGION_MATCH_RATIO = 0.8f;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    /** 在线下载体积上限，防止误下的超大文件把磁盘吃满。 */
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    private static final String DEFAULT_PACK_URL =
            "https://jimmyxiao2026.github.io/terrainrecorder/downloads/jx-bjd-dmm.zip";

    private static final TerrainManager INSTANCE = new TerrainManager();
    public static TerrainManager get() { return INSTANCE; }

    private final Set<ChunkPos> loadedChunks = new HashSet<>();
    // 扫描策略：模式开始时把作用范围内的区块按"由近到远"入队，每 tick 只用很小的时间预算处理；
    // 队列排空后不再周期性全扫（实时变化由 ClientLevel 的 mixin 增量维护，区块数据由 CHUNK_LOAD 触发）。
    private final Deque<ChunkPos> scanQueue = new ArrayDeque<>();
    private final Set<ChunkPos> scanQueued = new HashSet<>();
    /** 已扫描过的区块；离开作用范围 / 卸载后移除，回到范围时会重扫。 */
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private static final long SCAN_BUDGET_NANOS = 2_500_000L; // 每 tick 最多占用 2.5 ms
    private static final int MAX_SCAN_DIST = 10;     // 超过此距离不扫不渲
    private static final int RECONCILE_BUDGET = 400; // reconcile 每轮最多检查的位置数
    /**
     * 单次 /find 的差异上限：超过就认为"快照和当前位置对不上 / 区域差异过大"，
     * 直接停止高亮并提示，避免几万个方框把帧率拖死。
     */
    private static final int MAX_HIGHLIGHT = 5_000;
    /** 对齐投票用的稀疏采样：采样半径（区块）与各轴步长。 */
    private static final int SAMPLE_CHUNK_RADIUS = 4;
    private static final int SAMPLE_STEP = 4;
    /** 可复用的查询对象，避免每个方块都分配 BlockPos（只用于查表，绝不放进集合）。 */
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos lookupProbe = new BlockPos.MutableBlockPos();
    /** find 时快照覆盖到的区块（ChunkPos.asLong）：快照没覆盖的地方不参与比对，避免整片区域被标红。 */
    private LongSet findCoveredChunks;
    /** 分帧清理用随机起点，避免固定从头部开始导致后面的条目永远清不到（也不改动集合顺序，防止红框闪烁）。 */
    private static final Random SWEEP_RANDOM = new Random();
    private long scanStartNanos;
    private int scanStartQueued;
    private ClientLevel currentLevel;

    private RecMode recMode = RecMode.OFF;
    // 地形图工作副本：按区块组织，便于实时按区块扩充/修正
    private final Map<ChunkPos, Map<BlockPos, String>> working = new HashMap<>();
    // 三个高亮集合都用 LinkedHashSet：分帧清理时把"仍然有效"的条目移到队尾，保证轮转（见 sweep 方法）。
    private final Set<BlockPos> diffSet = new LinkedHashSet<>();
    // recmode min 期间"被移除的方块"（在地形图里但当前世界已消失/变化）→ 蓝色高亮
    private final Set<BlockPos> minRemoveSet = new LinkedHashSet<>();
    // recmode add 期间"新加入地形图的多余方块"（当前世界有、地形图没有）→ 蓝色高亮
    private final Set<BlockPos> addAddedSet = new LinkedHashSet<>();
    // recmode 基于的快照文件：stop 时覆盖写入它（为空则新建时间戳快照）
    private Path recBaseFile;
    // find 比对用的快照及其对齐偏移
    private Snapshot findCompare;
    private BlockPos findOffset;
    private boolean findActive = false;

    // /rec import 待确认覆盖的导入内容（相对 terrain_snapshots 路径 -> 字节）；冲突项用聊天栏可点击确认（TUI，不用 GUI）
    private Map<String, byte[]> pendingImport;
    private final List<String> pendingConflicts = new ArrayList<>(); // 剩余待确认的已存在文件
    private int pendingImported;
    private int pendingSkipped;
    private Path pendingZipToDelete; // /rec download import 下载的 zip，TUI 确认完成后删除

    /** 快照读取优先级排序：中文名 → 英文自定义名 → 默认 snapshot_*。 */
    private static final Pattern CJK = Pattern.compile("[\\u4E00-\\u9FFF]");
    private static final Comparator<Path> SNAPSHOT_PRIORITY =
            Comparator.<Path>comparingInt(TerrainManager::priorityRank)
                    .thenComparing(p -> p.getFileName().toString());
    private static int priorityRank(Path p) {
        String n = p.getFileName().toString();
        if (!n.startsWith("snapshot_")) return CJK.matcher(n).find() ? 0 : 1; // 中文 0 / 英文 1
        return 2; // 默认名
    }

    private long tickCounter = 0;
    private int awayStreak = 0; // 连续无匹配检测次数（离开区域自动结束的滞回计数）
    private static final int AWAY_STREAK_NEEDED = 3; // 连续 3 次检测（约 6 秒）都无匹配才自动结束 find
    private boolean scanBarVisible = false; // 是否正在显示扫描进度

    private TerrainManager() {}

    // ---------------- 生命周期 ----------------

    public void reset() {
        recMode = RecMode.OFF;
        working.clear();
        diffSet.clear();
        minRemoveSet.clear();
        addAddedSet.clear();
        recBaseFile = null;
        findCompare = null;
        findOffset = null;
        findActive = false;
        findCoveredChunks = null;
        loadedChunks.clear();
        clearScanState();
        awayStreak = 0;
        currentLevel = null;
        pendingImport = null;
        pendingConflicts.clear();
        pendingImported = 0;
        pendingSkipped = 0;
        pendingZipToDelete = null;
        bestScore = -1;
        tickCounter = 0;
    }

    /** 清空扫描队列与"已扫描"标记（停止模式 / 换世界时调用）。 */
    private void clearScanState() {
        scanQueue.clear();
        scanQueued.clear();
        scannedChunks.clear();
        scanBarVisible = false;
        scanStartNanos = 0;
        scanStartQueued = 0;
    }

    /** 客户端区块加载：加入集合；有活动模式时排进扫描队列（下一 tick 按预算处理）。 */
    public void onChunkLoad(ClientLevel level, ChunkPos pos) {
        if (currentLevel != level) currentLevel = level;
        loadedChunks.add(pos);
        scannedChunks.remove(pos);
        if (findActive) {
            if (findCovers(pos)) enqueueScan(pos);
        } else if (recMode != RecMode.OFF) {
            enqueueScan(pos);
        }
    }

    public void onChunkUnload(ClientLevel level, ChunkPos pos) {
        loadedChunks.remove(pos);
        scanQueued.remove(pos);
        scannedChunks.remove(pos);
    }

    /**
     * 客户端每 tick：检测换世界；活动模式下用很小的时预算处理扫描队列。
     * 队列排空后不再有任何周期性全量扫描（实时变化由 mixin 增量维护）。
     */
    public void onClientTick(ClientLevel level) {
        if (currentLevel != level) {
            reset();
            currentLevel = level;
            prime(level);
            return;
        }
        if (recMode == RecMode.OFF && !findActive) return;
        tickCounter++;
        if (tickCounter % 20 == 0) enqueueMissingInRange(level); // 走近后把先前没扫过的区块补进队列
        drainScanQueue(level);
        // 兜底清理（限预算分帧）：别人破坏变空气的差异红框、以及超出扫描范围的蓝框
        // 先做"离开快照区域"检测（带滞回）：find 活动时，只有连续 AWAY_STREAK_NEEDED 次检测
        // 玩家周围都几乎无重叠（如被踢回大厅/传送到别处）才自动结束 find，避免走动/飞行时瞬时无匹配误杀。
        if (findActive && findCompare != null && tickCounter % 40 == 0) {
            if (!nearSnapshotRegion(level)) {
                if (++awayStreak >= AWAY_STREAK_NEEDED) {
                    awayStreak = 0;
                    stopFindSilent();
                    chat(gray(Lang.tr("msg.find_auto_stop")));
                }
            } else {
                awayStreak = 0; // 只要有匹配就重置，走动不会触发
            }
        }
        reconcileDiffSetRemovals(level);
        if (recMode == RecMode.MIN) reconcileMinRemoveSet(level);
        if (recMode == RecMode.ADD) reconcileAddAddedSet(level);
    }

    /**
     * 把当前作用范围内的已加载区块按"由近到远"入队，并清空旧的扫描标记。
     * find 模式只入队快照实际覆盖到的区块（没覆盖的地方不存在"差异"这一说）；
     * 返回入队数量，供调用方判断"当前位置附近有没有快照数据"。
     */
    private int enqueueScanArea() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        ChunkPos pc = new ChunkPos(mc.player.blockPosition());
        scanQueue.clear();
        scanQueued.clear();
        scannedChunks.clear();
        scanBarVisible = false;
        int queued = 0;
        for (int r = 0; r <= MAX_SCAN_DIST; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    ChunkPos cp = new ChunkPos(pc.x + dx, pc.z + dz);
                    if (!loadedChunks.contains(cp)) continue;
                    if (findActive && !findCovers(cp)) continue;
                    enqueueScan(cp);
                    queued++;
                }
            }
        }
        if (queued > 0) {
            scanStartNanos = System.nanoTime();
            scanStartQueued = queued;
        }
        return queued;
    }

    private void enqueueScan(ChunkPos cp) {
        if (scanQueued.add(cp)) scanQueue.addLast(cp);
    }

    /** 该区块是否有 find 快照数据（快照对齐后的覆盖范围）。 */
    private boolean findCovers(ChunkPos cp) {
        return findCoveredChunks != null && findCoveredChunks.contains(ChunkPos.asLong(cp.x, cp.z));
    }

    /** 每 tick 最多花 SCAN_BUDGET_NANOS 处理扫描队列；队列空时零开销。 */
    private void drainScanQueue(ClientLevel level) {
        if (scanQueue.isEmpty()) return;
        long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
        while (!scanQueue.isEmpty()) {
            ChunkPos cp = scanQueue.pollFirst();
            scanQueued.remove(cp);
            if (!loadedChunks.contains(cp)) continue;
            if (!isChunkWithinScan(cp)) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(cp.x, cp.z, ChunkStatus.FULL, false);
            if (chunk != null) {
                scanChunk(level, chunk);
                scannedChunks.add(cp);
            }
            if (System.nanoTime() >= deadline) break;
        }
        if (scanQueue.isEmpty() && scanStartNanos != 0) {
            TerrainRecorderMod.LOGGER.info("地形扫描完成：{} 个区块，用时 {} ms",
                    scanStartQueued, (System.nanoTime() - scanStartNanos) / 1_000_000L);
            scanStartNanos = 0;
            scanStartQueued = 0;
        }
        updateScanProgress();
    }

    /**
     * 把"已经加载、但还没扫过"的区块补进队列（例如先在 12 区块外加载、玩家走近后才进入作用范围）。
     * 同时把已经离开作用范围 / 卸载的区块从"已扫描"集合里移除，回到范围时会重扫。
     */
    private void enqueueMissingInRange(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ChunkPos pc = new ChunkPos(mc.player.blockPosition());
        for (Iterator<ChunkPos> it = scannedChunks.iterator(); it.hasNext(); ) {
            ChunkPos cp = it.next();
            int d = Math.max(Math.abs(cp.x - pc.x), Math.abs(cp.z - pc.z));
            if (d > MAX_SCAN_DIST || !loadedChunks.contains(cp)) it.remove();
        }
        for (ChunkPos cp : loadedChunks) {
            if (scannedChunks.contains(cp) || scanQueued.contains(cp)) continue;
            if (Math.max(Math.abs(cp.x - pc.x), Math.abs(cp.z - pc.z)) > MAX_SCAN_DIST) continue;
            if (findActive && !findCovers(cp)) continue;
            enqueueScan(cp);
        }
    }

    /**
     * 在动作栏显示初始批量扫描的进度，扫完自动清掉。
     * 只对"一批"扫描显示（≥8 个区块），避免走路时单个区块加载让动作栏不停闪。
     */
    private void updateScanProgress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (scanQueue.size() >= 8) {
            scanBarVisible = true;
            mc.player.displayClientMessage(Component.literal(Lang.tr("msg.scanning", scanQueue.size())), true);
        } else if (scanBarVisible) {
            scanBarVisible = false;
            mc.player.displayClientMessage(Component.empty(), true);
        }
    }

    /** 玩家所在区块到 cp 的切比雪夫距离（区块单位）。 */
    private int chunkDist(ChunkPos cp) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        ChunkPos pc = new ChunkPos(mc.player.blockPosition());
        return Math.max(Math.abs(cp.x - pc.x), Math.abs(cp.z - pc.z));
    }

    /** 尽量补全已加载区块集合（进入世界 / 命令执行前用，覆盖刚进世界时的事件空档）。 */
    private void prime(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ChunkPos center = new ChunkPos(mc.player.blockPosition());
        for (int cx = center.x - 40; cx <= center.x + 40; cx++) {
            for (int cz = center.z - 40; cz <= center.z + 40; cz++) {
                try {
                    LevelChunk c = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (c != null) loadedChunks.add(c.getPos());
                } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 客户端单个方块变化（ClientLevel 注入）：实时更新。
     * 注入点覆盖服务器权威更新（setServerVerifiedBlockState）与本地 setBlock。
     */
    public void onClientBlockChange(ClientLevel level, BlockPos pos, BlockState newState) {
        // 没有任何模式在跑时直接返回，避免在服务器大量发方块更新时做无用功
        if (recMode == RecMode.OFF && !findActive) return;
        // 与扫描保持一致：超出 10 区块（≈160 格）的方块既不高亮也不记录
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            BlockPos pp = mc.player.blockPosition();
            int dx = Math.abs(pos.getX() - pp.getX());
            int dz = Math.abs(pos.getZ() - pp.getZ());
            if (Math.max(dx, dz) > MAX_SCAN_DIST * 16) return;
        }
        ChunkPos cp = new ChunkPos(pos);
        if (recMode == RecMode.ADD) {
            Map<BlockPos, String> wc = working.get(cp);
            if (newState.isAir()) {
                // 本次扩充时新加进去的方块又被挖掉 → 一并撤销，避免 stop 时把已不存在的方块写进快照
                if (addAddedSet.remove(pos) && wc != null) wc.remove(pos);
            } else {
                String id = Snapshot.blockId(newState);
                String old = wc == null ? null : wc.get(pos);
                if (old == null) {
                    working.computeIfAbsent(cp, k -> new HashMap<>()).put(pos, id);
                    if (isWithinRenderDistance(pos)) addAddedSet.add(pos); // 蓝色：新加入地形图的多余方块
                } else if (!old.equals(id) && addAddedSet.contains(pos)) {
                    wc.put(pos, id); // 本次新加入的方块变了类型 → 跟随更新
                }
            }
        } else if (recMode == RecMode.MIN) {
            Map<BlockPos, String> wc = working.get(cp);
            if (wc != null && wc.containsKey(pos)) {
                String newId = newState.isAir() ? null : Snapshot.blockId(newState);
                if (newId == null || !newId.equals(wc.get(pos))) {
                    wc.remove(pos);
                    minRemoveSet.add(pos); // 蓝色：被移除的方块
                }
            } else if (!newState.isAir() && minRemoveSet.remove(pos)) {
                // 之前被移除的方块又放回来了 → 撤销这次"移除"记录，重新算进地形图
                working.computeIfAbsent(cp, k -> new HashMap<>()).put(pos, Snapshot.blockId(newState));
            }
        }
        if (findActive && findCompare != null && findCovers(cp)) {
            if (newState.isAir()) diffSet.remove(pos);
            else if (findContains(pos, Snapshot.blockId(newState))) diffSet.remove(pos);
            else diffAdd(pos);
        }
    }

    // ---------------- 命令 ----------------

    public int saveSnapshot(FabricClientCommandSource src) {
        ClientLevel level = src.getWorld();
        if (level == null) { src.sendError(tex("msg.not_in_world")); return 0; }
        prime(level);
        if (loadedChunks.isEmpty()) { src.sendError(tex("msg.no_chunks")); return 0; }
        Snapshot snap = Snapshot.capture(level, loadedChunks);
        if (snap.size() == 0) { src.sendError(tex("msg.empty_terrain")); return 0; }
        Path dir = snapshotsDir();
        Path file = uniqueFile(dir, "snapshot_" + stamp(), ".snap");
        String name = file.getFileName().toString();
        try {
            snap.save(file);
        } catch (IOException e) {
            TerrainRecorderMod.LOGGER.error("保存快照失败", e);
            src.sendError(tex("msg.save_failed", errText(e)));
            return 0;
        }
        src.sendFeedback(green(Lang.tr("msg.snapshot_saved"))
                .append(fileLink(name))
                .append(gray(Lang.tr("msg.snapshot_stat", snap.size(), loadedChunks.size()))));
        TerrainRecorderMod.LOGGER.info("已保存快照 {} 到目录 {} ({} 方块 / {} 区块)", name, dir, snap.size(), loadedChunks.size());
        return 1;
    }

    public int startRecMode(FabricClientCommandSource src, RecMode mode) {
        ClientLevel level = src.getWorld();
        if (level == null) { src.sendError(tex("msg.not_in_world")); return 0; }
        if (recMode != RecMode.OFF) { src.sendError(tex("msg.already_mode")); return 0; }
        if (findActive) stopFind(src); // 模式互斥：开启 recmode 会先结束 /find
        prime(level);
        if (loadedChunks.isEmpty()) { src.sendError(tex("msg.no_chunks")); return 0; }

        Aligned best = bestSnapshot(level);
        if (best != null) {
            loadAlignedWorking(best.snap, best.offset);
            recBaseFile = best.file; // stop 时覆盖写入该基准快照
            TerrainRecorderMod.LOGGER.info("已对齐快照，偏移 ({},{},{})，区域匹配 {} 块", best.offset.getX(), best.offset.getY(), best.offset.getZ(), bestScore);
        } else {
            // 没有可对齐的快照：才需要把整片已加载区域抓成地形图（这一步比较重）
            Snapshot current = Snapshot.capture(level, loadedChunks);
            loadWorking(current);
            recBaseFile = null; // 无基准文件，stop 时新建时间戳快照
            src.sendFeedback(gold(Lang.tr("msg.no_aligned_base")));
        }
        if (workingSize() == 0) {
            working.clear();
            src.sendError(tex("msg.empty_terrain"));
            return 0;
        }
        recMode = mode;
        minRemoveSet.clear();
        addAddedSet.clear();
        enqueueScanArea(); // 由近到远分帧补扫（每 tick 限时，不会卡帧）
        Component msg = green(Lang.tr(mode == RecMode.ADD ? "msg.add_started" : "msg.min_started"))
                .append(tex("msg.mode_started_suffix", workingSize()).withStyle(ChatFormatting.GREEN))
                .append(tex("msg.click_stop")
                        .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/recmode stop"))));
        src.sendFeedback(msg);
        TerrainRecorderMod.LOGGER.info("开始 recmode={}，地形图 {} 方块 / {} 区块", mode, workingSize(), loadedChunks.size());
        return 1;
    }

    public int stopRecMode(FabricClientCommandSource src) {
        if (recMode == RecMode.OFF || working.isEmpty()) {
            src.sendError(tex("msg.no_mode"));
            return 0;
        }
        String name;
        Path file;
        if (recBaseFile != null) {
            name = recBaseFile.getFileName().toString();
            file = recBaseFile; // 覆盖写入 recmode 基于的基准快照
        } else {
            file = uniqueFile(snapshotsDir(), "snapshot_" + stamp(), ".snap");
            name = file.getFileName().toString();
        }
        try {
            Snapshot out = new Snapshot();
            for (Map.Entry<ChunkPos, Map<BlockPos, String>> e : working.entrySet())
                for (Map.Entry<BlockPos, String> b : e.getValue().entrySet())
                    out.blocks().put(b.getKey(), b.getValue());
            out.save(file);
        } catch (IOException ex) {
            TerrainRecorderMod.LOGGER.error("保存结果快照失败", ex);
            src.sendError(tex("msg.save_failed", errText(ex)));
            return 0;
        }
        recMode = RecMode.OFF;
        working.clear();
        minRemoveSet.clear();
        addAddedSet.clear();
        recBaseFile = null;
        clearScanState();
        src.sendFeedback(green(Lang.tr("msg.mode_saved")).append(fileLink(name)));
        return 1;
    }

    /** /find：自动选一张与当前地形最贴合的快照来比对差异。 */
    public int startFind(FabricClientCommandSource src) {
        return startFind(src, null);
    }

    /**
     * /find [id]：与指定快照比对（id 为时间戳，可 /find off 结束）。
     * 指定快照可用来对比"更早的地形图"，找出自那张以来新增/多余的方块。
     */
    public int startFind(FabricClientCommandSource src, String snapshotId) {
        final long prepStart = System.nanoTime();
        ClientLevel level = src.getWorld();
        if (level == null) { src.sendError(tex("msg.not_in_world")); return 0; }
        if (findActive) {
            // 已在 /find 内：视为全量刷新 —— 等价 /find off + /find，重新匹配快照并检测新的快照
            src.sendFeedback(gold(Lang.tr("msg.find_refresh")));
        }
        if (recMode != RecMode.OFF) {
            // 模式互斥：只要运行 /find 就先结束 recmode（保存结果），再进入 find
            src.sendFeedback(gold(Lang.tr("msg.find_stop_recmode")));
            stopRecMode(src);
        }
        prime(level);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { src.sendError(tex("msg.not_in_world")); return 0; }

        Snapshot snap;
        BlockPos off;
        String desc;
        if (snapshotId == null || snapshotId.isEmpty()) {
            Aligned best = bestSnapshot(level);
            if (best == null || best.snap.size() == 0) {
                src.sendError(tex("msg.find_no_snapshot"));
                return 0;
            }
            snap = best.snap;
            off = best.offset;
            desc = null;
        } else {
            // 指定快照：支持 id（时间戳）或自定义文件名（中文/英文，可带或不带 .snap）
            String id = snapshotId.trim();
            if (id.endsWith(".snap")) id = id.substring(0, id.length() - 5);
            if (id.contains("/") || id.contains("\\") || id.contains("..") || id.isEmpty()) {
                src.sendError(tex("msg.bad_id", snapshotId));
                return 0;
            }
            Path file = resolveSnapshotFile(id);
            if (file == null) {
                src.sendError(tex("msg.snapshot_not_found", id));
                listSnapshotsFeedback(src);
                return 0;
            }
            try {
                snap = Snapshot.load(file);
            } catch (IOException e) {
                TerrainRecorderMod.LOGGER.error("读取指定快照失败", e);
                src.sendError(tex("msg.load_failed", errText(e)));
                return 0;
            }
            if (snap.size() == 0) {
                src.sendError(tex("msg.snapshot_empty"));
                return 0;
            }
            // 指定快照：用玩家附近的稀疏采样做对齐投票（不再为整片区域建大 Map）
            Map<BlockPos, String> sample = sampleAround(level, mc.player.blockPosition());
            off = findAlignment(snap, sample, alignSupportFor(snap.size(), sample.size()));
            if (off == null) {
                off = BlockPos.ZERO;
                src.sendFeedback(gold(Lang.tr("msg.find_unaligned", id)));
            }
            desc = id;
        }

        findCompare = snap;
        findOffset = off;
        findActive = true;
        diffSet.clear();
        awayStreak = 0;
        // 只比对快照真正覆盖到的区块：快照没覆盖的地方不存在"差异"，否则整片区域都会被标红
        buildFindCoverage(snap, off);
        int queued = enqueueScanArea(); // 由近到远分帧扫描，差异逐步出现
        if (queued == 0) {
            stopFindSilent();
            src.sendError(tex("msg.find_no_coverage"));
            return 0;
        }
        src.sendFeedback(green(Lang.tr("msg.find_started", desc == null ? Lang.tr("msg.automatic") : desc)));
        TerrainRecorderMod.LOGGER.info("开始 find，比对快照 {} 方块{}，偏移 {}，覆盖范围内待扫描区块 {}，准备耗时 {} ms",
                snap.size(), desc == null ? "" : "[" + desc + "]", off, queued,
                (System.nanoTime() - prepStart) / 1_000_000L);
        return 1;
    }

    /** 计算快照（已应用对齐偏移）覆盖到的区块集合。用加载时顺手建好的区块索引，不再遍历全部方块。 */
    private void buildFindCoverage(Snapshot snap, BlockPos off) {
        int ox = off.getX(), oz = off.getZ();
        LongSet snapshotChunks = snap.chunks();
        LongOpenHashSet set = new LongOpenHashSet(Math.max(16, snapshotChunks.size() * 2));
        LongIterator it = snapshotChunks.iterator();
        while (it.hasNext()) {
            long l = it.nextLong();
            int cx = ChunkPos.getX(l), cz = ChunkPos.getZ(l);
            // 快照区块按方块偏移平移后，最多落到 2×2 个世界区块里
            int wx0 = (cx << 4) + ox, wz0 = (cz << 4) + oz;
            int wx1 = wx0 + 15, wz1 = wz0 + 15;
            for (int wx = wx0 >> 4; wx <= (wx1 >> 4); wx++)
                for (int wz = wz0 >> 4; wz <= (wz1 >> 4); wz++)
                    set.add(ChunkPos.asLong(wx, wz));
        }
        findCoveredChunks = set;
    }

    public int stopFind(FabricClientCommandSource src) {
        if (!findActive) { src.sendError(tex("msg.not_finding")); return 0; }
        stopFindSilent();
        src.sendFeedback(green(Lang.tr("msg.find_stopped")));
        return 1;
    }

    /** 静默结束 find（不发送提示）：供 /delrec、快捷键关闭等调用。 */
    public void stopFindSilent() {
        findActive = false;
        findCompare = null;
        findOffset = null;
        findCoveredChunks = null;
        diffSet.clear();
        awayStreak = 0;
        clearScanState();
    }

    public int deleteSnapshot(FabricClientCommandSource src, String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            src.sendError(tex("msg.delrec_usage"));
            listSnapshotsFeedback(src);
            return 0;
        }
        // 兼容直接贴完整文件名；支持 id（时间戳）或自定义文件名（中文/英文，可带或不带 .snap）
        String id = rawId.trim();
        if (id.endsWith(".snap")) id = id.substring(0, id.length() - 5);
        if (id.contains("/") || id.contains("\\") || id.contains("..") || id.isEmpty()) {
            src.sendError(tex("msg.bad_id", rawId));
            return 0;
        }
        Path file = resolveSnapshotFile(id);
        if (file == null) {
            src.sendError(tex("msg.delete_not_found", id));
            listSnapshotsFeedback(src);
            return 0;
        }
        String filename = file.getFileName().toString();
        try {
            Files.delete(file);
        } catch (IOException e) {
            src.sendError(tex("msg.delete_failed", errText(e)));
            return 0;
        }
        stopFindSilent(); // 删除成功后再结束 find（输错的 id 不应影响当前查找模式）
        src.sendFeedback(green(Lang.tr("msg.deleted", filename)));
        return 1;
    }

    /** /delrec all：删除当前服务器目录下全部快照。 */
    public int deleteAllSnapshots(FabricClientCommandSource src) {
        Path dir = snapshotsDir();
        if (!Files.isDirectory(dir)) { src.sendError(tex("msg.no_snapshot_dir")); return 0; }
        int n = 0;
        try (var stream = Files.list(dir)) {
            for (Path f : stream.toList()) {
                if (f.getFileName().toString().endsWith(".snap")) { Files.delete(f); n++; }
            }
        } catch (IOException e) {
            TerrainRecorderMod.LOGGER.error("删除全部快照失败", e);
            src.sendError(tex("msg.delete_failed", errText(e)));
            return 0;
        }
        stopFindSilent();
        src.sendFeedback(green(Lang.tr("msg.deleted_all", n)));
        return n > 0 ? 1 : 0;
    }

    /**
     * /delrec all else <keep>：删除除 keep 外的全部快照。
     * keep 可为快照 id，或关键字 latest（保留最新的）。
     */
    public int deleteAllExcept(FabricClientCommandSource src, String keepRaw) {
        String keep = keepRaw == null ? "" : keepRaw.trim();
        if (keep.endsWith(".snap")) keep = keep.substring(0, keep.length() - 5);
        List<Path> all = listSnapshotFiles();
        if (all.isEmpty()) { src.sendError(tex("msg.snapshot_dir_empty")); return 0; }
        Path keepPath;
        if (keep.equalsIgnoreCase("latest")) {
            keepPath = all.stream()
                    .max(Comparator.comparingLong(f -> lastModified(f)))
                    .orElse(null);
        } else {
            keepPath = resolveSnapshotFile(keep);
            if (keepPath == null) {
                src.sendError(tex("msg.keep_not_found", keep));
                listSnapshotsFeedback(src);
                return 0;
            }
        }
        String keepFile = keepPath != null ? keepPath.getFileName().toString() : null;
        Path dir = snapshotsDir();
        int n = 0;
        try (var stream = Files.list(dir)) {
            for (Path f : stream.toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".snap") && !name.equals(keepFile)) { Files.delete(f); n++; }
            }
        } catch (IOException e) {
            TerrainRecorderMod.LOGGER.error("删除其余快照失败", e);
            src.sendError(tex("msg.delete_failed", errText(e)));
            return 0;
        }
        stopFindSilent();
        src.sendFeedback(green(Lang.tr("msg.deleted_except", n, String.valueOf(keepFile))));
        return n > 0 ? 1 : 0;
    }

    private long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
    }

    private void listSnapshotsFeedback(FabricClientCommandSource src) {
        Path dir = snapshotsDir();
        if (!Files.isDirectory(dir)) { src.sendError(tex("msg.no_snapshot_dir")); return; }
        List<Path> files = listSnapshotFiles();
        if (files.isEmpty()) { src.sendError(tex("msg.snapshot_dir_empty")); return; }
        MutableComponent head = gold(Lang.tr("msg.snapshot_list_head", dir.toString()));
        for (Path f : files) head.append(gray(Lang.tr("msg.list_item", f.getFileName().toString())));
        src.sendFeedback(head);
    }

    /** 当前服务器快照目录里所有快照的 id / 文件名（不带 .snap），按读取优先级排列，供补全与校验。 */
    public List<String> listSnapshotIds() {
        return listSnapshotFiles().stream().map(TerrainManager::toId).toList();
    }

    /** 当前服务器快照目录里所有 .snap 文件，按读取优先级排序：中文名 → 英文自定义名 → 默认 snapshot_*。 */
    private List<Path> listSnapshotFiles() {
        Path dir = snapshotsDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".snap"))
                    .sorted(SNAPSHOT_PRIORITY)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 快照文件名 → id：去 .snap，去默认 snapshot_ 前缀（自定义名保留原名）。 */
    private static String toId(Path f) {
        String n = f.getFileName().toString();
        if (n.endsWith(".snap")) n = n.substring(0, n.length() - 5);
        if (n.startsWith("snapshot_")) n = n.substring("snapshot_".length());
        return n;
    }

    /** 把用户输入的 id/文件名（可带或不带 .snap）解析为快照文件；支持默认名与自定义中文/英文名，找不到返回 null。 */
    private Path resolveSnapshotFile(String token) {
        String t = token.trim();
        if (t.endsWith(".snap")) t = t.substring(0, t.length() - 5);
        if (t.isEmpty() || t.contains("/") || t.contains("\\") || t.contains("..")) return null;
        Path dir = snapshotsDir();
        Path def = t.startsWith("snapshot_") ? dir.resolve(t + ".snap") : dir.resolve("snapshot_" + t + ".snap");
        if (Files.exists(def)) return def;
        Path custom = dir.resolve(t + ".snap");
        if (Files.exists(custom)) return custom;
        return null;
    }

    /** 导出 zip 的默认目录：<terrain_snapshots>/zips/。安卓/iOS 也仅从这里导入。 */
    private Path zipsDir() {
        Path snap = snapshotsDir(); // terrain_snapshots/<serverKey>
        Path parent = snap.getParent();
        if (parent == null) parent = snap;
        return parent.resolve("zips");
    }

    /** 列出 zips 目录里的 zip 文件名，供 /rcim -x 补全。 */
    public List<String> listZips() {
        Path dir = zipsDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 把 /rcim -x 输入的 zip 路径解析为 zips 目录下的文件：
     * 允许 1 级 ".." 返回（回到 terrain_snapshots），禁止 2 级及以上越界；
     * 子目录可随便写（不会自动创建，不存在自然找不到）。返回 null 表示越界/非法。
     */
    private Path resolveZipPath(String input) {
        String s = input == null ? "" : input.trim().replace('\\', '/');
        if (s.isEmpty()) return null;
        Path zips = zipsDir();
        Path snapParent = zips.getParent(); // terrain_snapshots
        int ups = 0;
        while (s.startsWith("../")) { ups++; s = s.substring(3); }
        Path base;
        if (ups == 0) base = zips;
        else if (ups == 1) base = snapParent;
        else return null; // 2 级及以上拒绝
        Path resolved = base.resolve(s).normalize();
        // 双保险：不允许越过 terrain_snapshots
        if (snapParent != null && !resolved.startsWith(snapParent)) return null;
        return resolved;
    }

    // ---------------- 导出 / 导入 ----------------

    /**
     * /rec export [<id|文件名> ...] [-a] [-a-f] [-o <名称>]（别名 /rcex）—— 导出功能：
     * 不指定 id → 默认导出本服务器的全部快照；
     * -a → 本服务器全部快照；-a-f / -af → 所有服务器的全部快照（跳过 zips 文件夹）；
     * -o <名称> → 自定义压缩包名（统一放 terrain_snapshots/zips/ 下）；省略 → snapshot_<时间戳>.zip。
     * 压缩包内部结构：<服务器标识>/<文件名>.snap（含服务器一层，可保存多服务器快照）。
     */
    public int exportSnapshots(FabricClientCommandSource src, String spec) {
        List<String> toks = splitArgs(spec);
        boolean allServers = false; // -a-f / -af：所有服务器的全部快照（跳过 zips 文件夹）
        boolean currentAll = false; // -a：当前服务器的全部快照
        String outName = null;
        Map<String, Path> files = new LinkedHashMap<>(); // 相对 terrain_snapshots 的路径 -> 文件
        List<String> missing = new ArrayList<>(); // 明确写了但解析不到的 id（不能静默忽略）
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (t.equalsIgnoreCase("-a-f") || t.equalsIgnoreCase("-af")) allServers = true;
            else if (t.equalsIgnoreCase("-a")) currentAll = true;
            else if (t.equalsIgnoreCase("-o")) {
                if (i + 1 < toks.size()) outName = toks.get(++i);
                else { src.sendError(tex("msg.o_needs_name")); return 0; }
            } else {
                Path f = resolveSnapshotFile(t);
                if (f != null) files.putIfAbsent(relToSnapBase(f), f.toAbsolutePath());
                else missing.add(t);
            }
        }
        if (!missing.isEmpty()) {
            src.sendError(tex("msg.export_unknown", String.join(", ", missing)));
            return 0;
        }
        if (allServers) {
            Path base = terrainSnapshotsBase();
            try (var stream = Files.list(base)) {
                for (Path dir : stream.toList()) {
                    String dn = dir.getFileName().toString();
                    if (dn.equals("zips") || !Files.isDirectory(dir)) continue;
                    try (var s2 = Files.list(dir)) {
                        for (Path f : s2.toList())
                            if (f.getFileName().toString().endsWith(".snap"))
                                files.putIfAbsent(dn + "/" + f.getFileName(), f.toAbsolutePath());
                    } catch (IOException ignored) { }
                }
            } catch (IOException e) {
                TerrainRecorderMod.LOGGER.warn("导出全部服务器快照失败", e);
            }
        } else if (currentAll || files.isEmpty()) {
            // 未指定快照或 -a → 默认导出本服务器全部
            Path snap = snapshotsDir();
            if (Files.isDirectory(snap)) {
                try (var s = Files.list(snap)) {
                    for (Path f : s.toList())
                        if (f.getFileName().toString().endsWith(".snap"))
                            files.putIfAbsent(serverKey() + "/" + f.getFileName(), f.toAbsolutePath());
                } catch (IOException e) {
                    TerrainRecorderMod.LOGGER.warn("导出本服务器快照失败", e);
                }
            }
        }
        if (files.isEmpty()) {
            src.sendError(tex("msg.export_none"));
            return 0;
        }
        // 无 -o → 保存到 zips 目录，默认用时间戳名 snapshot_<时间戳>.zip（桌面与移动端一致，无需对话框）
        Path dir = zipsDir();
        try { Files.createDirectories(dir); }
        catch (IOException e) { src.sendError(tex("msg.mkdir_failed", errText(e))); return 0; }
        Path target;
        if (outName != null) {
            String name = sanitizeFileName(outName);
            if (name.isEmpty()) { src.sendError(tex("msg.bad_zip_name", outName)); return 0; }
            if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) name += ".zip";
            target = dir.resolve(name);
        } else {
            target = uniqueFile(dir, "snapshot_" + stamp(), ".zip");
        }
        try {
            zip(files, target);
        } catch (IOException e) {
            src.sendError(tex("msg.export_failed", errText(e)));
            return 0;
        }
        src.sendFeedback(green(Lang.tr("msg.exported", files.size())).append(zipLink(target)));
        return 1;
    }

    /** 把 zip 文件做成可点击组件：点击执行隐藏命令 /trzip <路径>，在文件管理器中显示该文件所在位置。 */
    private static MutableComponent zipLink(Path target) {
        String name = target.getFileName().toString();
        String abs = target.toAbsolutePath().toString();
        return Component.literal(name).withStyle(s -> s
                .withUnderlined(true)
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.RunCommand("/trzip " + abs))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(Lang.tr("msg.reveal_hover", abs)))));
    }

    /** 把快照文件路径转成相对 terrain_snapshots 的路径（<服务器标识>/<文件名>）。 */
    private String relToSnapBase(Path file) {
        Path base = terrainSnapshotsBase();
        try {
            return base.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    /** terrain_snapshots 根（所有服务器快照的父目录，zips 也在此下）。 */
    private Path terrainSnapshotsBase() {
        Path snap = snapshotsDir();
        Path p = snap.getParent();
        return p != null ? p : snap;
    }

    /**
     * /rec import（/rcim）[-x <zip路径>] [-f] —— 导入功能：
     * 把 zip 里的 .snap 快照导入到当前服务器目录（zip 里其他非 .snap 文件会被自动忽略/删除）。
     * -x <zip路径>：zip 相对 terrain_snapshots/zips/ 的路径（可省略；省略则弹文件对话框选 zip，Windows/Linux/Mac），带 Tab 补全。
     * -f：遇到已存在文件一律强制覆盖；不加 -f 时已存在文件会弹"子页面"逐个确认（覆盖/跳过/取消全部）。
     */
    public int importSnapshots(FabricClientCommandSource src, String spec) {
        List<String> toks = splitArgs(spec);
        boolean force = false, xFlag = false;
        String path = null;
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (t.equalsIgnoreCase("-f")) force = true;
            else if (t.equalsIgnoreCase("-x")) xFlag = true;
            else if (path == null) path = t; // 跟在 -x 后的 zip 路径（或裸路径）
            else path += " " + t; // 保留路径里的空格
        }
        Path zip;
        if (path == null) {
            if (!xFlag) {
                src.sendError(tex("msg.import_usage"));
                return 0;
            }
            zip = chooseZipViaDialog(src);
            if (zip == null) return 0;
        } else {
            zip = resolveZipPath(path);
            if (zip == null) {
                src.sendError(tex("msg.bad_zip_path", path));
                return 0;
            }
            if (!Files.isRegularFile(zip)) {
                src.sendError(tex("msg.zip_not_found", zip.toString()));
                return 0;
            }
        }
        return importZip(src, zip, force);
    }

    /**
     * 处理单个 zip 的导入：读 .snap 条目 → 冲突检测 → 无冲突直接写 / 有冲突走聊天栏 TUI 确认。
     * 供 /rec import 与 /rec download import 复用。
     */
    private int importZip(FabricClientCommandSource src, Path zip, boolean force) {
        Map<String, byte[]> entries = new LinkedHashMap<>(); // 相对 terrain_snapshots 的路径(<服务器>/<文件>.snap) -> 字节
        int nonSnap = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                // 解析条目路径，防 zip-slip（../、绝对路径、越界）
                String raw = e.getName().replace('\\', '/');
                Path rp;
                try {
                    rp = Path.of(raw).normalize();
                } catch (RuntimeException ex) {
                    continue; // 含非法路径字符（NUL 等）的条目直接跳过
                }
                if (rp.isAbsolute()) continue;
                String rps = rp.toString().replace('\\', '/');
                if (rps.equals("..") || rps.startsWith("../") || rps.startsWith("/")) continue;
                if (!rps.toLowerCase(Locale.ROOT).endsWith(".snap")) { nonSnap++; continue; } // 非 .snap 自动忽略
                String sub, fn;
                int sl = rps.lastIndexOf('/');
                if (sl < 0) { sub = serverKey(); fn = rps; }       // 无服务器层 → 归到当前服务器
                else { sub = rps.substring(0, sl); fn = rps.substring(sl + 1); }
                if (sub.isEmpty() || fn.isEmpty() || sub.contains("..")) continue;
                // 盘符相对路径（C:xxx）等仍然可能跳出目录，直接拒绝
                if (fn.contains(":") || fn.startsWith("/") || fn.startsWith("\\")) continue;
                entries.putIfAbsent(sub + "/" + fn, zin.readAllBytes());
            }
        } catch (IOException e) {
            src.sendError(tex("msg.zip_read_failed", errText(e)));
            return 0;
        }
        if (entries.isEmpty()) {
            src.sendError(tex("msg.zip_no_snap"));
            return 0;
        }
        if (nonSnap > 0) src.sendFeedback(gold(Lang.tr("msg.zip_ignored", nonSnap)));
        // 找出已存在的文件（冲突）
        List<String> conflicts = new ArrayList<>();
        for (String n : entries.keySet())
            if (Files.exists(terrainSnapshotsBase().resolve(n))) conflicts.add(n);
        if (force || conflicts.isEmpty()) {
            writeImports(entries, true, src);
            return 1;
        }
        // 聊天栏可点击确认（TUI）：先写入无冲突文件，再逐条给出 覆盖/跳过 按钮
        pendingImport = entries;
        pendingConflicts.clear();
        pendingConflicts.addAll(conflicts);
        pendingImported = 0;
        pendingSkipped = 0;
        for (String rel : entries.keySet()) {
            if (!pendingConflicts.contains(rel)) importOne(src, rel, true); // 无冲突直接导入
        }
        src.sendFeedback(gold(Lang.tr("msg.import_conflicts", pendingConflicts.size())));
        for (String rel : pendingConflicts) {
            src.sendFeedback(Component.literal(rel).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("  "))
                    .append(importBtn("btn.overwrite", "overwrite", rel))
                    .append(Component.literal(" "))
                    .append(importBtn("btn.skip", "skip", rel)));
        }
        src.sendFeedback(Component.literal("→ ").withStyle(ChatFormatting.GRAY)
                .append(importBtn("btn.overwrite_all", "overwriteall", null))
                .append(Component.literal(" "))
                .append(importBtn("btn.cancel_all", "cancelall", null)));
        return 1;
    }

    /** 聊天栏可点击的导入确认按钮：点击执行隐藏命令 /trimp <action> [rel]。 */
    private static Component importBtn(String labelKey, String action, String rel) {
        String cmd = "/trimp " + action + (rel != null ? " " + rel : "");
        return Component.literal("[" + Lang.tr(labelKey) + "]").withStyle(s -> s
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand(cmd))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(Lang.tr("msg.click_to_run", cmd)))));
    }

    /** /trimp 隐藏命令入口：处理导入确认按钮点击（overwrite/skip 单文件，overwriteall/cancelall 批量）。 */
    public int handleImportAction(FabricClientCommandSource src, String action, String rel) {
        if (pendingImport == null) { src.sendError(tex("msg.no_pending_import")); return 0; }
        switch (action == null ? "" : action) {
            case "overwrite" -> importOne(src, rel, true);
            case "skip" -> importOne(src, rel, false);
            case "overwriteall" -> { while (!pendingConflicts.isEmpty()) importOne(src, pendingConflicts.get(0), true); }
            case "cancelall" -> { pendingSkipped += pendingConflicts.size(); pendingConflicts.clear(); finishImport(src); }
            default -> { src.sendError(tex("msg.unknown_import_action", action)); return 0; }
        }
        return 1;
    }

    /** 导入单个文件：rel 为相对 terrain_snapshots 的路径；overwrite=false 且该文件是待确认冲突时视为跳过。 */
    private void importOne(FabricClientCommandSource src, String rel, boolean overwrite) {
        byte[] data = pendingImport == null ? null : pendingImport.get(rel);
        if (data == null) return;
        boolean wasConflict = pendingConflicts.remove(rel);
        if (wasConflict && !overwrite) { pendingSkipped++; checkFinishImport(src); return; }
        Path base = terrainSnapshotsBase();
        Path target = base.resolve(rel).normalize();
        if (!target.startsWith(base)) return; // 双保险：绝不写出 terrain_snapshots
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(target, data);
            pendingImported++;
        } catch (IOException e) { TerrainRecorderMod.LOGGER.warn("写快照失败 {}", target, e); }
        checkFinishImport(src);
    }

    private void checkFinishImport(FabricClientCommandSource src) {
        if (pendingConflicts.isEmpty()) finishImport(src);
    }

    private void finishImport(FabricClientCommandSource src) {
        if (pendingImport != null) {
            src.sendFeedback(green(Lang.tr("msg.import_done", pendingImported, pendingSkipped,
                    terrainSnapshotsBase().toString())));
        }
        pendingImport = null;
        pendingConflicts.clear();
        pendingImported = 0;
        pendingSkipped = 0;
        // TUI 确认完成后，删除 /rec download import 下载的压缩包
        Path zip = pendingZipToDelete;
        pendingZipToDelete = null;
        if (zip != null) deleteZipQuietly(src, zip);
    }

    /**
     * /rec download import <url>（缩写 /rec -d -i <url>）：把 zip 下载到 zips 目录 → 走导入（/rcim）→ 安装后删除压缩包。
     * url 补全 jx-bjd-dmm → 固定下载 https://jimmyxiao2026.github.io/terrainrecorder/downloads/jx-bjd-dmm.zip 。
     * 下载在后台线程进行，完成后回到客户端线程导入，避免网络慢时整个游戏卡死。
     */
    public int downloadImport(FabricClientCommandSource src, String url) {
        if (url == null || url.isBlank()) {
            src.sendError(tex("msg.download_usage"));
            return 0;
        }
        String u = url.trim();
        if (u.equalsIgnoreCase("jx-bjd-dmm")) u = DEFAULT_PACK_URL;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            src.sendError(tex("msg.bad_url"));
            return 0;
        }
        Path dir = zipsDir();
        try { Files.createDirectories(dir); }
        catch (IOException e) { src.sendError(tex("msg.mkdir_failed", errText(e))); return 0; }
        final Path target = dir.resolve(zipFileName(u));
        src.sendFeedback(green(Lang.tr("msg.download_started", target.getFileName().toString())));
        final String finalUrl = u;
        CompletableFuture.runAsync(() -> {
            try {
                downloadTo(finalUrl, target);
            } catch (Exception e) {
                TerrainRecorderMod.LOGGER.error("下载快照包失败 {}", finalUrl, e);
                try { Files.deleteIfExists(target); } catch (IOException ignored) { }
                Minecraft.getInstance().execute(() -> src.sendError(tex("msg.download_failed", errText(e))));
                return;
            }
            Minecraft.getInstance().execute(() -> downloadFinished(src, target));
        });
        return 1;
    }

    /** 后台下载完成：回到客户端线程再导入（zip 读写与聊天输出都必须在客户端线程）。 */
    private void downloadFinished(FabricClientCommandSource src, Path target) {
        if (Minecraft.getInstance().level == null) {
            // 已经离开世界：不知道要导入到哪个服务器目录，先留着 zip 让玩家下次手动导入
            src.sendError(tex("msg.not_in_world"));
            src.sendFeedback(gold(Lang.tr("msg.download_kept", target.getFileName().toString())));
            return;
        }
        src.sendFeedback(green(Lang.tr("msg.download_installing", target.getFileName().toString())));
        importZip(src, target, false);
        if (pendingImport == null) {
            deleteZipQuietly(src, target); // 无冲突，已导入完成 → 删除压缩包
        } else {
            pendingZipToDelete = target; // 有冲突走 TUI，确认完成后删除
        }
    }

    /** 带超时与体积上限的下载（后台线程执行，不要在此发送聊天消息）。 */
    private static void downloadTo(String url, Path target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "terrainrecorder/1.0 (fabric)");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            long declared = conn.getContentLengthLong();
            if (declared > MAX_DOWNLOAD_BYTES) throw new IOException("file too large");
            long total = 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("file too large");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /** URL → zips 目录内的安全文件名（去掉 query/fragment 与非法字符）。 */
    private static String zipFileName(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        String fn = sanitizeFileName(path.substring(path.lastIndexOf('/') + 1));
        if (fn.isEmpty()) fn = "download";
        if (!fn.toLowerCase(Locale.ROOT).endsWith(".zip")) fn += ".zip";
        return fn;
    }

    /** 压成安全的单层文件名：去掉路径分隔符与 Windows 非法字符，避免写出 zips 目录。 */
    private static String sanitizeFileName(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        while (s.startsWith(".")) s = s.substring(1);
        return s.trim();
    }

    private void deleteZipQuietly(FabricClientCommandSource src, Path zip) {
        try {
            Files.deleteIfExists(zip);
            src.sendFeedback(gray(Lang.tr("msg.zip_deleted", zip.getFileName().toString())));
        } catch (IOException e) {
            TerrainRecorderMod.LOGGER.warn("删除压缩包失败 {}", zip, e);
        }
    }

    private void writeImports(Map<String, byte[]> entries, boolean overwrite, FabricClientCommandSource src) {
        Path base = terrainSnapshotsBase();
        try { Files.createDirectories(base); }
        catch (IOException e) { src.sendError(tex("msg.mkdir_snapshot_failed", errText(e))); return; }
        int n = 0;
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            Path target = base.resolve(en.getKey()).normalize();
            if (!target.startsWith(base)) continue; // 双保险：绝不写出 terrain_snapshots
            if (!overwrite && Files.exists(target)) continue;
            try {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(target, en.getValue()); n++;
            } catch (IOException e) { TerrainRecorderMod.LOGGER.warn("写快照失败 {}", target, e); }
        }
        pendingImport = null;
        src.sendFeedback(green(Lang.tr("msg.imported", n, base.toString())));
    }

    private void zip(Map<String, Path> files, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target.toFile())))) {
            for (Map.Entry<String, Path> en : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(en.getKey())); // <服务器标识>/<文件名>.snap
                Files.copy(en.getValue(), zos);
                zos.closeEntry();
            }
        }
    }

    /** 弹出文件对话框选择 zip（PC：Windows/Linux/Mac；无头/移动端会报错回退）。 */
    private Path chooseZipViaDialog(FabricClientCommandSource src) {
        try {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle(Lang.tr("msg.dialog_zip_title"));
            fc.setFileFilter(new FileNameExtensionFilter(Lang.tr("msg.dialog_zip_filter"), "zip"));
            int r = fc.showOpenDialog(null); // 这里是"选择已有 zip"导入，必须是打开对话框
            if (r != JFileChooser.APPROVE_OPTION) { src.sendFeedback(gray(Lang.tr("msg.cancelled"))); return null; }
            String p = fc.getSelectedFile().getAbsolutePath();
            if (!p.toLowerCase(Locale.ROOT).endsWith(".zip")) p += ".zip";
            return Path.of(p);
        } catch (Exception e) {
            TerrainRecorderMod.LOGGER.warn("当前环境不支持文件对话框", e);
            src.sendError(tex("msg.no_dialog"));
            return null;
        }
    }

    private static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.trim().split("\\s+")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    // ---------------- 内部 ----------------

    private int workingSize() {
        int n = 0;
        for (Map<BlockPos, String> m : working.values()) n += m.size();
        return n;
    }

    private int bestScore = -1;

    /**
     * 找与当前地形最贴合的快照。
     * 匹配判据：以玩家为中心取 31×5×31 区域（x±15，y-2..+2，z±15），
     * 区域内非空气方块中，与对齐后快照同 ID 的比例 ≥80% 才算匹配；
     * 多张匹配时取匹配方块数最多的（同分取文件名/时间戳最大的）。
     *
     * <p>这里不再为整片已加载区域建 Snapshot：区域直接从世界查询，
     * 对齐投票只用玩家附近的稀疏采样，避免 /find、/recmode 启动时卡顿。
     */
    private Aligned bestSnapshot(ClientLevel level) {
        Path dir = snapshotsDir();
        if (!Files.isDirectory(dir)) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        BlockPos pc = mc.player.blockPosition();
        Map<BlockPos, String> region = regionAround(level, pc);
        int total = region.size();
        if (total == 0) {
            TerrainRecorderMod.LOGGER.info("玩家周围 {}×{}×{} 区域内无非空气方块，无法匹配快照",
                    REGION_XZ * 2 + 1, REGION_Y_HI - REGION_Y_LO + 1, REGION_XZ * 2 + 1);
            return null;
        }
        Map<BlockPos, String> sample = sampleAround(level, pc);
        bestScore = -1;
        Aligned best = null;
        int bestMatch = 0;
        try (var stream = Files.list(dir)) {
            // 按文件名（时间戳）降序：同比例时优先选最新的快照
            List<Path> files = stream
                    .filter(f -> f.getFileName().toString().endsWith(".snap"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString(), Comparator.reverseOrder()))
                    .toList();
            for (Path f : files) {
                Snapshot cand;
                try { cand = Snapshot.load(f); } catch (IOException e) { continue; }
                if (cand.size() == 0) continue;
                BlockPos off = findAlignment(cand, sample, alignSupportFor(cand.size(), sample.size()));
                if (off == null) continue;
                int match = 0;
                for (Map.Entry<BlockPos, String> e : region.entrySet()) {
                    BlockPos q = off.equals(BlockPos.ZERO) ? e.getKey() : e.getKey().subtract(off);
                    String cid = cand.blocks().get(q);
                    if (cid != null && cid.equals(e.getValue())) match++;
                }
                float ratio = (float) match / total;
                if (ratio >= REGION_MATCH_RATIO && match > bestMatch) {
                    bestMatch = match;
                    best = new Aligned(cand, off, f);
                    bestScore = match;
                    // 已经非常贴合：直接采用最新的这张，不必再把更旧的快照全部读一遍
                    if (ratio >= 0.95f) break;
                }
            }
        } catch (IOException e) {
            TerrainRecorderMod.LOGGER.error("读取快照失败", e);
        }
        if (best != null) {
            TerrainRecorderMod.LOGGER.info("选中最贴合快照 {} 方块，偏移 ({},{},{}), 区域匹配 {}% ({} / {} 非空气方块)",
                    best.snap.size(), best.offset.getX(), best.offset.getY(), best.offset.getZ(),
                    (int) ((float) bestMatch / total * 100), bestMatch, total);
        } else {
            TerrainRecorderMod.LOGGER.info("没有快照在玩家周围区域内达到 ≥{}% 匹配（区域非空气方块 {} 个）",
                    (int) (REGION_MATCH_RATIO * 100), total);
        }
        return best;
    }

    /** 把玩家周围 31×5×31 区域的非空气方块直接从世界读出来（用于匹配比例）。 */
    private Map<BlockPos, String> regionAround(ClientLevel level, BlockPos pc) {
        Map<BlockPos, String> region = new HashMap<>();
        int x0 = pc.getX() - REGION_XZ, x1 = pc.getX() + REGION_XZ;
        int y0 = pc.getY() + REGION_Y_LO, y1 = pc.getY() + REGION_Y_HI;
        int z0 = pc.getZ() - REGION_XZ, z1 = pc.getZ() + REGION_XZ;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir()) continue;
                    region.put(p, Snapshot.blockId(st));
                }
        return region;
    }

    /**
     * 玩家附近的稀疏采样，用于对齐投票：半径为 SAMPLE_CHUNK_RADIUS 个区块，
     * 每轴每 SAMPLE_STEP 格取 1 个方块。
     * 直接按步长读取（不是全量遍历再过滤），读取量约为 1/64，启动时几乎瞬间完成。
     */
    private Map<BlockPos, String> sampleAround(ClientLevel level, BlockPos pc) {
        Map<BlockPos, String> out = new HashMap<>();
        ChunkPos center = new ChunkPos(pc);
        for (int cx = center.x - SAMPLE_CHUNK_RADIUS; cx <= center.x + SAMPLE_CHUNK_RADIUS; cx++) {
            for (int cz = center.z - SAMPLE_CHUNK_RADIUS; cz <= center.z + SAMPLE_CHUNK_RADIUS; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                Snapshot.forEachBlockSampled(level, chunk, SAMPLE_STEP,
                        (x, y, z, id) -> out.put(new BlockPos(x, y, z), id));
            }
        }
        return out;
    }

    /**
     * 找把快照平移到与当前地形重叠的偏移：优先原坐标，重叠不足时用"同 ID 方块对"投票找平移量。
     *
     * @param minSupport 至少需要多少块重叠才认可（阈值越大越保守；避免个别巧合方块把偏移锁死）
     * @return 偏移；重叠不足时返回 null
     */
    private BlockPos findAlignment(Snapshot snap, Map<BlockPos, String> current, int minSupport) {
        int need = Math.max(1, minSupport);
        // 快路径：原坐标重叠已达阈值 → 不需要平移
        int direct = 0;
        for (Map.Entry<BlockPos, String> e : current.entrySet()) {
            String cid = snap.blocks().get(e.getKey());
            if (cid != null && cid.equals(e.getValue()) && ++direct >= need) return BlockPos.ZERO;
        }
        // 投票找非零偏移：对当前地形的每个锚点方块，统计它相对快照同ID方块应平移多少，取票数最多的偏移
        Map<String, List<BlockPos>> byId = new HashMap<>();
        for (Map.Entry<BlockPos, String> e : snap.blocks().entrySet())
            byId.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        Map<BlockPos, Integer> votes = new HashMap<>();
        int anchors = 0;
        for (Map.Entry<BlockPos, String> e : current.entrySet()) {
            if (++anchors > 2000) break;
            List<BlockPos> ms = byId.get(e.getValue());
            if (ms == null) continue;
            int step = Math.max(1, ms.size() / 400);
            for (int mi = 0; mi < ms.size(); mi += step) {
                BlockPos q = ms.get(mi);
                BlockPos off = new BlockPos(e.getKey().getX() - q.getX(), e.getKey().getY() - q.getY(), e.getKey().getZ() - q.getZ());
                votes.merge(off, 1, Integer::sum);
            }
        }
        BlockPos best = null;
        int bestV = 0;
        for (Map.Entry<BlockPos, Integer> en : votes.entrySet())
            if (en.getValue() > bestV) { bestV = en.getValue(); best = en.getKey(); }
        return bestV >= need ? best : null;
    }

    /**
     * 对齐所需的最少重叠块数：取"按快照规模"与"按采样规模"两个阈值中较小的一个。
     * 小建筑（快照块少）不会被采样阈值卡住，大快照也不会被一两个巧合方块骗到。
     */
    private static int alignSupportFor(int snapshotSize, int sampleSize) {
        int bySnapshot = Math.max(8, Math.min(100, snapshotSize / 50));
        int bySample = Math.max(8, Math.min(100, sampleSize / 20));
        return Math.min(bySnapshot, bySample);
    }

    /** 以对齐偏移加载地形图：把快照每个方块平移到对齐后的绝对坐标。 */
    private void loadAlignedWorking(Snapshot snap, BlockPos offset) {
        working.clear();
        for (Map.Entry<BlockPos, String> e : snap.blocks().entrySet()) {
            BlockPos p = offset.equals(BlockPos.ZERO) ? e.getKey() : e.getKey().offset(offset);
            working.computeIfAbsent(new ChunkPos(p), k -> new HashMap<>()).put(p, e.getValue());
        }
    }

    private void loadWorking(Snapshot snap) {
        working.clear();
        for (Map.Entry<BlockPos, String> e : snap.blocks().entrySet())
            working.computeIfAbsent(new ChunkPos(e.getKey()), k -> new HashMap<>()).put(e.getKey(), e.getValue());
    }

    /** find 比对（按坐标查询，使用可复用对象，避免每方块分配 BlockPos）。 */
    private boolean findContains(int x, int y, int z, String id) {
        if (findCompare == null) return false;
        int ox = findOffset == null ? 0 : findOffset.getX();
        int oy = findOffset == null ? 0 : findOffset.getY();
        int oz = findOffset == null ? 0 : findOffset.getZ();
        lookupProbe.set(x - ox, y - oy, z - oz);
        String cur = findCompare.blocks().get(lookupProbe);
        return cur != null && cur.equals(id);
    }

    /** find 比对（已有 BlockPos 的调用方）。 */
    private boolean findContains(BlockPos pos, String id) {
        return findContains(pos.getX(), pos.getY(), pos.getZ(), id);
    }

    /** 记录一个差异位置（坐标版：达到上限后直接停止高亮，连 BlockPos 都不再分配）。 */
    private void diffAdd(int x, int y, int z) {
        if (!findActive) return;
        if (diffSet.size() >= MAX_HIGHLIGHT) { stopFindTooMany(); return; }
        diffSet.add(new BlockPos(x, y, z));
    }

    /** 记录一个差异位置（已有 BlockPos 的调用方）。 */
    private void diffAdd(BlockPos p) {
        if (!findActive) return;
        if (diffSet.size() >= MAX_HIGHLIGHT) { stopFindTooMany(); return; }
        diffSet.add(p);
    }

    /**
     * 差异过多：说明快照和当前位置对不上（或者这片区域变化太大），继续画下去只会卡。
     * 直接结束 find 并提示，符合"差异过多就停止高亮"的预期。
     */
    private void stopFindTooMany() {
        if (!findActive) return;
        stopFindSilent();
        chat(gold(Lang.tr("msg.find_too_many_stop", MAX_HIGHLIGHT)));
    }

    /**
     * 扫描一个区块：
     * MIN 只做 reconcileChunk（不需要遍历世界）；
     * ADD 把地形图里没有的方块加进去；
     * FIND 把世界里有、快照里没有（或 ID 不同）的方块记进差异集合（只限快照覆盖到的区块）。
     */
    private void scanChunk(ClientLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        if (!isChunkWithinScan(cp)) return; // 统一在这里兜底：超范围不扫、不记录
        if (recMode == RecMode.MIN) {
            reconcileChunk(level, chunk, cp);
            return;
        }
        if (recMode == RecMode.ADD) {
            Map<BlockPos, String> wc = working.computeIfAbsent(cp, k -> new HashMap<>());
            Snapshot.forEachBlock(level, chunk, (x, y, z, id) -> {
                probe.set(x, y, z);
                if (!wc.containsKey(probe)) {
                    BlockPos p = new BlockPos(x, y, z);
                    wc.put(p, id);
                    if (isWithinRenderDistance(p)) addAddedSet.add(p); // 蓝色：新加入地形图的多余方块
                }
            });
            return;
        }
        if (findActive && findCompare != null) {
            if (!findCovers(cp)) return; // 快照没覆盖这里：不比对，避免整片区域标红
            Snapshot.forEachBlock(level, chunk, (x, y, z, id) -> {
                if (!findActive) return; // 可能已经在本次扫描中因差异过多而停止
                if (findContains(x, y, z, id)) {
                    probe.set(x, y, z);
                    diffSet.remove(probe);
                } else {
                    diffAdd(x, y, z);
                }
            });
        }
    }

    /** working 地形图里是否包含绝对坐标 pos（按区块查）。 */
    private boolean workingContains(BlockPos pos) {
        Map<BlockPos, String> wc = working.get(new ChunkPos(pos));
        return wc != null && wc.containsKey(pos);
    }

    /** MIN：移除该已加载区块内地形图中已不存在的方块（只动已加载区块，未加载的不动）。 */
    private void reconcileChunk(ClientLevel level, LevelChunk chunk, ChunkPos cp) {
        Map<BlockPos, String> wc = working.get(cp);
        if (wc == null || wc.isEmpty()) return;
        List<BlockPos> remove = null;
        for (Map.Entry<BlockPos, String> e : wc.entrySet()) {
            BlockState cur = level.getBlockState(e.getKey());
            String curId = cur.isAir() ? null : Snapshot.blockId(cur);
            if (curId == null || !curId.equals(e.getValue())) {
                if (remove == null) remove = new ArrayList<>();
                remove.add(e.getKey());
                if (isWithinRenderDistance(e.getKey())) minRemoveSet.add(e.getKey()); // 蓝色：被移除的方块
            }
        }
        if (remove != null) {
            for (BlockPos p : remove) wc.remove(p);
            TerrainRecorderMod.LOGGER.info("MIN 修正：区块 {} 移除 {} 个已消失/变化方块（working 剩余 {}）",
                    cp, remove.size(), wc.size());
        }
    }

    /**
     * 把已加载区块里已经变成空气的差异位置从 diffSet 移除（分帧轮转，每轮最多检查 RECONCILE_BUDGET 个）。
     * 原因：scanChunk 只遍历非空气方块，空气方块永远扫不到，别人破坏（服务端下发 air）后红框会残留。
     */
    private void reconcileDiffSetRemovals(ClientLevel level) {
        if (!findActive || findCompare == null || diffSet.isEmpty()) return;
        sweep(diffSet, RECONCILE_BUDGET, p -> {
            if (!loadedChunks.contains(new ChunkPos(p))) return false; // 未加载的区块不清（可能只是暂时不可见）
            try {
                return level.getBlockState(p).isAir();
            } catch (Exception ignored) {
                return false;
            }
        });
    }

    /**
     * min 期间兜底清理蓝色"被移除方块"（分帧轮转）：超出扫描范围、或区块已卸载的位置 → 移除。
     * 被移除的方块只要还在视野内就一直蓝框显示，直到 min 结束。
     */
    private void reconcileMinRemoveSet(ClientLevel level) {
        if (minRemoveSet.isEmpty()) return;
        sweep(minRemoveSet, RECONCILE_BUDGET,
                p -> !loadedChunks.contains(new ChunkPos(p)) || !isWithinRenderDistance(p));
    }

    /**
     * add 期间兜底清理蓝色"新加入方块"（分帧轮转）：超出范围、区块已卸载、或方块已变空气的位置 → 移除。
     * 变空气时同时把它从地形图里撤掉，避免 stop 时把已不存在的方块写进快照。
     */
    private void reconcileAddAddedSet(ClientLevel level) {
        if (addAddedSet.isEmpty()) return;
        sweep(addAddedSet, RECONCILE_BUDGET, p -> {
            if (!loadedChunks.contains(new ChunkPos(p)) || !isWithinRenderDistance(p)) return true;
            try {
                if (level.getBlockState(p).isAir()) {
                    Map<BlockPos, String> wc = working.get(new ChunkPos(p));
                    if (wc != null) wc.remove(p);
                    return true;
                }
            } catch (Exception ignored) { }
            return false;
        });
    }

    /**
     * 分帧清理：每轮从集合里随机挑一个起点，检查最多 budget 个条目（到尾部后从头继续）。
     *
     * <p>为什么不从头部遍历：前面的有效条目会一直吃掉预算，后面的过期条目永远清不掉。
     * 为什么不用"把有效条目搬到队尾"的轮转：那会改变集合顺序，渲染时超过上限的红框会忽隐忽现。
     * 随机起点既不会饿死后面的条目，也不改动集合顺序。
     */
    private static <T> void sweep(Set<T> set, int budget, Predicate<T> stale) {
        int size = set.size();
        if (size == 0 || budget <= 0) return;
        int skip = SWEEP_RANDOM.nextInt(size);
        Iterator<T> it = set.iterator();
        for (int i = 0; i < skip && it.hasNext(); i++) it.next();
        int checked = 0;
        boolean wrapped = skip == 0;
        while (checked < budget) {
            if (!it.hasNext()) {
                if (wrapped) return;
                wrapped = true;
                it = set.iterator();
                if (!it.hasNext()) return;
            }
            T v = it.next();
            checked++;
            if (stale.test(v)) it.remove();
        }
    }

    /** 判断区块是否在扫描范围内 —— 分层调度只扫玩家周围 ≤MAX_SCAN_DIST 的区块。 */
    private boolean isChunkWithinScan(ChunkPos cp) {
        return chunkDist(cp) <= MAX_SCAN_DIST;
    }

    /**
     * 判断玩家是否还在"快照覆盖区域"内（离开检测）。
     * 直接用已经算好的覆盖集合判断玩家所在区块及相邻一圈，不再对世界做全高度采样：
     * 既便宜，也不会因为采样点恰好落在空气/稀疏结构上而误判"已离开"导致高亮突然消失。
     */
    private boolean nearSnapshotRegion(ClientLevel level) {
        if (findCoveredChunks == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        ChunkPos pc = new ChunkPos(mc.player.blockPosition());
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (findCovers(new ChunkPos(pc.x + dx, pc.z + dz))) return true;
        return false;
    }

    /** 判断绝对坐标 pos 是否在扫描/渲染范围内（≤MAX_SCAN_DIST 区块 = 10 区块 ≈ 160 方块）—— 超过 10 区块不渲染、不加载。 */
    private boolean isWithinRenderDistance(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;
        int maxBlocks = MAX_SCAN_DIST * 16;
        return Math.abs(pos.getX() - mc.player.blockPosition().getX()) <= maxBlocks
                && Math.abs(pos.getZ() - mc.player.blockPosition().getZ()) <= maxBlocks;
    }

    /** 快照目录：<base>/terrain_snapshots/<服务器标识>/。单人在世界文件夹下，多人退回游戏根目录。 */
    private Path snapshotsDir() {
        Minecraft mc = Minecraft.getInstance();
        Path base;
        var srv = mc.getSingleplayerServer();
        if (srv != null) base = srv.getWorldPath(LevelResource.ROOT);
        else base = mc.gameDirectory.toPath();
        return base.resolve("terrain_snapshots").resolve(serverKey());
    }

    /** 服务器标识：多人用服务器 IP；单人用 singleplayer。 */
    private String serverKey() {
        Minecraft mc = Minecraft.getInstance();
        ServerData data = mc.getCurrentServer();
        if (data != null && data.ip != null && !data.ip.isEmpty()) return sanitize(data.ip);
        return "singleplayer";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 一张已与当前地形对齐的快照（连同其来源文件，供 recmode 覆盖写入）。 */
    private static final class Aligned {
        final Snapshot snap;
        final BlockPos offset;
        final Path file;
        Aligned(Snapshot s, BlockPos o, Path f) { snap = s; offset = o; file = f; }
    }

    /** 命令输出配色：成功=绿，提醒/刷新=金，列表/正文=灰，错误由 sendError 处理。 */
    private static MutableComponent green(String s) { return Component.literal(s).withStyle(ChatFormatting.GREEN); }
    private static MutableComponent gold(String s) { return Component.literal(s).withStyle(ChatFormatting.GOLD); }
    private static MutableComponent gray(String s) { return Component.literal(s).withStyle(ChatFormatting.GRAY); }

    /** 多语言文本 → 组件（统一入口，避免到处写 Lang.tr + Component.literal）。 */
    private static MutableComponent tex(String key, Object... args) {
        return Component.literal(Lang.tr(key, args));
    }

    /** 异常信息 → 可读文本（message 为空时退回异常类名）。 */
    private static String errText(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.isBlank() ? (t == null ? "unknown" : t.getClass().getSimpleName()) : m;
    }

    /** 当前时间戳（线程安全，替代原来的静态 SimpleDateFormat）。 */
    private static String stamp() {
        return STAMP.format(LocalDateTime.now());
    }

    /** 生成不存在的文件名（同一秒内多次拍摄/导出时避免互相覆盖）。 */
    private static Path uniqueFile(Path dir, String base, String ext) {
        Path f = dir.resolve(base + ext);
        int i = 2;
        while (Files.exists(f)) f = dir.resolve(base + "_" + i++ + ext);
        return f;
    }

    /** 直接往聊天栏发消息（无命令源时用，如导入子页面完成后）。 */
    private static void chat(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.getChat() != null) mc.gui.getChat().addMessage(c);
    }

    /** 把快照文件名做成可点击组件：点击执行 /openrec <id>，在系统文件管理器中显示该文件位置。 */
    private static MutableComponent fileLink(String name) {
        String raw = name;
        if (raw.endsWith(".snap")) raw = raw.substring(0, raw.length() - 5);
        if (raw.startsWith("snapshot_")) raw = raw.substring("snapshot_".length());
        final String id = raw;
        return Component.literal(name).withStyle(s -> s
                .withUnderlined(true)
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.RunCommand("/openrec " + id)));
    }

    /**
     * /openrec <id>：在系统文件管理器中显示该快照所在位置（纯客户端）。
     * 跨平台：Windows=explorer /select，macOS=open -R，Linux= xdg-open 父目录，其余回退 java.awt.Desktop。
     * 安卓/iOS 无桌面文件管理器，无法从游戏内打开（移动端沙盒限制）。
     */
    public int openSnapshotLocation(FabricClientCommandSource src, String rawId) {
        String id = rawId == null ? "" : rawId.trim();
        if (id.endsWith(".snap")) id = id.substring(0, id.length() - 5);
        if (id.contains("/") || id.contains("\\") || id.contains("..") || id.isEmpty()) {
            src.sendError(tex("msg.bad_id", rawId));
            return 0;
        }
        Path file = resolveSnapshotFile(id);
        if (file == null) {
            src.sendError(tex("msg.snapshot_missing", id));
            return 0;
        }
        revealInFileManager(file, src);
        return 1;
    }

    /** /trzip <绝对路径>：在文件管理器中显示导出的 zip 所在位置（供点击组件调用）。 */
    public int openZipLocation(FabricClientCommandSource src, String rawPath) {
        String p = rawPath == null ? "" : rawPath.trim();
        if (p.isEmpty()) { src.sendError(tex("msg.missing_zip_path")); return 0; }
        Path zip;
        try { zip = Path.of(p).toAbsolutePath().normalize(); }
        catch (Exception e) { src.sendError(tex("msg.bad_path", rawPath)); return 0; }
        if (!Files.isRegularFile(zip)) { src.sendError(tex("msg.zip_missing", zip.toString())); return 0; }
        revealInFileManager(zip, src);
        return 1;
    }

    private void revealInFileManager(Path file, FabricClientCommandSource src) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String path = file.toAbsolutePath().toString();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("explorer", "/select,", path).start();
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", "-R", path});
            } else if (os.contains("linux") || os.contains("nix") || os.contains("aix")) {
                Path dir = file.getParent();
                Runtime.getRuntime().exec(new String[]{"xdg-open", dir == null ? path : dir.toAbsolutePath().toString()});
            } else if (Desktop.isDesktopSupported()) {
                Path dir = file.getParent();
                Desktop.getDesktop().open(dir == null ? file.toFile() : dir.toFile());
            } else {
                src.sendError(tex("msg.reveal_unsupported", snapshotsDir().toString()));
                return;
            }
            src.sendFeedback(green(Lang.tr("msg.reveal_ok", file.getFileName().toString())));
        } catch (Exception e) {
            TerrainRecorderMod.LOGGER.warn("打开文件位置失败: {}", path, e);
            src.sendError(tex("msg.reveal_failed", errText(e)));
        }
    }

    public Set<BlockPos> diffSet() { return diffSet; }

    public boolean isFindActive() { return findActive; }

    public Set<BlockPos> minRemoveSet() { return minRemoveSet; }

    public Set<BlockPos> addAddedSet() { return addAddedSet; }
}