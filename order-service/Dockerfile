# Build the jar first:  (cd order-service && ./mvnw -DskipTests package)
FROM eclipse-temurin:17-jre

# Run as an unprivileged user, never root.
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --chown=root:root target/order-service-0.0.1-SNAPSHOT.jar app.jar

USER app

# Size the heap from the container's memory limit; exit on OOM so Docker restarts the container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
