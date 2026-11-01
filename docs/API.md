# HTTP API

接口遵循 [代码规范.md](../代码规范.md) 第 5 节。除 `GET /healthz` 与 `GET /api/info` 外，所有端点要求 `Authorization: Bearer <private-token>`。地址不包含文件路径，错误不回传令牌或源媒体完整路径。API 与源媒体均只面向授权内网客户端。接口由 `internal/app/http.go` 装配，传输类型在 `internal/app/wire.go`，目录/媒体查询由 `internal/library/` 实现。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /healthz` | 不鉴权，`{"status":"ok"}` |
| `GET /api/info` | 不鉴权，`{"version","capabilities"}`；能力为 `index_hints`、`search`、`thumbnails` |
| `GET /api/libraries` | `{"items":[...]}`：已启用媒体库及根目录 ID（`directory_id`）、健康状态 |
| `GET /api/directories/{id}/path` | `{"items":[...]}`：从根到当前目录的 ID/名称；校验祖先可见性，不返回源路径；最多 1024 层，工作量 O(深度) |
| `GET /api/directories/{id}/children` | 名称/ID 总序的子目录；`cursor` 翻页，`previous=true` 取前页，100 项上限；响应 `items`、`next_cursor`、`previous_cursor` |
| `GET /api/directories/{id}/media` | 当前目录媒体；默认 `sort=capture&direction=desc`，`kind=video/image` 可筛选；`limit` 默认 100、最大 200 |
| `GET /api/directories/{id}/search?q=...` | 按名称在该目录及全部可见子目录中搜索：`q` 为 1–100 个字符的子串（ASCII 不区分大小写，`%`、`_` 按字面匹配）；响应 `directories`（按名称，最多 50）、`items`（媒体，按名称，最多 200）、`truncated`（还有更多匹配，客户端提示缩小范围，不分页） |
| `GET /api/media/{id}` | 当前发布版本的元信息 |
| `GET /api/media/{id}/bytes?version=...` | 原件字节，GET 注册同时支持 HEAD、Range、ETag、条件请求；必须传当前版本 |
| `GET /api/media/{id}/index?version=...` | 同版本原文件的预加载范围提示：`version`、`size_bytes`、`ranges[{offset_bytes,length_bytes}]`；异常/其他容器可返回空范围，正常播放不依赖它 |
| `GET /api/media/{id}/thumbnail?version=...` | 版本及抽帧参数对应的 WebP；未就绪以可诊断状态与重试提示返回 |
| `POST /api/scans` | 安排扫描，202 `{"status":"scheduled"}`；`force=true` 重新检查/探测，仍需稳定性与资源边界。重复请求合并为同一轮扫描，本身幂等，不使用 `Idempotency-Key` |
| `GET /api/status` | 当前扫描状态、累计扫描/版本/事务计数、累计各阶段纳秒（`phase_ns`、`max_transaction_ns`）与封面字节账本（`thumbnail_bytes`） |

媒体字段：`id`、`version`、`directory_id`、`library_id`、`name`、`kind`、`size_bytes`、`width`、`height`、`rotation`（非 0 时）、`duration_ms`（已知时）、`modified_at`（文件修改时间）、`effective_at`（排序用的有效时间：拍摄时间，无法确定时回退修改时间）、`captured_raw` 与 `captured_source`（有拍摄时间时）、`status`。时间为 UTC RFC 3339、毫秒精度（`2026-10-27T08:00:00.000Z`）；排序键内部仍以纳秒保存。可选字段缺省时省略，不返回 `null`。

媒体 `status`：`discovered` 待探测，`probe_ready` 元数据已就绪，`ready` 封面已生成，`unsupported` 无法生成封面（如视频时长未知），`failed` 探测或抽帧 3 次均失败。探测得到的拍摄时间最多约 20 秒后批量写入排序，此时目录 revision 递增一次，旧游标返回 409，客户端按锚点重新定位。

媒体分页的 `cursor` 是不透明的服务器游标，不按页号/SQL OFFSET 访问。`previous=true` 返回游标前方的一页，结果仍按请求方向排列。`anchor=<media-id>` 恢复到该媒体所在十项组的开头；`fallback` 是同目录/排序的旧键游标，供原锚点缺失时定位。响应包含 `directory`、`items`、`next_cursor`（没有下一页时省略）、`previous_cursor`（非空页的首项边界，用于反向取页与书签恢复；首项已经在目录开头时，反向请求返回空页）、`anchor`、`anchor_found`、`healthy`。游标中的目录 revision、排序、方向和 kind 必须匹配当前请求，变化返回 409，客户端重新以稳定媒体 ID 定位。

查询字符串最多 16 KiB；未知参数、重复参数和非法编码均返回 `invalid_argument`。布尔参数只接受 `true` / `false`。令牌为 1–4096 个可见 ASCII 字符（`!` 至 `~`，不含空格），仅由 `Authorization` 头传输；服务端运行配置保存其 SHA-256 摘要并做恒定时间比较。

每个响应带 `X-Request-ID`：请求自带且符合 `^[A-Za-z0-9-]{1,64}$` 时沿用，否则由服务端生成；服务端日志用同一 ID。

所有错误响应（含 bytes 与 thumbnail 端点）均为 JSON `{"error":{"code":"<code>","message":"<英文说明>","details":{...}}}`；客户端只按 `code` 分支，`message` 不展示给用户：

| 状态 | `code` | 含义 |
| --- | --- | --- |
| 401 | `unauthenticated` | 缺少或错误的令牌 |
| 400 | `invalid_argument` | 参数、游标、排序或分页上限非法；`details.fields[{path,reason}]` 指出参数，如 bytes 缺少版本时为 `{"path":"version","reason":"required"}` |
| 404 | `not_found` | 资源不存在或不可见；未知路径同样返回 |
| 405 | `method_not_allowed` | 路径存在但方法不支持，带 `Allow` |
| 409 | `revision_changed` | 目录/文件版本变化 |
| 503 | `media_busy` | 媒体并发超过 8，带 `Retry-After: 1` |
| 503 | `thumbnail_pending` | 封面尚未就绪（含待探测），等待最多 2 秒后返回；带 `Retry-After: 2` |
| 404 | `thumbnail_unavailable` | 媒体为 `unsupported` 或 `failed`，不带 `Retry-After`；客户端停止重试，直到文件变化产生新版本或强制扫描 |
| 500 | `internal` | 服务器内部错误，细节只写入服务端日志 |

JSON 与错误响应使用 `Cache-Control: no-store`；成功的版本化媒体响应使用私有不可变缓存。安卓 JSON/封面读取仅对传输错误、429 和 503 最多重试两次，指数退避带抖动并遵守有上限的 `Retry-After`；404、409、500 不自动重试。

缓存 URL 与缓存键包含库/媒体/版本，不能混用旧索引与新字节。`Range` 的原字节坐标不因缓存或索引提示改变。

`phase_ns` 是自进程启动以来的累计阶段时间，多个并发任务的累计时间不等于墙钟耗时。`version_records_written` 只计新版本创建与探测版本更新，**不是全部 SQL 受影响行数**；候选成员复制/清理需用行为测试和单独事务测量评估成本。

缩略图和 bytes 的返回体是媒体二进制，其他端点为 JSON。当前库列表有 200 个配置根的读取上限；这是第一版边界，不能把媒体条目规模证明当作无限配置根支持。

源版本指纹（格式 `f3`）使用 inode、尺寸、mtime 与 ctime 秒/纳秒，不含网络挂载重挂后会变的设备号；ctime 不是拍摄时间。读块前后复核，保留 mtime 的同尺寸改写也必须拒绝旧版本并中止在途响应。它是文件系统元数据守卫，不是内容哈希。

子目录反向页仍按名称/ID 升序返回。游标包含父目录和边界名称/ID，越父目录游标拒绝；新增/删除目录采用实时键集语义，刷新回到首窗。客户端最多保留三页，按两端游标回取，不缓存全目录游标链。服务端使用既有 `directory_children(parent_id,state,name,id)` 范围索引，每次最多读取 101 行（额外一行判断边界），无深 OFFSET、写事务或扫描架构改动。
