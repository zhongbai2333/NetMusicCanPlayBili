package com.zhongbai233.net_music_can_play_bili.block;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.phys.shapes.VoxelShape;

public class ModernTurntableBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final BooleanProperty HAS_DISC = BooleanProperty.create("has_disc");
    public static final BooleanProperty PLAYING = BooleanProperty.create("playing");
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 8, 15);
    private static final MapCodec<ModernTurntableBlock> CODEC = simpleCodec(ModernTurntableBlock::new);

    public ModernTurntableBlock(Identifier id) {
        this(BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, id))
                .sound(SoundType.WOOD)
                .strength(1.5F)
                .noOcclusion());
    }

    public ModernTurntableBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.SOUTH)
                .setValue(HAS_DISC, false)
                .setValue(PLAYING, false));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ModernTurntableBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.MODERN_TURNTABLE.get()) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> ModernTurntableBlockEntity.tick(
                tickLevel, pos, tickState, (ModernTurntableBlockEntity) blockEntity);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (hand == InteractionHand.OFF_HAND) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                openClientScreen(pos);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.SUCCESS;
        }
        // 手持链接物品右键 → 存储连接目标到物品 NBT
        if (stack.getItem() == com.zhongbai233.net_music_can_play_bili.init.ModItems.LYRIC_PROJECTOR.get()) {
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            LinkHelper.writeLinkToItem(stack, pos);
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.lyric_projector.item_linked",
                    pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GOLD));
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        if (turntable.hasDisc()) {
            ejectDisc(level, pos, turntable);
            return InteractionResult.SUCCESS;
        }

        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(stack);
        if (songInfo == null) {
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.modern_turntable.need_cd").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        turntable.setDisc(stack.copyWithCount(1));
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        updateState(level, pos, turntable);
        turntable.startFromDisc(serverPlayer);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                openClientScreen(pos);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static void openClientScreen(BlockPos pos) {
        try {
            Class<?> client = Class.forName("com.zhongbai233.net_music_can_play_bili.client.ModernTurntableClient");
            client.getMethod("openScreen", BlockPos.class).invoke(null, pos);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to open modern turntable screen", e);
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity,
            ItemStack tool) {
        if (!level.isClientSide() && blockEntity instanceof ModernTurntableBlockEntity turntable
                && turntable.hasDisc()) {
            popResource(level, pos, turntable.removeDisc());
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    private static void ejectDisc(Level level, BlockPos pos, ModernTurntableBlockEntity turntable) {
        ItemStack removed = turntable.removeDisc();
        if (!removed.isEmpty()) {
            popResource(level, pos, removed);
        }
        updateState(level, pos, turntable);
    }

    private static void updateState(Level level, BlockPos pos, ModernTurntableBlockEntity turntable) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ModernTurntableBlock) {
            level.setBlock(pos, state
                    .setValue(HAS_DISC, turntable.hasDisc())
                    .setValue(PLAYING, turntable.isPlaying()), 3);
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HAS_DISC, PLAYING);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
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
