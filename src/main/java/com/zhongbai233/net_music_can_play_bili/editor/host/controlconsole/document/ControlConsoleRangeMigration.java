package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

/** 将历史文档中曾经写入存档的旧默认 hardRange 迁移到当前产品默认值。 */
public final class ControlConsoleRangeMigration {
    private static final int LAST_SCHEMA_WITH_LEGACY_DEFAULT = 4;
    private static final double LEGACY_DEFAULT_X = 8.0D;
    private static final double LEGACY_DEFAULT_Y = 4.0D;
    private static final double LEGACY_DEFAULT_Z = 8.0D;

    private ControlConsoleRangeMigration() {
    }

    public static Range migrate(int sourceSchemaVersion, double x, double y, double z) {
        if (sourceSchemaVersion <= LAST_SCHEMA_WITH_LEGACY_DEFAULT
                && Double.compare(x, LEGACY_DEFAULT_X) == 0
                && Double.compare(y, LEGACY_DEFAULT_Y) == 0
                && Double.compare(z, LEGACY_DEFAULT_Z) == 0) {
            return new Range(ControlConsoleDocument.DEFAULT_HARD_RANGE_X,
                    ControlConsoleDocument.DEFAULT_HARD_RANGE_Y,
                    ControlConsoleDocument.DEFAULT_HARD_RANGE_Z);
        }
        return new Range(x, y, z);
    }

    public record Range(double x, double y, double z) {
    }
}