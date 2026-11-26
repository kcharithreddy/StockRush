# Builds and runs order-service. Only Docker is needed: the code is compiled inside the build stage.
#   docker compose up -d --build

# --- build stage: the official Maven image (Maven 3.9.11, the same version as ./mvnw, on JDK 17) ---
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY src/ src/
# Downloaded libraries are kept in a BuildKit cache, so later builds only fetch what changed.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package && cp target/order-service-0.0.1-SNAPSHOT.jar /app.jar

# --- runtime stage: JRE only; no compiler, Maven or source code in the shipped image ---
FROM eclipse-temurin:17-jre

# Run as an unprivileged user, never root.
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=root:root /app.jar app.jar

USER app

# Size the heap from the container's memory limit; exit on OOM so Docker restarts the container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
