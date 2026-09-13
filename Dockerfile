# syntax=docker/dockerfile:1
#
# Production image: the React app is built and bundled into the Spring Boot
# jar, so the whole of InboxIQ is served from ONE origin. That keeps the
# session and CSRF cookies first-party — no CORS, no SameSite=None, and no
# breakage in browsers that block third-party cookies (Safari, Firefox).
#
#   docker build -t inboxiq .
#   docker run -p 8080:8080 --env-file backend/.env inboxiq

# ---- 1. Frontend -----------------------------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. Backend, with the SPA as static resources ---------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS backend
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline || true
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
RUN mvn -q -B clean package -DskipTests

# ---- 3. Runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S inboxiq && adduser -S inboxiq -G inboxiq
COPY --from=backend /app/backend/target/*.jar app.jar
USER inboxiq

# Sized for small instances (e.g. 512 MB free tiers): cap the heap relative
# to the container limit, use the low-overhead serial GC, and exit cleanly on
# OOM so the platform restarts the container. Override via JAVA_TOOL_OPTIONS.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/actuator/health" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
