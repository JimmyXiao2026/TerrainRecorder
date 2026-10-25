package com.terrainrecorder.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.terrainrecorder.TerrainManager;
import com.terrainrecorder.TerrainRecorderMod;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Set;

/**
 * 把 /find 检测出的差异方块高亮为红色（红框）；蓝框用于两处：
 *  /recmode add 期间新加入地形图的多余方块、/recmode min 期间被移除的方块。
 * 均锚定在世界坐标上、可穿透地形查看。
 *
 * 参考 Wurst 1.21.11 分支（WurstShaderPipelines + WurstRenderLayers）：
 * 用 RenderPipelines.DEBUG_FILLED_SNIPPET 自建一条无深度测试渲染管线
 * （DepthTestFunction.NO_DEPTH_TEST + withCull(false)），包成 RenderType 后
 * 在世界渲染里用 POSITION_COLOR 顶点格式给方块 6 面填色 —— 可穿透地形显示。
 */
public final class FindRenderer {

    // 无深度测试渲染管线：框可以穿透地形显示（照抄 Wurst 1.21.11 分支做法）
    private static final RenderPipeline BOX_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.parse("terrainrecorder:pipeline/find_red_box"))
                    .withCull(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());

    // 把管线包成 RenderType，走普通 BufferSource 提交
    private static final RenderType BOX = RenderType.create(
            "terrainrecorder:find_red_box",
            RenderSetup.builder(BOX_PIPELINE).sortOnUpload().useLightmap().createRenderSetup());

    private static final int RED_R = 255, RED_G = 24, RED_B = 24, RED_A = 150;   // 红框（find 差异）
    private static final int BLUE_R = 24, BLUE_G = 24, BLUE_B = 255, BLUE_A = 150; // 蓝框（min 新增）
    private static final float EPS = 0.002f; // 稍微外扩避免 z-fighting

    // 渲染上限：每帧最多渲染 MAX_BOXES 个方块框，超出部分本帧跳过。
    // 这些框是半透明、无深度测试的填充面，数量一多就是成片的 overdraw（很吃显卡），
    // 所以这里刻意压得比较低：差异很多时本来也看不清，800 个足够判断。
    private static final int MAX_BOXES = 800;
    private static final int MAX_DIST_BLOCKS = 10 * 16; // 10 区块 ≈ 160 格
    private static int frameBoxes = 0;

    // 分段渲染：刚开启 /find（或差异突然变多）时，每帧只多画 RAMP_STEP 个框，
    // 大约 8 帧内逐步涨到上限，避免第一帧突然写入几百个框造成明显卡顿。
    private static final int RAMP_STEP = 100;
    private static int allowance = 0;

    private FindRenderer() {}

    /** 世界渲染回调（AFTER_ENTITIES）：红框 = find 差异；蓝框 = recmode add 新加入 或 min 被移除的方块。 */
    public static void render(WorldRenderContext context) {
        Set<BlockPos> diff = TerrainManager.get().diffSet();
        Set<BlockPos> blue = TerrainManager.get().minRemoveSet();
        Set<BlockPos> blueAdd = TerrainManager.get().addAddedSet();
        if (diff.isEmpty() && blue.isEmpty() && blueAdd.isEmpty()) {
            allowance = 0;
            return;
        }

        // 分段渲染：本帧允许出现的方框数从上一帧平滑增长到目标值（差异减少时立即收缩）
        int total = diff.size() + blue.size() + blueAdd.size();
        int target = Math.min(MAX_BOXES, total);
        if (allowance > target) allowance = target;
        else if (allowance < target) allowance = Math.min(target, allowance + RAMP_STEP);

        // 该管线的 ModelViewMat uniform 只带相机旋转、不带相机位移。所以把相机位移
        // 烘焙进顶点（只平移、不旋转），旋转由 uniform 应用 → 框锚定在世界坐标。
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 pos = camera.position();
        Matrix4f pose = new Matrix4f()
                .translate(-(float) pos.x(), -(float) pos.y(), -(float) pos.z());

        MultiBufferSource consumers = context.consumers();
        frameBoxes = 0;
        try {
            renderSet(pose, consumers, diff, RED_R, RED_G, RED_B, RED_A, pos);
            renderSet(pose, consumers, blue, BLUE_R, BLUE_G, BLUE_B, BLUE_A, pos);
            renderSet(pose, consumers, blueAdd, BLUE_R, BLUE_G, BLUE_B, BLUE_A, pos);
        } catch (Exception e) {
            // 防御：单帧渲染异常不崩游戏（差异过多或 buffer 异常时）
            TerrainRecorderMod.LOGGER.warn("FindRenderer 渲染异常", e);
        }
        // 统一提交一次
        if (consumers instanceof MultiBufferSource.BufferSource bs) {
            try { bs.endBatch(BOX); } catch (Exception ignored) { }
        }
    }

    /**
     * 渲染一组方块框，带数量上限；超过 10 区块距离的方块不渲染。
     * 距离判断直接用相机坐标（不再为每个框取一次 player.blockPosition()，避免每帧成百上千次分配）。
     *
     * <p>注意：这里<b>不做"相机背后剔除"</b>。之前为了省顶点加了它，但那样"画哪 800 个"
     * 会随视角变化——差异多于上限时，每转一下视角可见的那批框就换一批，看起来就是色块忽隐忽现。
     * 屏幕外的框交给 GPU 裁剪即可，顶点开销很小，选择必须与视角无关。
     */
    private static void renderSet(Matrix4f pose, MultiBufferSource consumers,
                                  Set<BlockPos> set, int r, int g, int b, int a,
                                  Vec3 camPos) {
        VertexConsumer vc = consumers.getBuffer(BOX);
        double camX = camPos.x(), camZ = camPos.z();
        final double maxDist = MAX_DIST_BLOCKS;
        for (BlockPos p : set) {
            if (frameBoxes >= allowance) return; // 本帧可达上限（分段增长的当前值）
            if (Math.abs(p.getX() + 0.5 - camX) > maxDist || Math.abs(p.getZ() + 0.5 - camZ) > maxDist) continue;
            frameBoxes++;
            float x = p.getX(), y = p.getY(), z = p.getZ();
            filledBox(pose, vc, x - EPS, y - EPS, z - EPS,
                    x + 1 + EPS, y + 1 + EPS, z + 1 + EPS, r, g, b, a);
        }
    }

    /** 六面填充（无剔除 + 无深度测试 → 穿墙可见）。顶点格式 POSITION_COLOR。 */
    private static void filledBox(Matrix4f pose, VertexConsumer vc,
                                  float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ,
                                  int r, int g, int b, int a) {
        // 前 (z+)
        face(pose, vc, minX,minY,maxZ, maxX,minY,maxZ, maxX,maxY,maxZ, minX,maxY,maxZ, r,g,b,a);
        // 后 (z-)
        face(pose, vc, maxX,minY,minZ, minX,minY,minZ, minX,maxY,minZ, maxX,maxY,minZ, r,g,b,a);
        // 左 (x-)
        face(pose, vc, minX,minY,minZ, minX,minY,maxZ, minX,maxY,maxZ, minX,maxY,minZ, r,g,b,a);
        // 右 (x+)
        face(pose, vc, maxX,minY,maxZ, maxX,minY,minZ, maxX,maxY,minZ, maxX,maxY,maxZ, r,g,b,a);
        // 上 (y+)
        face(pose, vc, minX,maxY,maxZ, maxX,maxY,maxZ, maxX,maxY,minZ, minX,maxY,minZ, r,g,b,a);
        // 下 (y-)
        face(pose, vc, minX,minY,minZ, maxX,minY,minZ, maxX,minY,maxZ, minX,minY,maxZ, r,g,b,a);
    }

    private static void face(Matrix4f pose, VertexConsumer vc,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             int r, int g, int b, int a) {
        vc.addVertex(pose, x0, y0, z0).setColor(r, g, b, a);
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
    }
}
