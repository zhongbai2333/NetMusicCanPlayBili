package com.zhongbai233.net_music_can_play_bili.client.pad;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** Central JVM property boundary for Pad map layout, sampling, and cache scheduling. */
final class PadMapProperties {
    static final String VIEW_WIDTH = "ncpb.pad.map_view_width";
    static final String LEGACY_SIZE = "ncpb.pad.map_size";
    static final String VIEW_HEIGHT = "ncpb.pad.map_view_height";
    static final String OVERSCAN = "ncpb.pad.map_overscan";
    static final String WIDTH = "ncpb.pad.map_width";
    static final String HEIGHT = "ncpb.pad.map_height";
    static final String CELL_SAMPLES = "ncpb.pad.map_cell_samples";

    static final String RESAMPLE_CHUNKS = "ncpb.pad.map_resample_chunks";
    static final String CHUNKS_PER_TICK = "ncpb.pad.map_chunks_per_tick";
    static final String FAST_CHUNKS_PER_TICK = "ncpb.pad.map_fast_chunks_per_tick";
    static final String CELLS_PER_CHUNK_BUDGET = "ncpb.pad.map_cells_per_chunk_budget";
    static final String INITIAL_VISIBLE_BURST_CELLS = "ncpb.pad.map_initial_burst_cells";
    static final String MAX_JOB_LAG_CHUNKS = "ncpb.pad.map_max_job_lag_chunks";
    static final String DIRTY_CHUNKS_PER_TICK = "ncpb.pad.map_dirty_chunks_per_tick";
    static final String UPDATE_INTERVAL_TICKS = "ncpb.pad.map_update_interval_ticks";
    static final String UNKNOWN_RETRY_TICKS = "ncpb.pad.map_unknown_retry_ticks";
    static final String RECENTER_BLOCKS = "ncpb.pad.map_recenter_blocks";
    static final String INDOOR_RECENTER_BLOCKS = "ncpb.pad.map_indoor_recenter_blocks";
    static final String INDOOR_CEILING_SCAN_BLOCKS = "ncpb.pad.map_indoor_ceiling_scan_blocks";
    static final String INDOOR_CEILING_MIN_HITS = "ncpb.pad.map_indoor_ceiling_min_hits";
    static final String INDOOR_ARTIFICIAL_MIN_HITS = "ncpb.pad.map_indoor_artificial_min_hits";
    static final String INDOOR_ENTER_CONFIRM_TICKS = "ncpb.pad.map_indoor_enter_confirm_ticks";
    static final String INDOOR_EXIT_CONFIRM_TICKS = "ncpb.pad.map_indoor_exit_confirm_ticks";
    static final String INDOOR_FLOOR_CONFIRM_TICKS = "ncpb.pad.map_indoor_floor_confirm_ticks";
    static final String INDOOR_JUMP_TOLERANCE_BLOCKS = "ncpb.pad.map_indoor_jump_tolerance_blocks";
    static final String OUTDOOR_ZOOM = "ncpb.pad.map_outdoor_zoom";
    static final String INDOOR_ZOOM = "ncpb.pad.map_indoor_zoom";
    static final String INDOOR_DISPLAY_SCALE = "ncpb.pad.map_indoor_display_scale";
    static final String PREVIEW_CHUNKS = "ncpb.pad.map_preview_chunks";
    static final String CELL_CACHE_LIMIT = "ncpb.pad.map_cell_cache_limit";
    static final String DIRTY_CHUNK_LIMIT = "ncpb.pad.map_dirty_chunk_limit";
    static final String DISK_FLUSH_TICKS = "ncpb.pad.map_disk_flush_ticks";
    static final String DISK_CACHE = "ncpb.pad.map_disk_cache";

    private PadMapProperties() {
    }

    static Layout layout() {
        int viewWidth = NcpbSystemProperties.intValue(VIEW_WIDTH, LEGACY_SIZE, 384);
        int viewHeight = NcpbSystemProperties.intValue(VIEW_HEIGHT, 192);
        int overscan = NcpbSystemProperties.intValue(OVERSCAN, 96);
        int width = NcpbSystemProperties.intValue(WIDTH, viewWidth + overscan * 2);
        int height = NcpbSystemProperties.intValue(HEIGHT, viewHeight + overscan * 2);
        int cellSamples = NcpbSystemProperties.intValue(CELL_SAMPLES, 5);
        return new Layout(viewWidth, viewHeight, overscan, width, height, cellSamples);
    }

    static Cache cache() {
        return new Cache(
                NcpbSystemProperties.intValue(RESAMPLE_CHUNKS, 2),
                NcpbSystemProperties.intValue(CHUNKS_PER_TICK, 24),
                NcpbSystemProperties.intValue(FAST_CHUNKS_PER_TICK, 64),
                NcpbSystemProperties.intValue(CELLS_PER_CHUNK_BUDGET, 32),
                NcpbSystemProperties.intValue(INITIAL_VISIBLE_BURST_CELLS, 8192),
                NcpbSystemProperties.intValue(MAX_JOB_LAG_CHUNKS, 3),
                Math.max(1, NcpbSystemProperties.intValue(DIRTY_CHUNKS_PER_TICK, 4)),
                Math.max(1, NcpbSystemProperties.intValue(UPDATE_INTERVAL_TICKS, 1)),
                Math.max(20, NcpbSystemProperties.intValue(UNKNOWN_RETRY_TICKS, 40)),
                NcpbSystemProperties.intValue(RECENTER_BLOCKS, 16),
                NcpbSystemProperties.intValue(INDOOR_RECENTER_BLOCKS, 8),
                NcpbSystemProperties.intValue(INDOOR_CEILING_SCAN_BLOCKS, 96),
                NcpbSystemProperties.intValue(INDOOR_CEILING_MIN_HITS, 5),
                NcpbSystemProperties.intValue(INDOOR_ARTIFICIAL_MIN_HITS, 5),
                NcpbSystemProperties.intValue(INDOOR_ENTER_CONFIRM_TICKS, 2),
                NcpbSystemProperties.intValue(INDOOR_EXIT_CONFIRM_TICKS, 40),
                NcpbSystemProperties.intValue(INDOOR_FLOOR_CONFIRM_TICKS, 4),
                NcpbSystemProperties.intValue(INDOOR_JUMP_TOLERANCE_BLOCKS, 2),
                NcpbSystemProperties.floatValue(OUTDOOR_ZOOM, 1.25F),
                NcpbSystemProperties.floatValue(INDOOR_ZOOM, 3.0F),
                NcpbSystemProperties.floatValue(INDOOR_DISPLAY_SCALE, 2.0F),
                NcpbSystemProperties.intValue(PREVIEW_CHUNKS, 1),
                NcpbSystemProperties.intValue(CELL_CACHE_LIMIT, 524288),
                NcpbSystemProperties.intValue(DIRTY_CHUNK_LIMIT, 8192),
                NcpbSystemProperties.intValue(DISK_FLUSH_TICKS, 200),
                NcpbSystemProperties.booleanValue(DISK_CACHE, true));
    }

    record Layout(int viewWidth, int viewHeight, int overscan, int width, int height, int cellSamples) {
    }

    record Cache(int resampleChunkDistance, int chunksPerTick, int fastChunksPerTick, int cellsPerChunkBudget,
            int initialVisibleBurstCells, int maxJobLagChunks, int dirtyChunksPerTick, int updateIntervalTicks,
            int unknownRetryTicks, int recenterBlocks, int indoorRecenterBlocks, int indoorCeilingScanBlocks,
            int indoorCeilingMinHits, int indoorArtificialMinHits, int indoorEnterConfirmTicks,
            int indoorExitConfirmTicks, int indoorFloorConfirmTicks, int indoorJumpToleranceBlocks,
            float outdoorZoom, float indoorZoom, float indoorDisplayScale, int previewChunks, int cellCacheLimit,
            int dirtyChunkLimit, int diskFlushTicks, boolean diskCacheEnabled) {
    }
}
