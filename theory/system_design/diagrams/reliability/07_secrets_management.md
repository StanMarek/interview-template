# Secrets Management Service -- Architecture Design

## Requirements

### Functional
- Store, retrieve, and manage secrets (API keys, database passwords, TLS certificates, tokens)
- Access control: per-secret, per-identity, per-environment (dev/staging/prod)
- Secret versioning with rollback capability
- Automatic secret rotation with zero-downtime deployment
- Dynamic secret generation (e.g., generate a new DB credential per request with TTL)
- Encryption-as-a-service (encrypt/decrypt data without exposing the key)
- Lease-based access: secrets are "checked out" with a TTL and must be renewed
- Audit trail: every secret access is logged (who, what, when, from where)

### Non-Functional
- **Confidentiality:** Secrets encrypted at rest AND in transit. Even infrastructure admins cannot read plaintext secrets.
- **Availability:** 99.99% -- services depend on secrets for startup; if the secret service is down, nothing can start
- **Consistency:** Strong consistency for secret writes (a rotation must be immediately visible)
- **Latency:** p99 < 10ms for secret retrieval
- **Durability:** Zero secret loss -- encrypted backups in separate region

## Scale Estimates
- 10,000 secret reads/second
- 100 secret writes/second (rotations, new secrets)
- 1M total secrets across all environments
- 500 distinct services consuming secrets
- Secret size: average 1 KB, max 64 KB

## Architecture Decisions

### Decision 1: Envelope Encryption with Key Hierarchy

Secrets are encrypted using a three-level key hierarchy: (1) Root Key stored in HSM (never leaves hardware), (2) Key Encryption Keys (KEKs) / Master Key protected by the Root Key, (3) Data Encryption Keys (DEKs) -- one per secret, encrypted by the KEK.

**Why envelope encryption:** If we used a single master key for all secrets, compromising that key compromises everything. With envelope encryption, each secret has its own DEK. Compromising a DEK exposes only one secret. The KEK encrypts DEKs, and the Root Key encrypts KEKs. The Root Key lives in a Hardware Security Module (HSM) and never exists in software.

**Why not just encrypt with the master key directly:** Key rotation. If all secrets are encrypted with one key, rotating that key requires re-encrypting every secret. With envelope encryption, rotating the KEK only requires re-encrypting the DEKs (which are small), not re-encrypting all the secret data.

**Trade-off:** Three-level encryption adds complexity and a small amount of latency (two decryption operations per secret read: decrypt DEK with KEK, then decrypt secret with DEK). The HSM adds cost (~$5K/month in cloud). But for a secrets management service, this is the industry-standard approach because the cost of a secret breach is catastrophic.

### Decision 2: Shamir's Secret Sharing for Unsealing

The master key is split using Shamir's Secret Sharing into N shares, requiring K shares to reconstruct (e.g., 5 shares, 3 required). On startup, the secrets service is "sealed" -- it cannot decrypt anything. K share holders must provide their shares to "unseal" the service.

**Why Shamir's:** This prevents any single person (including the CTO or the on-call engineer) from accessing secrets unilaterally. It requires a quorum of trusted individuals to unlock the system. This is a critical security property for compliance (SOC 2, PCI DSS, HIPAA).

**Trade-off:** Unsealing requires human intervention on every service restart. This conflicts with automated deployment. Mitigation: auto-unseal using a cloud HSM (AWS CloudHSM, GCP Cloud KMS). The HSM holds the master key, and the secrets service authenticates to the HSM to obtain it. This trades human ceremony for trust in the cloud provider's HSM security.

### Decision 3: Dynamic Secrets with Leases

Instead of storing long-lived database credentials, the secrets service generates a unique credential per consumer with a limited TTL (e.g., 1 hour). When the lease expires, the credential is revoked at the database.

**Why dynamic secrets:** Long-lived credentials are the most common source of security breaches. They get committed to Git, shared in Slack, or forgotten in config files. Dynamic secrets with short TTLs limit the blast radius: even if a credential leaks, it expires in an hour. And each credential is unique to its consumer, so audit trails can identify exactly which service's credential was compromised.

**Trade-off:** Dynamic secrets require the secrets service to manage credentials at the target system (e.g., CREATE USER on PostgreSQL). This creates a coupling between the secrets service and every target system. It also means the secrets service must be available for services to get credentials -- there's no "cache the password and use it forever" option.

### Decision 4: mTLS for All Consumer Communication

Every consumer authenticates to the secrets service using mutual TLS (mTLS). The service verifies the consumer's certificate against the internal CA. The certificate's Subject Alternative Name (SAN) determines which secrets the consumer can access.

**Why mTLS over API keys:** Using API keys to authenticate to a secrets management service creates a chicken-and-egg problem -- where do you store the API key for the secrets service? mTLS with certificates provisioned via a service mesh (like Istio) or injected by the orchestrator (Kubernetes) solves this: the identity is cryptographically bound to the workload, not a shared secret.

## Consistency Model

**Strong consistency for secret writes.** When a secret is rotated, all subsequent reads must return the new version. We achieve this with Raft-based consensus in the storage backend (Consul or etcd). A write is not acknowledged until committed by a majority.

**Why strong consistency is non-negotiable:** If a secret is rotated due to a compromise, stale reads returning the old (compromised) secret would defeat the purpose of the rotation. Strong consistency ensures that once a rotation is committed, no consumer can read the old version.

**Read-after-write guarantee with caching.** The sidecar agent caches secrets locally (in memory, never on disk) with a configurable TTL (default: 5 minutes). This reduces load on the secrets service but means a rotation takes up to TTL seconds to propagate to all consumers. For emergency rotations, the secrets service pushes invalidation events to all sidecar agents.

## Failure Modes

### Secrets service unavailable
Services that have cached secrets (via sidecar agent) continue to operate with their cached values. Services that need to start up cannot obtain secrets and fail to start. This is the most critical failure mode. Mitigation: Raft-based HA cluster with 5 nodes across 3 AZs. The system tolerates 2 node failures.

### HSM failure
If the HSM is unavailable, the secrets service cannot unseal or perform root key operations. For auto-unseal, use multiple HSMs across AZs. For manual unseal, the shares are geographically distributed among key holders.

### Master key compromise
Emergency procedure: (1) rotate the master key using the HSM, (2) re-encrypt all KEKs with the new master key, (3) distribute new Shamir shares, (4) invalidate old shares. The secrets themselves don't need re-encryption (they're protected by DEKs, which are protected by KEKs, which are now protected by the new master key).

### Secret leakage (credential in log file, committed to Git)
Dynamic secrets with short TTLs limit exposure. For static secrets, emergency rotation is triggered via the Admin API. The rotation engine updates the secret at the target system and at the secrets store atomically. All sidecar agents are notified to refresh.

### Storage corruption
The Raft-based storage is replicated across nodes. Encrypted backups are taken every hour to a separate region. Recovery from backup is straightforward because all secret data is encrypted -- the backup itself is not a security risk.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **Application Service** | Consumes secrets via SDK or sidecar agent |
| **CI/CD Pipeline** | Retrieves deployment secrets (credentials, certificates) |
| **Sidecar Agent** | In-pod agent that caches secrets, handles auto-refresh and lease renewal |
| **Admin User** | Manages secrets, policies, and rotations via UI/CLI |
| **Secrets API** | Authenticated entry point for all secret operations |
| **AuthZ Engine** | Policy-based access control (e.g., "service X can read secret Y in prod") |
| **Lease Manager** | Tracks TTLs, triggers revocation on expiry, handles renewals |
| **Dynamic Secret Generator** | Creates per-request credentials at target systems |
| **Rotation Engine** | Automates periodic secret rotation at configured intervals |
| **Master Key (Shamir)** | Split key requiring quorum to reconstruct, protects KEKs |
| **Encryption Engine** | AES-256-GCM encryption/decryption of secret data |
| **Key Derivation** | Manages per-secret DEKs, handles key rotation |
| **HSM** | Hardware device that stores and protects the root key |
| **Transit Engine** | Encryption-as-a-service: encrypt/decrypt data without exposing keys |
| **Encrypted Store** | Raft-replicated storage for encrypted secrets |
| **Version History** | Tracks all versions of each secret for rollback |
| **Audit Log** | Immutable log of every secret access and modification |
| **Monitoring & Anomaly Detection** | Detects unusual access patterns (credential stuffing, bulk reads) |
| **Encrypted Backup** | Cross-region encrypted snapshots for disaster recovery |

## Key Trade-offs

### Security vs. Availability
Shamir's Secret Sharing and HSM requirements make the system harder to restart automatically. Auto-unseal with cloud HSM trades some security (trust in cloud provider) for operational simplicity.

### Dynamic vs. Static Secrets
Dynamic secrets are more secure but require the secrets service to be available for every credential request. Static secrets can be cached longer but have a larger blast radius on leakage.

### Caching vs. Freshness
Sidecar caching reduces load and provides resilience during outages, but introduces a staleness window during secret rotations. The invalidation mechanism (push-based) mitigates this for emergency rotations.

## What Fails First

**The lease renewal system becomes the bottleneck.** With dynamic secrets (short TTLs) and 500 services, each service needs to renew its lease every hour. During mass deployments (rolling restart of 500 services), all services request new credentials simultaneously. The dynamic secret generator must create 500 new database credentials in a short window, which hammers the target database with CREATE USER statements.

**Mitigation:** Jitter the lease renewal times. Instead of all leases expiring at the same time, add random jitter (0-5 minutes). Pre-generate a pool of dynamic credentials before they're needed. Rate-limit credential creation at the target system.

## v1 vs v2

### v1 (Ship first)
- Static secrets only (no dynamic generation)
- Manual secret rotation via Admin API
- AES-256-GCM encryption with software-managed master key
- Simple RBAC access control (admin/reader roles)
- Secret versioning with manual rollback
- Audit logging to file
- Single-region HA cluster

### v2 (Scale and harden)
- Dynamic secret generation (database credentials, cloud tokens)
- Automatic rotation on configurable schedules
- HSM-backed root key with Shamir's Secret Sharing
- Policy-based access control (service identity, environment, namespace)
- Lease management with automatic revocation
- Sidecar agent with push-based invalidation
- Encryption-as-a-service (transit engine)
- mTLS authentication with service mesh integration
- Cross-region encrypted backups
- Anomaly detection (unusual access patterns trigger alerts)
- Integration with cloud KMS for auto-unseal
- Certificate management (PKI-as-a-service)
