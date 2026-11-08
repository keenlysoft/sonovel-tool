# 只编译小型兼容补丁，不重新编译或修改原始 JAR。
FROM eclipse-temurin:21-jdk-alpine AS parser-patches
WORKDIR /build
COPY app.jar /build/app.jar
COPY patches /build/patches
RUN mkdir -p /build/classes && \
    javac -proc:none -J-Xmx128m -J-XX:ActiveProcessorCount=1 \
      -cp /build/app.jar -d /build/classes \
      /build/patches/com/pcdd/sonovel/util/*.java
RUN javac -proc:none -J-Xmx128m -J-XX:ActiveProcessorCount=1 \
      -d /build/tools /build/patches/PatchChapterParser.java && \
    java -Xmx64m -XX:ActiveProcessorCount=1 -cp /build/tools \
      PatchChapterParser /build/app.jar /build/classes

# Javet 的 Linux 原生库依赖 glibc，不能使用 Alpine/musl。
FROM eclipse-temurin:21-jre

# 设置工作目录
WORKDIR /app

# 复制应用程序文件
COPY app.jar /app/
COPY config.ini /app/
COPY rules /app/rules/

# app.jar 只含 macOS ARM 库；按容器架构补齐同版本 Linux V8 依赖。
# 固定版本及 Maven Central SHA256，避免获取不匹配的原生库。
RUN set -eu; \
    case "$(uname -m)" in \
      x86_64) javet_artifact=javet-v8-linux-x86_64; \
        javet_sha256=71348f9526aba0303257f17601f1428c138aa0fc7e79b33d4d1b0389117eae9e ;; \
      aarch64) javet_artifact=javet-v8-linux-arm64; \
        javet_sha256=6b8706ac1d548e62e96873dc61410d270a1662ef48cfac9e80cfc42269f36cb8 ;; \
      *) echo "Unsupported Javet architecture" >&2; exit 1 ;; \
    esac; \
    mkdir -p /app/downloads /app/lib; \
    curl --fail --silent --show-error --location --connect-timeout 10 \
      --max-time 120 --retry 2 --retry-delay 2 \
      "https://repo.maven.apache.org/maven2/com/caoccao/javet/${javet_artifact}/5.0.1/${javet_artifact}-5.0.1.jar" \
      --output /app/lib/javet-native.jar; \
    echo "${javet_sha256}  /app/lib/javet-native.jar" | sha256sum --check -

COPY --from=parser-patches /build/classes/ /app/patches/

# 兼容旧引擎在云主机并发线程中初始化 V8 的已知 PKU 问题。
ENV JAVET_DISABLE_PKU=1

# 暴露 Web UI 端口（根据 config.ini 中的端口配置）
EXPOSE 7765

# 精简版只运行 WebUI；SerialGC 更适合这类小型本地服务。
CMD ["java", \
    "-Xms32m", \
    "-Xmx256m", \
    "-XX:+UseSerialGC", \
    "-Dconfig.file=/app/config.ini", \
    "-Dmode=web", \
    "-cp", "/app/patches:/app/app.jar:/app/lib/*", \
    "com.pcdd.sonovel.Main"]
