# NetMusicCanPlayBili 网络包全景图

> 当前协议：NeoForge payload registrar `1`，短命名空间 `ncpb`。共 **28 个本地 payload**（17 C→S、11 S→C），另有上游 NetMusic 的刻录/播放兼容消息链。

```mermaid
flowchart LR
    classDef client fill:#102a43,stroke:#4dabf7,color:#fff,stroke-width:1.5px
    classDef c2s fill:#3b1d5a,stroke:#c77dff,color:#fff,stroke-width:1.5px
    classDef gate fill:#5c3a00,stroke:#ffd166,color:#fff,stroke-width:2px
    classDef server fill:#123524,stroke:#51cf66,color:#fff,stroke-width:1.5px
    classDef s2c fill:#5a1f2b,stroke:#ff8787,color:#fff,stroke-width:1.5px
    classDef upstream fill:#263238,stroke:#90a4ae,color:#fff,stroke-width:1.5px
    classDef note fill:#fff3bf,stroke:#f59f00,color:#352900,stroke-dasharray: 5 5

    subgraph CLIENT["① 客户端：UI、Tick 与自动策略"]
        direction TB
        C_CFG["方块/装备 UI<br/>唱片机 · 歌词投影 · 音箱<br/>视频投影 · 全息眼镜"]:::client
        C_TOOL["媒体管理工具 UI<br/>绑定 · 清除 · 举报"]:::client
        C_WL["白名单管理 UI<br/>刷新 · 删除 · 导出 · 预览/拖动"]:::client
        C_MP4["MP4 客户端<br/>状态脏合并 · 控制 · 自动恢复<br/>失败重试 · 队列完成"]:::client
        C_PAD["Pad 客户端<br/>10t 文档合并 · 发布 · 播放控制"]:::client
        C_CD["上游刻录 UI Mixin<br/>异步解析 B 站信息"]:::upstream
    end

    subgraph C2S["② C → S：17 个本地请求包"]
        direction TB
        P_CFG["配置/控制（6）<br/>ModernTurntableControlPacket<br/>LyricProjectorConfigPacket<br/>SpeakerConfigPacket<br/>VideoProjectorConfigPacket<br/>HolographicGlassesConfigPacket<br/>ClearEquippedBindingPacket *"]:::c2s
        P_TOOL["媒体工具（3）<br/>MediaToolConfirmBindingPacket<br/>MediaToolClearBindingPacket<br/>MediaToolReportPacket"]:::c2s
        P_WL["白名单（1）<br/>WhitelistReviewActionPacket"]:::c2s
        P_MP4["MP4（4）<br/>MP4StatePacket<br/>MP4PlaybackControlPacket<br/>MP4EnsureDeviceIdPacket<br/>MP4EnsureInventoryDeviceIdPacket"]:::c2s
        P_PAD["Pad（3）<br/>PadStatePacket<br/>PadPublishPacket<br/>PadPlaybackControlPacket"]:::c2s
    end

    GATE["③ 服务端信任边界<br/><br/>主线程 handler / 异步解析后回 server executor<br/>按包执行：1 秒窗口限流（2/3/4/8/12 次）<br/>距离 ≤ 8 格 · 方块实体类型 · 菜单上下文<br/>必须持有对应 deviceId · timestamp + sequence 去旧<br/>索引/音量/时间钳制 · VIP/白名单校验<br/>白名单管理权限默认 OP4"]:::gate

    subgraph AUTH["④ 服务端权威域"]
        direction TB
        S_BLOCK["方块与装备状态<br/>ModernTurntable / Speaker / Projectors<br/>Holographic Glasses ItemStack<br/>markDirtyAndSync → 原版 BE 同步"]:::server
        S_BIND["绑定与审计<br/>MediaBindingData / CleanupService<br/>PlaybackAuditManager / 服务端菜单"]:::server
        S_WL["白名单权威数据<br/>BiliWhitelistManager<br/>世界目录 JSON"]:::server
        S_ID["MP4 身份与位置<br/>MP4DeviceIdentity<br/>MP4DeviceLocationIndex（best-effort）<br/>HolderTracker：5t 扫描 / 300t 强镜像"]:::server
        S_MP4["MP4 权威状态<br/>MP4DeviceStateStore<br/>MP4PlaybackSavedData<br/>队列以服务端 ItemStack 为准"]:::server
        S_PAD["Pad 权威文档<br/>PadDocumentStore / SavedData<br/>草稿持有验证 · 锁定副本共享 deviceId<br/>HolderTracker：5t 扫描 / 300t 强镜像"]:::server
        S_MEDIA["统一媒体会话<br/>MP4PlaybackSyncManager.SESSIONS<br/>Pad 与 MP4 共用<br/>20t timeline · 300t full sync<br/>空间源 64 格 / PLAYER 源 owner 单播<br/>耳机绑定监听者定向单播"]:::server
        S_SCOPE["Pad 地图世界作用域<br/>PadMapScopeSavedData<br/>玩家登录时推送"]:::server
    end

    subgraph S2C["⑤ S → C：11 个本地回包/广播包"]
        direction TB
        R_ID["身份（3）<br/>MP4DeviceIdPacket<br/>MP4InventoryDeviceIdPacket<br/>MP4ContainerDeviceIdPacket *"]:::s2c
        R_STATE["权威镜像（2）<br/>MP4DeviceStateMirrorPacket<br/>PadStateMirrorPacket"]:::s2c
        R_PLAY["播放同步（2）<br/>MP4PlaybackSyncPacket：完整会话<br/>MP4PlaybackTimelinePacket：轻量校时"]:::s2c
        R_WL["白名单结果（3）<br/>WhitelistReviewPacket<br/>WhitelistPreviewPacket<br/>WhitelistCsvExportPacket"]:::s2c
        R_SCOPE["地图作用域（1）<br/>PadMapWorldScopePacket"]:::s2c
    end

    subgraph CLIENT_STATE["⑥ 客户端最终落点"]
        direction TB
        D_BLOCK["方块实体同步状态 / 装备 ItemStack"]:::client
        D_MP4["MP4Client<br/>device state · queue · headphone link"]:::client
        D_PAD["PadClient 文档缓存"]:::client
        D_PLAY["ClientMediaPlaybackRegistry<br/>ClientMediaSoundRegistry<br/>sourceId + sessionId 防串台<br/>timeline 不匹配则丢弃"]:::client
        D_WL["审核/预览 Screen<br/>客户端 CSV 文件"]:::client
        D_MAP["地图磁盘缓存<br/>world scope / dimension"]:::client
    end

    subgraph NETMUSIC["⑦ 上游 NetMusic 兼容协议（不属于 ncpb payload）"]
        direction TB
        U_SET["SetMusicIDMessage<br/>CDBurner → 服务端"]:::upstream
        U_GUARD["SetMusicIDMessageMixin<br/>SongInfo sanitize<br/>拒绝 CDN 直链 · 执行白名单"]:::gate
        U_MUSIC["MusicToClientMessage<br/>现代唱片机 → tracking players"]:::upstream
        U_CLIENT["MusicToClientMessageClientMixin<br/>接管默认播放<br/>歌词/视频/ModernTurntableSound"]:::upstream
    end

    C_CFG --> P_CFG
    C_TOOL --> P_TOOL
    C_WL --> P_WL
    C_MP4 --> P_MP4
    C_PAD --> P_PAD

    P_CFG --> GATE
    P_TOOL --> GATE
    P_WL --> GATE
    P_MP4 --> GATE
    P_PAD --> GATE

    GATE --> S_BLOCK
    GATE --> S_BIND
    GATE --> S_WL
    GATE --> S_ID
    GATE --> S_MP4
    GATE --> S_PAD
    GATE --> S_MEDIA

    S_ID --> R_ID
    S_ID --> R_STATE
    S_MP4 --> R_STATE
    S_PAD --> R_STATE
    S_MEDIA --> R_PLAY
    S_WL --> R_WL
    S_SCOPE --> R_SCOPE

    S_BLOCK -. "原版 BlockEntity 更新" .-> D_BLOCK
    R_ID --> D_MP4
    R_STATE --> D_MP4
    R_STATE --> D_PAD
    R_PLAY --> D_PLAY
    R_WL --> D_WL
    R_SCOPE --> D_MAP

    C_CD -->|"NetworkHandler.sendToServer"| U_SET
    U_SET --> U_GUARD
    U_GUARD --> S_WL
    S_BLOCK --> U_MUSIC
    U_MUSIC --> U_CLIENT
    U_CLIENT --> D_PLAY

    N1["* 已注册但当前源码未找到主动发送点：<br/>ClearEquippedBindingPacket<br/>MP4ContainerDeviceIdPacket"]:::note
    N2["三类同步不要混淆：<br/>Mirror = UI/文档权威状态<br/>Full Sync = 创建/替换播放会话<br/>Timeline = 只校时，不能创建会话"]:::note
    N3["关键授权语义：<br/>Pad 以‘持有同 deviceId’为编辑/播放依据，不是作者 ACL；<br/>MP4 UUID 去重与位置索引不是全服务器强一致扫描。"]:::note
    P_CFG -.-> N1
    R_ID -.-> N1
    R_PLAY -.-> N2
    S_ID -.-> N3
    S_PAD -.-> N3
```

## 读图顺序

从左向右看：客户端行为产生 C→S payload，经服务端信任边界验证后修改权威状态；服务端再通过身份回包、状态镜像、完整播放同步、轻量时间轴或管理结果回到客户端。MP4 与 Pad 的数据模型彼此独立，但两者最终共用同一套媒体会话同步与客户端播放器。

图中 `*` 表示包已注册且 handler 完整，但当前源码没有发现主动发送点。方块实体常规数据使用 Minecraft 原生 BlockEntity 同步，不会额外占用一个 `ncpb` payload。刻录和现代唱片机播放还复用了上游 NetMusic 消息，因此单独画在最下方。
