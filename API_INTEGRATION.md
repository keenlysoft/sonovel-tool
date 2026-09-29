# 外部系统搜索与 TXT 下载接口设计

状态：设计稿，尚未实现
适用分支：`codex/search-download-lite`

## 1. 背景

当前 SoNovel Lite 的网页已经能够搜索小说、排队下载并生成 UTF-8 TXT。核心程序同时暴露了若干供网页使用的内部接口：

- `GET /search/aggregated?kw=...`
- `GET /book-fetch?...`
- `GET /local-books`
- `GET /book-download?filename=...`

这些接口不是稳定的外部契约：参数直接包含书源 URL，下载请求会长时间占用连接，也没有面向系统集成的鉴权、幂等和任务状态。因此外部系统不应直接依赖它们。

本文定义一层版本化的 REST 接口。外部系统只需理解“搜索结果、下载任务、TXT 文件”三个概念，书源选择、参数转换、队列控制和内部接口变化均由本项目隐藏。

## 2. 目标

- 外部系统可以按关键词搜索小说。
- 外部系统可以通过搜索结果创建异步 TXT 下载任务。
- 创建任务后立即返回，不需要等待整本小说抓取完成。
- 外部系统可以查询任务状态，并在完成后拉取 TXT。
- 所有外部接口均使用 Bearer Token 鉴权。
- 外部调用者不能提交任意抓取 URL。
- 同一时间只执行一个实际抓取任务，避免底层下载器共享状态冲突。
- 保持现有网页搜索和后台排队下载功能不变。

## 3. 非目标

以下能力不属于 v1 范围：

- 主动向第三方系统推送文件或发送 Webhook。
- 上传到 S3、WebDAV、网盘或对象存储。
- EPUB、PDF、DOCX 等格式生成或转换。
- 多用户账号、权限角色和配额计费。
- 书源规则的远程增删改。
- 多本小说并行抓取。
- 在本项目内提供公网 TLS 证书管理。
- 删除外部系统已经下载的文件。

未来如需推送模式，应在异步任务完成后增加受控的交付适配器，不允许调用者直接提供任意回调 URL。

## 4. 用户故事

1. 作为外部系统，我希望通过关键词搜索小说，以便选择正确的书名、作者和书源。
2. 作为外部系统，我希望搜索结果返回稳定且不透明的书籍引用，以便无需了解内部书源参数。
3. 作为外部系统，我希望提交下载后立即获得任务编号，以便请求不会被长时间阻塞。
4. 作为外部系统，我希望查询下载任务状态，以便决定何时拉取文件。
5. 作为外部系统，我希望下载完成后取得 UTF-8 TXT，以便保存到自己的存储系统。
6. 作为外部系统，我希望重复请求可以通过幂等键复用原任务，以便网络重试不会重复抓取。
7. 作为外部系统，我希望未完成任务的文件请求得到明确错误，以便稍后重试。
8. 作为外部系统，我希望搜索或抓取失败时获得稳定错误码，以便自动处理异常。
9. 作为运维人员，我希望接口必须鉴权，以便未授权客户端不能搜索或下载。
10. 作为运维人员，我希望下载队列限制长度和并发，以便底层服务不会被压垮。
11. 作为运维人员，我希望服务重启后仍能查询已完成任务，以便外部流程可以恢复。
12. 作为维护人员，我希望外部契约不暴露内部接口，以便以后能够替换书源或核心 JAR。

## 5. 总体结构

```text
外部系统
   │  HTTPS / Bearer Token
   ▼
Nginx
   │  /api/v1/*
   ▼
API Gateway
   ├── 鉴权与限流
   ├── 搜索结果标准化
   ├── book_ref 管理
   ├── 单并发下载队列
   ├── 任务状态持久化
   └── TXT 文件输出
            │
            ▼
     SoNovel 内部接口
```

新增的 API Gateway 是外部系统唯一需要依赖的模块。它通过内部适配器调用现有 SoNovel 接口，并对外提供较小、稳定的接口。

建议将 Gateway 实现为独立的轻量 Go 进程，并在 Docker Compose 中作为新服务运行。Nginx 将 `/api/v1/` 转发到 Gateway，其他网页请求继续转发到现有 Java 服务。

## 6. 通用约定

### 6.1 基础地址

```text
http://127.0.0.1:7765/api/v1
```

默认仍只监听本机。如需局域网或公网调用，应由部署环境显式修改监听地址，并在上层反向代理配置 HTTPS、防火墙和来源限制。

### 6.2 鉴权

所有 `/api/v1/` 请求必须携带：

```http
Authorization: Bearer <token>
```

Token 通过环境变量 `SONOVEL_API_TOKEN` 注入，长度不得少于 32 个随机字节，不得提交到 Git。缺少或无效 Token 返回 `401 Unauthorized`。

### 6.3 内容格式

- 请求和普通响应使用 `application/json; charset=utf-8`。
- TXT 文件使用 `text/plain; charset=utf-8`。
- 时间使用 UTC ISO 8601，例如 `2026-09-29T10:30:00Z`。
- 每个响应包含 `X-Request-Id`，客户端也可以主动传入该头用于链路追踪。
- JSON 字段使用 `snake_case`。

### 6.4 标准成功结构

```json
{
  "data": {},
  "request_id": "req_01K..."
}
```

### 6.5 标准错误结构

```json
{
  "error": {
    "code": "invalid_request",
    "message": "q 不能为空",
    "retryable": false
  },
  "request_id": "req_01K..."
}
```

`message` 面向开发者，不保证文本长期稳定；调用方必须根据 `code` 判断错误类型。

## 7. 接口定义

### 7.1 搜索小说

```http
GET /api/v1/books?q=<关键词>&limit=<数量>
```

参数：

| 参数 | 必填 | 约束 | 默认值 |
|---|---|---|---|
| `q` | 是 | 去除首尾空格后 1–100 个字符 | 无 |
| `limit` | 否 | 1–50 | 20 |

成功响应：`200 OK`

```json
{
  "data": {
    "items": [
      {
        "book_ref": "book_01K...",
        "title": "凡人修仙传",
        "author": "忘语",
        "category": "仙侠修真",
        "latest_chapter": "忘语新书《玄界之门》",
        "updated_at_text": "2019-12-30",
        "status": "完本",
        "word_count": null,
        "source_id": 9
      }
    ],
    "count": 1
  },
  "request_id": "req_01K..."
}
```

约束：

- `book_ref` 是服务器生成的随机不透明引用，有效期 30 分钟。
- Gateway 在服务端保存 `book_ref` 到内部书源参数的映射。
- 响应不得暴露书源 URL。
- 搜索无结果仍返回 `200`，此时 `items` 为空。
- 上游搜索超时为 45 秒。

### 7.2 创建下载任务

```http
POST /api/v1/downloads
Idempotency-Key: <客户端生成的唯一值>
Content-Type: application/json
```

```json
{
  "book_ref": "book_01K...",
  "refresh": false
}
```

字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `book_ref` | 是 | 搜索接口返回的不透明引用 |
| `refresh` | 否 | `true` 表示已有同名 TXT 时仍重新抓取；默认 `false` |

新任务响应：`202 Accepted`

```json
{
  "data": {
    "task_id": "task_01K...",
    "status": "queued",
    "status_url": "/api/v1/downloads/task_01K...",
    "file_url": null,
    "created_at": "2026-09-29T10:30:00Z"
  },
  "request_id": "req_01K..."
}
```

如果 `refresh=false` 且已有可复用的 TXT，返回 `200 OK`，任务状态直接为 `completed`，同时返回 `file_url`。

幂等规则：

- `Idempotency-Key` 长度为 8–128 个可打印 ASCII 字符。
- 同一 Token、同一幂等键和相同请求体在 24 小时内返回同一任务。
- 同一幂等键配合不同请求体返回 `409 Conflict`，错误码为 `idempotency_conflict`。
- 未提供幂等键时允许创建任务，但网络重试可能产生重复任务。

安全约束：

- 请求体不得接受 `url`、`source_id` 或文件路径。
- 过期或不存在的 `book_ref` 返回 `410 Gone`。
- 队列最多保存 100 个未完成任务，满载时返回 `429 Too Many Requests`。

### 7.3 查询下载任务

```http
GET /api/v1/downloads/{task_id}
```

成功响应：`200 OK`

```json
{
  "data": {
    "task_id": "task_01K...",
    "status": "completed",
    "book": {
      "title": "凡人修仙传",
      "author": "忘语"
    },
    "created_at": "2026-09-29T10:30:00Z",
    "started_at": "2026-09-29T10:30:02Z",
    "finished_at": "2026-09-29T10:34:28Z",
    "file": {
      "name": "凡人修仙传(忘语).txt",
      "size": 22292368,
      "content_type": "text/plain; charset=utf-8",
      "url": "/api/v1/downloads/task_01K.../file"
    },
    "error": null
  },
  "request_id": "req_01K..."
}
```

任务状态仅允许：

| 状态 | 含义 |
|---|---|
| `queued` | 已进入队列，等待前序任务完成 |
| `running` | 正在抓取章节并生成 TXT |
| `completed` | TXT 已生成，可以下载 |
| `failed` | 下载失败，不会自动无限重试 |

失败任务示例：

```json
{
  "data": {
    "task_id": "task_01K...",
    "status": "failed",
    "file": null,
    "error": {
      "code": "upstream_download_failed",
      "message": "书源暂时不可用",
      "retryable": true
    }
  },
  "request_id": "req_01K..."
}
```

Gateway 重启后：

- `queued` 任务继续排队。
- 重启前的 `running` 任务标记为 `failed`，错误码为 `gateway_restarted`，避免不确定状态下重复抓取。
- `completed` 和 `failed` 状态至少保留 24 小时。

### 7.4 下载 TXT 文件

```http
GET /api/v1/downloads/{task_id}/file
```

任务完成时返回：`200 OK` 或 `206 Partial Content`

```http
Content-Type: text/plain; charset=utf-8
Content-Disposition: attachment; filename*=UTF-8''...
Content-Length: 22292368
Accept-Ranges: bytes
Cache-Control: private, no-store
```

要求：

- 文件内容必须是有效 UTF-8。
- 支持 HTTP Range，便于大文件断点续传。
- `queued` 或 `running` 状态返回 `409 Conflict`，错误码为 `task_not_completed`。
- 任务不存在返回 `404 Not Found`。
- 任务完成但文件丢失返回 `410 Gone`，错误码为 `file_gone`。
- 文件名只来自任务记录，不接受调用方传入路径或文件名。

## 8. 错误码

| HTTP 状态 | 错误码 | 是否可重试 | 说明 |
|---:|---|---|---|
| 400 | `invalid_request` | 否 | 参数或 JSON 不符合约束 |
| 401 | `unauthorized` | 否 | Token 缺失或无效 |
| 404 | `task_not_found` | 否 | 任务不存在 |
| 409 | `idempotency_conflict` | 否 | 幂等键已用于其他请求 |
| 409 | `task_not_completed` | 是 | 文件尚未生成 |
| 410 | `book_ref_expired` | 否 | 搜索结果引用已过期，需要重新搜索 |
| 410 | `file_gone` | 否 | 任务存在但文件已被移除 |
| 429 | `queue_full` | 是 | 下载队列已满 |
| 429 | `rate_limited` | 是 | 请求频率超过限制 |
| 502 | `upstream_search_failed` | 是 | 内部搜索失败 |
| 503 | `service_unavailable` | 是 | 核心下载服务不可用 |
| 504 | `upstream_timeout` | 是 | 内部搜索超时 |

返回 `429` 或临时性 `503` 时应附带 `Retry-After`。

## 9. 队列与持久化

- 下载 Worker 并发数固定为 1。
- 单个任务最长执行 30 分钟，超时后标记为 `failed`。
- 任务状态、幂等键和最终文件元数据写入持久化存储。
- 建议使用 SQLite，并挂载到 `api-data/jobs.db`。
- 小说 TXT 继续由现有服务写入 `downloads/`。
- Gateway 通过内部 `/local-books` 验证文件确实生成，再将任务标记为 `completed`。
- 不允许仅凭内部下载请求返回 `200` 就判定任务完成。
- 日志不得记录 Bearer Token、完整 `book_ref` 或小说正文。

## 10. 网络与安全

1. `/api/v1/` 只能通过 Gateway 访问，并强制鉴权。
2. 外部请求不得直接访问 `/book-fetch`、`/book-download`、`/local-books` 和 `/search/aggregated`。
3. 网页接口默认继续限制为本机访问。
4. API 不设置宽松的浏览器 CORS；这是服务器到服务器接口。
5. `book_ref` 使用至少 128 位随机值，并在服务端保存映射，避免泄露或篡改书源 URL。
6. Token 比较使用恒定时间算法。
7. 请求体设置大小上限，建议不超过 16 KiB。
8. 对每个 Token 设置速率限制：搜索默认 30 次/分钟，创建任务默认 10 次/分钟。
9. 如果监听非回环地址，部署方必须配置 TLS、防火墙或可信内网访问控制。

## 11. Docker Compose 变更

实现阶段新增 `api-gateway` 服务：

- 仅在 Docker 内部监听 `8080`。
- 通过内部网络访问 `sonovel:7765`。
- 挂载持久化目录 `./api-data:/data`。
- 从环境变量读取 `SONOVEL_API_TOKEN`。
- Nginx 将 `/api/v1/` 转发到 `api-gateway:8080`。
- Nginx 继续作为唯一宿主机入口。
- `api-data/` 和本地密钥文件加入 `.gitignore`。

为控制体积，Gateway 的最终运行镜像应使用单一静态二进制，新增镜像数据目标不超过 20 MiB。

## 12. 外部系统调用示例

### 12.1 搜索

```bash
curl -sS \
  -H "Authorization: Bearer $SONOVEL_API_TOKEN" \
  --get \
  --data-urlencode "q=凡人修仙传" \
  "http://127.0.0.1:7765/api/v1/books"
```

### 12.2 创建任务

```bash
curl -sS \
  -X POST \
  -H "Authorization: Bearer $SONOVEL_API_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-20260929-001" \
  -d '{"book_ref":"book_01K...","refresh":false}' \
  "http://127.0.0.1:7765/api/v1/downloads"
```

### 12.3 查询状态

```bash
curl -sS \
  -H "Authorization: Bearer $SONOVEL_API_TOKEN" \
  "http://127.0.0.1:7765/api/v1/downloads/task_01K..."
```

### 12.4 保存到外部系统

```bash
curl -fL \
  -H "Authorization: Bearer $SONOVEL_API_TOKEN" \
  "http://127.0.0.1:7765/api/v1/downloads/task_01K.../file" \
  -o novel.txt
```

外部系统拿到文件后，可以自行写入数据库、对象存储、内容管理系统或其他业务目录。

## 13. 验收标准

1. 未携带 Token 的四个外部接口均返回 `401`。
2. 搜索“凡人修仙传”能返回结构化结果，且响应中不存在书源 URL。
3. 使用有效 `book_ref` 创建任务在 1 秒内返回 `200` 或 `202`。
4. 同时创建两个任务时，第二个保持 `queued`，不会与第一个并行抓取。
5. 完成后的文件接口返回 UTF-8 TXT，文件名、长度和正文正确。
6. 文件接口支持 Range 请求并返回 `206`。
7. 相同幂等键和相同请求体不会创建重复任务。
8. 过期或伪造的 `book_ref` 不能触发任何外部 URL 请求。
9. Gateway 重启后仍能查询已完成任务。
10. Gateway 重启时处于 `running` 的任务会变为可解释的失败状态。
11. 外部网络不能绕过 Gateway 访问内部下载接口。
12. 现有网页搜索、排队下载和 TXT 输出继续正常工作。

## 14. 实施顺序

1. 实现 Gateway 的鉴权、错误格式和健康检查。
2. 接入内部搜索并生成不透明 `book_ref`。
3. 实现持久化任务记录和单并发下载队列。
4. 实现任务查询和 TXT 流式下载。
5. 增加幂等、限流、超时和重启恢复。
6. 更新 Docker Compose 与 Nginx 路由。
7. 通过公开 `/api/v1/` 接口执行端到端验收。
