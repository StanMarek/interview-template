# Fraud Detection Event Processing System — Architecture Design

## Requirements

### Functional
- Score every payment transaction for fraud risk in real-time (before the transaction is approved/declined)
- Support rule-based detection (velocity checks, blacklists, geo-impossible travel)
- Support ML-based detection (trained on historical fraud patterns)
- Correlate events across multiple signals: transactions, logins, device fingerprints, merchant history
- Route suspicious transactions to appropriate actions: block, step-up authentication (3DS/OTP), or manual review queue
- Maintain a graph of entity relationships (user-device-merchant-IP) for ring detection
- Support fraud analyst workflows: case investigation, decision feedback loop

### Non-Functional
- Detection latency: fraud decision returned within 100ms (synchronous in the payment flow)
- False positive rate: below 1% (blocking legitimate transactions destroys user trust)
- False negative rate: below 0.1% (missing fraud costs real money)
- Handle 50K transactions/sec at peak (Black Friday, flash sales)
- Audit trail: every decision must be traceable (regulatory compliance, PCI-DSS)
- Model updates must not cause downtime or decision inconsistencies

## Scale Estimates
- **Transactions/sec:** 50K peak, 10K sustained
- **Supporting events/sec:** 200K auth events, 100K device events (total 350K events/sec)
- **Unique entities:** 500M users, 10M merchants, 2B device fingerprints
- **Risk profiles:** 500M user profiles * ~500 bytes = ~250GB (Redis)
- **Graph:** ~5B edges (user-device, user-merchant, device-IP relationships)
- **Model inference:** 50K inferences/sec, each ~5ms on GPU, ~20ms on CPU

## Architecture Decisions

### Synchronous Scoring in the Transaction Path
Unlike most streaming systems where processing is asynchronous, fraud detection MUST be synchronous — the payment gateway waits for the fraud score before approving the transaction. This means the entire pipeline (enrichment + rules + ML scoring + decision) must complete within 100ms. The architecture uses a request-response pattern over Kafka (or direct gRPC) rather than fire-and-forget. Trade-off: this couples the fraud system's availability to the payment flow. If fraud scoring is down, we must decide: approve all transactions (risk of fraud) or decline all (risk of lost revenue). Most systems choose "approve with logging" during outages, accepting temporary risk.

### Three-Layer Detection: Rules + ML + Graph
Each layer catches different fraud types:
- **Rules engine (CEP):** Catches known patterns with deterministic logic. "3+ transactions from different countries in 10 minutes" or "amount exceeds 10x user's average." Low false positive rate, but only catches known patterns. Rules are updated by fraud analysts without engineering deployments
- **ML model:** Catches novel/evolving patterns learned from historical data. XGBoost or neural network trained on features like transaction amount, time-of-day, merchant category, device age, behavioral biometrics. Catches ~70% of fraud that rules miss, but has a higher false positive rate
- **Graph analysis:** Catches organized fraud rings by detecting unusual connectivity patterns (one device used across 50 accounts, one address receiving shipments from 100 different accounts). This is the hardest to evade because it requires the fraud ring to create genuine-looking relationship patterns

### Score Fusion Over Single Model
Rather than one model making the final decision, each layer produces an independent score (0-1). A decision engine fuses these scores with configurable weights and thresholds. This is critical because: (1) rules should be able to hard-block regardless of ML score (blacklisted card), (2) the ML model can be updated independently without changing rules, (3) the graph score adds context that the ML model doesn't have. The fusion function is: `final_score = max(rule_score, w1*ml_score + w2*graph_score + w3*velocity_score)` with action thresholds: score > 0.9 = block, 0.7-0.9 = step-up auth, 0.5-0.7 = manual review, < 0.5 = approve.

### Redis for Risk Profiles Over Database Lookups
At 50K TPS with 100ms budget, there's no room for database round-trips. User risk profiles (recent transaction patterns, device history, velocity counters) are pre-computed and cached in Redis. Each enrichment lookup is ~1ms (Redis) vs ~10ms (PostgreSQL). The risk profile is maintained by a separate Flink job that processes the same events asynchronously — this is the "write path" that keeps profiles fresh, decoupled from the synchronous "read path" that scores transactions.

## Pipeline Stages

1. **Event Sources:** Payment service emits transaction events synchronously (waits for fraud decision). Auth service emits login events. Device fingerprinting service emits device events. Merchant service emits merchant risk updates
2. **Kafka Ingestion:** `transactions` topic for synchronous scoring (partitioned by `user_id`), `auth-events` and `device-events` topics for asynchronous profile enrichment. All events also written for audit
3. **Flink Event Enrichment:** Enriches raw transaction with: user risk profile (Redis lookup), merchant risk score (Redis lookup), device trust score (Redis lookup), geo-distance from last transaction (computed). Outputs enriched transaction with ~50 features
4. **Rules Engine (Flink CEP):** Evaluates Complex Event Processing patterns: velocity checks (N transactions in M minutes using sliding windows), blacklist matching, geo-impossible travel, amount anomaly. Produces `rule_score` (0-1)
5. **ML Scorer:** Runs XGBoost inference on the enriched feature vector. Model loaded from Model Registry, hot-swapped on updates without restart. Produces `ml_score` (0-1)
6. **Velocity Checks (Flink Sliding Windows):** Maintains per-user and per-card sliding window counters: transaction count in last 1h/24h, total amount in last 1h/24h, unique merchants in last 24h. These are both features for ML and standalone signals
7. **Decision Engine:** Fuses scores from all layers, applies action thresholds, returns decision (approve/decline/challenge/review) to the payment service. Writes decision + reasoning to Case DB for audit
8. **Batch Retrain (Spark + XGBoost):** Daily job trains the ML model on labeled data (transactions labeled as fraud/legitimate based on chargebacks, manual reviews, confirmed fraud reports). New model deployed to Model Registry, evaluated on holdout set before activation
9. **Graph Analysis (Neo4j):** Async Flink job maintains the entity graph. Batch job (hourly) runs community detection and anomaly algorithms to identify fraud rings. Ring membership is added to user risk profiles

## Partitioning Strategy

- **Kafka `transactions`:** Partition by `user_id` — this ensures all transactions from one user go to the same Flink subtask, enabling accurate velocity checks without cross-partition state. Critical: if a user has 50K transactions, they all process on one subtask, but the 100ms SLA still holds because each transaction processes independently (no aggregation delay)
- **Redis risk profiles:** Sharded by `user_id`. Co-located related data: `user:{id}:profile`, `user:{id}:velocity`, `user:{id}:devices` — all on the same shard for pipeline locality (single round-trip to fetch all enrichment data)
- **Neo4j graph:** Sharded by user community (detected via batch graph partitioning). Related entities are co-located to minimize cross-shard traversals during ring detection

## Failure Handling

- **Fraud service outage:** Circuit breaker pattern. If fraud scoring is unavailable, the payment gateway has a configurable fallback: (1) approve low-risk transactions (amount < $50, known device, returning customer) with async scoring, (2) decline high-risk transactions (new account, high amount, new device), (3) queue medium-risk for delayed scoring. The fallback rules are deliberately conservative
- **Redis failure:** Sentinel failover (~5 seconds). During failover, enrichment uses stale cached data from the application's local LRU cache (each Flink subtask caches recently seen profiles). Stale profiles degrade scoring quality but don't cause outage
- **ML model failure:** Shadow deployment pattern. New models run in parallel with the production model for 24 hours. If the new model's false positive rate exceeds the threshold, it is automatically rolled back. The production model continues serving during evaluation
- **DLQ:** Transactions that cause processing exceptions are declined (safety-first) and sent to DLQ. Fraud analysts investigate. The DLQ must never grow unbounded — if it exceeds 1000 events, an alert fires immediately because each DLQ entry is a customer-impacting decline
- **Audit trail:** Every transaction, every feature vector, every score, and every decision is written to S3 (immutable). Regulatory requirement: must be queryable for 7 years. Athena provides SQL access over S3 for compliance audits

## Key Trade-offs

- **Latency vs accuracy:** The 100ms budget forces model simplicity. A deep neural network with attention layers might catch 5% more fraud but takes 50ms for inference, consuming half the budget. XGBoost with 500 trees achieves 95% detection in 5ms. The remaining 5% is caught by rules and graph analysis, which are faster for their specific patterns
- **False positives vs false negatives:** Tuning the decision threshold is a direct business trade-off. Lowering the block threshold catches more fraud (fewer false negatives) but blocks more legitimate transactions (more false positives). Each false positive costs ~$5 in customer support + lost goodwill. Each false negative costs the fraud amount + chargeback fees. The optimal threshold depends on average transaction amount and fraud rate, and should be tuned per merchant category
- **Deterministic rules vs ML:** Rules are explainable (regulators can audit them) but rigid (fraudsters adapt). ML adapts but is a black box. Regulatory pressure (e.g., EU AI Act) may require explainability for automated decisions that affect users. Compromise: use ML for scoring but rules for the final action decision, so there's always a deterministic rule that caused the block
- **Real-time graph vs batch graph:** Real-time graph updates (adding edges on every transaction) enable immediate ring detection but create write bottlenecks in Neo4j. Batch graph updates (hourly) miss rapidly forming rings. We use batch for graph algorithms + real-time for simple 1-hop queries (e.g., "has this device been used by other accounts?")

## What Fails First

**The 100ms latency SLA** breaks first under load. The budget is divided roughly: 5ms Kafka, 10ms enrichment (Redis lookups), 5ms rules engine, 10ms ML inference, 5ms decision engine, 5ms response = 40ms ideal. The remaining 60ms is buffer for GC pauses, network jitter, and retries. Under sustained 50K TPS, the Flink job's thread pool saturates, enrichment Redis lookups queue up, and p99 latency exceeds 100ms. At this point, the payment gateway starts timing out, and transactions get the fallback treatment (approve/decline without scoring).

Solutions: (1) Pre-compute as much as possible — move enrichment from "at scoring time" to "when events arrive" (the async Flink job updates Redis profiles, so the synchronous path just reads pre-computed features), (2) Tier the detection — only run the full pipeline (rules + ML + graph) for high-risk transactions (new account, high amount); for low-risk transactions, only run the rules engine (< 10ms), (3) Scale Flink horizontally — more parallelism reduces per-subtask load.

## v1 vs v2

### v1 — Ship in 4 weeks
- Rules engine only: velocity checks + blacklists + geo checks
- Direct gRPC from payment service to fraud service (no Kafka in synchronous path)
- Redis risk profiles with basic features (transaction count, amount averages)
- Single-threaded decision logic (no score fusion, just rule-based approve/decline)
- PostgreSQL for case management, manual review via admin UI
- No ML model, no graph analysis
- Basic monitoring (latency, throughput, block rate)

### v2 — ML-powered fraud detection
- Three-layer detection: rules + ML + graph
- Kafka for all event streaming (async enrichment path)
- ML model (XGBoost) with automated retraining and shadow deployment
- Neo4j graph database for entity relationship analysis and ring detection
- Score fusion decision engine with configurable thresholds per merchant category
- A/B testing framework for model variants
- Fraud analyst dashboard with case investigation tools
- Automated feedback loop: chargeback data feeds back into model training
- Feature store for online/offline consistency
- Compliance audit trail (S3 + Athena) for 7-year retention
