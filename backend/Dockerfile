# API-only image (no frontend). For production use the root Dockerfile,
# which bundles the React app into the same jar so everything is served from
# one origin — see DEPLOYMENT.md.
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# Cache dependency resolution separately from source changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true

COPY src src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S inboxiq && adduser -S inboxiq -G inboxiq
COPY --from=build /build/target/*.jar app.jar
USER inboxiq
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
