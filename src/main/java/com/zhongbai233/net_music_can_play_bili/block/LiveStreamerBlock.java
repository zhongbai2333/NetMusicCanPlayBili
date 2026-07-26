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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
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

/** 直播机方块：机柜造型，右键打开控制界面，可与音响、投影仪链接。 */
public class LiveStreamerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final BooleanProperty PLAYING = BooleanProperty.create("playing");
    private static final MapCodec<LiveStreamerBlock> CODEC = simpleCodec(LiveStreamerBlock::new);

    public LiveStreamerBlock(Identifier id) {
        this(BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, id))
                .sound(SoundType.METAL)
                .strength(1.5F));
    }

    public LiveStreamerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.SOUTH)
                .setValue(PLAYING, false));
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
