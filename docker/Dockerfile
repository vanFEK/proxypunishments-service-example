# syntax=docker/dockerfile:1

# --- build -------------------------------------------------------------------
FROM gradle:9.3.0-jdk25 AS build
WORKDIR /workspace

# Dependency layer first: source changes should not re-download the world.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN gradle --no-daemon dependencies --configuration runtimeClasspath -q || true

COPY src src
RUN gradle --no-daemon bootJar -x test

# --- runtime -----------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# Runs unprivileged: a compromised process should not own the container.
RUN addgroup -S proxybans && adduser -S -G proxybans proxybans

WORKDIR /app
COPY --from=build --chown=proxybans:proxybans /workspace/build/libs/*.jar app.jar

USER proxybans
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
