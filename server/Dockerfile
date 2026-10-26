# ── 构建:先只拷 pom 拉依赖,单独成层;只改代码时这一层命中缓存,不用重新下载 ──
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# 不加 -DskipTests:单测都是纯单元测试(不连库),挂了就不出镜像
RUN mvn -B -ntp package

# ── 运行 ──
FROM eclipse-temurin:17-jre
# 工作目录放在 /app/server,与本地"在 server/ 下启动"同构:
# application.yml 里的 ../corpus、../logs、../.env 原样解析成 /app/corpus、/app/logs、/app/.env
WORKDIR /app/server
RUN useradd --system --uid 10001 app \
    && mkdir -p /app/corpus /app/logs \
    && chown -R app /app
COPY --from=build /src/target/*.jar app.jar
# 部署时前面总有反代(web 容器的 nginx,或宿主机的 Caddy/nginx):让 getRemoteAddr() 取 X-Forwarded-For 里的真实 IP,
# 否则验证码的按 IP 限流会把所有人算成同一个代理 IP。只信任内网段来的代理头,公网直连伪造不了
ENV SERVER_FORWARD_HEADERS_STRATEGY=native
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
