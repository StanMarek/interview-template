# 06. Common Senior Interview Questions

## Q1: How would you design authentication and authorization for a microservices architecture?

Use a centralized authorization server or IdP for user login and token issuance, and treat each service as its own policy enforcement point. Public traffic usually enters through an API gateway, but each downstream service still validates tokens and enforces its own authorization rules. For service-to-service traffic, use machine identity such as mTLS plus client credentials where appropriate. For high-value internal calls, talk explicitly about tenant isolation, least privilege, and short-lived credentials.

## Q2: Stateful sessions or stateless tokens?

Use session cookies for traditional server-rendered web apps when you want simple revocation and server-controlled session state. Use tokens for APIs, mobile clients, and cross-service scenarios where local validation and portability matter. The strongest practical answer is often hybrid: short-lived access tokens plus carefully controlled refresh-token or server-side session infrastructure.

## Q3: A security audit found SQL injection. How do you remediate it?

Search for string-built SQL and native query concatenation, convert everything to parameterized queries, and then add automation so it does not regress. Review JDBC, JPA native queries, HQL/JPQL string construction, report exports, and admin search tools. Pair the code change with least-privilege DB accounts and CI scanning, but make it clear that parameter binding is the primary fix.

## Q4: When can CSRF be disabled safely?

Only when the browser is not automatically attaching the credential used for authentication. That usually means a stateless API authenticated by headers such as `Authorization: Bearer ...`, not cookies. If the app uses session cookies, leave CSRF on. If the question is about an SPA backed by a session cookie, the right answer is still "protect it", not "it is JSON so it is safe".

## Q5: How do you rotate secrets without downtime?

Support overlap. Accept both the old and new secret during the transition window, publish both relevant public keys if you are rotating signing keys, wait for all consumers to pick up the new material, and only then retire the old one. Use a secret manager where possible, and monitor for stale-secret usage so you know when the rotation is actually complete.

## Q6: What is the OWASP Top 10:2025 and how does it apply to Java/Spring?

The 2025 list is:

| # | Category | Java/Spring examples |
|---|---|---|
| A01 | Broken Access Control | Missing ownership checks, weak method security, SSRF into internal systems |
| A02 | Security Misconfiguration | Exposed actuators, wild CORS, bad TLS, debug settings in prod |
| A03 | Software Supply Chain Failures | Vulnerable dependencies, poisoned build inputs, unsigned artifacts |
| A04 | Cryptographic Failures | Weak password storage, ECB, hardcoded keys, missing TLS |
| A05 | Injection | SQL injection, command injection, template injection, XSS |
| A06 | Insecure Design | No threat modeling, no rate limits, weak recovery flows |
| A07 | Authentication Failures | Weak MFA, poor session handling, outdated OAuth flows |
| A08 | Software or Data Integrity Failures | Deserialization risk, unsigned updates, trust-boundary mistakes |
| A09 | Security Logging and Alerting Failures | Missing audit trails, poor alerting, logging secrets |
| A10 | Mishandling of Exceptional Conditions | Fail-open behavior, swallowed errors, unsafe fallback logic |

Three easy talking points:

- SSRF is folded into A01.
- Supply chain got its own top-level category in A03.
- Error paths must fail closed, not open.

## Q7: How would you implement distributed rate limiting?

Use a shared store or a gateway, not per-instance memory. Redis-backed token bucket or sliding-window designs are common. Rate-limit on the right identity for the endpoint: IP before login, user or API key after login, and stricter controls for auth endpoints than for read-only APIs. Return `429` and keep the limiter in front of expensive work.

## Q8: Encryption at rest vs in transit?

In transit means TLS between communicating systems. At rest means disk, database, backup, or object-storage protection. Strong answers usually mention layered controls: TLS for transport, platform or DB encryption for storage, and application-level encryption for the subset of fields that remain sensitive even from privileged infrastructure operators.

## Q9: How do you prevent and detect a compromised JWT?

Prevention means short-lived access tokens, strict validation of `iss`, `aud`, and expiry, strong signing keys, and careful storage. Detection means anomaly monitoring, refresh-token reuse detection, and targeted revocation paths. If compromise is suspected, revoke the refresh-token family, force re-authentication, and rotate signing keys if the signing material itself is at risk.

## Q10: How do you secure a CI/CD pipeline?

Use least-privilege identities, short-lived credentials via OIDC federation, dependency and container scanning, artifact signing, provenance, and branch protections. Treat the pipeline as production infrastructure. If an attacker can push artifacts or alter a workflow, they effectively own production.

## Fast self-test

If you cannot answer these cleanly, revisit the linked parts:

1. Why is Auth Code + PKCE the default browser/mobile answer in 2026?
2. Why is `AuthorizationManager` the preferred Spring Security vocabulary?
3. Why are passkeys phishing-resistant?
4. Why is CORS not a CSRF defense?
5. Why is an SBOM not enough without provenance and signature verification?
