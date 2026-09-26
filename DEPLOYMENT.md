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
