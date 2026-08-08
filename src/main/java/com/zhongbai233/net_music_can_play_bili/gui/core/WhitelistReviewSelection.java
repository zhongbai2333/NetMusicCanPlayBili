package com.zhongbai233.net_music_can_play_bili.gui.core;

import java.util.List;

/** 白名单审核列表与预览页共享的稳定条目选择规则。 */
public final class WhitelistReviewSelection {
    private WhitelistReviewSelection() {
    }

    public static int indexOf(List<String> ids, String selectedId) {
        if (ids == null || selectedId == null || selectedId.isBlank()) {
            return -1;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (selectedId.equals(ids.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static boolean matchesPreview(String entryId, String rawUrl) {
        String id = normalized(entryId);
        String raw = normalized(rawUrl);
        if (id.isEmpty() || raw.isEmpty()) {
            return false;
        }
        if (id.equals(raw)) {
            return true;
        }
        if (id.regionMatches(true, 0, "url:", 0, 4)) {
            return id.substring(4).equals(raw);
        }
        if (id.regionMatches(true, 0, "bili:", 0, 5)) {
            String biliId = id.substring(5);
            return raw.equals(biliId) || raw.startsWith(biliId + "|p=");
        }
        return false;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}