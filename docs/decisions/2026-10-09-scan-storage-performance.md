# 决策：从 video-rename 提取扫描与 SQLite 性能机制

日期：2026-10-09。状态：采纳下述机制作为 VVideo 的施工要求；尚未实现或压测 VVideo。

## 证据范围

用户复制了 `video-rename/` 作为只读参考，要求提取后可将其删除。本次检查了实际代码、测试和历史记录，没有运行参考服务、访问用户媒体、运行其规模测试或修改参考项目。

参考副本的 Git HEAD 为 `0a1d64dcfaa17c437844ae5c7f9112832fc51a25`，检查时 `git status --short` 为空。以下源路径均相对此副本，只用于追溯，不是 VVideo 的依赖或施工入口。实施方法已独立写入 [开发说明](../IMPLEMENTATION.md)，任务和测试契约见 [服务端性能实施计划](../plans/2026-10-09-server-performance.md)。删除参考目录不会破坏这些文档的引用。

## 实际代码中的机制

| 编号 | 参考源与定位 | 核实的行为 | VVideo 的采用方式 |
| --- | --- | --- | --- |
| REF-01 | `internal/library/refresh.go`：`refreshPageSize`、`readDirectoryMetadata` | 每页 128 项，最多 4 个属性 worker；结果按输入槽位保存；失败和取消也等待已派发 worker 退出 | 采用分页、有界并发和明确回收；worker 数取本项目 `scan.parallel`，不照搬固定 4 |
| REF-02 | `internal/library/directory-scan.go`：`collectScanMetadata`、`scanPinnedDirectoryEntry` | 文件属性在写事务外采集；用固定目录描述符和不跟随符号链接的属性读取，避免路径切换后读到别处 | 遍历、stat、工具执行全部在正式库写事务及共享锁外；Linux 文件身份复核单独实现 |
| REF-03 | 同文件：`newScanStage`、`DirectoryScanner` | 磁盘暂存 SQLite 保存 baseline、snapshot、verification；一个扫描器复用暂存库，避免每目录建库 | 采用本机磁盘暂存与流式比较；不用全库 Go map；一轮复用，失败清理、重启可丢弃 |
| REF-04 | 同文件：`buildScanGeneration`、`writeScanGenerationPage`、`publishDirectoryScan` | 双遍观测后分页构建新目录代；无变化不重建；核对基线后事务发布；失败保留旧目录代 | 采用“先构建、后发布”；新增、变更、缺失分开处理，缺失还须满足 VVideo 的两轮健康确认 |
| REF-05 | `internal/jobs/scan.go`：`queueFiles` | 一页候选共用事务和预编译语句，准备路径在事务外；实际插入数及任务计数同事务提交 | 用于持久探测/缩略图队列；幂等任务键、提交后计数，避免逐文件事务和重复入队 |
| REF-06 | `internal/storage/store.go`：`Open`、`Read` | 正式库 WAL、FULL 同步、单写连接、4 个只读连接；每连接启用外键、busy timeout；读池只读 | 保留耐久性和读写分池；读事务关闭后才慢速传输 HTTP 响应；不把多个 writer 当加速方式 |
| REF-07 | `internal/storage/schema.sql`、`indexes_test.go` | 父目录加条目 ID 的游标索引、目录加条目的关联索引、候选部分索引及外键反向索引；测试检查执行计划 | 围绕实际 WHERE / ORDER BY / JOIN 设计索引；覆盖目录浏览、恢复锚点、任务认领、清理，不照搬改名业务表 |
| REF-08 | `internal/libraryscan/manager.go`：`nextChild`、`run` | 单轮扫描，子目录按 ID 分页，深度优先游标栈；递归扫描串行，单页属性读取并行 | 首版一个遍历协调者加全局属性 worker 池；不层层乘并发，不复制“整小时结束后再等待”的调度方式 |
| REF-09 | `internal/library/progress_cache.go` 及 `docs/IMPLEMENTATION.md` | 有界统计缓存，修订号失效；避免每个请求重复汇总整棵目录树 | 只对真实热点缓存；缓存按版本失效、有容量上限；目录列表不附带昂贵的实时全库统计 |
| REF-10 | `internal/library/*scan*test.go`、`refresh_parallel_test.go`、`internal/storage/read_test.go` | 测试覆盖慢属性读取时 DB 可用、并发上限、取消收尾、发布回滚、清理隔离、读池快照 | 将这些故障模型移植成 VVideo 行为测试，而不是只复制正常路径的速度测试 |

“批量写库”在这里主要是**一批多行共用一个事务和语句**，不是必须拼成一条巨型 SQL。索引同样关键：批次再小，若每批都扫描大表，仍可能很慢。

## 历史数字与适用边界

以下是参考仓库记录的历史结果，**本次没有复测，也不能作为 VVideo 的验收结果**。

| 历史记录 | 当时记录的数字 | 能说明什么 / 不能说明什么 |
| --- | --- | --- |
| `docs/archive/2026-09-27-large-library-performance.md` | 30 万条、约 908 MiB 合成库；目录统计约 2.9 秒到 0.97 秒；候选分页落库约 148 秒到 3.1–3.3 秒 | 记录把后者收益归因于游标索引；不应把这个倍数归因于“换模型”或直接用于新 NAS |
| `docs/archive/2026-10-02-refresh-performance.md` | 生产记录 529,654 文件，总计 394.262 秒，其中刷新 264.188 秒、校验 102.311 秒、标记写入 25.575 秒 | 刷新包含文件系统和数据库工作；原日志不足以把全部耗时归为 SQLite 或 SMB |
| `docs/archive/2026-10-07-before-directory-layout-acceptance.md` | 本机合成 53 万文件 / 111 目录；首次扫描 48.305 秒、未变复扫 13.370 秒 | 对应 `directory-scan_large_scale_test.go`，创建实际临时小文件，计时不含夹具生成；**不含真实视频解码、ffprobe 或封面生成**，不是 NAS 吞吐 |

最后一种夹具是 10 个分支 × 每分支 10 个叶目录 × 每叶 5,300 个文件。它验证了大量文件分散在目录中的扫描，不等于验证“单目录 53 万文件”或同数量的大视频。

## 不照搬的地方

1. **临时库与正式库的保证不同。** 参考暂存库使用 `journal_mode=MEMORY` 和 `synchronous=OFF`；正式库是 WAL / FULL。VVideo 仅允许可丢弃、与正式数据分离的扫描暂存降低耐久性。不能用关闭正式库同步、外键或错误处理来赢得跑分。
2. **有界分页不代表所有返回值都已限界。** `ScanDirectory` 仍把全部直接子目录 ID 追加到 `result.Directories`；递归 manager 又通过游标读取子目录。VVideo 只保留分页迭代接口和计数，不返回全量子目录切片。
3. **“短锁发布”不等于恒定时间发布。** 参考最终事务有缺失标记、候选提升和变更更新；影响行多时事务仍可能长。VVideo 要单独计量发布事务及 writer 等待，不能把大 SQL 隐藏在“切换 generation”这个名字里。
4. **改动一个文件也可能重建整个目录代。** 参考构建成本与该目录大小相关，而非只有变化数。先保留正确性边界并测量单个超大目录的少量变更；如不达标，再减少目录代复制或引入版本化条目，不能声称当前算法只有 O(变化数) 成本。
5. **双遍观测不是文件系统快照。** 它能发现两遍之间的许多变化，但无法保证扫描期间没有短暂变化，也不等同于“文件已稳定 60 秒”。播放与探测仍要复核文件版本。
6. **原项目业务和调度不适用本项目。** 不复制 AI 改名、编号、模型队列容量、Web 构建、整小时等待调度、固定 Go 版本或“不支持数据库迁移”的策略。
7. **测试有规模边界。** 50 万合成数据库行与 50 万真实文件是不同测试；延迟模拟也不等于真实 SMB/NFS。取消 context 通常不能立即打断已经陷入内核等待的文件系统调用，不能承诺挂载故障下瞬时退出。

## 采纳的取舍

采用 SQLite 本地存储、单写多读、有界扫描、事务批处理、索引游标、目录代发布和失败保留旧索引。扫描发现与昂贵媒体探测拆开；真正不变的文件不重新探测、不重新抽帧、不逐条更新时间。

首版先用 128 项内部批次，以 `scan.parallel` 控制全局文件属性并发。这些是起点，不能保证任意 NAS 最优。后台在批次边界让步给原画播放，接受连续播放时建库推迟。完整文件遍历仍有 O(文件数) 的文件系统工作；临时快照及目录代构建有本机磁盘成本。

实现或压测证明上述选择不足时，先记录具体查询、阶段耗时和资源争用，再修改设计。不得只因文件总量大就增加 Redis、多个 SQLite writer 或改成 MySQL。

## 源码指纹

用于确认此次提取对应哪个副本，不要求保留参考目录。

| 参考文件 | SHA-256 |
| --- | --- |
| `internal/library/refresh.go` | `81e71da0d0ab130dc856d3d97b69506c1072c07cef994f1d7acadbcabb291437` |
| `internal/library/directory-scan.go` | `e3c999f04d0e1051c4511033ed323c07e49d7eb2b227959e43f7df4b3e3767e0` |
| `internal/libraryscan/manager.go` | `6d514da798161249ce810ad4821903b86eb02a934c6d9cbfa4e18be0d5feee75` |
| `internal/jobs/scan.go` | `77b91e9e0906734d396f00c62e16e3b13ec8abdbdd2cfde775dd15e811c27bb7` |
| `internal/storage/store.go` | `a1279bd468895d5edbe1612ab3ceb918401048222179b4727a19018ffe249a57` |
| `internal/storage/schema.sql` | `ff91a93ce102640dea2f8d472426975dc8ca6db440cdb86e3dee4b42ee863d71` |

## 官方机制核对

- [Go File.ReadDir](https://pkg.go.dev/os#File.ReadDir)：正数参数支持分批读取；扫描器须处理同一次返回的数据和错误。不能误用一次读取完整目录的入口。
- [SQLite WAL](https://www.sqlite.org/wal.html)：支持读写并行但同一时刻仍只有一个 writer；长期读事务会影响 checkpoint，数据库应放本机文件系统。
- [SQLite synchronous](https://www.sqlite.org/pragma.html#pragma_synchronous)：耐久性设置有不同故障语义；本项目不静默降低正式库保证。
- [SQLite EXPLAIN QUERY PLAN](https://www.sqlite.org/eqp.html)：可检查索引和临时排序，输出格式可能随版本变化；测试不匹配整段固定文本，也不能把计划检查当作吞吐实测。
