# CORTEX Postman collections

This folder satisfies rule 25.2 / 25.3 of the strict-rules contract:
every REST service ships a Postman v2.1 collection plus one environment
file per deployment target.

## Files

| File                                              | Purpose                                            |
|---------------------------------------------------|----------------------------------------------------|
| `log-gateway.postman_collection.json`             | Public + admin + error scenarios for log-gateway. |
| `log-gateway.postman_environment_local.json`      | Local dev (`http://localhost:8080`).              |
| `log-gateway.postman_environment_staging.json`    | Staging cluster.                                  |
| `log-gateway.postman_environment_prod.json`       | Production cluster.                               |

Additional services land here as P4-P8 progress.

## Running locally (Postman UI)

1. Open Postman -> Import -> drop both files above.
2. Pick the `log-gateway :: local` environment in the top-right selector.
3. Start the gateway:

   ```bash
   ./mvnw -pl log-gateway -am spring-boot:run
   ```

4. Run the "Health > GET /api/v1/health" request. Expect 200 and a body
   with `status: "UP"`.

## Running headless (Newman)

[Newman](https://learning.postman.com/docs/collections/using-newman-cli/installing-running-newman/)
is the official CLI runner. CI uses it as a smoke gate.

```bash
# one-time
npm install -g newman

# all environments
newman run postman/log-gateway.postman_collection.json \
       -e postman/log-gateway.postman_environment_local.json \
       --reporters cli,junit \
       --reporter-junit-export target/newman/log-gateway-junit.xml
```

## Environment variables

Every environment file defines:

| Key         | Purpose                                                    |
|-------------|------------------------------------------------------------|
| `base_url`  | Service root URL. NO trailing slash.                       |
| `tenant_id` | Default tenant id sent on multi-tenant endpoints.          |
| `jwt_token` | Bearer token. Leave blank locally; CI injects in staging.  |

## Adding a request

1. Place the request under one of the existing folders (`Health`,
   `Admin`, `Error Scenarios`) or create a new folder for a new
   functional area.
2. Always send `X-Request-Id: {{request_id}}` so the gateway echoes
   the same correlation id back; the collection's top-level test hook
   asserts the echo.
3. Add a `pm.test(...)` block that asserts the HTTP status and at least
   one body field.
4. Re-export the collection from Postman to keep this file in sync.

## Rules anchor

- 25.2: Postman collection + per-environment files MANDATORY for every
  REST service.
- 25.3: collection MUST live under `postman/` at the repo root.
- 25.5: every request carries `X-Request-Id` so correlation works end
  to end (see also rule 17.5).
- 25.7: folder layout is `Auth/` (added in P3.1), per-module folders,
  `Admin/`, `Error Scenarios/`.
