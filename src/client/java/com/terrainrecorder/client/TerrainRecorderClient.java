package com.terrainrecorder.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.terrainrecorder.Lang;
import com.terrainrecorder.TerrainManager;
import com.terrainrecorder.TerrainRecorderMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 纯客户端入口。命令为客户端侧命令（本地执行，不发给服务器），
 * 方块数据直接从客户端内存（ClientLevel）读取，单人或多人服务器均可使用。
 */
public class TerrainRecorderClient implements ClientModInitializer {

    /** 官方按键绑定（可在"选项-按键绑定"里改键）。 */
    private KeyMapping[] hotkeys;

    @Override
    public void onInitializeClient() {
        TerrainManager mgr = TerrainManager.get();
        registerHotkeys();

        // 跟踪客户端已加载区块（边走边实时扫描）
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> mgr.onChunkLoad(level, chunk.getPos()));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> mgr.onChunkUnload(level, chunk.getPos()));

        // 每 tick：检测换世界 + 活动模式下周期安全重扫 + 处理快捷键
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != null) mgr.onClientTick(mc.level);
            handleHotkeys(mgr);
        });

        // 客户端侧命令
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> registerCommands(dispatcher, mgr));

        // 世界渲染中绘制穿墙 3D 红色差异方块（锚定在世界坐标，可透视查看）
        WorldRenderEvents.AFTER_ENTITIES.register(FindRenderer::render);

        // 断开/切换服务器时清理
        ClientPlayConnectionEvents.DISCONNECT.register((handler, server) -> TerrainManager.get().reset());

        TerrainRecorderMod.LOGGER.info("TerrainRecorder 已加载（纯客户端，内存读取，MC 1.21.11）");
    }

    /** 注册官方按键绑定：\\ = 拍摄快照(/rec)，= = 修正添加(/recmode add)，- = 修正减少(/recmode min)，BackSpace = 修正停止(/recmode stop)，Enter = 寻找/关闭(/find 开关)。 */
    private void registerHotkeys() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.parse("terrainrecorder:category"));
        hotkeys = new KeyMapping[]{
                registerKey("key.terrainrecorder.snapshot", GLFW.GLFW_KEY_BACKSLASH, cat),
                registerKey("key.terrainrecorder.recmode_add", GLFW.GLFW_KEY_EQUAL, cat),
                registerKey("key.terrainrecorder.recmode_min", GLFW.GLFW_KEY_MINUS, cat),
                registerKey("key.terrainrecorder.recmode_stop", GLFW.GLFW_KEY_BACKSPACE, cat),
                registerKey("key.terrainrecorder.find", GLFW.GLFW_KEY_ENTER, cat)
        };
    }

    private KeyMapping registerKey(String name, int key, KeyMapping.Category cat) {
        KeyMapping k = new KeyMapping(name, key, cat);
        KeyBindingHelper.registerKeyBinding(k);
        return k;
    }

    /** 每 tick 检测快捷键是否被按下，按下则执行对应命令（复用客户端命令逻辑）。 */
    private void handleHotkeys(TerrainManager mgr) {
        if (hotkeys == null) return;
        ClientSource src = new ClientSource();
        for (int i = 0; i < hotkeys.length; i++) {
            if (hotkeys[i].consumeClick()) {
                switch (i) {
                    case 0 -> mgr.saveSnapshot(src);
                    case 1 -> mgr.startRecMode(src, TerrainManager.RecMode.ADD);
                    case 2 -> mgr.startRecMode(src, TerrainManager.RecMode.MIN);
                    case 3 -> mgr.stopRecMode(src);
                    case 4 -> { // /find 开关：已在 find 内则关闭，否则开启
                        if (mgr.isFindActive()) mgr.stopFind(src);
                        else mgr.startFind(src);
                    }
                }
            }
        }
    }

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, TerrainManager mgr) {
        // /rec 与别名 /rc：保存时间戳快照
        for (String root : new String[]{"rec", "rc"}) {
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal(root)
                    .executes(ctx -> mgr.saveSnapshot(ctx.getSource())));
        }

        // /recmode 与别名 /rcm（子命令兼容；无参数时显示帮助）
        registerRecMode(dispatcher, mgr);

        // /help recmode、/help rcm、/help delrec、/help export、/help import
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("help")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("recmode").executes(ctx -> recmodeHelp(ctx.getSource())))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rcm").executes(ctx -> recmodeHelp(ctx.getSource())))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("delrec").executes(ctx -> delrecHelp(ctx.getSource())))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("export").executes(ctx -> recExportHelp(ctx.getSource())))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rcex").executes(ctx -> recExportHelp(ctx.getSource())))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("import").executes(ctx -> recImportHelp(ctx.getSource())))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rcim").executes(ctx -> recImportHelp(ctx.getSource()))));

        // /delrec：无参数时显示帮助
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("delrec")
                .executes(ctx -> delrecHelp(ctx.getSource()))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("all")
                        .executes(ctx -> mgr.deleteAllSnapshots(ctx.getSource()))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("else")
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("keep", StringArgumentType.string())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("latest");
                                            for (String id : mgr.listSnapshotIds()) builder.suggest(id);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> mgr.deleteAllExcept(ctx.getSource(), StringArgumentType.getString(ctx, "keep"))))))
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (String id : mgr.listSnapshotIds()) builder.suggest(id);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> mgr.deleteSnapshot(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));

        // /openrec <id>：隐藏命令，供"点击文件名打开所在位置"使用（带补全）
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("openrec")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (String id : mgr.listSnapshotIds()) builder.suggest(id);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> mgr.openSnapshotLocation(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));

        // /trimp <overwrite|skip> <rel> / <overwriteall|cancelall>：导入确认按钮的隐藏执行入口（TUI）
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("trimp")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("overwrite")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("rel", StringArgumentType.greedyString())
                                .executes(ctx -> mgr.handleImportAction(ctx.getSource(), "overwrite", StringArgumentType.getString(ctx, "rel")))))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("skip")
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("rel", StringArgumentType.greedyString())
                                .executes(ctx -> mgr.handleImportAction(ctx.getSource(), "skip", StringArgumentType.getString(ctx, "rel")))))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("overwriteall")
                        .executes(ctx -> mgr.handleImportAction(ctx.getSource(), "overwriteall", null)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cancelall")
                        .executes(ctx -> mgr.handleImportAction(ctx.getSource(), "cancelall", null))));

        // /trzip <绝对路径>：导出成功文本的点击组件，在文件管理器中显示该 zip 所在位置
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("trzip")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("path", StringArgumentType.greedyString())
                        .executes(ctx -> mgr.openZipLocation(ctx.getSource(), StringArgumentType.getString(ctx, "path")))));

        // /rec export <id...> [-a | -o <名称>]（别名 /rcex）：导出多张快照为 zip；无参数显示帮助
        for (String root : new String[]{"export", "rcex"}) {
            if (root.equals("rcex")) {
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal(root)
                        .executes(ctx -> recExportHelp(ctx.getSource()))
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("spec", StringArgumentType.greedyString())
                                .suggests(exportSuggestions(mgr))
                                .executes(ctx -> mgr.exportSnapshots(ctx.getSource(), StringArgumentType.getString(ctx, "spec")))));
            } else {
                // "export" 挂在 /rec 下
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rec")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal(root)
                                .executes(ctx -> recExportHelp(ctx.getSource()))
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("spec", StringArgumentType.greedyString())
                                        .suggests(exportSuggestions(mgr))
                                        .executes(ctx -> mgr.exportSnapshots(ctx.getSource(), StringArgumentType.getString(ctx, "spec"))))));
            }
        }

        // /rec import [-x <zip路径>] [-f]（别名 /rcim）：导入 zip；无参数显示帮助
        for (String root : new String[]{"import", "rcim"}) {
            if (root.equals("rcim")) {
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal(root)
                        .executes(ctx -> recImportHelp(ctx.getSource()))
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("spec", StringArgumentType.greedyString())
                                .suggests(importSuggestions(mgr))
                                .executes(ctx -> mgr.importSnapshots(ctx.getSource(), StringArgumentType.getString(ctx, "spec")))));
            } else {
                // "import" 挂在 /rec 下
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rec")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal(root)
                                .executes(ctx -> recImportHelp(ctx.getSource()))
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("spec", StringArgumentType.greedyString())
                                        .suggests(importSuggestions(mgr))
                                        .executes(ctx -> mgr.importSnapshots(ctx.getSource(), StringArgumentType.getString(ctx, "spec"))))));
            }
        }

        // /find 与别名 /fd：无参=自动匹配并实时差异红框，off=关闭，<id>=指定快照
        for (String root : new String[]{"find", "fd"}) {
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal(root)
                    .executes(ctx -> mgr.startFind(ctx.getSource()))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                            .executes(ctx -> mgr.stopFind(ctx.getSource())))
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.string())
                            .suggests((ctx, builder) -> {
                                for (String id : mgr.listSnapshotIds()) builder.suggest(id);
                                return builder.buildFuture();
                            })
                            .executes(ctx -> mgr.startFind(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        }

        // /rec download import <url>（缩写 /rec -d -i <url>）：下载 zip 到 zips → 导入（/rcim）→ 删压缩包
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rec")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("download")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("import")
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("url", StringArgumentType.greedyString())
                                        .suggests(downloadUrlSuggestions())
                                        .executes(ctx -> mgr.downloadImport(ctx.getSource(), StringArgumentType.getString(ctx, "url"))))))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("-d")
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("-i")
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("url", StringArgumentType.greedyString())
                                        .suggests(downloadUrlSuggestions())
                                        .executes(ctx -> mgr.downloadImport(ctx.getSource(), StringArgumentType.getString(ctx, "url")))))));
    }

    /** 下载源补全：内置 jx-bjd-dmm（固定 URL）。 */
    private static SuggestionProvider<FabricClientCommandSource> downloadUrlSuggestions() {
        return (ctx, builder) -> {
            String cur = builder.getInput().substring(builder.getStart());
            if ("jx-bjd-dmm".startsWith(cur)) builder.suggest("jx-bjd-dmm");
            return builder.buildFuture();
        };
    }

    /** 导出命令的补全：快照 id/文件名 + -a + -o（多 token，只替换当前词，并排除已添加的 id）。 */
    private static SuggestionProvider<FabricClientCommandSource> exportSuggestions(TerrainManager mgr) {
        return (ctx, builder) -> completeArg(ctx, builder, (b, cur, before) -> {
            List<String> used = tokens(before); // 当前词之前已输入的完整 token（含已选 id、-a、-o）
            String last = used.isEmpty() ? "" : used.get(used.size() - 1);
            if (last.equalsIgnoreCase("-o")) return; // -o 后是任意 zip 名，无需补全
            boolean hasA = used.stream().anyMatch(s -> s.equalsIgnoreCase("-a") || s.equalsIgnoreCase("-a-f") || s.equalsIgnoreCase("-af"));
            boolean hasO = used.stream().anyMatch(s -> s.equalsIgnoreCase("-o"));
            for (String id : mgr.listSnapshotIds())
                if (!used.stream().anyMatch(s -> s.equalsIgnoreCase(id)) && id.startsWith(cur)) b.suggest(id); // 已添加的不再出现
            if (!hasA && "-a".startsWith(cur)) { b.suggest("-a"); b.suggest("-a-f"); }
            if (!hasO && "-o".startsWith(cur)) b.suggest("-o");
        });
    }

    /** 导入命令的补全：zips 目录里的 zip 文件 + -x + -f（多 token，只替换当前词，并排除已添加的 zip）。 */
    private static SuggestionProvider<FabricClientCommandSource> importSuggestions(TerrainManager mgr) {
        return (ctx, builder) -> completeArg(ctx, builder, (b, cur, before) -> {
            List<String> used = tokens(before);
            String last = used.isEmpty() ? "" : used.get(used.size() - 1);
            if (last.equalsIgnoreCase("-x") || last.equalsIgnoreCase("-f")) return; // -x 后是 zip 路径；-f 是最后一个参数
            boolean hasX = used.stream().anyMatch(s -> s.equalsIgnoreCase("-x"));
            boolean hasF = used.stream().anyMatch(s -> s.equalsIgnoreCase("-f"));
            for (String z : mgr.listZips())
                if (!used.stream().anyMatch(s -> s.equalsIgnoreCase(z)) && z.startsWith(cur)) b.suggest(z);
            if (!hasX && "-x".startsWith(cur)) b.suggest("-x");
            if (!hasF && "-f".startsWith(cur)) b.suggest("-f");
        });
    }

    /** 把一段以空格分隔的已输入文本拆成 token 列表。 */
    private static List<String> tokens(String s) {
        if (s == null || s.trim().isEmpty()) return List.of();
        return List.of(s.trim().split("\\s+"));
    }

    /**
     * 处理 greedyString 多 token 参数的补全：
     * 把替换范围限定为"当前正在输入的那一个词"（光标前最后一个空格之后），避免按 Tab 时把前面已填的词一并替换掉。
     */
    private static CompletableFuture<Suggestions> completeArg(CommandContext<FabricClientCommandSource> ctx,
                                                              SuggestionsBuilder builder, ArgCompleter completer) {
        String input = builder.getInput();
        int argStart = Math.min(builder.getStart(), input.length());
        String arg = input.substring(argStart); // 参数起点到光标的文本（可能含前面多个词）
        int sp = arg.lastIndexOf(' ');
        String cur = sp < 0 ? arg : arg.substring(sp + 1);       // 当前词
        int curStart = argStart + (sp < 0 ? 0 : sp + 1);          // 当前词起点
        String before = sp < 0 ? "" : arg.substring(0, sp).trim(); // 当前词之前的完整 token
        SuggestionsBuilder b = new SuggestionsBuilder(input, curStart); // 只替换当前词
        completer.accept(b, cur, before);
        return b.buildFuture();
    }

    @FunctionalInterface
    private interface ArgCompleter { void accept(SuggestionsBuilder b, String cur, String before); }

    /** /recmode 与 /rcm：同一个命令树注册两个根名，子命令 add/min/stop 及别名 a/m/s 兼容。 */
    private void registerRecMode(CommandDispatcher<FabricClientCommandSource> dispatcher, TerrainManager mgr) {        for (String root : new String[]{"recmode", "rcm"}) {
            LiteralArgumentBuilder<FabricClientCommandSource> b = LiteralArgumentBuilder.<FabricClientCommandSource>literal(root)
                    .executes(ctx -> recmodeHelp(ctx.getSource())); // 无参数 → 帮助
            b.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add").executes(ctx -> mgr.startRecMode(ctx.getSource(), TerrainManager.RecMode.ADD)));
            b.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("a").executes(ctx -> mgr.startRecMode(ctx.getSource(), TerrainManager.RecMode.ADD)));
            b.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("min").executes(ctx -> mgr.startRecMode(ctx.getSource(), TerrainManager.RecMode.MIN)));
            b.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("m").executes(ctx -> mgr.startRecMode(ctx.getSource(), TerrainManager.RecMode.MIN)));
            b.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(ctx -> mgr.stopRecMode(ctx.getSource())));
            b.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("s").executes(ctx -> mgr.stopRecMode(ctx.getSource())));
            dispatcher.register(b);
        }
    }

    /** /recmode（/rcm，/help recmode，/help rcm）帮助界面。 */
    private static int recmodeHelp(FabricClientCommandSource src) {
        src.sendFeedback(gold(Lang.tr("help.recmode.title") + "\n")
                .append(gray(Lang.tr("help.recmode.body"))));
        return 1;
    }

    /** /delrec 帮助界面。 */
    private static int delrecHelp(FabricClientCommandSource src) {
        src.sendFeedback(gold(Lang.tr("help.delrec.title") + "\n")
                .append(gray(Lang.tr("help.delrec.body"))));
        return 1;
    }

    /** /rec export（/rcex，/help export）帮助界面。 */
    private static int recExportHelp(FabricClientCommandSource src) {
        src.sendFeedback(gold(Lang.tr("help.export.title") + "\n")
                .append(gray(Lang.tr("help.export.body"))));
        return 1;
    }

    /** /rec import（/rcim，/help import）帮助界面。 */
    private static int recImportHelp(FabricClientCommandSource src) {
        src.sendFeedback(gold(Lang.tr("help.import.title") + "\n")
                .append(gray(Lang.tr("help.import.body"))));
        return 1;
    }

    /** 命令输出配色（与 TerrainManager 保持一致）。 */
    private static MutableComponent gold(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GOLD);
    }

    private static MutableComponent gray(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GRAY);
    }

    /**
     * 从按键触发命令用的轻量客户端命令源：
     * 把 sendFeedback/sendError 转发到聊天栏，getWorld/getPlayer 取当前客户端。
     * 其余 SharedSuggestionProvider / PermissionSetSupplier 抽象方法返回空值即可（本 mod 用不到）。
     */
    private static final class ClientSource implements FabricClientCommandSource {
        @Override public void sendFeedback(Component c) { chat(c); }
        @Override public void sendError(Component c) { chat(c.copy().withStyle(ChatFormatting.RED)); }
        @Override public Minecraft getClient() { return Minecraft.getInstance(); }
        @Override public LocalPlayer getPlayer() { return Minecraft.getInstance().player; }
        @Override public ClientLevel getWorld() { return Minecraft.getInstance().level; }
        @Override public Collection<String> getOnlinePlayerNames() { return List.of(); }
        @Override public Collection<String> getAllTeams() { return List.of(); }
        @Override public Stream<Identifier> getAvailableSounds() { return Stream.empty(); }
        @Override public CompletableFuture<Suggestions> customSuggestion(CommandContext<?> ctx) { return emptySuggestions(); }
        @Override public Set<ResourceKey<Level>> levels() { return Set.of(); }
        @Override public RegistryAccess registryAccess() { ClientLevel l = getWorld(); return l != null ? l.registryAccess() : RegistryAccess.EMPTY; }
        @Override public FeatureFlagSet enabledFeatures() { ClientLevel l = getWorld(); return l != null ? l.enabledFeatures() : FeatureFlags.VANILLA_SET; }
        @Override public CompletableFuture<Suggestions> suggestRegistryElements(ResourceKey<? extends Registry<?>> key,
                                                                                SharedSuggestionProvider.ElementSuggestionType type,
                                                                                SuggestionsBuilder builder, CommandContext<?> ctx) {
            return emptySuggestions();
        }
        @Override public PermissionSet permissions() { return PermissionSet.NO_PERMISSIONS; }

        private static CompletableFuture<Suggestions> emptySuggestions() {
            return CompletableFuture.completedFuture(new Suggestions(StringRange.at(0), List.of()));
        }

        private static void chat(Component c) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui != null && mc.gui.getChat() != null) mc.gui.getChat().addMessage(c);
        }
    }
}