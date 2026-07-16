package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.item.MediaManagementToolItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class VideoProjectorBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = EnumProperty.create("facing", Direction.class,
            Direction.UP, Direction.DOWN);
    public static final BooleanProperty ACTIVATED = BooleanProperty.create("activated");
    private static final MapCodec<VideoProjectorBlock> CODEC = simpleCodec(VideoProjectorBlock::new);
    private static final VoxelShape SHAPE = Block.box(2.75, 0, 2.75, 13.25, 5.3, 13.25);

    public VideoProjectorBlock(Identifier id) {
        this(BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, id))
                .sound(SoundType.METAL)
                .strength(2.0F)
                .lightLevel(state -> state.getValue(ACTIVATED) ? 15 : 0)
                .noOcclusion());
    }

    public VideoProjectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.UP)
                .setValue(ACTIVATED, false));
    }

    @Override
    protected MapCodec<VideoProjectorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVATED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, Direction.UP);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VideoProjectorBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, net.minecraft.core.BlockPos pos,
            CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()) {
            applyLinkedPosition(level, pos, stack, placer);
        }
    }

    /** 从物品 NBT 读取远程连接目标并写入视频投影仪方块实体 */
    private static void applyLinkedPosition(Level level, BlockPos pos, ItemStack stack, LivingEntity placer) {
        BlockPos linkedPos = LinkHelper.readLinkFromItem(stack);
        if (linkedPos == null) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof VideoProjectorBlockEntity projector) {
            projector.linkTo(linkedPos);
            if (!(placer instanceof Player player) || !player.isCreative()) {
                LinkHelper.clearLinkFromItem(stack);
                clearLinkedBlockEntityData(stack);
            }
        }
    }

    /**
     * 同步写入标准方块实体组件。MinecartRevolution 等通用方块载具会复制该组件，
     * 因而无需依赖本模组的物品 CUSTOM_DATA 即可保留唱片机链接。
     */
    public static void writeLinkedBlockEntityData(ItemStack stack, BlockPos linkedPos) {
        TypedEntityData<?> existing = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        CompoundTag tag = existing != null ? existing.copyTagWithoutId() : new CompoundTag();
        tag.putBoolean("LinkedTarget_has", true);
        tag.putInt("LinkedTarget_x", linkedPos.getX());
        tag.putInt("LinkedTarget_y", linkedPos.getY());
        tag.putInt("LinkedTarget_z", linkedPos.getZ());
        stack.set(DataComponents.BLOCK_ENTITY_DATA,
                TypedEntityData.of(com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities.VIDEO_PROJECTOR.get(),
                        tag));
    }

    /** 清除标准方块实体组件中的唱片机链接，同时保留投影参数等其它数据。 */
    public static void clearLinkedBlockEntityData(ItemStack stack) {
        TypedEntityData<?> existing = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (existing == null) {
            return;
        }
        CompoundTag tag = existing.copyTagWithoutId();
        tag.remove("LinkedTarget_has");
        tag.remove("LinkedTarget_x");
        tag.remove("LinkedTarget_y");
        tag.remove("LinkedTarget_z");
        if (tag.isEmpty()) {
            stack.remove(DataComponents.BLOCK_ENTITY_DATA);
            return;
        }
        stack.set(DataComponents.BLOCK_ENTITY_DATA,
                TypedEntityData.of(com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities.VIDEO_PROJECTOR.get(),
                        tag));
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (hand == InteractionHand.MAIN_HAND && stack.getItem() instanceof MediaManagementToolItem) {
            return InteractionResult.PASS;
        }
        if (!player.mayBuild()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            openClientScreen(pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!player.mayBuild()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            openClientScreen(pos);
        }
        return InteractionResult.SUCCESS;
    }

    private static void openClientScreen(BlockPos pos) {
        com.zhongbai233.net_music_can_play_bili.client.VideoProjectorClient.openScreen(pos);
    }
}
