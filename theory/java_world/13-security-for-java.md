# Security for Java Applications

> April 2026 refresh. This sheet was split into smaller interview-focused notes and the time-sensitive claims were rechecked against primary sources.

Use this page as the entry point. Read the parts in order if you want a full review, or jump straight to the area you are drilling.

## Recommended reading order

1. [01-authn-authz-spring-security.md](13-security-for-java/01-authn-authz-spring-security.md)
   Authentication vs authorization, threat modeling, and the Spring Security 6.5 / 7.0 mental model.
2. [02-jwt-oauth-oidc.md](13-security-for-java/02-jwt-oauth-oidc.md)
   JWT trade-offs, OAuth 2.1 draft status, OIDC validation, DPoP, mTLS, PAR, and Spring resource-server patterns.
3. [03-passkeys-cors-web-threats.md](13-security-for-java/03-passkeys-cors-web-threats.md)
   WebAuthn/passkeys, CORS, CSRF, SSRF, XSS, SQL injection, XXE, deserialization, and input validation.
4. [04-passwords-secrets-tls-headers.md](13-security-for-java/04-passwords-secrets-tls-headers.md)
   Password hashing, Spring encoders, secrets management, TLS, security headers, rate limiting, and audit logging.
5. [05-supply-chain-and-java-crypto.md](13-security-for-java/05-supply-chain-and-java-crypto.md)
   SBOMs, SLSA, Sigstore, Security Manager removal, KDF API, ML-KEM, ML-DSA, and post-quantum TLS status.
6. [06-interview-questions.md](13-security-for-java/06-interview-questions.md)
   Concise senior-level interview answers that map back to the study notes.

## What changed in this refresh

- Spring Security notes were updated to the current stable lines listed in the official docs as of April 23, 2026: 6.5.10 and 7.0.5.
- Legacy Spring access-control APIs were corrected: `AccessDecisionManager` and `AccessDecisionVoter` are deprecated legacy APIs, but they still exist in 7.0 in `spring-security-access`; `AuthorizationManager` is the current direction.
- `AuthorizationManager` examples were updated for 7.0-style `authorize(...)` usage instead of older `check(...)` guidance.
- OAuth 2.1 guidance was corrected to match the current March 2026 draft and RFC 9700 security BCP. In particular, refresh-token replay protection for public clients is "sender-constrained or rotation", not "rotation is always mandatory".
- Passkey terminology was aligned with WebAuthn Level 3 and NIST SP 800-63B in the SP 800-63-4 suite: multi-device credentials vs single-device credentials, and syncable authenticators are not acceptable at AAL3.
- OWASP Top 10 references were rechecked against the official 2025 release pages.
- JDK crypto notes were corrected: the KDF API is final in Java 25, the JDK ships HKDF implementations today, Argon2 is still draft work, and hybrid PQ TLS moved to JDK 27 via JEP 527.

## Fast interview framing

If you only have ten minutes before an interview:

1. Read part 1 for Spring Security modernization.
2. Read part 2 for OAuth/OIDC and JWT trade-offs.
3. Read part 3 for passkeys, CSRF, SSRF, and injection classes.
4. Skim part 5 for current JDK security and post-quantum updates.

## Primary sources used for this refresh

- Spring Security reference docs: <https://docs.spring.io/spring-security/reference/>
- Spring Authorization Server reference docs: <https://docs.spring.io/spring-authorization-server/reference/>
- IETF OAuth 2.1 draft: <https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/>
- OAuth 2.0 Security BCP / RFC 9700: <https://www.rfc-editor.org/rfc/rfc9700>
- WebAuthn Level 3: <https://www.w3.org/TR/webauthn-3/>
- NIST SP 800-63-4 suite / SP 800-63B: <https://pages.nist.gov/800-63-4/sp800-63b.html>
- OWASP Top 10 2025: <https://owasp.org/Top10/2025/>
- OWASP Password Storage Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>
- OpenJDK JEPs 486, 496, 497, 510, 527 and draft 8377081: <https://openjdk.org/jeps/486>, <https://openjdk.org/jeps/496>, <https://openjdk.org/jeps/497>, <https://openjdk.org/jeps/510>, <https://openjdk.org/jeps/527>, <https://openjdk.org/jeps/8377081>
