# Distributed Job Scheduler -- Architecture Design

## Requirements
### Functional
- Submit, schedule, and execute jobs (one-off, recurring/cron, and DAG-based workflows)
- Priority queues with multiple priority levels and preemption support
- At-least-once execution guarantee with idempotency support
- Job lifecycle management: PENDING -> QUEUED -> RUNNING -> SUCCEEDED/FAILED/CANCELLED
- DAG support: define job dependencies (A must complete before B starts)
- Dead letter queue for jobs that fail after max retries
- Job artifacts: input/output data, logs, exit codes
- Multiple worker pools with different resource profiles (CPU, GPU, memory)

### Non-Functional
- Schedule accuracy within 1 second for cron triggers
- No job lost: every submitted job eventually executes or lands in dead letter queue
- Handle 100K+ pending jobs in the queue
- Worker failure must not lose a running job -- detect and re-queue within 60s
- Control plane available 99.99%; worker pool available 99.9%

## Scale Estimates
- 50K-200K job executions per day
- 1000-5000 concurrent running jobs
- 500-2000 worker nodes across pools
- Queue depth: up to 100K pending jobs during batch processing peaks
- Job metadata store: 100M+ historical job records
- Cron schedules: 10K recurring job definitions

## Architecture Decisions

### Pull-Based Worker Model (Workers Pull Jobs)
Workers poll the queue for jobs rather than the scheduler pushing to specific workers. This naturally handles worker failures (if a worker dies, its job times out and becomes available for another worker) and simplifies scaling (add more workers, they start pulling). The trade-off: slightly higher latency (polling interval) compared to push. Mitigated with long-polling or blocking dequeue.

### Partitioned Queues by Priority and Resource Type
Instead of one monolithic queue, partition by (priority, resource type). High-priority jobs go to a separate queue consumed first. GPU jobs go to a queue only GPU workers consume. This prevents head-of-line blocking (a flood of low-priority jobs does not block high-priority ones) and ensures resource-specific jobs reach appropriate workers. Trade-off: more queues to manage and monitor.

### Leader-Elected Scheduler
The scheduler runs as active-standby with distributed lock-based leader election (ZooKeeper, etcd, or Redis lock). Only the leader makes scheduling decisions (cron trigger evaluation, DAG progression, job timeout detection). This avoids duplicate scheduling. If the leader dies, the standby acquires the lock within seconds. Trade-off: brief scheduling gap during failover.

### At-Least-Once with Idempotency Keys
Jobs may execute more than once (worker crashes after completing but before acknowledging). The system guarantees at-least-once, and job implementations should be idempotent. The scheduler assigns a unique execution ID to each attempt, and the job can check if that execution already completed. This is simpler and more reliable than exactly-once, which requires distributed transactions.

## Component Breakdown

- **Job API Service**: REST/gRPC API for submitting, cancelling, querying, and listing jobs. Validates job specs (resource requirements, retry policy, schedule). Writes to job store and enqueues.
- **Web UI / Dashboard**: Visualize job status, DAG progress, queue depth, worker utilization. Allows manual retry, cancel, and priority adjustment.
- **Cron Trigger Service**: Evaluates cron expressions every second. Submits jobs to the API when their schedule fires. Handles timezone, DST transitions, and catch-up for missed triggers (configurable: skip or backfill).
- **Scheduler (Leader)**: Manages the priority queue. Handles DAG progression (when upstream job completes, enqueue downstream). Detects timed-out jobs and re-queues. Evaluates rate limits per tenant/queue.
- **Queue Manager (Partitioned)**: Manages partitioned queues backed by Redis or Kafka. Supports blocking dequeue, visibility timeout (job invisible while being processed), and dead-letter after max attempts.
- **DAG Engine**: Parses and validates DAG definitions. Tracks completion status of each node. Triggers downstream jobs when all upstream dependencies are satisfied. Handles partial failure (fail-fast or continue-on-failure modes).
- **Job State Machine**: Manages job lifecycle transitions with strict state machine rules (e.g., only RUNNING can transition to SUCCEEDED). Persists every transition with timestamp for debugging.
- **Dead Letter Queue**: Holds jobs that exhausted retries. Operators can inspect, fix, and re-submit. Alerts on DLQ depth growth.
- **Distributed Lock (Leader Election)**: Redis SETNX or etcd lease-based leader election. Lock has TTL; leader must renew periodically. Standby watches for lock release.
- **Rate Limiter**: Per-tenant and per-queue rate limits to prevent one team from monopolizing the scheduler. Token bucket algorithm with configurable burst.
- **Worker Pool (General/GPU/Memory)**: Stateless workers that pull jobs, execute in isolated containers, and report results. Each pool has autoscaling based on queue depth.
- **Container Executor**: Runs each job in an isolated container (Docker/containerd). Enforces resource limits (CPU, memory, timeout). Captures stdout/stderr as job logs.
- **Worker Heartbeat Manager**: Workers send heartbeats every 10s. If missed for 3 intervals, the scheduler marks the job as timed out and re-queues it.

## Operational Concerns
- **Deploying scheduler changes safely**: Blue-green deploy with leader election. Deploy new version as standby, promote by releasing the old leader's lock. If new version is buggy, demote it and restore old leader.
- **Blast radius of a bad job**: A buggy job that consumes all memory on a worker kills only that job (container isolation). But a flood of bad jobs can exhaust the worker pool. Mitigation: per-tenant quotas, circuit breakers on high-failure-rate job definitions.
- **Rollback**: For the scheduler itself, swap leader to previous version. For bad job definitions, disable the cron trigger and cancel pending instances.
- **Queue depth monitoring**: Alert when queue depth exceeds threshold (jobs backing up faster than workers consume). Auto-scale worker pool up. If workers are at max capacity, alert humans.

## Failure Modes
- **Scheduler leader crash**: Standby acquires lock within 5-10s. During the gap, no new cron triggers fire, no DAG progression happens, and no timeouts are detected. Jobs already in the queue continue being processed by workers. Brief delay, no data loss.
- **Worker crash mid-job**: Heartbeat stops, scheduler detects timeout after 30-60s, re-queues the job. The new execution gets a new attempt ID. If the job was partially completed, the idempotency key prevents duplicate side effects.
- **Queue (Redis) failure**: If Redis is down, no new jobs can be enqueued or dequeued. Running jobs continue. Jobs submitted during outage are rejected with a retriable error. Multi-node Redis with failover mitigates this.
- **Job store (Postgres) failure**: Job metadata writes fail. The scheduler cannot persist state transitions. Running jobs continue but their status is not updated. On recovery, the scheduler reconciles by querying workers for current state.
- **Thundering herd after outage recovery**: When the scheduler recovers after an extended outage, many cron jobs need to catch up. Configurable catch-up policy: skip missed runs (default for most jobs), or backfill one-at-a-time (for data pipelines that must not miss a window).

## Key Trade-offs
- **At-least-once vs Exactly-once**: At-least-once is simpler and more reliable but requires idempotent jobs. Exactly-once requires distributed transactions between the queue and the job store, which is complex and fragile. Most production schedulers choose at-least-once.
- **Polling interval vs Scheduling latency**: Short polling interval (100ms) means fast job pickup but high load on the queue. Long polling interval (5s) reduces load but adds latency. Blocking dequeue (long poll) gives the best of both: instant pickup with low load.
- **Single scheduler vs Sharded schedulers**: A single leader scheduler is simpler but becomes a bottleneck at scale. Sharding by queue partition allows parallel scheduling but adds complexity (partition assignment, rebalancing). Start with single leader, shard when it becomes a bottleneck.
- **Container isolation vs Bare-process execution**: Containers provide strong isolation but add startup overhead (1-5s). Bare processes start instantly but risk resource interference. Use containers for untrusted or user-submitted jobs, bare processes for trusted internal jobs.

## What Fails First
Queue depth growth during peak load. When job submission rate exceeds worker consumption rate, the queue grows unboundedly. This consumes Redis memory, increases scheduling latency (more jobs to prioritize), and causes cascading timeouts. The fix is aggressive autoscaling of the worker pool with preemptive scaling (scale based on queue depth trend, not just current depth), combined with admission control that rejects or rate-limits low-priority jobs when queue depth exceeds a threshold.

## v1 vs v2
### v1 (Minimum Viable Scheduler)
- Single scheduler instance (no HA)
- Redis-backed priority queue (single partition)
- One-off and cron jobs only (no DAG)
- Single worker pool
- Postgres for job metadata
- Fixed retry count (3 attempts, exponential backoff)
- Basic web UI showing job status
- Container-based execution

### v2 (Production Grade)
- Leader-elected scheduler with standby
- Partitioned queues by priority and resource type
- Full DAG engine with conditional branching
- Multiple worker pools with autoscaling
- Per-tenant rate limiting and quotas
- Dead letter queue with alerting
- Job artifact storage (S3) and log streaming
- Cron catch-up policy (skip vs backfill)
- SLA tracking (job must complete within X minutes of scheduled time)
- Integration with incident management for DLQ alerts
- Audit log for all job lifecycle events
