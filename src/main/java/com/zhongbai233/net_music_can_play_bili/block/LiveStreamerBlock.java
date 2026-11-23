package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.LiveStreamerClientHooks;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/** Desktop live receiver. The physical controls face the player on placement. */
public class LiveStreamerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final BooleanProperty PLAYING = BooleanProperty.create("playing");
    private static final MapCodec<LiveStreamerBlock> CODEC = simpleCodec(LiveStreamerBlock::new);

    private static final VoxelShape HOUSING = Shapes.or(
            Block.box(3.55, 0, 5.4, 12.45, .62, 11.1),
            Block.box(3, .57, 5, 13, 4.65, 11.5),
            Block.box(3.19, 1.15, 4.53, 5.29, 3.67, 5.1));
    private static final Map<Direction, VoxelShape> COLLISION = Shapes.rotateHorizontal(HOUSING);
    private static final Map<Direction, VoxelShape> OUTLINE = Shapes.rotateHorizontal(Shapes.or(HOUSING,
            Block.box(4.07, 4.46, 9.37, 6.20, 5.42, 10.84),
            Block.box(3.8, 5.35, 9.7, 5.62, 7.15, 10.47),
            Block.box(2.82, 7, 9.7, 4.35, 8.70, 10.47)));

    public LiveStreamerBlock(Identifier id) {
        this(BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, id))
                .sound(SoundType.METAL)
                .strength(1.5F)
                .noOcclusion());
    }

    public LiveStreamerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.SOUTH)
                .setValue(PLAYING, false));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION.get(state.getValue(FACING));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LiveStreamerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.LIVE_STREAMER.get()) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> LiveStreamerBlockEntity.tick(
                tickLevel, pos, tickState, (LiveStreamerBlockEntity) blockEntity);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        AudioLinkIndex.removePlaybackSource(level, pos);
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (hand == InteractionHand.OFF_HAND) {
            return InteractionResult.PASS;
        }
        // 手持链接物品右键 → 存储连接目标到物品 NBT，与现代化唱片机一致
        if (stack.getItem() == ModItems.SPEAKER.get()) {
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            LinkHelper.writeLinkToItem(stack, pos);
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.speaker.item_linked",
                    pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GOLD));
            return InteractionResult.SUCCESS;
        }
        if (stack.getItem() == ModItems.VIDEO_PROJECTOR.get()) {
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            LinkHelper.writeLinkToItem(stack, pos);
            VideoProjectorBlock.writeLinkedBlockEntityData(stack, pos);
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.video_projector.item_linked",
                    pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GOLD));
            return InteractionResult.SUCCESS;
        }
        if (stack.getItem() == ModItems.CONTROL_CONSOLE.get()) {
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
                LinkHelper.writeControlConsoleLinkToItem(stack, pos, level.dimension().identifier().toString(),
                    LinkHelper.ControlConsoleSourceKind.LIVE_STREAMER);
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.control_console.item_linked",
                    pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GOLD));
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) {
            LiveStreamerClientHooks.openLiveStreamerScreen(pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (level.isClientSide()) {
            LiveStreamerClientHooks.openLiveStreamerScreen(pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
        if (level instanceof Level realLevel && !realLevel.isClientSide()
                && realLevel.getBlockEntity(pos) instanceof LiveStreamerBlockEntity streamer) {
            streamer.stopForBlockRemoval();
        }
        super.destroy(level, pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PLAYING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
