# Identity and Session Management Platform -- Architecture Design

## Requirements

### Functional
- User registration with email/password, passwordless sign-in via Passkeys (WebAuthn/FIDO2 platform authenticators), social login (Google, GitHub), and enterprise SSO (SAML/OIDC)
- Multi-factor authentication (TOTP, WebAuthn/FIDO2 security keys, SMS backup -- SMS deprecated for high-assurance accounts)
- OAuth 2.1 + OIDC for third-party integrations (PKCE mandatory for all clients including confidential, no implicit flow, no password grant)
- Session management: create, validate, refresh, and revoke sessions
- JWT issuance with configurable claims and short TTLs
- Token refresh flow with refresh token rotation and sender-constrained tokens (DPoP) for high-value APIs
- Immediate session revocation (logout from all devices)
- Role-based access control (RBAC) and attribute-based access control (ABAC)
- Password reset, account recovery, and account lockout
- Brute force protection and adaptive authentication (risk-based step-up)

### Non-Functional
- **Consistency:** Strong consistency for authentication state (a revoked session must be immediately rejected)
- **Availability:** 99.99% -- authentication is a hard dependency for every service
- **Latency:** p99 < 5ms for JWT validation, p99 < 50ms for session validation, p99 < 200ms for login
- **Security:** Passwords hashed with argon2id, all tokens cryptographically signed, PII encrypted at rest
- **Compliance:** GDPR (right to deletion), SOC 2 (audit trail), PCI DSS (if handling payment data)

## Scale Estimates
- 100K session validations/second (every API request)
- 10K logins/second at peak
- 500M registered users
- 50M concurrent active sessions
- JWT signing: 10K signatures/second
- Session store: ~500 bytes per session, so ~25 GB for 50M sessions

## Architecture Decisions

### Decision 1: Short-Lived JWTs with Opaque Refresh Tokens

Access tokens are JWTs with a 15-minute TTL, signed with RS256. Refresh tokens are opaque random strings stored server-side with a 30-day TTL. The access JWT is validated locally (no database hit) using the public key. The refresh token requires a server-side lookup.

**Why this hybrid approach:** Pure JWT-based sessions have a critical flaw: you cannot revoke a JWT before it expires. Once issued, it's valid until the TTL passes. For a 15-minute JWT, this means a compromised token is valid for up to 15 minutes. By keeping the TTL short and using server-side refresh tokens, we limit the exposure window while maintaining the performance benefit of local JWT validation.

**Why not pure opaque sessions:** Opaque session tokens require a server-side lookup on EVERY request. At 100K validations/second, this creates a hard dependency on the session store. If Redis is slow, every API call is slow. JWTs allow most validations to be purely local (verify signature, check expiry), with only refresh operations hitting the server.

**Trade-off:** The 15-minute window where a revoked user can still use their JWT. This is acceptable for most applications. For high-security operations (transferring money, changing password), require a fresh authentication (step-up auth) rather than relying on the JWT.

### Decision 2: Refresh Token Rotation with Reuse Detection

Every time a refresh token is used, it is invalidated and a new one is issued. If a refresh token is reused (someone tries to use an already-rotated token), ALL sessions for that user are immediately revoked.

**Why rotation:** If a refresh token is stolen, the attacker has 30 days to use it. With rotation, the stolen token becomes invalid as soon as the legitimate user's client refreshes. The reuse detection catches the case where the attacker uses the stolen token AFTER the legitimate user has already rotated it -- this is a strong signal of token theft, and revoking all sessions is the safe response.

**Senior-level nuance:** Refresh token rotation creates a race condition in mobile apps. If the app fires two concurrent requests, both using the same refresh token, one will succeed and rotate the token, and the other will trigger reuse detection and revoke all sessions. The solution: the client SDK must serialize refresh requests (only one refresh in-flight at a time) with a mutex.

### Decision 3: Token Blacklist with Bloom Filter + Redis

When a session is revoked, the JWT's `jti` (JWT ID) is added to a blacklist. The blacklist is checked on every JWT validation. The check uses a two-tier approach: (1) in-process Bloom filter for fast rejection of non-blacklisted tokens, (2) Redis for definitive check when the Bloom filter says "maybe blacklisted."

**Why blacklisting instead of re-validating all JWTs:** Re-validating every JWT against the session store defeats the purpose of JWTs. Blacklisting maintains the O(1) local validation for 99.99% of requests (non-revoked tokens) and only adds a server round trip for the rare revoked token.

**Trade-off:** The Bloom filter has false positives. A small percentage of valid tokens will be flagged as "maybe blacklisted" and require a Redis lookup. At 0.01% FPR, this is 10 out of 100K requests -- negligible.

### Decision 4: Signing Key Rotation with Overlap Period

JWT signing keys are rotated every 30 days. During rotation, BOTH the old and new keys are valid for signature verification (overlap period of 24 hours). The JWKS (JSON Web Key Set) endpoint publishes both keys. After the overlap, the old key is retired.

**Why overlap:** Without an overlap period, rotating the signing key would instantly invalidate all existing JWTs signed with the old key. This would force all users to re-authenticate simultaneously -- a thundering herd that could bring down the authentication service.

**Implementation:** Each JWT contains a `kid` (Key ID) header that identifies which key was used to sign it. The verifier looks up the matching key from the JWKS endpoint. The JWKS endpoint is aggressively cached (TTL: 1 hour) at every service.

## Consistency Model

**Strong consistency for session state.** When a session is revoked (logout), the session store (Redis) is updated synchronously. All subsequent session validation requests see the revocation immediately. This is critical: a user who clicks "logout" expects immediate effect.

**Eventual consistency for JWT blacklist.** The Bloom filter at edge nodes is rebuilt periodically (every 5 seconds). A JWT revoked via blacklist may still be valid for up to 5 seconds at some edge nodes. For the 15-minute JWT TTL, this 5-second window is acceptable.

**Strong consistency for identity data.** User profile changes (email, password, MFA enrollment) are written to PostgreSQL with read-after-write consistency. A user who changes their password can immediately log in with the new password.

## Failure Modes

### Session store (Redis) failure
JWT validation continues locally (signature + expiry check only). Refresh token operations fail -- users cannot get new access tokens. Existing JWTs remain valid until they expire (up to 15 minutes). Session revocation is not possible during the outage. This is the most critical failure mode.

**Mitigation:** Redis Cluster with 6 nodes (3 masters + 3 replicas) across 3 AZs. Sentinel for automatic failover. Failover completes in ~5 seconds.

### Identity database failure
New logins fail. Password changes fail. User registrations fail. Existing sessions continue to work because they don't need the identity DB for validation.

### Signing key compromise
Emergency procedure: (1) add the compromised key to a "revoked keys" list, (2) issue a new signing key, (3) force all clients to refresh their JWTs. Any JWT signed with the compromised key is rejected. This is a nuclear option -- it forces re-authentication for all users.

### Brute force attack
The brute force protection service tracks failed login attempts per IP and per account. After 5 failures: CAPTCHA required. After 10 failures: account locked for 30 minutes. After 100 failures from one IP: IP-level rate limiting. All thresholds are configurable.

### Session hijacking
The session includes device fingerprint and IP range. If a session is used from a drastically different device or geography, step-up authentication is triggered. The risk engine scores each request and may require MFA re-verification.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **Web Browser** | Cookie-based session with HttpOnly, Secure, SameSite flags |
| **Mobile App** | Token-based authentication with secure token storage (Keychain/Keystore) |
| **Service-to-Service** | mTLS or JWT-based service authentication |
| **External IdP** | Google, Okta, Azure AD for federated login (OIDC/SAML) |
| **MFA Device** | TOTP authenticator app, WebAuthn security key, SMS backup |
| **API Gateway** | Routes auth requests, rate limiting, DDoS protection |
| **Token Validator** | Local JWT signature verification using cached JWKS |
| **Session Validator** | Server-side opaque token lookup in Redis |
| **Brute Force Protection** | Tracks failed attempts, triggers CAPTCHA/lockout |
| **Authentication Service** | Handles login flows (password, social, SSO, MFA) |
| **Session Service** | Creates, refreshes, and revokes sessions |
| **Token Service** | Issues and signs JWTs with configurable claims |
| **Authorization Service** | RBAC/ABAC policy evaluation |
| **MFA Service** | TOTP generation/validation, WebAuthn ceremony handling |
| **Password Service** | Hashing (argon2id), validation, breach checking (HaveIBeenPwned) |
| **Federation Service** | OIDC/SAML protocol handling for external IdPs |
| **Signing Key Rotation** | Periodic key rotation with overlap period |
| **Identity DB** | User profiles, credentials, roles, permissions |
| **Session Store (Redis)** | Active sessions with TTL-based expiry |
| **Token Blacklist** | Revoked JWT IDs for immediate revocation |
| **Signing Keys (JWKS)** | Public/private key pairs for JWT signing |
| **Audit Log** | Every authentication event (login, logout, failure, MFA) |
| **Anomaly Detection** | Detects unusual login patterns (new device, impossible travel) |
| **Risk Engine** | Adaptive authentication -- adjusts auth requirements based on risk |
| **Compliance Reporting** | GDPR data access requests, SOC 2 audit reports |

## Key Trade-offs

### Stateless (JWT) vs. Stateful (Session) Validation
We use a hybrid: JWTs for performance (no DB hit on most requests) with server-side sessions for revocability (immediate logout). Pure stateless would be faster but unrevocable. Pure stateful would be revocable but slower.

### Token TTL: Short vs. Long
Short JWTs (15 min) limit the exposure window of compromised tokens but require frequent refreshes. Long JWTs (1 hour) reduce refresh traffic but extend the compromise window. 15 minutes is the standard industry balance.

### Security vs. User Experience
MFA on every login is more secure but frustrating. Adaptive MFA (only when risk is elevated) provides a better UX but relies on the risk engine's accuracy. We default to MFA-required and let organizations configure their risk tolerance.

## What Fails First

**The refresh token endpoint becomes the thundering herd bottleneck.** With 15-minute JWTs and 50M active sessions, approximately 50M / 15 = 3.3M refresh requests per 15-minute window, or ~3,700/second. This is manageable. But if the JWT signing key is rotated (all JWTs instantly invalid), all 50M sessions try to refresh simultaneously.

**Mitigation:** The overlap period for key rotation prevents this scenario. If the session store fails and recovers, stagger the retry with exponential backoff in the client SDK. Pre-provision extra capacity for the refresh endpoint to handle 10x normal load.

## v1 vs v2

### v1 (Ship first)
- Email/password authentication only
- Opaque session tokens stored in Redis (no JWT)
- Simple session revocation (delete from Redis)
- Basic RBAC (admin/user roles)
- bcrypt password hashing
- Rate limiting on login endpoint
- Simple audit logging

### v2 (Scale and harden)
- JWT + refresh token hybrid
- Passkeys (WebAuthn) as primary credential -- phishing-resistant, syncs across user's devices via platform keychain (iCloud Keychain, Google Password Manager)
- Social login (Google, GitHub) and enterprise SSO (SAML/OIDC)
- OAuth 2.1 compliance: mandatory PKCE for all flows, no implicit grant, no password grant, exact-match redirect URIs
- MFA with TOTP and WebAuthn (SMS deprecated for high-assurance flows)
- Refresh token rotation with reuse detection
- DPoP (Demonstrating Proof of Possession) for sender-constrained tokens on financial/admin APIs
- Token blacklist with Bloom filter
- Signing key rotation with overlap
- Adaptive authentication with risk engine
- Password breach checking (HaveIBeenPwned API)
- GDPR compliance (right to deletion, data export)
- Device trust and session binding
- Step-up authentication for sensitive operations
