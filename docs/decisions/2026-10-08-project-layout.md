# 项目目录决策：根 Go 服务与 Android 客户端

日期：2026-10-08。状态：本次目录规范交付采用的施工约定；目录按实际代码逐步创建。

## 背景

用户要求 Go 按根 [目录规范.md](../../目录规范.md) 施工，并授权制定 Android 目录规范。该基线 1.0.0 已覆盖根 Go 后端，但没有原生 Android 项目类型。本决策制定时工作区只有项目文档。2026-10-09 已实施根 Go / Android 工程；当前仍无 Git 仓库。

## 决定

1. Go 服务应用根保持在仓库根，遵守基线第 2、6、7、8 节；不增加 `server/`、`backend/` 或 Go 的 `src/`。
2. 原生 Android 工程根为 `android/`，规则以根 [安卓目录规范.md](../../安卓目录规范.md) 为准。这是本项目明确、有限的类型扩展；不据此允许其他未列出的顶层应用目录。
3. 当前不启用基线第 10 节的 `apps/` 多应用布局，也不创建 `packages/`。Go 与 Android 通过 HTTP 协议协作，互不导入对方源码。
4. 英文入口为 `README.md`，中文入口为 `README.zh-CN.md`，内容保持语义一致。根 `AGENTS.md` 引用基线和项目补充，不复制基线全文。
5. 现有当前设计由 `docs/plans/2026-10-08-media-library-design.md` 迁到 `docs/DESIGN.md`，符合基线第 9 节。旧路径只在本条迁移记录中出现，不保留副本。后续可执行实施计划才放 `docs/plans/`。

## 目录例外与平台映射

以下表格是项目例外的单一记录，两个 README 均引用本表。没有列出的部分继续遵循通用基线。

| 规则 / 标准路径 | 实际路径或行为 | 原因与依据 | 适用范围 | 重新评估条件 |
| --- | --- | --- | --- | --- |
| 第 2、4 节未覆盖原生移动客户端 | 根 `android/` 是 Android 应用与 Gradle 工程边界 | 用户明确委托制定安卓规范；服务端与客户端构建工具和运行环境独立 | 本项目 Android 工程 | 引入独立发布的多个客户端或用户要求重组仓库 |
| 第 3 节根入口文档清单 | 增加根 `安卓目录规范.md` 与 `README.zh-CN.md` | 用户明确要求安卓规范及中英文 README | 项目文档 | 文档入口或语言策略调整 |
| 第 4、6 节通用源码和测试位置 | `android/<module>/src/main/kotlin/`、`src/test/kotlin/`、`src/androidTest/kotlin/`；资源和 Manifest 使用相应 source set | 本项目选择 Android Gradle Plugin 支持的标准 source set；测试依赖及运行环境由 source set 区分，依据见安卓规范 | Android Gradle 模块 | 更换构建系统或 source set 配置 |
| 第 4 节非 Go 发布输出与测试报告 | Android 输出保留在所属 Gradle 工程 / 模块的 `build/`，不另拷源码或构建目录 | 使用 Gradle/AGP 的默认输出和报告发现机制；不声称路径不可配置 | Android 构建及报告 | 实际工具配置更改输出位置 |
| 第 7 节运行配置、`data/` 与 `.cache/` | 手机上使用 Android 应用私有持久目录、数据库、DataStore 与缓存目录；不创建源码树内的手机状态副本 | 应用沙箱和平台存储 API；可回收媒体缓存与持久配置/历史分开 | Android 安装后的运行状态 | 存储模式、离线下载功能或系统备份策略改变 |
| 第 7、8 节依赖工具和本地配置 | `android/gradle/`、Gradle Wrapper、版本目录；忽略 `android/.gradle/`、`android/.kotlin/`、`android/local.properties` 及适用的本地 IDE 配置 | Kotlin DSL / Gradle 的项目配置方式；本机 SDK 路径和缓存不进入版本控制 | Android 工程 | 工具链或团队 IDE 策略改变 |
| 第 4 节数据库迁移默认目录 | 初始化 SQL 作为常量内置于 `internal/storage/schema.go`，不创建独立迁移目录或包 | 用户于2026-10-09明确要求；SQL与SQLite初始化职责共置，编入二进制，保留事务与数据库版本检查 | 本项目Go数据库schema；不改变通用目录基线 | schema版本或用户要求调整 |

## 取舍

Go 保持用户基线，Android 有独立且唯一的工具根，不需要在当前阶段引入整套多应用工作区工具。代价是 Android 需要上述有限补充；未来调整布局时应更新本决策和全部有效引用，而非并存两套源码。

Android 初期仅在真实实现时创建 `:app` 和 `:core:media` 两个生产模块；功能先在 `:app` 内按包分区。播放器模块是本产品已有明确职责的隔离边界，不因参考大型开源项目而预建十几个 Gradle 模块。性能测试开始时再创建 `:benchmark`。

## 施工预期

以下说明施工归属；实际源码按职责创建：

```text
<repo>/
├── go.mod                    # Go 初始化时创建
├── cmd/vvideo/main.go         # 服务入口与退出管理
├── internal/
│   ├── app/                  # HTTP 应用装配
│   ├── config/               # 配置加载代码
│   ├── storage/              # SQLite连接、事务与内置schema SQL
│   ├── library/              # 媒体、目录与索引领域
│   ├── scanner/              # 遍历、变更检测和扫描状态
│   ├── thumbnail/            # 抽帧规则与任务执行
│   ├── streaming/            # Range 播放及索引范围
│   └── jobs/                 # 持久队列与受控调度，有真实公共需求时创建
├── configs/                  # 服务端运行配置示例；不放 Go 配置代码
├── tests/                    # Go 跨包集成与协议测试
├── android/                  # 按安卓目录规范
├── docs/                     # 当前设计、项目决策和后续实施计划
├── scripts/                  # 仅承载实际复用的仓库操作逻辑
└── deploy/                   # 实际采用的部署定义
```

Go 领域内部的细分按真实依赖确定，不同时建立另一套全局 controller/service/repository 分类。SQLite 接入按其实际使用边界安置，不因为目录树没有列出就混入入口文件。

2026-10-08 只交付文档；2026-10-09 首版实现按上述归属施工。

2026-10-09按用户要求将初始化SQL收进`internal/storage/schema.go`，移除独立迁移资源包；SQL、schema版本与已有数据库兼容行为保持不变。
