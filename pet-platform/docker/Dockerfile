# 多阶段构建：编译期用 JDK 21 + Maven，运行期只留 JRE。
# 构建上下文是仓库根目录（见 docker-compose.yml 的 context: .）。

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 依赖缓存在 BuildKit 卷里，重复构建只需重编改动模块
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

# 容器的 healthcheck 用 curl 探活；JRE 基础镜像不保证自带 curl。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 不用 root 跑
RUN useradd --system --uid 10001 pet
COPY --from=builder /build/pet-platform-app/target/pet-platform-app-1.0.0-SNAPSHOT.jar app.jar
RUN chown -R pet:pet /app
USER pet

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# stdout/stderr 强制 UTF-8：容器内 locale 是 POSIX，否则中文日志乱码
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar /app/app.jar"]
