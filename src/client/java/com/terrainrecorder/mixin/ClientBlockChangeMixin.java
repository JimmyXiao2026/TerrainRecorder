package com.terrainrecorder.mixin;

import com.terrainrecorder.TerrainManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入 ClientLevel 的方块更新入口，驱动 /recmode 与 /find 的增量逻辑。
 *
 * <p>1.21.11 里服务器下发的方块变化走的是
 * {@code ClientLevel.setServerVerifiedBlockState(BlockPos, BlockState, int)}，
 * 它在内部直接调用 {@code Level.setBlock}，<b>不会</b>经过 {@code ClientLevel.setBlock}。
 * 所以只注入 setBlock 时，多人/服务器环境下的实时更新其实一次都不会触发。
 *
 * <p>此外，区块批量更新包（ClientboundSectionBlocksUpdatePacket）会复用同一个
 * {@code BlockPos.MutableBlockPos} 逐个回调，这里必须 {@code pos.immutable()} 复制一份再交给管理器，
 * 否则所有位置都会指向同一个可变对象。
 */
@Mixin(ClientLevel.class)
public class ClientBlockChangeMixin {

    /** 服务器下发的单个 / 批量方块更新（权威状态）。 */
    @Inject(
            method = "setServerVerifiedBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
            at = @At("RETURN"))
    private void terrainrecorder$onServerVerifiedBlockState(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        TerrainManager.get().onClientBlockChange(self, pos.immutable(), self.getBlockState(pos));
    }

    /** 客户端本地路径（预测放置、指令等）：有些调用仍然走 ClientLevel.setBlock。 */
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void terrainrecorder$onSetBlock(BlockPos pos, BlockState state, int flags, int recursion,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            ClientLevel self = (ClientLevel) (Object) this;
            TerrainManager.get().onClientBlockChange(self, pos.immutable(), self.getBlockState(pos));
        }
    }
}
