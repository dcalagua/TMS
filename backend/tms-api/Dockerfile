# TMS by EBIM - backend container image.
#
# Two stages: a JDK image that builds the jar, and a JRE image that runs it. The build tools,
# the sources and the local Maven repository stay in the first stage, so what ships is a JRE,
# the application jar and nothing else.

# --------------------------------------------------------------------------------------------
# Stage 1 - build
# --------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# The Maven wrapper committed with the project, rather than a Maven base image: the build in
# CI, on a developer machine and in this container then resolve the same Maven version.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Dependencies resolve in their own layer, keyed only on pom.xml. Editing application code
# therefore does not re-download the dependency tree on every rebuild.
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/

# Tests are not run here. The suite needs Docker for Testcontainers, which is not available
# inside a build container, and a deployment image must not be the first place the tests run
# anyway - CI owns that gate.
RUN ./mvnw -B -q -DskipTests package \
    && mv target/tms-api-*.jar target/app.jar

# --------------------------------------------------------------------------------------------
# Stage 2 - runtime
# --------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Unprivileged: nothing this process does needs root, and a JVM running as root in a container
# is one container escape away from being root on the host.
RUN addgroup -S tms && adduser -S -G tms tms

WORKDIR /app
COPY --from=build --chown=tms:tms /build/target/app.jar app.jar

USER tms

# Render, and most container platforms, inject the port to listen on as PORT. The application
# reads TMS_API_PORT (see application.yml), so the two are bridged at startup rather than by
# asking the operator to set both to the same value.
ENV TMS_API_PORT=8080
EXPOSE 8080

# Container-aware heap sizing. Without it the JVM sizes the heap against the host's total
# memory, not the container limit, and gets OOM-killed on a small instance.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:+UseSerialGC"

# `exec` so the JVM becomes PID 1 and receives SIGTERM directly, which is what makes
# `server.shutdown: graceful` actually drain in-flight requests before the process dies.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar app.jar --server.port=${PORT:-${TMS_API_PORT}}"]
