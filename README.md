# sh-bff — Backend for Frontend / edge gateway

The single process the browser talks to in the **Smart Home AI** project. It
serves the web app, terminates authentication, owns the home database, keeps a
live view of the physical devices, and forwards assistant requests to the AI
layer. Everything the front‑end needs is behind one origin, one port, one
deployable.

> Part of a portfolio built as a set of independent repos — see
> [`sh-infra`](https://github.com/smart-home-ia-sample/sh-infra) for the whole
> picture. This repo is the Java/Spring Boot half of a deliberately polyglot
> stack (the orchestrator, agents, MCP server and discovery service are Python).

---

## Where it sits

```
                 ┌───────────────────────── sh-bff (this repo) ─────────────────────────┐
  Browser ─────► │  static SPA  ·  JWT filter on /api/**  ·  home/room/device CRUD (H2)  │
   (one origin)  │  /api/home-status  ◄── MQTT read-model                                │
                 │  /api/agui/run     ──► streaming proxy                                │
                 └───────┬─────────────────────────┬───────────────────────────┬─────────┘
                         │                         │                           │
                   H2 file / Postgres        Mosquitto (MQTT)          Python orchestrator
                                                   │                    (LangGraph + agents)
                                             device-sim (physical layer)
```

The Dashboard reads device state straight from the BFF (no AI involved). The
assistant chat is the only path that reaches the Python orchestrator, and it
goes through the BFF as a proxy so the browser still sees a single origin.

## What it does

| Responsibility | How |
| --- | --- |
| **Serve the web app** | The `sh-frontend` React/Vite build is baked into the jar at image‑build time; Spring Boot serves it as static content with an `index.html` fallback for client‑side routes. |
| **Authentication** | `JwtAuthFilter` (`OncePerRequestFilter`) guards every `/api/**` route. `POST /auth/login` issues an HS256 JWT (60 min, no refresh); one demo user. A bad or missing token is `401` *before* any handler or upstream call runs. |
| **Persistence** | Spring Data JPA over **H2** (embedded file) by default, or an external **Postgres** — driver and dialect are auto‑detected from `DB_URL`. The BFF is the only writer. Schema: `homes → rooms → devices`, each device carrying a self‑announced **capability descriptor** (JSON column). |
| **Live home status** | `MqttGateway` (Eclipse Paho) subscribes to `home/+/+/+/state` and keeps an in‑memory read‑model. `GET /api/home-status` streams it as SSE (full snapshot, then per‑device deltas); `GET /api/home-status/snapshot` is the one‑shot form. Pure device state — never the orchestrator. |
| **Device commands** | `POST /api/devices/{id}/command` maps a semantic action to a state change, validates it against the device's announced capabilities, publishes `.../set`, and blocks on the `.../state` echo (≈5 s → `504`). |
| **Assistant proxy** | `POST /api/agui/run` is hand‑forwarded to the orchestrator with `StreamingResponseBody` so SSE events flow through unbuffered. |
| **Capability ingest** | `DeviceCapabilityIngestor` listens for the retained `.../capabilities` a device announces and persists it onto the row — the announce is the source of truth, the seed only pre‑fills. |
| **Seed** | On an empty database the demo user gets a model home (1 home, 5 rooms, 13 devices). Idempotent, race‑safe. |

## Strategy adopted in this repo

- **BFF pattern taken literally.** One process is the static host, the auth
  edge, the API gateway and the persistence tier. The browser never needs CORS,
  a second port, or knowledge of the services behind it.
- **Servlet MVC + a hand‑rolled streaming proxy, not a gateway framework.**
  Spring Cloud Gateway was tried and dropped: its client emits requests that
  uvicorn's strict HTTP parser rejects. A tiny `HttpClient` +
  `StreamingResponseBody` proxy gives full control over the request line and
  keeps SSE flowing. Auth is a plain servlet filter for the same reason —
  predictable, in‑process, `401` before work happens.
- **Stateless by construction.** JWT only, no `HttpSession`. Leave `DB_URL`
  unset and you get a single‑instance H2 file; point it at Postgres and the same
  image runs replicated with no code change.
- **CQRS‑lite for the Dashboard.** Device state is a projection the BFF
  maintains from MQTT retained messages, decoupled from both the database and
  the AI layer. Reads are cheap and never trigger reasoning.
- **Devices describe themselves.** Following the Matter/HomeKit commissioning
  idea, `device-sim` announces what each device can do; the BFF stores that and
  validates every command against it, instead of hard‑coding per‑type rules.
- **The frontend is a build input, not a runtime dependency.** The image bakes a
  specific SPA build in (see below), so a running container has no coupling to
  `sh-frontend` at all.

## Layout

```
src/main/java/com/smarthome/bff/
  auth/     JWT service, login controller, /api/** filter, @CurrentUser
  home/     HomeService (CRUD), HomeStatusService (read-model), DeviceActions
            (semantic action → validated state change), capability ingest,
            domain/ (JPA entities) · repo/ · web/ (controllers, DTOs)
  mqtt/     MqttGateway (Paho client, command + echo, read-model)
  proxy/    OrchestratorProxyController (streaming /api/agui/run)
  seed/     DataSeeder
  web/      ApiExceptionHandler
src/main/resources/application.yml     all config, env-overridable
src/test/…                             46 tests (see below)
Dockerfile                            multi-stage: build SPA → package jar → jre
```

## Running

**Standalone:**

```bash
./mvnw test                       # 46 tests, no broker/DB needed (H2 mem, MQTT off)
./mvnw spring-boot:run            # http://localhost:8080, embedded H2 file in ./data
docker build -t sh-bff .          # image with the SPA from sh-frontend/main baked in
```

**As part of the stack:** `sh-infra`'s `docker-compose.build.yml` builds this
with `FRONT_SOURCE=local` (SPA from the sibling `../sh-frontend` checkout) and
wires Mosquitto, `device-sim` and the orchestrator.

### Frontend source (`--build-arg FRONT_SOURCE=`)

| value | behaviour |
| --- | --- |
| `git` *(default)* | clone `sh-frontend`, build it. `FRONT_REF` picks the ref and **falls back to `main`** if it doesn't exist. CI passes the BFF's own branch name, so a coordinated front/back change builds together. |
| `local` | build `../sh-frontend` supplied as the `sh_frontend` build context (offline dev). |
| `none` | skip the SPA — API‑only image. |

## Configuration (env)

| Variable | Default | Purpose |
| --- | --- | --- |
| `JWT_SECRET` | dev placeholder | HS256 signing key (≥ 32 bytes). **Override everywhere real.** |
| `DEMO_USER` / `DEMO_PASS_HASH` | `demo` / bcrypt("demo") | the single login |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | H2 file `./data/bff` | set to `jdbc:postgresql://…` to run on Postgres |
| `ORCHESTRATOR_URL` | `http://orchestrator:8500` | assistant proxy target |
| `MQTT_ENABLED` / `MQTT_HOST` / `MQTT_PORT` / `MQTT_USERNAME` / `MQTT_PASSWORD` | `true` / `mosquitto` / `1883` / `smarthome` | physical‑layer link; `false` → device commands `503`, snapshot still builds |
| `MQTT_COMMAND_TIMEOUT_MS` | `5000` | how long a command waits for the `.../state` echo |
| `LOG_LEVEL` | `INFO` | root log level |

## Tests & CI

`./mvnw test` — 46 tests: `JwtServiceTest` (6), `HomeServiceTest` (8, Mockito),
`DeviceActionsTest` (7), `BffIntegrationTest` (25, `@SpringBootTest` + MockMvc,
`@Transactional` rollback per test). The `test` profile uses in‑memory H2 and
disables MQTT, so no broker or database is required.

| Workflow | Gate |
| --- | --- |
| `ci` | builds the Docker image on every PR; on `main` also pushes `ghcr.io/<owner>/sh-bff:latest` + `:<sha>` |
| `test` | `mvnw test` + JaCoCo; `jacoco:check` fails under the coverage floor in `pom.xml`, and a coverage summary is posted on the PR |
| `codeql` | CodeQL analysis (Java) on PRs, `main`, and weekly |
