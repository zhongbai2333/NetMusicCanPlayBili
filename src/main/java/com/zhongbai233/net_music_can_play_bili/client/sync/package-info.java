/**
 * 客户端媒体同步公共层。
 *
 * <p>
 * 维护原则：MP4、Pad、白名单预览和唱片机/投影仪不应各自实现 B站解析、音视频时间线仲裁
 * 或手持屏幕元数据。设备差异放进 profile，公共消费者走 {@link HandheldMediaSystem}、
 * {@link com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver}
 * 和
 * {@link ClientMediaTimelineView}。
 * </p>
 */
package com.zhongbai233.net_music_can_play_bili.client.sync;
