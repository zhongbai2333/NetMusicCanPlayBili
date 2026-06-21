package com.zhongbai233.net_music_can_play_bili.network;

import net.minecraft.resources.Identifier;

/** 网络 payload 专用短 namespace，降低自定义包 ID 在网络中的重复开销。 */
final class NetworkPayloadIds {
    static final String NAMESPACE = "ncpb";

    private NetworkPayloadIds() {
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }
}
