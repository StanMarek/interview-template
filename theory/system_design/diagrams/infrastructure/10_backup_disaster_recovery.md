# Backup and Disaster Recovery Strategy for Critical Services -- Architecture Design

## Requirements
### Functional
- Automated backup of all critical data stores: databases, object storage, configuration, secrets
- Point-in-time recovery (PITR) for databases with granularity of 1 second
- Cross-region replication for disaster recovery
- Automated failover to DR region when primary is unavailable
- Backup verification: nightly restore tests to prove backups are valid
- Runbook-driven DR process with automated steps where possible
- Backup encryption at rest and in transit
- Retention policies: daily backups for 30 days, weekly for 1 year, monthly for 7 years (compliance)

### Non-Functional
- RPO (Recovery Point Objective): < 1 minute for critical databases, < 1 hour for non-critical
- RTO (Recovery Time Objective): < 15 minutes for automated failover, < 4 hours for full region rebuild
- Backup must not impact production performance (use replicas for backup source)
- DR region must be in a different geographic area (> 500km from primary)
- All backups encrypted with customer-managed keys
- Regular DR drills (quarterly) to validate the entire failover process

## Scale Estimates
- 50 critical databases (total 20TB), 200 non-critical databases (total 100TB)
- Object storage: 500TB across all services
- Backup storage: 3x primary data (daily + weekly + monthly retention) = 360TB+ for databases
- Cross-region replication bandwidth: sustained 1Gbps, burst 10Gbps
- Backup window: continuous for WAL/incremental, nightly for full snapshots
- DR failover: tested quarterly, triggered maybe once per year

## Architecture Decisions

### Active-Passive vs Active-Active Multi-Region
**Active-passive**: Primary region handles all traffic, DR region has standby infrastructure. On failover, promote DR to active. Simple, lower cost, but RTO is higher (minutes to promote DB, warm caches, start services). **Active-active**: Both regions serve traffic, data replicated bidirectionally. RTO is near-zero but introduces conflict resolution complexity (split-brain writes). Recommendation: active-passive for most services (simpler, cheaper), active-active only for the most critical user-facing services where even 15 minutes of downtime is unacceptable.

### Backup Levels: Snapshot + WAL + Logical
Three complementary backup strategies: (1) **Full snapshots** (nightly): complete database copy, used as the base for PITR. (2) **WAL archiving** (continuous): write-ahead log shipped to object storage, enables PITR to any second. (3) **Logical backups** (weekly): pg_dump/mysqldump, slower but human-readable and portable across database versions. Each level serves a different recovery scenario. Full snapshots for fast restore, WAL for fine-grained PITR, logical for cross-version migration.

### Cross-Region Replication vs Cross-Region Backup Copy
Replication (async DB replica in DR region) gives sub-minute RPO and fast failover (promote the replica). But replication can propagate corruption (a bad write replicates to DR). Backup copy (ship encrypted snapshots to DR) protects against corruption but has higher RPO (last backup time). Use both: replication for fast failover, backup copies for corruption recovery.

### Automated vs Manual Failover
Fully automated failover (DNS health check detects failure, promotes DR automatically) minimizes RTO but risks false positives (network blip triggers unnecessary failover). Manual failover (human confirms the decision) adds minutes to RTO but avoids false positives. The hybrid approach: automated detection with manual confirmation. Alert the on-call, provide a one-click failover button, require human approval. For truly critical services, allow automated failover after a sustained failure threshold (5+ minutes of health check failures).

## Component Breakdown

### Primary Region
- **App Servers (Active)**: The running application instances. Stateless, easily replaced. Configuration stored in version control and config management systems.
- **Load Balancer**: Distributes traffic to app servers. Health-checked by Global DNS.
- **Primary DB (Writer)**: The authoritative database accepting writes. PostgreSQL or MySQL with WAL archiving enabled. Backup source is the local read replica (never backup from the writer to avoid performance impact).
- **Read Replica (Local)**: Serves read traffic and acts as the backup source. Backup agent takes snapshots from this replica.
- **Cache (Redis)**: Not backed up in the traditional sense (it is a cache, rebuilt from DB on startup). However, if used as a primary data store, it must be replicated and backed up.
- **Message Queue**: Durable messages (Kafka with replication) survive broker failures. For DR, MirrorMaker or similar replicates to the DR region. In-flight messages may be lost during failover (acceptable for most queues).
- **Object Storage (S3)**: Cross-region replication enabled. New objects replicate within minutes. Versioning enabled for accidental deletion protection.
- **Backup Agent**: Runs on a schedule. Takes full snapshots nightly, ships WAL continuously, and sends encrypted backups to both local backup vault and remote DR vault.
- **Local Backup Vault**: Stores snapshots and WAL archives locally for fast restore. Encrypted with KMS keys. Retention: 30 days for dailies, 1 year for weeklies.
- **WAL Archive**: Continuous WAL shipping to object storage. Enables PITR to any second within the retention window. Critical for recovering from accidental data deletion.

### DR Region
- **App Servers (Standby)**: Scaled to minimum (1-2 instances). Pre-deployed with the same application version. On failover, autoscaler brings to full capacity within minutes.
- **Cross-Region Replica (Async)**: Async database replica of the primary. Lag typically < 1 second under normal conditions, may increase during high write load. On failover, promoted to writer. Data since last replicated transaction is lost (RPO).
- **DR Read Replica**: Additional replica in DR region for read scaling after failover.
- **Cache (Cold)**: Empty or minimally populated. On failover, caches warm up from the database. Application must handle cold cache gracefully (higher latency for first requests).
- **Object Storage (Cross-Region)**: Replication of primary S3 bucket. Available immediately on failover.
- **Remote Backup Vault**: Copy of all backups from the primary region. Used if the primary region is completely lost. Encrypted with the same KMS key (key replicated to DR).
- **DR Runbook (Automated Steps)**: Step-by-step failover procedure, partially automated. Steps: verify primary is actually down (not just a monitoring failure), promote DB replica, update DNS, scale up app servers, warm caches, run smoke tests.

### DR Control Plane (Global)
- **Global DNS (Route53 Failover)**: Health checks the primary region's load balancer. If primary fails health checks for 3 consecutive intervals (90 seconds), automatically switches DNS to DR. TTL set low (60 seconds) to minimize stale DNS propagation.
- **DR Controller (Failover Orchestrator)**: Coordinates the failover sequence. Promotes DB replica, updates service configuration, scales up DR compute, runs post-failover verification. Can be triggered automatically (by DNS failover) or manually (one-click button).
- **Backup Scheduler**: Manages backup schedules for all databases. Full snapshot nightly at 02:00 UTC, WAL continuous, logical backup weekly on Sunday. Monitors backup success/failure. Alerts on any backup failure immediately.
- **Chaos Testing (DR Drills)**: Quarterly DR failover exercises. Simulates primary region failure, executes the full failover process, measures actual RTO and RPO, identifies gaps. Uses isolated test environments initially, then graduates to production with controlled scope.
- **Restore Verifier**: Nightly job that restores the latest backup to a temporary database and runs data integrity checks (row counts, checksum comparison, application health check against restored DB). Alerts if restore fails or data is inconsistent. This is the most important component -- a backup that cannot be restored is worthless.
- **RPO/RTO Monitor**: Continuously measures cross-region replication lag (actual RPO) and estimates RTO based on current infrastructure state. Alerts if RPO exceeds the target (replication lag > 1 minute) or if DR region infrastructure is not ready.
- **Incident Manager (PagerDuty)**: Receives alerts from the monitoring system. Pages the on-call for backup failures, replication lag spikes, and DR readiness issues. Coordinates the human decision to failover.

## Operational Concerns
- **Testing DR regularly**: The single most important operational practice. A DR plan that has never been tested is a plan that will fail. Start with tabletop exercises, progress to automated failover in staging, then quarterly production DR drills (failover to DR, run for 1 hour, fail back).
- **Blast radius of a bad failover**: If you failover to DR unnecessarily (false positive), you: (1) lose any data not yet replicated (RPO gap), (2) run on DR infrastructure that may be undersized, (3) need to failback later (another risky operation). Mitigation: require human confirmation, ensure DR is always provisioned to handle full load.
- **Failback after DR**: After primary region recovers, you need to failback. This is often harder than failover: you must replicate all data written to DR back to primary, then switch traffic back. Plan and test failback as part of DR drills.
- **Backup key management**: If you lose the encryption keys, you lose the backups. Store KMS keys in multiple regions. Ensure key access is not dependent on a single region.

## Failure Modes
- **Primary region complete outage**: Global DNS detects failure, routes to DR. DR controller promotes DB replica and scales up compute. Data written after last replication is lost (RPO gap). Services come up in DR within 15 minutes (RTO target).
- **Database corruption (not a region failure)**: A bug writes bad data that replicates to DR. Replication does not help here -- both regions have bad data. Recovery: PITR from WAL archive to the point before the corruption. This is why WAL archiving is essential even when you have replication.
- **Backup corruption (silent failure)**: A backup appears to succeed but the data is corrupted or incomplete. Discovered only when you try to restore. This is why the nightly restore verifier is critical. Without it, you may discover your backups are useless during an actual disaster.
- **DR region also fails (correlated failure)**: Extremely rare but possible (cloud provider issue, natural disaster affecting both regions). Mitigation: maintain logical backups in a third region or different cloud provider. Accept much longer RTO (hours to days) for this scenario.
- **Network partition between regions**: Primary is up but cannot replicate to DR. Replication lag grows. RPO increases. Alert when lag exceeds threshold. If partition persists, primary continues serving while backups accumulate. DR state is stale. On failover, data loss equals the partition duration.

## Key Trade-offs
- **RPO vs Cost**: Sub-second RPO requires synchronous cross-region replication, which adds write latency (50-200ms per write). Async replication gives sub-minute RPO with no latency impact but risks data loss. Most services should use async replication unless they process financial transactions.
- **RTO vs Standby cost**: Lower RTO requires more standby infrastructure in DR (running VMs, pre-warmed caches, higher DB replica count). "Pilot light" DR (minimum standby, scale up on failover) is cheaper but has higher RTO. "Warm standby" (scaled-down but running) balances cost and RTO.
- **Automated failover vs False positive risk**: Automated failover minimizes RTO but can trigger on monitoring false positives. Manual failover is safer but slower. The right balance depends on the cost of downtime vs the cost of unnecessary failover.
- **Backup frequency vs Storage cost**: More frequent backups reduce RPO but increase storage cost. Continuous WAL archiving for databases is the sweet spot: low RPO, incremental storage cost. For object storage, cross-region replication handles this natively.

## What Fails First
The failback process. Organizations invest in failover but neglect failback. After running on DR for hours or days, data has been written to the DR region. Failing back to primary requires: (1) replicating DR data to primary, (2) verifying data integrity, (3) switching traffic back, (4) confirming primary is healthy. This process is error-prone and rarely tested. The result: organizations failover to DR successfully but then get stuck there, running on DR infrastructure indefinitely. The fix: include failback in every DR drill. Make failback a documented, automated process with the same rigor as failover.

## v1 vs v2
### v1 (Minimum Viable Backup & DR)
- Automated nightly full snapshots for all databases
- WAL archiving to local object storage (PITR within same region)
- Cross-region replication for the 5 most critical databases
- S3 cross-region replication for object storage
- Manual failover runbook (documented steps, human execution)
- Nightly restore verification for critical databases
- Global DNS with health checks (manual failover trigger)
- Weekly DR tabletop exercises
- Backup encryption with cloud-managed keys

### v2 (Production Grade)
- Continuous WAL archiving for all databases (not just critical)
- Cross-region replication for all critical and semi-critical databases
- Automated DR controller with one-click failover (human approval gate)
- Full failback automation and quarterly production DR drills
- Restore verifier for ALL databases nightly (not just critical)
- RPO/RTO monitoring dashboard with real-time replication lag
- Chaos testing integration (simulate region failure monthly)
- Multi-tier backup retention (daily/weekly/monthly/yearly)
- Customer-managed encryption keys (BYOK)
- Third-region logical backup copy for catastrophic scenarios
- Automated capacity pre-provisioning in DR (match primary scaling)
- DR drill scorecards: measured RTO vs target, identified gaps, remediation tracking
