package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

/** Pure text policy for live title, room and status subtitle modes. */
public final class LiveSubtitleMetadata {
    public static final String TITLE_MODE = "LIVE_TITLE";
    public static final String ROOM_MODE = "LIVE_ROOM";
    public static final String STATUS_MODE = "LIVE_STATUS";
    public static final Metadata EMPTY = new Metadata("", "", "", "", "");

    private LiveSubtitleMetadata() {
    }

    public static boolean isLiveMode(String contentMode) {
        return TITLE_MODE.equals(contentMode) || ROOM_MODE.equals(contentMode) || STATUS_MODE.equals(contentMode);
    }

    public static Metadata resolve(String roomId, String title, String parentAreaName, String areaName,
            int apiLiveStatus, boolean playing, boolean waitingForLive) {
        String normalizedRoom = safe(roomId);
        String normalizedTitle = safe(title);
        String area = areaText(parentAreaName, areaName);
        String status;
        if (playing) {
            status = apiLiveStatus == 2 ? "轮播中" : "直播中";
        } else if (waitingForLive) {
            status = "等待开播";
        } else {
            status = "已停止";
        }
        return new Metadata(normalizedTitle.isEmpty() ? fallbackTitle(normalizedRoom) : normalizedTitle,
                normalizedRoom, area, status, roomText(normalizedRoom, area));
    }

    public static String text(String contentMode, Metadata metadata) {
        Metadata value = metadata != null ? metadata : EMPTY;
        return switch (contentMode) {
            case TITLE_MODE -> value.title();
            case ROOM_MODE -> value.roomText();
            case STATUS_MODE -> value.status();
            default -> "";
        };
    }

    private static String fallbackTitle(String roomId) {
        return roomId.isEmpty() ? "B站直播" : "B站直播 " + roomId;
    }

    private static String roomText(String roomId, String area) {
        String base = roomId.isEmpty() ? "直播间" : "房间 " + roomId;
        return area.isEmpty() ? base : base + " · " + area;
    }

    private static String areaText(String parentAreaName, String areaName) {
        String parent = safe(parentAreaName);
        String area = safe(areaName);
        if (parent.isEmpty()) {
            return area;
        }
        if (area.isEmpty() || parent.equals(area)) {
            return parent;
        }
        return parent + " / " + area;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record Metadata(String title, String roomId, String area, String status, String roomText) {
        public Metadata {
            title = safe(title);
            roomId = safe(roomId);
            area = safe(area);
            status = safe(status);
            roomText = safe(roomText);
        }
    }
}
