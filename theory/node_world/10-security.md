# Security for Node.js Applications — Senior Engineer Interview Preparation

---

## 1. Authentication & Authorization

### Authentication ("Who are you?")

Authentication is the process of **verifying identity** — confirming that a user or system is who they claim to be. It answers the question "Who are you?" before any access decision is made.

**Authentication Factors:**

| Factor | Description | Examples |
|--------|-------------|----------|
| Something you **know** | Knowledge-based secrets | Password, PIN, security questions |
| Something you **have** | Physical or digital possession | Hardware token, phone (SMS/TOTP), smart card, passkey |
| Something you **are** | Biometric characteristics | Fingerprint, face recognition, retinal scan |

**Multi-Factor Authentication (MFA)** requires two or more factors from **different** categories. Using a password + security question is NOT true MFA (both are "something you know"). A password (know) + TOTP code from an authenticator app (have) is genuine MFA.

### Authorization ("What can you do?")

Authorization determines **what actions an authenticated entity is permitted to perform**. It happens after authentication and answers "What are you allowed to do?"

### Session-Based vs Token-Based Authentication

| Aspect | Session-Based | Token-Based (JWT) |
|--------|--------------|-------------------|
| State | **Stateful** — server stores session in memory/DB/Redis | **Stateless** — all data encoded in the token |
| Storage | Session ID in a cookie | Token in cookie, `localStorage`, or `Authorization` header |
| Scalability | Requires shared session store for multiple servers | Any server can validate — no shared state needed |
| Revocation | Easy — delete session from store | Hard — token valid until expiration (requires blocklist) |
| Size | Small cookie (~32 bytes session ID) | Large (~800+ bytes with claims) |
| CSRF vulnerability | Yes (cookies sent automatically) | No (if sent via `Authorization` header) |
| XSS vulnerability | HttpOnly cookie protects session ID | `localStorage` is accessible via XSS |
| Best for | Traditional web apps with server rendering | SPAs, mobile apps, microservice APIs |

**Session-based flow:**
```
Client -> POST /login (credentials)
Server -> Creates session, stores in Redis, returns Set-Cookie: sessionId=abc123
Client -> GET /api/data (Cookie: sessionId=abc123)
Server -> Looks up session in Redis -> returns data
```

**Token-based flow:**
```
Client -> POST /login (credentials)
Server -> Creates JWT, returns { token: "eyJ..." }
Client -> GET /api/data (Authorization: Bearer eyJ...)
Server -> Validates JWT signature + claims -> returns data
```

### JWT Deep Dive

A JWT consists of three Base64URL-encoded parts separated by dots:

```
Header.Payload.Signature

eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.
eyJzdWIiOiJ1c2VyXzEyMyIsInJvbGUiOiJhZG1pbiIsImlhdCI6MTcxMjAwMDAwMH0.
<signature-bytes>
```

**Header** — algorithm and token type:
```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "key-2024-04"
}
```

**Payload** — claims (data):
```json
{
  "sub": "user_123",
  "name": "Jane Doe",
  "role": "admin",
  "iat": 1712000000,
  "exp": 1712003600,
  "iss": "auth.example.com",
  "aud": "api.example.com"
}
```

**Signature** — ensures integrity:
```
RSA-SHA256(base64UrlEncode(header) + "." + base64UrlEncode(payload), privateKey)
```

### Signing Algorithms

| Algorithm | Type | Key | Use Case |
|-----------|------|-----|----------|
| HS256 | Symmetric (HMAC) | Single shared secret | Single service, internal APIs |
| RS256 | Asymmetric (RSA) | Private key signs, public key verifies | Distributed systems, OIDC providers |
| ES256 | Asymmetric (ECDSA) | Smaller keys than RSA, same security | Mobile, performance-sensitive |
| EdDSA | Asymmetric (Ed25519) | Modern, high performance | Emerging standard, faster than RS256 |

**When to use which:**
- **HS256** — when the same service creates and validates tokens. Simpler, faster. The secret must be shared with every verifier (security risk in distributed systems).
- **RS256** — when multiple services need to verify tokens but only one should create them. The authorization server keeps the private key; resource servers only need the public key. Standard choice for OAuth 2.0 / OIDC.
- **ES256** — same trust model as RS256 but with smaller keys (~256-bit vs ~2048-bit) and faster signing. Good default for new projects.

### JWT Pitfalls

1. **No built-in revocation** — once issued, a JWT is valid until `exp`. If a user logs out or is banned, the token still works. Mitigation: short-lived access tokens (5-15 min) + refresh tokens stored server-side, or a Redis-based token blocklist.

2. **Token size** — JWTs carry all claims in every request. A JWT with many roles/permissions can easily exceed 1KB. This adds bandwidth to every API call and can exceed cookie size limits (4KB).

3. **`none` algorithm attack** — an attacker modifies the header to `{"alg": "none"}` and strips the signature. Vulnerable libraries accept the token without verification. Always validate the algorithm server-side.

4. **Algorithm confusion** — an attacker changes `RS256` to `HS256` and signs with the public key (which is public). The server, configured for RS256, treats the public key as the HMAC secret and validates the forged token. Mitigation: explicitly specify the expected algorithm.

5. **Sensitive data in payload** — JWTs are only Base64-encoded, not encrypted. Never put passwords, SSNs, or secrets in claims. Anyone can decode the payload.

### JWT Implementation in Node.js

```typescript
import jwt, { JwtPayload } from "jsonwebtoken";
import { readFileSync } from "node:fs";
import { Request, Response, NextFunction } from "express";

// --- Key Loading ---
const privateKey = readFileSync("./keys/private.pem", "utf-8");
const publicKey = readFileSync("./keys/public.pem", "utf-8");

// --- Token Creation ---
interface TokenPayload {
  sub: string;
  role: string;
  permissions: string[];
}

function createAccessToken(user: {
  id: string;
  role: string;
  permissions: string[];
}): string {
  const payload: TokenPayload = {
    sub: user.id,
    role: user.role,
    permissions: user.permissions,
  };

  return jwt.sign(payload, privateKey, {
    algorithm: "RS256",        // Asymmetric — only auth service has private key
    expiresIn: "15m",          // Short-lived access token
    issuer: "auth.example.com",
    audience: "api.example.com",
  });
}

function createRefreshToken(userId: string): string {
  return jwt.sign({ sub: userId }, privateKey, {
    algorithm: "RS256",
    expiresIn: "7d",           // Long-lived refresh token
    issuer: "auth.example.com",
    jwtid: crypto.randomUUID(), // Unique ID for revocation tracking
  });
}

// --- Token Verification ---
function verifyAccessToken(token: string): JwtPayload {
  // CRITICAL: Explicitly specify algorithms to prevent algorithm confusion attacks
  return jwt.verify(token, publicKey, {
    algorithms: ["RS256"],        // ONLY allow RS256 — prevents "none" and HS256 attacks
    issuer: "auth.example.com",   // Reject tokens from other issuers
    audience: "api.example.com",  // Reject tokens for other audiences
    clockTolerance: 30,           // Allow 30s clock skew between servers
  }) as JwtPayload;
}

// --- Express Middleware ---
interface AuthenticatedRequest extends Request {
  user?: JwtPayload;
}

function authMiddleware(
  req: AuthenticatedRequest,
  res: Response,
  next: NextFunction
): void {
  const authHeader = req.headers.authorization;
  if (!authHeader?.startsWith("Bearer ")) {
    res.status(401).json({ error: "Missing or malformed Authorization header" });
    return;
  }

  const token = authHeader.slice(7); // Remove "Bearer "

  try {
    const decoded = verifyAccessToken(token);
    req.user = decoded;
    next();
  } catch (err) {
    if (err instanceof jwt.TokenExpiredError) {
      res.status(401).json({ error: "Token expired" });
    } else if (err instanceof jwt.JsonWebTokenError) {
      res.status(401).json({ error: "Invalid token" });
    } else {
      res.status(500).json({ error: "Internal server error" });
    }
  }
}

// --- Role-Based Authorization Middleware ---
function requireRole(...roles: string[]) {
  return (req: AuthenticatedRequest, res: Response, next: NextFunction): void => {
    if (!req.user || !roles.includes(req.user.role as string)) {
      res.status(403).json({ error: "Insufficient permissions" });
      return;
    }
    next();
  };
}

// Usage:
// app.delete("/api/users/:id", authMiddleware, requireRole("admin"), deleteUserHandler);
```

### OAuth 2.0 Flows

OAuth 2.0 is an **authorization framework** (not authentication) that enables third-party applications to access resources on behalf of a user without sharing credentials.

| Flow | Use Case | Involves User? | Client Type |
|------|----------|---------------|-------------|
| **Authorization Code + PKCE** | SPAs, mobile apps, server apps | Yes | Public or confidential |
| **Client Credentials** | Machine-to-machine (service accounts) | No | Confidential |
| **Device Code** | Smart TVs, CLIs, IoT devices | Yes (on separate device) | Public |
| **Refresh Token** | Obtaining new access tokens silently | No (after initial consent) | Any |

**Authorization Code Flow with PKCE (Proof Key for Code Exchange):**

```
1. Client generates code_verifier (random string) and code_challenge = SHA256(code_verifier)
2. Client -> Auth Server: GET /authorize?response_type=code&code_challenge=<hash>&code_challenge_method=S256
3. User authenticates and consents
4. Auth Server -> Client: redirect with ?code=AUTH_CODE
5. Client -> Auth Server: POST /token { code, code_verifier }
6. Auth Server: verifies SHA256(code_verifier) == code_challenge
7. Auth Server -> Client: { access_token, refresh_token, id_token }
```

PKCE prevents authorization code interception attacks. Even if an attacker captures the `AUTH_CODE` from step 4, they cannot exchange it without the `code_verifier`.

```typescript
import crypto from "node:crypto";

// PKCE helper — used by the client before starting the OAuth flow
function generatePKCE(): { codeVerifier: string; codeChallenge: string } {
  // code_verifier: 43-128 characters, unreserved URI characters
  const codeVerifier = crypto.randomBytes(32).toString("base64url");

  // code_challenge: SHA256 hash of the verifier, Base64URL-encoded
  const codeChallenge = crypto
    .createHash("sha256")
    .update(codeVerifier)
    .digest("base64url");

  return { codeVerifier, codeChallenge };
}
```

**Client Credentials Flow** — machine-to-machine, no user involvement:

```typescript
// Service A calling Service B
async function getServiceToken(): Promise<string> {
  const response = await fetch("https://auth.example.com/oauth/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "client_credentials",
      client_id: process.env.CLIENT_ID!,
      client_secret: process.env.CLIENT_SECRET!,
      scope: "read:reports write:metrics",
    }),
  });

  const data = await response.json();
  return data.access_token;
}
```

### OpenID Connect (OIDC)

OIDC is an **identity layer on top of OAuth 2.0**. While OAuth 2.0 only handles authorization ("can this app access my photos?"), OIDC adds authentication ("who is this user?").

Key additions:
- **ID Token** — a JWT containing user identity claims (`sub`, `name`, `email`, `email_verified`)
- **`/userinfo` endpoint** — returns additional profile information
- **Standardized scopes** — `openid`, `profile`, `email`, `address`, `phone`
- **Discovery document** — `/.well-known/openid-configuration` describes all endpoints

### Passport.js Strategies

Passport.js is middleware for Node.js that abstracts authentication strategies behind a uniform interface.

```typescript
import passport from "passport";
import {
  Strategy as JwtStrategy,
  ExtractJwt,
  StrategyOptionsWithoutRequest,
} from "passport-jwt";
import { Strategy as GoogleStrategy } from "passport-google-oauth20";

// --- JWT Strategy ---
const jwtOptions: StrategyOptionsWithoutRequest = {
  jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
  secretOrKey: publicKey,
  algorithms: ["RS256"],
  issuer: "auth.example.com",
  audience: "api.example.com",
};

passport.use(
  new JwtStrategy(jwtOptions, async (payload, done) => {
    try {
      const user = await userRepository.findById(payload.sub);
      if (!user) return done(null, false);
      return done(null, user);
    } catch (err) {
      return done(err, false);
    }
  })
);

// --- Google OAuth Strategy ---
passport.use(
  new GoogleStrategy(
    {
      clientID: process.env.GOOGLE_CLIENT_ID!,
      clientSecret: process.env.GOOGLE_CLIENT_SECRET!,
      callbackURL: "/auth/google/callback",
      scope: ["openid", "profile", "email"],
    },
    async (accessToken, refreshToken, profile, done) => {
      try {
        let user = await userRepository.findByGoogleId(profile.id);
        if (!user) {
          user = await userRepository.create({
            googleId: profile.id,
            email: profile.emails?.[0]?.value,
            name: profile.displayName,
          });
        }
        return done(null, user);
      } catch (err) {
        return done(err as Error, undefined);
      }
    }
  )
);

// Usage in routes:
// app.get("/api/protected", passport.authenticate("jwt", { session: false }), handler);
// app.get("/auth/google", passport.authenticate("google"));
```

### Authorization Models: RBAC vs ABAC vs ACL

#### RBAC (Role-Based Access Control)

Users are assigned **roles**, and roles are granted **permissions**. Simple and widely used.

```typescript
// Simple RBAC middleware
const ROLE_PERMISSIONS: Record<string, Set<string>> = {
  admin:  new Set(["read", "write", "delete", "manage_users"]),
  editor: new Set(["read", "write"]),
  viewer: new Set(["read"]),
};

function requirePermission(permission: string) {
  return (req: AuthenticatedRequest, res: Response, next: NextFunction): void => {
    const userRole = req.user?.role as string;
    const permissions = ROLE_PERMISSIONS[userRole];

    if (!permissions?.has(permission)) {
      res.status(403).json({ error: `Missing permission: ${permission}` });
      return;
    }
    next();
  };
}

// app.delete("/api/posts/:id", authMiddleware, requirePermission("delete"), deletePost);
```

#### ABAC (Attribute-Based Access Control)

Access decisions based on **attributes** of the user, resource, action, and environment. More flexible, more complex.

```typescript
interface PolicyContext {
  user: { id: string; role: string; department: string; clearanceLevel: number };
  resource: { ownerId: string; department: string; classification: number };
  action: string;
  environment: { time: Date; ipAddress: string };
}

function evaluatePolicy(ctx: PolicyContext): boolean {
  // Rule 1: Admins can do anything
  if (ctx.user.role === "admin") return true;

  // Rule 2: Users can only access resources in their department
  if (ctx.user.department !== ctx.resource.department) return false;

  // Rule 3: Clearance level must meet or exceed classification
  if (ctx.user.clearanceLevel < ctx.resource.classification) return false;

  // Rule 4: Destructive actions only allowed during business hours
  if (ctx.action === "delete") {
    const hour = ctx.environment.time.getHours();
    if (hour < 9 || hour > 17) return false;
  }

  return true;
}
```

#### ACL (Access Control List)

Each resource has an explicit list of who can access it and with what permissions. Fine-grained but hard to scale.

```typescript
interface ACLEntry {
  resourceId: string;
  principalId: string;
  permissions: Set<string>;
}

// ACL stored per resource in database
// document_acl: { document_id, user_id, permission }
// Check: SELECT 1 FROM document_acl WHERE document_id = $1 AND user_id = $2 AND permission = $3
```

### Comparison Table

| Feature | RBAC | ABAC | ACL |
|---------|------|------|-----|
| Granularity | Coarse (role-level) | Fine (attribute-level) | Fine (per-resource) |
| Scalability | High | Medium | Low |
| Complexity | Low | High | Medium |
| Dynamic decisions | No (static roles) | Yes (runtime attributes) | No (static lists) |
| Audit trail | Easy (role assignments) | Complex (attribute evaluation) | Easy (explicit lists) |
| Best for | APIs, microservices | Complex policies, multi-tenant | File systems, shared documents |
| Implementation effort | Low | High | Medium |

---

## 2. Common Vulnerabilities (OWASP Top 10)

### SQL Injection

SQL injection occurs when user input is concatenated directly into SQL queries, allowing an attacker to manipulate the query logic.

**Vulnerable code:**

```typescript
// VULNERABLE: String concatenation in SQL
import { Pool } from "pg";
const pool = new Pool();

async function getUser(username: string) {
  // Attacker passes: username = "admin' OR '1'='1' --"
  // Resulting query: SELECT * FROM users WHERE username = 'admin' OR '1'='1' --'
  const result = await pool.query(
    `SELECT * FROM users WHERE username = '${username}'`
  );
  return result.rows[0];
}

// Even worse — DROP TABLE attack:
// username = "'; DROP TABLE users; --"
```

**Secure code:**

```typescript
// SECURE: Parameterized queries — the driver escapes input automatically
async function getUser(username: string) {
  const result = await pool.query(
    "SELECT * FROM users WHERE username = $1",
    [username]
  );
  return result.rows[0];
}

// SECURE with ORM (Prisma):
async function getUserPrisma(username: string) {
  return prisma.user.findUnique({
    where: { username }, // Prisma parameterizes automatically
  });
}

// SECURE with query builder (Knex):
async function getUserKnex(username: string) {
  return knex("users").where({ username }).first();
}
```

### NoSQL Injection

NoSQL databases like MongoDB are also vulnerable. Attackers exploit query operators passed as objects.

**Vulnerable code:**

```typescript
// VULNERABLE: Passing req.body directly to MongoDB query
import { MongoClient } from "mongodb";

app.post("/api/login", async (req, res) => {
  const { username, password } = req.body;

  // Attacker sends: { "username": "admin", "password": { "$gt": "" } }
  // This matches any password greater than empty string — always true
  const user = await db.collection("users").findOne({
    username: username,
    password: password, // If password is { "$gt": "" }, this bypasses auth
  });

  if (user) {
    res.json({ token: createToken(user) });
  } else {
    res.status(401).json({ error: "Invalid credentials" });
  }
});
```

**Secure code:**

```typescript
// SECURE: Validate input types and sanitize
import { z } from "zod";

const loginSchema = z.object({
  username: z.string().min(1).max(100),
  password: z.string().min(1).max(200),
});

app.post("/api/login", async (req, res) => {
  const parsed = loginSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: "Invalid input" });
  }

  // Zod guarantees these are strings, not objects with $gt/$ne operators
  const { username, password } = parsed.data;

  const user = await db.collection("users").findOne({
    username: String(username), // Extra safety: explicit String() cast
  });

  if (user && await bcrypt.compare(password, user.passwordHash)) {
    res.json({ token: createToken(user) });
  } else {
    res.status(401).json({ error: "Invalid credentials" });
  }
});
```

### Command Injection

Occurs when user input is passed to shell commands via `exec`, which spawns a shell and interprets metacharacters.

**Vulnerable code:**

```typescript
import { exec } from "node:child_process";

// VULNERABLE: User input in shell command
app.get("/api/dns/:domain", (req, res) => {
  const domain = req.params.domain;
  // Attacker sends: domain = "example.com; rm -rf /"
  // exec() spawns a shell that interprets the semicolon as a command separator
  exec(`nslookup ${domain}`, (err, stdout) => {
    res.json({ result: stdout });
  });
});
```

**Secure code:**

```typescript
import { execFile } from "node:child_process";

// SECURE: execFile does NOT spawn a shell — arguments passed directly to the process
app.get("/api/dns/:domain", (req, res) => {
  const domain = req.params.domain;

  // Validate domain format
  const domainRegex = /^[a-zA-Z0-9][a-zA-Z0-9.-]+[a-zA-Z]{2,}$/;
  if (!domainRegex.test(domain)) {
    return res.status(400).json({ error: "Invalid domain" });
  }

  // execFile passes arguments as an array — semicolons, pipes, and other
  // shell metacharacters have no special meaning
  execFile("nslookup", [domain], (err, stdout) => {
    if (err) return res.status(500).json({ error: "Lookup failed" });
    res.json({ result: stdout });
  });
});
```

### XSS (Cross-Site Scripting)

XSS allows attackers to inject malicious scripts into pages viewed by other users. Three types:

| Type | Where Injected | Persistence | Example |
|------|---------------|-------------|---------|
| **Stored** | Database, rendered to all users | Persistent | Comment containing `<script>` tag |
| **Reflected** | URL parameter, reflected in response | Per-request | Search query in URL rendered on page |
| **DOM-based** | Client-side JS manipulates DOM | Per-request | `innerHTML = location.hash` |

**Vulnerable code (stored XSS):**

```typescript
// VULNERABLE: Rendering user input as raw HTML
app.get("/api/comments", async (req, res) => {
  const comments = await db.collection("comments").find().toArray();
  // If a comment contains <script>document.cookie</script>,
  // it runs in every viewer's browser
  const html = comments
    .map((c) => `<div class="comment">${c.text}</div>`)
    .join("");
  res.send(html);
});
```

**Secure code:**

```typescript
import createDOMPurify from "dompurify";
import { JSDOM } from "jsdom";
import { escape as escapeHtml } from "lodash";

const window = new JSDOM("").window;
const DOMPurify = createDOMPurify(window);

// SECURE: Sanitize HTML input before storage AND escape on output
app.post("/api/comments", async (req, res) => {
  const rawText = req.body.text;

  // Option 1: Strip all HTML (plain text only)
  const safeText = escapeHtml(rawText);
  // <script>alert('xss')</script> becomes &lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;

  // Option 2: Allow safe HTML subset (bold, italic, links)
  const safeHtml = DOMPurify.sanitize(rawText, {
    ALLOWED_TAGS: ["b", "i", "a", "p", "br"],
    ALLOWED_ATTR: ["href"],
  });

  await db.collection("comments").insertOne({ text: safeText });
  res.status(201).json({ success: true });
});
```

**Content Security Policy (CSP)** is the strongest defense against XSS — see Section 4.

### CSRF (Cross-Site Request Forgery)

CSRF tricks a user's browser into making requests to a site where they are already authenticated. Only relevant for cookie-based authentication.

**Attack scenario:**
```html
<!-- Attacker's page at evil.com -->
<!-- If user is logged into bank.com, cookies are sent automatically -->
<form action="https://bank.com/transfer" method="POST" id="attack">
  <input type="hidden" name="to" value="attacker_account" />
  <input type="hidden" name="amount" value="10000" />
</form>
<script>document.getElementById('attack').submit();</script>
```

**Prevention — CSRF tokens + SameSite cookies:**

> ⚠️ **`csurf` is deprecated and archived** (unmaintained since 2022, no security patches). Do **not** use it in new code. Production-ready alternatives: **`csrf-csrf`** (double-submit cookie, actively maintained), **`@nestjs/csrf` / `@fastify/csrf-protection`** for framework-specific use. The `csurf` example below is kept for historical reference only — the token-workflow concepts are identical across libraries.

```typescript
// ⚠️ csurf is archived; prefer csrf-csrf in new projects. API is nearly identical.
import csrf from "csurf";
import cookieParser from "cookie-parser";

app.use(cookieParser());

// CSRF protection middleware — generates and validates tokens
const csrfProtection = csrf({
  cookie: {
    httpOnly: true,
    sameSite: "strict",  // Cookie only sent for same-site requests
    secure: true,         // HTTPS only
  },
});

app.get("/api/csrf-token", csrfProtection, (req, res) => {
  // Client must include this token in subsequent state-changing requests
  res.json({ csrfToken: req.csrfToken() });
});

app.post("/api/transfer", csrfProtection, (req, res) => {
  // csurf validates the token from req.body._csrf, query._csrf, or x-csrf-token header
  // If token is missing or invalid, returns 403
  performTransfer(req.body);
  res.json({ success: true });
});

// Modern approach: SameSite=Strict cookies eliminate most CSRF without tokens
app.use(
  session({
    cookie: {
      httpOnly: true,
      secure: true,
      sameSite: "strict",  // Browser will NOT send this cookie on cross-site requests
      maxAge: 3600000,
    },
  })
);
```

### SSRF (Server-Side Request Forgery)

SSRF allows an attacker to make the server send HTTP requests to internal resources that are not directly accessible from the internet.

**Vulnerable code:**

```typescript
// VULNERABLE: Accepting arbitrary URLs from user input
app.post("/api/fetch-url", async (req, res) => {
  const { url } = req.body;
  // Attacker sends: url = "http://169.254.169.254/latest/meta-data/iam/security-credentials/"
  // This fetches AWS instance metadata, exposing IAM credentials
  // Or: url = "http://localhost:6379/" to probe internal Redis
  const response = await fetch(url);
  const data = await response.text();
  res.json({ content: data });
});
```

**Secure code:**

```typescript
import { URL } from "node:url";
import { lookup } from "node:dns/promises";

const BLOCKED_HOSTS = new Set(["localhost", "127.0.0.1", "0.0.0.0", "::1"]);
const ALLOWED_PROTOCOLS = new Set(["https:"]);

async function isUrlSafe(urlString: string): Promise<boolean> {
  let parsed: URL;
  try {
    parsed = new URL(urlString);
  } catch {
    return false;
  }

  // Block non-HTTPS protocols
  if (!ALLOWED_PROTOCOLS.has(parsed.protocol)) return false;

  // Block localhost and loopback
  if (BLOCKED_HOSTS.has(parsed.hostname)) return false;

  // Resolve DNS and block private IP ranges
  try {
    const { address } = await lookup(parsed.hostname);
    if (
      address.startsWith("10.") ||
      address.startsWith("172.16.") ||
      address.startsWith("192.168.") ||
      address.startsWith("169.254.") || // AWS metadata endpoint
      address === "127.0.0.1" ||
      address === "0.0.0.0"
    ) {
      return false;
    }
  } catch {
    return false;
  }

  return true;
}

app.post("/api/fetch-url", async (req, res) => {
  const { url } = req.body;

  if (!(await isUrlSafe(url))) {
    return res.status(400).json({ error: "URL not allowed" });
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);

  try {
    const response = await fetch(url, {
      signal: controller.signal,
      redirect: "error", // Don't follow redirects (could redirect to internal IP)
    });
    const data = await response.text();
    res.json({ content: data.slice(0, 10000) }); // Limit response size
  } catch {
    res.status(400).json({ error: "Failed to fetch URL" });
  } finally {
    clearTimeout(timeout);
  }
});
```

### Broken Access Control (IDOR)

Insecure Direct Object Reference (IDOR) occurs when an application exposes internal IDs and does not verify that the authenticated user is authorized to access the referenced object.

**Vulnerable code:**

```typescript
// VULNERABLE: No ownership check — any authenticated user can access any order
app.get("/api/orders/:orderId", authMiddleware, async (req, res) => {
  const order = await db.collection("orders").findOne({
    _id: new ObjectId(req.params.orderId),
  });
  // User A can access User B's order by guessing/incrementing the orderId
  res.json(order);
});
```

**Secure code:**

```typescript
// SECURE: Include user ID in the query — enforces ownership
app.get(
  "/api/orders/:orderId",
  authMiddleware,
  async (req: AuthenticatedRequest, res) => {
    const order = await db.collection("orders").findOne({
      _id: new ObjectId(req.params.orderId),
      userId: req.user!.sub, // Only returns if it belongs to the authenticated user
    });

    if (!order) {
      // Return 404, not 403 — don't reveal that the resource exists
      return res.status(404).json({ error: "Order not found" });
    }

    res.json(order);
  }
);
```

### Security Misconfiguration

| Misconfiguration | Risk | Fix |
|-----------------|------|-----|
| Debug endpoints in production | Exposes stack traces, env vars | Disable in production, use `NODE_ENV=production` |
| Default credentials | Full system access | Change defaults, enforce password policies |
| Directory listing enabled | Exposes file structure | Disable `serveIndex` in express.static |
| Verbose error messages | Reveals internal architecture | Generic errors in production, detailed in dev only |
| Unused HTTP methods | Attack surface | Only register needed routes |
| Open CORS (`*`) | Cross-origin data theft | Whitelist specific origins |

```typescript
// VULNERABLE: Leaking stack traces in production
app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
  res.status(500).json({
    error: err.message,
    stack: err.stack, // NEVER expose this in production
  });
});

// SECURE: Environment-aware error handling
app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
  console.error(err); // Log full error server-side

  if (process.env.NODE_ENV === "production") {
    res.status(500).json({ error: "Internal server error" });
  } else {
    res.status(500).json({ error: err.message, stack: err.stack });
  }
});
```

---

## 3. Input Validation & Sanitization

### Validation vs Sanitization

- **Validation** — checks that input conforms to expected rules (type, format, length, range). Rejects bad input.
- **Sanitization** — transforms input to remove dangerous content. Modifies input to make it safe.

**Always do both.** Validate first (reject invalid input), then sanitize (clean what remains).

### Zod — Schema Validation with TypeScript Integration

Zod is the go-to validation library for TypeScript because it infers static types from runtime schemas.

```typescript
import { z } from "zod";

// Schema definition — this IS the source of truth for the type
const CreateUserSchema = z.object({
  email: z
    .string()
    .email("Invalid email format")
    .max(255)
    .transform((e) => e.toLowerCase().trim()),

  password: z
    .string()
    .min(12, "Password must be at least 12 characters")
    .max(128)
    .regex(/[A-Z]/, "Must contain uppercase letter")
    .regex(/[a-z]/, "Must contain lowercase letter")
    .regex(/[0-9]/, "Must contain a number")
    .regex(/[^A-Za-z0-9]/, "Must contain a special character"),

  name: z
    .string()
    .min(1)
    .max(100)
    .regex(/^[a-zA-Z\s'-]+$/, "Name contains invalid characters"),

  age: z.number().int().min(13).max(150).optional(),

  role: z.enum(["user", "editor", "admin"]).default("user"),

  preferences: z
    .object({
      theme: z.enum(["light", "dark"]).default("light"),
      notifications: z.boolean().default(true),
    })
    .optional(),
});

// TypeScript type is INFERRED from the schema — no duplication
type CreateUserInput = z.infer<typeof CreateUserSchema>;
// Result type:
// {
//   email: string;
//   password: string;
//   name: string;
//   age?: number;
//   role: "user" | "editor" | "admin";
//   preferences?: { theme: "light" | "dark"; notifications: boolean };
// }

// Express middleware factory using Zod
function validate<T extends z.ZodType>(schema: T) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const result = schema.safeParse(req.body);
    if (!result.success) {
      const errors = result.error.issues.map((issue) => ({
        path: issue.path.join("."),
        message: issue.message,
      }));
      res.status(400).json({ errors });
      return;
    }
    req.body = result.data; // Replace with validated + transformed data
    next();
  };
}

app.post("/api/users", validate(CreateUserSchema), async (req, res) => {
  // req.body is now validated and typed as CreateUserInput
  const user = req.body as CreateUserInput;
  // email is already lowercased and trimmed by Zod transform
  await createUser(user);
  res.status(201).json({ success: true });
});
```

### Advanced Zod Patterns

```typescript
// Discriminated unions — different shapes based on a type field
const PaymentSchema = z.discriminatedUnion("method", [
  z.object({
    method: z.literal("credit_card"),
    cardNumber: z.string().regex(/^\d{16}$/),
    expiry: z.string().regex(/^\d{2}\/\d{2}$/),
    cvv: z.string().regex(/^\d{3,4}$/),
  }),
  z.object({
    method: z.literal("bank_transfer"),
    iban: z.string().min(15).max(34),
    bic: z.string().min(8).max(11),
  }),
  z.object({
    method: z.literal("crypto"),
    walletAddress: z.string().min(26).max(62),
    network: z.enum(["ethereum", "bitcoin", "solana"]),
  }),
]);

// Refinements — custom validation logic
const DateRangeSchema = z
  .object({
    startDate: z.coerce.date(),
    endDate: z.coerce.date(),
  })
  .refine((data) => data.endDate > data.startDate, {
    message: "End date must be after start date",
    path: ["endDate"],
  });

// Preprocessing — transform raw input before validation
const NumericIdSchema = z.preprocess(
  (val) => (typeof val === "string" ? parseInt(val, 10) : val),
  z.number().int().positive()
);
```

### Joi — Alternative Schema Validation

```typescript
import Joi from "joi";

const userSchema = Joi.object({
  email: Joi.string().email().required(),
  password: Joi.string().min(12).max(128).required(),
  age: Joi.number().integer().min(13).max(150),
  role: Joi.string().valid("user", "editor", "admin").default("user"),
});

const { error, value } = userSchema.validate(req.body, {
  abortEarly: false,   // Return all errors, not just first
  stripUnknown: true,   // Remove fields not in schema
});
```

### File Upload Validation

```typescript
import multer from "multer";
import { fileTypeFromBuffer } from "file-type";
import path from "node:path";

const ALLOWED_MIMES = new Set(["image/jpeg", "image/png", "image/webp"]);
const MAX_SIZE = 5 * 1024 * 1024; // 5MB

const upload = multer({
  limits: { fileSize: MAX_SIZE },
  storage: multer.memoryStorage(),
});

app.post("/api/upload", upload.single("avatar"), async (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: "No file uploaded" });
  }

  // 1. Check declared MIME type (unreliable — client can fake this)
  if (!ALLOWED_MIMES.has(req.file.mimetype)) {
    return res.status(400).json({ error: "Invalid file type" });
  }

  // 2. Check actual file content (magic bytes) — this is the real check
  const detected = await fileTypeFromBuffer(req.file.buffer);
  if (!detected || !ALLOWED_MIMES.has(detected.mime)) {
    return res.status(400).json({ error: "File content does not match allowed types" });
  }

  // 3. Sanitize filename — prevent path traversal
  const safeFilename = `${crypto.randomUUID()}${path.extname(req.file.originalname)}`;
  // NEVER use req.file.originalname directly in file paths
  // Attacker could send: originalname = "../../etc/passwd"

  await saveFile(safeFilename, req.file.buffer);
  res.json({ filename: safeFilename });
});
```

### Path Traversal Prevention

```typescript
import path from "node:path";
import fs from "node:fs/promises";

const SAFE_BASE_DIR = "/app/uploads";

// VULNERABLE: Direct path concatenation
app.get("/api/files/:filename", async (req, res) => {
  // Attacker sends: filename = "../../etc/passwd"
  const filePath = `${SAFE_BASE_DIR}/${req.params.filename}`;
  res.sendFile(filePath); // Serves /etc/passwd
});

// SECURE: Resolve and verify the path stays within the allowed directory
app.get("/api/files/:filename", async (req, res) => {
  const filename = req.params.filename;

  // Resolve the full path (handles .., symlinks, etc.)
  const resolvedPath = path.resolve(SAFE_BASE_DIR, filename);

  // Verify the resolved path is still within SAFE_BASE_DIR
  if (!resolvedPath.startsWith(SAFE_BASE_DIR + path.sep)) {
    return res.status(400).json({ error: "Invalid file path" });
  }

  try {
    await fs.access(resolvedPath);
    res.sendFile(resolvedPath);
  } catch {
    res.status(404).json({ error: "File not found" });
  }
});
```

---

## 4. HTTP Security Headers

### helmet Middleware

`helmet` is Express middleware that sets security-related HTTP headers. It is a collection of smaller middleware functions, each setting a specific header.

```typescript
import helmet from "helmet";

// Default configuration — sets all recommended headers
app.use(helmet());

// What helmet() sets by default:
// Content-Security-Policy: default-src 'self'; ...
// Cross-Origin-Opener-Policy: same-origin
// Cross-Origin-Resource-Policy: same-origin
// Origin-Agent-Cluster: ?1
// Referrer-Policy: no-referrer
// Strict-Transport-Security: max-age=15552000; includeSubDomains
// X-Content-Type-Options: nosniff
// X-DNS-Prefetch-Control: off
// X-Download-Options: noopen
// X-Frame-Options: SAMEORIGIN
// X-Permitted-Cross-Domain-Policies: none
// X-XSS-Protection: 0  (disabled — CSP is the modern replacement)
```

### Content-Security-Policy (CSP)

CSP is the **most important security header**. It tells the browser which sources of content are allowed, preventing XSS even if an attacker injects a `<script>` tag.

```typescript
app.use(
  helmet({
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],                            // Default: only same-origin
        scriptSrc: ["'self'", "'nonce-abc123'"],           // Scripts: same-origin + nonce
        styleSrc: ["'self'", "https://fonts.googleapis.com"],
        imgSrc: ["'self'", "data:", "https://cdn.example.com"],
        fontSrc: ["'self'", "https://fonts.gstatic.com"],
        connectSrc: ["'self'", "https://api.example.com"], // XHR/fetch destinations
        frameSrc: ["'none'"],                              // No iframes allowed
        objectSrc: ["'none'"],                             // No Flash/Java plugins
        baseUri: ["'self'"],                               // Restrict <base> tag
        formAction: ["'self'"],                            // Form submission destinations
        upgradeInsecureRequests: [],                       // Auto-upgrade HTTP to HTTPS
      },
    },
  })
);
```

**CSP with Nonces** — allows specific inline scripts without `'unsafe-inline'`:

```typescript
import crypto from "node:crypto";

app.use((req, res, next) => {
  // Generate a unique nonce for each request
  const nonce = crypto.randomBytes(16).toString("base64");
  res.locals.cspNonce = nonce;

  res.setHeader(
    "Content-Security-Policy",
    `default-src 'self'; script-src 'self' 'nonce-${nonce}'; style-src 'self' 'nonce-${nonce}'`
  );
  next();
});

// In your template:
// <script nonce="<%= cspNonce %>">console.log("This is allowed");</script>
// <script>console.log("This is BLOCKED by CSP");</script>
```

### Key Security Headers Explained

| Header | Value | Purpose |
|--------|-------|---------|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains; preload` | Forces HTTPS for 1 year. Browser refuses HTTP connections. `preload` submits to browser preload list. |
| `X-Content-Type-Options` | `nosniff` | Prevents MIME-type sniffing. Browser trusts declared `Content-Type` only. Stops `text/html` from being interpreted as JS. |
| `X-Frame-Options` | `DENY` or `SAMEORIGIN` | Prevents clickjacking by blocking the page from being embedded in iframes. Modern replacement: CSP `frame-ancestors`. |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Controls how much URL info is sent in the `Referer` header. Prevents leaking internal paths to external sites. |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=()` | Disables browser features the app does not use. Limits damage from XSS. |

### CORS Deep Dive

Cross-Origin Resource Sharing (CORS) controls which external origins can access your API. Without CORS headers, browsers block cross-origin requests.

**How CORS works:**

```
1. Browser sends request with Origin: https://app.example.com
2. Server responds with Access-Control-Allow-Origin: https://app.example.com
3. Browser allows the response (or blocks if header is missing/mismatched)
```

**Preflight requests** happen for "non-simple" requests (custom headers, PUT/DELETE methods, JSON content type):

```
1. Browser sends OPTIONS /api/data with:
   Origin: https://app.example.com
   Access-Control-Request-Method: DELETE
   Access-Control-Request-Headers: Authorization, Content-Type

2. Server responds with:
   Access-Control-Allow-Origin: https://app.example.com
   Access-Control-Allow-Methods: GET, POST, PUT, DELETE
   Access-Control-Allow-Headers: Authorization, Content-Type
   Access-Control-Max-Age: 86400  (cache preflight for 24 hours)

3. Browser sends the actual DELETE request
```

```typescript
import cors from "cors";

// VULNERABLE: Allow all origins
app.use(cors()); // Access-Control-Allow-Origin: * — any site can read responses

// SECURE: Whitelist specific origins
const allowedOrigins = new Set([
  "https://app.example.com",
  "https://admin.example.com",
]);

app.use(
  cors({
    origin: (origin, callback) => {
      // Allow requests with no origin (mobile apps, server-to-server)
      if (!origin || allowedOrigins.has(origin)) {
        callback(null, true);
      } else {
        callback(new Error("Not allowed by CORS"));
      }
    },
    methods: ["GET", "POST", "PUT", "DELETE"],
    allowedHeaders: ["Authorization", "Content-Type"],
    credentials: true,            // Allow cookies/auth headers
    maxAge: 86400,                // Cache preflight for 24 hours
    exposedHeaders: ["X-Total-Count"], // Custom headers the client can read
  })
);
```

**Critical CORS rule:** When `credentials: true`, you **cannot** use `Access-Control-Allow-Origin: *`. You must specify the exact origin. This is a browser security requirement.

---

## 5. Secrets Management

### The Secrets Hierarchy

From least secure to most secure:

| Method | Security Level | Best For |
|--------|---------------|----------|
| Hardcoded in source | Terrible | Never |
| `.env` files (dotenv) | Low | Local development only |
| CI/CD secrets (GitHub Actions) | Medium | Build-time secrets |
| Environment variables (container) | Medium | Container deployments |
| AWS Parameter Store | High | Cloud applications |
| AWS Secrets Manager | High | Secrets with rotation |
| HashiCorp Vault | Very High | Enterprise, multi-cloud |

### Environment Variables and dotenv

```typescript
// .env file — ONLY for local development
// DATABASE_URL=postgres://user:pass@localhost:5432/mydb
// JWT_SECRET=dev-secret-not-for-production
// STRIPE_SECRET_KEY=sk_test_xxx

import "dotenv/config"; // Loads .env into process.env

// Access secrets
const dbUrl = process.env.DATABASE_URL;
const jwtSecret = process.env.JWT_SECRET;

// ALWAYS validate that required secrets are present at startup
const REQUIRED_ENV_VARS = [
  "DATABASE_URL",
  "JWT_SECRET",
  "STRIPE_SECRET_KEY",
] as const;

function validateEnv(): void {
  const missing = REQUIRED_ENV_VARS.filter((key) => !process.env[key]);
  if (missing.length > 0) {
    console.error(`Missing required environment variables: ${missing.join(", ")}`);
    process.exit(1);
  }
}
validateEnv();
```

### Why .env is NOT for Production

1. **`.env` files are plain text on disk** — anyone with filesystem access reads all secrets.
2. **No access control** — every developer with repo access sees every secret.
3. **No audit trail** — no record of who accessed which secret.
4. **No rotation** — changing a secret means redeploying.
5. **Risk of committing to git** — `.env` in version control is a common breach vector.

### AWS Secrets Manager

```typescript
import {
  SecretsManagerClient,
  GetSecretValueCommand,
} from "@aws-sdk/client-secrets-manager";

const client = new SecretsManagerClient({ region: "us-east-1" });

// Cache secrets to avoid API calls on every request
const secretsCache = new Map<string, { value: string; expiresAt: number }>();
const CACHE_TTL = 5 * 60 * 1000; // 5 minutes

async function getSecret(secretId: string): Promise<string> {
  const cached = secretsCache.get(secretId);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.value;
  }

  const command = new GetSecretValueCommand({ SecretId: secretId });
  const response = await client.send(command);

  if (!response.SecretString) {
    throw new Error(`Secret ${secretId} has no string value`);
  }

  secretsCache.set(secretId, {
    value: response.SecretString,
    expiresAt: Date.now() + CACHE_TTL,
  });

  return response.SecretString;
}

// Usage at application startup:
async function initializeSecrets() {
  const dbSecret = JSON.parse(await getSecret("prod/database"));
  const dbUrl = `postgres://${dbSecret.username}:${dbSecret.password}@${dbSecret.host}:${dbSecret.port}/${dbSecret.dbname}`;

  const jwtSecret = await getSecret("prod/jwt-signing-key");

  return { dbUrl, jwtSecret };
}
```

### HashiCorp Vault

```typescript
import Vault from "node-vault";

const vault = Vault({
  endpoint: process.env.VAULT_ADDR || "https://vault.example.com",
  token: process.env.VAULT_TOKEN, // Or use AppRole authentication
});

// AppRole authentication (preferred for services)
async function authenticateVault(): Promise<string> {
  const result = await vault.approleLogin({
    role_id: process.env.VAULT_ROLE_ID!,
    secret_id: process.env.VAULT_SECRET_ID!,
  });
  return result.auth.client_token;
}

// Read secrets from Vault KV v2
async function getVaultSecret(
  path: string
): Promise<Record<string, string>> {
  const result = await vault.read(`secret/data/${path}`);
  return result.data.data;
}

// Dynamic database credentials — Vault creates short-lived DB users
async function getDatabaseCredentials(): Promise<{
  username: string;
  password: string;
}> {
  const result = await vault.read("database/creds/myapp-role");
  // Returns credentials that auto-expire (e.g., TTL of 1 hour)
  return {
    username: result.data.username,
    password: result.data.password,
  };
  // When the lease expires, Vault automatically revokes the DB user
}
```

### Kubernetes Secrets (and Their Limitations)

Kubernetes Secrets are **Base64-encoded, not encrypted** by default. Anyone with `kubectl` access to the namespace can read them.

Mitigations:
- **Enable encryption at rest** (`EncryptionConfiguration` in kube-apiserver)
- **Use RBAC** to restrict who can read secrets
- **External secret operators** — sync from Vault/AWS Secrets Manager into K8s Secrets
- **Sealed Secrets** (Bitnami) — encrypted secrets safe to store in git

```yaml
# kubernetes-secret.yaml — Base64 encoded, NOT secure on its own
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
type: Opaque
data:
  # base64 decode reveals the actual URL — this is NOT encryption
  DATABASE_URL: cG9zdGdyZXM6Ly91c2VyOnBhc3NAaG9zdDo1NDMyL2Ri
```

### Secret Detection Tools

| Tool | Type | Detects |
|------|------|---------|
| `gitleaks` | Pre-commit hook + CI | Secrets in git history and staged files |
| `trufflehog` | Git history scanner | High-entropy strings, known secret patterns |
| `detect-secrets` (Yelp) | Pre-commit hook | Secrets in code, maintains baseline |
| GitHub Secret Scanning | SaaS (built into GitHub) | Known secret patterns, alerts partner providers |

```yaml
# .pre-commit-config.yaml — Install gitleaks as a pre-commit hook
repos:
  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.18.0
    hooks:
      - id: gitleaks
```

### Secrets Rotation Strategy

```typescript
// Pattern: graceful secret rotation without downtime
// During rotation, BOTH old and new secrets are valid temporarily

interface RotatableSecret {
  current: string;
  previous?: string;
  rotatedAt: Date;
}

// JWT verification that accepts both current and previous signing keys
function verifyTokenWithRotation(
  token: string,
  secrets: RotatableSecret
): JwtPayload {
  try {
    return jwt.verify(token, secrets.current) as JwtPayload;
  } catch {
    if (secrets.previous) {
      // Token might have been signed with the old key before rotation
      return jwt.verify(token, secrets.previous) as JwtPayload;
    }
    throw new Error("Invalid token");
  }
}

// JWKS (JSON Web Key Set) endpoint — the standard approach for key rotation
// Auth server publishes public keys at /.well-known/jwks.json
// Resource servers fetch and cache these keys
// When a new key is rotated in, old key stays in JWKS until all tokens
// signed with it expire
```

---

## 6. Dependency Security

### npm audit

`npm audit` scans your dependency tree for known vulnerabilities in the npm advisory database.

```bash
# Run audit
npm audit

# Only production dependencies (skip devDependencies)
npm audit --omit=dev

# JSON output for CI/CD parsing
npm audit --json

# Attempt to auto-fix by upgrading to non-vulnerable versions
npm audit fix

# Force fix (may include major version bumps — can break things)
npm audit fix --force
```

**Limitations of `npm audit`:**
- Only checks the npm advisory database — misses zero-days and unreported vulns
- High false-positive rate — many advisories are not exploitable in your context
- Does not detect malicious packages (intentional backdoors)
- Does not analyze how you use the dependency (reachability)

### Supply Chain Attacks

| Attack Type | Description | Example |
|-------------|-------------|---------|
| **Typosquatting** | Publish `expres` hoping developers mistype `express` | `crossenv` (stole env vars, mimicking `cross-env`) |
| **Dependency Confusion** | Public package with same name as private internal package | Researcher Alex Birsan demonstrated against Apple, Microsoft |
| **Malicious Maintainer** | Legitimate package owner goes rogue or account compromised | `event-stream` — new maintainer added crypto-stealing code |
| **Install Script Attacks** | `postinstall` script runs arbitrary code during `npm install` | Miners, keyloggers, reverse shells |
| **Protestware** | Maintainer adds destructive code to protest | `node-ipc` — deleted files on Russian IP ranges |

### Prevention Strategies

```bash
# 1. Use lockfile integrity checking
# npm and yarn verify checksums in lock files automatically
# Always commit package-lock.json / yarn.lock / pnpm-lock.yaml

# 2. Disable install scripts for untrusted packages
npm install suspicious-package --ignore-scripts

# 3. Use pnpm for stricter node_modules structure
# pnpm uses a content-addressable store + symlinks
# Packages cannot access dependencies they don't declare (prevents phantom dependencies)

# 4. Pin exact versions in production
npm config set save-exact true
# Saves "express": "4.18.2" instead of "express": "^4.18.2"

# 5. Use npm provenance to verify package origin
npm audit signatures  # Verify that packages have valid registry signatures
```

### Package Manager Security Comparison

| Feature | npm | yarn (v3/berry) | pnpm |
|---------|-----|-----------------|------|
| Lockfile integrity | SHA-512 hashes | SHA-512 hashes | SHA-512 hashes |
| Phantom dependency prevention | No (flat hoisting) | PnP mode (no hoisting) | Yes (strict symlink structure) |
| Install scripts | Run by default | Run by default | Run by default |
| Dependency confusion | Scoped packages help | Scoped packages help | Scoped packages help |
| Overrides/resolutions | `overrides` in package.json | `resolutions` in package.json | `overrides` or `pnpm.overrides` |
| Audit | `npm audit` | `yarn npm audit` | `pnpm audit` |
| Patching | `npm-force-resolutions` | `yarn patch` (built-in) | `pnpm patch` (built-in) |

### Snyk, Socket.dev, and Dependabot

```yaml
# .github/dependabot.yml — Automated dependency updates
version: 2
updates:
  - package-ecosystem: "npm"
    directory: "/"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 10
    reviewers:
      - "security-team"
    labels:
      - "dependencies"
      - "security"
    # Group minor/patch updates to reduce PR noise
    groups:
      production-dependencies:
        dependency-type: "production"
        update-types:
          - "minor"
          - "patch"
```

**Socket.dev** goes beyond vulnerability databases — it analyzes package **behavior**:
- Detects install scripts that access the network
- Flags packages that read environment variables
- Identifies obfuscated code
- Monitors for maintainer changes and sudden activity spikes

---

## 7. Cryptography Essentials

### node:crypto Module Overview

Node.js ships with a built-in `node:crypto` module backed by OpenSSL. Use it for all cryptographic operations — never implement your own crypto.

### Password Hashing

**Never store passwords in plain text.** Use a slow, memory-hard hashing algorithm with a unique salt per password.

| Algorithm | CPU Cost | Memory Cost | Parallelism Resistance | Recommendation |
|-----------|----------|-------------|----------------------|----------------|
| bcrypt | Configurable (rounds) | Fixed (~4KB) | Moderate | Good default, widely supported |
| scrypt | Configurable (N) | Configurable (r*p*128) | Strong | Built into Node.js `crypto` |
| Argon2id | Configurable (t) | Configurable (m) | Strong | Best choice (OWASP recommendation), needs `argon2` package |

```typescript
// --- bcrypt ---
import bcrypt from "bcrypt";

const SALT_ROUNDS = 12; // 2^12 iterations — takes ~250ms on modern hardware

async function hashPassword(password: string): Promise<string> {
  // bcrypt generates a unique salt and embeds it in the hash
  return bcrypt.hash(password, SALT_ROUNDS);
  // Result: "$2b$12$LJ3m4ys3Lg9Tqx.gGHbMnueMQmU5jQq6Xw1B6S5ByO5C3.FQy8O6"
  //          ^alg ^cost ^----salt (22 chars)----^^----hash (31 chars)----^
}

async function verifyPassword(
  password: string,
  hash: string
): Promise<boolean> {
  return bcrypt.compare(password, hash);
}

// --- scrypt (built into Node.js) ---
import crypto from "node:crypto";
import { promisify } from "node:util";

const scryptAsync = promisify(crypto.scrypt);

async function hashPasswordScrypt(password: string): Promise<string> {
  const salt = crypto.randomBytes(32);
  const derivedKey = (await scryptAsync(password, salt, 64, {
    N: 16384,  // CPU/memory cost (must be power of 2)
    r: 8,      // Block size
    p: 1,      // Parallelization
  })) as Buffer;
  return `${salt.toString("hex")}:${derivedKey.toString("hex")}`;
}

async function verifyPasswordScrypt(
  password: string,
  stored: string
): Promise<boolean> {
  const [saltHex, hashHex] = stored.split(":");
  const salt = Buffer.from(saltHex, "hex");
  const storedHash = Buffer.from(hashHex, "hex");
  const derivedKey = (await scryptAsync(password, salt, 64, {
    N: 16384,
    r: 8,
    p: 1,
  })) as Buffer;
  // Use timingSafeEqual to prevent timing attacks
  return crypto.timingSafeEqual(derivedKey, storedHash);
}

// --- Argon2id (OWASP recommended) ---
import argon2 from "argon2";

async function hashPasswordArgon2(password: string): Promise<string> {
  return argon2.hash(password, {
    type: argon2.argon2id, // Hybrid: resistant to side-channel AND GPU attacks
    memoryCost: 65536,     // 64 MB
    timeCost: 3,           // 3 iterations
    parallelism: 4,        // 4 threads
  });
}

async function verifyPasswordArgon2(
  password: string,
  hash: string
): Promise<boolean> {
  return argon2.verify(hash, password);
}
```

### Symmetric Encryption (AES-256-GCM)

AES-256-GCM provides both **confidentiality** (encryption) and **integrity** (authentication tag). Always use GCM mode over CBC.

```typescript
import crypto from "node:crypto";

const ALGORITHM = "aes-256-gcm";
const KEY_LENGTH = 32; // 256 bits
const IV_LENGTH = 12;  // 96 bits — recommended for GCM
const TAG_LENGTH = 16; // 128-bit auth tag

interface EncryptedData {
  iv: string;        // Initialization vector (unique per encryption)
  encrypted: string; // Ciphertext
  tag: string;       // Authentication tag (integrity verification)
}

function encrypt(plaintext: string, key: Buffer): EncryptedData {
  // CRITICAL: Generate a unique IV for every encryption operation
  // Reusing an IV with the same key completely breaks GCM security
  const iv = crypto.randomBytes(IV_LENGTH);

  const cipher = crypto.createCipheriv(ALGORITHM, key, iv, {
    authTagLength: TAG_LENGTH,
  });

  let encrypted = cipher.update(plaintext, "utf8", "hex");
  encrypted += cipher.final("hex");

  return {
    iv: iv.toString("hex"),
    encrypted,
    tag: cipher.getAuthTag().toString("hex"),
  };
}

function decrypt(data: EncryptedData, key: Buffer): string {
  const decipher = crypto.createDecipheriv(
    ALGORITHM,
    key,
    Buffer.from(data.iv, "hex"),
    { authTagLength: TAG_LENGTH }
  );

  decipher.setAuthTag(Buffer.from(data.tag, "hex"));

  let decrypted = decipher.update(data.encrypted, "hex", "utf8");
  decrypted += decipher.final("utf8");

  return decrypted;
}

// Key generation — use a cryptographically secure random source
const encryptionKey = crypto.randomBytes(KEY_LENGTH);

// Usage:
const encrypted = encrypt("sensitive data", encryptionKey);
const decrypted = decrypt(encrypted, encryptionKey);
```

### HMAC for Message Integrity

HMAC (Hash-based Message Authentication Code) verifies that a message has not been tampered with and was sent by someone who knows the shared secret.

```typescript
import crypto from "node:crypto";

// Webhook signature verification (e.g., Stripe, GitHub)
function createHmacSignature(payload: string, secret: string): string {
  return crypto
    .createHmac("sha256", secret)
    .update(payload, "utf8")
    .digest("hex");
}

function verifyWebhookSignature(
  payload: string,
  signature: string,
  secret: string
): boolean {
  const expected = createHmacSignature(payload, secret);

  // CRITICAL: Use timingSafeEqual to prevent timing attacks
  // String comparison (===) leaks information about which bytes match
  const a = Buffer.from(signature, "hex");
  const b = Buffer.from(expected, "hex");

  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

// Express middleware for Stripe webhook verification
app.post(
  "/webhooks/stripe",
  express.raw({ type: "application/json" }), // Raw body needed for signature
  (req, res) => {
    const signature = req.headers["stripe-signature"] as string;
    const payload = req.body.toString();

    if (
      !verifyWebhookSignature(
        payload,
        signature,
        process.env.STRIPE_WEBHOOK_SECRET!
      )
    ) {
      return res.status(400).json({ error: "Invalid signature" });
    }

    // Process the webhook event safely
    const event = JSON.parse(payload);
    handleStripeEvent(event);
    res.json({ received: true });
  }
);
```

### Secure Random Generation

```typescript
import crypto from "node:crypto";

// Cryptographically secure random bytes
const randomBytes = crypto.randomBytes(32); // 256-bit random value

// UUID v4 (built into Node.js 19+)
const uuid = crypto.randomUUID(); // "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"

// Random token for email verification, password reset, etc.
function generateSecureToken(length: number = 32): string {
  return crypto.randomBytes(length).toString("base64url");
  // URL-safe, no padding, 43 characters for 32 bytes
}

// NEVER use Math.random() for security-sensitive values
// Math.random() uses xorshift128+ — fast but predictable
// An attacker who observes a few outputs can predict future values
```

### Key Derivation Functions

Derive encryption keys from passwords or master keys:

```typescript
import crypto from "node:crypto";

// PBKDF2 — derive an encryption key from a user's password
async function deriveKey(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    crypto.pbkdf2(
      password,
      salt,
      600_000,  // OWASP recommends >= 600,000 iterations for PBKDF2-SHA256
      32,       // Key length (256 bits)
      "sha256",
      (err, derivedKey) => {
        if (err) reject(err);
        else resolve(derivedKey);
      }
    );
  });
}

// HKDF — derive multiple keys from a single master key
function deriveSubKey(
  masterKey: Buffer,
  info: string,
  length: number = 32
): Buffer {
  return crypto.hkdfSync("sha256", masterKey, Buffer.alloc(0), info, length);
  // info parameter creates domain separation:
  // deriveSubKey(masterKey, "encryption") !== deriveSubKey(masterKey, "signing")
}
```

---

## 8. Rate Limiting & DDoS Protection

### Rate Limiting Strategies

| Strategy | How It Works | Pros | Cons |
|----------|-------------|------|------|
| **Fixed Window** | Count requests in fixed time intervals (e.g., 100/min) | Simple to implement | Burst at window boundaries (up to 2x limit) |
| **Sliding Window Log** | Track timestamp of each request, count within rolling window | Accurate | High memory (stores every timestamp) |
| **Sliding Window Counter** | Weighted count between current and previous window | Accurate, low memory | Slightly complex |
| **Token Bucket** | Tokens added at fixed rate, each request consumes one | Allows controlled bursts | Slightly complex |
| **Leaky Bucket** | Requests queued and processed at fixed rate | Smooth output rate | Queuing adds latency |

### Fixed Window vs Sliding Window

```
Fixed Window (100 req/min):
|-------- Window 1 --------|-------- Window 2 --------|
|    80 requests            | 80 requests              |
                        ^
                        At boundary: 80 + 80 = 160 requests in 1 minute
                        (exceeds the 100/min intent)

Sliding Window Counter:
Current window: 80 requests, 75% through the window
Previous window: 80 requests
Weighted count: 80 * 0.25 (prev remaining) + 80 * 1.0 (current) = 100
More accurate representation of the actual rate.
```

### express-rate-limit

```typescript
import rateLimit from "express-rate-limit";

// General API rate limit
const apiLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 100,                  // 100 requests per window per IP
  standardHeaders: true,     // Return RateLimit-* headers (draft-6)
  legacyHeaders: false,      // Disable X-RateLimit-* headers
  message: { error: "Too many requests, please try again later." },
  keyGenerator: (req) => {
    // Use X-Forwarded-For behind a reverse proxy
    return req.ip || req.socket.remoteAddress || "unknown";
  },
  skip: (req) => {
    // Skip rate limiting for health checks
    return req.path === "/health";
  },
});

// Strict limiter for authentication endpoints
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 5,                    // Only 5 login attempts per 15 minutes
  message: { error: "Too many login attempts. Try again in 15 minutes." },
  skipSuccessfulRequests: true, // Only count failed attempts
});

// Apply limiters
app.use("/api/", apiLimiter);
app.use("/api/auth/login", authLimiter);
app.use("/api/auth/register", authLimiter);
```

### Redis-Backed Distributed Rate Limiting

In-memory rate limiting only works for single-instance servers. For distributed systems, use Redis as a shared counter store.

```typescript
import rateLimit from "express-rate-limit";
import RedisStore from "rate-limit-redis";
import Redis from "ioredis";

const redis = new Redis({
  host: process.env.REDIS_HOST,
  port: 6379,
  enableOfflineQueue: false, // Fail fast if Redis is down
});

const distributedLimiter = rateLimit({
  windowMs: 60 * 1000, // 1 minute
  max: 60,              // 60 requests per minute per key
  standardHeaders: true,
  store: new RedisStore({
    sendCommand: (...args: string[]) => redis.call(...args),
    prefix: "rl:", // Redis key prefix
  }),
});

// Token bucket implementation with Redis (Lua script for atomicity)
const TOKEN_BUCKET_SCRIPT = `
  local key = KEYS[1]
  local max_tokens = tonumber(ARGV[1])
  local refill_rate = tonumber(ARGV[2])  -- tokens per second
  local now = tonumber(ARGV[3])

  local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
  local tokens = tonumber(bucket[1]) or max_tokens
  local last_refill = tonumber(bucket[2]) or now

  -- Refill tokens based on elapsed time
  local elapsed = now - last_refill
  tokens = math.min(max_tokens, tokens + elapsed * refill_rate)

  if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, math.ceil(max_tokens / refill_rate) + 1)
    return 1  -- allowed
  else
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, math.ceil(max_tokens / refill_rate) + 1)
    return 0  -- rejected
  end
`;

async function tokenBucketCheck(
  key: string,
  maxTokens: number,
  refillRate: number
): Promise<boolean> {
  const now = Date.now() / 1000;
  const result = await redis.eval(
    TOKEN_BUCKET_SCRIPT,
    1,
    key,
    maxTokens.toString(),
    refillRate.toString(),
    now.toString()
  );
  return result === 1;
}
```

### NestJS Throttler

```typescript
import {
  ThrottlerModule,
  ThrottlerGuard,
  Throttle,
  SkipThrottle,
} from "@nestjs/throttler";

// Module configuration
@Module({
  imports: [
    ThrottlerModule.forRoot({
      throttlers: [
        { name: "short", ttl: 1000, limit: 3 },    // 3 requests per second
        { name: "medium", ttl: 10000, limit: 20 },  // 20 requests per 10 seconds
        { name: "long", ttl: 60000, limit: 100 },   // 100 requests per minute
      ],
    }),
  ],
  providers: [
    {
      provide: "APP_GUARD",
      useClass: ThrottlerGuard, // Apply globally
    },
  ],
})
export class AppModule {}

// Controller-level overrides
@Controller("auth")
export class AuthController {
  @Throttle({ short: { limit: 1, ttl: 1000 }, long: { limit: 5, ttl: 60000 } })
  @Post("login")
  async login() {
    // Stricter limits for login endpoint
  }

  @SkipThrottle()
  @Get("health")
  health() {
    // No rate limiting for health checks
  }
}
```

### DDoS Mitigation

| Layer | Tool | Protection |
|-------|------|-----------|
| **Edge/CDN** | Cloudflare, AWS CloudFront | Absorb volumetric attacks, geographic filtering, bot detection |
| **Network** | AWS Shield (Standard/Advanced) | Layer 3/4 protection (SYN floods, UDP reflection) |
| **Application** | Rate limiting, WAF rules | Layer 7 protection (HTTP floods, slowloris) |
| **Infrastructure** | Auto-scaling, load balancing | Absorb traffic spikes |

### Brute Force Protection

```typescript
import Redis from "ioredis";

const redis = new Redis();

interface BruteForceResult {
  allowed: boolean;
  remainingAttempts: number;
  lockoutExpiresAt?: Date;
}

const MAX_ATTEMPTS = 5;
const LOCKOUT_DURATION = 15 * 60; // 15 minutes in seconds
const ATTEMPT_WINDOW = 15 * 60;   // 15 minutes in seconds

async function checkBruteForce(
  identifier: string
): Promise<BruteForceResult> {
  const lockoutKey = `lockout:${identifier}`;
  const attemptsKey = `attempts:${identifier}`;

  // Check if currently locked out
  const lockoutTTL = await redis.ttl(lockoutKey);
  if (lockoutTTL > 0) {
    return {
      allowed: false,
      remainingAttempts: 0,
      lockoutExpiresAt: new Date(Date.now() + lockoutTTL * 1000),
    };
  }

  const attempts = parseInt((await redis.get(attemptsKey)) || "0", 10);
  return {
    allowed: attempts < MAX_ATTEMPTS,
    remainingAttempts: Math.max(0, MAX_ATTEMPTS - attempts),
  };
}

async function recordFailedAttempt(
  identifier: string
): Promise<BruteForceResult> {
  const attemptsKey = `attempts:${identifier}`;
  const lockoutKey = `lockout:${identifier}`;

  const attempts = await redis.incr(attemptsKey);
  await redis.expire(attemptsKey, ATTEMPT_WINDOW);

  if (attempts >= MAX_ATTEMPTS) {
    // Lock the account
    await redis.set(lockoutKey, "1", "EX", LOCKOUT_DURATION);
    await redis.del(attemptsKey);

    return {
      allowed: false,
      remainingAttempts: 0,
      lockoutExpiresAt: new Date(Date.now() + LOCKOUT_DURATION * 1000),
    };
  }

  return {
    allowed: true,
    remainingAttempts: MAX_ATTEMPTS - attempts,
  };
}

async function resetBruteForce(identifier: string): Promise<void> {
  await redis.del(`attempts:${identifier}`, `lockout:${identifier}`);
}

// Login endpoint with brute force protection
app.post("/api/auth/login", async (req, res) => {
  const { email, password } = req.body;

  // Check by both IP and email to prevent distributed AND targeted attacks
  const ipCheck = await checkBruteForce(`ip:${req.ip}`);
  const emailCheck = await checkBruteForce(`email:${email}`);

  if (!ipCheck.allowed || !emailCheck.allowed) {
    const retryAfter = Math.max(
      ipCheck.lockoutExpiresAt?.getTime() || 0,
      emailCheck.lockoutExpiresAt?.getTime() || 0
    );
    res.set("Retry-After", String(Math.ceil((retryAfter - Date.now()) / 1000)));
    return res.status(429).json({
      error: "Too many failed attempts. Please try again later.",
    });
  }

  const user = await findUserByEmail(email);
  if (!user || !(await verifyPassword(password, user.passwordHash))) {
    // Record failed attempt for BOTH ip and email
    await Promise.all([
      recordFailedAttempt(`ip:${req.ip}`),
      recordFailedAttempt(`email:${email}`),
    ]);
    // IMPORTANT: Same error message for "user not found" and "wrong password"
    // to prevent user enumeration
    return res.status(401).json({ error: "Invalid email or password" });
  }

  // Successful login — reset counters
  await Promise.all([
    resetBruteForce(`ip:${req.ip}`),
    resetBruteForce(`email:${email}`),
  ]);

  const token = createAccessToken(user);
  res.json({ token });
});
```

### Account Lockout — Progressive Delays

```typescript
// Progressive delay strategy — avoids permanent lockouts while deterring brute force
const DELAY_SCHEDULE: Record<number, number> = {
  1: 0,       // First attempt: no delay
  2: 0,       // Second attempt: no delay
  3: 1000,    // Third attempt: 1 second delay
  4: 2000,    // Fourth: 2 seconds
  5: 5000,    // Fifth: 5 seconds
  6: 10000,   // Sixth: 10 seconds
  7: 30000,   // Seventh: 30 seconds
  8: 60000,   // Eighth: 1 minute
  9: 300000,  // Ninth: 5 minutes
  10: 900000, // Tenth+: 15 minutes
};

async function getProgressiveDelay(identifier: string): Promise<number> {
  const attempts = parseInt(
    (await redis.get(`attempts:${identifier}`)) || "0",
    10
  );
  const clampedAttempts = Math.min(attempts + 1, 10);
  return DELAY_SCHEDULE[clampedAttempts] || 900000;
}
```

---

## Quick Reference — Security Checklist for Node.js Applications

| Category | Must-Do | Common Mistake |
|----------|---------|---------------|
| **Authentication** | Use bcrypt/argon2 for passwords, RS256 JWTs, short-lived tokens | Storing passwords in plain text, HS256 with weak secrets |
| **Authorization** | Check ownership on every resource access | Only checking authentication, not authorization (IDOR) |
| **Input Validation** | Validate all input with Zod/Joi at API boundary | Trusting client-side validation |
| **SQL Injection** | Parameterized queries, ORMs | String concatenation in queries |
| **XSS** | CSP headers, output encoding, DOMPurify | `innerHTML` with user input |
| **CSRF** | SameSite cookies, CSRF tokens for cookie auth | Disabling CSRF "because we use JWT" (while also using cookies) |
| **Headers** | Use `helmet`, configure CSP, enable HSTS | Missing security headers entirely |
| **CORS** | Whitelist specific origins | `Access-Control-Allow-Origin: *` with credentials |
| **Secrets** | AWS Secrets Manager / Vault, never hardcode | Secrets in `.env` committed to git |
| **Dependencies** | `npm audit`, Snyk, lock file integrity | Ignoring `npm audit` warnings |
| **Crypto** | `node:crypto` for all cryptographic operations | `Math.random()` for tokens, MD5/SHA1 for passwords |
| **Rate Limiting** | Redis-backed distributed rate limiting | In-memory rate limiting on load-balanced servers |
| **Errors** | Generic error messages in production | Stack traces exposed to clients |
| **Logging** | Log auth events, never log secrets/passwords | Logging full request bodies including credentials |
