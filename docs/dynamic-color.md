# 动态取色

Lyra 的「动态取色」不是把系统的 Material You 配色直接搬过来，而是**只取壁纸主色，
再按 Lyra 的规则重算一遍**。这样动态配色和内置主题共用同一套角色与明度关系，
换壁纸不会把界面变成另一种设计语言。

## 为什么不用系统现成的整套配色

系统给的 dynamic scheme 已经决定了主色饱和度、三色关系和表面色阶。直接用会遇到三个问题：

1. 内置主题和动态主题像两个 App：一个按 Lyra 的色阶，一个按 OEM 的色阶；
2. 壁纸偏灰时，系统仍会硬套一组彩色，界面显脏；
3. 系统方案的 tertiary / 表面色阶无法和内置主题对齐，组件切换主题时会跳色。

所以动态取色被降级成「只提供色相」，其余全部由 Lyra 自己决定。

## 算法

入口在 `core/designsystem/.../DynamicColor.kt`：

```
system_accent1_500 (Android 12+)
        │  sRGB → CIELAB
        ▼
   seed: (h, C)
        │  按角色缩放彩度、固定色相
        ▼
6 条色调阶梯：primary  secondary  tertiary  neutral  neutralVariant  error
   h, 1.05C      h, 0.34C   h+60°, .55C   h, 6      h, 10           32°, 80
        │  每个 tone 在 sRGB 色域内二分收缩彩度
        ▼
标准 M3 角色：colorPrimary / colorOnPrimary / colorSurfaceContainer …
```

### 1. 取 seed

API 31+ 读取 `android.R.color.system_accent1_500`。这是系统从壁纸生成的
accent 调色板的 tone 50，色相稳定、彩度接近该色相的上限，适合当 seed。
读取失败或系统低于 Android 12 时返回 `null`，界面回退到用户选中的固定主题。

不申请壁纸读取权限，也不依赖 `WallpaperManager`：没有权限成本，也不受
OEM 壁纸服务实现差异影响。

### 2. 转到 CIELAB

只保留色相 `h` 与彩度 `C`：

- **L\*** 就是 M3 的 tone（0 黑、100 白），对比度由它决定；
- 色相在 AB 平面里固定，换 tone 不会偏色；
- 彩度用二分法在 sRGB 色域内收缩，保留色相与 L\*。

这正是 HCT 的核心承诺；用 CIELAB 实现，省掉 CAM16 的整套推导，
仍然保证「tone 差 50 ≈ 对比度 4.5」这个可用性前提。

### 3. 彩度策略

| 角色 | 色相 | 彩度 | 说明 |
|---|---|---|---|
| primary | h | `clamp(1.05C, 32, 56)` | 比壁纸原色更饱满，但不至于荧光 |
| secondary | h | `clamp(0.34C, 8, 16)` | 低饱和的同色相辅助色 |
| tertiary | h + 60° | `clamp(0.55C, 14, 28)` | 对位色，用于强调 |
| neutral | h | 6 | 表面带一点壁纸色温 |
| neutralVariant | h | 10 | 轮廓、次要文字的容器 |
| error | 32° | 80 | 固定红色语义，不跟壁纸走 |

壁纸彩度 `C < 4` 时视为灰度壁纸：除 error 外全部彩度归零，得到单色主题，
而不是硬塞一种颜色。

### 4. tone → 角色

和 `LyraColorTheme` 完全一致：浅色用 40 / 90 / 10，深色用 80 / 30 / 90；
surface 浅色 98、深色 6；容器色阶 100 / 96 / 94 / 92 / 90（深色 4 / 10 / 12 / 17 / 22）。
角色表被抽成 `buildScheme(...)`，内置主题与动态主题共用同一份 tone 约定。

## 界面

「动态取色」是配色方案列表里的第一项，和 8 套固定主题平级：

- 选中动态取色 → `dynamic_color = true`，优先使用本算法生成的方案；
- 选中固定主题 → `dynamic_color = false`，写入 `color_theme`；
- 动态取色的缩略色块就是当前算法算出来的三颗角色色；
- Android 12 以下没有壁纸调色板资源，列表里直接不显示这一项。

## 回退与边界

- 系统 < Android 12：`isDynamicColorAvailable() == false`，不显示动态取色；
- 资源读取失败：`systemDynamicColorScheme()` 返回 `null`，回退固定主题；
- 壁纸变化：方案在 `remember(dynamicColor, darkTheme, colorTheme, context)` 内计算，
  壁纸切换通常伴随 Activity 重建；如果需要更实时的跟随，可以在 key 里加入
  `WallpaperManager.getWallpaperId(FLAG_SYSTEM)`；
- 性能：一次生成约 6 × 20 个 tone，每个 tone 做 18 次二分；同一条阶梯的 tone 会缓存。
  方案在 composition 里只算一次，预期开销在一帧预算内（单测覆盖在 JVM 上即时完成）。

## 测试

`core/designsystem/src/test/.../DynamicColorTest.kt` 覆盖：

- 各种 seed（蓝 / 绿 / 橙 / 紫 / 高彩度黄 / 灰）在明暗两套下的对比度下限；
- 彩色壁纸的色相是否留在主色上、tertiary 是否保持 +60°；
- 灰度壁纸是否退化成单色、error 是否仍为红色；
- Lab 往返、L\* 已知值、主题 id 回退。

```bash
./gradlew :core:designsystem:testDebugUnitTest
```
