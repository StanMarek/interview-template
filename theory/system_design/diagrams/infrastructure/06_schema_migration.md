# Schema Migration Platform for Many Services -- Architecture Design

## Requirements
### Functional
- Manage schema migrations for 100+ microservices, each owning its own database
- Version-track migrations per service (ordered, immutable sequence of DDL changes)
- Safety checks: detect destructive operations (DROP COLUMN, data type narrowing), lock-hazardous operations (ALTER on large tables without online DDL)
- Online schema changes for large tables (no downtime, no long-held locks)
- Approval gates for high-risk migrations (production databases, large tables)
- Rollback support: generate reverse migrations, support undo
- Integration with CI/CD: migrations run as part of deployment pipeline
- Multi-database engine support: MySQL, PostgreSQL, and potentially others

### Non-Functional
- Zero production downtime during schema migrations
- Replication lag must stay below threshold during migration execution
- Migration state must be durable (never lose track of which migrations have been applied)
- Blast radius control: a bad migration affects only one service's database
- Audit trail for every migration applied to every database

## Scale Estimates
- 200 microservices, each with 1-3 databases = 300-600 databases
- 50-100 migrations applied per week across all services
- Table sizes: from thousands of rows to billions of rows
- Largest tables: 500M+ rows, 200GB+, requiring online DDL tools
- Migration execution time: seconds (small table DDL) to hours (large table online DDL)

## Architecture Decisions

### Online DDL as the Default for Large Tables
Direct ALTER TABLE on a large MySQL table acquires a metadata lock that blocks all queries for the duration. Tools like gh-ost (GitHub Online Schema Tool) or pt-online-schema-change (Percona) create a shadow table, copy data row-by-row, then atomically swap. This allows zero-downtime changes. The trade-off: online DDL takes much longer (hours vs minutes), requires extra disk space (shadow table), and increases replication traffic. The platform should automatically choose the migration strategy based on table size.

### Per-Database Distributed Locking
Only one migration should run against a database at a time. Concurrent migrations can cause deadlocks, unpredictable lock interactions, and make rollback impossible. A distributed lock (Redis or ZooKeeper) per database ensures serialization. The lock has a TTL to prevent orphaned locks from blocking all future migrations.

### Expand-Contract Pattern for Backward Compatibility
Every destructive change should follow the expand-contract pattern: (1) Expand: add new column/table, dual-write both old and new. (2) Migrate: backfill old data to new schema. (3) Contract: remove old column/table after all consumers have switched. This ensures the application works with both old and new schemas during the transition period. The platform should enforce this pattern for destructive operations.

### Schema Validation at PR Time
Catch dangerous migrations before they reach production. A CI check analyzes the SQL and flags: removing columns with foreign keys, changing column types that lose precision, adding NOT NULL without defaults, and any DDL on tables above a size threshold without using online DDL. This shifts the feedback loop left and prevents production incidents.

## Component Breakdown

- **CI/CD Pipeline**: Triggers migration execution during deploy. Migrations run before application code deploys (schema must be compatible with both old and new code). Can also run migrations independently from code deploys.
- **PR Review / Lint**: Static analysis of migration SQL at PR time. Checks for destructive operations, missing indexes, lock-hazardous patterns. Outputs warnings and blocks merge for critical issues.
- **Migration Dashboard**: Shows migration status per service and per database. Tracks which version each database is at. Shows running migrations, estimated completion time, replication lag during execution.
- **CLI Tool**: Developer-facing tool for authoring migrations, testing locally, dry-running against staging, and manually triggering migrations.
- **Migration Registry (Version Tracking)**: Stores the ordered sequence of migrations per service. Each migration has a unique ID (timestamp-based or sequential), SQL content, and metadata. The applied version per database is tracked separately.
- **Schema Validator (Safety Checks)**: Analyzes migration SQL statically. Rules include: no DROP TABLE in production, no ALTER TABLE without online DDL for tables > 10M rows, no removing columns referenced by other services, no changing primary key types.
- **Migration Orchestrator**: The brain of the platform. Receives migration requests, acquires database locks, selects execution strategy (direct DDL vs online DDL), monitors execution, and handles success/failure transitions.
- **Distributed Lock (Per-DB)**: Ensures one migration per database at a time. Lock includes the migration ID and executor identity. TTL prevents orphaned locks. Lock is released on completion, failure, or manual override.
- **Rollback Manager**: Stores reverse migrations (auto-generated or manually provided). Executes rollback by applying the reverse migration. Some operations are not reversible (DROP TABLE with data loss) -- the rollback manager flags these and requires a backup-restore path instead.
- **Approval Gate**: Requires manual approval for high-risk migrations (production databases, tables > 100M rows, destructive operations). Can be auto-approved for low-risk migrations (adding nullable columns, creating indexes concurrently).
- **Impact Analyzer**: Queries the target database to determine table size, lock duration estimate, disk space requirements, and replication lag impact. Feeds this information into the approval gate and execution strategy selection.
- **gh-ost / pt-osc Runner**: Executes online DDL for large tables. Monitors replication lag and throttles the copy process when lag exceeds threshold (typically 5-10 seconds). Reports progress percentage to the dashboard.
- **Direct DDL Executor**: For small tables and simple operations that do not require online DDL. Wraps the migration in a transaction (where supported by the engine), applies it, and verifies.
- **Data Backfill Runner**: For expand-contract migrations that require populating a new column from existing data. Runs batch updates with configurable batch size and sleep between batches to avoid overloading the database.
- **DB Health Monitor (Replication Lag)**: Continuously monitors replication lag on replica databases. Feeds lag data to the gh-ost runner for throttling. Pauses the migration entirely if lag exceeds a hard threshold.

## Operational Concerns
- **Deploying platform changes safely**: The migration platform itself is a critical service. Deploy with canary (apply one migration through the new version, verify, then roll out). The platform's own schema is migrated by its own tooling (dogfooding).
- **Blast radius of a bad migration**: Affects only one service's database. The per-database lock prevents cascading. If a migration corrupts data, the rollback manager can undo it (for reversible changes) or restore from backup (for irreversible changes). Application code should handle both old and new schemas during the expand phase.
- **Rollback**: For DDL changes: apply the reverse migration (e.g., ADD COLUMN -> DROP COLUMN). For data migrations: restore from point-in-time backup. The platform should always take a backup (or verify a recent backup exists) before applying a migration to a production database.
- **Long-running migration management**: gh-ost on a 500M-row table can take hours. The dashboard shows progress, ETA, and replication lag. Operators can pause/resume/cancel. If the migration must be abandoned, gh-ost cleans up the shadow table.

## Failure Modes
- **Migration executor crash mid-migration**: For direct DDL in a transaction: the transaction rolls back automatically. For gh-ost: the shadow table remains, and the migration can be resumed (gh-ost supports resumption). The orchestrator detects the crash via heartbeat timeout, releases the lock, and marks the migration as FAILED.
- **Replication lag spike during migration**: The health monitor detects the spike, throttles the migration (gh-ost reduces copy rate), and pauses entirely if lag exceeds the hard threshold. The migration resumes when lag recovers. No manual intervention needed.
- **Lock service failure**: If the distributed lock service (Redis) goes down, no new migrations can start (cannot acquire lock). Running migrations continue. The lock TTL prevents permanent blocking: after TTL expires, the lock is released. Risk: if a migration is still running when the lock expires, two migrations could run concurrently. Mitigation: set TTL longer than the maximum expected migration duration.
- **Platform database failure**: Migration state is lost. Running migrations continue (they execute directly on the target database) but their status is not tracked. On recovery, the platform must reconcile by checking each target database's actual schema version.

## Key Trade-offs
- **Speed vs Safety**: Direct DDL is fast but locks the table. Online DDL is safe but slow. The platform should default to safety (online DDL for large tables) and allow override for emergencies.
- **Automated vs Manual approval**: Full automation speeds up development but increases risk. Full manual approval slows everything down. The sweet spot: auto-approve for low-risk operations (additive, small tables), require approval for high-risk ones.
- **Forward-only vs Reversible migrations**: Forward-only is simpler (Flyway style) but makes rollback harder. Reversible migrations (up/down like Rails) are more flexible but developers rarely test the "down" path. Recommendation: forward-only by default, with mandatory rollback plans for high-risk changes documented in the PR.
- **Centralized platform vs Per-service tooling**: A centralized platform provides consistency and guardrails but adds a dependency. Per-service tooling gives teams autonomy but leads to inconsistent practices. The centralized platform wins for organizations with 50+ services.

## What Fails First
Long-running online DDL on a table that is also experiencing a traffic spike. The combination of gh-ost's row copying and high application write throughput causes replication lag to spike repeatedly, the migration keeps throttling and resuming, and the shadow table grows large. Meanwhile, the extra disk space usage pushes the database server toward disk exhaustion. The fix: schedule large migrations during low-traffic windows, ensure 2x free disk space before starting, and set a maximum migration duration after which the migration auto-cancels.

## v1 vs v2
### v1 (Minimum Viable Platform)
- Migration files in each service's repo (Flyway or Liquibase format)
- CI pipeline runs migrations against target database during deploy
- Schema lint in PR checks (detect DROP, missing index, large-table DDL)
- Per-database advisory lock (SELECT pg_advisory_lock or GET_LOCK)
- Manual approval via Slack for production migrations
- Basic dashboard showing migration history per service
- Direct DDL execution only (no online DDL tool integration)

### v2 (Production Grade)
- gh-ost / pt-osc integration for online DDL on tables > 10M rows
- Automatic strategy selection based on table size and operation type
- Replication lag monitoring with automatic throttling
- Impact analyzer (table size, estimated duration, disk space check)
- Expand-contract enforcement for destructive changes
- Data backfill runner with batch processing
- Rollback manager with auto-generated reverse migrations
- Cross-service schema dependency tracking
- Pre-migration backup verification
- Multi-region migration coordination (migrate read replica region first)
- SLA tracking (migration must complete within deployment window)
