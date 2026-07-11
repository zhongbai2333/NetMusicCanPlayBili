package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Shared binary format constants/helpers for Pad map disk cache files. */
final class PadMapDiskCacheFormat {
    // v18 淘汰可能在客户端列尚未同步时被错误持久化为 GRASS 的旧采样。
    static final Header CELLS = new Header(0x4E504D43, 18);
    static final Header SNAPSHOT = new Header(0x4E504D53, 18);
    static final Header CHUNKS = new Header(0x4E504B43, 18);

    private PadMapDiskCacheFormat() {
    }

    static int encodeTile(PadMapTileKind tile) {
        return tile == null ? PadMapTileKind.UNKNOWN.ordinal() : tile.ordinal();
    }

    static PadMapTileKind decodeTile(int ordinal) {
        PadMapTileKind[] values = PadMapTileKind.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    record Header(int magic, int version) {
        boolean matches(int magic, int version) {
            return this.magic == magic && this.version == version;
        }
    }
}
