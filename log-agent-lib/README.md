# log-agent-lib

Embeddable Java SDK for shipping structured log events to a CORTEX cluster.

This is the **client side** of CORTEX. It is **NOT a Spring Boot
application**. It is a plain Java library that any JVM application
(Spring Boot, Quarkus, Micronaut, plain Java CLI, Android, etc.) can
put on its classpath to send logs to a CORTEX gateway over HTTPS.

See [docs/adr/0013-log-agent-lib-is-lombok-free.md](../docs/adr/0013-log-agent-lib-is-lombok-free.md)
for why the SDK is intentionally Spring-free and Lombok-free.

---

## Requirements

| | Version |
|---|---|
| Java | 17 or newer (the SDK is compiled to bytecode 17) |
| Network | HTTPS reachability to your CORTEX gateway endpoint |
| Dependencies the SDK pulls in | `jackson-databind`, `jackson-datatype-jsr310`, `slf4j-api`, `logback-classic` (optional, only if you use the appender) |

The SDK has **no Spring**, **no Lombok**, **no MapStruct**, **no Resilience4j**.
HTTP transport is the JDK `java.net.http.HttpClient`.

---

## Build and test the SDK locally

From the **repository root** (where `mvnw` lives):

```powershell
# Windows
./mvnw -pl log-agent-lib -am verify
```

```bash
# Linux / macOS
./mvnw -pl log-agent-lib -am verify
```

What this runs:

- Compile with `-Werror` (warnings are errors).
- Checkstyle (universal Javadoc, method length <= 30, parameter count <= 6).
- SpotBugs at High threshold.
- All unit tests under `src/test/java`.
- JaCoCo coverage gate (BUNDLE >= 80% line and >= 80% branch).

A green run looks like this (current state on `main`):

```
[INFO] Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

To install the SDK into your local Maven cache so other modules / projects
can depend on it:

```powershell
./mvnw -pl log-agent-lib -am install -DskipTests
```

---

## Add the SDK to your project

Coordinates (currently a SNAPSHOT - will be promoted to a release in P18):

```xml
<dependency>
    <groupId>io.cortex</groupId>
    <artifactId>log-agent-lib</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

If you only want the programmatic client and not the Logback appender,
exclude `logback-classic`:

```xml
<dependency>
    <groupId>io.cortex</groupId>
    <artifactId>log-agent-lib</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <exclusions>
        <exclusion>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

---

## Quickstart 1 - programmatic client

The simplest possible usage:

```java
import io.cortex.agent.CortexClient;
import io.cortex.agent.CortexClientBuilder;
import io.cortex.agent.LogEntry;
import io.cortex.agent.LogLevel;

import java.time.Instant;
import java.util.Map;

public final class Demo {

    public static void main(final String[] args) throws Exception {
        try (CortexClient client = new CortexClientBuilder()
                .endpoint("https://cortex.example.com/ingest")
                .apiKey("ck_live_xxxxxxxxxxxxxxxxxxxxxxxx")
                .build()) {

            client.send(new LogEntry(
                    Instant.now(),
                    LogLevel.INFO,
                    "checkout-service",
                    "Order accepted",
                    Map.of(
                            LogEntry.LABEL_TENANT, "acme-corp",
                            LogEntry.LABEL_TRACE_ID, "9c1d3e4f...",
                            "order_id", "ord_4827",
                            "amount_usd", "129.95"
                    )
            ));
        }
    }
}
```

Key points:

- `CortexClientBuilder` is fluent; `endpoint` is the only required setting,
  everything else has a sensible default. `apiKey` is optional (omit for an
  unauthenticated gateway, not recommended in production).
- The `service` name and `tenantId` live on each `LogEntry` (set via the
  message and the `labels` map). They are not on the builder. For
  consumers that want a default service / tenant filled in automatically,
  use the Logback appender (Quickstart 3) which carries them as appender
  settings.
- `endpoint` must be an **absolute URI with a host** (`http://` or
  `https://`). Relative paths are rejected at build time.
- `LogEntry` is an immutable record. The `labels` map is defensively
  copied; the SDK stores an unmodifiable view.
- `client.send(...)` is **fail-soft**. Network errors, non-2xx responses,
  and serialization errors are logged at WARN via SLF4J and never thrown.
- `try-with-resources` guarantees the buffered sender is flushed and the
  daemon thread is stopped.

---

## Quickstart 2 - batching with `BufferedSender`

For high-throughput callers, wrap the HTTP client in `BufferedSender`
to coalesce events into batches:

```java
import io.cortex.agent.CortexClient;
import io.cortex.agent.CortexClientBuilder;
import java.time.Duration;

try (CortexClient client = new CortexClientBuilder()
        .endpoint("https://cortex.example.com/ingest")
        .apiKey(System.getenv("CORTEX_API_KEY"))
        .batchSize(500)                       // up to 500 events per HTTP POST
        .flushInterval(Duration.ofSeconds(2)) // flush every 2 seconds
        .build()) {

    for (Order order : queue) {
        client.send(toLogEntry(order));   // returns immediately; queued
    }
    client.flush();                       // optional: force flush before close
}
```

Defaults:

| Setting | Default |
|---|---|
| `batchSize` | 256 |
| `flushInterval` | 1 s |
| `connectTimeout` | 2 s |
| `requestTimeout` | 5 s |
| `maxRetries` | 3 |
| `retryBackoff` | 200 ms (fixed) |
| `buffered` | `true` (set `false` for synchronous tests) |

Backpressure: the internal queue is `batchSize * 8` deep. When full,
new events are **dropped** (and counted via the WARN log line) rather
than blocking the caller. The SDK's contract is to never slow down the
host application.

---

## Quickstart 3 - Logback appender

If your application already uses SLF4J + Logback, the easiest integration
is to add `CortexLogbackAppender` to your `logback.xml`. Every log call
your app makes is automatically shipped to CORTEX with no code change.

`src/main/resources/logback.xml`:

```xml
<configuration>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} %-5level [%thread] %logger - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="CORTEX" class="io.cortex.agent.logback.CortexLogbackAppender">
        <endpoint>https://cortex.example.com/ingest</endpoint>
        <apiKey>${CORTEX_API_KEY}</apiKey>
        <service>checkout-service</service>
        <tenantId>acme-corp</tenantId>
        <batchSize>500</batchSize>
        <flushIntervalMs>2000</flushIntervalMs>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="CORTEX"/>
    </root>

</configuration>
```

Set the API key via environment variable so it never lands in the repo:

```powershell
$env:CORTEX_API_KEY = 'ck_live_xxxxxxxxxxxxxxxxxxxxxxxx'
```

```bash
export CORTEX_API_KEY=ck_live_xxxxxxxxxxxxxxxxxxxxxxxx
```

The appender translates each `ILoggingEvent` into a `LogEntry`:

- `timestamp` -> the event timestamp
- `level` -> mapped from Logback `Level` via `LogLevel.fromSlf4jInt(...)`
- `service` -> the `<service>` you configured
- `message` -> the formatted message
- `labels.tenant` -> the `<tenantId>` you configured
- `labels.trace_id` -> taken from MDC if your app sets it (e.g. Spring Sleuth,
  Micrometer Tracing, OpenTelemetry)

---

## Run the SDK against a local CORTEX

Until P3 (`log-gateway`) and P4 (`log-ingest-service`) land, the SDK has
no real CORTEX endpoint to talk to. To smoke-test the wire format right
now, point the endpoint at any HTTP echo service or a local
`com.sun.net.httpserver.HttpServer`. The integration test
`HttpCortexClientTest` shows the exact pattern - it spins up a JDK
`HttpServer` on a random port and asserts the JSON payload.

Once P3 is up:

```powershell
# bring up the gateway locally (P3+; not available yet)
docker compose -f infra/docker/docker-compose.yml up -d log-gateway
```

Then point the SDK at `http://localhost:8080/ingest` and watch the
gateway logs.

---

## Observability of the SDK itself

The SDK logs to SLF4J at the following levels:

| Level | When |
|---|---|
| `WARN` | Non-2xx HTTP response, I/O error, dropped event due to full queue, gave-up-after-retries |
| `INFO` | Never (the SDK does not chatter on the happy path) |
| `DEBUG` | Not currently emitted |

There are intentionally **no metrics, no Micrometer, no OpenTelemetry**
inside the SDK. That keeps the dependency footprint small. Embedders
that want telemetry on the agent wrap the `CortexClient` interface in
their own decorator.

---

## What to do when something goes wrong

| Symptom | Likely cause | Fix |
|---|---|---|
| `IllegalArgumentException: endpoint must be absolute with a host` | Builder got a relative path or missing scheme | Use `https://host[:port]/path` |
| `IllegalStateException: endpoint is required` | Forgot `.endpoint(...)` on the builder | Set the endpoint |
| WARN `CORTEX ingest non-2xx status=401` | Wrong API key | Verify `CORTEX_API_KEY` |
| WARN `CORTEX ingest gave up after N attempts` | Network down or gateway dead | Check gateway health; bump `maxRetries` if transient |
| WARN `dropped event due to full queue` | Producer is faster than the gateway | Increase `batchSize` or scale the gateway |

---

## License

Apache 2.0 (same as the parent repo).
