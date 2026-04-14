# Distributed Configuration Service -- Architecture Design

## Requirements

### Functional
- Store and retrieve key-value configuration for all microservices, organized by namespace (service name + environment)
- Dynamic configuration updates without service restart or redeployment
- Configuration versioning: every change creates a new version with rollback capability
- Environment-specific configs: dev, staging, production with inheritance (dev inherits from default, overrides selectively)
- Encrypted storage for secrets (DB passwords, API keys) with access control
- Schema validation: prevent invalid config from being written (e.g., timeout must be positive integer)
- Change notification: services receive updates within seconds of a config change

### Non-Functional
- **Read latency:** < 1ms (in-process local cache, never a network call on the hot path)
- **Write propagation:** < 10 seconds from write to all consumers seeing the new value
- **Availability:** 99.99% -- services must always be able to read config. If the config service is down, cached values work
- **Strong consistency for writes:** A write that succeeds is visible to all subsequent reads (linearizable)
- **Durability:** Config data must survive node failures. Zero data loss

## Scale Estimates
- **Services consuming config:** 500 microservice instances
- **Total config keys:** 50K across all services
- **Config data size:** 50K keys * avg 500 bytes = ~25 MB total (trivially small)
- **Config reads:** Effectively zero network reads in steady state (served from local cache)
- **Config writes:** ~100/day (config changes are infrequent by nature)
- **Watch connections:** 500 persistent connections (one per service instance)

## Architecture Decisions

### etcd as the Config Store (Not PostgreSQL, Not Redis)
etcd is purpose-built for configuration storage because:
- **Raft consensus:** Writes are linearizable. When a config write succeeds, it's replicated to a majority of nodes. No "I wrote the config but my service didn't see it" scenarios
- **Watch API:** Built-in long-poll/streaming watch on key prefixes. No need to build a separate change notification system
- **Hierarchical key space:** Keys like `/config/user-service/production/db.host` naturally organize by service and environment
- **Small data, high consistency:** etcd is optimized for small amounts of critical data (exactly what config is)

**Why not PostgreSQL?** Postgres could work, but you'd need to build the watch/notification layer yourself (LISTEN/NOTIFY has limitations at scale). etcd provides this out of the box.

**Why not Redis?** Redis is not strongly consistent. In a failover, recent writes can be lost. Losing a config update (especially one that was confirmed successful) is unacceptable.

**Why not ZooKeeper?** etcd is the modern alternative with a simpler API, better performance, and first-class Kubernetes integration. ZooKeeper has known operational complexity issues.

### Client SDK with Local Cache and Watch
The config client SDK is the critical design element. Services NEVER make a network call to read config in the hot path. Instead:

1. **Bootstrap:** On startup, SDK fetches all config for its namespace from the Config API (via Redis cache for speed)
2. **Local cache:** All config stored in an in-memory hash map. `getConfig("db.host")` is a hash-map lookup (~100 nanoseconds)
3. **Watch:** SDK maintains a long-poll/SSE connection to the Change Notifier. When config changes, the SDK receives the diff and updates its local cache
4. **Callback hooks:** Application registers callbacks: `onConfigChange("db.pool.size", newValue -> adjustPoolSize(newValue))`

**Fallback chain:** Local cache -> File snapshot -> etcd API. If the config service is completely down, the SDK uses the last-known file snapshot (written to disk on every update). Services can start and run indefinitely with stale config rather than failing to start.

### Strong Consistency via Raft Consensus
etcd's Raft protocol ensures:
- Writes go to the leader node. The leader replicates to a majority (2 of 3 nodes) before confirming
- Reads can be served from any node (relaxed) or from the leader (strict). For config, relaxed consistency is fine -- a few hundred milliseconds of staleness is acceptable when the watch system provides near-real-time updates
- If the leader fails, a new leader is elected in ~1 second. During election, writes are blocked but reads continue from followers

**3-node or 5-node cluster?** 3-node tolerates 1 failure. 5-node tolerates 2 failures. For most config services, 3 nodes is sufficient -- config writes are rare, and the blast radius of a brief write outage is small.

### Environment Inheritance with Override
Configuration follows an inheritance chain:
```
default -> environment -> service -> service+environment
```

Example lookup for `user-service` in `production`:
1. Check `/config/user-service/production/db.host` (most specific)
2. If not found, check `/config/user-service/default/db.host`
3. If not found, check `/config/global/production/db.host`
4. If not found, check `/config/global/default/db.host`

**Why?** Most config is the same across environments. Only differences (DB URLs, API keys, feature toggles) need environment-specific overrides. This prevents config duplication and ensures consistency.

**Resolution happens at the SDK level**, not at the API level. The SDK receives all layers and resolves the effective value locally. This means the SDK can show "this value comes from the global default" in debug mode.

### Secret Encryption with Vault Integration
Secrets (DB passwords, API keys) are stored encrypted at rest:
1. Admin writes a secret via the Config API
2. Config API encrypts the value using a key from HashiCorp Vault (or AWS KMS)
3. Encrypted value stored in etcd
4. SDK decrypts on read using its service's Vault token

**Why separate from plain config?** Secrets require:
- Encryption at rest and in transit
- Access control (not every service should see every secret)
- Rotation support (change a secret without downtime)
- Audit logging (who accessed which secret and when)

### Schema Validation on Write
Every config namespace has an optional JSON Schema that validates writes:
```json
{
  "db.pool.size": {"type": "integer", "minimum": 1, "maximum": 100},
  "db.host": {"type": "string", "format": "hostname"},
  "feature.new_checkout": {"type": "boolean"}
}
```

**Why validate?** A typo in a config value can cause an outage. Schema validation catches:
- Wrong type (`"10"` vs `10`)
- Out-of-range values (pool size of 0 or 10000)
- Missing required fields

This is a pre-write check. Invalid writes are rejected with a clear error message before reaching etcd.

## Component Breakdown

| Component | Role |
|---|---|
| **Services A/B/C** | Microservices that consume configuration via the SDK |
| **Admin CLI / UI** | Interface for engineers to read, write, and manage configuration |
| **Local Config Cache** | In-process hash map in each SDK instance. All reads served from here |
| **Change Watcher** | SDK component maintaining a persistent connection for real-time config updates |
| **File Fallback** | Disk-based snapshot of last-known config. Used if the config service is unreachable on startup |
| **Config API (Read Path)** | REST API for initial config bootstrap and polling fallback |
| **Config API (Write Path)** | REST API for config CRUD. Validates schema, encrypts secrets, writes to etcd |
| **Change Notifier** | Watches etcd for changes, pushes updates to connected SDKs via SSE/long-poll |
| **etcd Cluster (3 nodes)** | Raft-based strongly consistent key-value store. Source of truth for all config |
| **Version History** | Every config change stored with version number, author, timestamp, and diff |
| **Audit Log** | Records every config read (for secrets) and write with full context |
| **Encryption (Vault/KMS)** | Encrypts/decrypts secret config values. Manages encryption keys and access policies |
| **Schema Validator** | JSON Schema validation on write path. Prevents invalid config from being persisted |

## Key Trade-offs

- **Strong consistency vs availability:** etcd prioritizes consistency (CP in CAP). During a network partition, the minority side cannot write. For config (write-rare, read-cached), this is the right choice -- a brief write outage is preferable to inconsistent config
- **Local cache staleness:** Between a config write and the watch notification, services serve stale config. For most settings (timeouts, pool sizes), a 1-5 second staleness window is harmless. For security-critical settings (revoked API keys), you need a different mechanism (direct cache invalidation)
- **Centralized vs file-based config:** Centralized config (etcd) enables dynamic updates but introduces a dependency. File-based config (YAML in git) is simpler but requires redeployment. The hybrid approach (centralized with file fallback) gives you both
- **Encryption complexity:** Encrypting secrets adds latency to reads and complexity to key management. Some teams skip encryption and rely on network-level security (VPC, mTLS). But if an etcd backup leaks, all secrets are exposed. Encryption is worth the complexity

## What Fails First

**etcd leader election during rolling upgrades** is the most common disruption. Upgrading an etcd node requires restarting it. If the leader is restarted, a new election occurs (~1 second). During this window, writes fail.

**Mitigation:**
- Always upgrade followers first, leader last
- SDK handles transient write failures with retry
- Reads continue from followers during leader election
- File fallback ensures services never fail to start

**Secondary risk:** Watch connection storms after a config service restart. All 500 service instances reconnect simultaneously, overwhelming the Change Notifier. **Mitigation:** SDK reconnects with random jitter (0-5 seconds). Change Notifier serves the diff from the last-known version, not a full snapshot.

## v1 vs v2

### v1 (Ship in 1 week)
- PostgreSQL as the config store (simpler to operate than etcd for a small team)
- YAML-based config files with a REST API overlay
- Polling-based updates (SDK polls every 30 seconds)
- No encryption (secrets in environment variables or AWS Secrets Manager)
- No schema validation
- Single environment (production only)
- File-based fallback for startup

### v2 (Production-grade)
- etcd cluster (3 or 5 nodes) with Raft consensus
- SDK with local cache, SSE-based watch, and file fallback
- Environment inheritance (default -> env -> service -> service+env)
- Vault-integrated secret encryption with per-service access policies
- JSON Schema validation on all writes
- Full version history with one-click rollback
- Audit log with Slack notifications on production config changes
- Canary config: apply a change to 10% of instances, verify, then roll out to all
- Config diffing tool: "what changed between v47 and v48?"
- RBAC: who can modify which namespaces in which environments
- Config drift detection: alert if a service's effective config diverges from its namespace
