# iOS 客户端决策

日期：2026-10-22。状态：已撤回。2026-10-26 用户决定暂不做 iOS 端，`ios/` 工程已删除；`iOS代码规范.md` 与 `iOS目录规范.md` 保留，以后重新开始 iOS 端时再按新决策施工。下文保留当时的决策记录。

## 背景

[当前设计](../DESIGN.md)原先写明“首先支持 Android，iOS 待后续决定”，根 `AGENTS.md` 和 README 把 iOS 列为本版范围之外。2026-10-22 用户要求按安卓应用的功能和界面同步编写 iOS 应用，并确定：

- 播放引擎：系统 AVPlayer 与 FFmpeg 引擎并用；
- 测试设备：用户的 iPhone Pro（120 Hz ProMotion）；
- 最低系统：iOS 18；
- 交付节奏：一次对齐安卓的全部功能，不分阶段交付。

本决策把 iOS 纳入范围。服务端 API、目录规则和性能门槛不因客户端增加而改变。

## 决定

1. **工程位置与目录。** iOS 应用根为仓库根 `ios/`，按根 [iOS目录规范.md](../../iOS目录规范.md) 施工：提交 Xcode 工程（与磁盘同步的文件夹），主应用 target 按 `App/`、`Features/`、`Data/`、`Models/`、`UI/`、`Core/`、`Resources/` 划分。媒体引擎拆为本地 Swift 包 `ios/Packages/MediaEngine/`，与安卓 `:core:media` 对应：它有独立依赖（libmpv / FFmpeg）、C 接口和独立测试，应用代码只通过其公开接口播放和缓存。
2. **界面技术。** 相册列表、视频流、照片查看等滚动与手势密集的页面使用 UIKit（`UICollectionView`、`UIScrollView`），以便精确控制滚动位置恢复、分页锚点、预取和手势冲突；设置、连接表单、存储页和各类底部面板使用 SwiftUI。两者都在主线程之外完成网络、文件和布局计算。
3. **双播放引擎。** MP4 / MOV / M4V 默认交给 AVPlayer（硬件解码、系统集成最好）；MKV、WebM、TS、M2TS、AVI 以及 AVPlayer 报告无法解码的文件交给基于 libmpv 的引擎（FFmpeg 解复用，VideoToolbox 硬解，dav1d 软解 AV1）。同一时刻只有一个活动引擎实例，翻页复用，不为每页新建播放器。
4. **统一的版本化字节缓存。** 两个引擎都从应用内仅监听回环地址的 HTTP 代理读取原文件字节。代理按 `服务端标识|库|媒体 ID|版本` 作为缓存键，未命中时以带 Bearer 令牌的 Range 请求向服务端取数，同时写入本机缓存；预加载写入同一份缓存。这样文件版本、Range 偏移和缓存键在两个引擎间保持一致，令牌也不会出现在播放器 URL 中。
5. **依赖。** [MPVKit](https://github.com/mpvkit/MPVKit) `1.1.0-n9.0.2` 的 LGPL 产品（libmpv + FFmpeg 预编译 xcframework，通过 Swift Package Manager 校验和下载）；[Nuke](https://github.com/kean/Nuke) `13.2.0` 负责封面与照片的下载、内存缓存、磁盘缓存和降采样。项目许可 GPL-3.0-or-later 与两者兼容；第三方声明同步到 `NOTICE`。
6. **平台差异。** 访问令牌存 Keychain，其他设置存 UserDefaults，浏览位置存 Application Support 下的 SQLite；页面返回使用 iOS 边缘右滑；媒体音量通过系统音量视图调节，亮度调节只在查看器内生效、离开即恢复；局域网 HTTP 需要 ATS 例外和本地网络权限说明。

## 取舍

- 只用 AVPlayer 会让 MKV / TS 无法播放，与安卓功能不一致；只用 libmpv 则放弃系统播放器在 MP4 上的硬解首帧、省电和系统集成。并用的代价是两套播放状态映射和更大的安装包。
- 回环代理比 `AVAssetResourceLoaderDelegate` 多一层本机 HTTP，但能让两个引擎共享缓存与预加载，且 libmpv 无法使用 AVFoundation 的资源加载回调。
- MPVKit 的 iOS 渲染经 MoltenVK（Vulkan → Metal），上游说明 Metal 渲染路径尚非官方正式支持；若真机发现渲染或功耗问题，记录后再评估替代方案，不预先引入第二套 FFmpeg 播放器。

## 验收

iOS 性能验收在用户的 iPhone Pro 上用 Release 构建测量，分别记录列表滚动帧表现、视频首帧、缓存场景和长时间内存；门槛沿用 [当前设计](../DESIGN.md) 的性能验收计划。模拟器只用于功能检查，不作为性能证据。未测量的项目不能宣称达标。
