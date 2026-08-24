package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 中控台方块；首版只提供放置、文档宿主和编辑入口，不启动媒体运行时。 */
public final class ControlConsoleBlock extends Block implements EntityBlock {
    private static final MapCodec<ControlConsoleBlock> CODEC = simpleCodec(ControlConsoleBlock::new);
    /** 覆盖 Blockbench 模型约 0..1.127 X、0..1.813 Y、0..0.986 Z 的完整选择范围。 */
    private static final VoxelShape SELECTION_SHAPE = Block.box(-1, 0, 0, 18, 29, 16);
    /** 只碰撞设备的主要承重结构，避免旋转薄板形成大量难以通过的空气盒。 */
    private static final VoxelShape COLLISION_SHAPE = Shapes.or(
            Block.box(1, 0, 1, 15, 4, 15),
            Block.box(7, 4, 7, 9, 29, 9),
            Block.box(3, 18, 7, 13, 27, 9),
            Block.box(5, 25, 0, 11, 27, 7));

    public ControlConsoleBlock(Identifier id) {
        this(BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, id))
                .strength(2.5F)
                .noOcclusion());
    }

    public ControlConsoleBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ControlConsoleBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        if (placer instanceof Player player
                && level.getBlockEntity(pos) instanceof ControlConsoleBlockEntity console) {
            console.claimIfUnowned(player.getUUID());
        }
        com.zhongbai233.net_music_can_play_bili.link.LinkHelper.ControlConsoleLink link =
            com.zhongbai233.net_music_can_play_bili.link.LinkHelper.readControlConsoleLinkFromItem(stack);
        if (link == null) {
            return;
        }
        BlockPos sourcePos = link.pos();
        BlockEntity source = level.getBlockEntity(sourcePos);
        String currentDimension = level.dimension().identifier().toString();
        boolean correctDimension = link.legacy() || currentDimension.equals(link.dimension());
        boolean validSource = link.legacy()
            ? source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity
                || source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity
            : link.sourceKind() == com.zhongbai233.net_music_can_play_bili.link.LinkHelper.ControlConsoleSourceKind.TURNTABLE
                ? source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity
                : source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
        if (correctDimension && validSource
            && level.getBlockEntity(pos) instanceof ControlConsoleBlockEntity console) {
            console.linkTo(currentDimension, sourcePos,
                link.sourceKind() == com.zhongbai233.net_music_can_play_bili.link.LinkHelper.ControlConsoleSourceKind.LIVE_STREAMER
                    ? com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument.SourceKind.LIVE_STREAMER
                    : com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument.SourceKind.TURNTABLE);
            if (!(placer instanceof Player player) || !player.isCreative()) {
                com.zhongbai233.net_music_can_play_bili.link.LinkHelper.clearLinkFromItem(stack);
            }
        } else if (placer instanceof Player player) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.net_music_can_play_bili.control_console.invalid_source"));
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return SELECTION_SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level,
            BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return COLLISION_SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState above = level.getBlockState(pos.above());
        return above.canBeReplaced() || above.isAir();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!player.mayBuild()
                && !player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return InteractionResult.FAIL;
        }
        if (!(level.getBlockEntity(pos) instanceof ControlConsoleBlockEntity console)) {
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide()) {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                console.claimIfUnowned(serverPlayer.getUUID());
                return console.canEdit(serverPlayer) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            return InteractionResult.FAIL;
        }
        com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.openScreen(pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
