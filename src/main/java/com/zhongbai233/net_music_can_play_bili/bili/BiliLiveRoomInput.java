package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.Locale;

/**
 * 直播间号的输入归一化与占位地址编解码。
 *
 * <p>
 * NetMusic 服务端只接受 m3u8 形式的广播地址，所以直播间号要先包装成
 * {@code http://live/<直播间号>.m3u8} 才能通过校验并同步给客户端；真实地址由客户端解析。
 * </p>
 */
public final class BiliLiveRoomInput {
    private static final String LIVE_PREFIX = "live:";
    private static final String LIVE_HOST = "live.bilibili.com";
    private static final String PLACEHOLDER_PREFIX = "http://live/";
    private static final String PLACEHOLDER_SUFFIX = ".m3u8";

    private BiliLiveRoomInput() {
    }

    /** 把直播间号包装成 NetMusic 广播喇叭可接受的占位地址。 */
    public static String placeholderUrl(String roomId) {
        return PLACEHOLDER_PREFIX + roomId + PLACEHOLDER_SUFFIX;
    }

    /** @return 占位地址中的直播间号；不是本模组的直播占位地址时返回空串 */
    public static String roomIdFromPlaceholder(String url) {
        if (url == null || !url.startsWith(PLACEHOLDER_PREFIX) || !url.endsWith(PLACEHOLDER_SUFFIX)) {
            return "";
        }
        String roomId = url.substring(PLACEHOLDER_PREFIX.length(), url.length() - PLACEHOLDER_SUFFIX.length());
        return BiliLiveStreamResolver.isValidRoomId(roomId) ? roomId : "";
    }

    /**
     * 只识别显式直播输入：{@code live:直播间号} 或 live.bilibili.com 链接。
     *
     * <p>
     * 与 {@link #parseRoomId} 不同，纯数字不会被当成直播间——白名单等共享入口
     * 不能把任意数字串归类为直播资源。
     * </p>
     *
     * @return 直播间号；输入不是显式直播形式时返回空串
     */
    public static String parseExplicitRoomId(String input) {
        if (input == null) {
            return "";
        }
        String text = input.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith(LIVE_PREFIX)) {
            String candidate = text.substring(LIVE_PREFIX.length()).trim();
            return BiliLiveStreamResolver.isValidRoomId(candidate) ? candidate : "";
        }
        return parseRoomUrl(text);
    }

    /** @return 直播间号；输入不是直播间时返回空串 */
    public static String parseRoomId(String input) {
        if (input == null) {
            return "";
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return "";
        }

        String candidate = text.toLowerCase(Locale.ROOT).startsWith(LIVE_PREFIX)
                ? text.substring(LIVE_PREFIX.length()).trim()
                : text;
        if (BiliLiveStreamResolver.isValidRoomId(candidate)) {
            return candidate;
        }
        return parseRoomUrl(candidate);
    }

    /** 支持直接粘贴 {@code https://live.bilibili.com/<直播间号>} 形式的链接。 */
    private static String parseRoomUrl(String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "";
        }
        int hostStart = lower.indexOf("//") + 2;
        int hostEnd = indexOfAny(lower, hostStart, '/', '?', '#');
        String host = lower.substring(hostStart, hostEnd);
        if (!host.equals(LIVE_HOST)) {
            return "";
        }

        String path = candidate.substring(hostEnd, indexOfAny(candidate, hostEnd, '?', '#'));
        for (String segment : path.split("/")) {
            String trimmed = segment.trim();
            if (BiliLiveStreamResolver.isValidRoomId(trimmed)) {
                return trimmed;
            }
        }
        return "";
    }

    private static int indexOfAny(String text, int fromIndex, char... chars) {
        for (int i = Math.max(0, fromIndex); i < text.length(); i++) {
            for (char c : chars) {
                if (text.charAt(i) == c) {
                    return i;
                }
            }
        }
        return text.length();
    }
}
