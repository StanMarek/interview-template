# 03. Passkeys, CORS, and Common Web Threats

## Passkeys and WebAuthn

For 2026 interviews, treat passkeys as a mainstream authentication answer, not an edge feature.

Why they matter:

- The private key stays with the authenticator.
- The browser and authenticator scope credentials to the relying party.
- Authentication is challenge-response, not replay of a shared secret.
- They are phishing-resistant in a way passwords, SMS OTP, and TOTP are not.

## Correct terminology

WebAuthn Level 3 distinguishes:

- multi-device credentials: backup-eligible credentials, usually what consumer platforms market as synced passkeys
- single-device credentials: not backup-eligible, sometimes called device-bound in product language

That distinction matters in higher-assurance environments.

## NIST nuance you should know

The SP 800-63-4 suite includes requirements for syncable authenticators.

- Syncable authenticators are allowed in some contexts.
- Syncable authenticators are not acceptable at AAL3.
- Do not claim that "all passkeys automatically satisfy every assurance level".

## WebAuthn ceremonies

### Registration

1. Server creates a challenge and credential options
2. Browser asks the authenticator to create a key pair for the relying party
3. Authenticator returns attestation data
4. Server verifies and stores credential metadata and the public key

### Authentication

1. Server creates a challenge and assertion options
2. Browser asks the authenticator to produce an assertion
3. Server verifies the signature, RP binding, and related flags/counter data

## Spring Security passkeys

Spring Security 6.5 added first-class passkey support and JDBC repositories.

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .formLogin(Customizer.withDefaults())
        .webAuthn((webAuthn) -> webAuthn
            .rpId("example.com")
            .allowedOrigins("https://example.com")
        )
        .build();
}

@Bean
JdbcPublicKeyCredentialUserEntityRepository publicKeyCredentialUsers(
        JdbcOperations jdbc) {
    return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
}

@Bean
JdbcUserCredentialRepository userCredentials(JdbcOperations jdbc) {
    return new JdbcUserCredentialRepository(jdbc);
}
```

Important correction from the original sheet:

- The official repositories are `PublicKeyCredentialUserEntityRepository` and `UserCredentialRepository`.
- The JDBC implementations are `JdbcPublicKeyCredentialUserEntityRepository` and `JdbcUserCredentialRepository`.

## Attestation: when to care

Most consumer applications:

- use attestation conveyance preference `none`
- care more about phishing resistance than hardware model inventory

Enterprise or regulated deployments:

- may require stronger attestation handling
- may integrate with the FIDO Metadata Service
- may restrict enrollment to approved authenticators

## Authenticator trade-offs

| Authenticator style | Phishing-resistant | Recovery posture | Best fit |
|---|---|---|---|
| Multi-device credential | Yes | Easier recovery | Consumer SaaS |
| Single-device credential | Yes | Harder recovery | Managed enterprise devices |
| Roaming security key | Yes | Strong portability with hardware control | Admins, privileged users |
| TOTP | No | Easy to deploy | Legacy fallback only |
| SMS OTP | No | Weakest of the common MFA options | Avoid for new high-value systems |

## CORS

CORS is a browser policy mechanism, not a server-to-server security control.

### Same-origin policy

Origins are defined by:

- scheme
- host
- port

### Simple vs preflighted requests

Simple requests do not trigger preflight only when they stay within the narrow browser rules.

Common triggers for preflight:

- `Authorization` header
- `Content-Type: application/json`
- methods such as `PUT`, `PATCH`, `DELETE`

### Common CORS mistakes

- `Access-Control-Allow-Origin: *` together with credentials
- configuring CORS in Spring MVC but forgetting Spring Security
- not allowing `OPTIONS`
- assuming CORS protects you from CSRF

## CSRF

CSRF is about browsers automatically sending ambient credentials such as cookies.

Safe default:

- keep CSRF protection enabled for session-cookie applications
- only disable it for genuinely stateless APIs that authenticate with headers instead of cookies

Spring Security 7 also adds an SPA-oriented CSRF mode:

```java
http.csrf((csrf) -> csrf.spa());
```

If your app uses a session cookie, do not casually disable CSRF because "it is a JSON API".

## XSS

Core defenses:

- output encoding
- safe templating defaults
- HTML sanitization when rich text is unavoidable
- Content Security Policy

Thymeleaf example:

- `th:text` is escaped
- `th:utext` renders raw HTML and is dangerous with untrusted input

## SQL injection

Primary defense:

- parameterized queries

Secondary defenses:

- least-privilege DB accounts
- allowlist validation
- static analysis in CI

Never treat input validation as a substitute for parameter binding.

## SSRF

Senior interview answer:

- validate scheme
- resolve and re-check the destination IP
- block loopback, link-local, RFC1918/private ranges, and cloud metadata targets
- prefer explicit egress allowlists

SSRF is now explicitly folded into OWASP A01:2025 Broken Access Control.

## XXE

Disable:

- DTDs
- external general entities
- external parameter entities
- external DTD loading

## Java deserialization

Canonical 2026 answer:

- do not deserialize untrusted Java object streams
- use structured formats like JSON
- if legacy code forces Java serialization, add strict `ObjectInputFilter` allowlists

## Path traversal

Correct pattern:

1. resolve against a fixed base directory
2. normalize
3. if needed, resolve to real path
4. verify the resulting path still starts with the base path

## Input validation

Prefer allowlists and explicit DTO constraints.

```java
public record CreateUserRequest(
    @NotBlank @Size(min = 3, max = 32)
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$")
    String username,

    @NotBlank @Email @Size(max = 254)
    String email
) { }
```

Bean Validation is a boundary defense, not a replacement for query parameterization or output encoding.

## Interview shortcuts

- "Passkeys are WebAuthn credentials. They are phishing-resistant because the authenticator signs for the relying party, not for any random lookalike domain."
- "CORS is a browser policy. It is not an auth mechanism."
- "CSRF matters when the browser auto-sends auth cookies."
- "SQL injection is fixed with parameterization, not regexes."
- "For SSRF, validate destination after DNS resolution and use egress allowlists."

## Primary references

- Spring Security passkeys docs: <https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passkeys.html>
- WebAuthn Level 3: <https://www.w3.org/TR/webauthn-3/>
- NIST SP 800-63B: <https://pages.nist.gov/800-63-4/sp800-63b.html>
- OWASP Top 10 2025: <https://owasp.org/Top10/2025/>
- OWASP XXE Prevention Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html>
- OWASP SSRF Prevention Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html>
- OWASP Input Validation Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html>
