# VVideo 安卓 UI 重设计规范（频道式媒体浏览）

日期：2026-10-10（Asia/Shanghai）。状态：**设计方案，待实施**。视觉稿见 Claude 设计画布“VVideo 安卓 UI 设计”（14 个画板：首页、文件夹流、菜单、排序、子文件夹、播放器三态、首次连接、设置、存储用量、浅色两张、设计令牌与组件）。

本文替代 [2026-10-09 UI 改版方案](2026-10-09-android-ui-redesign.md) 中的**视觉、布局与交互部分**（绿色主题、文件夹行样式、相册表现、播放器控件、设置布局）。产品行为与性能门槛仍以 [DESIGN.md](../DESIGN.md) 为准：十项相册、排序语义、锚点恢复、单播放器复用、缓存预算、高刷验收都不变。本文没有任何数值是实测结果。

---

## 1. 设计思路：把媒体库当成 Telegram

旧界面的问题不是某个颜色，而是缺一个“心智模型”：文件夹像文件管理器，相册是一堆没有时间感的彩色块，播放器是默认控件。Telegram 的媒体体验好，是因为它把一切放进三个熟悉的层级。本设计直接借用这个结构（只借交互模式，不使用 Telegram 的名称、图标与品牌元素）：

| Telegram | VVideo | 说明 |
| --- | --- | --- |
| 会话列表 | **首页**：置顶的媒体库 + 最近浏览的文件夹 | 圆形字母头像、右侧时间、按最近访问排序 |
| 频道 / 聊天 | **文件夹页**：消息流式的相册 | 每组十项 = 一条“相册消息”，日期胶囊分隔 |
| 相册消息（≤10 项拼图） | 现有十项拼接相册 | 气泡外轮廓 16dp 圆角、2dp 缝隙、时长和时间角标 |
| 共享媒体标签页 | 全部 / 视频 / 照片 | 直接使用 API 已有的 `kind` 参数 |
| 置顶消息条 | **子文件夹条** | 不让子目录挤占相册流，点开为底部面板 |
| 图片/视频查看器 | 全屏播放器 | 黑底、顶部文件名与时间、底部细进度条、双击 ±10 秒、长按 2× |
| 顶栏“连接中…” | 首页标题显示真实连接状态 | 替代旧版的独立状态文字 |
| 存储用量页 | 缓存页：环形图 + 清除按钮 + 三档上限滑杆 | 对应 1/5/10 GB |

默认采用“夜蓝”深色主题（看媒体时最舒服，也最像 Telegram 夜间模式）；浅色跟随系统，可在设置中固定。

---

## 2. 信息架构与导航

```
首页 ─┬─ 媒体库（置顶） ─→ 文件夹页（媒体库根目录）
      └─ 最近浏览       ─→ 文件夹页（恢复锚点）
文件夹页 ─┬─ 子文件夹条 → 子文件夹面板 → 文件夹页（子级）
          ├─ ⋮ 菜单 → 排序面板 / 回到最新 / 刷新 / 文件夹信息
          ├─ 视频 → 播放器（同目录视频队列，上下滑切换）
          └─ 照片 → 照片查看器
首页 ⚙ → 设置 → 存储用量 / 修改连接 / 主题
未配置连接 → 首次连接（唯一入口）
```

- 路由在现有 `VVideoRoot` 的 `route` 字符串上扩展：`library`（首页与文件夹共用 `LibraryViewModel`）、`video`、`photo`、`settings`、`storage`、`connection`。
- 返回规则沿用旧方案：先关面板/菜单 → 离开播放器、设置 → 文件夹返回父级 → 媒体库根返回首页 → 首页才退出应用。**变化点**：媒体库根目录返回的是“首页”，而首页同时承担媒体库列表与最近浏览。
- 冷启动仍恢复最近目录（`Connection.lastDirectory`），返回键从那里回到首页。

---

## 3. 设计令牌

### 3.1 颜色

所有颜色从一份 `VVColors` 取，不再依赖 Material 动态色。Material 组件需要的 `colorScheme` 由它映射生成（见 3.6）。

| 令牌 | 夜蓝（深色） | 浅色 | 用途 |
| --- | --- | --- | --- |
| `bgPage` | `#0E1621` | `#E3EAF0` | 相册流底色、设置分组之间的底 |
| `bgSurface` | `#17212B` | `#FFFFFF` | 顶栏、列表、设置分组 |
| `bgRaised` | `#1E2C3A` | `#FFFFFF`（加阴影） | 底部面板、弹出菜单、FAB |
| `bgInput` | `#111A23` | `#F4F6F8` | 输入框 |
| `bgPlaceholder` | `#1A2531` | `#D5DEE6` | 封面未到时的占位 |
| `pinnedRow` | `#1A2531` | `#F6F8FA` | 首页置顶的媒体库行 |
| `divider` | `#222F3C` | `#E8EBEF` | 分隔线 |
| `textPrimary` | `#E9EEF3` | `#15191E` | 正文、标题 |
| `textSecondary` | `#8B9BAB` | `#6B7682` | 副标题、时间、说明 |
| `textTertiary` | `#6A7B8C` | `#9AA4AE` | 置顶图标、箭头等弱提示 |
| `icon` | `#AEBBC8` | `#5B6670` | 顶栏图标 |
| `accentText` | `#6AB3F8` | `#2575CC` | 强调文字、选中标签、分组标题 |
| `accentFill` | `#2575CC` | `#2575CC` | 主按钮底色（白字对比约 4.6:1） |
| `accentSoft` | `#1D3550` | `#E3EFFB` | 次按钮、图标底、选中分段 |
| `online` | `#5BC88A` | `#2E9E5E` | 在线、已连接 |
| `warn` | `#E8B04B` | `#B7791F` | 离线、提醒 |
| `error` | `#EF6B6B` | `#D93F3F` | 错误、危险操作 |
| `datePill` | `#1E2C3A` α 0.92 | `#283E54` α 0.55 | 日期胶囊底 |
| `datePillText` | `#D3DEE8` | `#FFFFFF` | 日期胶囊文字 |
| `overlayBadge` | `#000000` α 0.50 | 同左 | 媒体格角标 |
| `viewerSheet` | `#1B232C` | 同左（播放器内永远深色） | 播放器内的详情面板 |

播放器、照片查看器永远黑底白字，不跟随主题。

**头像色板**（字母头像，上→下线性渐变）：

| 序号 | 起 | 止 |
| --- | --- | --- |
| 0 红 | `#F2836B` | `#D14C4C` |
| 1 橙 | `#F5B256` | `#E0782E` |
| 2 紫 | `#A891F2` | `#6A5BD6` |
| 3 绿 | `#86C566` | `#3E9F4A` |
| 4 青 | `#4FCFC4` | `#1E9C93` |
| 5 蓝 | `#5DB4EE` | `#2E7FCB` |
| 6 粉 | `#F287A6` | `#C94F72` |

- 色号 = `abs(stableId.hashCode()) % 7`；媒体库用 `library.id`，文件夹用 `directory.id`。同一文件夹在首页、顶栏、子文件夹面板颜色一致。
- 字母 = 名称中第一个汉字或字母（跳过开头的数字、空格、标点），拉丁字母转大写；名称全是数字时取第一个数字。“2024 京都旅行”→“京”，“DJI_Export”→“D”。

### 3.2 字体

使用系统默认字体（`FontFamily.Default`），不打包字体。中文在 iQOO 上会走系统中文字体。数字统一开启等宽数字（`fontFeatureSettings = "tnum"`）用于时长、时间、容量。

| 令牌 | 大小/行高 | 字重 | 用在 |
| --- | --- | --- | --- |
| `titleLarge` | 22/30 sp | Medium | 首次连接标题 |
| `titleAppBar` | 20/26 | Medium | 首页、设置等顶栏标题 |
| `titleHeader` | 17/22 | Medium | 文件夹顶栏标题、面板标题（面板用 18/24） |
| `titleRow` | 16/22 | Medium | 列表行主标题 |
| `body` | 16/22 | Regular | 设置行、详情值 |
| `bodySecondary` | 14/20 | Regular | 列表副标题、路径 |
| `labelSection` | 14/20 | Medium | 分组标题（accentText 色） |
| `tab` | 15/20 | Medium | 标签页、菜单项 |
| `meta` | 13/18 | Regular | 时间、说明、日期胶囊（胶囊用 Medium） |
| `badge` | 12/16 | Medium + tnum | 时长、时间角标 |

大字体：所有高度用 `heightIn(min=…)` 而不是固定 `height`，行高随字体增长；角标不缩字号，宁可让布局计算避开过窄格子（沿用现有 `minimumWidths` 机制）。

### 3.3 尺寸

| 元素 | 值（dp） |
| --- | --- |
| 顶栏高度 | 56（加状态栏边衬） |
| 文件夹顶栏头像 | 40，距返回键 4，距标题 12 |
| 列表行（首页、子文件夹全页） | 最小 72；头像 52；左内边 12；头像到文字 12；分隔线从 76 开始 |
| 子文件夹面板行 | 64；头像 44 |
| 标签栏 | 44；左右内边 8；标签内边 12；下划线 3 高、顶部 3 圆角、宽度=文字宽 |
| 子文件夹条 | 52；图标底 32 圆形 |
| 相册 | 左右外边 8；格缝 2；外轮廓圆角 16；相册之间 6；日期胶囊上 12 下 8 |
| 媒体角标 | 高 20，圆角 10，距边 6，左右内边 7 |
| 格内播放钮 | 40 圆，仅当格子短边 ≥ 96 显示 |
| FAB | 48 圆，距右 16、底 24（加导航栏边衬） |
| 底部面板 | 顶部圆角 16；拖柄 36×4，距顶 8；标题区左右 20 |
| 弹出菜单 | 宽 236，圆角 10，行高 48（带副标题 56），图标 22，图标到文字 16 |
| 主按钮 | 高 50，圆角 12 |
| 输入框 | 高 52，圆角 12，聚焦描边 2（accentFill 偏亮 `#3D8FE6`） |
| 播放器进度条 | 触控高 40；轨道 3（拖动中 5）；滑块 14（拖动中 18）；左右 16 |
| 播放器控件行 | 48；播放钮 48 触控 / 26 图标 |
| 触控最小区域 | 48 × 48，所有图标按钮 |

### 3.4 形状与层级

- 只有三种圆角：10（菜单、角标外的小块）、12（按钮、输入框、提示条）、16（相册、面板）。角标与胶囊用全圆角。
- 深色主题不用阴影区分层级，靠 `bgSurface / bgRaised` 色阶；只有 FAB、弹出菜单、提示条有阴影（y=2–6，blur 8–24，α 0.3–0.45）。
- 浅色主题顶栏有 1dp 阴影；深色顶栏下用 1dp `#0B121A` 线。

### 3.5 动效

| 场景 | 规格 |
| --- | --- |
| 页面推入/返回 | 新页从右 100%→0，旧页 0→-25% 并压暗 15%，260 ms，`FastOutSlowInEasing`；返回反向 220 ms。系统预测性返回手势跟手 |
| 打开查看器 | 从被点击格子的屏幕位置放大到全屏，黑底透明度 0→1，240 ms（P1：Compose `SharedTransitionLayout`；P0 先用 180 ms 淡入 + 0.92→1 缩放） |
| 关闭查看器 | 缩回到当前视频在相册中的格子（若该格不在可见区则淡出），200 ms |
| 控件显隐 | 150 ms 淡入淡出；播放中 3 秒无操作隐藏（沿用现有无障碍推荐超时） |
| 底部面板 | 250 ms 上滑；遮罩 0→50%；下拉超过面板高 30% 或快速下滑关闭 |
| 浮动日期胶囊 | 开始滚动 150 ms 淡入；停止 1.2 s 后 200 ms 淡出 |
| 封面到达 | 0→1 透明度 120 ms；内存缓存命中不做动画（Coil `crossfade` 仅网络/磁盘来源） |
| 标签切换 | 下划线位移 200 ms；内容直接替换，不做横向滑动动画 |
| 系统关闭动画 | 读取 `Settings.Global.ANIMATOR_DURATION_SCALE`，为 0 时全部即时切换 |

禁止：循环闪光骨架、实时模糊、视差背景、列表项入场动画。

### 3.6 Compose 落地

```kotlin
// ui/theme/VVideoTheme.kt
@Immutable
data class VVColors(
    val bgPage: Color, val bgSurface: Color, val bgRaised: Color, val bgInput: Color,
    val bgPlaceholder: Color, val pinnedRow: Color, val divider: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color, val icon: Color,
    val accentText: Color, val accentFill: Color, val accentSoft: Color,
    val online: Color, val warn: Color, val error: Color,
    val datePill: Color, val datePillText: Color, val isDark: Boolean,
)

val NightBlue = VVColors(
    bgPage = Color(0xFF0E1621), bgSurface = Color(0xFF17212B), bgRaised = Color(0xFF1E2C3A),
    bgInput = Color(0xFF111A23), bgPlaceholder = Color(0xFF1A2531), pinnedRow = Color(0xFF1A2531),
    divider = Color(0xFF222F3C), textPrimary = Color(0xFFE9EEF3), textSecondary = Color(0xFF8B9BAB),
    textTertiary = Color(0xFF6A7B8C), icon = Color(0xFFAEBBC8), accentText = Color(0xFF6AB3F8),
    accentFill = Color(0xFF2575CC), accentSoft = Color(0xFF1D3550), online = Color(0xFF5BC88A),
    warn = Color(0xFFE8B04B), error = Color(0xFFEF6B6B),
    datePill = Color(0xEB1E2C3A), datePillText = Color(0xFFD3DEE8), isDark = true,
)

val Day = VVColors(
    bgPage = Color(0xFFE3EAF0), bgSurface = Color.White, bgRaised = Color.White,
    bgInput = Color(0xFFF4F6F8), bgPlaceholder = Color(0xFFD5DEE6), pinnedRow = Color(0xFFF6F8FA),
    divider = Color(0xFFE8EBEF), textPrimary = Color(0xFF15191E), textSecondary = Color(0xFF6B7682),
    textTertiary = Color(0xFF9AA4AE), icon = Color(0xFF5B6670), accentText = Color(0xFF2575CC),
    accentFill = Color(0xFF2575CC), accentSoft = Color(0xFFE3EFFB), online = Color(0xFF2E9E5E),
    warn = Color(0xFFB7791F), error = Color(0xFFD93F3F),
    datePill = Color(0x8C283E54), datePillText = Color.White, isDark = false,
)

val AvatarGradients: List<Brush> = listOf(
    0xFFF2836B to 0xFFD14C4C, 0xFFF5B256 to 0xFFE0782E, 0xFFA891F2 to 0xFF6A5BD6,
    0xFF86C566 to 0xFF3E9F4A, 0xFF4FCFC4 to 0xFF1E9C93, 0xFF5DB4EE to 0xFF2E7FCB,
    0xFFF287A6 to 0xFFC94F72,
).map { (top, bottom) -> Brush.verticalGradient(listOf(Color(top), Color(bottom))) }

val LocalVV = staticCompositionLocalOf { NightBlue }

enum class ThemeMode { System, Light, Dark }

@Composable
fun VVideoTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    val c = if (dark) NightBlue else Day
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accentFill, onPrimary = Color.White, primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accentText, background = c.bgPage, surface = c.bgSurface,
        onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary, surfaceVariant = c.bgPlaceholder,
        surfaceContainer = c.bgRaised, surfaceContainerHigh = c.bgRaised, surfaceContainerLow = c.bgSurface,
        outline = c.divider, outlineVariant = c.divider, error = c.error,
    )
    CompositionLocalProvider(LocalVV provides c) {
        MaterialTheme(colorScheme = scheme, typography = VVTypography, content = content)
    }
}
```

`AvatarGradients` 是顶层常量，`Brush` 只创建一次；行组件里不要每次重组新建渐变。

---

## 4. 页面规范

### 4.1 首页（画板 01 / 12）

**结构**：顶栏 → 媒体库（置顶）→ “最近浏览”分组标题 → 最近浏览列表。一个 `LazyColumn`。

- **顶栏**：左侧标题 `VVideo`（`titleAppBar`），右侧 ⚙ 设置。标题按真实连接状态替换，左侧加 16dp 转圈：
  - 正在请求 `/v1/libraries` 且无数据 → “连接中…”
  - 网络失败（`errorStatus == 0`）→ “等待网络…”，点标题重试
  - 401 → “凭据无效”，`warn` 色，点标题进入修改连接
  - 其他正常 → `VVideo`
- **媒体库行**（置顶）：底色 `pinnedRow`；字母头像 52；主标题库名；右上角置顶图标 16（`textTertiary`）；副标题前 7dp 圆点：
  - `healthy=true` → 绿点 + “在线”
  - `healthy=false` → 黄点 + “离线 · 显示保留的索引”，整行文字 `warn`
- **最近浏览行**：头像 + 文件夹名；右上角最近访问时间（`meta`，见 6.2 的时间格式）；副标题为路径“媒体库 › 父级”（只显示到父级，最多两段，超出前段省略）。
  - 数据来自本地 Room（见 7.1），最多 30 条，按 `visitedAt` 倒序。
  - 长按 → 底部面板“从最近浏览中移除”（只隐藏，不删除锚点）。
  - 离线媒体库下的文件夹仍可点进，进入后显示离线横幅和保留内容。
- **空状态**：没有最近浏览时不显示分组标题；没有媒体库时显示空状态“服务器上还没有配置媒体目录”。
- 只有一个媒体库、且从未浏览过时，也停在首页，不自动跳入（保持返回逻辑简单）。

### 4.2 文件夹页（画板 02 / 13）

**结构**：顶栏 + 标签栏 + 子文件夹条（固定在顶部，`bgSurface`）→ 相册流（`bgPage`）→ FAB。

**顶栏**：返回 · 字母头像 40 · 标题（`titleHeader`，单行省略）+ 副标题（`meta`，`textSecondary`）· ⋮。
- 副标题 = 父级路径“家庭影像 › 旅行”（来自 `state.path` 去掉自身）。媒体库根目录副标题为“媒体库”。
- 点标题区域打开“文件夹信息”面板（完整路径、当前排序、已加载的媒体数量不显示——没有可靠总数就不显示）。
- 目录离线（`healthy=false`）或请求失败时，顶栏下方插入离线横幅（组件 5.7），不遮挡内容。

**标签栏 全部 / 视频 / 照片**：
- 只有本层有媒体时显示；纯文件夹目录不显示。
- 切换 = 以新 `kind` 调用 `api.page(..., kind=)` 重新取首组（游标包含 kind，不能复用）。不持久化，进入文件夹总是“全部”。
- 切换后回到顶部，不尝试保持锚点；浏览书签只在“全部”下保存，避免过滤视图覆盖正常锚点。

**子文件夹条**（置顶消息样式）：
- 本层既有子文件夹又有媒体时显示。文案：第一行 `子文件夹 · N`（`accentText` 13 Medium，N 为已加载数量，存在下一页时写“N+”），第二行前三个名称用“、”连接。
- 点击打开子文件夹面板（4.5）。
- **只有子文件夹、没有媒体**：不显示条，正文直接是首页同款列表行（头像 52、行高 72），滚动到底自动 `moreChildren(false)`，到顶自动 `moreChildren(true)`，去掉“更多目录/前面的文件夹”按钮，改为列表边界的 24dp 转圈。

**相册流**：
- 每个十项组渲染为一个“相册气泡”：外轮廓 16dp 圆角裁切，内部格子 2dp 缝隙，格子本身不加圆角。行划分沿用 `AlbumLayoutCalculator`，宽度 = 屏宽 − 16。
- 角标：
  - 视频格左上：▶ + 时长（`m:ss`，≥1 小时 `h:mm:ss`；无时长时显示“…”而不是 0:00）。
  - 视频格中央 40dp 播放钮，仅当格子短边 ≥ 96dp。
  - 相册最后一格右下：该组第一项的时间 `HH:mm`（按名称排序时不显示）。
- **日期胶囊**：在相册前插入，当该相册第一项的日期与上一个相册第一项不同（按设备时区换算 `effectiveTime`；修改时间排序用 `mtime`；名称排序不插入）。文案见 6.2。
- **浮动日期胶囊**：滚动时在标签栏下方 8dp 居中显示当前首个可见相册的日期，规则见动效。
- **FAB 回到最新**：首个可见项超过第 3 个相册，或窗口前方还有未加载页（`window.previous` 非空）时出现。点击：窗口包含首页则 `animateScrollToItem(0)`；否则调用新的 `vm.jumpToStart()`（等价于用当前排序、无锚点重新 `open`）。
- **列表 key**：新增 `date:<相册首项 id>`、`subfolder-bar`、`folder:<id>`、`album:<首项 id>`。**删除 `albumStart()` 的下标推算**，恢复与触底判断全部按 key 查找（`keys.indexOf`），因为日期项数量会随窗口变化。

**点按与长按**：
- 点视频 → 播放器；点照片 → 照片查看器。
- 长按格子 → 底部面板：媒体详情 / 复制文件名（P1）。不做多选。

**加载/错误**：
- 首次加载：顶栏、标签栏照常显示；流区显示 2 个按 `displayRatio` 预留高度的占位相册（`bgPlaceholder` 静态色块），不显示文字“加载中”。
- 翻页：列表头/尾 24dp 转圈。
- 空目录：空状态组件（5.9）“这里还没有媒体”，按钮“刷新”。
- 锚点媒体已删除：提示条“原位置已不在，已定位到附近”（替代旧的列表内文字）。

### 4.3 ⋮ 菜单（画板 03）

从右上角锚定弹出（`DropdownMenu` 自定义外观：宽 236，圆角 10，`bgRaised`）：

1. **排序方式**（副标题显示当前值，如“拍摄时间 · 最新在前”）→ 排序面板
2. **回到最新** → 同 FAB
3. **刷新** → `open(id, refresh=true)`
4. **切换为网格视图**（P2，未实现前隐藏）
5. 分隔线
6. **文件夹信息** → 文件夹信息面板

### 4.4 排序面板（画板 04）

`ModalBottomSheet`。标题“排序方式”，副标题“只对‘<文件夹名>’生效，切换后回到开头”。

- 单选：拍摄 / 录制时间（副标题“缺失时使用文件修改时间”）、文件修改时间、文件名。行高 56/52，左侧 20dp 单选圈。
- “顺序”分组：分段控件，两段等宽，选中段 `accentSoft` 底 + 对勾。文案随依据变化：时间类为“最新在前 / 最早在前”，文件名为“A → Z / Z → A”（对应 `asc/desc`，注意文件名的 asc 才是 A→Z）。
- 点选即生效并关闭面板，调用现有 `vm.sort()`；不需要“确定”按钮。

### 4.5 子文件夹面板（画板 05）

`ModalBottomSheet`，初始高度 = 屏高 52%，可上拉到 90%。标题“子文件夹 N”，右上关闭。
- 行：头像 44 + 名称 + 右箭头，行高 64，分隔线从头像右侧开始。
- 面板内 `LazyColumn` 直接消费 `ChildrenWindow`，到边界自动加载（同 4.2 纯文件夹规则）。
- 底部说明：“子文件夹不会混进本层相册。”（只在列表不满一屏时显示）。

### 4.6 播放器（画板 06 / 07 / 08）

保留现有架构：单个 `PlayerView` Surface + `VerticalPager` 封面层。下面只描述外观与手势。

**控件显示（画板 06）**
- 顶部渐变：0→132dp，`#000` α 0.62→0。
- 顶栏（状态栏下）：返回 · 文件名（16 Medium，单行省略）+ 拍摄时间（13，白 72%，格式“2024年4月6日 18:42”）· ⋮。
- ⋮ 菜单：媒体详情、循环播放（勾选项，`REPEAT_MODE_ONE`）、复制文件名。
- 底部渐变：0→190dp。
- 进度条（自绘，不用 Material `Slider`）：底轨白 22%，已缓冲白 45%（`player.bufferedPosition`），已播放纯白，滑块 14 白色带阴影。拖动时轨道变 5、滑块 18，滑块上方 8dp 显示时间气泡“0:31”。拖动中禁用 pager 翻页（沿用 `dragging`）。
- 控件行：播放/暂停 · `0:12 / 0:42`（后半段白 60%）· 弹性空白 · 倍速按钮 · 横屏按钮。
  - 倍速按钮：描边胶囊，循环 1× → 1.5× → 2× → 0.5× → 1×，`PlaybackParameters(speed)`；切换视频后重置为 1×。
  - 横屏按钮：仅横向视频显示；点按设置 `SENSOR_LANDSCAPE`，横屏时禁用上下翻页，再点或返回恢复竖屏。

**控件隐藏（画板 07）**
- 只剩视频；底部 2dp 细进度线（白 85% / 底 20%），让用户不唤出控件也知道进度。

**手势**（叠加在 pager 上的 `pointerInput`，只用 `detectTapGestures`，不消费拖动，保证上下滑翻页仍然有效）

| 手势 | 行为 |
| --- | --- |
| 单击 | 显示/隐藏控件（因为要识别双击，会有约 250 ms 延迟，接受） |
| 双击左 1/3 | 后退 10 秒，左侧半椭圆白 13% 波纹 + “« 10 秒”；600 ms 内连续双击累加 20、30 秒 |
| 双击右 1/3 | 前进 10 秒，右侧对称 |
| 双击中间 | 播放/暂停 |
| 长按 | 按住期间 2× 播放，顶部居中显示“2× 快进中 ▶▶”胶囊；松手恢复原速度 |
| 上下滑 | 切换上一/下一个视频（现有） |
| 第一个视频再下拉 / 最后一个再上滑 | 回弹；到末尾时提示条“已经是最后一个视频” |

**暂停（画板 08）**：画面中央 64dp 圆形播放钮（黑 45%）。
**缓冲**：中央 48dp 白色转圈，放在 64dp 黑 45% 圆底里；不和播放钮同时出现。
**失败**：中央内容块（组件 5.9 的深色版）：图标 + “这台手机无法解码此视频” / “无法播放这个视频” + 原因一行（编码信息可从 `playerError` 取）+ 次按钮“重试”（不支持格式时不显示重试，改为“查看详情”）。

**媒体详情面板（画板 08）**：替换现在的 `AlertDialog`。`viewerSheet` 底色。每行：24dp 图标 · 值（16）+ 标签（13，白 55%），行最小 64。
1. 文件名（右侧复制按钮）
2. 分辨率 · 时长 · 大小（旋转非 0 时追加“旋转 90°”）
3. 拍摄时间（标签写来源：“来自 EXIF 原始拍摄时间 / 容器创建时间 / 文件修改时间”；容器时间时值下方加一行 `warn` 色说明“也可能是导出或编辑时间”）
4. 原始时间字段（仅 `capturedRaw` 非空时）
5. 所在文件夹（完整路径）

### 4.7 照片查看器

与播放器共用顶栏（返回、文件名、时间、⋮），无底部控件。
- 双击：1× ↔ 2.5×，以点按位置为中心；捏合 1–4×；放大时单指拖动平移。
- 单击显示/隐藏顶栏。
- 加载中：先显示已缓存的缩略图（放大到屏宽，与列表同一 Coil key），原图到达后替换，中间不出现黑屏转圈。
- 失败：同播放器失败块，按钮“重试”。

### 4.8 首次连接 / 修改连接（画板 09）

- 首次：无顶栏；上方 104dp 圆形品牌标（蓝色渐变 + 白色播放三角），标题“连接你的媒体服务”，说明两行。
- 修改：顶栏“修改连接”带返回，表单预填，按钮文案“保存并重新连接”。
- 字段：标签在框外上方（13 Medium，聚焦时 `accentText`），框高 52。地址键盘 `KeyboardType.Uri`；令牌为密码框，右侧眼睛图标切换明文。
- 帮助文字：“HTTPS 地址可直接使用；HTTP 只允许本版本构建时指定的内网服务器。”
- 错误贴在对应字段下（`error` 色 13）：地址格式不允许 → 地址字段；401 → 令牌字段“令牌不正确”；不可达 → 地址字段“无法连接到这个地址”。
- 主按钮贴底（加导航栏和输入法边衬，`imePadding()`）；连接中按钮内显示 20dp 白色转圈并禁用输入。

### 4.9 设置（画板 10）

`bgPage` 底，分组 `bgSurface`，组间 12dp。
- **头部**：64dp 服务器头像（蓝色渐变 + 服务器图标）· 主机名（18 Medium）· 状态行（圆点 + 文案：已连接 · N 个媒体库 / 连接中… / 不可达 / 凭据无效 / 已保存 · 未验证）。
- **连接**：服务地址（右侧 `host:port`）→ 修改连接；访问令牌（右侧 `••••••` + 末 4 位）→ 修改连接；重新检测连接（点后头部状态变“连接中…”）。
- **存储**：存储用量（右侧“2.31 GB / 5 GB”）→ 存储页。
- **显示**：主题（右侧当前值，`accentText`）→ 单选对话框“跟随系统 / 浅色 / 深色”；文件夹默认视图（P2，网格未做前隐藏）。
- 底部居中小字：“VVideo <versionName> · 仅限局域网”。
- 行：高 52，左侧 24dp 线性图标（`textSecondary`），标题 16，右侧值 15 `textSecondary`。

### 4.10 存储用量（画板 11）

- **环形图**：直径 176，环宽 18，段间 2° 缝（填 `bgSurface`），每段最小 3° 以便可见。中央：已用总量（26 Medium tnum）+ “上限 5 GB”。用 `Canvas.drawArc` 自绘。
- 图例三行：视频片段 `#4FA3F7`、图片与封面 `#F2A541`、索引与管理开销 `#8E9AA6`；每行：12dp 色点 · 名称 · 百分比 · 容量（右对齐 72dp）。
- 统计中：环形图显示为灰色整环，中心“正在计算…”，图例数值为“—”。
- **清除缓存 · 2.31 GB**：主按钮。点击弹出确认面板“清除 2.31 GB 缓存？只删除本机缓存，原件、连接与浏览位置都会保留。”；正在播放时补一句“播放会先暂停”。执行中按钮显示转圈“正在清理…”；完成后提示条“缓存已清理，释放 X”。
- **缓存上限**：三档离散滑杆 1 GB / 5 GB / 10 GB（等距），选中档文字 `accentText`。调低到小于已用量时，滑杆下方说明改为“超出部分会在后台回收”。
- 低存储空间：在环形图上方插入 `warn` 横幅“手机可用空间不足，已停止预加载”。

---

## 5. 组件规范（画板 14）

| # | 组件 | 规格 | 建议位置 |
| --- | --- | --- | --- |
| 5.1 | `LetterAvatar(id, name, size)` | 圆形渐变 + 白字（字号 = 尺寸 × 0.4，Medium）；算法见 3.1 | `ui/component/`（首页、文件夹、面板复用） |
| 5.2 | `MediaBadge` | 高 20、圆角 10、`overlayBadge` 底、白字 12 Medium tnum；视频前缀 10dp 实心三角 | `feature/library/` |
| 5.3 | `DatePill` | 内边 3×10，圆角 12，`datePill` 底，13 Medium；浮动版同样式 | `feature/library/` |
| 5.4 | `MediaTabs` | 见 3.3；选中 `accentText` + 下划线，未选 `textSecondary` | `feature/library/` |
| 5.5 | `VVButton` 主/次/文字 | 主：`accentFill` 底白字；次：`accentSoft` 底 `accentText` 字；文字危险：`error` | `ui/component/` |
| 5.6 | `Bulletin`（提示条） | 底部距导航栏 16，左右 8；高 ≥52，圆角 12，深色 `#2A3542` / 浅色 `#2C3640`，白字 14；左侧 22dp 状态图标，右侧可选文字按钮（`accentText`）；3 秒自动消失，有按钮时 5 秒 | `ui/component/` + 每个 Screen 一个 host |
| 5.7 | `OfflineBanner` | 最小高 44，`warn` 色系：深色底 `#3A2F17` 字 `#F1D59C`，浅色底 `#FFF4DE` 字 `#7A4E00`；图标 + 文案 + “重试” | `ui/component/` |
| 5.8 | `CoverPlaceholder` | 状态：生成中（时钟图标 + “生成中”）、失败可重试（刷新图标 + “点按重试”）、不支持（划线图标 + “不支持”）；格子太小（短边 < 72）只显示图标 | `feature/library/` |
| 5.9 | `EmptyState` | 72dp 圆形图标底（`bgRaised`）· 标题 16 Medium · 说明 14 `textSecondary` · 可选次按钮；整体居中，最大宽 280 | `ui/component/` |
| 5.10 | `SettingsRow` / `SectionHeader` | 见 4.9 | `feature/settings/`（只在设置用） |
| 5.11 | `VVBottomSheet` | Material3 `ModalBottomSheet` + 自定义拖柄/圆角/底色；面板标题 18 Medium | `ui/component/` |
| 5.12 | `SeekBar` | 见 4.6；`semantics { progressBarRangeInfo; setProgress {} }` 支持 TalkBack 调节 | `feature/player/` |

图标：全部用 24dp 线性矢量（描边 1.8–2，圆头），放 `res/drawable/ic_*.xml`；需要新增：pin、sort、refresh、grid、info、copy、clock、key、globe、disk、moon、eye、fullscreen、close、arrow_up、check、server、wifi_off、video_off、image_off。画板里的 SVG 路径可直接转 VectorDrawable。

---

## 6. 文案与格式

### 6.1 新增/修改的字符串（`res/values/strings.xml`）

| key | 文案 |
| --- | --- |
| `home_title` | VVideo |
| `status_connecting` | 连接中… |
| `status_waiting_network` | 等待网络… |
| `status_bad_token` | 凭据无效 |
| `recent` | 最近浏览 |
| `library_online` | 在线 |
| `library_offline` | 离线 · 显示保留的索引 |
| `remove_from_recent` | 从最近浏览中移除 |
| `tab_all` / `tab_video` / `tab_photo` | 全部 / 视频 / 照片 |
| `subfolders_count` | 子文件夹 · %1$s |
| `sort_title` | 排序方式 |
| `sort_scope` | 只对“%1$s”生效，切换后回到开头 |
| `sort_capture_hint` | 缺失时使用文件修改时间 |
| `order` | 顺序 |
| `newest_first` / `oldest_first` | 最新在前 / 最早在前 |
| `name_asc` / `name_desc` | A → Z / Z → A |
| `jump_to_start` | 回到最新（名称排序时为“回到开头”） |
| `folder_info` | 文件夹信息 |
| `empty_media` | 这里还没有媒体 |
| `anchor_moved` | 原位置已不在，已定位到附近 |
| `offline_banner` | 服务器不可达，显示的是已加载内容 |
| `last_video` | 已经是最后一个视频 |
| `seek_back` / `seek_forward` | %1$d 秒 |
| `speed_hold` | 2× 快进中 |
| `loop` | 循环播放 |
| `copy_name` / `copied` | 复制文件名 / 已复制 |
| `cannot_decode` | 这台手机无法解码此视频 |
| `connect_title` | 连接你的媒体服务 |
| `connect_body` | 填写 NAS 上 vvideo 服务的地址和访问令牌。媒体只在局域网内传输。 |
| `connect` / `save_reconnect` | 连接 / 保存并重新连接 |
| `token_wrong` | 令牌不正确 |
| `address_unreachable` | 无法连接到这个地址 |
| `storage_usage` | 存储用量 |
| `cache_video_segments` / `cache_images` / `cache_index` | 视频片段 / 图片与封面 / 索引与管理开销 |
| `clear_cache_amount` | 清除缓存 · %1$s |
| `clear_confirm` | 清除 %1$s 缓存？只删除本机缓存，原件、连接与浏览位置都会保留。 |
| `cache_limit` | 缓存上限 |
| `cache_limit_note` | 上限是磁盘预算，不会预先占用空间。手机可用空间不足时会自动停止预加载。 |
| `theme` / `theme_system` / `theme_light` / `theme_dark` | 主题 / 跟随系统 / 浅色 / 深色 |

旧字符串里偏技术化的长句（如 `offline`、`server_update_required`、`memory_note`）保留给详情/帮助，不再直接铺在主界面。

### 6.2 时间格式（统一在一个 `TimeLabels.kt` 纯函数里，写单元测试）

| 场景 | 今天 | 昨天 | 7 天内 | 今年 | 更早 |
| --- | --- | --- | --- | --- | --- |
| 首页最近浏览 | `14:22` | 昨天 | 周三 | 10月3日 | 2023/10/3 |
| 日期胶囊 | 今天 | 昨天 | 4月6日 周六 | 4月6日 周六 | 2024年4月6日 周六 |
| 相册时间角标 | `18:42`（始终只有时分） | | | | |
| 播放器副标题 / 详情 | `2024年4月6日 18:42`（详情带秒） | | | | |

时区：`effectiveTime` 是纳秒时间戳，按设备时区 `ZoneId.systemDefault()` 显示。

---

## 7. 数据需求与优先级

**P0（只改安卓，不动接口）**：主题与令牌、首页样式、文件夹顶栏/子文件夹条/面板、相册气泡与角标、日期胶囊、FAB、排序面板、播放器外观与手势、详情面板、设置/存储页重排、首次连接表单。

**P1（安卓本地改动）**

1. **最近浏览**：`BookmarkEntity` 增加 `name TEXT NOT NULL DEFAULT ''`、`pathLabel TEXT NOT NULL DEFAULT ''`、`visitedAt INTEGER NOT NULL DEFAULT 0`、`hiddenFromRecent INTEGER NOT NULL DEFAULT 0`；`BrowseDatabase` 版本 1→2，写 `Migration(1,2)`（`ALTER TABLE ... ADD COLUMN`），导出新 schema。`visitedAt` 只在 `open()` 成功时写，不在滚动保存时改（`savedAt` 继续给锚点用）。DAO 新增：
   ```kotlin
   @Query("SELECT * FROM bookmarks WHERE connection=:c AND hiddenFromRecent=0 AND name!='' ORDER BY visitedAt DESC LIMIT 30")
   fun recent(c: String): Flow<List<BookmarkEntity>>
   ```
2. **主题模式**：`SettingsRepository` 增加 DataStore 键 `theme`（system/light/dark），`MainActivity` 读取后传给 `VVideoTheme`；系统栏图标明暗跟随实际主题与路由。
3. **媒体类型标签**：`LibraryViewModel` 增加 `kind` 状态，`open/page` 透传。
4. **打开查看器的共享元素动画**。

**P2（需要服务端或更大改动，可不做）**

- 目录媒体总数（`/children` 返回 `media_count`），用于首页/面板副标题“1,284 个视频”和顶栏副标题。
- “自上次访问后的新增数”——首页像未读数一样的蓝色计数胶囊。需要服务端按 `effective_time > since` 计数的接口，并评估查询成本（遵守 PERF 规则）。
- 网格视图（Telegram 共享媒体样式：3 列正方形、1dp 缝、按月吸顶标题、捏合 3↔5 列）。

---

## 8. 性能约束（必须遵守）

- 日期胶囊在 `albums()` 计算时一起在 `Dispatchers.Default` 生成（`Album` 增加 `dateLabel: String?`、`timeLabel: String?`），UI 不在滚动中做日期计算。窗口前插/后追加时整体重算（窗口有界，成本可控）。
- 浮动日期胶囊用 `derivedStateOf { firstVisibleItemIndex }` 读取，避免每帧重组整个列表。
- 头像渐变、角标背景色、`RoundedCornerShape` 都是顶层常量。
- 封面占位是纯色，无动画骨架；`crossfade` 只对非内存来源启用。
- 播放器手势层不新建播放器、不重建 `PlayerView`；倍速/循环只改同一个 `ExoPlayer` 的参数。进度条只在 `SeekBar` 子树内每 250ms 读位置（沿用现有做法），缓冲进度同一循环里读取。
- 底部面板里的子文件夹列表和主列表共用 `ChildrenWindow`，不复制第二份数据。
- 改版后按 [DESIGN.md 性能验收计划](../DESIGN.md#性能验收计划) 在 iQOO 12 Pro profile 构建上重跑相册滚动与全屏翻页。新增的日期项和角标是重点怀疑对象。

---

## 9. 实施顺序与文件落点

| 步骤 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `ui/theme/VVideoTheme.kt` | 换成 3.6 的 `VVColors` / `VVTypography` / `VVDimens`；删除绿色 scheme |
| 2 | `app/MainActivity.kt`、`app/navigation/VVideoRoot.kt` | 主题模式、系统栏、路由 `storage`/`connection`、页面切换动画（`AnimatedContent`） |
| 3 | `ui/component/` | `LetterAvatar`、`VVButton`、`Bulletin`、`OfflineBanner`、`EmptyState`、`VVBottomSheet` |
| 4 | `feature/library/` | 把 `LibraryScreen.kt` 拆成 `HomeScreen.kt`、`FolderScreen.kt`、`AlbumBubble.kt`、`FolderSheets.kt`（排序/子文件夹/信息）、`TimeLabels.kt`；`LibraryViewModel` 增加 `kind`、`jumpToStart()`、`Album.dateLabel/timeLabel`；列表 key 化 |
| 5 | `data/library/local/Bookmarks.kt` | v2 迁移、`recent()`、`hide()` |
| 6 | `feature/player/` | `PlayerChrome.kt`（顶栏/底栏）、`SeekBar.kt`、`GestureLayer.kt`；`MediaDetails.kt` 改为面板；`PhotoScreen` 双击缩放 |
| 7 | `feature/settings/` | `SettingsScreen.kt` 重排、新建 `StorageScreen.kt`、`ConnectionForm.kt`（首次与修改共用） |
| 8 | `res/` | 字符串、矢量图标、`themes.xml` 窗口底色改为 `#0E1621`/`#FFFFFF` 避免启动白闪 |
| 9 | 测试 | `TimeLabels`、日期分隔算法、字母头像取字、列表 key ↔ 锚点映射的单元测试；`UiRedesignDeviceTest` 更新截图路径 |

建议节奏：1→2→3→4 先让“首页 + 文件夹”成型并真机跑一次滚动性能，再做 6 播放器，最后 7 设置。

---

## 10. 验收清单

**视觉**（逐张对照画板，深浅两套）
- [ ] 首页置顶媒体库、最近浏览、离线库黄色副标题
- [ ] 文件夹顶栏头像/标题/路径，标签栏下划线，子文件夹条
- [ ] 相册外圆角 16、缝 2、时长左上、时间右下、播放钮阈值
- [ ] 日期胶囊与浮动日期；名称排序时不出现
- [ ] FAB 出现/隐藏条件
- [ ] 菜单、排序面板、子文件夹面板、详情面板样式一致（拖柄、圆角、标题）
- [ ] 播放器顶/底渐变、细进度条、倍速、横屏按钮、暂停大按钮、缓冲、失败态
- [ ] 设置分组、存储环形图、三档滑杆、首次连接表单错误位置
- [ ] 大字体（系统 1.3×）下没有文字被裁切，角标不换行

**行为**
- [ ] 返回链：面板 → 播放器 → 子目录 → 根目录 → 首页 → 退出
- [ ] 标签切换后分页、锚点、播放队列正确；回到“全部”恢复正常书签
- [ ] 双击 ±10 秒可累加；长按 2× 松手恢复；拖进度时不翻页；单击切换控件
- [ ] 横屏按钮只对横向视频出现，横屏时不能上下翻页
- [ ] 最近浏览：打开文件夹后出现在首页顶部；长按移除；升级安装后旧书签不崩溃（迁移）
- [ ] 主题切换立即生效，系统栏图标明暗正确
- [ ] 清除缓存确认、进行中、完成提示；播放中清理会先暂停

**性能**：按第 8 节在 profile 构建重测，结果写入 `docs/ACCEPTANCE.md`，未测项注明。
