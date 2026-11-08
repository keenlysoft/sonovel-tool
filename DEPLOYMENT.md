# SoNovel Lite 部署

## 启动

```bash
docker-compose up -d --build
```

访问 <http://localhost:7765/>。端口只绑定本机回环地址。

## 常用命令

```bash
docker-compose ps
docker-compose logs -f sonovel
docker-compose restart
docker-compose down
```

## 数据和配置

| 路径 | 用途 |
|---|---|
| `config.ini` | 运行配置，已固定 TXT 输出 |
| `rules/` | 搜索和章节解析规则 |
| `downloads/` | 持久化下载文件 |

`downloads/` 不会被镜像重建或 `docker-compose down` 删除。

## 更新 `app.jar`

1. 替换根目录的 `app.jar`；先核对 Javet 版本及 `patches/` 的 API 兼容性。当前原生依赖与补丁针对 v1.9.6，不能盲目用于其他核心版本。
2. 重建并启动：

```bash
docker-compose up -d --build
```

## 排错

```bash
docker-compose ps
docker-compose logs --tail=200 sonovel
docker exec sonovel-proxy nginx -t
curl -I http://localhost:7765/
```

如果书源失效，优先更新 `rules/`；如果下载失败，先检查 `downloads/` 目录是否可写。

## 与溪笔同机部署

目标是溪笔香港源站 `149.104.31.74`。SoNovel 独立使用 Docker Compose，端口保留为 `127.0.0.1:7765`，与溪笔默认 `SONOVEL_BASE_URL=http://localhost:7765` 一致，无须修改或重启溪笔。

- 发布目录：`/opt/sonovel/releases/<release-id>`，当前版本入口为 `/opt/sonovel/current`。
- 下载目录：`/opt/sonovel/downloads`；各发布目录中的 `downloads` 链接到此目录。
- 发布包：只包含 JAR、Dockerfile、Compose、Nginx 配置、书源、网页补丁和文档，不包含本机已下载小说、Git 元数据或临时文件。
- 资源限制：Java 容器最多 1 核 CPU、384 MiB 内存；Nginx 最多 0.25 核、64 MiB；章节抓取并发为 2。两个容器均保留自动重启与日志轮转。
- 仅提供同机访问，不新增公网域名或放行公网端口。

上传并校验发布包 SHA256，解压到新的发布目录，准备下载目录链接并设置 `current` 后执行：

```bash
cd /opt/sonovel/current
COMPOSE_PARALLEL_LIMIT=1 docker compose -p sonovel config --quiet
COMPOSE_PARALLEL_LIMIT=1 docker compose -p sonovel up -d --build
docker compose -p sonovel ps
curl --fail http://127.0.0.1:7765/version
curl --fail http://localhost:7765/version
```

验收须同时检查健康状态、实际搜索结果、持久化文件读回，以及溪笔部署版本的 SoNovel 适配器调用。对比部署前后 `xibi.service` 的 MainPID、重启次数和 `https://xibi.ai/healthz`，确认现有服务没有因本次部署重启或异常。

后续更新时先记录旧 `current` 目标与旧镜像 ID。新版本验证失败时，用旧发布目录与旧镜像恢复；下载目录独立保留。首次部署需要撤回时，仅在 `/opt/sonovel/current` 执行 `docker compose -p sonovel down`，保留发布包和下载文件。

## 书源与 Linux 兼容性验收

当前默认配置使用 `rules/hongkong-rules.json`。书源及兼容补丁详见 [rules/SOURCES.md](rules/SOURCES.md)。改规则后需要重建并重新创建 SoNovel 容器，旧核心会缓存规则；不需要重启溪笔。

构建阶段只编译小型补丁，`javac` 使用 128 MiB 堆和一个工作 CPU；最终运行阶段使用 glibc JRE。Linux JavaScript 依赖从 Maven Central 下载并检查固定 SHA256，不能改回缺少依赖的 Alpine 运行镜像或 `java -jar` 启动方式。

验证时先用独立回环端口启动候选容器，使用 Java 21 编译测试类，再在候选运行镜像中串行执行：

```bash
# 编译 classpath 使用 app.jar；只执行一个编译任务。
javac -proc:none -J-Xmx128m -J-XX:ActiveProcessorCount=1 \
  -cp app.jar tests/TocListTest.java tests/JsCallerTest.java \
  tests/ChapterPaginationTest.java tests/RuleSmoke.java

# 以下 classpath 为运行容器内路径，tests 挂载到 /work/tests。
java -Xmx128m -XX:ActiveProcessorCount=1 \
  -cp '/app/patches:/app/app.jar:/app/lib/*:/work/tests' TocListTest
java -Xmx128m -XX:ActiveProcessorCount=1 \
  -cp '/app/patches:/app/app.jar:/app/lib/*:/work/tests' JsCallerTest
java -Xmx128m -XX:ActiveProcessorCount=1 -Dconfig.file=/app/config.ini \
  -cp '/app/patches:/app/app.jar:/app/lib/*:/work/tests' ChapterPaginationTest
timeout 180 java -Xmx256m -XX:ActiveProcessorCount=1 \
  -Dconfig.file=/app/config.ini \
  -cp '/app/patches:/app/app.jar:/app/lib/*:/work/tests' \
  RuleSmoke 12 'https://www.biquge365.net/book/44911/' 2
```

真实解析测试应对每个启用书源分别执行，并保留失败及修正记录。完整目录和抽样正文通过后，再对章节数可控的短篇进行一次实际 `/book-fetch` → `/local-books` → `/book-download` 验收；确认书名、作者、末章及 UTF-8 文件后才切换生产版本。

网页缺作者时的文件确认回归可在本机低负荷执行：

```bash
node --test --test-concurrency=1 tests/async-download.test.cjs
```
