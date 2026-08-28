# syntax=docker/dockerfile:1

# 1. Build the jar, baking in the pre-built SPA when FRONT_DIST_URL is given.
#    FRONT_DIST_URL points at a dist.tar.gz release asset from sh-frontend
#    (tarball of the Vite `dist/` contents). Empty -> API-only image (no SPA).
FROM maven:3.9-eclipse-temurin-21 AS build
ARG FRONT_DIST_URL=""
WORKDIR /build
COPY pom.xml .
COPY src src
RUN if [ -n "$FRONT_DIST_URL" ]; then \
      mkdir -p src/main/resources/static && \
      curl -fsSL "$FRONT_DIST_URL" | tar xz -C src/main/resources/static ; \
    fi
RUN mvn -B -DskipTests package

# 2. Runtime
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/bff.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
