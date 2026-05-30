# 0015 - log-gateway uses Spring Security OAuth2 Resource Server for JWT auth

Status: Accepted
Date: 2026-05-30
Deciders: CORTEX core engineering
Tags: gateway, security, auth, jwt

## Context

The CORTEX log-gateway is the only ingress into the platform. Every request
must be authenticated (rule B7.1) and every protected endpoint must declare
its auth requirement in OpenAPI (rule B7.4). Sessions are forbidden: the
gateway is stateless and horizontally scalable behind a load balancer
(ADR-0014). We need to pick a JWT stack that:

1. Integrates with Spring Security 6 (the rest of the gateway is Spring Boot 3.3.5).
2. Supports method-level `@PreAuthorize("hasRole(...)")` without bespoke glue.
3. Lets us rotate refresh tokens defensively (single-use, rule B7.5).
4. Has a clean upgrade path to JWKS / RS256 once v0.2.0 introduces an external IdP.

## Decision

Adopt **Spring Boot Starter OAuth2 Resource Server**
(`spring-boot-starter-oauth2-resource-server`) for JWT decoding and SecurityFilterChain
integration, backed by **Nimbus JOSE+JWT** (pulled in transitively via
`spring-security-oauth2-jose`).

Concrete choices for v0.1.0:

- **Algorithm**: HMAC HS256 with a Base64-encoded symmetric secret loaded
  from `cortex.gateway.security.jwt.secret`. The decoder rejects keys
  shorter than 32 bytes (HS256 minimum per RFC 7518).
- **Issuer**: `cortex-gateway` (configurable per environment).
- **Access TTL**: 15 minutes. **Refresh TTL**: 7 days.
- **Refresh single-use**: every refresh token carries a fresh `jti`. The
  `RefreshTokenStore` interface (P3.1 ships an in-memory impl, P3.2
  swaps to Redis) records the `jti` at issue time and atomically
  removes it on consumption. A replayed refresh is rejected with `401`.
- **Roles**: emitted as a top-level `roles` JSON array (e.g.
  `["ADMIN","USER"]`). `JwtGrantedAuthoritiesConverter` prefixes them
  with `ROLE_` so `@PreAuthorize("hasRole('ADMIN')")` works.
- **Passwords**: Argon2 (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`)
  per rule B7.3.
- **Bootstrap users**: P3.1 ships an in-memory user list loaded from
  `cortex.gateway.security.bootstrap.users[*]`. P4 replaces this with a
  PostgreSQL-backed user store.

## Alternatives Considered

1. **Legacy `spring-security-oauth2` (Spring Security OAuth 2.x)** -
   end-of-life since 2020. Rejected per rule B7.1 ("no end-of-life
   security libraries").
2. **Raw JJWT library (`io.jsonwebtoken:jjwt`)** - works but has no
   first-class Spring Security integration; we would re-implement the
   resolver / converter / filter chain plumbing that the starter
   provides for free. Rejected on maintenance grounds.
3. **External IdP (Keycloak, Cognito) with JWKS Day-1** - correct
   end state but requires standing up the IdP, federating user data,
   and onboarding a managed dependency before the gateway can ship.
   Deferred to v0.2.0: the gateway already isolates token verification
   behind `JwtDecoder`, so flipping `NimbusJwtDecoder.withSecretKey`
   for `NimbusJwtDecoder.withJwkSetUri` is a single-bean change.

## Consequences

Positive:

- Standard Spring Security DSL (`http.oauth2ResourceServer(...)`) - no
  custom filter, no custom authentication provider.
- `JwtAuthenticationToken` is auto-populated; downstream code reads
  `Authentication#getName()` and `getAuthorities()` exactly the same
  way it would with form login.
- Argon2 hashing is in-tree; no bcrypt downgrade temptation.
- Refresh-token rotation is enforced by an interface boundary, so the
  P3.2 Redis swap is mechanical.

Negative / Open:

- HS256 means every gateway replica shares the same secret. Acceptable
  for v0.1.0 (single-tenant, single deployment) but does NOT scale to
  multi-tenant. The v0.2.0 JWKS migration removes this.
- The in-memory `RefreshTokenStore` does NOT survive a restart. Any
  refresh issued before the gateway restarts is silently invalidated.
  Acceptable for v0.1.0 (clients re-login); P3.2 fixes by persisting
  to Redis.
- Bootstrap users live in `application-{env}.yml`. They are
  scaffolding only and MUST be replaced by the P4 PostgreSQL store
  before v0.1.0 ships to staging.

## Verification

- Unit tests: `NimbusJwtIssuerTest`, `InMemoryRefreshTokenStoreTest`,
  `AuthServiceImplTest`.
- Slice test: `AuthControllerTest` (`@WebMvcTest`).
- Integration test: `AuthIntegrationTest` (`@SpringBootTest`) performs
  a real login -> refresh -> single-use rejection round trip.
- Architecture: `ArchitectureRulesTest.LAYERING` re-adds the Service
  layer (reverting EQ8 now that real service classes exist).
- OpenAPI: `OpenApiConfig` registers the `bearerAuth` security scheme
  so every protected endpoint can reference it via
  `@SecurityRequirement` (rule B7.4).
