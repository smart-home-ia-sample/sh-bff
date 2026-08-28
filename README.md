# sh-bff

Edge gateway: serves the React SPA, terminates JWT auth, persists home / rooms /
devices (H2, or an external Postgres via `DB_URL`), talks MQTT to the simulated
physical layer (device commands + the live `/api/home-status` SSE), and proxies
`/api/agui/run` to the orchestrator. The only service the browser talks to.

The SPA is built in **sh-frontend** and pulled in at image-build time via
`--build-arg FRONT_DIST_URL=<dist.tar.gz release asset>`. Without it the image is
API-only.

```
./mvnw test
docker build --build-arg FRONT_DIST_URL=... -t sh-bff .
```
