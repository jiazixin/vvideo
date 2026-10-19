# 第一版 HTTP API

所有端点要求 `Authorization: Bearer <private-token>`。地址不包含文件路径，错误不回传令牌或源媒体完整路径。API 与源媒体均只面向授权内网客户端。接口由 `internal/app/http.go` 装配，目录/媒体查询由 `internal/library/` 实现。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /v1/libraries` | 已启用媒体库及根目录 ID、健康状态 |
| `GET /v1/directories/{id}/path` | 从根到当前目录的 ID/名称数组；校验祖先可见性，不返回源路径；最多1024层，工作量 O(深度) |
| `GET /v1/directories/{id}/children` | 名称/ID 总序的子目录；`cursor` 翻页，`previous=true` 取前页，100 项上限；响应含 `items/next/previous` |
| `GET /v1/directories/{id}/media` | 当前目录媒体；默认 `sort=capture&direction=desc`，`kind=video/image` 可筛选；默认 100、最大 200 项 |
| `GET /v1/media/{id}` | 当前发布版本的元信息 |
| `GET /v1/media/{id}/bytes?version=...` | 原件字节，GET 注册同时支持 HEAD、Range、ETag、条件请求；必须传当前版本 |
| `GET /v1/media/{id}/index?version=...` | 同版本原文件 `ftyp/moov` 字节偏移提示；异常/其他容器可返回空范围，正常播放不依赖它 |
| `GET /v1/media/{id}/thumbnail?version=...` | 版本及抽帧参数对应的 WebP；未就绪以可诊断状态与重试提示返回 |
| `POST /v1/scan` | 安排扫描，202；`force=true` 重新检查/探测，仍需稳定性与资源边界 |
| `GET /v1/status` | 当前扫描状态、累计扫描/版本/事务计数、累计各阶段纳秒与封面字节账本 |

媒体 `status`：`discovered` 待探测，`probe_ready` 元数据已就绪，`ready` 封面已生成，`unsupported` 无法生成封面（如视频时长未知），`failed` 探测或抽帧 3 次均失败。探测得到的拍摄时间最多约 20 秒后批量写入排序，此时目录 revision 递增一次，旧游标返回 409，客户端按锚点重新定位。

媒体分页的 `cursor` 是不透明的服务器游标，不按页号/SQL OFFSET 访问。`previous=true` 返回游标前方的一页，结果仍按请求方向排列。`anchor=<media-id>` 恢复到该媒体所在十项组的开头；`fallback` 是同目录/排序的旧键游标，供原锚点缺失时定位。响应包含 `directory`、`items`、`next`、`previous`、`anchor`、`anchor_found`、`healthy`。游标中的目录 revision、排序、方向和 kind 必须匹配当前请求，变化返回 409，客户端重新以稳定媒体 ID 定位。

所有错误响应（含 bytes 与 thumbnail 端点）均为 JSON `{"error":"<code>"}`：401 `unauthorized` 为鉴权失败；400 `invalid_request` 为参数、游标、排序或分页上限非法，bytes 缺少 `version` 时为 `version_required`；404 `unavailable` 为不可用资源；409 `revision_changed` 为目录/文件版本变化；媒体并发超过 8 返回 503 `media_busy` 与 `Retry-After: 1`；封面正在生成且 2 秒内未完成返回 503 `thumbnail_pending`，媒体仍在探测返回 404 `thumbnail_pending`，二者均带 `Retry-After: 2`；媒体状态为 `unsupported`（如时长未知）或 `failed`（探测/抽帧 3 次均失败）时返回不带 `Retry-After` 的 404 `thumbnail_unavailable`，客户端应停止重试，直到文件变化产生新版本或强制扫描。服务器内部错误返回 500 `internal_error`，细节只写入服务端日志。缓存 URL 与缓存键包含库/媒体/版本，不能混用旧索引与新字节。`Range` 的原字节坐标不因缓存或索引提示改变。

`phase_ns` 是自进程启动以来的累计阶段时间，多个并发任务的累计时间不等于墙钟耗时。`version_records_written` 只计新版本创建与探测版本更新，**不是全部 SQL 受影响行数**；候选成员复制/清理需用行为测试和单独事务测量评估成本。

缩略图和 bytes 的返回体是媒体二进制，其他端点为 JSON。当前库列表有 200 个配置根的读取上限；这是第一版边界，不能把媒体条目规模证明当作无限配置根支持。

源版本指纹使用设备/inode、尺寸、mtime 与 ctime 秒/纳秒；ctime 不是拍摄时间。读块前后复核，保留 mtime 的同尺寸改写也必须拒绝旧版本并中止在途响应。它是文件系统元数据守卫，不是内容哈希。

子目录反向页仍按名称/ID 升序返回。游标包含父目录和边界名称/ID，越父目录游标拒绝；新增/删除目录采用实时键集语义，刷新回到首窗。客户端最多保留三页，按两端游标回取，不缓存全目录游标链。服务端使用既有 `directory_children(parent_id,state,name,id)` 范围索引，每次最多读取101行（额外一行判断边界），无深 OFFSET、写事务或扫描架构改动。
