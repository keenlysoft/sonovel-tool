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

1. 替换根目录的 `app.jar`。
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
