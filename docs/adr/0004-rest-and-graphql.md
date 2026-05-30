# 0004. Expose REST and GraphQL with four-operation parity

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: api, graphql, rest

## Context and problem statement

CORTEX serves two distinct audiences:

- **Ingest clients** (agents, sidecars, log forwarders) - high-throughput,
  fire-and-forget, API-key-authenticated.
- **Query clients** (dashboards, ad-hoc tools, IDE plugins) - lower
  volume, often interactive, sometimes typed schemas via GraphQL.

The user asked: **"u can have both HTTP amd graphsql support - like 4
endpoints?"**. We need to decide which operations live in which surface,
and whether mutations are part of the GraphQL contract.

## Decision drivers

- Ingest must be REST (high throughput, simple, well-understood by every
  agent on the planet).
- Query clients benefit from GraphQL's typed schema + field selection.
- We should not maintain two divergent APIs for the same data.
- Authn / authz models differ between the two audiences and must remain
  separable.

## Considered options

- **Option A**: REST for everything; no GraphQL.
- **Option B**: REST for ingestion + REST and GraphQL for queries (parity).
- **Option C**: GraphQL for everything (including ingestion mutations).
- **Option D**: REST for ingestion + GraphQL for queries only (no REST query).

## Decision outcome

Chosen option: **B - REST for ingestion, both REST and GraphQL for the
four query operations**. Both surfaces share the same service-layer code;
the controller / resolver layer is the only divergence. This gives REST
clients (curl, Postman, sidecars) zero-friction access while letting
GraphQL clients pick exactly the fields they want.

### The four query operations (both surfaces)

| Operation       | REST                            | GraphQL                           |
| --------------- | ------------------------------- | --------------------------------- |
| `searchLogs`    | `GET  /v1/logs/search?...`      | `searchLogs(input): LogSearchResult!` |
| `getLogById`    | `GET  /v1/logs/{id}`            | `getLogById(id: ID!): LogEntry`   |
| `nlToLogQL`     | `POST /v1/logs/nl-to-logql`     | `nlToLogQL(prompt: String!): String!` |
| `getAnomalies`  | `GET  /v1/anomalies?...`        | `getAnomalies(input): [Anomaly!]!` |

### Ingestion stays REST-only

`POST /v1/ingest` and `POST /v1/ingest/stream` accept newline-delimited
JSON and authenticate with an API key in `X-Api-Key`. There is **no
GraphQL mutation** in v0.1.0 (RA5). Rationale: GraphQL adds payload
overhead per record (curly braces, field names) and its strength
(precise field selection) is irrelevant for write workloads.

### Positive consequences

- Each audience uses the surface that suits its workload.
- Service layer is shared; the two controllers / resolvers are thin.
- GraphQL schema doubles as living documentation for query clients.
- Authn differences (API key vs JWT) are cleanly isolated to the
  gateway routing rules.

### Negative consequences

- Schema-first GraphQL requires keeping `.graphqls` files in sync with
  DTOs. Mitigated by code generation in the build (`graphql-java`).
- Two controller test surfaces per operation. Mitigated by extracting
  shared scenarios into reusable test fixtures.

## Pros and cons of the options

### A - REST only

- **Good**, simplest; one surface.
- **Bad**, user has explicitly asked for GraphQL.

### B - REST ingestion + REST/GraphQL queries

- **Good**, matches user ask; right tool for each audience.
- **Bad**, two surfaces for queries to maintain.

### C - GraphQL everything

- **Good**, single surface; single schema.
- **Bad**, ingestion overhead; agents don't speak GraphQL natively.

### D - REST ingestion + GraphQL queries only

- **Good**, no duplication.
- **Bad**, REST clients (curl, Postman) lose query access; hurts adoption.

## Links

- Locked decision LD4.
- Rejected alternative RA5 (GraphQL mutations Day 1).
- [ARCHITECTURE.md §4](../ARCHITECTURE.md).
