package com.zhongbai233.net_music_can_play_bili.client.pad;

public enum PadMapTileKind {
    UNKNOWN(0xFF8798A6, "等待采样"),
    GRASS(0xFFE6E0D5, "地面"),
    INDOOR_FLOOR(0xFFE9E1D3, "室内"),
    BUILDING(0xFFDAD5CF, "建筑"),
    WATER(0xFF9DBDCE, "水域"),
    TREE(0xFFBCD3B2, "林地"),
    FARMLAND(0xFFE0D5B7, "农田"),
    ROCK(0xFFD0CDC6, "岩地"),
    SNOW(0xFFE8E5DE, "雪地");

    private final int color;
    private final String label;

    PadMapTileKind(int color, String label) {
        this.color = color;
        this.label = label;
    }

    public int color() {
        return color;
    }

    public String label() {
        return label;
    }
}