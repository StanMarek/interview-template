# 04. Passwords, Secrets, TLS, and Security Headers

## Password hashing: the current baseline

Fast hashes are wrong for passwords.

- MD5, SHA-1, SHA-256, SHA-512: not password hashes
- Password hashing must be slow and tunable
- Memory-hard algorithms are preferred where available

## OWASP 2026 guidance

The OWASP Password Storage Cheat Sheet currently recommends:

- Argon2id: minimum `m=19 MiB`, `t=2`, `p=1`
- scrypt: minimum `N=2^17`, `r=8`, `p=1`
- bcrypt: work factor `10` or more
- PBKDF2-HMAC-SHA256: `600,000` or more iterations when FIPS constraints push you there

Practical senior answer:

- Prefer Argon2id for new systems where your stack supports it.
- Use scrypt when Argon2id is unavailable.
- bcrypt is still acceptable and still common, but know its 72-byte input limit.
- Use PBKDF2 mainly when compliance requirements push you there.

## Salts, peppers, and work factor

Salt:

- unique per password
- defeats rainbow tables
- prevents identical passwords from producing identical stored hashes

Pepper:

- optional extra secret stored separately from the DB
- useful defense in depth

Work factor:

- tune against your real hardware
- target a login-time cost that is acceptable for users but expensive for attackers

## Spring Security password encoders

Important correction from the old sheet:

- `PasswordEncoderFactories.createDelegatingPasswordEncoder()` still uses bcrypt as the default encoding id for new hashes.
- It does not switch to Argon2 automatically.

If you want Argon2 for new hashes, set it explicitly.

```java
@Bean
PasswordEncoder passwordEncoder() {
    Map<String, PasswordEncoder> encoders = Map.of(
        "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
        "bcrypt", new BCryptPasswordEncoder(),
        "pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
        "scrypt", SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8()
    );
    return new DelegatingPasswordEncoder("argon2", encoders);
}
```

Spring Security 7 also adds Password4j-based alternatives, but the main architectural point is still the same: use a delegating encoder so you can migrate hashes over time.

## KDF API vs password hashing

Do not confuse:

- password hashing for stored verifiers
- key derivation for cryptographic material

Java 25 finalized the `javax.crypto.KDF` API, but the JDK currently ships HKDF implementations. That does not mean "the JDK now has built-in Argon2 password hashing".

## Secrets management

Rules you should state clearly:

- do not hardcode secrets in source
- do not commit real secrets to config files
- rotate secrets deliberately
- support overlap during rotation

Operational patterns:

- Vault or cloud secret managers for runtime retrieval
- dynamic DB credentials where possible
- JWKS publication for key rotation
- alert on usage of deprecated secrets after the cutover window

## Environment variables vs secret managers

Environment variables are acceptable for simple deployments, but they are not a full secrets-management strategy.

Weaknesses:

- hard to rotate cleanly
- easy to leak in process dumps, logs, and misconfigured tooling
- poor auditability

## TLS in practice

Minimum senior answer:

- use TLS everywhere external or cross-boundary traffic exists
- prefer TLS 1.3
- disable legacy protocols and ciphers
- know when mTLS is justified

### TLS 1.3 handshake, simplified

1. Client and server negotiate parameters
2. Server proves identity with its certificate
3. Both sides establish shared secrets
4. Traffic keys are derived
5. Application data is encrypted and integrity-protected

## Certificates, keystores, and truststores

- Keystore: what this app presents as its identity
- Truststore: what this app trusts

This distinction matters constantly in Java ops work.

Useful commands:

```bash
keytool -genkeypair -alias app -keyalg RSA -keystore keystore.p12 -storetype PKCS12
keytool -importcert -alias ca -file ca.crt -keystore truststore.p12 -storetype PKCS12
keytool -list -v -keystore keystore.p12
```

## mTLS

Use mTLS when both sides need strong cryptographic identity:

- service-to-service traffic
- privileged internal APIs
- regulated ecosystems

Common mistake: thinking mTLS replaces application authorization. It does not. It authenticates the peer transport identity.

## Zero trust

Use the phrase carefully:

- every request is evaluated
- network location alone is not trusted
- service identity, user identity, and policy all matter

In interviews, connect zero trust to:

- mTLS
- short-lived credentials
- strong service identity
- per-request authorization

## Security headers

High-value defaults:

- `Content-Security-Policy`
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy`
- `Permissions-Policy`
- `Strict-Transport-Security`
- `X-Frame-Options` or CSP `frame-ancestors`

Example:

```java
http.headers((headers) -> headers
    .contentSecurityPolicy((csp) -> csp
        .policyDirectives("default-src 'self'; frame-ancestors 'none'"))
    .referrerPolicy((referrer) -> referrer
        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
);
```

## Rate limiting

Apply it before expensive operations:

- login
- OTP verification
- password reset
- token minting
- search endpoints vulnerable to scraping

Distributed options:

- token bucket in Redis
- gateway-based rate limiting
- per-user, per-IP, and per-API-key controls

## Security logging and audit trails

Log:

- authentication success and failure
- password reset and MFA enrollment events
- privilege changes
- token refresh / revocation
- secret access where applicable

Do not log:

- raw passwords
- session IDs
- bearer tokens
- PII you do not need for investigation

Senior point: logs must be useful for incident response, not just for debugging.

## Interview shortcuts

- "Argon2id first, scrypt second, bcrypt still acceptable, PBKDF2 for FIPS-heavy environments."
- "Spring's delegating encoder still defaults to bcrypt unless you change the id."
- "Environment variables are okay for simple cases, but rotation and auditability push serious systems toward secret managers."
- "mTLS authenticates the transport peer; it does not replace authorization."
- "Know the keystore vs truststore distinction."

## Primary references

- OWASP Password Storage Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>
- Spring Security password encoder docs and API: <https://docs.spring.io/spring-security/reference/api/java/>
- Spring Security 7 whats-new: <https://docs.spring.io/spring-security/reference/whats-new.html>
- Java 25 KDF API: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/KDF.html>
- NIST SP 800-63B: <https://pages.nist.gov/800-63-4/sp800-63b.html>
