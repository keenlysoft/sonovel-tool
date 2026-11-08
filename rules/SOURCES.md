# 香港书源配置

默认启用 `hongkong-rules.json`，原始 `main-rules.json` 保留。2026-11-07 在溪笔香港源站检查旧配置时，只有原书源 11 能返回搜索结果；其他来源分别存在域名迁移、地区限制、连接关闭、循环跳转或过期证书。

当前配置保留前 11 个条目的位置和原 URL，只停用已确认失败的搜索，避免已有书源编号发生变化。新来源追加在末尾：

| ID | 书源 | 配置要点 |
|---|---|---|
| 11 | 顶点小说 | 保留已可用搜索和抓取规则 |
| 12 | 笔趣阁365 | 完整目录在 `/newbook/`；两次搜索应间隔至少 15 秒 |
| 13 | 第八中文网 | 启用目录及章内分页；即使按钮写“下一章”，`_2.html` 仍属于同章 |
| 14 | 笔趣阁info | 搜索缺少作者时，详情页补齐；启用章内分页 |

这些规则显式补全旧 v1.9.6 引擎需要的详情字段和 `pagination` 布尔值，不能直接使用新版规则文件替换。新规则的章节并发均为 2，搜索不批量展开分页，保留正常 TLS 校验。

原书源 1、3、4、7 的新域名已从官方资料及跳转核实，但官方标明不支持非大陆 IP，在香港仍无法连接。2 的请求被上游关闭，5 出现自重定向循环，6 证书过期，8 无法连接，9、10 同样有地区限制。停用失败搜索不代表这些上游网站已修复；也没有通过关闭证书校验或使用未经配置的代理绕过问题。

## 引擎兼容修复

原 JAR 只包含 macOS ARM 的 Javet 原生库。Dockerfile 使用 glibc 系列 JRE，按 Linux x86_64 / arm64 下载同版本原生 JAR，并通过 classpath 加载；两个架构的 SHA256 均固定。

`patches/` 中的小型补丁先于原 JAR 加载：

- `TocList` 按 URL 保留最后出现的目录项；同一标题的不同章节不会互相覆盖。
- `JsCaller` 每次执行后关闭 V8 运行时及引用，避免虚拟搜索线程遗留原生资源。
- `ChapterParser` 构建时仅修正分页 URL 的页码正则，避免 `_10.html` 等多位页码被误认成下一章；原类的其他字节码保持不变。

原 `app.jar` 保持不变。更换核心版本前须重新核对这些类的 API 及 Javet 版本。

## 验证方式

`tests/RuleSmoke.java` 使用实际引擎读取元数据、完整目录及两章正文。它独立采集原始目录锚点，按 URL 核对顺序和完整性，并确认每个正文分页的校验片段进入最终文本；仅输出标题、长度和哈希，不输出正文。每次只验证一个书源，最多 80 个 HTTP 请求、每 URL 3 次和 120 秒内部时限，外层进程另限 180 秒。

`tests/TocListTest.java` 和 `tests/JsCallerTest.java` 分别验证同名章节保留、重复目录项顺序，以及正常、异常和两线程调用后的原生运行时释放。`tests/ChapterPaginationTest.java` 使用本机 HTTP 样例验证第 9→10→11 页的合并和下一章边界，无须访问第三方站点。不要仅用 HTTP 200 或空搜索数组判断书源可用。

来源：

- [官方书源地区与限制说明](https://github.com/freeok/so-novel/blob/main/BOOK_SOURCES.md)
- [官方当前规则](https://github.com/freeok/so-novel/blob/main/bundle/rules/main.json)
- [Javet 5.0.1 安装要求](https://github.com/caoccao/Javet/blob/5.0.1/docs/tutorial/basic/installation.rst)
- [Javet x86_64 原生依赖与摘要](https://repo.maven.apache.org/maven2/com/caoccao/javet/javet-v8-linux-x86_64/5.0.1/)
- [Javet arm64 原生依赖与摘要](https://repo.maven.apache.org/maven2/com/caoccao/javet/javet-v8-linux-arm64/5.0.1/)
