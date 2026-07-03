package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * 开发期服务端自测：自动搭建真实方块结构并验证 Pad 地图采样分类。
 * <p>
 * 通过 -Dncpb.pad.map.server_self_test=true 启用；成功或失败后都会请求停服，方便 Gradle 自动跑。
 */
public final class PadMapSamplerServerSelfTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("ncpb.pad.map.server_self_test");
    private static boolean ran;

    private PadMapSamplerServerSelfTest() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        if (!ENABLED || ran) {
            return;
        }
        ran = true;
        MinecraftServer server = event.getServer();
        try {
            run(server.overworld());
            LOGGER.info("PadMapSamplerServerSelfTest passed");
        } catch (RuntimeException ex) {
            LOGGER.error("PadMapSamplerServerSelfTest failed", ex);
            Runtime.getRuntime().halt(1);
            throw ex;
        } finally {
            server.halt(false);
        }
    }

    private static void run(ServerLevel level) {
        int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 0, 0);
        BlockPos origin = new BlockPos(0, Math.max(surfaceY + 16, 160), 0);
        clearBox(level, origin.offset(-2, -2, -2), origin.offset(86, 18, 24));
        fillGround(level, origin.offset(-2, -2, -2), origin.offset(86, 0, 24));
        flatQuartzRoofCenterIsBuilding(level, origin.offset(0, 0, 0));
        concretePlazaIsNotBuilding(level, origin.offset(10, 0, 0));
        stairRoofIsBuilding(level, origin.offset(20, 0, 0));
        hollowCourtyardRoofIsBuilding(level, origin.offset(30, 0, 0));
        largeFlatRoofCenterIsBuilding(level, origin.offset(48, 0, 0));
        whiteFacadeRoofAndAwningAreBuilding(level, origin.offset(70, 0, 0));
        polishedDioriteCourtyardIsNotBuilding(level, origin.offset(70, 0, 12));
        polishedDioriteCourtyardWideSampleIsNotBuilding(level, origin.offset(70, 0, 20));
        interiorRoomFloorIsBuilding(level, origin.offset(0, 0, 8));
        tallInteriorRoomFloorIsBuilding(level, origin.offset(10, 0, 8));
        stoneBrickRoadIsGround(level, origin.offset(20, 0, 16));
    }

    private static void flatQuartzRoofCenterIsBuilding(ServerLevel level, BlockPos origin) {
        buildFlatRoofHouse(level, origin, Blocks.QUARTZ_BLOCK.defaultBlockState());
        assertKind(level, origin.offset(3, 0, 3), PadMapTileKind.BUILDING,
                "flat quartz roof center should be BUILDING");
    }

    private static void concretePlazaIsNotBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                level.setBlock(origin.offset(x, 1, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
            }
        }
        assertNotKind(level, origin.offset(3, 0, 3), PadMapTileKind.BUILDING,
                "flat concrete plaza should not be BUILDING");
    }

    private static void stairRoofIsBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                boolean wall = x == 1 || x == 5 || z == 1 || z == 5;
                if (wall) {
                    level.setBlock(origin.offset(x, 1, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
                    level.setBlock(origin.offset(x, 2, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
                }
                level.setBlock(origin.offset(x, 3, z), Blocks.BRICK_STAIRS.defaultBlockState(), 3);
            }
        }
        assertKind(level, origin.offset(3, 0, 3), PadMapTileKind.BUILDING,
                "stair roof over interior air should be BUILDING");
    }

    private static void hollowCourtyardRoofIsBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 9; z++) {
                boolean outerWall = x == 1 || x == 9 || z == 1 || z == 9;
                boolean courtyard = x >= 4 && x <= 6 && z >= 4 && z <= 6;
                if (outerWall) {
                    for (int y = 1; y <= 5; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.BRICKS.defaultBlockState(), 3);
                    }
                }
                if (!courtyard) {
                    level.setBlock(origin.offset(x, 6, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                }
            }
        }
        assertKind(level, origin.offset(2, 0, 4), PadMapTileKind.BUILDING,
                "hollow courtyard roof edge should be BUILDING");
        assertKind(level, origin.offset(4, 0, 2), PadMapTileKind.BUILDING,
                "hollow courtyard roof side should be BUILDING");
    }

    private static void largeFlatRoofCenterIsBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 17; x++) {
            for (int z = 1; z <= 17; z++) {
                boolean edge = x == 1 || x == 17 || z == 1 || z == 17;
                if (edge) {
                    for (int y = 1; y <= 5; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
                    }
                    level.setBlock(origin.offset(x, 7, z), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
                }
                level.setBlock(origin.offset(x, 6, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
            }
        }
        assertKind(level, origin.offset(9, 0, 9), PadMapTileKind.BUILDING,
                "large flat roof center should be BUILDING even far from edge supports");
    }

    private static void whiteFacadeRoofAndAwningAreBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 7; z++) {
                boolean outerWall = x == 1 || x == 13 || z == 1 || z == 7;
                if (outerWall) {
                    for (int y = 1; y <= 10; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
                    }
                }
                level.setBlock(origin.offset(x, 11, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
            }
        }

        for (int x = 3; x <= 11; x++) {
            level.setBlock(origin.offset(x, 7, 1), Blocks.GRAY_STAINED_GLASS_PANE.defaultBlockState(), 3);
            level.setBlock(origin.offset(x, 8, 1), Blocks.GRAY_STAINED_GLASS_PANE.defaultBlockState(), 3);
            level.setBlock(origin.offset(x, 6, 0), Blocks.IRON_TRAPDOOR.defaultBlockState(), 3);
        }
        for (int x = 2; x <= 12; x++) {
            level.setBlock(origin.offset(x, 12, 1), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
            level.setBlock(origin.offset(x, 12, 7), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
        }

        assertKind(level, origin.offset(7, 0, 4), PadMapTileKind.BUILDING,
                "white high-rise roof center should be BUILDING");
        assertKind(level, origin.offset(7, 0, 1), PadMapTileKind.BUILDING,
                "white facade roof edge above window band should be BUILDING");
        assertKind(level, origin.offset(7, 0, 0), PadMapTileKind.BUILDING,
                "white facade trapdoor awning should be BUILDING");
    }

    private static void polishedDioriteCourtyardIsNotBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 15; x++) {
            for (int z = 1; z <= 11; z++) {
                level.setBlock(origin.offset(x, 1, z), Blocks.POLISHED_DIORITE.defaultBlockState(), 3);
            }
        }
        for (int x = 1; x <= 15; x++) {
            level.setBlock(origin.offset(x, 2, 1), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
            level.setBlock(origin.offset(x, 2, 11), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
        }
        for (int z = 1; z <= 11; z++) {
            level.setBlock(origin.offset(1, 2, z), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
            level.setBlock(origin.offset(15, 2, z), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
        }
        assertNotKind(level, origin.offset(8, 0, 6), PadMapTileKind.BUILDING,
                "polished diorite courtyard floor should not be BUILDING");
    }

    private static void polishedDioriteCourtyardWideSampleIsNotBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 19; x++) {
            for (int z = 1; z <= 13; z++) {
                level.setBlock(origin.offset(x, 1, z), Blocks.POLISHED_DIORITE.defaultBlockState(), 3);
            }
        }
        for (int x = 1; x <= 19; x++) {
            level.setBlock(origin.offset(x, 2, 1), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
            level.setBlock(origin.offset(x, 2, 13), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
        }
        for (int z = 1; z <= 13; z++) {
            level.setBlock(origin.offset(1, 2, z), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
            level.setBlock(origin.offset(19, 2, z), Blocks.POLISHED_DIORITE_SLAB.defaultBlockState(), 3);
        }
        assertNotKind(level, origin.offset(10, 0, 7), PadMapTileKind.BUILDING,
                "wide polished diorite courtyard should not be BUILDING");
    }

    private static void interiorRoomFloorIsBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
                if (x == 1 || x == 5 || z == 1 || z == 5) {
                    level.setBlock(origin.offset(x, 1, z), Blocks.BRICKS.defaultBlockState(), 3);
                    level.setBlock(origin.offset(x, 2, z), Blocks.BRICKS.defaultBlockState(), 3);
                    level.setBlock(origin.offset(x, 3, z), Blocks.BRICKS.defaultBlockState(), 3);
                }
                level.setBlock(origin.offset(x, 4, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            }
        }
        assertInteriorKind(level, origin.offset(3, 1, 3), PadMapTileKind.INDOOR_FLOOR,
                "interior room floor should be INDOOR_FLOOR");
        assertInteriorKind(level, origin.offset(1, 1, 3), PadMapTileKind.BUILDING,
                "interior room wall should be BUILDING");
    }

    private static void tallInteriorRoomFloorIsBuilding(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 7; x++) {
            for (int z = 1; z <= 7; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
                if (x == 1 || x == 7 || z == 1 || z == 7) {
                    for (int y = 1; y <= 10; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.BRICKS.defaultBlockState(), 3);
                    }
                }
                level.setBlock(origin.offset(x, 11, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            }
        }
        assertInteriorKind(level, origin.offset(4, 1, 4), PadMapTileKind.INDOOR_FLOOR,
                "tall interior room floor should be INDOOR_FLOOR");
        assertInteriorKind(level, origin.offset(1, 1, 4), PadMapTileKind.BUILDING,
                "tall interior room wall should be BUILDING");
    }

    private static void buildFlatRoofHouse(ServerLevel level, BlockPos origin, BlockState roof) {
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                if (x == 1 || x == 5 || z == 1 || z == 5) {
                    level.setBlock(origin.offset(x, 1, z), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
                    level.setBlock(origin.offset(x, 2, z), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
                }
                level.setBlock(origin.offset(x, 3, z), roof, 3);
            }
        }
    }

    /**
     * 石砖 + 安山岩混铺、贴地、两侧草地同高、路边有路灯的典型道路：应识别为普通地面而非建筑。
     */
    private static void stoneBrickRoadIsGround(ServerLevel level, BlockPos origin) {
        for (int x = 1; x <= 9; x++) {
            for (int z = 0; z <= 4; z++) {
                if (z == 0 || z == 4) {
                    level.setBlock(origin.offset(x, 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                } else {
                    level.setBlock(origin.offset(x, 1, z), (x + z) % 3 == 0
                            ? Blocks.ANDESITE.defaultBlockState()
                            : Blocks.STONE_BRICKS.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(origin.offset(5, 2, 0), Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(origin.offset(5, 3, 0), Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(origin.offset(5, 4, 0), Blocks.LANTERN.defaultBlockState(), 3);
        assertKind(level, origin.offset(8, 0, 2), PadMapTileKind.GRASS,
                "paved stone brick road should be plain ground");
        assertNotKind(level, origin.offset(4, 0, 1), PadMapTileKind.BUILDING,
                "road beside street lamp should not be BUILDING");
    }

    private static void assertKind(ServerLevel level, BlockPos pos, PadMapTileKind expected, String message) {
        PadMapTileKind actual = classify(level, pos);
        if (actual != expected) {
            throw new IllegalStateException(message + ", actual=" + actual + ", pos=" + pos);
        }
    }

    private static void assertNotKind(ServerLevel level, BlockPos pos, PadMapTileKind rejected, String message) {
        PadMapTileKind actual = classify(level, pos);
        if (actual == rejected) {
            throw new IllegalStateException(message + ", actual=" + actual + ", pos=" + pos);
        }
    }

    private static void assertInteriorKind(ServerLevel level, BlockPos pos, PadMapTileKind expected,
            String message) {
        PadMapTileKind actual = PadMapSampler.classifyInteriorCell(level, new BlockPos.MutableBlockPos(),
                pos.getX(), pos.getZ(), pos.getY(), 1);
        if (actual != expected) {
            throw new IllegalStateException(message + ", actual=" + actual + ", pos=" + pos);
        }
    }

    private static PadMapTileKind classify(ServerLevel level, BlockPos basePos) {
        return PadMapSampler.classifyCell(level, new BlockPos.MutableBlockPos(), basePos.getX(), basePos.getZ(), 1);
    }

    private static void clearBox(ServerLevel level, BlockPos from, BlockPos to) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int z = from.getZ(); z <= to.getZ(); z++) {
                level.setBlock(new BlockPos(x, from.getY() - 1, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    /** 为测试场景铺设真实地基：从 from.y 到 to.y 填充泥土，模拟自然地表。 */
    private static void fillGround(ServerLevel level, BlockPos from, BlockPos to) {
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int z = from.getZ(); z <= to.getZ(); z++) {
                for (int y = from.getY(); y <= to.getY(); y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 3);
                }
            }
        }
    }
}
