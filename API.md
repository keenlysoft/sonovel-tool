# SoNovel 当前服务 API 接口文档

本文记录当前运行的 SoNovel Lite 服务接口，核对日期为 2026-10-01。核心版本为 `v1.9.6`。

基础地址：`http://127.0.0.1:7765`。

当前接口用于网页搜索和下载，没有 Bearer Token 鉴权，也没有服务器端的任务编号和任务查询接口。`API_INTEGRATION.md` 是未来的外部 API 设计稿，其中 `/api/v1/*` 尚未实现。

## 1. 通用约定

- 当前服务端口只绑定本机 `127.0.0.1`。
- 下表中的业务接口均使用 `GET`，参数放在查询字符串中；中文、文件名和 URL 需要编码。
- 普通成功 JSON 响应使用 `code`、`message`、`data` 字段；`code` 是 JSON 中的业务状态码，调用方还应检查 HTTP 状态码。
- 文件下载返回文件内容，下载进度返回 SSE 流，不采用普通 JSON 响应结构。
- 浏览器跨域仅允许 `http` 或 `https` 的 `localhost`、`127.0.0.1` 来源，可带端口；其他 `Origin` 被 Nginx 拒绝并返回 `403`。不携带 `Origin` 的本地 curl 请求可直接调用。

普通成功响应示例：

```json
{
  "code": 200,
  "message": "OK",
  "data": []
}
```

## 2. 接口一览

| 方法 | 路径 | 用途 | 返回内容 |
|---|---|---|---|
| GET | `/version` | 查询核心版本 | JSON |
| GET | `/search/aggregated` | 聚合搜索小说 | JSON |
| GET | `/book-fetch` | 抓取小说并保存到服务端 | 等待抓取请求结束 |
| GET | `/local-books` | 列出服务端下载目录中的文件 | JSON |
| GET | `/book-download` | 下载已保存的文件 | 文件内容 |
| GET | `/download-progress` | 订阅共享下载进度 | SSE |

## 3. 查询版本

```http
GET /version
```

无参数。

```bash
curl 'http://127.0.0.1:7765/version'
```

当前实测响应：

```json
{"code":200,"message":"OK","data":"v1.9.6"}
```

## 4. 聚合搜索小说

```http
GET /search/aggregated?kw=<关键词>
```

| 参数 | 类型 | 说明 |
|---|---|---|
| `kw` | string | 搜索关键词；调用方应传入非空值 |

```bash
curl --get 'http://127.0.0.1:7765/search/aggregated' \
  --data-urlencode 'kw=凡人修仙传'
```

响应的 `data` 为搜索结果数组。以下为字段结构示例，内容用于说明，不代表本次搜索的实际结果：

```json
{
  "code": 200,
  "message": "OK",
  "data": [
    {
      "bookName": "示例书名",
      "author": "示例作者",
      "latestChapter": "最新章节标题",
      "lastUpdateTime": "书源返回的更新时间文本",
      "sourceId": 1,
      "url": "https://example.com/book/123/"
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `bookName` | 书名 |
| `author` | 作者 |
| `latestChapter` | 最新章节；可能为空 |
| `lastUpdateTime` | 书源提供的更新时间；调用方不应假定固定日期格式 |
| `sourceId` | 搜索结果来自的书源标识 |
| `url` | 书源中的书籍地址，抓取时原样传回 |

搜索结果还可能包含 `intro`、`category`、`status`、`wordCount` 等字符串字段；空字段可能省略。`sourceId` 为整数，用于标识搜索结果来自哪个书源，抓取接口会根据 `url` 自行识别书源。

同一本书可能返回多个书源结果。没有匹配结果时，实测响应为 `{"code":200,"message":"OK","data":[]}`。搜索耗时取决于书源响应速度。

## 5. 抓取小说到服务端

```http
GET /book-fetch?url=<搜索结果中的书籍地址>
```

选择上一节搜索结果中的一条记录，传入它的 `url`：

| 参数 | 说明 |
|---|---|
| `url` | 必填，搜索结果中的书籍地址；后端根据地址识别书源 |

当前网页还会附带 `bookName`、`author`、`latestChapter`、`lastUpdateTime` 和 `sourceId`，但当前后端只读取 `url`，其余参数不会被使用。

调用示例，地址须替换为实际搜索结果中的 `url`：

```bash
curl --max-time 1800 --get 'http://127.0.0.1:7765/book-fetch' \
  --data-urlencode 'url=https://example.com/book/123/'
```

这个请求会执行抓取并等待处理结束，新下载配置为 UTF-8 TXT，保存在项目 `downloads/` 目录中。它不会立即返回异步任务编号。当前网页的排队功能由浏览器中的 `web/async-download.js` 实现。

后端正常结束时返回 HTTP `200`，响应体为空，不应调用 `response.json()`。处理异常时返回 HTTP `500`，JSON 消息为 `下载失败: <异常信息>`，例如：

```json
{"code":500,"message":"下载失败: <异常信息>"}
```

调用时应串行处理：Nginx 只允许一个 `/book-fetch` 请求同时执行，其他并发请求返回 `429`。网关上游读取超时设置为 1800 秒，调用方也需要为长时间抓取设置合适的超时。

请求结束后，通过 `/local-books` 查找生成文件，再调用 `/book-download` 取回内容。当前网页按 `书名(作者).txt` 查找，并比较文件大小和修改时间；接口响应本身不提供可供查询的任务编号。客户端断开连接后，网关配置仍允许后端继续处理，断开连接不等于取消抓取。

## 6. 列出已下载文件

```http
GET /local-books
```

无参数。

```bash
curl 'http://127.0.0.1:7765/local-books'
```

响应示例，取自当前服务中的一条记录：

```json
{
  "code": 200,
  "message": "OK",
  "data": [
    {
      "name": "无声1937(梦知隅).txt",
      "size": 1836044,
      "timestamp": 1790404852671
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 文件名，传给 `/book-download` 的 `filename` |
| `size` | number | 文件大小，单位为字节 |
| `timestamp` | number | 文件最后修改时间，Unix 毫秒时间戳 |

列表中也可能包含历史 EPUB 文件。调用方应按文件扩展名筛选，并自行按 `timestamp` 排序。

## 7. 下载已保存文件

```http
GET /book-download?filename=<文件名>
```

| 参数 | 类型 | 说明 |
|---|---|---|
| `filename` | string | `/local-books` 返回的 `name`，调用时进行 URL 编码 |

```bash
curl --fail --get 'http://127.0.0.1:7765/book-download' \
  --data-urlencode 'filename=无声1937(梦知隅).txt' \
  --output '无声1937(梦知隅).txt'
```

成功时返回文件内容，不是 JSON；`Content-Type` 为 `application/octet-stream`，`Content-Disposition` 为附件下载，`Content-Length` 为文件字节数。

`filename` 缺失或为空时返回 HTTP `400`：

```json
{"code":400,"message":"参数文件名不可为空"}
```

文件不存在时，实测返回 HTTP `404` 和以下 JSON：

```json
{"code":404,"message":"文件不存在"}
```

## 8. 订阅下载进度

```http
GET /download-progress
```

无参数。响应类型为 `text/event-stream;charset=UTF-8`，连接会保持打开。

```bash
curl --no-buffer 'http://127.0.0.1:7765/download-progress'
```

浏览器调用示例：

```javascript
const events = new EventSource('http://127.0.0.1:7765/download-progress');
events.onmessage = (event) => {
  const progress = JSON.parse(event.data);
  if (progress.type === 'download-progress') {
    console.log(progress.index, progress.total);
  }
};
// 不再需要进度时调用 events.close()。
```

当前内置网页读取的事件字段是 `type`、`index`、`total`。这条流提供共享下载进度，没有 `task_id`，不能用于区分多个调用方的任务。空闲时实测首先收到 SSE 注释 `: this is a test stream`；它不是 JSON 业务消息。

## 9. 推荐调用顺序

1. 调用 `/search/aggregated`，选择一个搜索结果。
2. 把结果中的 `url` 传给 `/book-fetch`，等待抓取结束；遇到 `429` 时稍后重试。
3. 调用 `/local-books`，确认目标文件已生成或更新。
4. 使用列表中的 `name` 调用 `/book-download`，保存返回的文件。

核对来源：当前运行服务的 HTTP 响应、`app.jar` Servlet 字节码和内置网页、`web/async-download.js`、`nginx.conf` 和 `docker-compose.yml`。本次没有发起整本小说抓取，抓取流程依据当前后端实现、网页和配置说明。
