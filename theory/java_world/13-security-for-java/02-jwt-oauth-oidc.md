# 02. JWT, OAuth 2.0/2.1, and OpenID Connect

## JWT in one minute

A JWT is a token format, not an authentication architecture.

- Header: metadata such as `alg` and `kid`
- Payload: claims such as `sub`, `iss`, `aud`, `exp`
- Signature: integrity protection

Use JWTs when you need portable claims or decentralized validation. Do not use them just because "microservices".

## Signing algorithms

- Prefer asymmetric signing for distributed systems: `RS256`, `PS256`, `ES256`, or `EdDSA` where supported by your ecosystem.
- Avoid `alg=none`, weak shared secrets, and algorithm confusion.
- Keep `kid`-based key rotation simple with JWKS.

## Standard claims you should validate

- `iss`: expected issuer
- `aud`: intended audience
- `exp`: not expired
- `nbf`: not used too early
- `iat`: sane issue time
- `sub`: stable subject identity
- `jti`: useful for replay detection and revocation bookkeeping

## Access tokens vs refresh tokens

Access tokens:

- Short-lived
- Sent to resource servers
- Should contain only the claims the resource server needs

Refresh tokens:

- Longer-lived
- Sent only to the authorization server
- Need stronger storage and replay controls

## Common JWT mistakes

- Treating JWT as "automatically secure" because it is signed
- Storing long-lived bearer tokens in `localStorage`
- Putting too much authorization state into long-lived tokens
- Skipping `iss` and `aud` checks
- Using JWTs where opaque tokens plus introspection would be operationally safer

## Revocation and compromise handling

JWT validation is fast because it is local, but that also means revocation is harder.

Common patterns:

- Short access-token lifetime
- Refresh-token rotation
- `jti` denylist in Redis for high-risk events
- Key rotation through JWKS
- Opaque tokens + introspection for endpoints that require immediate revocation

## OAuth 2.1 status in April 2026

OAuth 2.1 is still an IETF draft as of March 2026 (`draft-ietf-oauth-v2-1-15`). In production, most systems are still "OAuth 2.0 plus modern security BCPs".

That is the right senior answer. Do not say "OAuth 2.1 is fully standardized" unless that actually changes and you recheck.

## Modern OAuth posture

| Topic | Practical 2026 answer |
|---|---|
| Main browser flow | Authorization Code + PKCE |
| Implicit flow | Do not use it |
| Resource Owner Password grant | Do not use it |
| Confidential clients | Still authenticate to the AS; PKCE is also recommended/used for auth-code hardening |
| Public clients | No client secret; use PKCE |
| Redirect URIs | Exact registered URI matching is the safe/default posture |
| Refresh-token replay protection | For public clients, use sender-constrained refresh tokens or rotation |
| Query-string bearer tokens | Avoid them; use the `Authorization` header |

## OAuth roles

- Resource owner: the user
- Client: the application
- Authorization server: issues tokens
- Resource server: protects APIs and validates or introspects tokens

## Authorization Code + PKCE

This is the default answer for browser and mobile login.

Flow:

1. Client creates a random `code_verifier`
2. Client derives a `code_challenge`
3. Browser redirects to the authorization server
4. User authenticates and consents
5. Client receives an authorization code
6. Client sends the code plus `code_verifier` to the token endpoint

PKCE protects against authorization-code interception and code injection. It is no longer just a "public-client-only trick".

## Client Credentials

Use for service-to-service access where no end user is present.

Do not forward end-user permissions into every machine token by default. Distinguish:

- machine identity
- delegated user identity

## Device Authorization Grant

Good answer for TVs, CLIs, kiosks, and constrained devices.

## OpenID Connect

OIDC is the identity layer on top of OAuth 2.x.

- OAuth gives you delegated authorization
- OIDC adds authentication semantics
- The ID token tells the client who authenticated

## ID token validation checklist

Validate at least:

1. signature against the issuer's key set
2. `iss`
3. `aud`
4. `exp`
5. `nonce` when used in the authorization request
6. `acr` / `amr` if your business policy requires a specific auth strength

Do not use an access token as a drop-in replacement for an ID token.

## `state`, `nonce`, and PKCE

Keep these separate in your head:

- `state`: client-side request correlation and CSRF protection
- `nonce`: protects OIDC ID-token replay/substitution
- PKCE: binds the authorization code exchange to the original client

They solve different problems. In browser-based login flows, the safe default is to use all three where applicable.

## Sender-constrained tokens: DPoP and mTLS

Bearer tokens are usable by whoever steals them. Sender-constrained tokens reduce that blast radius.

### DPoP

- Defined by RFC 9449
- Client proves possession of a private key by signing a DPoP proof JWT per request
- Better fit for browser or public-client scenarios where mTLS is awkward

### mTLS

- Defined by RFC 8705
- Strong fit for service-to-service and regulated environments
- Operationally heavier, but still the most natural answer for internal machine-to-machine trust

Senior nuance: DPoP reduces bearer-token theft risk but does not magically defeat XSS if the attacker controls the client runtime.

## PAR, JAR, and JARM

These are high-assurance hardening features you should know by name:

- PAR: Pushed Authorization Requests, RFC 9126
- JAR: JWT-Secured Authorization Request, RFC 9101
- JARM: JWT-Secured Authorization Response Mode, OpenID Foundation spec

Use them when the front channel is too exposed or the ecosystem requires strong request/response integrity, such as open banking or government profiles.

Important Spring-specific correction:

- Spring Authorization Server 1.5.x explicitly documents PAR and DPoP support.
- The 1.5.x reference docs do not present JAR or JARM as first-class built-in features in the same way, so do not claim "Spring Authorization Server supports PAR, JAR, and JARM out of the box" without rechecking newer docs.

## JWT validation vs introspection

| Approach | Best for | Trade-off |
|---|---|---|
| Local JWT validation | High-throughput APIs | Revocation is delayed unless you add more machinery |
| Introspection | Opaque tokens or hard real-time revocation | Extra network hop and AS dependency |

## Spring Security resource-server example

```java
@Bean
SecurityFilterChain api(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests((authz) -> authz
            .requestMatchers("/api/public/**").permitAll()
            .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin")
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer((oauth2) -> oauth2
            .jwt((jwt) -> jwt
                .jwtAuthenticationConverter(jwtAuthenticationConverter())
            )
        )
        .build();
}

@Bean
JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities =
        new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
}
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/issuer
```

## Interview shortcuts

- "OAuth is about delegated access; OIDC is the identity layer."
- "JWT is just one token format."
- "Auth Code + PKCE is the default for browser/mobile clients."
- "Use short-lived access tokens and treat refresh tokens as high-value credentials."
- "For public clients, replay-protect refresh tokens with sender constraint or rotation."
- "Use DPoP where mTLS is impractical; use mTLS for service-to-service."

## Primary references

- OAuth 2.1 draft: <https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/>
- OAuth 2.0 Security BCP / RFC 9700: <https://www.rfc-editor.org/rfc/rfc9700>
- OAuth 2.0 PAR / RFC 9126: <https://www.rfc-editor.org/rfc/rfc9126>
- OAuth 2.0 DPoP / RFC 9449: <https://www.rfc-editor.org/rfc/rfc9449>
- OAuth 2.0 mTLS / RFC 8705: <https://www.rfc-editor.org/rfc/rfc8705>
- OIDC Core: <https://openid.net/specs/openid-connect-core-1_0.html>
- Spring Security OAuth2 resource server docs: <https://docs.spring.io/spring-security/reference/servlet/oauth2/>
- Spring Authorization Server overview: <https://docs.spring.io/spring-authorization-server/reference/overview.html>
