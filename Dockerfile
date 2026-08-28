# syntax=docker/dockerfile:1

# The React SPA (sh-frontend) is built here and baked into the jar; Spring Boot
# then serves it as static content with an index.html fallback for client
# routes. Pick where the SPA source comes from with --build-arg FRONT_SOURCE:
#
#   git   (default) clone sh-frontend and build it. FRONT_REF selects the ref;
#                   if that ref does not exist it falls back to main. CI passes
#                   the BFF's own branch name so a coordinated FE/BE change can
#                   be built together.
#   local           build ../sh-frontend passed as the named build context
#                   `sh_frontend` (docker build --build-context sh_frontend=../sh-frontend).
#                   Used by sh-infra's docker-compose.build.yml for offline dev.
#   none            skip the SPA -> API-only image.
ARG FRONT_SOURCE=git

# --- SPA source: clone sh-frontend at a ref, fall back to main ---
FROM node:22-slim AS front-git
ARG FRONT_REPO=https://github.com/smart-home-ia-sample/sh-frontend.git
ARG FRONT_REF=main
WORKDIR /front
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch "$FRONT_REF" "$FRONT_REPO" . \
    || git clone --depth 1 --branch main "$FRONT_REPO" .
RUN npm ci && npm run build   # -> /front/dist

# --- SPA source: local sibling checkout (offline dev) ---
# Copy only source (never the host node_modules — it may hold win32 binaries)
# and let `npm ci` build a clean Linux dependency tree.
FROM node:22-slim AS front-local
WORKDIR /front
COPY --from=sh_frontend package.json package-lock.json ./
RUN npm ci
COPY --from=sh_frontend index.html tsconfig.json tsconfig.app.json tsconfig.node.json tsconfig.test.json vite.config.ts vitest.config.ts ./
COPY --from=sh_frontend src ./src
RUN npm run build   # -> /front/dist

# --- SPA source: none (API-only) ---
FROM alpine:3 AS front-none
WORKDIR /front
RUN mkdir -p dist && touch dist/.keep

FROM front-${FRONT_SOURCE} AS front

# --- Build the jar, baking in whatever `front` produced ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mkdir -p src/main/resources/static
COPY --from=front /front/dist/ src/main/resources/static/
RUN mvn -B -DskipTests package

# --- Runtime ---
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/bff.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
