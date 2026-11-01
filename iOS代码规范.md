# iOS 代码规范

基线版本：1.0.0。适用范围：Swift 编写、Xcode 构建的原生 iOS / iPadOS 应用及其本地 Swift 包。先读 [代码规范.md](代码规范.md)。目录、target 划分、依赖方向、文件命名、`body` 内不做 I/O、主 actor 修改界面状态、Keychain / UserDefaults 的用途划分、依赖管理方式与测试位置见 [iOS目录规范.md](iOS目录规范.md)，本文不重复，只补充代码写法。

## 1. 语言与工具

- 新项目的部署目标为 iOS 18 及以上，使用 Xcode 26 及以上（Swift 6.2）。部署目标更低的项目按本文标注的降级写法执行，并在 README 写明。
- 所有 target 与本地包使用 Swift 6 语言模式和完整的严格并发检查。
- 应用 target 固定设置 `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` 与 `SWIFT_APPROACHABLE_CONCURRENCY = YES`。本地包在 `Package.swift`（tools 6.2）中给每个 target 声明 `swiftSettings`，使并发语义与应用 target 一致：放界面代码的包用 `.defaultIsolation(MainActor.self)`，其余包用 `.defaultIsolation(nil)` 并在包说明中写明；两类都加 `.enableUpcomingFeature("NonisolatedNonsendingByDefault")` 与 `.enableUpcomingFeature("InferIsolatedConformances")`。
- 构建设置只写在 `Configs/*.xcconfig` 中；不同 target 的差异放在各自的 `Configs/<Target>.xcconfig`，不在 `project.pbxproj` 中直接覆盖。UI 测试 target 可以使用 `nonisolated` 默认隔离。
- 格式化与检查使用工具链自带的 `swift format`。`ios/.swift-format` 先用 `swift format dump-configuration > ios/.swift-format` 生成完整配置，再把 `indentation.spaces` 改为 4、`lineLength` 改为 120，并把 `rules` 中的 `NeverForceUnwrap`、`NeverUseForceTry`、`NeverUseImplicitlyUnwrappedOptionals` 设为 `true`，其余规则保留生成值。禁止只写部分规则：未列出的规则会全部关闭。

  CI 只对项目自己的源码目录运行，不扫描构建目录和依赖检出：`swift format lint --strict --parallel --recursive ios/<App> ios/<App>Tests ios/<App>UITests ios/Packages/*/Sources ios/Packages/*/Tests`（按实际存在的目录列出）。
- 新增依赖按 [代码规范.md](代码规范.md) 第 4 节执行，版本精确固定。

## 2. 命名与代码组织

- 遵守 Swift API Design Guidelines：缩写整体大小写一致（`userID`、`indexURL`、`UIState`），不写 `UiState`、`Id`。
- 访问控制取最小；本地包只把调用方需要的接口标为 `public`。不需要继承的类标 `final`。
- 平台之间共享的是行为契约（接口、缓存键、错误码），不是代码惯用法；不照搬其他平台的命名或结构，注释中不写“与 Android 相同”之类的对照，需要对齐的约定写进接口说明。

## 3. UI

- 新页面默认使用 SwiftUI。高频滚动、复杂手势、媒体播放等经测量 SwiftUI 无法满足的界面使用 UIKit，取舍记录为技术决策（见 [代码规范.md](代码规范.md) 第 9 节）；两者通过 `UIHostingController` / `UIViewRepresentable` 嵌入。
- UIKit 列表使用 Diffable Data Source 与 `CellRegistration`；禁止字符串 reuse identifier 加 `as!` 的写法。
- 代码创建的 UIKit 视图把 `init(coder:)` 标为 `@available(*, unavailable)`。
- 本地化 key 使用源语言（zh-Hans）原文字面量，以便 String Catalog 抽取：SwiftUI 直接写 `Text("设置")`、`Button("重试")`；非视图代码用 `String(localized: "设置")`；本地包传 `bundle: .module`。不用变量作为 key，不自建 `L("key")` 一类包装；带参数的文本用字符串插值，不用 `String(format:)`。
- 无障碍：图标按钮设置 `accessibilityLabel`；文字使用系统文本样式以支持动态字体；UI 测试定位使用 `accessibilityIdentifier`，不依赖界面文案。

## 4. 状态与观察

- 可观察状态只用 Observation（`@Observable`）；新模块不使用 `ObservableObject`、`@Published` 和 Combine。
- 视图模型运行在主 actor 上，对外暴露只读状态与操作方法。频繁变化的状态（播放进度、下载进度）单独建模，避免整页重算。
- UIKit 观察 `@Observable` 对象：系统支持自动观察追踪（`updateProperties()` 等）时使用系统机制；否则使用应用内唯一的观察辅助方法，不在每个控制器手写 `withObservationTracking` 的重新注册逻辑。

## 5. 并发

- 需要离开调用方 actor 的耗时工作（解码、文件读写、哈希）标 `@concurrent`，或放进独立的 actor。在上述设置下 `nonisolated async` 函数仍在调用方的 actor 上运行，不能用它把工作移出主线程。
- 共享可变状态优先使用 actor；需要同步访问时使用 `Synchronization.Mutex`（部署目标低于 iOS 18 时用 `OSAllocatedUnfairLock`）。`DispatchQueue` 只用于桥接 C 接口或回调式 API，并封装在一个类型内。每个类型只使用一种同步机制。
- 每处 `@unchecked Sendable`、`nonisolated(unsafe)` 必须在声明处注释说明保证线程安全的不变量。
- 不使用 `Task.detached`，除非需要脱离当前优先级和任务本地值，并写明原因。创建的 `Task` 要保存句柄，在所有者释放或页面消失时取消。
- 在网络边界把 `URLError.cancelled` 转为 `CancellationError`；调用方不把取消当作失败展示。

## 6. 错误处理

- 生产代码禁止强制解包 `!`、`as!`、`try!` 与隐式解包可选类型；`@IBOutlet` 除外。字面量 URL 等编译期可确定成立的值写成 `guard let url = URL(string: "…") else { preconditionFailure("invalid literal URL") }`。
- 错误使用具体的 `Error` 类型（如 `enum APIError: Error`），保留底层原因；不把所有失败折叠成同一个错误。
- `try?` 只用于确实可以丢弃结果的尽力而为操作，并注释原因；持久化、网络等失败必须记录日志或向上抛出。
- `fatalError` / `precondition` 只用于程序员错误。

## 7. 网络与序列化

使用网络的应用：

- 每个服务端连接使用一个配置好的 `URLSession`，同时设置 `timeoutIntervalForRequest` 与 `timeoutIntervalForResource`；鉴权头、重定向策略集中定义，携带鉴权信息的请求不跟随重定向。
- JSON 响应用 `bytes(for:)` 读取并在读取中检查大小上限；大文件下载使用下载任务，不读入内存。
- AVFoundation 播放需要鉴权的媒体时，通过 `AVAssetResourceLoaderDelegate` 或本地回环代理注入 `Authorization` 头；禁止把令牌放进媒体 URL。
- JSON 使用 `Codable`，字段映射一律写显式 `CodingKeys`（`case userID = "user_id"`）；禁止 `.convertFromSnakeCase`（它会把 `user_id` 转成 `userId`，与第 2 节命名冲突）。未知字段忽略；枚举实现 `init(from:)` 把未知值解码为 `.unknown`。时间字段按 [代码规范.md](代码规范.md) 第 5.2 节的固定毫秒格式解析（`ISO8601DateFormatter` 加 `.withFractionalSeconds`，或 `Date.ISO8601FormatStyle(includingFractionalSeconds: true)`）。
- 服务端错误按 [代码规范.md](代码规范.md) 第 5.3 节的 `error.code` 解码为领域错误，保留 `X-Request-ID`。

## 8. 持久化与秘密

- Keychain 新增与更新条目都必须设置可访问性（通常为 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`）。
- `UserDefaults` 的 key 集中定义为常量，不散落字符串字面量。
- 本地数据库选型（SwiftData、GRDB、SQLite C API）记录为技术决策。发布前允许破坏式重建 schema 并写明；发布后必须提供迁移并测试。
- App Transport Security 不全局放开（`NSAllowsArbitraryLoads`）；确需访问明文服务时使用最小范围的例外（如 `NSAllowsLocalNetworking`），并记录原因。
- 处理深链与 Universal Link 时校验全部参数，不信任外部传入的路径和 ID。

## 9. 日志

- 使用 `os.Logger(subsystem: <bundle id>, category: <模块>)`，每个模块一个 `Logger` 常量；不用 `print` 输出运行日志。
- 消息格式与 [代码规范.md](代码规范.md) 第 3.5 节一致：`logger.info("scan_failed library_id=\(id, privacy: .public)")`。字符串插值默认不公开，确认不敏感的值才标 `.public`；敏感的数值显式标 `.private`。

## 10. 测试

- 单元测试与本地包测试使用 Swift Testing（`@Test`、`#expect`），用 `try #require` 代替强制解包；UI 测试与性能测试使用 XCTest。
- 等待异步结果使用 Swift Testing 的 `confirmation` 或注入的 `Clock`，不用 `Task.sleep`。
- 视图模型的状态转换、错误映射、取消处理必须有单元测试；依赖通过协议或闭包注入，用假实现替代网络与存储。
