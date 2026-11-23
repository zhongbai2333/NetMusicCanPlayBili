package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/** 中控台方块；首版只提供放置、文档宿主和编辑入口，不启动媒体运行时。 */
public final class ControlConsoleBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<ControlConsoleBlock> CODEC = simpleCodec(ControlConsoleBlock::new);

    public enum Section implements StringRepresentable {
        SINGLE("single"), BASE("base"), TOP("top");
        private final String name;

        Section(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Section> SECTION = EnumProperty.create("section", Section.class);
    private static final VoxelShape CABINET = Shapes.or(
            Block.box(1.9, 0, 2.6, 14.1, .85, 14.3),
            Block.box(1.28, .85, 2.94, 14.72, 14.22, 14.92),
            Block.box(1.18, .85, 1.93, 2.21, 14.2, 3.02),
            Block.box(13.79, .85, 1.93, 14.82, 14.2, 3.02),
            Block.box(2.24, 1.75, 2.18, 13.76, 5.80, 3.07),
            Block.box(2.24, 6.58, 2.18, 13.76, 10.50, 3.07),
            Block.box(2.24, 11.10, 2.20, 13.76, 13.34, 3.05));
    private static final VoxelShape MIXER_REAR = Shapes.or(
            Block.box(1.75, 14.21, 8.7651, 14.25, 18.7239, 14.72),
            Block.box(0.76, 14.22, 8.7958, 1.75, 18.9185, 14.76),
            Block.box(14.25, 14.22, 8.7958, 15.24, 18.9185, 14.76),
            Block.box(9.55, 18.7239, 11.76, 10.95, 19.0939, 13.16),
            Block.box(11.86, 18.7239, 11.76, 13.26, 19.0939, 13.16));
    private static final VoxelShape WORKSTATION = Shapes.or(CABINET, MIXER_REAR,
            Block.box(1.60, 14.19, 6.97, 14.40, 15.92, 10.35),
            Block.box(.76, 14.04, .30, 15.24, 17.03, 2.6),
            Block.box(.76, 14.52, 2.6, 15.24, 17.76, 5.0),
            Block.box(.76, 15.14, 5, 15.24, 18.45, 7.3),
            Block.box(.76, 15.76, 7.3, 15.24, 18.92, 9.53));
    private static final Map<Direction, VoxelShape> LOWER = Shapes.rotateHorizontal(
            Shapes.join(WORKSTATION, Shapes.block(), BooleanOp.AND));
    private static final Map<Direction, VoxelShape> UPPER = Shapes.rotateHorizontal(
            Shapes.join(WORKSTATION.move(0, -1, 0), Shapes.block(), BooleanOp.AND));

    public ControlConsoleBlock(Identifier id) {
        this(BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, id))
                .strength(2.5F)
                .pushReaction(PushReaction.BLOCK)
                .noOcclusion());
    }

    public ControlConsoleBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SECTION, Section.SINGLE));
    }

    @Override
    protected MapCodec<ControlConsoleBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(SECTION) == Section.TOP ? null : new ControlConsoleBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (pos.getY() >= level.getMaxY() || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(SECTION, Section.BASE);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SECTION);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction side, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
        Section part = state.getValue(SECTION);
        if (part == Section.TOP && side == Direction.DOWN) {
            return neighbor.is(this) && neighbor.getValue(SECTION) == Section.BASE
                    ? state.setValue(FACING, neighbor.getValue(FACING)) : Blocks.AIR.defaultBlockState();
        }
        if (part == Section.BASE && side == Direction.UP && !(neighbor.is(this) && neighbor.getValue(SECTION) == Section.TOP)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (part == Section.SINGLE && side == Direction.UP && neighbor.isAir()) {
            ticks.scheduleTick(pos, this, 1);
        }
        return super.updateShape(state, level, ticks, pos, side, neighborPos, neighbor, random);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(SECTION) == Section.SINGLE && pos.getY() < level.getMaxY() && level.isEmptyBlock(pos.above())) {
            BlockState base = state.setValue(SECTION, Section.BASE);
            level.setBlock(pos, base, Block.UPDATE_CLIENTS);
            level.setBlock(pos.above(), base.setValue(SECTION, Section.TOP), Block.UPDATE_ALL);
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && state.getValue(SECTION) == Section.TOP
                && (player.preventsBlockDrops() || !player.hasCorrectToolForDrops(state, level, pos))) {
            BlockState base = level.getBlockState(pos.below());
            if (base.is(this) && base.getValue(SECTION) == Section.BASE) {
                level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(SECTION) == Section.BASE) {
            level.setBlock(pos.above(), state.setValue(SECTION, Section.TOP), Block.UPDATE_ALL);
        }
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
        String currentDimension = level.dimension().identifier().toString();
        boolean correctDimension = link.legacy() || currentDimension.equals(link.dimension());
        BlockEntity source = correctDimension
                && level.hasChunk(Math.floorDiv(sourcePos.getX(), 16), Math.floorDiv(sourcePos.getZ(), 16))
                ? level.getBlockEntity(sourcePos) : null;
        boolean validSource = link.legacy()
            ? source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity
                || source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity
            : link.sourceKind() == com.zhongbai233.net_music_can_play_bili.link.LinkHelper.ControlConsoleSourceKind.TURNTABLE
                ? source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity
                : source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
        if (correctDimension && validSource
            && level.getBlockEntity(pos) instanceof ControlConsoleBlockEntity console) {
            console.linkTo(currentDimension, sourcePos,
                source instanceof com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity
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
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return (state.getValue(SECTION) == Section.TOP ? UPPER : LOWER).get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (state.getValue(SECTION) == Section.TOP) {
            pos = pos.below();
        }
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
