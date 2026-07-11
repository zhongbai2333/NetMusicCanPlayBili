# 小地图模组系统设计参考

本文总结 `aleeve-atlas`、`FTB-Chunks`、`VoxelMap` 三类成熟地图/小地图模组的设计思路，抽象出一个通用“小地图模组”应该如何设计。本文**不讨论某个具体业务需求**，也不以当前项目现有实现为前提；它只描述一个 Minecraft 小地图/世界地图系统从零设计时应采用的架构、数据流、缓存、渲染与性能策略。

## 设计目标

一个合格的小地图模组至少要满足以下目标：

1. **稳定**：玩家移动、转向、跨 chunk、跨 region 时地图不能抖动、回弹或闪烁。
2. **准确**：底图应尽可能反映真实世界地形，而不是依赖脆弱的语义猜测。
3. **增量**：世界变化后只更新受影响区域，不能每次重建整张地图。
4. **异步**：采样、缓存、纹理生成应尽可能脱离 GUI 渲染路径。
5. **分层**：地形底图、玩家箭头、实体雷达、waypoint、文字、边框等必须分层渲染。
6. **可缓存**：已经探索过的地图应能复用、持久化、压缩和淘汰。
7. **可扩展**：支持不同 zoom、过滤模式、洞穴/下界模式、biome overlay、height shading、world map 等扩展能力。

小地图系统本质不是一个 GUI 控件，而是一套完整的客户端地图引擎。

```text
World / Chunks
  ↓
Sampler
  ↓
Map Data Cache
  ↓
Texture Builder
  ↓
Viewport Renderer
  ↓
Overlay Renderer
  ↓
HUD / Screen / Item Surface
```

## 三个参考模组的核心启发

### aleeve-atlas：简单、直接、以真实颜色为底

`aleeve-atlas` 的重点是简单可靠：

- 使用屏幕网格或地图网格进行采样。
- 底图主要基于 Minecraft 原生颜色体系，例如 `MapColor`、block color、biome fallback。
- 不把“草地 / 建筑 / 树 / 道路”这类语义分类作为底图核心。
- waypoint、玩家标记、雷达等是 overlay，不参与底图生成。
- 使用短周期颜色缓存，避免每帧重复访问世界数据。

它说明了一件事：

> 小地图底图的第一目标是“像世界”，不是“理解世界”。

也就是说，小地图底层应该先画出可靠的地貌颜色，再考虑额外的语义增强。

### FTB-Chunks：世界固定 region 与 chunk 增量更新

`FTB-Chunks` 的地图系统更偏工程化：

- 地图数据按世界坐标切成固定 region。
- region 再由 chunk 级数据组成。
- chunk 更新后只刷新对应区域。
- 小地图不是重新采样玩家周围整张图，而是从 world-fixed region texture 中裁切当前视口。
- 玩家在 chunk 内移动时主要通过 UV / 矩阵偏移表现，不触发大规模重采样。

典型结构类似：

```text
MapManager
  └─ MapDimension
      └─ MapRegion
          ├─ MapRegionData
          ├─ MapChunk
          └─ MapRegionTexture
```

这种设计的关键是：

> 地图缓存坐标必须固定在世界上，而不是固定在玩家身上。

如果地图以玩家为中心反复生成 snapshot，玩家移动时就会不断改变采样边界、缓存命中和渲染锚点，极易出现抖动、空洞、回弹和性能尖峰。

### VoxelMap：实时小地图与持久世界地图分离

`VoxelMap` 同时包含实时 minimap 和 persistent world map，两套系统各自优化。

实时小地图部分：

- 为不同 zoom 准备多套 `FullMapData` 和动态纹理。
- 小范围移动时移动已有 texture/data buffer，只补新露出的边缘条带。
- 大距离跳跃或 zoom 改变时才 full render。
- 使用后台计算线程进行地图计算。
- 主渲染路径只负责上传 dirty texture 和绘制。

持久世界地图部分：

- 使用 `CachedRegion` 作为世界固定地图块。
- 一个 region 对应固定世界范围，例如 256x256 blocks。
- region 内保存可压缩地图数据和可上传纹理。
- chunk 变化会延迟处理，确认 chunk 周围加载稳定后再更新。
- 不活跃 region 会压缩、保存或淘汰。

VoxelMap 的核心启发是：

> 实时小地图可以用滚动纹理补边；大地图应使用持久 region。两者都不能把 GUI 渲染、世界采样和贴图上传混在一起。

## 推荐总体架构

一个通用小地图模组建议拆成以下模块。

```text
MinimapSystem
  ├─ WorldMapIndex
  │   ├─ 按 dimension / world / subworld 管理地图数据
  │   └─ 管理 region 生命周期
  │
  ├─ RegionManager
  │   ├─ 创建、加载、保存、淘汰 region
  │   ├─ 接收 dirty chunk
  │   └─ 调度异步刷新任务
  │
  ├─ RegionData
  │   ├─ 保存 raw/factual map data
  │   ├─ 可压缩
  │   └─ 可持久化
  │
  ├─ MapSampler
  │   ├─ 从 chunk/block/biome/light 采样
  │   ├─ 输出事实数据或最终颜色
  │   └─ 避免 GUI 线程重采样
  │
  ├─ TextureManager
  │   ├─ region texture
  │   ├─ mipmap / filtering
  │   └─ dirty upload
  │
  ├─ ViewportRenderer
  │   ├─ 将世界坐标映射到屏幕坐标
  │   ├─ 裁切可见 region
  │   └─ 处理 zoom、旋转、UV 偏移
  │
  └─ OverlayRenderer
      ├─ player arrow
      ├─ waypoints
      ├─ mob radar
      ├─ chunk grid / slime chunk
      └─ text / frame / mask
```

这种结构的原则是：

- `RegionData` 不关心 GUI。
- `TextureManager` 不关心 waypoint。
- `ViewportRenderer` 不采样世界。
- `OverlayRenderer` 不修改底图。
- `MapSampler` 不直接画屏幕。

每一层只做一件事。

## 坐标系统设计

小地图最容易出问题的地方是坐标系统。建议明确区分以下坐标：

| 坐标类型 | 说明 |
|---|---|
| Block 坐标 | Minecraft 世界坐标，单位为 block |
| Chunk 坐标 | `block >> 4`，单位为 16x16 blocks |
| Region 坐标 | 固定地图块坐标，例如 256x256 或 512x512 blocks |
| Local 坐标 | region 内部像素坐标 |
| Texture 坐标 | GPU texture UV |
| Screen 坐标 | HUD/GUI/item surface 上的最终绘制坐标 |

推荐关系：

```text
blockX, blockZ
  ↓ floorDiv(regionSize)
regionX, regionZ
  ↓ floorMod(regionSize)
localX, localZ
  ↓ / regionSize
u, v
  ↓ viewport transform
screenX, screenY
```

所有缓存 key 必须基于世界固定坐标：

```text
MapRegionKey:
  worldId / serverId
  dimensionId
  subworldId optional
  caveMode / floorMode optional
  regionX
  regionZ
  zoom or scale optional
```

不要用玩家坐标作为缓存 key 的核心。玩家坐标只应该影响 viewport。

## Region 尺寸选择

常见选择：

| Region 尺寸 | 优点 | 缺点 |
|---|---|---|
| 128x128 blocks | 更新细、内存小 | region 数量多，draw call/管理成本高 |
| 256x256 blocks | 平衡，VoxelMap 采用类似规模 | 需要一定缓存管理 |
| 512x512 blocks | region 数量少，适合大地图 | 单次刷新成本更高 |

推荐默认：

```text
256x256 blocks = 16x16 chunks
```

原因：

- 与 chunk 边界天然对齐。
- 单 region 数据规模可控。
- 可见区域通常只需少量 region。
- dirty chunk 只影响 region 内 16x16 像素区域。

如果目标是大地图浏览，可提高到 512x512；如果目标是超低延迟小地图，可降低到 128x128。

## 地图数据模型

底图数据不应首先设计成语义枚举。推荐保存“世界事实”。

### 最小数据模型

```text
MapCell:
  argbColor: int
  height: short
  flags: byte
```

适合轻量小地图，采样后直接得到颜色。

### 标准数据模型

```text
MapCell:
  surfaceHeight: short
  surfaceBlockStateId: int or short-local-id
  biomeId: int or short-local-id
  light: byte
  flags: byte
```

适合支持 biome tint、光照、heightmap、slopemap。

### 高级数据模型

```text
MapCell:
  surfaceHeight
  surfaceBlockState
  surfaceLight

  oceanFloorHeight
  oceanFloorBlockState
  oceanFloorLight

  transparentHeight
  transparentBlockState
  transparentLight

  foliageHeight
  foliageBlockState
  foliageLight

  biome
  flags
```

这是接近 VoxelMap 的思路，适合追求高拟真：

- 水透明。
- 树叶/植被层。
- 玻璃/透明方块层。
- 下界/洞穴特殊高度。
- biome tint 动态变化。

但高级模型复杂度较高，不建议一开始就实现完整版本。

## 采样系统设计

采样器的职责是把 Minecraft 世界转换为地图数据或颜色。

### 基础采样流程

```text
for each target cell:
  locate chunk
  if chunk not ready:
    mark unknown
    continue

  find surface height
  find visible block/fluid
  get biome
  get base color
  apply biome tint
  apply height/slope shading optional
  apply light optional
  write map cell
```

### Chunk ready 判断

不能只判断目标 chunk 是否存在。推荐要求：

```text
chunk ready = 当前 chunk 已加载 && 周围 3x3 chunk 已加载
```

这样可以避免：

- 高度图刚加载不稳定。
- 邻接边缘采样断裂。
- 水体、斜坡、透明层判断缺信息。
- 光照未完成导致颜色错误。

### Dirty chunk 延迟

世界变化后不要立即采样，建议：

```text
block/chunk changed
  → mark dirty chunk
  → delay 10~20 ticks
  → check chunk ready
  → resample that chunk area
  → mark region texture dirty
```

这种延迟可以吸收一连串方块变化，也能等 chunk/light 状态稳定。

### 采样预算

小地图必须有预算系统：

```text
maxChunksPerTick
maxCellsPerTick
maxRegionRefreshesPerTick
maxTextureUploadsPerFrame
```

并按优先级排序：

1. 当前视口中心。
2. 玩家移动方向前方。
3. 可见边缘。
4. 视口外预热区域。
5. 远处缓存刷新。

不要让远处预热抢占当前屏幕可见区域。

## 颜色系统设计

小地图底图颜色建议来源顺序：

1. 原版 `MapColor` 或 block map color。
2. Block color / biome tint。
3. Fluid color。
4. Biome fallback color。
5. Unknown color。

推荐颜色管线：

```text
block state
  → base map color
  → biome tint optional
  → water/transparent blend optional
  → height/slope shade optional
  → light shade optional
  → final ARGB
```

### Unknown 不应伪装成草地

未采样区域必须显式表现为 unknown，例如：

- 半透明深色。
- 棋盘格。
- 雾化边缘。
- loading tile。

不要把 unknown 当成 grass、stone 或 biome default。否则用户会误以为地图已经准确加载。

## 实时小地图策略

实时 minimap 有两种主流实现方式。

### 方案 A：World-fixed region 裁切

这是最推荐的通用方案。

```text
visible world rect
  → find intersecting regions
  → blit region textures with UV clipping
  → draw overlays
```

优点：

- 稳定。
- 与大地图共享数据。
- chunk dirty 更新自然。
- 玩家移动只改变 viewport。

缺点：

- 初始架构稍复杂。
- 需要 region texture 管理。

### 方案 B：Rolling texture

这是 VoxelMap 实时小地图使用的思路。

```text
player moved by dx/dz
  → move existing texture buffer
  → move existing map data buffer
  → sample newly exposed strips
  → upload changed texture
```

优点：

- 对小地图非常高效。
- texture 数量少。
- 玩家小范围移动成本极低。

缺点：

- 和持久大地图共享较难。
- 需要处理大距离跳跃、zoom 改变、dimension 改变。
- buffer 移动和坐标锚点必须非常严谨。

如果同时需要小地图和世界地图，推荐以方案 A 为主；如果只做 HUD minimap，方案 B 也很合适。

## 世界地图策略

世界地图应采用 persistent region 架构。

```text
WorldMap
  ├─ visible region set
  ├─ region cache pool
  ├─ async load queue
  ├─ async refresh queue
  └─ async save/compress queue
```

### Region 生命周期

```text
created
  → load cached data
  → load current chunks
  → optional load saved/anvil data
  → build image
  → upload texture
  → visible
  → inactive
  → compress
  → save
  → evict
```

### 缓存淘汰

淘汰排序建议同时考虑：

- 最近访问时间。
- 距离当前视口中心的距离。
- 是否 dirty。
- 是否正在显示。
- 是否已经保存。

示例策略：

```text
if cacheSize > limit:
  sort by visible desc, dirty desc, recentAccess desc, distance asc
  evict tail
```

## 纹理系统设计

地图纹理应该和地图数据分离。

```text
RegionData -> RegionImage -> GPU Texture
```

### Dirty upload

不要每帧上传全部纹理。推荐：

```text
if region.imageDirty:
  rebuild image optional
  upload texture on render thread
  imageDirty = false
```

并限制每帧上传数量：

```text
maxTextureUploadsPerFrame = 1~4
```

### Filtering

建议支持两套或可切换 sampler：

- nearest：像素风清晰。
- linear：缩放平滑。

VoxelMap 的 filtered/unfiltered 双纹理思路说明：过滤模式本身也可能成为配置项。

### Mipmap

大地图缩放时 mipmap 很有用：

- 减少远距离闪烁。
- 降低采样噪声。
- 提高缩小时视觉质量。

但生成 mipmap 有成本，适合 region 刷新完成后异步或低频生成。

## Viewport 渲染设计

Viewport 是世界坐标到屏幕坐标的映射。

```text
Viewport:
  centerX
  centerZ
  zoomScale
  rotation
  screenRect
  worldUnitsPerPixel
```

渲染时：

```text
visibleWorldRect = viewport.computeWorldRect()
visibleRegions = regionManager.query(visibleWorldRect)
for region in visibleRegions:
  intersection = region.worldRect ∩ visibleWorldRect
  uv = intersection within region texture
  screenQuad = viewport.worldToScreen(intersection)
  draw region texture
```

玩家移动只应该改变 `centerX/centerZ`，不应该导致整张底图重采样。

### 旋转地图

旋转地图有两种方式：

1. 旋转整个 map layer。
2. 保持地图朝北，只旋转玩家箭头。

如果支持旋转地图，应注意：

- 需要更大的 overscan，避免旋转后边角露空。
- square map 旋转时可见范围变大约 $\sqrt{2}$ 倍。
- waypoint 和 radar overlay 要和地图使用同一旋转上下文。

## Overlay 分层设计

Overlay 不应污染底图数据。

推荐层级：

```text
Layer 0: map base texture
Layer 1: optional map effects, grid, biome overlay
Layer 2: entity radar / mob icons
Layer 3: waypoints / markers
Layer 4: player arrow
Layer 5: frame / mask
Layer 6: text / coordinates / debug
```

### Waypoint

Waypoint 应保存世界坐标：

```text
Waypoint:
  id
  name
  x, y, z
  dimension
  icon
  color
  visible
```

渲染时通过 viewport 投影：

```text
screen = viewport.worldToScreen(waypoint.x, waypoint.z)
if outside minimap:
  clamp to edge optional
  draw edge arrow optional
else:
  draw icon
```

### Radar

Radar 应独立于地图采样：

```text
entity position
  → relative to player/viewport
  → icon selection
  → depth/elevation marker optional
  → draw overlay
```

实体刷新频率可以低于帧率，例如 5~10 tick 一次。

## 多维度、洞穴和下界

小地图必须考虑特殊维度。

### 普通地表

使用 heightmap 找地表：

```text
MOTION_BLOCKING / WORLD_SURFACE
```

根据需求选择是否穿透树叶、玻璃、水面。

### 洞穴模式

洞穴图通常不是最高地表，而是玩家附近可见层。

可选策略：

```text
从玩家 Y 向下找可站立表面
或
从玩家 Y 附近找第一个实心/空气边界
```

洞穴模式必须加入 floor/cave key，否则同一 X/Z 不同 Y 层会混用缓存。

### 下界/有天花板维度

下界高度逻辑不能直接用最高 heightmap。常见策略：

- 玩家在开放区域时显示上方/当前位置附近地形。
- 玩家在洞穴/封闭区域时显示玩家 Y 附近层。
- 对 lava、netherrack、透明层单独处理。

缓存 key 应包含：

```text
mode = SURFACE | CAVE | NETHER_LAYER
floorY or layerBand optional
```

## 异步与线程模型

建议线程职责：

```text
Client Tick Thread:
  collect player position
  collect dirty chunks
  enqueue jobs

Worker Thread(s):
  sample chunks
  rebuild region images
  compress/save data

Render Thread:
  upload GPU textures
  draw map and overlays
```

注意：

- 不能在非渲染线程上传 GPU texture。
- 不能在渲染线程做大规模世界扫描。
- 访问 Minecraft world/chunk 数据时要遵守线程安全约束；必要时只在 client tick 收集快照，再交给 worker 处理。
- worker 结果应通过 immutable result 或锁保护结构回传。

## 性能预算

建议暴露以下配置或内部常量：

```text
maxChunkSamplesPerTick
maxCellsPerTick
maxRegionBuildsPerTick
maxTextureUploadsPerFrame
maxLoadedRegions
maxCachedRegions
maxDiskSaveJobs
```

### 优先级队列

采样任务建议按优先级排序：

```text
priority = visibleNow
         + nearViewportCenter
         + playerMovingDirection
         + dirtyChunk
         + notYetSampled
         - farAway
```

不要用简单 FIFO 处理所有任务，否则玩家快速移动时会一直追赶旧位置。

### 避免性能尖峰

常见尖峰来源：

- zoom 改变时全量重采样。
- dimension 切换时同步加载大量 region。
- 一帧上传多张大 texture。
- 每帧重新计算所有 waypoint label。
- 在 GUI draw 中访问大量 block state。
- unknown 区域反复采样失败。

对应策略：

- 分帧采样。
- 异步 region load。
- 上传限流。
- overlay label 缓存。
- chunk ready 失败后 backoff。
- region LRU。

## 持久化设计

如果支持世界地图或探索记录，应持久化 region data。

推荐目录结构：

```text
cache/
  server-or-world-id/
    dimension-id/
      subworld-id/
        region_x_z.dat
        region_x_z.png optional
```

数据文件建议包含：

```text
version
regionX
regionZ
dimension
mode
palette blockstates optional
palette biomes optional
compressed cell data
checksum optional
```

### 版本迁移

地图缓存必须有版本号：

```text
CACHE_VERSION = n
```

当以下内容变化时应 bump：

- cell 数据布局。
- 颜色算法。
- region 尺寸。
- mode key。
- block/biome palette 编码。

旧缓存可以：

- 自动迁移。
- 忽略重建。
- 分版本目录隔离。

## 配置项设计

典型小地图配置：

```text
showMinimap
showWorldMap
mapShape: square | round
rotateMap
northLock
zoom
filtering
showCoords
showBiome
showWaypoints
showRadar
showMobs
showPlayers
showChunkGrid
showSlimeChunks
heightmap
slopemap
dynamicLighting
biomeTint
biomeOverlay
waterTransparency
blockTransparency
cacheSize
```

配置变化后不一定要清空数据。应区分：

| 配置类型 | 影响 |
|---|---|
| 渲染配置 | 只影响 draw，例如 frame、coords、waypoint |
| 着色配置 | 需要重建 region image，例如 biome overlay、heightmap |
| 采样配置 | 需要重新采样，例如 cave mode、透明层策略 |
| 数据布局配置 | 需要清缓存或迁移 |

## Debug 与可观测性

小地图系统复杂，必须内置 debug 能力。

建议提供：

```text
visible region count
loaded region count
queued sample jobs
queued upload jobs
sampled cells per tick
texture uploads per frame
cache hit/miss
chunk ready failures
dirty chunk count
last region build time
last texture upload time
```

并支持点击/悬停查看某个地图像素：

```text
blockX/blockZ
regionX/regionZ
localX/localZ
height
blockstate
biome
light
final color
cache source
```

没有这些信息，地图 bug 会很难定位。

## 常见错误设计

### 错误一：玩家中心 snapshot 作为主缓存

问题：

- 玩家一动，采样边界变化。
- 缓存坐标不稳定。
- 容易抖动和回弹。
- 快速移动时采样永远追不上。

推荐：

```text
世界固定 region + viewport 裁切
```

### 错误二：语义分类作为底图

问题：

- Minecraft 方块组合太自由。
- 建筑、道路、树冠、装饰、自然地貌边界模糊。
- 启发式很容易误判。

推荐：

```text
真实颜色底图 + 可选语义 overlay
```

### 错误三：在渲染函数里采样世界

问题：

- FPS 抖动。
- GUI 卡顿。
- 采样耗时不可控。

推荐：

```text
异步/分帧采样 + dirty texture upload
```

### 错误四：unknown 伪装成已采样地形

问题：

- 用户误以为地图准确。
- 后续刷新会像“地形突变”。

推荐：

```text
unknown 明确显示为 loading/空白/棋盘格
```

### 错误五：overlay 混入底图

问题：

- waypoint/radar 更新导致底图重建。
- 图层无法独立开关。
- 交互命中检测混乱。

推荐：

```text
底图、实体、waypoint、文字、frame 分层
```

## 推荐实现顺序

如果从零实现一个小地图模组，建议按以下顺序推进。

### Phase 1：静态 region 底图

目标：能生成并显示玩家附近 region。

实现：

- Region key。
- Region data。
- 基础 sampler。
- 基础 region texture。
- viewport 裁切渲染。

不做：

- 持久化。
- radar。
- 复杂透明层。
- world map。

### Phase 2：chunk dirty 增量更新

目标：方块变化后局部刷新。

实现：

- dirty chunk queue。
- delayed update。
- chunk ready check。
- region partial rebuild。
- texture upload 限流。

### Phase 3：overlay 系统

目标：玩家箭头、waypoint、实体图标独立渲染。

实现：

- overlay renderer。
- worldToScreen。
- edge clamp。
- label cache。

### Phase 4：持久化和 LRU

目标：探索过的地图能复用。

实现：

- region save/load。
- cache version。
- compression。
- LRU prune。

### Phase 5：高级视觉

目标：接近成熟 minimap。

实现：

- heightmap/slopemap。
- biome overlay。
- water transparency。
- cave/nether mode。
- mipmap/filtering。
- debug inspector。

## 最终推荐架构摘要

最稳妥的小地图模组设计是：

```text
世界固定 region/tile
  + chunk 级 dirty 增量更新
  + 真实颜色/事实数据作为底图
  + viewport/UV 裁切显示
  + overlay 独立分层
  + 异步采样和纹理上传限流
  + region 持久化、压缩、LRU
```

三个参考模组可以这样理解：

| 模组 | 最值得学习的点 |
|---|---|
| aleeve-atlas | 简单真实颜色底图，overlay 分离 |
| FTB-Chunks | 世界固定 region/chunk 架构，chunk 增量更新 |
| VoxelMap | 实时小地图滚动纹理、persistent region、异步计算、压缩缓存 |

如果只能记住一句话：

> 小地图不是“以玩家为中心不断截图”，而是“把世界切成稳定地图块，再用视口去看这些地图块”。
