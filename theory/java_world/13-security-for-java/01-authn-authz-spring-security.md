# 01. Authentication, Authorization, and Spring Security

## Authentication vs authorization

Authentication answers "who are you?"

- Something you know: password, PIN
- Something you have: hardware key, phone, smart card
- Something you are: biometrics

Authorization answers "what can you do?"

- Roles, permissions, ownership, tenant boundaries, resource state, and policy context all live here.

Senior-level point: two secrets of the same factor are still one factor. Password + security question is not MFA.

## MFA and phishing resistance

For 2026 interview answers, the practical bar is:

- Passwords, SMS OTP, and TOTP are phishable.
- WebAuthn/FIDO authenticators are phishing-resistant because the browser and authenticator scope credentials to the relying party.
- In the NIST SP 800-63-4 suite, AAL3 requires phishing-resistant cryptographic authenticators, and syncable authenticators are not allowed at AAL3.

Do not oversell this into "everything except passkeys is dead". TOTP still exists in real systems, but it is no longer the best answer when an interviewer asks for the strongest mainstream option.

## Threat modeling with STRIDE

| Threat | Security property | Java/Spring example | Typical mitigation |
|---|---|---|---|
| Spoofing | Authentication | Login as another user | Passkeys, MFA, strong token validation |
| Tampering | Integrity | Modify payloads or claims | TLS, signatures, MACs, server-side validation |
| Repudiation | Auditability | "I never approved this transfer" | Immutable audit logs, signed events |
| Information disclosure | Confidentiality | Stack traces leaking PII | Sanitized errors, encryption, least data exposure |
| Denial of service | Availability | Exhaust thread pools | Rate limiting, timeouts, bulkheads |
| Elevation of privilege | Authorization | User reaches admin API | Least privilege, ownership checks, method security |

Use STRIDE early. Interviewers often care more about whether you structure the problem well than whether you memorize framework classes.

## Authorization models

### RBAC

Best for clear hierarchies and coarse policy.

```java
@PreAuthorize("hasRole('ADMIN')")
void deleteUser(Long userId) { }
```

### ABAC

Best when the decision depends on user, resource, action, and environment attributes.

```java
@PreAuthorize("@authz.canAccessDocument(authentication, #documentId)")
Document getDocument(Long documentId) { ... }
```

### ACL

Best for resource-by-resource permission models. Powerful, but expensive to maintain at scale.

```java
@PreAuthorize("hasPermission(#document, 'WRITE')")
void updateDocument(Document document) { }
```

## Spring Security 6.5 / 7.0: what actually matters

As of April 23, 2026, the official docs list Spring Security 6.5.10 and 7.0.5 as the stable lines.

### Migration facts worth memorizing

| Topic | Correct 2026 answer |
|---|---|
| `WebSecurityConfigurerAdapter` | Deprecated in 5.7, removed in 6.0. Use `SecurityFilterChain` beans. |
| `authorizeRequests()` | Replaced by `authorizeHttpRequests()`. |
| `and()` style chaining | Removed in 7.0. Lambda DSL is the supported style. |
| Access API | `AuthorizationManager` is the current model. `AccessDecisionManager` and `AccessDecisionVoter` still exist as deprecated legacy APIs in 7.0, moved to `spring-security-access`. |
| Request authorization | `AuthorizationFilter` is the modern path for `authorizeHttpRequests()`. Do not claim `FilterSecurityInterceptor` vanished from the framework entirely. |
| Method security | `@EnableMethodSecurity` replaces `@EnableGlobalMethodSecurity`. Pre/post advisors are published by default. |
| Custom authz | In 7.0, implement `AuthorizationManager<T>` with `authorize(...)`, not the older `check(...)` guidance. |

### 6.5 highlights that are still relevant

- DPoP support for resource servers
- PKCE toggle for confidential clients via `ClientRegistration.clientSettings.requireProofKey`
- Passkey/WebAuthn support with JDBC persistence options and configurable `messageConverter`
- Automatic Micrometer context propagation support
- Observability key rename from `security.security.reached.filter.section` to `spring.security.reached.filter.section`

### 7.0 highlights that are likely interview bait

- `and()` removed from `HttpSecurity`
- `authorizeRequests()` removed in favor of `authorizeHttpRequests()`
- `AuthorizationManager#authorize(...)` is the main authorization contract
- Built-in SPA CSRF support via `csrf.spa()`
- New Password4j-based password encoders

## SecurityFilterChain mental model

Think in terms of ordered filters, not magic annotations:

1. Request enters the servlet filter chain.
2. Spring Security loads or creates the security context.
3. Authentication filters try to establish identity.
4. Exception translation converts auth failures into HTTP responses.
5. Authorization decides whether the request can continue.

Do not memorize one exact default filter order. It changes across versions and features. In interviews, describe the phases and where you would insert a custom filter.

## SecurityContext and reactive boundaries

Servlet applications use `SecurityContextHolder`.

```java
Authentication authentication =
    SecurityContextHolder.getContext().getAuthentication();
```

Reactive applications use `ReactiveSecurityContextHolder`.

```java
Mono<String> username = ReactiveSecurityContextHolder.getContext()
    .map(SecurityContext::getAuthentication)
    .map(Authentication::getName);
```

Important nuance:

- `ThreadLocal` still matters in servlet code, including on virtual threads.
- Reactive flows do not use the servlet `SecurityContextHolder`.
- Micrometer Context Propagation can bridge some async boundaries, but only when the app is configured for it. Do not claim it is "automatic everywhere".

## AuthenticationManager and AuthenticationProvider

Use this vocabulary precisely:

- `AuthenticationManager` is the entry point.
- `ProviderManager` is the common implementation.
- `AuthenticationProvider` implementations handle specific credential types.

Examples:

- `DaoAuthenticationProvider` for username/password
- JWT resource-server support for bearer tokens
- LDAP or SAML/OIDC integrations where relevant

## Method security

```java
@Configuration
@EnableMethodSecurity
class MethodSecurityConfig { }
```

Typical use:

```java
@PreAuthorize("hasRole('ADMIN')")
void deleteUser(Long userId) { }

@PreAuthorize("#userId == authentication.name or hasRole('ADMIN')")
UserProfile getProfile(String userId) { ... }
```

Senior-level rule: URL security is not enough. Enforce authorization again at the service boundary for important operations.

## Custom authorization with AuthorizationManager

For Spring Security 7, this is the current shape:

```java
AuthorizationManager<RequestAuthorizationContext> sameTenant =
    (authentication, context) -> {
        Authentication auth = authentication.get();
        String tenant = context.getRequest().getHeader("X-Tenant-Id");
        boolean granted = auth != null
            && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("TENANT_" + tenant));
        return new AuthorizationDecision(granted);
    };
```

And wire it like this:

```java
http.authorizeHttpRequests((authz) -> authz
    .requestMatchers("/api/tenant/**").access(sameTenant)
    .anyRequest().authenticated()
);
```

## Custom filters

Use `OncePerRequestFilter` when you need request-scoped behavior exactly once.

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            // validate token, load authorities, set Authentication
        }
        filterChain.doFilter(request, response);
    }
}
```

Typical placement:

```java
http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

## Interview shortcuts

- Use session cookies for server-rendered web apps.
- Use bearer tokens or sender-constrained tokens for APIs and mobile clients.
- Prefer local JWT validation for scale, introspection for immediate revocation or opaque-token deployments.
- Apply authorization at both HTTP and service layers.
- Talk about tenant isolation explicitly in multi-tenant systems.

## Primary references

- Spring Security 6.5 whats-new: <https://docs.spring.io/spring-security/reference/6.5/whats-new.html>
- Spring Security 7.0 whats-new: <https://docs.spring.io/spring-security/reference/whats-new.html>
- Spring Security 7 migration guide: <https://docs.spring.io/spring-security/reference/migration/>
- Method security docs: <https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html>
- AuthorizationManager API: <https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/authorization/AuthorizationManager.html>
- AccessDecisionManager API: <https://docs.spring.io/spring-security/reference/7.0/api/java/org/springframework/security/access/AccessDecisionManager.html>
- NIST SP 800-63B: <https://pages.nist.gov/800-63-4/sp800-63b.html>
