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

    /**
     * Reconciles selection after a refreshed page arrives. If the selected row was deleted,
     * the row that shifted into its old position wins; deleting the last row selects its predecessor.
     * The previous scroll position is preserved whenever the resulting selection remains visible.
     */
    public static RefreshState resolveAfterRefresh(List<String> ids, String selectedId,
            int previousSelectedIndex, int previousScrollOffset, int visibleRows) {
        int size = ids == null ? 0 : ids.size();
        if (size == 0) {
            return new RefreshState(-1, 0);
        }

        int selectedIndex = indexOf(ids, selectedId);
        if (selectedIndex < 0) {
            int adjacentIndex = previousSelectedIndex >= 0 ? previousSelectedIndex : previousScrollOffset;
            selectedIndex = clamp(adjacentIndex, 0, size - 1);
        }

        int safeVisibleRows = Math.max(1, visibleRows);
        int scrollOffset = clamp(previousScrollOffset, 0, Math.max(0, size - safeVisibleRows));
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + safeVisibleRows) {
            scrollOffset = Math.max(0, selectedIndex - safeVisibleRows + 1);
        }
        return new RefreshState(selectedIndex, scrollOffset);
    }

    /** Returns the next row after a removal, falling back to the previous row at the end of a page. */
    public static String adjacentIdAfterRemoval(List<String> ids, String removedId) {
        int removedIndex = indexOf(ids, removedId);
        if (removedIndex < 0) {
            return "";
        }
        int adjacentIndex = removedIndex + 1 < ids.size() ? removedIndex + 1 : removedIndex - 1;
        if (adjacentIndex < 0) {
            return "";
        }
        String adjacentId = ids.get(adjacentIndex);
        return adjacentId == null ? "" : adjacentId;
    }

    /** Keeps a requested page valid after a mutation reduces the total number of rows. */
    public static int clampPageOffset(int requestedOffset, int totalRows, int pageSize) {
        if (totalRows <= 0 || pageSize <= 0) {
            return 0;
        }
        int lastPageOffset = ((totalRows - 1) / pageSize) * pageSize;
        return Math.min(lastPageOffset, Math.max(0, requestedOffset));
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

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record RefreshState(int selectedIndex, int scrollOffset) {
    }
}
