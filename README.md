# SoNovel Lite

只保留小说搜索和 TXT 下载的本地 Web 工具。

## 功能

- 聚合搜索多个书源
- 连续点击多本书，后台自动排队下载
- 所有新下载统一生成 UTF-8 TXT
- 已下载文件保存在 `downloads/`
- 香港部署使用 `hongkong-rules.json`，保留旧书源编号并加入可达书源
- Linux 镜像补齐 JavaScript 原生依赖，目录按章节 URL 去重，保留同名不同章

不再包含 Obsidian 插件、AI 分析、时间线、笔记生成和多格式解析功能。

## 启动

需要 Docker Desktop 或 Docker Engine + Compose。

```bash
docker-compose up -d --build
```

打开 <http://localhost:7765/>。

也可使用项目脚本：

```bash
./start-docker.sh start
./start-docker.sh status
./start-docker.sh logs
./start-docker.sh stop
```

## 目录

```text
app.jar              SoNovel 核心服务
config.ini           搜索、抓取和 TXT 输出配置
rules/               书源规则
patches/             v1.9.6 的目录与 JavaScript 资源兼容补丁
tests/               文件匹配回归及有界的真实解析验收
web/                 非阻塞下载队列
downloads/           下载文件（不纳入 Git）
Dockerfile           精简 JRE 运行镜像
docker-compose.yml   Java 服务与 Nginx 网关
```

Web 端口只绑定 `127.0.0.1`。`/book-fetch` 由网关串行保护，避免 SoNovel 同时处理多本书时产生错误文件。

## 配置

`config.ini` 中最常用的项：

```ini
[download]
download-path = downloads
extname = txt

[source]
search-limit = 30
active-rules = hongkong-rules.json

[crawl]
min-interval = 200
max-interval = 400
```

更多启停和更新方法见 [DEPLOYMENT.md](DEPLOYMENT.md)。

书源适用地区、保留编号及验证范围见 [rules/SOURCES.md](rules/SOURCES.md)。首次构建会从 Maven Central 获取与容器架构匹配的 Javet 5.0.1 原生依赖，并检查固定 SHA256；运行镜像只包含 JRE。
