# 运行编译好的 JAR 只需要 JRE，无需完整 JDK。
FROM eclipse-temurin:21-jre-alpine

# 设置工作目录
WORKDIR /app

# 复制应用程序文件
COPY app.jar /app/
COPY config.ini /app/
COPY rules /app/rules/

# 创建下载目录
RUN mkdir -p /app/downloads

# 暴露 Web UI 端口（根据 config.ini 中的端口配置）
EXPOSE 7765

# 精简版只运行 WebUI；SerialGC 更适合这类小型本地服务。
CMD ["java", \
    "-Xms32m", \
    "-Xmx256m", \
    "-XX:+UseSerialGC", \
    "-Dconfig.file=/app/config.ini", \
    "-Dmode=web", \
    "-jar", "/app/app.jar"]
